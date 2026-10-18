-- 처리 함수(process-record, Edge Function)가 서버 키(service_role)로 쓰는 표에 필요한 권한만 준다(DEC-30).
-- 0100~0200에서 표 권한을 명시적으로만 열었는데 service_role이 빠져 있어, 2단계 첫 시험에서 처리 작업 조회가 403으로 막혔다.
-- service_role은 RLS를 건너뛰므로 소유자 검사는 함수 코드가 한다. 권한은 함수가 실제로 하는 동작으로만 제한한다.

GRANT SELECT, UPDATE ON public.processing_job     TO service_role;
GRANT SELECT, UPDATE ON public.source_record      TO service_role;
GRANT SELECT         ON public.audio_asset        TO service_role;
GRANT SELECT         ON public.settings           TO service_role;
GRANT SELECT         ON public.area               TO service_role;
GRANT SELECT         ON public.project            TO service_role;
GRANT SELECT, INSERT ON public.cost_ledger        TO service_role;
GRANT SELECT, INSERT ON public.transcript_version TO service_role;
GRANT SELECT, INSERT ON public.analysis_run       TO service_role;
GRANT SELECT, INSERT ON public.meeting_version    TO service_role;
GRANT SELECT, INSERT ON public.change_proposal    TO service_role;
GRANT SELECT, INSERT ON public.evidence_link      TO service_role;

-- source_record 수정 시 소유 검사 트리거(wb_check_links → wb_owned)가 workspace를 읽는다
GRANT SELECT ON public.workspace TO service_role;
GRANT EXECUTE ON FUNCTION public.wb_owned(text, uuid, uuid) TO service_role;
