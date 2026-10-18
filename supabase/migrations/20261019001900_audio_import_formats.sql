-- 파일 가져오기(DEC-40): 앱 녹음(m4a) 외에 통화 녹음·다른 앱 녹음 파일도 받는다.
-- 받아쓰기 API가 읽는 형식만 허용한다(m4a·mp4·mp3·wav·ogg·webm·flac). amr·3gp 같은 옛 통화 녹음 형식은 앱이 미리 거절한다.
-- 저장 경로는 <사용자ID>/<기록ID>/audio.<확장자>. 처리 함수는 저장 경로의 확장자와 audio_asset.mime으로 파일을 보낸다.

UPDATE storage.buckets SET allowed_mime_types = ARRAY[
  'audio/mp4', 'audio/m4a', 'audio/x-m4a', 'audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav', 'audio/wave',
  'audio/ogg', 'audio/webm', 'audio/flac', 'audio/x-flac'
] WHERE id = 'audio';

DROP FUNCTION IF EXISTS public.wb_record_ready(uuid, bigint, int, text);

CREATE OR REPLACE FUNCTION public.wb_record_ready(p_record_id uuid, p_bytes bigint, p_duration_sec int, p_hash text, p_ext text DEFAULT 'm4a')
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_key text;
  v_mime text;
  v_days int;
  v_job uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;
  v_mime := CASE lower(p_ext)
    WHEN 'm4a' THEN 'audio/mp4' WHEN 'mp4' THEN 'audio/mp4' WHEN 'mp3' THEN 'audio/mpeg' WHEN 'wav' THEN 'audio/wav'
    WHEN 'ogg' THEN 'audio/ogg' WHEN 'webm' THEN 'audio/webm' WHEN 'flac' THEN 'audio/flac' END;
  IF v_mime IS NULL THEN
    RAISE EXCEPTION 'WB400: 받아쓸 수 없는 음성 형식입니다(%)', p_ext USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.source_record WHERE id = p_record_id AND owner_id = v_uid) THEN
    RAISE EXCEPTION 'WB404: 기록을 찾을 수 없습니다' USING ERRCODE = 'P0002';
  END IF;
  -- 음성 외부 전사·텍스트 AI 분석 동의가 모두 살아 있어야 처리한다(FR-17)
  IF (SELECT count(DISTINCT purpose) FROM public.consent_grant
      WHERE owner_id = v_uid AND purpose IN ('voice-stt', 'text-ai') AND revoked_at IS NULL) < 2 THEN
    RAISE EXCEPTION 'WB403: 외부 전사·분석 동의가 필요합니다' USING ERRCODE = '42501';
  END IF;
  v_key := v_uid::text || '/' || p_record_id::text || '/audio.' || lower(p_ext);
  IF NOT EXISTS (SELECT 1 FROM storage.objects WHERE bucket_id = 'audio' AND name = v_key) THEN
    RAISE EXCEPTION 'WB409: 음성 파일이 아직 올라오지 않았습니다' USING ERRCODE = 'P0002';
  END IF;
  SELECT retention_audio_days INTO v_days FROM public.settings WHERE user_id = v_uid;

  INSERT INTO public.audio_asset (source_record_id, owner_id, storage_key, file_hash, bytes, duration_sec, mime, retention_expires_at)
  VALUES (p_record_id, v_uid, v_key, p_hash, p_bytes, p_duration_sec, v_mime, now() + make_interval(days => coalesce(v_days, 30)))
  ON CONFLICT (owner_id, file_hash) DO NOTHING;

  INSERT INTO public.processing_job (owner_id, source_record_id, stage, stage_state, execution_key)
  VALUES (v_uid, p_record_id, 'STT', 'PENDING', p_record_id::text || ':pipeline')
  ON CONFLICT (owner_id, execution_key) DO NOTHING;
  SELECT id INTO v_job FROM public.processing_job WHERE owner_id = v_uid AND execution_key = p_record_id::text || ':pipeline';

  RETURN jsonb_build_object('job_id', v_job);
END;
$$;

REVOKE ALL ON FUNCTION public.wb_record_ready(uuid, bigint, int, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_record_ready(uuid, bigint, int, text, text) TO authenticated;
