-- approve_proposal 수정: 'AI 제안 승인' 표시(workbrain.actor)를 할 일 쓰기 순간으로 한정한다.
-- 0300에서는 트랜잭션 끝까지 남아, 같은 트랜잭션의 이후 수동 편집도 승인 이력으로 기록될 수 있었다(P0 시험 작성 중 발견).

-- 할 일 쓰기 직전에 켜고(p_event = 승인 기록 ID), 쓰고 나면 NULL로 바로 끈다.
CREATE OR REPLACE FUNCTION public.wb_mark_ai_write(p_event uuid)
RETURNS void
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  PERFORM set_config('workbrain.actor', CASE WHEN p_event IS NULL THEN '' ELSE 'ai-approved' END, true);
  PERFORM set_config('workbrain.approval_event_id', coalesce(p_event::text, ''), true);
END;
$$;

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

  UPDATE public.approval_event SET applied_version = v_applied WHERE id = v_event;
  UPDATE public.change_proposal SET status = 'approved' WHERE id = v_p.id;
  INSERT INTO public.activity_log (owner_id, entity_type, entity_id, action, actor)
  VALUES (v_uid, 'change_proposal', v_p.id, 'approved:' || p_context, 'user');

  RETURN jsonb_build_object('result', 'approved', 'approval_event_id', v_event, 'applied_version', v_applied);
END;
$$;

REVOKE ALL ON FUNCTION public.wb_mark_ai_write(uuid) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.approve_proposal(uuid, int, text, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.approve_proposal(uuid, int, text, jsonb) TO authenticated;
