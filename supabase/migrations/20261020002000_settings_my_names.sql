-- 내 이름·호칭(DEC-49): 더보기 '내 계정' 카드에서 입력한다.
-- 쓰임: 회의·통화에서 AI가 담당자로 뽑은 이름이 이 목록에 있으면 처리 함수가 담당을 '나'로 바꾼다(지시문은 그대로, 정해진 규칙).
-- 받아쓰기 교정 단계에는 참석자처럼 이름 표기 기준으로 넘긴다. 새로 처리하는 기록부터 적용한다.
ALTER TABLE public.settings ADD COLUMN IF NOT EXISTS my_names text[] NOT NULL DEFAULT '{}';
ALTER TABLE public.settings DROP CONSTRAINT IF EXISTS settings_my_names_check;
ALTER TABLE public.settings ADD CONSTRAINT settings_my_names_check
  CHECK (cardinality(my_names) <= 5 AND array_to_string(my_names, '') !~ '[\r\n]' AND coalesce(length(array_to_string(my_names, ',')), 0) <= 120);
GRANT UPDATE (my_names) ON public.settings TO authenticated;
