-- 0045 비서와 결정 + 집중 기록(DEC-111, 2026-11-19 사용자 결정 '전부 추천대로', Design §3.21)
-- 다시 정할 일(지연)에 날짜가 아니라 결정을 받고, [2분 시작] 집중을 잰다.
-- ① task_decision: 내린 결정(시작·나눔·날짜 다시·기다림·내려놓음·끝남)과 그때 미룬 횟수·지난 날수. 주간 리뷰 '무엇이 왜 밀리나'용.
--    내려놓음(drop)은 할 일을 지우고 그 사본(snapshot)을 여기에 남긴다. 할 일 상태에 값을 더하면 오늘·모든 할 일·검색·알림·업무계획·웹이
--    모두 'done이 아니면 열린 일'로 세고 있어 여러 곳을 같이 고쳐야 해서. 되살리기는 사본으로 새 할 일을 만든다(wb_task_restore).
-- ② focus_session: 집중 한 번. 정한 분·실제로 한 초·끝난 방식·첫 동작을 끝냈는지(11/19 사용자: 시간을 정량으로 보려면).
--    처음 2주는 재기만 하고 예상 숫자는 보여 주지 않는다(DEC-111 C).
-- ③ 두 표의 쓰기는 SECURITY DEFINER 함수로만(운영 기록 표와 같은 규칙, M13 — 앱·웹은 내 행 읽기만).
-- ④ 미룬 횟수 = 이력에서 기한 날짜가 늦춰진 횟수(wb_task_defer_counts). 비서 함수(서비스 권한)도 같은 정의를 쓴다.
-- 집중·결정 기록의 보관 정책은 메모 v1.2 §10 '추후 정의'라 백업 표 목록(wb_backup_tables)에는 아직 넣지 않는다. 계정을 지우면 함께 지워진다.
-- 원격 SQL 확인 창이 만료되므로 DROP·DELETE 문 없이(함수 안 할 일 지우기는 제외) 다시 실행해도 같은 결과로 쓴다.

-- ① 결정
CREATE TABLE IF NOT EXISTS public.task_decision (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id          uuid NOT NULL REFERENCES public.app_user(id) ON DELETE CASCADE,
  task_id           uuid,                 -- FK 없음: 내려놓아 할 일이 지워져도 결정은 남는다
  kind              text NOT NULL CHECK (kind IN ('start', 'split', 'later', 'wait', 'drop', 'done')),
  defer_count       int  NOT NULL DEFAULT 0 CHECK (defer_count BETWEEN 0 AND 10000),
  days_late         int  CHECK (days_late BETWEEN -36600 AND 36600),
  snapshot          jsonb CHECK (snapshot IS NULL OR (jsonb_typeof(snapshot) = 'object' AND octet_length(snapshot::text) <= 20000)),
  restored_task_id  uuid,
  restored_at       timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT task_decision_drop_snapshot CHECK ((kind = 'drop') = (snapshot IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS task_decision_owner_idx ON public.task_decision (owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS task_decision_dropped_idx ON public.task_decision (owner_id) WHERE kind = 'drop' AND restored_at IS NULL;

-- ② 집중
CREATE TABLE IF NOT EXISTS public.focus_session (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id     uuid NOT NULL REFERENCES public.app_user(id) ON DELETE CASCADE,
  task_id      uuid REFERENCES public.task(id) ON DELETE SET NULL,
  step         text CHECK (char_length(step) <= 200),
  planned_min  int  NOT NULL CHECK (planned_min BETWEEN 1 AND 180),
  actual_sec   int  NOT NULL CHECK (actual_sec BETWEEN 0 AND 86400),
  started_at   timestamptz NOT NULL,
  ended_at     timestamptz NOT NULL,
  ended_how    text NOT NULL CHECK (ended_how IN ('done', 'here')),   -- done = 할 일까지 끝냄, here = 여기까지·그만
  step_done    boolean,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT focus_session_order CHECK (ended_at >= started_at)
);
CREATE INDEX IF NOT EXISTS focus_session_owner_idx ON public.focus_session (owner_id, started_at DESC);
CREATE INDEX IF NOT EXISTS focus_session_task_idx ON public.focus_session (task_id);

-- ③ 권한: 앱·웹은 내 행 읽기만. 비서 함수(서비스 권한)는 읽기만
ALTER TABLE public.task_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.focus_session ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'task_decision' AND policyname = 'own_select') THEN
    CREATE POLICY own_select ON public.task_decision FOR SELECT TO authenticated USING (owner_id = (SELECT auth.uid()));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'focus_session' AND policyname = 'own_select') THEN
    CREATE POLICY own_select ON public.focus_session FOR SELECT TO authenticated USING (owner_id = (SELECT auth.uid()));
  END IF;
END
$$;
GRANT SELECT ON public.task_decision, public.focus_session TO authenticated;
GRANT SELECT ON public.task_decision, public.focus_session TO service_role;

-- ④ 미룬 횟수: 이력(버전 순)에서 기한 날짜가 앞 버전보다 늦어진 횟수. 기한을 처음 정하거나 지운 것은 세지 않는다.
-- 부른 사람 권한으로 읽는다(앱은 RLS로 내 이력만, 비서 함수는 서비스 권한)
CREATE OR REPLACE FUNCTION public.wb_task_defer_counts(p_ids uuid[])
RETURNS TABLE (task_id uuid, defers int, revisions int)
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT s.tid,
         (count(*) FILTER (WHERE s.prev_due IS NOT NULL AND s.due IS NOT NULL AND s.due > s.prev_due))::int,
         count(*)::int
    FROM (
      SELECT r.task_id AS tid,
             (r.snapshot ->> 'due_date')::date AS due,
             lag((r.snapshot ->> 'due_date')::date) OVER (PARTITION BY r.task_id ORDER BY r.version, r.created_at) AS prev_due
        FROM public.task_revision r
       WHERE r.task_id = ANY (p_ids)
    ) s
   GROUP BY s.tid;
$$;
REVOKE ALL ON FUNCTION public.wb_task_defer_counts(uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_task_defer_counts(uuid[]) TO authenticated, service_role;

-- 지난 날수: 오늘(서울) - 기한 날짜(시각만 있으면 그 서울 날짜). 기한이 없으면 NULL
CREATE OR REPLACE FUNCTION public.wb_task_days_late(p_due_date date, p_due_time timestamptz)
RETURNS int
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT (now() AT TIME ZONE 'Asia/Seoul')::date - coalesce(p_due_date, (p_due_time AT TIME ZONE 'Asia/Seoul')::date);
$$;
REVOKE ALL ON FUNCTION public.wb_task_days_late(date, timestamptz) FROM PUBLIC, anon, authenticated;

-- ⑤ 결정 남기기(내려놓음 빼고). 앱은 할 일을 바꾸기 전에 부른다 — 그때의 미룬 횟수·지난 날수를 남기려고
CREATE OR REPLACE FUNCTION public.wb_task_decide(p_task_id uuid, p_kind text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_t   public.task%ROWTYPE;
  v_id  uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요해요' USING ERRCODE = '42501';
  END IF;
  IF p_kind IS NULL OR p_kind NOT IN ('start', 'split', 'later', 'wait', 'done') THEN
    RAISE EXCEPTION 'WB400: 결정 종류가 올바르지 않아요' USING ERRCODE = '22023';
  END IF;
  SELECT * INTO v_t FROM public.task t WHERE t.id = p_task_id AND t.owner_id = v_uid;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'WB404: 할 일을 찾을 수 없어요' USING ERRCODE = 'P0002';
  END IF;
  INSERT INTO public.task_decision (owner_id, task_id, kind, defer_count, days_late)
  VALUES (v_uid, v_t.id, p_kind,
          coalesce((SELECT d.defers FROM public.wb_task_defer_counts(ARRAY[v_t.id]) d), 0),
          public.wb_task_days_late(v_t.due_date, v_t.due_time))
  RETURNING id INTO v_id;
  RETURN v_id;
END;
$$;
REVOKE ALL ON FUNCTION public.wb_task_decide(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_task_decide(uuid, text) TO authenticated;

-- 결정 되돌리기(앱 알림의 [되돌리기]): 내 결정 가운데 내려놓음이 아니고 10분 안에 남긴 것만 지운다
CREATE OR REPLACE FUNCTION public.wb_task_decide_undo(p_decision_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요해요' USING ERRCODE = '42501';
  END IF;
  DELETE FROM public.task_decision d
   WHERE d.id = p_decision_id AND d.owner_id = auth.uid() AND d.kind <> 'drop' AND d.created_at > now() - interval '10 minutes';
  RETURN FOUND;
END;
$$;
REVOKE ALL ON FUNCTION public.wb_task_decide_undo(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_task_decide_undo(uuid) TO authenticated;

-- ⑥ 내려놓기: 버전이 같을 때만(다른 기기에서 먼저 바꿨으면 NULL, M18). 사본(하위·출처·근거 문장 포함)을 남기고 할 일을 지운다(하위는 함께 지워짐).
-- 캘린더 일정은 앱이 지우기 전에 먼저 처리한다(일정 연결이 할 일과 함께 지워지므로)
CREATE OR REPLACE FUNCTION public.wb_task_drop(p_task_id uuid, p_version int)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid  uuid := auth.uid();
  v_t    public.task%ROWTYPE;
  v_src  jsonb;
  v_snap jsonb;
  v_id   uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요해요' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO v_t FROM public.task t WHERE t.id = p_task_id AND t.owner_id = v_uid FOR UPDATE;
  IF NOT FOUND OR v_t.version <> p_version THEN
    RETURN NULL;
  END IF;
  -- 출처: AI 승인으로 생긴 할 일이면 그 기록 제목·때와 근거 문장
  SELECT jsonb_build_object('record_id', sr.id, 'title', sr.title, 'at', coalesce(sr.occurred_at, sr.ingested_at),
                            'quote', (SELECT el.quote FROM public.evidence_link el
                                       WHERE el.proposal_id = cp.id AND el.quote IS NOT NULL ORDER BY el.created_at LIMIT 1))
    INTO v_src
    FROM public.task_revision r
    JOIN public.approval_event ae ON ae.id = r.approval_event_id
    JOIN public.change_proposal cp ON cp.id = ae.proposal_id
    JOIN public.analysis_run ar ON ar.id = cp.analysis_run_id
    JOIN public.transcript_version tv ON tv.id = ar.input_transcript_version_id
    JOIN public.source_record sr ON sr.id = tv.source_record_id
   WHERE r.task_id = v_t.id AND r.actor = 'ai-approved'
   ORDER BY r.version
   LIMIT 1;
  v_snap := jsonb_build_object(
    'title', v_t.title, 'priority', v_t.priority, 'project_id', v_t.project_id, 'area_id', v_t.area_id,
    'owner_kind', v_t.owner_kind, 'assignee', v_t.assignee,
    'due_date', v_t.due_date, 'due_time', v_t.due_time, 'due_ambiguous', v_t.due_ambiguous, 'due_text', v_t.due_text,
    'recipients', to_jsonb(v_t.recipients), 'parent_id', v_t.parent_id, 'repeat_rule', v_t.repeat_rule,
    'created_at', v_t.created_at, 'source', v_src,
    'subtasks', coalesce((SELECT jsonb_agg(jsonb_build_object('title', c.title, 'done', c.status = 'done') ORDER BY c.created_at)
                            FROM public.task c WHERE c.parent_id = v_t.id), '[]'::jsonb));
  INSERT INTO public.task_decision (owner_id, task_id, kind, defer_count, days_late, snapshot)
  VALUES (v_uid, v_t.id, 'drop',
          coalesce((SELECT d.defers FROM public.wb_task_defer_counts(ARRAY[v_t.id]) d), 0),
          public.wb_task_days_late(v_t.due_date, v_t.due_time), v_snap)
  RETURNING id INTO v_id;
  DELETE FROM public.task t WHERE t.id = v_t.id;
  RETURN v_id;
END;
$$;
REVOKE ALL ON FUNCTION public.wb_task_drop(uuid, int) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_task_drop(uuid, int) TO authenticated;

-- ⑦ 되살리기: 사본으로 새 할 일(+하위)을 만든다. 상위가 남아 있으면 그 하위로, 원래 곳이 보관·삭제됐으면 '정리 안 됨'으로.
-- 미리 알림은 되살리지 않는다(지난 시각에 울리지 않게). 이미 되살렸으면 그 할 일 ID를 그대로 돌려준다
CREATE OR REPLACE FUNCTION public.wb_task_restore(p_decision_id uuid)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid    uuid := auth.uid();
  v_d      public.task_decision%ROWTYPE;
  s        jsonb;
  v_parent uuid;
  v_proj   uuid;
  v_area   uuid;
  v_new    uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요해요' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO v_d FROM public.task_decision d WHERE d.id = p_decision_id AND d.owner_id = v_uid AND d.kind = 'drop' FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'WB404: 내려놓은 할 일을 찾을 수 없어요' USING ERRCODE = 'P0002';
  END IF;
  IF v_d.restored_at IS NOT NULL THEN
    RETURN v_d.restored_task_id;
  END IF;
  s := v_d.snapshot;
  SELECT p.id INTO v_parent FROM public.task p
   WHERE p.id = nullif(s ->> 'parent_id', '')::uuid AND p.owner_id = v_uid AND p.parent_id IS NULL;
  IF v_parent IS NULL THEN
    SELECT p.id INTO v_proj FROM public.project p
     WHERE p.id = nullif(s ->> 'project_id', '')::uuid AND p.owner_id = v_uid AND p.archived_at IS NULL;
    SELECT a.id INTO v_area FROM public.area a
     WHERE a.id = nullif(s ->> 'area_id', '')::uuid AND a.owner_id = v_uid AND a.archived_at IS NULL;
  END IF;
  INSERT INTO public.task (owner_id, title, priority, project_id, area_id, owner_kind, assignee,
                           due_date, due_time, due_ambiguous, due_text, recipients, parent_id, repeat_rule)
  VALUES (v_uid, s ->> 'title',
          CASE WHEN v_parent IS NULL THEN (s ->> 'priority')::int END,
          v_proj, v_area,
          coalesce(s ->> 'owner_kind', 'me'), s ->> 'assignee',
          (s ->> 'due_date')::date, (s ->> 'due_time')::timestamptz,
          coalesce((s ->> 'due_ambiguous')::boolean, false), s ->> 'due_text',
          coalesce(ARRAY(SELECT jsonb_array_elements_text(coalesce(s -> 'recipients', '[]'::jsonb))), '{}'::text[]),
          v_parent,
          CASE WHEN v_parent IS NULL AND (s ->> 'due_date') IS NOT NULL THEN s ->> 'repeat_rule' END)
  RETURNING id INTO v_new;
  IF v_parent IS NULL THEN
    INSERT INTO public.task (owner_id, title, parent_id, status)
    SELECT v_uid, c ->> 'title', v_new, CASE WHEN (c ->> 'done')::boolean THEN 'done' ELSE 'open' END
      FROM jsonb_array_elements(coalesce(s -> 'subtasks', '[]'::jsonb)) c;
  END IF;
  UPDATE public.task_decision d SET restored_at = now(), restored_task_id = v_new WHERE d.id = v_d.id;
  RETURN v_new;
END;
$$;
REVOKE ALL ON FUNCTION public.wb_task_restore(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_task_restore(uuid) TO authenticated;

-- ⑧ 집중 남기기: 할 일이 그새 지워졌으면 할 일 없이 남긴다. 시작 시각은 지금부터 하루 안, 실제 초는 시작~지금 사이로 맞춘다
CREATE OR REPLACE FUNCTION public.wb_focus_log(
  p_task_id uuid, p_step text, p_planned_min int, p_actual_sec int, p_started_at timestamptz, p_ended_how text, p_step_done boolean)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid  uuid := auth.uid();
  v_task uuid;
  v_id   uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요해요' USING ERRCODE = '42501';
  END IF;
  IF p_started_at IS NULL OR p_started_at < now() - interval '1 day' OR p_started_at > now() + interval '5 minutes' THEN
    RAISE EXCEPTION 'WB400: 시작 시각이 올바르지 않아요' USING ERRCODE = '22023';
  END IF;
  IF p_planned_min IS NULL OR p_planned_min NOT BETWEEN 1 AND 180 OR p_actual_sec IS NULL OR p_actual_sec < 0
     OR p_ended_how IS NULL OR p_ended_how NOT IN ('done', 'here') THEN
    RAISE EXCEPTION 'WB400: 집중 기록 값이 올바르지 않아요' USING ERRCODE = '22023';
  END IF;
  SELECT t.id INTO v_task FROM public.task t WHERE t.id = p_task_id AND t.owner_id = v_uid;
  INSERT INTO public.focus_session (owner_id, task_id, step, planned_min, actual_sec, started_at, ended_at, ended_how, step_done)
  VALUES (v_uid, v_task, nullif(left(btrim(coalesce(p_step, '')), 200), ''), p_planned_min,
          least(p_actual_sec, greatest(0, extract(epoch FROM now() - p_started_at)::int) + 60, 86400),
          least(p_started_at, now()), now(), p_ended_how, p_step_done)
  RETURNING id INTO v_id;
  RETURN v_id;
END;
$$;
REVOKE ALL ON FUNCTION public.wb_focus_log(uuid, text, int, int, timestamptz, text, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_focus_log(uuid, text, int, int, timestamptz, text, boolean) TO authenticated;
