-- 받아쓴 글 가져오기(DEC-53): 클로바노트 등에서 받아쓴 txt를 가져오면 받아쓰기(STT)를 건너뛰고
-- 용어 교정(CORRECT) → 회의록·할 일 정리(EXTRACT)부터 진행한다. 음성 파일·audio_asset은 만들지 않는다.
-- 앱이 화자 표시·시각 줄을 떼고 말한 문장만 줄 단위로 보낸다(화자 추정 금지, NFR-02·DEC-27).

ALTER TABLE public.source_record DROP CONSTRAINT IF EXISTS source_record_channel_check;
ALTER TABLE public.source_record ADD CONSTRAINT source_record_channel_check
  CHECK (channel IN ('recording', 'upload', 'call-share', 'text-import'));

CREATE OR REPLACE FUNCTION public.wb_record_text_ready(p_record_id uuid, p_text text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_text text := btrim(coalesce(p_text, ''));
  v_months int;
  v_segments jsonb;
  v_job uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;
  IF length(v_text) = 0 THEN
    RAISE EXCEPTION 'WB400: 가져온 글이 비어 있습니다' USING ERRCODE = '22023';
  END IF;
  -- 앱 한도(1MB)와 맞춘 상한. 약 3시간 회의 분량
  IF length(v_text) > 300000 THEN
    RAISE EXCEPTION 'WB400: 가져온 글이 너무 깁니다(30만 자 이하)' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.source_record WHERE id = p_record_id AND owner_id = v_uid AND channel = 'text-import') THEN
    RAISE EXCEPTION 'WB404: 기록을 찾을 수 없습니다' USING ERRCODE = 'P0002';
  END IF;
  -- 음성을 보내지 않으므로 텍스트 AI 분석 동의만 확인한다(FR-17)
  IF NOT EXISTS (SELECT 1 FROM public.consent_grant
      WHERE owner_id = v_uid AND purpose = 'text-ai' AND revoked_at IS NULL) THEN
    RAISE EXCEPTION 'WB403: 외부 분석 동의가 필요합니다' USING ERRCODE = '42501';
  END IF;

  -- 같은 기록을 다시 보내면(재시도) 원문을 한 번만 저장한다
  IF NOT EXISTS (SELECT 1 FROM public.transcript_version WHERE source_record_id = p_record_id AND owner_id = v_uid) THEN
    SELECT retention_transcript_months INTO v_months FROM public.settings WHERE user_id = v_uid;
    SELECT coalesce(jsonb_agg(jsonb_build_object('idx', t.n - 1, 'text', t.line) ORDER BY t.n), '[]'::jsonb)
      INTO v_segments
      FROM (
        SELECT row_number() OVER (ORDER BY l.ord) AS n, btrim(l.line) AS line
          FROM regexp_split_to_table(v_text, E'\n') WITH ORDINALITY AS l(line, ord)
         WHERE btrim(l.line) <> ''
      ) t;
    INSERT INTO public.transcript_version (source_record_id, owner_id, kind, content, segments, retention_expires_at)
    VALUES (p_record_id, v_uid, 'stt-original', v_text, v_segments, now() + make_interval(months => coalesce(v_months, 6)));
  END IF;

  -- 교정 단계는 용어집·참석자·내 이름이 없으면 그대로 정리 단계로 넘긴다
  INSERT INTO public.processing_job (owner_id, source_record_id, stage, stage_state, execution_key)
  VALUES (v_uid, p_record_id, 'CORRECT', 'PENDING', p_record_id::text || ':pipeline')
  ON CONFLICT (owner_id, execution_key) DO NOTHING;
  SELECT id INTO v_job FROM public.processing_job WHERE owner_id = v_uid AND execution_key = p_record_id::text || ':pipeline';

  RETURN jsonb_build_object('job_id', v_job);
END;
$$;

REVOKE ALL ON FUNCTION public.wb_record_text_ready(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_record_text_ready(uuid, text) TO authenticated;
