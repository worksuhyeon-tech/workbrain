-- 기록 종류(회의·통화·메모). 추출 규칙이 다르다: 메모의 1인칭은 '나', 회의·통화의 1인칭은 '확인 필요'(DEC-27)
alter table public.source_record
  add column if not exists record_kind text not null default 'meeting'
  check (record_kind in ('meeting', 'call', 'memo'));
