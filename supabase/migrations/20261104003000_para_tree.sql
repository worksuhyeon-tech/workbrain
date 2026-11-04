-- FR-02 1단계: PARA 트리(프로젝트·반복실행·자료·아카이브) 손 정리 (DEC-22·24, Design §4.2, TC-02)
-- 앱은 project·area·resource·resource_link·task를 Data API(RLS)로 직접 읽고 쓴다. 이 마이그레이션은 서버가 지켜야 할 규칙만 더한다.
--   1) 아카이브는 읽기 전용: 보관된 항목은 복원 전까지 이름·내용을 못 고치고, 보관된 곳으로 할 일·자료·프로젝트를 넣지 못한다.
--      보관 시각은 기기 시계가 아니라 서버 시각으로 맞춘다.
--   2) 자료도 프로젝트처럼 버전을 1씩 올려야 고칠 수 있다(두 기기 동시 수정 충돌, M18).
--   3) 보관·복원과 자료 연결·해제를 activity_log에 남긴다(보관해도 ID·이력 유지). 할 일 소속 변경은 task_revision이 이미 남긴다.
--   4) 이름 길이·자료 형식 검사와 열 단위 권한 정리(앱이 고칠 열만).
-- 기존 행: 이 마이그레이션 시점에 project·area·resource는 0건이라 새 CHECK가 기존 데이터를 막지 않는다.

-- ── 1. 입력값 검사 ──
ALTER TABLE public.project
  ADD CONSTRAINT project_name_len CHECK (char_length(btrim(name)) BETWEEN 1 AND 80),
  ADD CONSTRAINT project_purpose_len CHECK (char_length(purpose) <= 500);

ALTER TABLE public.area
  ADD CONSTRAINT area_name_len CHECK (char_length(btrim(name)) BETWEEN 1 AND 80);

-- 자료는 링크·짧은 메모·기존 기록 참조만(M15). 링크는 http(s)만 열게 해 다른 앱을 여는 주소(intent: 등)를 막는다
ALTER TABLE public.resource
  ADD CONSTRAINT resource_title_len CHECK (char_length(btrim(title)) BETWEEN 1 AND 80),
  ADD CONSTRAINT resource_url_format CHECK (url IS NULL OR (url ~* '^https?://\S+$' AND char_length(url) <= 2000)),
  ADD CONSTRAINT resource_kind_body CHECK (
    (kind <> 'link' OR url IS NOT NULL)
    AND (kind <> 'note' OR note IS NOT NULL)
    AND (kind <> 'record-ref' OR source_record_id IS NOT NULL)
  );

-- ── 2. 아카이브 읽기 전용·보관된 곳으로 옮기기 거부 ──
-- 표마다 열이 달라서, 다른 표의 열을 한 식에 섞지 않도록 표 이름으로 먼저 나눈다(0500 trigger_table_branching과 같은 이유).
-- INSERT에는 OLD가 없으므로 'INSERT이거나 값이 바뀜'도 따로 나눠 판단한다
CREATE OR REPLACE FUNCTION public.wb_para_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path TO ''
AS $$
DECLARE
  v_changed boolean;
BEGIN
  IF TG_TABLE_NAME = 'task' THEN
    -- 보관된 프로젝트·반복실행에는 할 일을 새로 넣거나 옮겨 넣지 않는다. 이미 들어 있던 할 일은 그대로 둔다
    IF TG_OP = 'INSERT' THEN
      v_changed := true;
    ELSE
      v_changed := NEW.project_id IS DISTINCT FROM OLD.project_id OR NEW.area_id IS DISTINCT FROM OLD.area_id;
    END IF;
    IF v_changed THEN
      IF EXISTS (SELECT 1 FROM public.project p WHERE p.id = NEW.project_id AND p.archived_at IS NOT NULL) THEN
        RAISE EXCEPTION 'WB409: 아카이브된 프로젝트에는 할 일을 넣을 수 없습니다' USING ERRCODE = '55000';
      END IF;
      IF EXISTS (SELECT 1 FROM public.area a WHERE a.id = NEW.area_id AND a.archived_at IS NOT NULL) THEN
        RAISE EXCEPTION 'WB409: 아카이브된 반복실행에는 할 일을 넣을 수 없습니다' USING ERRCODE = '55000';
      END IF;
    END IF;
    RETURN NEW;
  END IF;

  IF TG_TABLE_NAME = 'resource_link' THEN
    IF EXISTS (SELECT 1 FROM public.resource r WHERE r.id = NEW.resource_id AND r.archived_at IS NOT NULL)
       OR EXISTS (SELECT 1 FROM public.project p WHERE NEW.target_type = 'project' AND p.id = NEW.target_id AND p.archived_at IS NOT NULL)
       OR EXISTS (SELECT 1 FROM public.area a WHERE NEW.target_type = 'area' AND a.id = NEW.target_id AND a.archived_at IS NOT NULL) THEN
      RAISE EXCEPTION 'WB409: 아카이브된 항목은 연결할 수 없습니다' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
  END IF;

  -- 이하 project·area·resource
  IF TG_OP = 'UPDATE' THEN
    -- 보관하는 순간의 시각은 서버 시각(기기 시계가 틀려도 순서가 맞게)
    IF OLD.archived_at IS NULL AND NEW.archived_at IS NOT NULL THEN
      NEW.archived_at := now();
    END IF;
    -- 보관된 채로 내용을 고치는 것은 막는다(복원 후 고치기)
    IF OLD.archived_at IS NOT NULL AND NEW.archived_at IS NOT NULL THEN
      IF TG_TABLE_NAME = 'project' THEN
        v_changed := (NEW.name, NEW.purpose, NEW.area_id) IS DISTINCT FROM (OLD.name, OLD.purpose, OLD.area_id);
      ELSIF TG_TABLE_NAME = 'area' THEN
        v_changed := NEW.name IS DISTINCT FROM OLD.name;
      ELSE
        v_changed := (NEW.kind, NEW.title, NEW.url, NEW.note, NEW.source_record_id)
                     IS DISTINCT FROM (OLD.kind, OLD.title, OLD.url, OLD.note, OLD.source_record_id);
      END IF;
      IF v_changed THEN
        RAISE EXCEPTION 'WB409: 아카이브된 항목은 복원한 뒤 고칠 수 있습니다' USING ERRCODE = '55000';
      END IF;
    END IF;
  END IF;

  -- 프로젝트를 보관된 반복실행에 새로 연결하지 않는다(이미 연결돼 있던 것은 그대로)
  IF TG_TABLE_NAME = 'project' THEN
    IF TG_OP = 'INSERT' THEN
      v_changed := true;
    ELSE
      v_changed := NEW.area_id IS DISTINCT FROM OLD.area_id;
    END IF;
    IF v_changed AND EXISTS (SELECT 1 FROM public.area a WHERE a.id = NEW.area_id AND a.archived_at IS NOT NULL) THEN
      RAISE EXCEPTION 'WB409: 아카이브된 반복실행에는 연결할 수 없습니다' USING ERRCODE = '55000';
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS wb_para_guard ON public.task;
CREATE TRIGGER wb_para_guard BEFORE INSERT OR UPDATE OF project_id, area_id ON public.task
  FOR EACH ROW EXECUTE FUNCTION public.wb_para_guard();
DROP TRIGGER IF EXISTS wb_para_guard ON public.project;
CREATE TRIGGER wb_para_guard BEFORE INSERT OR UPDATE ON public.project
  FOR EACH ROW EXECUTE FUNCTION public.wb_para_guard();
DROP TRIGGER IF EXISTS wb_para_guard ON public.area;
CREATE TRIGGER wb_para_guard BEFORE UPDATE ON public.area
  FOR EACH ROW EXECUTE FUNCTION public.wb_para_guard();
DROP TRIGGER IF EXISTS wb_para_guard ON public.resource;
CREATE TRIGGER wb_para_guard BEFORE UPDATE ON public.resource
  FOR EACH ROW EXECUTE FUNCTION public.wb_para_guard();
DROP TRIGGER IF EXISTS wb_para_guard ON public.resource_link;
CREATE TRIGGER wb_para_guard BEFORE INSERT ON public.resource_link
  FOR EACH ROW EXECUTE FUNCTION public.wb_para_guard();

-- ── 3. 자료 버전(M18): 프로젝트·할 일과 같은 규칙(고칠 때마다 1 올림) ──
DROP TRIGGER IF EXISTS wb_version_bump ON public.resource;
CREATE TRIGGER wb_version_bump BEFORE UPDATE ON public.resource
  FOR EACH ROW EXECUTE FUNCTION public.wb_require_version_bump();

-- ── 4. 보관·복원, 자료 연결·해제 이력 ──
-- 앱은 activity_log를 읽기만 하므로 트리거가 소유자 권한으로 쓴다
CREATE OR REPLACE FUNCTION public.wb_para_log()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
DECLARE
  v_actor text := coalesce(nullif(current_setting('workbrain.actor', true), ''), 'user');
BEGIN
  IF TG_TABLE_NAME = 'resource_link' THEN
    IF TG_OP = 'INSERT' THEN
      INSERT INTO public.activity_log (owner_id, entity_type, entity_id, action, actor)
      VALUES (NEW.owner_id, 'resource', NEW.resource_id, 'linked:' || NEW.target_type || ':' || NEW.target_id, v_actor);
      RETURN NEW;
    END IF;
    -- 계정·자료를 지우면서 연쇄로 사라지는 연결은 남기지 않는다(지운 주인 앞으로 이력을 쓸 수 없고, 쓰면 계정 삭제가 막힌다)
    IF NOT EXISTS (SELECT 1 FROM public.app_user u WHERE u.id = OLD.owner_id)
       OR NOT EXISTS (SELECT 1 FROM public.resource r WHERE r.id = OLD.resource_id) THEN
      RETURN OLD;
    END IF;
    INSERT INTO public.activity_log (owner_id, entity_type, entity_id, action, actor)
    VALUES (OLD.owner_id, 'resource', OLD.resource_id, 'unlinked:' || OLD.target_type || ':' || OLD.target_id, v_actor);
    RETURN OLD;
  END IF;

  -- project·area·resource: 보관 상태가 바뀐 경우만(트리거 WHEN 조건)
  INSERT INTO public.activity_log (owner_id, entity_type, entity_id, action, actor)
  VALUES (NEW.owner_id, TG_TABLE_NAME, NEW.id, CASE WHEN NEW.archived_at IS NULL THEN 'restored' ELSE 'archived' END, v_actor);
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS wb_para_log ON public.project;
CREATE TRIGGER wb_para_log AFTER UPDATE OF archived_at ON public.project
  FOR EACH ROW WHEN ((OLD.archived_at IS NULL) <> (NEW.archived_at IS NULL)) EXECUTE FUNCTION public.wb_para_log();
DROP TRIGGER IF EXISTS wb_para_log ON public.area;
CREATE TRIGGER wb_para_log AFTER UPDATE OF archived_at ON public.area
  FOR EACH ROW WHEN ((OLD.archived_at IS NULL) <> (NEW.archived_at IS NULL)) EXECUTE FUNCTION public.wb_para_log();
DROP TRIGGER IF EXISTS wb_para_log ON public.resource;
CREATE TRIGGER wb_para_log AFTER UPDATE OF archived_at ON public.resource
  FOR EACH ROW WHEN ((OLD.archived_at IS NULL) <> (NEW.archived_at IS NULL)) EXECUTE FUNCTION public.wb_para_log();
DROP TRIGGER IF EXISTS wb_para_log ON public.resource_link;
CREATE TRIGGER wb_para_log AFTER INSERT OR DELETE ON public.resource_link
  FOR EACH ROW EXECUTE FUNCTION public.wb_para_log();

REVOKE ALL ON FUNCTION public.wb_para_guard() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_para_log() FROM PUBLIC, anon, authenticated;

-- ── 5. 열 단위 권한: 앱이 고칠 열만 연다(id·소유자·만든 시각·공간은 고정) ──
REVOKE INSERT, UPDATE ON public.area FROM authenticated;
GRANT INSERT (workspace_id, name) ON public.area TO authenticated;
GRANT UPDATE (name, archived_at) ON public.area TO authenticated;

REVOKE INSERT, UPDATE ON public.resource FROM authenticated;
GRANT INSERT (workspace_id, kind, title, url, note, source_record_id) ON public.resource TO authenticated;
GRANT UPDATE (title, url, note, version, archived_at) ON public.resource TO authenticated;

REVOKE INSERT ON public.resource_link FROM authenticated;
GRANT INSERT (resource_id, target_type, target_id) ON public.resource_link TO authenticated;
