-- 회의록 문서와 지침 가져오기(FR-30, Design §3.8, DEC-56).
-- ① 처리 함수(DOCUMENT 단계)가 회의록 초안에 문서와 적용한 지침 정보를 써넣고, 참고 파일 전송 동의를 확인한다.
-- ② 앱이 스킬 zip에서 꺼낸 SKILL.md·참고 파일로 지침을 통째로 바꾼다(검사 후 교체, rev +1).

GRANT UPDATE (document, guideline_rev, guideline_imported_at, guideline_input_hash) ON public.meeting_version TO service_role;
GRANT SELECT (owner_id, purpose, revoked_at) ON public.consent_grant TO service_role;

-- p_refs = [{name, content}] (references/*.md). 상한: SKILL.md 8,000자, 참고 포함 합계 12,000자.
-- 하나라도 어기면 전체를 거부하고 기존 지침을 그대로 둔다
CREATE OR REPLACE FUNCTION public.wb_guideline_set(p_guideline text, p_refs jsonb, p_source_name text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_guideline text := btrim(coalesce(p_guideline, ''));
  v_refs jsonb := coalesce(p_refs, '[]'::jsonb);
  v_total int;
  v_rev int;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;
  IF length(v_guideline) = 0 THEN
    RAISE EXCEPTION 'WB400: SKILL.md 내용이 비어 있습니다' USING ERRCODE = '22023';
  END IF;
  IF length(v_guideline) > 8000 THEN
    RAISE EXCEPTION 'WB400: SKILL.md가 8,000자를 넘습니다' USING ERRCODE = '22023';
  END IF;
  IF jsonb_typeof(v_refs) <> 'array' OR EXISTS (
      SELECT 1 FROM jsonb_array_elements(v_refs) r
       WHERE jsonb_typeof(r -> 'name') IS DISTINCT FROM 'string'
          OR jsonb_typeof(r -> 'content') IS DISTINCT FROM 'string'
          OR r ->> 'name' !~* '\.md$'
          OR length(r ->> 'name') > 120) THEN
    RAISE EXCEPTION 'WB400: 참고 파일 형식이 올바르지 않습니다' USING ERRCODE = '22023';
  END IF;
  SELECT length(v_guideline) + coalesce(sum(length(r ->> 'content')), 0) INTO v_total FROM jsonb_array_elements(v_refs) r;
  IF v_total > 12000 THEN
    RAISE EXCEPTION 'WB400: 참고 파일까지 합쳐 12,000자를 넘습니다' USING ERRCODE = '22023';
  END IF;

  UPDATE public.settings
     SET minutes_guideline = v_guideline,
         minutes_guideline_refs = v_refs,
         guideline_rev = guideline_rev + 1,
         guideline_source_name = left(btrim(coalesce(p_source_name, '')), 120),
         guideline_imported_at = now(),
         updated_at = now()
   WHERE user_id = v_uid
  RETURNING guideline_rev INTO v_rev;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'WB404: 설정을 찾을 수 없습니다' USING ERRCODE = 'P0002';
  END IF;

  RETURN jsonb_build_object('guideline_rev', v_rev);
END;
$$;

REVOKE ALL ON FUNCTION public.wb_guideline_set(text, jsonb, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_guideline_set(text, jsonb, text) TO authenticated;
