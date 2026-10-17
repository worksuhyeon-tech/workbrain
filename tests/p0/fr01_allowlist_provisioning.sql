-- P0 실측: FR-01 로그인 허용 목록 훅·첫 로그인 준비 (마이그레이션 0800)
-- 서비스 권한으로 통째로 실행. 합성 주소(p0-allowed@example.test)만 쓰고 끝에 지운다. 결과는 마지막 SELECT.
-- 실제 훅 연결(대시보드 Auth Hooks)과 Google 로그인 거부 화면은 Google OAuth 연결 뒤 따로 확인한다.
create temp table if not exists p0_result (seq serial, name text, pass boolean, detail text);
truncate p0_result;
do $$
declare
  U uuid := '00000000-0000-4000-a000-0000000000e1';
  r jsonb; n int; v_err text;
begin
  insert into public.auth_allowlist (email, note) values ('p0-allowed@example.test', 'P0 시험') on conflict do nothing;

  r := public.wb_before_user_created('{"user":{"email":"p0-allowed@example.test","app_metadata":{"provider":"google"}}}');
  insert into p0_result values (default, 'FR-01 허용 목록 이메일 → 통과', r = '{}'::jsonb, r::text);
  r := public.wb_before_user_created('{"user":{"email":"P0-Allowed@Example.test "}}');
  insert into p0_result values (default, 'FR-01 대소문자·공백 달라도 같은 주소로 인정', r = '{}'::jsonb, r::text);
  r := public.wb_before_user_created('{"user":{"email":"stranger@example.test","app_metadata":{"provider":"google"}}}');
  insert into p0_result values (default, 'FR-01 목록 밖 이메일 → 403 거부', (r->'error'->>'http_code')::int = 403, r::text);
  r := public.wb_before_user_created('{"user":{"phone":"01000000000"}}');
  insert into p0_result values (default, 'FR-01 이메일 없는 가입 → 거부', r ? 'error', r::text);

  -- 첫 로그인 준비: auth.users에 계정이 생기면 앱 사용자·설정·'본업' 공간이 생긴다
  delete from auth.users where id = U;
  insert into auth.users (id, aud, role, email, created_at, updated_at) values (U, 'authenticated', 'authenticated', 'p0-allowed@example.test', now(), now());
  select (select count(*) from public.app_user where id = U) + (select count(*) from public.settings where user_id = U)
       + (select count(*) from public.workspace where owner_id = U and name = '본업') into n;
  insert into p0_result values (default, 'FR-01 첫 로그인 → 사용자·설정·본업 공간 자동 생성', n = 3, n || '/3');

  -- 권한: 로그인 사용자는 허용 목록을 읽거나 훅을 부를 수 없다
  perform set_config('request.jwt.claims', json_build_object('sub', U, 'role', 'authenticated')::text, true);
  set local role authenticated;
  v_err := null; begin select count(*) into n from public.auth_allowlist; exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'FR-01 로그인 사용자 허용 목록 읽기 → 거부', v_err is not null, coalesce(v_err, n || '건 보임')); set local role authenticated;
  v_err := null; begin r := public.wb_before_user_created('{}'); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'FR-01 로그인 사용자 훅 직접 호출 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  delete from auth.users where id = U;
  delete from public.auth_allowlist where email = 'p0-allowed@example.test';
end
$$;
select seq, case when pass then 'PASS' else 'FAIL' end as result, name, detail from p0_result order by seq;
