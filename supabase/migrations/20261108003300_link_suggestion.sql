-- FR-06 AI 프로젝트 연결 제안(DEC-75, 2026-11-08, Design §3.11)
-- 처리 함수(process-record, service_role)가 기록 단위 연결 제안을 source_project_link에 'proposed'로 남긴다.
-- 사용자의 확인([연결]·[아니요])은 0032의 wb_link_record_project를 그대로 쓴다(같은 행을 confirmed·rejected로).
-- service_role은 RLS를 건너뛰므로 '내 기록·내 공간의 진행 중 프로젝트'인지는 함수 코드가 확인한다(0012와 같은 방식).

-- ── 1. 처리 함수 권한: 이미 있는 연결 확인(거절한 제안을 다시 만들지 않기)과 제안 넣기만 ──
GRANT SELECT, INSERT ON public.source_project_link TO service_role;

-- ── 2. 확신 등급은 정해진 값만(Design §3.10, 숫자 확률 아님) ──
ALTER TABLE public.source_project_link DROP CONSTRAINT IF EXISTS source_project_link_confidence_check;
ALTER TABLE public.source_project_link ADD CONSTRAINT source_project_link_confidence_check
  CHECK (confidence IN ('high', 'medium', 'low', 'unrated'));
