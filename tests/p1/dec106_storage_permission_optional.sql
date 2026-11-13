-- P1 실측: DEC-106 업로드 전 저장 허용 질문 폐지(마이그레이션 0041) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0041 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다. 사용자는 합성 시험 계정 A 하나다(@example.test).
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000041a1';
  wsA uuid;
  before_nulls int;
  v_err text;
  res text[] := '{}';
begin
  -- 기존 기록은 모두 값이 있어야 한다(이 마이그레이션은 기존 값을 건드리지 않음)
  select count(*) into before_nulls from public.source_record where storage_permission is null;

  -- ── 0. 시드(서비스 권한) ──
  insert into auth.users (id, aud, role, email, created_at, updated_at) values
    (A, 'authenticated', 'authenticated', 'p1-perm-a@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-perm-a@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;

  -- A가 앱·웹에서 만드는 것(소유자는 auth.uid 기본값)
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;

  -- ── 1. 묻지 않은 새 기록(값 없음)을 만들 수 있다 ──
  begin
    insert into public.source_record (workspace_id, channel, title, occurred_at, record_kind, attendees)
    values (wsA, 'upload', '시험 기록(값 없음)', now(), 'meeting', '{}');
    res := res || 'PASS 값 없이 새 기록'::text;
  exception when others then
    get stacked diagnostics v_err = message_text;
    res := res || format('FAIL 값 없이 새 기록 (%s)', v_err);
  end;

  -- ── 2. 예전 앱이 보내는 값은 그대로 받는다 ──
  begin
    insert into public.source_record (workspace_id, channel, title, occurred_at, record_kind, attendees, storage_permission)
    values (wsA, 'recording', '시험 기록(예전 값)', now(), 'call', '{}', 'personal');
    res := res || 'PASS 예전 값(personal)'::text;
  exception when others then
    get stacked diagnostics v_err = message_text;
    res := res || format('FAIL 예전 값(personal) (%s)', v_err);
  end;

  -- ── 3. 범위 밖 값은 여전히 거부(값 범위 제약 그대로) ──
  begin
    insert into public.source_record (workspace_id, channel, title, occurred_at, record_kind, attendees, storage_permission)
    values (wsA, 'upload', '시험 기록(잘못된 값)', now(), 'meeting', '{}', 'deny');
    res := res || 'FAIL 범위 밖 값이 들어감'::text;
  exception when check_violation then
    res := res || 'PASS 범위 밖 값(deny) 거부'::text;
  end;

  reset role;

  -- ── 4. 기존 기록 값은 그대로(값 없는 기존 기록 수가 그대로 0) ──
  res := res || format('%s 기존 기록 그대로 (값 없는 기존 기록 %s)', case when before_nulls = 0 then 'PASS' else 'FAIL' end, before_nulls);

  -- ── 5. 칸 성질: NULL 허용, 값 범위 제약은 남아 있음 ──
  res := res || format('%s NULL 허용·제약 유지',
    case when (select is_nullable from information_schema.columns where table_schema = 'public' and table_name = 'source_record' and column_name = 'storage_permission') = 'YES'
              and exists (select 1 from pg_constraint where conname = 'source_record_storage_permission_check')
         then 'PASS' else 'FAIL' end);

  -- 결과를 예외로 돌려주며 모든 변경을 되돌린다(합성 계정도 남지 않음)
  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
