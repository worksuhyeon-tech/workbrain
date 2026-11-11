-- 0038 업무계획 저장(FR-33, DEC-95, 2026-11-11 사용자 결정)
-- 업무계획은 회의록처럼 [업무계획 만들기]를 눌러야 만들고, 만든 결과를 서버에 남긴다.
-- 그래서 화면을 나갔다 와도·앱을 껐다 켜도·다른 기기(S23·Fold8·나중 PC)에서도 같은 결과를 보고, Word를 다시 받을 때 AI를 또 부르지 않는다.
-- 같은 사람·종류·기간(전주·전월 칸 시작일)은 한 줄: [다시 만들기]면 고쳐 쓴다(Word로 받아 고쳐 내는 초안이라 이력은 두지 않는다).
-- 승인 절차는 없다(DEC-95 ②). 쓰기는 처리 함수(process-record, 서비스 권한)만, 앱은 읽기만 한다.
-- 다시 만들 수 있는 초안이라 백업 표 목록(wb_backup_tables)에는 넣지 않는다. 계정을 지우면 함께 지워진다(app_user cascade).
-- 원격 SQL 확인 창이 만료되므로 DROP·DELETE 없이 쓴다. 다시 실행해도 같은 결과(IF NOT EXISTS·DO 블록).

CREATE TABLE IF NOT EXISTS public.work_plan (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id      uuid NOT NULL REFERENCES public.app_user(id) ON DELETE CASCADE,
  kind          text NOT NULL CHECK (kind IN ('weekly','monthly')),
  -- 전주·전월 칸 시작일: 주간 = 그 주 월요일, 월간 = 그달 1일(_shared/work-plan.ts periods와 같음)
  period_start  date NOT NULL,
  -- 화면 미리보기·Word에 쓰는 글 그대로(_shared/work-plan.ts WorkPlan)
  plan          jsonb NOT NULL CHECK (jsonb_typeof(plan) = 'object' AND octet_length(plan::text) <= 200000),
  -- □ 줄 AI 결과: done(다듬음) · none(넣을 곳 없음) · budget(예산 대기선) · no-key · failed — 뒤 셋은 규칙 줄 그대로
  ai            text NOT NULL CHECK (ai IN ('done','none','budget','no-key','failed')),
  made_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT work_plan_period_ok CHECK (
    (kind = 'weekly' AND extract(isodow FROM period_start) = 1) OR
    (kind = 'monthly' AND extract(day FROM period_start) = 1)
  )
);
CREATE UNIQUE INDEX IF NOT EXISTS work_plan_owner_period_uq ON public.work_plan (owner_id, kind, period_start);

ALTER TABLE public.work_plan ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'work_plan' AND policyname = 'own_select') THEN
    CREATE POLICY own_select ON public.work_plan FOR SELECT TO authenticated USING (owner_id = (SELECT auth.uid()));
  END IF;
END
$$;
GRANT SELECT ON public.work_plan TO authenticated;
GRANT SELECT, INSERT, UPDATE ON public.work_plan TO service_role;
