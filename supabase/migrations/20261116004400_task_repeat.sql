-- 0044 할 일 반복(DEC-109, 2026-11-16 사용자 결정, Design §3.20): task.repeat_rule + 다음 회차 만들기 트리거
-- 반복 규칙이 있는 상위 할 일을 '완료'하면 서버가 다음 회차를 새 할 일로 만든다(PC 웹·앱 어디서 끝내도 같게).
-- 끝낸 할 일은 완료로 남고(오늘 완료·업무계획 보고), 규칙은 새 할 일로 옮겨 간다. 완료를 되돌리면 손대지 않은 다음 회차를 지운다.
-- 원격 SQL 확인 창이 만료되므로 DROP을 쓰지 않는다(트리거는 CREATE OR REPLACE). 제약은 없을 때만 더한다(다시 실행해도 같은 결과).

-- ① 칸: 반복 규칙(RFC 5545 RRULE 일부, NULL = 반복 안 함) · 이 할 일을 끝내며 만든 다음 회차(되돌리기용, 외래 키 없음 —
--    다음 회차를 지울 때 이 행을 고치는 연쇄 동작이 같은 UPDATE 안에서 부딪히지 않게)
ALTER TABLE public.task ADD COLUMN IF NOT EXISTS repeat_rule text;
ALTER TABLE public.task ADD COLUMN IF NOT EXISTS repeat_next_id uuid;

-- ② 모양 검사: FREQ=DAILY|WEEKLY|MONTHLY|YEARLY, INTERVAL 1~99, BYDAY(요일 1~7개)는 WEEKLY일 때만
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'task_repeat_rule_shape' AND conrelid = 'public.task'::regclass) THEN
    ALTER TABLE public.task ADD CONSTRAINT task_repeat_rule_shape CHECK (
      repeat_rule IS NULL OR (
        repeat_rule ~ '^FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;INTERVAL=[1-9][0-9]?)?(;BYDAY=(MO|TU|WE|TH|FR|SA|SU)(,(MO|TU|WE|TH|FR|SA|SU)){0,6})?$'
        AND (repeat_rule !~ 'BYDAY=' OR repeat_rule ~ '^FREQ=WEEKLY')
      )
    );
  END IF;
END;
$$;

-- ③ 권한: 앱·웹은 규칙만 쓴다(다음 회차 칸은 트리거만)
GRANT INSERT (repeat_rule), UPDATE (repeat_rule) ON public.task TO authenticated;

-- ④ 다음 회차 날짜(기한 기준, DEC-109 ③): 기한 다음 첫 회차, 오늘보다 앞이면 오늘 이후 첫 회차까지 건너뛴다(오늘 자체는 됨).
--    주 단위는 월요일 시작 주(DEC-31)에서 INTERVAL 주마다 BYDAY 요일(없으면 기한의 요일). 월·년은 기한에서 n개월·n년(말일은 그달 마지막 날)
CREATE OR REPLACE FUNCTION public.wb_repeat_next(p_rule text, p_due date, p_today date)
RETURNS date
LANGUAGE plpgsql
IMMUTABLE
SET search_path = ''
AS $$
DECLARE
  v_freq text := substring(p_rule from 'FREQ=([A-Z]+)');
  v_int int := coalesce(substring(p_rule from 'INTERVAL=([0-9]+)')::int, 1);
  v_days text := substring(p_rule from 'BYDAY=([A-Z,]+)');
  v_dows int[];
  v_base_week date;
  v_next date := p_due;
  v_step int := 0;
  v_guard int := 0;
BEGIN
  IF p_rule IS NULL OR p_due IS NULL OR v_freq IS NULL THEN
    RETURN NULL;
  END IF;
  IF v_days IS NOT NULL THEN
    SELECT array_agg(CASE d WHEN 'MO' THEN 1 WHEN 'TU' THEN 2 WHEN 'WE' THEN 3 WHEN 'TH' THEN 4 WHEN 'FR' THEN 5 WHEN 'SA' THEN 6 ELSE 7 END)
      INTO v_dows FROM unnest(string_to_array(v_days, ',')) AS d;
  END IF;
  v_base_week := p_due - (extract(isodow from p_due)::int - 1);
  LOOP
    v_guard := v_guard + 1;
    EXIT WHEN v_guard > 20000;  -- 아주 오래된 기한의 매일 반복 등에서 멈추지 않게(그때는 마지막 계산값)
    IF v_freq = 'DAILY' THEN
      v_next := v_next + v_int;
    ELSIF v_freq = 'WEEKLY' AND v_dows IS NULL THEN
      v_next := v_next + 7 * v_int;
    ELSIF v_freq = 'WEEKLY' THEN
      -- 다음 날부터 하루씩: 고른 요일이고, 기한이 있던 주에서 INTERVAL 주째인 날
      v_next := v_next + 1;
      WHILE NOT (extract(isodow from v_next)::int = ANY (v_dows)
                 AND (((v_next - (extract(isodow from v_next)::int - 1)) - v_base_week) / 7) % v_int = 0) LOOP
        v_next := v_next + 1;
      END LOOP;
    ELSIF v_freq = 'MONTHLY' THEN
      v_step := v_step + v_int;
      v_next := (p_due + make_interval(months => v_step))::date;
    ELSIF v_freq = 'YEARLY' THEN
      v_step := v_step + v_int;
      v_next := (p_due + make_interval(years => v_step))::date;
    ELSE
      RETURN NULL;
    END IF;
    EXIT WHEN v_next >= p_today;
  END LOOP;
  RETURN v_next;
END;
$$;

-- ⑤ 완료하면 다음 회차, 되돌리면 손대지 않은 다음 회차를 지운다(SECURITY DEFINER: 소유자는 끝낸 할 일의 소유자 그대로)
CREATE OR REPLACE FUNCTION public.wb_task_repeat()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_today date := (now() AT TIME ZONE 'Asia/Seoul')::date;
  v_next date;
  v_time timestamptz;
  v_new uuid;
  v_next_row public.task%ROWTYPE;
BEGIN
  IF NEW.status = 'done' AND OLD.status <> 'done' AND OLD.repeat_rule IS NOT NULL
     AND NEW.parent_id IS NULL AND NEW.due_date IS NOT NULL THEN
    v_next := public.wb_repeat_next(OLD.repeat_rule, NEW.due_date, v_today);
    IF v_next IS NOT NULL THEN
      -- 기한 시각이 있으면 다음 회차도 같은 시각(서울)
      IF NEW.due_time IS NOT NULL THEN
        v_time := (v_next + (NEW.due_time AT TIME ZONE 'Asia/Seoul')::time) AT TIME ZONE 'Asia/Seoul';
      END IF;
      BEGIN
        INSERT INTO public.task (owner_id, title, priority, owner_kind, assignee, recipients, project_id, area_id,
                                 due_date, due_time, due_ambiguous, remind_before_min, repeat_rule, status)
        VALUES (NEW.owner_id, NEW.title, NEW.priority, NEW.owner_kind, NEW.assignee, NEW.recipients, NEW.project_id, NEW.area_id,
                v_next, v_time, false, NEW.remind_before_min, OLD.repeat_rule, 'open')
        RETURNING id INTO v_new;
        -- 하위 할 일도 안 끝난 채로(기한 없이, DEC-109 ④)
        INSERT INTO public.task (owner_id, title, owner_kind, assignee, recipients, parent_id, status)
        SELECT c.owner_id, c.title, c.owner_kind, c.assignee, c.recipients, v_new, 'open'
          FROM public.task c WHERE c.parent_id = NEW.id ORDER BY c.created_at;
        NEW.repeat_rule := NULL;
        NEW.repeat_next_id := v_new;
      EXCEPTION WHEN others THEN
        -- 보관된 곳 등으로 못 만들면 완료는 그대로 두고 규칙도 남긴다(다시 열어 고칠 수 있게)
        RAISE WARNING 'wb_task_repeat: next not created for % (%)', NEW.id, SQLERRM;
      END;
    END IF;
  ELSIF OLD.status = 'done' AND NEW.status <> 'done' AND OLD.repeat_next_id IS NOT NULL THEN
    SELECT * INTO v_next_row FROM public.task WHERE id = OLD.repeat_next_id;
    IF FOUND AND v_next_row.version = 0 AND v_next_row.status <> 'done' THEN
      -- 손대지 않은 다음 회차만 지운다(하위는 외래 키로 함께)
      DELETE FROM public.task WHERE id = OLD.repeat_next_id;
      NEW.repeat_rule := v_next_row.repeat_rule;
    END IF;
    NEW.repeat_next_id := NULL;
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE TRIGGER wb_task_repeat BEFORE UPDATE OF status ON public.task
  FOR EACH ROW EXECUTE FUNCTION public.wb_task_repeat();
-- 트리거 전용: RPC(/rest/v1/rpc)로 직접 부르지 못하게(다른 트리거 함수와 같게)
REVOKE ALL ON FUNCTION public.wb_task_repeat() FROM PUBLIC, anon, authenticated;

-- ⑥ 이력 스냅샷에 반복 규칙도 남긴다(0043 본문에 칸 하나만 더함)
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
                             'recipients', to_jsonb(NEW.recipients), 'parent_id', NEW.parent_id,
                             'remind_before_min', NEW.remind_before_min, 'repeat_rule', NEW.repeat_rule),
          v_actor, v_event);
  RETURN NEW;
END;
$$;
