-- FR-06 AI 연결 제안 이유 한 줄(DEC-75 보완, 2026-11-09 사용자 선택 '이유 한 줄 + 문장 하나')
-- 근거 문장만으로는 왜 그 프로젝트인지 와닿지 않아서(회의 초반 예시 대화가 근거로 보였음), 처리 함수(link-v2)가 이유를 한 줄 남긴다.
-- 이미 만든 제안(link-v1)은 이유가 비어 있고 다시 만들지 않는다. 앱은 이유가 없으면 근거 문장만 보여 준다.
-- 읽기(authenticated)·넣기(service_role)는 표 전체 권한이라 새 칸도 그대로 열린다(0033).

ALTER TABLE public.source_project_link ADD COLUMN IF NOT EXISTS rationale text;

ALTER TABLE public.source_project_link DROP CONSTRAINT IF EXISTS source_project_link_rationale_check;
ALTER TABLE public.source_project_link ADD CONSTRAINT source_project_link_rationale_check
  CHECK (rationale IS NULL OR char_length(rationale) <= 200);
