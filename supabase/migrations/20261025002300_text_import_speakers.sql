-- 전문 보기 화자 표시(DEC-54): 가져온 txt의 화자·시각을 전사 segments에 함께 저장한다.
-- segments = [{idx, text, speaker?, offsetMs?}]. speaker는 화면 표시 전용이다.
-- 정리 단계는 segments의 text만 AI에 넘기므로 화자는 AI 입력에 들어가지 않는다(DEC-27).
-- p_turns = [{speaker, offsetMs, text}] (text는 여러 줄일 수 있음). 없으면 0022와 같다.

-- 인자가 늘어 0022의 2인자 함수와 겹치지 않도록 먼저 지운다
DROP FUNCTION IF EXISTS public.wb_record_text_ready(uuid, text);

CREATE OR REPLACE FUNCTION public.wb_record_text_ready(p_record_id uuid, p_text text, p_turns jsonb DEFAULT NULL)
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
  v_turn_lines text;
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
  IF p_turns IS NOT NULL AND (jsonb_typeof(p_turns) <> 'array' OR EXISTS (
      SELECT 1 FROM jsonb_array_elements(p_turns) t
       WHERE jsonb_typeof(t) <> 'object'
          OR jsonb_typeof(t -> 'text') IS DISTINCT FROM 'string'
          OR (t ? 'speaker' AND jsonb_typeof(t -> 'speaker') NOT IN ('string', 'null'))
          OR length(coalesce(t ->> 'speaker', '')) > 40
          OR (t ? 'offsetMs' AND jsonb_typeof(t -> 'offsetMs') NOT IN ('number', 'null'))
          OR (t ->> 'offsetMs')::numeric < 0)) THEN
    RAISE EXCEPTION 'WB400: 화자 정보 형식이 올바르지 않습니다' USING ERRCODE = '22023';
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
    IF p_turns IS NULL THEN
      SELECT coalesce(jsonb_agg(jsonb_build_object('idx', t.n - 1, 'text', t.line) ORDER BY t.n), '[]'::jsonb)
        INTO v_segments
        FROM (
          SELECT row_number() OVER (ORDER BY l.ord) AS n, btrim(l.line) AS line
            FROM regexp_split_to_table(v_text, E'\n') WITH ORDINALITY AS l(line, ord)
           WHERE btrim(l.line) <> ''
        ) t;
    ELSE
      -- 차례마다 글 줄을 나눠 문장 구간으로, 같은 차례의 줄은 같은 화자·시각을 갖는다
      WITH lines AS (
        SELECT row_number() OVER (ORDER BY tr.ord, l.ord) AS n, btrim(l.line) AS line,
               nullif(btrim(tr.t ->> 'speaker'), '') AS speaker, (tr.t ->> 'offsetMs')::bigint AS offset_ms
          FROM jsonb_array_elements(p_turns) WITH ORDINALITY AS tr(t, ord)
          CROSS JOIN LATERAL regexp_split_to_table(tr.t ->> 'text', E'\n') WITH ORDINALITY AS l(line, ord)
         WHERE btrim(l.line) <> ''
      )
      SELECT coalesce(jsonb_agg(jsonb_strip_nulls(jsonb_build_object('idx', n - 1, 'text', line, 'speaker', speaker, 'offsetMs', offset_ms)) ORDER BY n), '[]'::jsonb),
             string_agg(line, E'\n' ORDER BY n)
        INTO v_segments, v_turn_lines
        FROM lines;
      -- 정리 단계는 segments 글을 AI에 넘기므로, 본문과 다른 글이 섞이지 않게 막는다
      IF v_turn_lines IS DISTINCT FROM (
          SELECT string_agg(btrim(l.line), E'\n' ORDER BY l.ord)
            FROM regexp_split_to_table(v_text, E'\n') WITH ORDINALITY AS l(line, ord)
           WHERE btrim(l.line) <> '') THEN
        RAISE EXCEPTION 'WB400: 화자 정보와 본문이 맞지 않습니다' USING ERRCODE = '22023';
      END IF;
    END IF;
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

REVOKE ALL ON FUNCTION public.wb_record_text_ready(uuid, text, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_record_text_ready(uuid, text, jsonb) TO authenticated;
