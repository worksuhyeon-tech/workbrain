-- 0037 프로젝트 현재 상태(FR-09, DEC-86, 2026-11-10 사용자 결정)
-- 프로젝트 상세 '현재 상태' 카드: 요약 2~3줄 + 상태(진행 중 active·멈춤 paused·완료 done).
-- 기록을 프로젝트에 연결하면 처리 함수가 그 회의와 지금 상태를 비교해 '현재 상태 제안'(change_proposal, target_type project_state)을 만들고,
-- 사용자가 검토함이나 프로젝트 상세에서 승인해야 반영된다(승인 함수가 버전을 올림). 직접 쓰기·고치기는 wb_set_project_state.
-- 원격 SQL 확인 창이 만료되므로 DROP·DELETE 없이 쓴다. 칸·제약·색인은 없을 때만 더한다(다시 실행해도 같은 결과).

-- ① 칸: 이 상태의 근거 회의 시각(직접 쓰기면 그 시각)과 반영한 기록들
ALTER TABLE public.project_state_version ADD COLUMN IF NOT EXISTS basis_at timestamptz;
ALTER TABLE public.project_state_version ADD COLUMN IF NOT EXISTS source_record_ids uuid[] NOT NULL DEFAULT '{}';

-- ② 요약은 비어 있지 않은 600자 이내(화면 카드 2~3줄 + 여유)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'project_state_version_summary_ok') THEN
    ALTER TABLE public.project_state_version ADD CONSTRAINT project_state_version_summary_ok
      CHECK (summary IS NULL OR char_length(btrim(summary)) BETWEEN 1 AND 600);
  END IF;
END
$$;

-- ③ 프로젝트마다 승인 안 한 현재 상태 제안은 하나(새 기록을 연결하면 그 제안을 새 내용으로 고쳐 쓴다, DEC-86 ⑤)
CREATE UNIQUE INDEX IF NOT EXISTS change_proposal_state_pending_one
  ON public.change_proposal (target_id) WHERE target_type = 'project_state' AND status = 'pending';
CREATE INDEX IF NOT EXISTS project_state_version_project_version
  ON public.project_state_version (project_id, version DESC);

-- ④ 직접 쓰기·고치기(DEC-86 ⑧): 사용자가 본 버전(p_base_version)이 지금 버전과 같을 때만 새 버전을 쌓는다.
-- 다르면(다른 기기에서 먼저 바뀜) 쓰지 않고 conflict와 지금 버전을 돌려준다. 대기 중인 AI 제안은 기준 버전이 달라져 승인할 때 충돌로 돌아간다
CREATE OR REPLACE FUNCTION public.wb_set_project_state(p_project_id uuid, p_summary text, p_status text, p_base_version int)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_cur int;
  v_archived timestamptz;
  v_summary text := btrim(coalesce(p_summary, ''));
  v_records uuid[];
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;
  IF p_status IS NULL OR p_status NOT IN ('active','paused','done') THEN
    RAISE EXCEPTION 'WB400: 상태는 진행 중·멈춤·완료 중 하나입니다' USING ERRCODE = '22023';
  END IF;
  IF char_length(v_summary) NOT BETWEEN 1 AND 600 THEN
    RAISE EXCEPTION 'WB400: 요약은 1~600자로 적어 주세요' USING ERRCODE = '22023';
  END IF;

  SELECT current_state_version, archived_at INTO v_cur, v_archived
    FROM public.project WHERE id = p_project_id AND owner_id = v_uid FOR UPDATE;
  -- 남의 프로젝트는 '없음'과 똑같이 답한다
  IF NOT FOUND THEN
    RAISE EXCEPTION 'WB404: 프로젝트를 찾을 수 없습니다' USING ERRCODE = 'P0002';
  END IF;
  IF v_archived IS NOT NULL THEN
    RAISE EXCEPTION 'WB409: 아카이브한 프로젝트는 고칠 수 없습니다' USING ERRCODE = '40001';
  END IF;
  IF v_cur <> p_base_version THEN
    RETURN jsonb_build_object('result', 'conflict', 'current_version', v_cur);
  END IF;

  -- 직접 고쳐도 지금까지 반영한 기록은 그대로 이어 간다(같은 기록을 다시 제안하지 않게)
  SELECT source_record_ids INTO v_records FROM public.project_state_version
   WHERE project_id = p_project_id AND owner_id = v_uid AND version = v_cur;
  INSERT INTO public.project_state_version (owner_id, project_id, version, summary, status, applied_by, change_reason,
                                            basis_at, source_record_ids)
  VALUES (v_uid, p_project_id, v_cur + 1, v_summary, p_status, 'user', '직접 수정', now(), coalesce(v_records, '{}'));
  UPDATE public.project SET current_state_version = v_cur + 1 WHERE id = p_project_id;
  INSERT INTO public.activity_log (owner_id, entity_type, entity_id, action, actor)
  VALUES (v_uid, 'project', p_project_id, 'state:user', 'user');
  RETURN jsonb_build_object('result', 'saved', 'version', v_cur + 1);
END;
$$;

-- ⑤ 승인 함수(0035 그대로 + 현재 상태 승인 때 근거 회의 시각·반영한 기록을 남김)
CREATE OR REPLACE FUNCTION public.approve_proposal(
  p_proposal_id uuid,
  p_seen_revision int,
  p_context text DEFAULT 'inbox',
  p_edits jsonb DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_p public.change_proposal%ROWTYPE;
  v_task public.task%ROWTYPE;
  v_cur int;
  v_after jsonb;
  v_event uuid;
  v_applied int;
  v_is_new_task boolean;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;
  IF p_context NOT IN ('inbox','record') THEN
    RAISE EXCEPTION 'WB400: 알 수 없는 승인 위치 %', p_context USING ERRCODE = '22023';
  END IF;

  SELECT * INTO v_p FROM public.change_proposal WHERE id = p_proposal_id FOR UPDATE;
  -- 남의 제안은 '없음'과 똑같이 답해 존재 여부도 알리지 않는다
  IF NOT FOUND OR v_p.owner_id <> v_uid THEN
    RAISE EXCEPTION 'WB404: 제안을 찾을 수 없습니다' USING ERRCODE = 'P0002';
  END IF;

  -- 이미 처리된 제안: 다시 반영하지 않고 현재 결과만 알려 준다(멱등)
  IF v_p.status <> 'pending' THEN
    RETURN jsonb_build_object('result', 'already-' || v_p.status);
  END IF;
  -- 사용자가 본 뒤 제안이 바뀌었으면 다시 보게 한다
  IF v_p.revision <> p_seen_revision THEN
    RETURN jsonb_build_object('result', 'stale-revision', 'revision', v_p.revision);
  END IF;

  v_is_new_task := v_p.target_type = 'task' AND v_p.target_id IS NULL;
  -- 기록 상세에서는 새 할 일만 바로 추가한다. 상태·기한 변경은 검토함에서 전후 비교(DEC-23)
  IF p_context = 'record' AND NOT v_is_new_task THEN
    RAISE EXCEPTION 'WB403: 이 제안은 검토함에서 승인해 주세요' USING ERRCODE = '42501';
  END IF;
  IF p_edits IS NOT NULL AND NOT v_is_new_task THEN
    RAISE EXCEPTION 'WB400: 수정값은 새 할 일 제안에만 쓸 수 있습니다' USING ERRCODE = '22023';
  END IF;

  -- 본문이 남은 전사 근거가 하나도 없으면 승인할 수 없다(음성만 만료는 허용, DEC-21)
  IF NOT EXISTS (
    SELECT 1 FROM public.evidence_link e
    JOIN public.transcript_version t ON t.id = e.transcript_version_id
    WHERE e.proposal_id = v_p.id AND e.owner_id = v_uid AND t.content IS NOT NULL
  ) THEN
    UPDATE public.change_proposal SET status = 'quarantined' WHERE id = v_p.id;
    RETURN jsonb_build_object('result', 'evidence-missing');
  END IF;

  INSERT INTO public.approval_event (owner_id, target_type, proposal_id, seen_revision, result, actor, context)
  VALUES (v_uid, 'proposal', v_p.id, p_seen_revision, 'approved', 'user', p_context)
  RETURNING id INTO v_event;

  IF v_is_new_task THEN
    -- 사용자가 고친 값(제목·담당·기한·위치·받는 사람)을 AI 값 위에 덮는다. 허용 키만 읽는다
    v_after := v_p.after_json || coalesce(p_edits, '{}'::jsonb);
    PERFORM public.wb_mark_ai_write(v_event);
    INSERT INTO public.task (owner_id, project_id, area_id, title, priority, owner_kind, assignee,
                             due_date, due_time, due_ambiguous, due_text, recipients)
    VALUES (v_uid,
            (v_after->>'project_id')::uuid, (v_after->>'area_id')::uuid,
            v_after->>'title', (v_after->>'priority')::int,
            coalesce(v_after->>'owner_kind', 'unknown'), v_after->>'assignee',
            (v_after->>'due_date')::date, (v_after->>'due_time')::timestamptz,
            coalesce((v_after->>'due_ambiguous')::boolean, false), v_after->>'due_text',
            -- 받는 사람(DEC-77): 배열일 때만 읽고 빈 이름은 뺀다. 5명·40자 검사는 표 제약이 한다
            CASE WHEN jsonb_typeof(v_after->'recipients') = 'array'
                 THEN ARRAY(SELECT btrim(x) FROM jsonb_array_elements_text(v_after->'recipients') AS x WHERE btrim(x) <> '')
                 ELSE '{}'::text[] END)
    RETURNING version INTO v_applied;
    PERFORM public.wb_mark_ai_write(NULL);

  ELSIF v_p.target_type = 'task' THEN
    SELECT * INTO v_task FROM public.task WHERE id = v_p.target_id AND owner_id = v_uid FOR UPDATE;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'WB404: 대상 할 일을 찾을 수 없습니다' USING ERRCODE = 'P0002';
    END IF;
    IF v_task.version <> v_p.base_version THEN
      -- 승인 기록을 남기지 않고 충돌로 돌린다(새 차이를 다시 보여 줌)
      DELETE FROM public.approval_event WHERE id = v_event;
      UPDATE public.change_proposal SET status = 'conflict' WHERE id = v_p.id;
      RETURN jsonb_build_object('result', 'conflict', 'current_version', v_task.version);
    END IF;
    v_after := v_p.after_json;
    PERFORM public.wb_mark_ai_write(v_event);
    UPDATE public.task SET
      due_date      = CASE WHEN v_after ? 'due_date' THEN (v_after->>'due_date')::date ELSE due_date END,
      due_time      = CASE WHEN v_after ? 'due_time' THEN (v_after->>'due_time')::timestamptz ELSE due_time END,
      due_ambiguous = CASE WHEN v_after ? 'due_ambiguous' THEN (v_after->>'due_ambiguous')::boolean ELSE due_ambiguous END,
      due_text      = CASE WHEN v_after ? 'due_text' THEN v_after->>'due_text' ELSE due_text END,
      status        = CASE WHEN v_after ? 'status' THEN v_after->>'status' ELSE status END,
      priority      = CASE WHEN v_after ? 'priority' THEN (v_after->>'priority')::int ELSE priority END,
      title         = CASE WHEN v_after ? 'title' THEN v_after->>'title' ELSE title END,
      owner_kind    = CASE WHEN v_after ? 'owner_kind' THEN v_after->>'owner_kind' ELSE owner_kind END,
      assignee      = CASE WHEN v_after ? 'assignee' THEN v_after->>'assignee' ELSE assignee END,
      version       = version + 1
    WHERE id = v_task.id
    RETURNING version INTO v_applied;
    PERFORM public.wb_mark_ai_write(NULL);

  ELSIF v_p.target_type = 'project_state' THEN
    SELECT current_state_version INTO v_cur FROM public.project WHERE id = v_p.target_id AND owner_id = v_uid FOR UPDATE;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'WB404: 대상 프로젝트를 찾을 수 없습니다' USING ERRCODE = 'P0002';
    END IF;
    IF v_cur <> v_p.base_version THEN
      DELETE FROM public.approval_event WHERE id = v_event;
      UPDATE public.change_proposal SET status = 'conflict' WHERE id = v_p.id;
      RETURN jsonb_build_object('result', 'conflict', 'current_version', v_cur);
    END IF;
    v_applied := v_cur + 1;
    -- 근거 회의 시각·반영한 기록(DEC-86): 다음 제안이 '과거 회의'인지 가리고 같은 기록을 두 번 반영하지 않게 남긴다
    INSERT INTO public.project_state_version (owner_id, project_id, version, summary, status, applied_by, change_reason,
                                              basis_at, source_record_ids)
    VALUES (v_uid, v_p.target_id, v_applied, v_p.after_json->>'summary', v_p.after_json->>'status', 'ai-approved', v_p.rationale,
            (v_p.after_json->>'basis_at')::timestamptz,
            CASE WHEN jsonb_typeof(v_p.after_json->'records') = 'array'
                 THEN ARRAY(SELECT x::uuid FROM jsonb_array_elements_text(v_p.after_json->'records') AS x)
                 ELSE '{}'::uuid[] END);
    UPDATE public.project SET current_state_version = v_applied WHERE id = v_p.target_id;

  ELSE
    RAISE EXCEPTION 'WB501: % 제안 승인은 아직 지원하지 않습니다', v_p.target_type USING ERRCODE = '0A000';
  END IF;

  -- 승인한 항목의 근거 문장을 남긴다(DEC-45 A안). 전사가 만료돼도 근거를 볼 수 있게
  UPDATE public.evidence_link e
     SET quote = public.wb_evidence_quote(e.transcript_version_id, e.segment_ref -> 'idx')
   WHERE e.proposal_id = v_p.id AND e.owner_id = v_uid AND e.quote IS NULL;

  UPDATE public.approval_event SET applied_version = v_applied WHERE id = v_event;
  UPDATE public.change_proposal SET status = 'approved' WHERE id = v_p.id;
  INSERT INTO public.activity_log (owner_id, entity_type, entity_id, action, actor)
  VALUES (v_uid, 'change_proposal', v_p.id, 'approved:' || p_context, 'user');

  RETURN jsonb_build_object('result', 'approved', 'approval_event_id', v_event, 'applied_version', v_applied);
END;
$$;

-- ⑥ 처리 함수(서비스 권한)가 현재 상태 제안을 만들 때 쓰는 권한만: 지금 상태 읽기, 대기 제안을 새 내용으로 고쳐 쓰기(상태·대상은 못 바꿈)
GRANT SELECT ON public.project_state_version TO service_role;
GRANT UPDATE (analysis_run_id, base_version, before_json, after_json, rationale, revision) ON public.change_proposal TO service_role;

REVOKE ALL ON FUNCTION public.wb_set_project_state(uuid, text, text, int) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_set_project_state(uuid, text, text, int) TO authenticated;
REVOKE ALL ON FUNCTION public.approve_proposal(uuid, int, text, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.approve_proposal(uuid, int, text, jsonb) TO authenticated;
