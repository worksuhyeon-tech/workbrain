-- 0043 할 일 미리 알림(DEC-108, 2026-11-15 사용자 결정, Design §3.19): task.remind_before_min + 권한·이력
-- 할 일마다 '몇 분 전'을 저장하고 휴대폰 앱이 그때 울린다(서버에 두어 PC·다른 휴대폰에서 정해도 울림).
-- 울리는 시각 = 기한 시각(없으면 그날 09:00 서울) − 값. 계산과 예약은 앱이 한다(서버 크론 없음).
-- 원격 SQL 확인 창이 만료되므로 DROP·DELETE 없이 쓴다. 제약은 없을 때만 더한다(다시 실행해도 같은 결과).

-- ① 칸: NULL = 알림 없음, 0 = 정각, 그 밖은 몇 분 전(1일 전 = 1440)
ALTER TABLE public.task ADD COLUMN IF NOT EXISTS remind_before_min int;

-- ② 검사: 0분 ~ 30일(43200분)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'task_remind_before_min_range' AND conrelid = 'public.task'::regclass) THEN
    ALTER TABLE public.task ADD CONSTRAINT task_remind_before_min_range CHECK (remind_before_min IS NULL OR remind_before_min BETWEEN 0 AND 43200);
  END IF;
END;
$$;

-- ③ 권한: 앱이 할 일을 넣고 고칠 때 알림 값도 쓴다(열 단위 권한, 0035와 같은 방식)
GRANT INSERT (remind_before_min), UPDATE (remind_before_min) ON public.task TO authenticated;

-- ④ 이력 스냅샷에 알림 값도 남긴다(M18: 덮어쓰지 않는 이력). 0040 본문에 칸 하나만 더함
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
                             'remind_before_min', NEW.remind_before_min),
          v_actor, v_event);
  RETURN NEW;
END;
$$;
