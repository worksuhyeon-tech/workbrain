-- 서버 음성 보관 기한 자동 삭제(DEC-35, 설정 retention_audio_days 기본 30일).
-- 매일 한 번 pg_cron이 purge-audio 함수를 부르고, 함수가 기한 지난 음성 파일을 저장소에서 지운 뒤 purged_at을 남긴다.
-- 파일 삭제는 저장소 API로만 한다(storage.objects 행을 SQL로 지우면 실제 파일이 남는다).
-- 전사·회의록·할 일은 각자의 보관 기한을 따르므로 여기서 건드리지 않는다(원본만 삭제, PRD 불변 조건).

ALTER TABLE public.audio_asset ADD COLUMN IF NOT EXISTS purged_at timestamptz;

-- 처리 함수가 기한 지난 행만 고르고 지운 표시를 남길 수 있게 한다(다른 열은 못 바꾼다)
GRANT UPDATE (purged_at) ON public.audio_asset TO service_role;

CREATE INDEX IF NOT EXISTS audio_asset_expiry_idx ON public.audio_asset (retention_expires_at) WHERE purged_at IS NULL;

CREATE EXTENSION IF NOT EXISTS pg_cron;
CREATE EXTENSION IF NOT EXISTS pg_net;

-- 매일 03:30(서울) = 18:30 UTC. 같은 이름 작업이 있으면 바꿔 끼운다
SELECT cron.unschedule(jobid) FROM cron.job WHERE jobname = 'wb-purge-audio';
SELECT cron.schedule(
  'wb-purge-audio',
  '30 18 * * *',
  $$SELECT net.http_post(
      url := 'https://xupzzlpmysnljxlmygjy.supabase.co/functions/v1/purge-audio',
      headers := '{"content-type": "application/json"}'::jsonb,
      body := '{}'::jsonb
    )$$
);
