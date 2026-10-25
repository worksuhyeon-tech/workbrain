-- 같은 음성 파일을 다른 기록으로 다시 올리면 새 기록으로 처음부터 다시 처리한다(사용자 결정 2026-10-26).
-- 문제: unique (owner_id, file_hash) + ON CONFLICT DO NOTHING 때문에 같은 파일을 새 기록으로 올리면
-- audio_asset이 만들어지지 않고 작업만 생겨, 받아쓰기가 '음성 파일 정보가 없어요'로 세 번 실패했다(D04 재업로드).
-- 중복 막기(NFR-03)는 원래 같은 기록의 올리기 재시도용이므로 기준을 기록 단위로 좁힌다.

ALTER TABLE public.audio_asset DROP CONSTRAINT IF EXISTS audio_asset_owner_id_file_hash_key;
ALTER TABLE public.audio_asset DROP CONSTRAINT IF EXISTS audio_asset_owner_id_source_record_id_key;
ALTER TABLE public.audio_asset ADD CONSTRAINT audio_asset_owner_id_source_record_id_key UNIQUE (owner_id, source_record_id);

CREATE OR REPLACE FUNCTION public.wb_record_ready(p_record_id uuid, p_bytes bigint, p_duration_sec integer, p_hash text, p_ext text DEFAULT 'm4a'::text)
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
  IF (SELECT count(DISTINCT purpose) FROM public.consent_grant
      WHERE owner_id = v_uid AND purpose IN ('voice-stt', 'text-ai') AND revoked_at IS NULL) < 2 THEN
    RAISE EXCEPTION 'WB403: 외부 전사·분석 동의가 필요합니다' USING ERRCODE = '42501';
  END IF;
  v_key := v_uid::text || '/' || p_record_id::text || '/audio.' || lower(p_ext);
  IF NOT EXISTS (SELECT 1 FROM storage.objects WHERE bucket_id = 'audio' AND name = v_key) THEN
    RAISE EXCEPTION 'WB409: 음성 파일이 아직 올라오지 않았습니다' USING ERRCODE = 'P0002';
  END IF;
  SELECT retention_audio_days INTO v_days FROM public.settings WHERE user_id = v_uid;

  -- 같은 기록의 재시도만 한 번으로 친다. 같은 파일이라도 다른 기록이면 따로 저장해 다시 받아쓴다
  INSERT INTO public.audio_asset (source_record_id, owner_id, storage_key, file_hash, bytes, duration_sec, mime, retention_expires_at)
  VALUES (p_record_id, v_uid, v_key, p_hash, p_bytes, p_duration_sec, v_mime, now() + make_interval(days => coalesce(v_days, 30)))
  ON CONFLICT (owner_id, source_record_id) DO NOTHING;

  INSERT INTO public.processing_job (owner_id, source_record_id, stage, stage_state, execution_key)
  VALUES (v_uid, p_record_id, 'STT', 'PENDING', p_record_id::text || ':pipeline')
  ON CONFLICT (owner_id, execution_key) DO NOTHING;
  SELECT id INTO v_job FROM public.processing_job WHERE owner_id = v_uid AND execution_key = p_record_id::text || ':pipeline';

  RETURN jsonb_build_object('job_id', v_job);
END;
$$;

REVOKE ALL ON FUNCTION public.wb_record_ready(uuid, bigint, integer, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_record_ready(uuid, bigint, integer, text, text) TO authenticated;
