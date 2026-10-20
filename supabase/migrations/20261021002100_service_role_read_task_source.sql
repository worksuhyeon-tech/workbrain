-- 기존 할 일 맞춰 보기(DEC-38) 보완(DEC-52): 처리 함수가 기존 할 일이 어느 기록에서 나왔는지 알아야
-- '회의록 정리·메일 발송'처럼 회의마다 생기는 일을 다른 회의의 할 일과 묶지 않는다.
-- 할 일 → 첫 승인 기록 → 후보 → 분석 → 전사 → 기록으로 따라가는 데 필요한 열만 읽게 한다(쓰기 권한 없음).
GRANT SELECT (task_id, version, approval_event_id) ON public.task_revision TO service_role;
GRANT SELECT (id, proposal_id) ON public.approval_event TO service_role;
