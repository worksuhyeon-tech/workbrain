-- 회의록 문서 단계(DOCUMENT, DEC-56)도 처리 함수가 작업을 잡을 수 있게 한다.
-- 없으면 EXTRACT 뒤 DOCUMENT로 넘어간 작업을 아무도 잡지 못해 '처리 중'에서 멈춘다.

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
     AND stage IN ('STT', 'CORRECT', 'EXTRACT', 'DOCUMENT')
     AND (stage_state = 'PENDING'
          OR (stage_state = 'FAILED' AND retry_count < 3)
          OR (stage_state = 'RUNNING' AND lease_until < now()));
  RETURN FOUND;
END;
$$;
