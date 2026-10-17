-- 2단계: 녹음 업로드·처리 작업 (FR-03/04/05/17, Design §2.3·§4.7, DEC-30)
-- 음성은 Supabase 저장소 'audio' 버킷의 <사용자ID>/<기록ID>/audio.m4a 에 둔다(본인 폴더만 읽기·쓰기).
-- 업로드가 끝나면 앱이 wb_record_ready를 불러 음성 자산·처리 작업을 만들고, 처리 함수(Edge Function)가 단계별로 진행한다.

-- 비공개 버킷. 한 파일 30MB(60분 약 14MB, 전사 API 25MB 한도는 처리 함수가 다시 확인), m4a만
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('audio', 'audio', false, 30 * 1024 * 1024, array['audio/mp4', 'audio/m4a', 'audio/x-m4a'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;

-- 저장소 권한: 경로 첫 폴더가 자기 사용자 ID인 파일만
drop policy if exists wb_audio_insert on storage.objects;
create policy wb_audio_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text);
drop policy if exists wb_audio_select on storage.objects;
create policy wb_audio_select on storage.objects for select to authenticated
  using (bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text);
drop policy if exists wb_audio_delete on storage.objects;
create policy wb_audio_delete on storage.objects for delete to authenticated
  using (bucket_id = 'audio' and (storage.foldername(name))[1] = (select auth.uid())::text);

-- 업로드 완료 등록: 소유·동의·파일 존재를 확인하고 음성 자산과 전사 작업을 만든다(같은 기록 재호출은 같은 작업을 돌려줌)
CREATE OR REPLACE FUNCTION public.wb_record_ready(p_record_id uuid, p_bytes bigint, p_duration_sec int, p_hash text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_key text;
  v_days int;
  v_job uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.source_record WHERE id = p_record_id AND owner_id = v_uid) THEN
    RAISE EXCEPTION 'WB404: 기록을 찾을 수 없습니다' USING ERRCODE = 'P0002';
  END IF;
  -- 음성 외부 전사·텍스트 AI 분석 동의가 모두 살아 있어야 처리한다(FR-17)
  IF (SELECT count(DISTINCT purpose) FROM public.consent_grant
      WHERE owner_id = v_uid AND purpose IN ('voice-stt', 'text-ai') AND revoked_at IS NULL) < 2 THEN
    RAISE EXCEPTION 'WB403: 외부 전사·분석 동의가 필요합니다' USING ERRCODE = '42501';
  END IF;
  v_key := v_uid::text || '/' || p_record_id::text || '/audio.m4a';
  IF NOT EXISTS (SELECT 1 FROM storage.objects WHERE bucket_id = 'audio' AND name = v_key) THEN
    RAISE EXCEPTION 'WB409: 음성 파일이 아직 올라오지 않았습니다' USING ERRCODE = 'P0002';
  END IF;
  SELECT retention_audio_days INTO v_days FROM public.settings WHERE user_id = v_uid;

  INSERT INTO public.audio_asset (source_record_id, owner_id, storage_key, file_hash, bytes, duration_sec, mime, retention_expires_at)
  VALUES (p_record_id, v_uid, v_key, p_hash, p_bytes, p_duration_sec, 'audio/mp4', now() + make_interval(days => coalesce(v_days, 30)))
  ON CONFLICT (owner_id, file_hash) DO NOTHING;

  INSERT INTO public.processing_job (owner_id, source_record_id, stage, stage_state, execution_key)
  VALUES (v_uid, p_record_id, 'STT', 'PENDING', p_record_id::text || ':pipeline')
  ON CONFLICT (owner_id, execution_key) DO NOTHING;
  SELECT id INTO v_job FROM public.processing_job WHERE owner_id = v_uid AND execution_key = p_record_id::text || ':pipeline';

  RETURN jsonb_build_object('job_id', v_job);
END;
$$;

-- 처리 함수가 작업을 잡는다: 대기·실패(재시도 3회 이내)·임대 만료된 실행 중만. 두 번 동시에 잡히지 않는다
CREATE OR REPLACE FUNCTION public.wb_claim_job(p_job_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  UPDATE public.processing_job
     SET stage_state = 'RUNNING', lease_until = now() + interval '3 minutes', updated_at = now()
   WHERE id = p_job_id
     AND stage IN ('STT', 'EXTRACT')
     AND (stage_state = 'PENDING'
          OR (stage_state = 'FAILED' AND retry_count < 3)
          OR (stage_state = 'RUNNING' AND lease_until < now()));
  RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION public.wb_record_ready(uuid, bigint, int, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_record_ready(uuid, bigint, int, text) TO authenticated;
REVOKE ALL ON FUNCTION public.wb_claim_job(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.wb_claim_job(uuid) TO service_role;
