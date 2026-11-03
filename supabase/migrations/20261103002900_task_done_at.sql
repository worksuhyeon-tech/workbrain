-- 할 일 완료 시각(DEC-64, 실사용 개선 Plan P1.5-A의 task.done_at을 앞당김)
-- '오늘' 탭이 오늘 완료한 일을 취소선으로 남기려면 언제 완료했는지가 필요하다.
-- 앱이 직접 쓰지 않고 트리거가 상태 변화를 보고 채운다: 완료로 바뀌면 지금 시각, 완료를 취소하면 null.
-- 로그인 사용자에게 이 열의 쓰기 권한은 주지 않는다(0200의 열 단위 권한 그대로).
-- 이 기능 전에 완료한 할 일은 완료 시각이 없어 '오늘 완료'에 나오지 않는다(지난 완료는 원래 보이지 않으므로 채우지 않음)

ALTER TABLE public.task ADD COLUMN IF NOT EXISTS done_at timestamptz;

CREATE OR REPLACE FUNCTION public.wb_task_done_at()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.status = 'done' THEN
    IF TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'done' THEN
      NEW.done_at := now();
    ELSE
      -- 완료 상태에서 다른 칸만 고치면 완료 시각은 그대로 둔다
      NEW.done_at := OLD.done_at;
    END IF;
  ELSE
    NEW.done_at := NULL;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS wb_task_done_at ON public.task;
CREATE TRIGGER wb_task_done_at BEFORE INSERT OR UPDATE ON public.task
  FOR EACH ROW EXECUTE FUNCTION public.wb_task_done_at();

REVOKE ALL ON FUNCTION public.wb_task_done_at() FROM PUBLIC, anon, authenticated;

-- '오늘 완료'를 고르는 조회(본인 할 일 중 최근 완료)
CREATE INDEX IF NOT EXISTS task_owner_done_at_idx ON public.task (owner_id, done_at) WHERE done_at IS NOT NULL;
