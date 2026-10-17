-- 로그인 허용 목록·첫 로그인 준비 (FR-01, DEC-13/26, Design §4.7 M13)
-- Google 로그인은 '새 가입 허용'을 끄면 본인 첫 로그인도 막히므로(DEC-26), 계정이 만들어지기 직전에
-- Supabase Auth 'Before User Created' 훅이 이 함수를 불러 허용 목록에 없는 이메일을 거부한다.
-- 허용 목록의 실제 주소는 저장소에 적지 않고 DB에만 넣는다(공개 저장소 규칙).

create table if not exists public.auth_allowlist (
  email      text primary key check (email = lower(email)),
  note       text,
  created_at timestamptz not null default now()
);
alter table public.auth_allowlist enable row level security;
-- 정책 없음 + 권한 없음: 로그인 사용자·anon은 읽을 수도 없다. 아래 함수(소유자 권한)만 읽는다
revoke all on public.auth_allowlist from anon, authenticated;

-- 훅: 허용이면 {} , 거부면 {"error": {...}} (Supabase Auth 훅 계약)
CREATE OR REPLACE FUNCTION public.wb_before_user_created(event jsonb)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_email text := lower(trim(coalesce(event->'user'->>'email', '')));
BEGIN
  IF v_email = '' OR NOT EXISTS (SELECT 1 FROM public.auth_allowlist WHERE email = v_email) THEN
    -- 어떤 주소가 허용되는지 알리지 않는다
    RETURN jsonb_build_object('error', jsonb_build_object('message', '이 계정은 WorkBrain을 사용할 수 없어요.', 'http_code', 403));
  END IF;
  RETURN '{}'::jsonb;
END;
$$;

REVOKE ALL ON FUNCTION public.wb_before_user_created(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.wb_before_user_created(jsonb) TO supabase_auth_admin;

-- 첫 로그인: 계정이 생기면 앱 사용자·설정·기본 공간('본업')을 만든다.
-- 이 트리거가 오류를 내면 계정 생성(첫 로그인)도 실패하므로, 이미 있는 행은 건너뛰어 겹쳐도 오류가 나지 않게 한다.
-- 허용 목록 검사는 위 훅이 먼저 한다.
CREATE OR REPLACE FUNCTION public.wb_provision_user()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  INSERT INTO public.app_user (id, email) VALUES (NEW.id, coalesce(lower(NEW.email), NEW.id::text)) ON CONFLICT (id) DO NOTHING;
  INSERT INTO public.settings (user_id) VALUES (NEW.id) ON CONFLICT (user_id) DO NOTHING;
  IF NOT EXISTS (SELECT 1 FROM public.workspace WHERE owner_id = NEW.id) THEN
    INSERT INTO public.workspace (owner_id, name, description) VALUES (NEW.id, '본업', '보상시스템 기획');
  END IF;
  RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.wb_provision_user() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS wb_provision_user ON auth.users;
CREATE TRIGGER wb_provision_user AFTER INSERT ON auth.users FOR EACH ROW EXECUTE FUNCTION public.wb_provision_user();
