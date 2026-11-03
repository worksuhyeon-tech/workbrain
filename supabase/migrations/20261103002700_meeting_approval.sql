-- 회의록 승인(FR-07)과 승인 회의록 3년·근거 문장 저장(Q5, DEC-45) — Design v0.1.9 §3.9, DEC-61
-- ① 승인 보관 기본값 1→3년. 지금 계정 값도 3으로 바꾼다(앱에 보관 기간 화면이 없어 사용자가 고른 값이 아님)
-- ② evidence_link.quote: 승인한 항목의 근거 앞 2구간 문장(300자). 승인 전 후보·초안에는 채우지 않는다
-- ③ approve_meeting: 회의록 버전 승인은 이 함수로만 한다. 승인본은 트리거로 고칠 수 없다
-- ④ approve_proposal: 할 일 후보를 승인할 때 근거 문장도 함께 저장한다(0017의 나머지 동작은 그대로)

-- ── ① 승인 회의록 보관 3년 ──
ALTER TABLE public.settings ALTER COLUMN retention_knowledge_years SET DEFAULT 3;
UPDATE public.settings SET retention_knowledge_years = 3, updated_at = now() WHERE retention_knowledge_years = 1;

-- ── ② 근거 문장 칸 ──
ALTER TABLE public.evidence_link ADD COLUMN IF NOT EXISTS quote text CHECK (char_length(quote) <= 300);

-- 근거 구간 번호(jsonb 배열)에서 번호가 앞선 2구간의 전사 문장을 이어 300자까지 돌려준다.
-- 구간 번호는 전사 segments 배열의 위치다(처리 함수가 그렇게 매김). 전사 본문이 이미 없으면 null
CREATE OR REPLACE FUNCTION public.wb_evidence_quote(p_transcript_version_id uuid, p_idx jsonb)
RETURNS text
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT nullif(left(string_agg(t.segments -> i.n ->> 'text', ' ' ORDER BY i.n), 300), '')
  FROM public.transcript_version t
  CROSS JOIN LATERAL (
    SELECT DISTINCT value::int AS n
    FROM jsonb_array_elements_text(CASE WHEN jsonb_typeof(p_idx) = 'array' THEN p_idx ELSE '[]'::jsonb END)
    WHERE value ~ '^[0-9]{1,6}$'
    ORDER BY 1
    LIMIT 2
  ) i
  WHERE t.id = p_transcript_version_id
    AND t.content IS NOT NULL;
$$;

-- ── ③ 회의록 승인 ──
-- 결과(jsonb.result): approved | already-approved | stale-version | no-document | evidence-missing
CREATE OR REPLACE FUNCTION public.approve_meeting(p_meeting_version_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_mv public.meeting_version%ROWTYPE;
  v_record uuid;
  v_years int;
  v_now timestamptz := now();
  v_expires timestamptz;
  v_event uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;

  -- 두 기기에서 같은 버전을 동시에 눌러도 잠금 순서대로 처리되어 승인은 1회다
  SELECT * INTO v_mv FROM public.meeting_version WHERE id = p_meeting_version_id FOR UPDATE;
  -- 남의 회의록은 '없음'과 똑같이 답해 존재 여부도 알리지 않는다
  IF NOT FOUND OR v_mv.owner_id <> v_uid THEN
    RAISE EXCEPTION 'WB404: 회의록을 찾을 수 없습니다' USING ERRCODE = 'P0002';
  END IF;
  IF v_mv.state = 'approved' THEN
    RETURN jsonb_build_object('result', 'already-approved', 'approved_at', v_mv.approved_at);
  END IF;

  SELECT t.source_record_id INTO v_record FROM public.transcript_version t WHERE t.id = v_mv.transcript_version_id;
  -- 사용자가 본 뒤 같은 기록에 새 버전이 생겼으면(다른 기기에서 다시 만들기 등) 그 화면으로 승인하지 않는다(M14)
  IF EXISTS (
    SELECT 1 FROM public.meeting_version m
    JOIN public.transcript_version t ON t.id = m.transcript_version_id
    WHERE t.source_record_id = v_record AND m.owner_id = v_uid
      AND m.id <> v_mv.id AND m.created_at > v_mv.created_at
  ) THEN
    RETURN jsonb_build_object('result', 'stale-version');
  END IF;
  -- 승인하는 것은 Word로 나가는 회의록 문서다. 문서는 '회의록 만들기'를 눌러야 생긴다(DEC-56)
  IF v_mv.document IS NULL THEN
    RETURN jsonb_build_object('result', 'no-document');
  END IF;
  -- 분석에 쓴 전사 본문이 만료로 없으면 근거를 확인할 수 없어 승인하지 않는다(DEC-21)
  IF NOT EXISTS (SELECT 1 FROM public.transcript_version t WHERE t.id = v_mv.transcript_version_id AND t.content IS NOT NULL) THEN
    RETURN jsonb_build_object('result', 'evidence-missing');
  END IF;

  SELECT s.retention_knowledge_years INTO v_years FROM public.settings s WHERE s.user_id = v_uid;
  v_expires := v_now + make_interval(years => coalesce(v_years, 3));

  -- 같은 기록의 이전 승인본은 지우지 않고 '대체됨'만 표시한다
  UPDATE public.meeting_version m SET superseded_at = v_now
    FROM public.transcript_version t
   WHERE t.id = m.transcript_version_id AND t.source_record_id = v_record
     AND m.owner_id = v_uid AND m.state = 'approved' AND m.superseded_at IS NULL AND m.id <> v_mv.id;

  UPDATE public.meeting_version
     SET state = 'approved', approved_at = v_now, retention_expires_at = v_expires
   WHERE id = v_mv.id;

  INSERT INTO public.approval_event (owner_id, target_type, meeting_version_id, result, actor, context)
  VALUES (v_uid, 'meeting', v_mv.id, 'approved', 'user', 'record')
  RETURNING id INTO v_event;

  -- 결정·미결의 근거 문장을 승인 회의록과 같은 수명으로 남긴다(DEC-45 A안). 근거 구간이 없는 항목은 건너뛴다
  INSERT INTO public.evidence_link (owner_id, meeting_version_id, transcript_version_id, segment_ref, quote)
  SELECT v_uid, v_mv.id, v_mv.transcript_version_id,
         jsonb_build_object('item', k.item, 'n', x.o - 1, 'idx', x.e -> 'segments'),
         public.wb_evidence_quote(v_mv.transcript_version_id, x.e -> 'segments')
    FROM unnest(ARRAY['decisions', 'open_questions']) AS k(item)
   CROSS JOIN LATERAL jsonb_array_elements(
           CASE WHEN jsonb_typeof(v_mv.fields -> k.item) = 'array' THEN v_mv.fields -> k.item ELSE '[]'::jsonb END
         ) WITH ORDINALITY AS x(e, o)
   WHERE jsonb_typeof(x.e -> 'segments') = 'array' AND jsonb_array_length(x.e -> 'segments') > 0;

  INSERT INTO public.activity_log (owner_id, entity_type, entity_id, action, actor)
  VALUES (v_uid, 'meeting_version', v_mv.id, 'approved:record', 'user');

  RETURN jsonb_build_object('result', 'approved', 'approval_event_id', v_event,
                            'approved_at', v_now, 'retention_expires_at', v_expires);
END;
$$;

-- 승인본 보호: 승인한 회의록의 내용·지침 정보·승인 시각·전사 연결은 바꿀 수 없다(FR-11).
-- 바뀔 수 있는 것은 superseded_at·보관 기한 값, 그리고 보관 기한이 지난 뒤의 정리뿐이다.
-- 다시 만들기는 처리 함수가 새 초안 버전을 만든다(DEC-61)
CREATE OR REPLACE FUNCTION public.wb_meeting_approved_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF OLD.state = 'approved'
     AND now() < OLD.retention_expires_at
     AND (NEW.state IS DISTINCT FROM OLD.state
          OR NEW.fields IS DISTINCT FROM OLD.fields
          OR NEW.document IS DISTINCT FROM OLD.document
          OR NEW.guideline_rev IS DISTINCT FROM OLD.guideline_rev
          OR NEW.guideline_imported_at IS DISTINCT FROM OLD.guideline_imported_at
          OR NEW.guideline_input_hash IS DISTINCT FROM OLD.guideline_input_hash
          OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
          OR NEW.transcript_version_id IS DISTINCT FROM OLD.transcript_version_id
          OR NEW.parent_version_id IS DISTINCT FROM OLD.parent_version_id
          OR NEW.owner_id IS DISTINCT FROM OLD.owner_id) THEN
    RAISE EXCEPTION 'WB409: 승인한 회의록은 바꿀 수 없습니다(다시 만들면 새 초안이 생깁니다)' USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS wb_meeting_approved_guard ON public.meeting_version;
CREATE TRIGGER wb_meeting_approved_guard BEFORE UPDATE ON public.meeting_version
  FOR EACH ROW EXECUTE FUNCTION public.wb_meeting_approved_guard();

-- ── ④ 할 일 후보 승인 + 근거 문장 저장 (0017 본문에 근거 문장 저장만 더함) ──
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
    -- 사용자가 고친 값(제목·담당·기한·위치)을 AI 값 위에 덮는다. 허용 키만 읽는다
    v_after := v_p.after_json || coalesce(p_edits, '{}'::jsonb);
    PERFORM public.wb_mark_ai_write(v_event);
    INSERT INTO public.task (owner_id, project_id, area_id, title, priority, owner_kind, assignee,
                             due_date, due_time, due_ambiguous, due_text)
    VALUES (v_uid,
            (v_after->>'project_id')::uuid, (v_after->>'area_id')::uuid,
            v_after->>'title', (v_after->>'priority')::int,
            coalesce(v_after->>'owner_kind', 'unknown'), v_after->>'assignee',
            (v_after->>'due_date')::date, (v_after->>'due_time')::timestamptz,
            coalesce((v_after->>'due_ambiguous')::boolean, false), v_after->>'due_text')
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
    INSERT INTO public.project_state_version (owner_id, project_id, version, summary, status, applied_by, change_reason)
    VALUES (v_uid, v_p.target_id, v_applied, v_p.after_json->>'summary', v_p.after_json->>'status', 'ai-approved', v_p.rationale);
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

-- 이미 승인한 할 일 후보의 근거 문장을 한 번 채운다(지금은 전사가 모두 남아 있음)
UPDATE public.evidence_link e
   SET quote = public.wb_evidence_quote(e.transcript_version_id, e.segment_ref -> 'idx')
  FROM public.change_proposal p
 WHERE p.id = e.proposal_id AND p.status = 'approved' AND e.quote IS NULL;

-- 함수 실행 권한: 승인 함수만 로그인 사용자에게 연다. 근거 문장·트리거 함수는 승인 함수 안에서만 쓴다
REVOKE ALL ON FUNCTION public.wb_evidence_quote(uuid, jsonb) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_meeting_approved_guard() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.approve_meeting(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.approve_meeting(uuid) TO authenticated;
