-- 트리거 수정(P0 시험에서 발견): PL/pgSQL은 `TG_TABLE_NAME = 'x' AND NEW.col ...`을 단락 평가하지 않아
-- 그 열이 없는 다른 테이블에서도 NEW.col을 읽다가 실패한다(예: area 삽입 시 NEW.area_id).
-- 테이블 이름 확인과 열 접근을 중첩 IF로 나눈다. 검사 내용은 0200과 같다.

CREATE OR REPLACE FUNCTION public.wb_check_links()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
  v_ws uuid;
  v_target_ws uuid;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF NEW.owner_id <> OLD.owner_id THEN
      RAISE EXCEPTION 'WB403: owner_id는 바꿀 수 없습니다' USING ERRCODE = '42501';
    END IF;
  END IF;

  IF TG_TABLE_NAME IN ('area','project','resource','source_record') THEN
    IF NOT public.wb_owned('workspace', NEW.workspace_id, NEW.owner_id) THEN
      RAISE EXCEPTION 'WB403: 내 공간이 아닙니다' USING ERRCODE = '42501';
    END IF;
  END IF;

  IF TG_TABLE_NAME = 'project' THEN
    IF NEW.area_id IS NOT NULL THEN
      SELECT a.workspace_id INTO v_ws FROM public.area a WHERE a.id = NEW.area_id AND a.owner_id = NEW.owner_id;
      IF NOT FOUND THEN
        RAISE EXCEPTION 'WB403: 내 반복실행이 아닙니다' USING ERRCODE = '42501';
      END IF;
      IF v_ws <> NEW.workspace_id THEN
        RAISE EXCEPTION 'WB422: 프로젝트와 반복실행의 공간이 다릅니다(DEC-22)' USING ERRCODE = '23514';
      END IF;
    END IF;
  END IF;

  IF TG_TABLE_NAME = 'resource' THEN
    IF NOT public.wb_owned('source_record', NEW.source_record_id, NEW.owner_id) THEN
      RAISE EXCEPTION 'WB403: 내 기록이 아닙니다' USING ERRCODE = '42501';
    END IF;
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

CREATE OR REPLACE FUNCTION public.wb_require_version_bump()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  -- 승인 함수가 할 일을 쓰는 순간에는 버전을 함수가 직접 관리한다
  IF coalesce(current_setting('workbrain.actor', true), '') = 'ai-approved' THEN
    RETURN NEW;
  END IF;
  -- 프로젝트는 이름·목적·반복실행·보관이 바뀔 때만 버전을 요구한다(상태 버전 갱신은 승인 함수 몫)
  IF TG_TABLE_NAME = 'project' THEN
    IF NEW.name IS NOT DISTINCT FROM OLD.name AND NEW.purpose IS NOT DISTINCT FROM OLD.purpose
       AND NEW.area_id IS NOT DISTINCT FROM OLD.area_id AND NEW.archived_at IS NOT DISTINCT FROM OLD.archived_at THEN
      RETURN NEW;
    END IF;
  END IF;
  IF NEW.version <> OLD.version + 1 THEN
    RAISE EXCEPTION 'WB409: 버전이 맞지 않습니다(현재 %, 보낸 값 %)', OLD.version, NEW.version USING ERRCODE = '40001';
  END IF;
  RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.wb_check_links() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_require_version_bump() FROM PUBLIC, anon, authenticated;
