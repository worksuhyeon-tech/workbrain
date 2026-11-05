-- FR-02 2단계 ① 기록↔프로젝트 연결(DEC-69, 2026-11-06)
-- 사용자가 기록 전체를 프로젝트에 직접 연결·해제·옮긴다(기록 상세·녹음 화면·트리 끌어서 옮기기).
-- 기존 source_project_link(FR-06·M16 항목 단위 연결 표)를 그대로 쓰고, 기록 전체 연결은 item_ref = {"scope":"record"}로 구분한다.
-- 녹음 화면에서 미리 고른 프로젝트는 올리기 직후(전사 전)에 연결해야 해서, 기록 전체 연결만 전사 버전 없이 허용한다.
-- 항목 단위 연결(AI 제안, FR-06)은 지금처럼 전사 버전·근거가 있어야 한다. 반복실행 연결은 하지 않는다(사용자 선택: 프로젝트만).
-- 앱은 이 표를 읽기만 하고(RLS own_select), 쓰기는 아래 함수로만 한다. 해제는 지우지 않고 status = 'rejected'로 남긴다(이력·다시 연결).

-- ── 1. 기록 전체 연결은 전사 버전 없이, 기록·프로젝트 쌍마다 하나 ──
ALTER TABLE public.source_project_link ALTER COLUMN transcript_version_id DROP NOT NULL;

ALTER TABLE public.source_project_link DROP CONSTRAINT IF EXISTS source_project_link_scope_check;
-- item_ref에 scope가 없으면 ->>가 NULL이라 'NULL = record'가 참·거짓이 아닌 NULL이 되고 CHECK를 통과해 버린다(반영 전 시험에서 발견) → coalesce
ALTER TABLE public.source_project_link ADD CONSTRAINT source_project_link_scope_check
  CHECK (transcript_version_id IS NOT NULL OR coalesce(item_ref->>'scope', '') = 'record');

-- 기록별 조회는 이 색인(맨 앞 source_record_id)으로, 프로젝트별 조회는 0100의 project_id 색인으로 한다
CREATE UNIQUE INDEX IF NOT EXISTS source_project_link_record_scope
  ON public.source_project_link (source_record_id, project_id)
  WHERE item_ref->>'scope' = 'record';

-- ── 2. 연결·해제 ──
-- 결과: linked / unlinked / unchanged. 내 기록·내 프로젝트가 아니면 '없음'과 똑같이 답한다(존재 여부를 알리지 않음).
-- 아카이브된 프로젝트에는 새로 연결하지 않는다(풀기는 허용). 기록과 프로젝트의 공간이 다르면 거부(DEC-22).
CREATE OR REPLACE FUNCTION public.wb_link_record_project(p_record_id uuid, p_project_id uuid, p_linked boolean DEFAULT true)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_record_ws uuid;
  v_project_ws uuid;
  v_archived_at timestamptz;
  v_link public.source_project_link%ROWTYPE;
  v_status text := CASE WHEN p_linked THEN 'confirmed' ELSE 'rejected' END;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;

  SELECT r.workspace_id INTO v_record_ws FROM public.source_record r WHERE r.id = p_record_id AND r.owner_id = v_uid;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'WB404: 기록을 찾을 수 없습니다' USING ERRCODE = 'P0002';
  END IF;
  SELECT p.workspace_id, p.archived_at INTO v_project_ws, v_archived_at
    FROM public.project p WHERE p.id = p_project_id AND p.owner_id = v_uid;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'WB404: 프로젝트를 찾을 수 없습니다' USING ERRCODE = 'P0002';
  END IF;
  IF p_linked AND v_archived_at IS NOT NULL THEN
    RAISE EXCEPTION 'WB409: 아카이브된 프로젝트에는 연결할 수 없습니다' USING ERRCODE = '55000';
  END IF;
  IF v_project_ws <> v_record_ws THEN
    RAISE EXCEPTION 'WB422: 다른 공간에는 연결할 수 없습니다(DEC-22)' USING ERRCODE = '23514';
  END IF;

  SELECT * INTO v_link FROM public.source_project_link l
   WHERE l.source_record_id = p_record_id AND l.project_id = p_project_id AND l.item_ref->>'scope' = 'record'
   FOR UPDATE;
  IF NOT FOUND THEN
    IF NOT p_linked THEN
      RETURN 'unchanged';
    END IF;
    INSERT INTO public.source_project_link (owner_id, source_record_id, transcript_version_id, item_ref, project_id, status, created_by)
    VALUES (v_uid, p_record_id, NULL, '{"scope":"record"}'::jsonb, p_project_id, 'confirmed', 'user');
  ELSIF v_link.status = v_status THEN
    RETURN 'unchanged';
  ELSE
    UPDATE public.source_project_link SET status = v_status, version = version + 1 WHERE id = v_link.id;
  END IF;

  INSERT INTO public.activity_log (owner_id, entity_type, entity_id, action, actor)
  VALUES (v_uid, 'source_record', p_record_id,
          CASE WHEN p_linked THEN 'linked:project:' ELSE 'unlinked:project:' END || p_project_id, 'user');
  RETURN CASE WHEN p_linked THEN 'linked' ELSE 'unlinked' END;
END;
$$;

-- ── 3. 옮기기(트리에서 기록을 다른 프로젝트로 끌어 놓기, DEC-24): 새 곳 연결과 옛 곳 해제를 한 번에 ──
-- 새 곳 연결이 실패하면(아카이브·남의 것) 옛 연결도 그대로 남는다(한 트랜잭션)
CREATE OR REPLACE FUNCTION public.wb_move_record_project(p_record_id uuid, p_from_project_id uuid, p_to_project_id uuid)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  IF p_from_project_id = p_to_project_id THEN
    RETURN 'unchanged';
  END IF;
  PERFORM public.wb_link_record_project(p_record_id, p_to_project_id, true);
  PERFORM public.wb_link_record_project(p_record_id, p_from_project_id, false);
  RETURN 'moved';
END;
$$;

REVOKE ALL ON FUNCTION public.wb_link_record_project(uuid, uuid, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_link_record_project(uuid, uuid, boolean) TO authenticated;
REVOKE ALL ON FUNCTION public.wb_move_record_project(uuid, uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_move_record_project(uuid, uuid, uuid) TO authenticated;
