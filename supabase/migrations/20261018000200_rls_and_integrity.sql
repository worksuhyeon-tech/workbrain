-- RLS·권한 계약(M13)과 무결성 트리거 (Design v0.1.7 §4.7)
-- 1) 모든 업무 테이블: RLS 켜고 owner_id = auth.uid() 하나로 통일. anon에는 아무 권한도 없다.
-- 2) 운영 테이블(상태 버전·승인·예약·삭제·할 일 이력 등)은 authenticated에 SELECT만 준다.
--    바꾸는 길은 SECURITY DEFINER 함수뿐이라 Data API로 승인을 건너뛸 수 없다.
-- 3) RLS는 '남의 행 읽기'만 막고 '남의 ID를 FK로 참조'는 막지 못하므로, 연결 양끝 소유자를 트리거로 확인한다.

-- ── 권한 초기화: Supabase 기본 GRANT를 걷어내고 필요한 것만 다시 준다 ──
revoke all on all tables in schema public from anon, authenticated;
revoke all on all functions in schema public from anon, authenticated, public;
alter default privileges in schema public revoke all on tables from anon, authenticated;
alter default privileges in schema public revoke all on functions from anon, authenticated, public;

do $$
declare t text;
begin
  foreach t in array array[
    'app_user','consent_grant','settings','workspace','area','project','project_state_version',
    'source_record','audio_asset','transcript_version','meeting_version','mail_dispatch','analysis_run',
    'source_project_link','resource','resource_link','task','change_proposal','evidence_link',
    'approval_event','task_revision','activity_log','processing_job','cost_ledger','budget_reservation',
    'deletion_request','tombstone']
  loop
    -- FORCE는 쓰지 않는다: 테이블 소유자(postgres)로 도는 SECURITY DEFINER 함수가
    -- 이력·승인 행을 쓸 수 있어야 하고, 대신 그 함수들이 auth.uid()를 직접 확인한다.
    execute format('alter table public.%I enable row level security', t);
    execute format('grant select on public.%I to authenticated', t);
    if t = 'app_user' then
      execute format('create policy own_select on public.%I for select to authenticated using (id = (select auth.uid()))', t);
    elsif t = 'settings' then
      execute format('create policy own_select on public.%I for select to authenticated using (user_id = (select auth.uid()))', t);
    else
      execute format('create policy own_select on public.%I for select to authenticated using (owner_id = (select auth.uid()))', t);
    end if;
  end loop;
end
$$;

-- 사용자가 직접 쓰는 테이블(수동 CRUD). 나머지는 SELECT만.
do $$
declare t text;
begin
  foreach t in array array['workspace','area','resource','resource_link','source_record','consent_grant'] loop
    execute format('create policy own_insert on public.%I for insert to authenticated with check (owner_id = (select auth.uid()))', t);
    execute format('create policy own_update on public.%I for update to authenticated using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()))', t);
  end loop;
  foreach t in array array['project','task'] loop
    execute format('create policy own_insert on public.%I for insert to authenticated with check (owner_id = (select auth.uid()))', t);
    execute format('create policy own_update on public.%I for update to authenticated using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()))', t);
    execute format('create policy own_delete on public.%I for delete to authenticated using (owner_id = (select auth.uid()))', t);
  end loop;
  foreach t in array array['resource_link'] loop
    execute format('create policy own_delete on public.%I for delete to authenticated using (owner_id = (select auth.uid()))', t);
  end loop;
end
$$;
create policy own_update on public.settings for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

grant insert, update on public.workspace, public.area, public.resource, public.source_record to authenticated;
grant insert, update (revoked_at) on public.consent_grant to authenticated;
grant insert, delete on public.resource_link to authenticated;
grant update (timezone, retention_audio_days, retention_transcript_months, retention_knowledge_years,
              budget_warn_krw, budget_wait_krw, mail_recipients, updated_at) on public.settings to authenticated;
-- project: 상태 버전(current_state_version)은 승인 함수만 바꾼다 → 열 단위로 허용
grant insert (workspace_id, area_id, name, purpose) on public.project to authenticated;
grant update (area_id, name, purpose, archived_at, version) on public.project to authenticated;
grant delete on public.project to authenticated;
-- task: 수동 편집 허용. 버전 증가·이력 기록은 트리거가 강제한다
grant insert (project_id, area_id, title, status, priority, owner_kind, assignee, due_date, due_time, due_ambiguous, due_text) on public.task to authenticated;
grant update (project_id, area_id, title, status, priority, owner_kind, assignee, due_date, due_time, due_ambiguous, due_text, version) on public.task to authenticated;
grant delete on public.task to authenticated;

-- ── 소유자 일치 검사 ──
-- RLS가 걸린 호출자에게 남의 행은 보이지 않으므로, '보이는 내 행인지'를 확인하면 된다.
-- 서비스 키(RLS 우회)로 쓸 때도 owner_id 비교로 막힌다.
CREATE OR REPLACE FUNCTION public.wb_owned(p_table text, p_id uuid, p_owner uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SET search_path = ''
AS $$
DECLARE
  v_ok boolean;
BEGIN
  IF p_id IS NULL THEN
    RETURN true;
  END IF;
  EXECUTE format('select exists (select 1 from public.%I where id = $1 and owner_id = $2)', p_table)
    INTO v_ok USING p_id, p_owner;
  RETURN v_ok;
END;
$$;

CREATE OR REPLACE FUNCTION public.wb_check_links()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
  v_ws uuid;
  v_target_ws uuid;
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.owner_id <> OLD.owner_id THEN
    RAISE EXCEPTION 'WB403: owner_id는 바꿀 수 없습니다' USING ERRCODE = '42501';
  END IF;

  IF TG_TABLE_NAME IN ('area','project','resource','source_record') THEN
    IF NOT public.wb_owned('workspace', NEW.workspace_id, NEW.owner_id) THEN
      RAISE EXCEPTION 'WB403: 내 공간이 아닙니다' USING ERRCODE = '42501';
    END IF;
  END IF;

  IF TG_TABLE_NAME = 'project' AND NEW.area_id IS NOT NULL THEN
    SELECT a.workspace_id INTO v_ws FROM public.area a WHERE a.id = NEW.area_id AND a.owner_id = NEW.owner_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'WB403: 내 반복실행이 아닙니다' USING ERRCODE = '42501';
    END IF;
    IF v_ws <> NEW.workspace_id THEN
      RAISE EXCEPTION 'WB422: 프로젝트와 반복실행의 공간이 다릅니다(DEC-22)' USING ERRCODE = '23514';
    END IF;
  END IF;

  IF TG_TABLE_NAME = 'resource' AND NOT public.wb_owned('source_record', NEW.source_record_id, NEW.owner_id) THEN
    RAISE EXCEPTION 'WB403: 내 기록이 아닙니다' USING ERRCODE = '42501';
  END IF;

  IF TG_TABLE_NAME = 'task' THEN
    IF NOT public.wb_owned('project', NEW.project_id, NEW.owner_id) OR NOT public.wb_owned('area', NEW.area_id, NEW.owner_id) THEN
      RAISE EXCEPTION 'WB403: 내 프로젝트·반복실행이 아닙니다' USING ERRCODE = '42501';
    END IF;
  END IF;

  IF TG_TABLE_NAME = 'resource_link' THEN
    SELECT r.workspace_id INTO v_ws FROM public.resource r WHERE r.id = NEW.resource_id AND r.owner_id = NEW.owner_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'WB403: 내 자료가 아닙니다' USING ERRCODE = '42501';
    END IF;
    IF NEW.target_type = 'project' THEN
      SELECT p.workspace_id INTO v_target_ws FROM public.project p WHERE p.id = NEW.target_id AND p.owner_id = NEW.owner_id;
    ELSE
      SELECT a.workspace_id INTO v_target_ws FROM public.area a WHERE a.id = NEW.target_id AND a.owner_id = NEW.owner_id;
    END IF;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'WB403: 연결 대상이 내 것이 아닙니다' USING ERRCODE = '42501';
    END IF;
    IF v_target_ws <> v_ws THEN
      RAISE EXCEPTION 'WB422: 다른 공간에는 연결할 수 없습니다(DEC-22)' USING ERRCODE = '23514';
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS wb_check_links ON public.area;
CREATE TRIGGER wb_check_links BEFORE INSERT OR UPDATE ON public.area FOR EACH ROW EXECUTE FUNCTION public.wb_check_links();
DROP TRIGGER IF EXISTS wb_check_links ON public.project;
CREATE TRIGGER wb_check_links BEFORE INSERT OR UPDATE ON public.project FOR EACH ROW EXECUTE FUNCTION public.wb_check_links();
DROP TRIGGER IF EXISTS wb_check_links ON public.resource;
CREATE TRIGGER wb_check_links BEFORE INSERT OR UPDATE ON public.resource FOR EACH ROW EXECUTE FUNCTION public.wb_check_links();
DROP TRIGGER IF EXISTS wb_check_links ON public.source_record;
CREATE TRIGGER wb_check_links BEFORE INSERT OR UPDATE ON public.source_record FOR EACH ROW EXECUTE FUNCTION public.wb_check_links();
DROP TRIGGER IF EXISTS wb_check_links ON public.task;
CREATE TRIGGER wb_check_links BEFORE INSERT OR UPDATE ON public.task FOR EACH ROW EXECUTE FUNCTION public.wb_check_links();
DROP TRIGGER IF EXISTS wb_check_links ON public.resource_link;
CREATE TRIGGER wb_check_links BEFORE INSERT OR UPDATE ON public.resource_link FOR EACH ROW EXECUTE FUNCTION public.wb_check_links();

-- ── 낙관적 동시성: 사용자가 고칠 때는 version을 정확히 1 올려야 한다(M18) ──
-- 클라이언트는 `update ... set ..., version = :v + 1 where id = :id and version = :v`로 보낸다.
-- 오래된 버전이면 0행이 바뀌어 충돌로 처리하고, 버전을 안 올린 덮어쓰기는 여기서 거부한다.
CREATE OR REPLACE FUNCTION public.wb_require_version_bump()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  -- 승인 함수가 같은 트랜잭션에서 버전을 직접 관리할 때는 건너뛴다
  IF coalesce(current_setting('workbrain.actor', true), '') = 'ai-approved' THEN
    RETURN NEW;
  END IF;
  IF TG_TABLE_NAME = 'project'
     AND NEW.name IS NOT DISTINCT FROM OLD.name AND NEW.purpose IS NOT DISTINCT FROM OLD.purpose
     AND NEW.area_id IS NOT DISTINCT FROM OLD.area_id AND NEW.archived_at IS NOT DISTINCT FROM OLD.archived_at THEN
    RETURN NEW;
  END IF;
  IF NEW.version <> OLD.version + 1 THEN
    RAISE EXCEPTION 'WB409: 버전이 맞지 않습니다(현재 %, 보낸 값 %)', OLD.version, NEW.version USING ERRCODE = '40001';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS wb_version_bump ON public.task;
CREATE TRIGGER wb_version_bump BEFORE UPDATE ON public.task FOR EACH ROW EXECUTE FUNCTION public.wb_require_version_bump();
DROP TRIGGER IF EXISTS wb_version_bump ON public.project;
CREATE TRIGGER wb_version_bump BEFORE UPDATE ON public.project FOR EACH ROW EXECUTE FUNCTION public.wb_require_version_bump();

-- ── 할 일 이력: 저장될 때마다 스냅샷을 쌓는다(덮어쓰지 않음, M18) ──
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
                             'due_ambiguous', NEW.due_ambiguous, 'due_text', NEW.due_text),
          v_actor, v_event);
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS wb_task_revision ON public.task;
CREATE TRIGGER wb_task_revision AFTER INSERT OR UPDATE ON public.task FOR EACH ROW EXECUTE FUNCTION public.wb_task_revision();

-- 함수 실행 권한: 기본 PUBLIC 실행을 걷어내고, 트리거 안에서 호출자 권한으로 도는 wb_owned만 연다
REVOKE ALL ON FUNCTION public.wb_owned(text, uuid, uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.wb_check_links() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_require_version_bump() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_task_revision() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.wb_owned(text, uuid, uuid) TO authenticated;
