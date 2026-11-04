-- FR-02 화면을 목업 S03대로 맞춤(DEC-66): 목업에 있는데 서버가 막던 두 가지를 연다.
--   1) 반복실행 설명(목업 '무엇을 계속 맡는 일인지' 한 줄, 수정에서 입력). 보관 중에는 못 고친다(0030과 같은 규칙).
--   2) 자료는 이름·종류만으로 먼저 만들고 내용(주소·메모)은 '수정'에서 채운다(목업 '아직 내용이 없어요'). 0030의 종류별 본문 필수를 푼다.
--      링크 주소 형식(http(s)만)과 기록 참조의 기록 필수는 그대로 둔다.

ALTER TABLE public.area ADD COLUMN IF NOT EXISTS description text;
ALTER TABLE public.area DROP CONSTRAINT IF EXISTS area_description_len;
ALTER TABLE public.area ADD CONSTRAINT area_description_len CHECK (char_length(description) <= 500);
GRANT INSERT (description) ON public.area TO authenticated;
GRANT UPDATE (description) ON public.area TO authenticated;

ALTER TABLE public.resource DROP CONSTRAINT IF EXISTS resource_kind_body;
ALTER TABLE public.resource DROP CONSTRAINT IF EXISTS resource_record_ref;
ALTER TABLE public.resource ADD CONSTRAINT resource_record_ref CHECK (kind <> 'record-ref' OR source_record_id IS NOT NULL);

-- 0030 함수와 같고, 반복실행은 이름·설명을 함께 본다
CREATE OR REPLACE FUNCTION public.wb_para_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path TO ''
AS $$
DECLARE
  v_changed boolean;
BEGIN
  IF TG_TABLE_NAME = 'task' THEN
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

  IF TG_OP = 'UPDATE' THEN
    IF OLD.archived_at IS NULL AND NEW.archived_at IS NOT NULL THEN
      NEW.archived_at := now();
    END IF;
    IF OLD.archived_at IS NOT NULL AND NEW.archived_at IS NOT NULL THEN
      IF TG_TABLE_NAME = 'project' THEN
        v_changed := (NEW.name, NEW.purpose, NEW.area_id) IS DISTINCT FROM (OLD.name, OLD.purpose, OLD.area_id);
      ELSIF TG_TABLE_NAME = 'area' THEN
        v_changed := (NEW.name, NEW.description) IS DISTINCT FROM (OLD.name, OLD.description);
      ELSE
        v_changed := (NEW.kind, NEW.title, NEW.url, NEW.note, NEW.source_record_id)
                     IS DISTINCT FROM (OLD.kind, OLD.title, OLD.url, OLD.note, OLD.source_record_id);
      END IF;
      IF v_changed THEN
        RAISE EXCEPTION 'WB409: 아카이브된 항목은 복원한 뒤 고칠 수 있습니다' USING ERRCODE = '55000';
      END IF;
    END IF;
  END IF;

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

REVOKE ALL ON FUNCTION public.wb_para_guard() FROM PUBLIC, anon, authenticated;
