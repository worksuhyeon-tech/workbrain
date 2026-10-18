-- 참석자(DEC-39, Design S04 Attendees): 녹음 화면·올리기 시트에서 입력해 기록과 함께 올린다.
-- 쓰임: 회의록 참석자 칸, 이름 표기 기준(용어 교정·정리 단계). 참석자 명단만으로 담당자를 정하지 않는다(NFR-02, DEC-17).
ALTER TABLE public.source_record ADD COLUMN IF NOT EXISTS attendees text[] NOT NULL DEFAULT '{}';
ALTER TABLE public.source_record DROP CONSTRAINT IF EXISTS source_record_attendees_check;
ALTER TABLE public.source_record ADD CONSTRAINT source_record_attendees_check
  CHECK (cardinality(attendees) <= 30 AND array_to_string(attendees, '') !~ '[\r\n]' AND coalesce(length(array_to_string(attendees, ',')), 0) <= 900);
