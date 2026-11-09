-- 0035 할 일 받는 사람(DEC-77, 2026-11-09 사용자 결정): task.recipients + 승인 함수·이력·권한
-- AI가 할 일 후보와 함께 받는 사람(메일·공유·전달·알려 줄 상대)을 뽑고, 할 일 줄 제목 옆 '→ 박서연 외 1' 칩으로 보여 준다.
-- 원격 SQL 확인 창이 만료되므로 DROP·DELETE 없이 쓴다. 제약은 없을 때만 더한다(다시 실행해도 같은 결과).

-- ① 칸: 받는 사람 이름 목록. 없으면 빈 배열(칩이 안 보임)
ALTER TABLE public.task ADD COLUMN IF NOT EXISTS recipients text[] NOT NULL DEFAULT '{}';

-- ② 검사: 최대 5명, 이름마다 공백이 아닌 1~40자
CREATE OR REPLACE FUNCTION public.wb_recipients_ok(p_names text[])
RETURNS boolean
LANGUAGE sql
IMMUTABLE
SET search_path = ''
AS $$
  SELECT cardinality(p_names) <= 5
     AND NOT EXISTS (SELECT 1 FROM unnest(p_names) AS n WHERE n IS NULL OR length(btrim(n)) NOT BETWEEN 1 AND 40);
$$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'task_recipients_ok' AND conrelid = 'public.task'::regclass) THEN
    ALTER TABLE public.task ADD CONSTRAINT task_recipients_ok CHECK (public.wb_recipients_ok(recipients));
  END IF;
END;
$$;

-- ③ 권한: 앱이 할 일을 넣고 고칠 때 받는 사람도 쓴다(열 단위 권한, 0002와 같은 방식)
GRANT INSERT (recipients), UPDATE (recipients) ON public.task TO authenticated;

-- ④ 이력 스냅샷에 받는 사람도 남긴다(M18: 덮어쓰지 않는 이력)
CREATE OR REPLACE FUNCTION public.wb_task_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_actor text := coalesce(nullif(current_setting('workbrain.actor', true), ''), 'user');
  v_event uuid := nullif(current_setting('workbrain.approval_event_id', true), '')::uuid;
BEGIN
  INSERT INTO public.task_revision (owner_id, task_id, version, snapshot, actor, approval_event_id)
  VALUES (NEW.owner_id, NEW.id, NEW.version,
          jsonb_build_object('title', NEW.title, 'status', NEW.status, 'priority', NEW.priority,
                             'project_id', NEW.project_id, 'area_id', NEW.area_id,
                             'owner_kind', NEW.owner_kind, 'assignee', NEW.assignee,
                             'due_date', NEW.due_date, 'due_time', NEW.due_time,
                             'due_ambiguous', NEW.due_ambiguous, 'due_text', NEW.due_text,
                             'recipients', to_jsonb(NEW.recipients)),
          v_actor, v_event);
  RETURN NEW;
END;
$$;

-- ⑤ 받는 사람 고르기 칩(DEC-77): 그 기록의 참석자 + 이름 붙은 화자(번호 화자·'(나)'는 뺌, '님'은 뗌).
--    호출한 사용자 권한으로 읽어(SECURITY INVOKER) 남의 기록이면 빈 목록이다(RLS)
CREATE OR REPLACE FUNCTION public.wb_record_people(p_record_id uuid)
RETURNS text[]
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
  WITH names AS (
    SELECT btrim(a) AS name FROM public.source_record r, unnest(r.attendees) AS a WHERE r.id = p_record_id
    UNION
    SELECT btrim(s->>'speaker') FROM public.transcript_version t, jsonb_array_elements(t.segments) AS s
     WHERE t.source_record_id = p_record_id AND jsonb_typeof(t.segments) = 'array'
  ), cleaned AS (
    SELECT DISTINCT left(regexp_replace(name, '\s*님$', ''), 40) AS name FROM names
     WHERE name IS NOT NULL AND name <> ''
       AND name !~* '^(참석자|화자|발화자|speaker)\s*[0-9]+$'
       AND name !~ '\(나\)\s*$'
  )
  SELECT coalesce(array_agg(name ORDER BY name), '{}'::text[]) FROM (SELECT name FROM cleaned WHERE name <> '' LIMIT 30) AS x;
$$;

-- ⑥ 할 일 후보 승인: 0027 본문에 새 할 일의 받는 사람만 더함(나머지 동작 그대로)
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

-- 표 제약은 쓰는 사람 권한으로 검사 함수를 부르므로 로그인 사용자·처리 함수에 실행 권한을 준다(앱이 할 일을 직접 넣음)
REVOKE ALL ON FUNCTION public.wb_recipients_ok(text[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_recipients_ok(text[]) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.wb_record_people(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_record_people(uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.approve_proposal(uuid, int, text, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.approve_proposal(uuid, int, text, jsonb) TO authenticated;
