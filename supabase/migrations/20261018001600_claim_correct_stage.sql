-- 0015에서 CORRECT 단계를 추가하면서 작업 잡기 함수의 단계 목록을 빠뜨렸다.
-- 그 결과 CORRECT 작업을 아무도 잡지 못해 'RUNNING'만 돌려주고 멈춰 있었다(2026-10-18 실기기에서 발견).
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
     AND stage IN ('STT', 'CORRECT', 'EXTRACT')
     AND (stage_state = 'PENDING'
          OR (stage_state = 'FAILED' AND retry_count < 3)
          OR (stage_state = 'RUNNING' AND lease_until < now()));
  RETURN FOUND;
END;
$$;
