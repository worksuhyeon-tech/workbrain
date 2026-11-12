-- 0040 하위 할 일(FR-23a, DEC-101, 2026-11-12 사용자 결정, Design §3.15)
-- 실사용에서 끝낸 할 일에 후속 일이 계속 생겨, P4(FR-23)에 있던 하위 작업만 앞당겼다. 할 일 아래 한 단계만.
-- 하위의 곳(프로젝트·반복실행)은 늘 상위를 따른다: 넣을 때 상위 것을 넣고, 상위를 옮기면 하위도 옮긴다.
-- 끝낸 상위에 안 끝난 하위가 생기거나 다시 열리면 상위를 다시 연다. 하위를 다 끝내도 상위는 그대로(사용자가 직접).
-- 상위를 지우면 하위도 지워진다(외래 키 cascade, 이력도 함께).
-- 원격 SQL 확인 창이 만료되므로 DROP·DELETE 없이 쓴다. 다시 실행해도 같은 결과(IF NOT EXISTS·CREATE OR REPLACE, Postgres 17).

-- ① 칸·제약·색인
ALTER TABLE public.task ADD COLUMN IF NOT EXISTS parent_id uuid;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'task_parent_id_fkey') THEN
    ALTER TABLE public.task ADD CONSTRAINT task_parent_id_fkey
      FOREIGN KEY (parent_id) REFERENCES public.task(id) ON DELETE CASCADE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'task_parent_not_self') THEN
    ALTER TABLE public.task ADD CONSTRAINT task_parent_not_self CHECK (parent_id IS NULL OR parent_id <> id);
  END IF;
END
$$;
CREATE INDEX IF NOT EXISTS task_parent_id_idx ON public.task (parent_id) WHERE parent_id IS NOT NULL;

-- ② 넣거나 고칠 때(BEFORE): 상위 검사와 곳 물려받기.
-- 이름(wb_aa_…)을 앞에 두어 같은 때 도는 소유 검사(wb_check_links)·보관 검사(wb_para_guard)보다 먼저 돈다 —
-- 그래야 물려받은 곳을 그 검사들이 본다(보관된 프로젝트의 할 일에는 하위를 못 붙임).
-- 부르는 사람 권한으로 상위를 읽으므로(RLS) 남의 할 일은 '없음'과 같다
CREATE OR REPLACE FUNCTION public.wb_task_parent()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
  v_parent public.task%ROWTYPE;
BEGIN
  -- 하위가 있는 할 일은 하위가 될 수 없다(한 단계)
  IF NEW.parent_id IS NOT NULL AND EXISTS (SELECT 1 FROM public.task c WHERE c.parent_id = NEW.id) THEN
    RAISE EXCEPTION 'WB422: 하위 할 일이 있는 할 일은 다른 할 일의 하위가 될 수 없어요' USING ERRCODE = '23514';
  END IF;
  IF NEW.parent_id IS NULL THEN
    RETURN NEW;
  END IF;

  SELECT * INTO v_parent FROM public.task t WHERE t.id = NEW.parent_id AND t.owner_id = NEW.owner_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'WB403: 내 할 일이 아니에요' USING ERRCODE = '42501';
  END IF;
  IF v_parent.parent_id IS NOT NULL THEN
    RAISE EXCEPTION 'WB422: 하위 할 일 아래에는 더 넣을 수 없어요(한 단계)' USING ERRCODE = '23514';
  END IF;

  IF TG_OP = 'INSERT' THEN
    NEW.project_id := v_parent.project_id;
    NEW.area_id := v_parent.area_id;
  ELSIF (NEW.project_id, NEW.area_id) IS DISTINCT FROM (v_parent.project_id, v_parent.area_id) THEN
    -- 하위의 곳만 따로 옮기지 않는다(조용히 되돌리지 않고 바로 알린다). 상위를 옮기면 아래 트리거가 같이 옮긴다
    RAISE EXCEPTION 'WB422: 하위 할 일의 곳은 상위 할 일을 따라가요' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE OR REPLACE TRIGGER wb_aa_task_parent BEFORE INSERT OR UPDATE OF parent_id, project_id, area_id ON public.task
  FOR EACH ROW EXECUTE FUNCTION public.wb_task_parent();

-- ③ 상위를 옮기면(AFTER) 하위도 같은 곳으로. 버전을 올려 이력이 남고, 다른 기기의 낡은 화면은 충돌로 다시 읽는다
CREATE OR REPLACE FUNCTION public.wb_task_parent_moved()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.parent_id IS NULL AND (NEW.project_id, NEW.area_id) IS DISTINCT FROM (OLD.project_id, OLD.area_id) THEN
    UPDATE public.task c
       SET project_id = NEW.project_id, area_id = NEW.area_id, version = c.version + 1
     WHERE c.parent_id = NEW.id;
  END IF;
  RETURN NULL;
END;
$$;
CREATE OR REPLACE TRIGGER wb_task_parent_moved AFTER UPDATE OF project_id, area_id ON public.task
  FOR EACH ROW EXECUTE FUNCTION public.wb_task_parent_moved();

-- ④ 안 끝난 하위가 생기거나 다시 열리면(AFTER) 끝난 상위를 다시 연다(DEC-101 ③). 완료 시각은 기존 트리거(wb_task_done_at)가 지운다
CREATE OR REPLACE FUNCTION public.wb_task_reopen_parent()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.parent_id IS NOT NULL AND NEW.status <> 'done'
     AND (TG_OP = 'INSERT' OR OLD.status = 'done' OR OLD.parent_id IS DISTINCT FROM NEW.parent_id) THEN
    UPDATE public.task p SET status = 'open', version = p.version + 1
     WHERE p.id = NEW.parent_id AND p.status = 'done';
  END IF;
  RETURN NULL;
END;
$$;
CREATE OR REPLACE TRIGGER wb_task_reopen_parent AFTER INSERT OR UPDATE OF status, parent_id ON public.task
  FOR EACH ROW EXECUTE FUNCTION public.wb_task_reopen_parent();

REVOKE ALL ON FUNCTION public.wb_task_parent() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_task_parent_moved() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_task_reopen_parent() FROM PUBLIC, anon, authenticated;

-- ⑤ 권한: 앱은 하위를 넣을 때만 상위를 정한다(다른 상위로 옮기기는 2단계, 그래서 UPDATE는 주지 않음)
GRANT INSERT (parent_id) ON public.task TO authenticated;

-- ⑥ 이력 스냅샷에 상위도 남긴다(0035 본문 + parent_id)
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
                             'recipients', to_jsonb(NEW.recipients), 'parent_id', NEW.parent_id),
          v_actor, v_event);
  RETURN NEW;
END;
$$;
