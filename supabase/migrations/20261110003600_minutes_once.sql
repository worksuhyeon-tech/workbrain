-- 회의록 만들기 한 번만(DEC-79).
-- 계기(11/10): 이동 중(LTE) 회의록 만들기를 누르고 화면을 끄자 앱 작업이 응답 전에 멈췄다가 앱을 다시 열 때마다 처음부터 다시 돌아,
-- 서버는 같은 회의록을 네 번 만들었는데(20원) 앱은 실패로 알렸다.
-- 앱은 누를 때마다 요청 열쇠를 하나 만들고, 작업이 멈췄다 다시 돌아도 같은 열쇠로 부른다.
-- 처리 함수는 만드는 동안 그 기록의 처리 작업에 잠금을 걸고 끝나면 결과를 남긴다:
-- 같은 열쇠가 다시 오면 다시 만들지 않고(끝났으면 DONE, 만드는 중이면 RUNNING), 다른 열쇠는 잠금이 풀릴 때까지 BUSY.
-- 앱은 응답을 못 받으면 wb_document_status로 끝났는지 확인한다.

ALTER TABLE public.processing_job
  ADD COLUMN IF NOT EXISTS document_key text,
  ADD COLUMN IF NOT EXISTS document_lease_until timestamptz,
  ADD COLUMN IF NOT EXISTS document_result text;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'processing_job_document_ok') THEN
    ALTER TABLE public.processing_job ADD CONSTRAINT processing_job_document_ok CHECK (
      (document_key IS NULL OR length(document_key) BETWEEN 1 AND 64)
      AND (document_result IS NULL OR document_result IN ('DONE', 'FAILED'))
    );
  END IF;
END $$;

-- 처리 함수(서버 키)만 부른다. 잠금은 170초: 함수 최대 실행 150초가 지나도 안 풀리면 멈춘 것으로 본다
CREATE OR REPLACE FUNCTION public.wb_claim_document(p_job_id uuid, p_key text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_key text;
  v_lease timestamptz;
  v_result text;
BEGIN
  IF p_key IS NULL OR length(p_key) NOT BETWEEN 1 AND 64 THEN
    RAISE EXCEPTION 'invalid document key' USING ERRCODE = '22023';
  END IF;
  -- 같은 기록에 두 요청이 동시에 와도 한쪽만 잡도록 줄을 잠그고 읽는다
  SELECT document_key, document_lease_until, document_result INTO v_key, v_lease, v_result
    FROM public.processing_job WHERE id = p_job_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN 'MISSING';
  END IF;
  IF v_key = p_key AND v_result = 'DONE' THEN
    RETURN 'DONE';
  END IF;
  IF v_lease > now() THEN
    RETURN CASE WHEN v_key = p_key THEN 'RUNNING' ELSE 'BUSY' END;
  END IF;
  UPDATE public.processing_job
     SET document_key = p_key, document_lease_until = now() + interval '170 seconds', document_result = NULL
   WHERE id = p_job_id;
  RETURN 'CLAIMED';
END;
$$;

REVOKE ALL ON FUNCTION public.wb_claim_document(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.wb_claim_document(uuid, text) TO service_role;

-- 앱이 응답을 못 받았을 때 그 요청이 어떻게 됐는지: DONE·FAILED·RUNNING(내 요청 만드는 중)·BUSY(다른 요청 만드는 중)·NONE(내 요청이 서버에 안 옴).
-- 잠금이 풀렸는데 결과가 없으면 함수가 중간에 멈춘 것이라 FAILED. 내 기록이 아니면 NULL
CREATE OR REPLACE FUNCTION public.wb_document_status(p_record_id uuid, p_key text)
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT CASE
           WHEN document_key IS DISTINCT FROM p_key THEN
             CASE WHEN document_lease_until > now() THEN 'BUSY' ELSE 'NONE' END
           WHEN document_result = 'DONE' THEN 'DONE'
           WHEN document_result = 'FAILED' THEN 'FAILED'
           WHEN document_lease_until > now() THEN 'RUNNING'
           ELSE 'FAILED'
         END
    FROM public.processing_job
   WHERE source_record_id = p_record_id AND owner_id = (SELECT auth.uid())
   LIMIT 1;
$$;

REVOKE ALL ON FUNCTION public.wb_document_status(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_document_status(uuid, text) TO authenticated;
