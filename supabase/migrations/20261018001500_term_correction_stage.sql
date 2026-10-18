-- 용어 교정 단계(DEC-36): STT(힌트 없음) → CORRECT(용어집·문맥으로 잘못 들린 업무 용어만 교정) → EXTRACT.
-- 교정본은 새 전사 버전(term-corrected)으로 저장하고 받아쓴 원문(stt-original)은 그대로 둔다.

ALTER TABLE public.processing_job DROP CONSTRAINT processing_job_stage_check;
ALTER TABLE public.processing_job ADD CONSTRAINT processing_job_stage_check
  CHECK (stage = ANY (ARRAY['UPLOAD', 'STT', 'CORRECT', 'EXTRACT', 'DOCUMENT', 'REVIEWABLE']));

ALTER TABLE public.transcript_version DROP CONSTRAINT transcript_version_kind_check;
ALTER TABLE public.transcript_version ADD CONSTRAINT transcript_version_kind_check
  CHECK (kind = ANY (ARRAY['stt-original', 'term-corrected', 'user-edited']));
