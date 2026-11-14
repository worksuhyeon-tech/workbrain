-- 0042 구글 캘린더 보기·넣기(FR-24a, DEC-105, 2026-11-13·14 사용자 결정, Design §3.16)
-- 로그인한 같은 Google 계정의 기본 캘린더 하나. 연결 열쇠(refresh token)는 처리 함수 `calendar`가 받아 Vault(DB 안 암호화 저장)에 넣고,
-- 앱·웹은 열쇠를 보지 못한다(연결 상태만 읽음). 일정 자체는 서버에 저장하지 않는다(부를 때마다 Google에서 읽음).
-- 할 일 ↔ 일정 연결은 task 표가 아닌 별도 표(task_calendar)에 둔다: task의 버전 올림(wb_version_bump)·이력(wb_task_revision) 규칙이
-- 함수가 일정 번호만 적는 것까지 '사용자 수정'으로 다루지 않게 하려는 것.
-- 연결 끊기는 행을 지우지 않고 열쇠를 빈 값으로 덮어쓰고 connected_at을 비운다(다시 연결하면 같은 Vault 비밀을 고쳐 씀).
-- 다시 만들 수 있는 연결 정보라 백업 표 목록(wb_backup_tables)에는 넣지 않는다. 계정을 지우면 함께 지워진다(app_user cascade).
-- 원격 SQL 확인 창이 만료되므로 DROP·DELETE 없이 쓴다. 다시 실행해도 같은 결과(IF NOT EXISTS·CREATE OR REPLACE·DO 블록).

-- ① 연결: 사람마다 한 줄. 열쇠는 Vault에, 여기엔 그 비밀 번호만
CREATE TABLE IF NOT EXISTS public.calendar_link (
  owner_id      uuid PRIMARY KEY REFERENCES public.app_user(id) ON DELETE CASCADE,
  secret_id     uuid NOT NULL,
  connected_at  timestamptz,            -- NULL = 연결 끊음
  broken_at     timestamptz             -- Google이 열쇠를 거절함(invalid_grant) — 다시 연결이 필요
);

-- ② 캘린더 허락 1회용 확인값(state): 함수만 읽고 쓴다. 10분 안에 한 번만 쓸 수 있다(함수가 used_at으로 확인)
CREATE TABLE IF NOT EXISTS public.calendar_oauth_state (
  nonce       text PRIMARY KEY CHECK (char_length(nonce) BETWEEN 32 AND 128),
  owner_id    uuid NOT NULL REFERENCES public.app_user(id) ON DELETE CASCADE,
  -- 허락 뒤 돌아갈 곳: 정해 둔 셋만(열린 리디렉션 방지)
  return_to   text NOT NULL CHECK (return_to IN ('app', 'web', 'web-local')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  used_at     timestamptz
);

-- ③ 할 일 ↔ 일정: WB가 넣은 일정 번호와 '고쳤는데 캘린더에 아직 반영 안 됨' 표시. 할 일을 지우면 함께 지워진다
CREATE TABLE IF NOT EXISTS public.task_calendar (
  task_id     uuid PRIMARY KEY REFERENCES public.task(id) ON DELETE CASCADE,
  owner_id    uuid NOT NULL REFERENCES public.app_user(id) ON DELETE CASCADE,
  event_id    text NOT NULL CHECK (char_length(event_id) BETWEEN 1 AND 1024),
  dirty       boolean NOT NULL DEFAULT false,
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS task_calendar_dirty_idx ON public.task_calendar (owner_id) WHERE dirty;

-- ④ 권한: 앱·웹은 내 연결 상태와 내 할 일의 일정 연결만 읽는다. 쓰기는 처리 함수(서비스 권한)만
ALTER TABLE public.calendar_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.calendar_oauth_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.task_calendar ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'calendar_link' AND policyname = 'own_select') THEN
    CREATE POLICY own_select ON public.calendar_link FOR SELECT TO authenticated USING (owner_id = (SELECT auth.uid()));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'task_calendar' AND policyname = 'own_select') THEN
    CREATE POLICY own_select ON public.task_calendar FOR SELECT TO authenticated USING (owner_id = (SELECT auth.uid()));
  END IF;
END
$$;
-- 비밀 번호(secret_id)는 앱·웹에 보이지 않게 칸 단위로만 연다. 확인값 표는 앱·웹에 아무 권한도 없다(정책도 없음)
GRANT SELECT (owner_id, connected_at, broken_at) ON public.calendar_link TO authenticated;
GRANT SELECT (task_id, event_id, dirty) ON public.task_calendar TO authenticated;
GRANT SELECT, UPDATE (broken_at) ON public.calendar_link TO service_role;
GRANT SELECT, INSERT, UPDATE ON public.calendar_oauth_state TO service_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.task_calendar TO service_role;

-- ⑤ 일정이 있는 할 일의 제목·기한이 바뀌면, 어느 경로든(서랍·빠른 입력·AI 변경 승인) '반영 필요'로 표시한다.
-- 앱·웹은 할 일 목록을 다시 읽을 때 이 표시를 보고 함수 sync를 부른다(서버 크론 없음). 앱 권한은 task_calendar를 못 고쳐 SECURITY DEFINER
CREATE OR REPLACE FUNCTION public.wb_task_calendar_dirty()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  IF (NEW.title, NEW.due_date, NEW.due_time) IS DISTINCT FROM (OLD.title, OLD.due_date, OLD.due_time) THEN
    UPDATE public.task_calendar c SET dirty = true, updated_at = now()
     WHERE c.task_id = NEW.id AND NOT c.dirty;
  END IF;
  RETURN NULL;
END;
$$;
CREATE OR REPLACE TRIGGER wb_task_calendar_dirty AFTER UPDATE OF title, due_date, due_time ON public.task
  FOR EACH ROW EXECUTE FUNCTION public.wb_task_calendar_dirty();

-- ⑥ 열쇠 넣기·읽기·지우기(처리 함수만). 열쇠는 Vault에만 두고 이 함수들로만 다룬다
CREATE OR REPLACE FUNCTION public.wb_calendar_save_token(p_owner uuid, p_token text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_secret uuid;
BEGIN
  IF p_token IS NULL OR char_length(p_token) < 10 THEN
    RAISE EXCEPTION 'WB422: 연결 열쇠가 비었어요' USING ERRCODE = '23514';
  END IF;
  SELECT l.secret_id INTO v_secret FROM public.calendar_link l WHERE l.owner_id = p_owner;
  IF FOUND THEN
    PERFORM vault.update_secret(v_secret, p_token);
    UPDATE public.calendar_link SET connected_at = now(), broken_at = NULL WHERE owner_id = p_owner;
  ELSE
    v_secret := vault.create_secret(p_token, 'wb_calendar_' || p_owner::text, 'WorkBrain Google Calendar refresh token');
    INSERT INTO public.calendar_link (owner_id, secret_id, connected_at) VALUES (p_owner, v_secret, now());
  END IF;
END;
$$;

-- 연결돼 있을 때만 열쇠를 돌려준다(끊었으면 NULL)
CREATE OR REPLACE FUNCTION public.wb_calendar_token(p_owner uuid)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_token text;
BEGIN
  SELECT s.decrypted_secret INTO v_token
    FROM public.calendar_link l
    JOIN vault.decrypted_secrets s ON s.id = l.secret_id
   WHERE l.owner_id = p_owner AND l.connected_at IS NOT NULL;
  IF NOT FOUND OR v_token IS NULL OR v_token = '' THEN
    RETURN NULL;
  END IF;
  RETURN v_token;
END;
$$;

-- 연결 끊기: 행을 지우지 않고 열쇠를 빈 값으로 덮어쓴다
CREATE OR REPLACE FUNCTION public.wb_calendar_forget(p_owner uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_secret uuid;
BEGIN
  SELECT l.secret_id INTO v_secret FROM public.calendar_link l WHERE l.owner_id = p_owner;
  IF NOT FOUND THEN
    RETURN;
  END IF;
  PERFORM vault.update_secret(v_secret, '');
  UPDATE public.calendar_link SET connected_at = NULL, broken_at = NULL WHERE owner_id = p_owner;
END;
$$;

REVOKE ALL ON FUNCTION public.wb_task_calendar_dirty() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_calendar_save_token(uuid, text) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_calendar_token(uuid) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_calendar_forget(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.wb_calendar_save_token(uuid, text) TO service_role;
GRANT EXECUTE ON FUNCTION public.wb_calendar_token(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.wb_calendar_forget(uuid) TO service_role;
