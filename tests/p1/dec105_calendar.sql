-- P1.5 실측: FR-24a 구글 캘린더 보기·넣기(DEC-105, 마이그레이션 0042) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정·Vault 비밀 포함)을 되돌린다.
--   반영 전 시험은 0042 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다. 사용자는 합성 계정 A·B(@example.test), 열쇠는 가짜 문자열.
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000042a1';
  B uuid := '00000000-0000-4000-a000-0000000042b1';
  tLinked uuid; tOther uuid; tPlain uuid;
  v_err text; v_err2 text; v_tok text; n int; n2 int; f boolean; f2 boolean;
  res text[] := '{}';
begin
  -- ── 0. 시드(서비스 권한) ──
  insert into auth.users (id, aud, role, email, created_at, updated_at) values
    (A, 'authenticated', 'authenticated', 'p1-cal-a@example.test', now(), now()),
    (B, 'authenticated', 'authenticated', 'p1-cal-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-cal-a@example.test'), (B, 'p1-cal-b@example.test') on conflict (id) do nothing;

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.task (title, due_date) values ('캘린더에 넣은 할 일', '2026-11-20') returning id into tLinked;
  insert into public.task (title, due_date, due_time) values ('캘린더에 넣은 다른 할 일', '2026-11-21', '2026-11-21 15:00+09') returning id into tOther;
  insert into public.task (title) values ('캘린더에 안 넣은 할 일') returning id into tPlain;
  reset role;

  -- ── 1. 열쇠 넣기·읽기(처리 함수 = 서비스 권한) ──
  perform public.wb_calendar_save_token(A, 'fake-refresh-token-A-0001');
  v_tok := public.wb_calendar_token(A);
  res := res || format('%s 열쇠 넣고 읽기', case when v_tok = 'fake-refresh-token-A-0001' then 'PASS' else 'FAIL' end);

  -- ── 2. 다시 연결하면 같은 줄·같은 Vault 비밀을 고쳐 씀 ──
  perform public.wb_calendar_save_token(A, 'fake-refresh-token-A-0002');
  select count(*) into n from public.calendar_link where owner_id = A;
  select count(*) into n2 from vault.secrets where name = 'wb_calendar_' || A::text;
  v_tok := public.wb_calendar_token(A);
  res := res || format('%s 다시 연결은 고쳐 씀 (연결 %s줄·비밀 %s개)',
    case when n = 1 and n2 = 1 and v_tok = 'fake-refresh-token-A-0002' then 'PASS' else 'FAIL' end, n, n2);

  -- ── 3. 빈 열쇠 거부 ──
  v_err := null;
  begin perform public.wb_calendar_save_token(A, ''); exception when others then v_err := sqlstate; end;
  res := res || format('%s 빈 열쇠 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- 서비스 권한으로 일정 연결 두 개
  insert into public.task_calendar (task_id, owner_id, event_id) values (tLinked, A, 'evt-linked'), (tOther, A, 'evt-other');

  -- ── 4. 앱 권한: 내 연결 상태는 읽고, 비밀 번호·열쇠는 못 읽음 ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.calendar_link where connected_at is not null;
  v_err := null;
  begin perform l.secret_id from public.calendar_link l; exception when others then v_err := sqlstate; end;
  res := res || format('%s 앱은 연결 상태만 읽음 (상태 %s줄 / 비밀 번호 %s)', case when n = 1 and v_err = '42501' then 'PASS' else 'FAIL' end, n, coalesce(v_err, '읽힘'));

  v_err := null;
  begin perform public.wb_calendar_token(A); exception when others then v_err := sqlstate; end;
  res := res || format('%s 앱은 열쇠 함수 못 부름 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '불림'));

  v_err := null;
  begin perform s.nonce from public.calendar_oauth_state s; exception when others then v_err := sqlstate; end;
  res := res || format('%s 앱은 확인값 표 못 읽음 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '읽힘'));

  -- ── 5. 앱 권한: 내 일정 연결은 읽고, 직접 쓰지는 못함 ──
  select count(*) into n from public.task_calendar;
  v_err := null;
  begin insert into public.task_calendar (task_id, owner_id, event_id) values (tPlain, A, 'evt-forged'); exception when others then v_err := sqlstate; end;
  v_err2 := null;
  begin update public.task_calendar set event_id = 'evt-forged' where task_id = tLinked; exception when others then v_err2 := sqlstate; end;
  res := res || format('%s 앱은 일정 연결 읽기만 (읽음 %s줄 / 넣기 %s / 고치기 %s)',
    case when n = 2 and v_err = '42501' and v_err2 = '42501' then 'PASS' else 'FAIL' end, n, coalesce(v_err, '허용됨'), coalesce(v_err2, '허용됨'));

  -- ── 6. 트리거: 일정이 있는 할 일의 제목·기한이 바뀔 때만 '반영 필요' ──
  update public.task set title = '캘린더에 넣은 할 일(고침)', version = version + 1 where id = tLinked;
  update public.task set priority = 1, version = version + 1 where id = tOther;
  update public.task set due_date = '2026-11-25', version = version + 1 where id = tPlain;
  reset role;
  select dirty into f from public.task_calendar where task_id = tLinked;
  select dirty into f2 from public.task_calendar where task_id = tOther;
  select count(*) into n from public.task_calendar where task_id = tPlain;
  res := res || format('%s 제목 바꾸면 반영 필요·우선순위만이면 그대로·연결 없는 할 일은 줄 안 생김 (%s / %s / %s줄)',
    case when f and not f2 and n = 0 then 'PASS' else 'FAIL' end, f, f2, n);

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  update public.task set due_time = '2026-11-21 16:00+09', version = version + 1 where id = tOther;
  reset role;
  select dirty into f2 from public.task_calendar where task_id = tOther;
  res := res || format('%s 기한 시각 바꾸면 반영 필요 (%s)', case when f2 then 'PASS' else 'FAIL' end, f2);

  -- ── 7. 다른 사람(B)은 A의 연결·일정 연결을 못 봄 ──
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.calendar_link;
  select count(*) into n2 from public.task_calendar;
  reset role;
  res := res || format('%s 다른 사람은 못 봄 (연결 %s·일정 %s)', case when n = 0 and n2 = 0 then 'PASS' else 'FAIL' end, n, n2);

  -- ── 8. 함수가 일정 연결을 적어도 할 일 버전·이력은 그대로 ──
  select count(*) into n from public.task_revision where task_id = tLinked;
  update public.task_calendar set dirty = false, event_id = 'evt-linked-2', updated_at = now() where task_id = tLinked;
  select count(*) into n2 from public.task_revision where task_id = tLinked;
  res := res || format('%s 일정 연결 고쳐도 할 일 이력 그대로 (%s → %s)', case when n = n2 then 'PASS' else 'FAIL' end, n, n2);

  -- ── 9. 확인값: 돌아갈 곳은 정해 둔 셋만 ──
  v_err := null;
  begin insert into public.calendar_oauth_state (nonce, owner_id, return_to) values (repeat('x', 40), A, 'https://evil.example.test'); exception when others then v_err := sqlstate; end;
  res := res || format('%s 돌아갈 곳 셋만 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 10. 연결 끊기 → 열쇠 없음, 다시 연결 → 다시 읽힘 ──
  update public.calendar_link set broken_at = now() where owner_id = A;
  perform public.wb_calendar_forget(A);
  v_tok := public.wb_calendar_token(A);
  select count(*) into n from public.calendar_link where owner_id = A and connected_at is null and broken_at is null;
  f := v_tok is null and n = 1;
  perform public.wb_calendar_save_token(A, 'fake-refresh-token-A-0003');
  v_tok := public.wb_calendar_token(A);
  res := res || format('%s 끊으면 열쇠 없음·다시 연결하면 읽힘 (%s / %s)',
    case when f and v_tok = 'fake-refresh-token-A-0003' then 'PASS' else 'FAIL' end, f, coalesce(v_tok, '없음'));

  -- 결과를 예외로 돌려주며 모든 변경을 되돌린다(합성 계정·Vault 비밀도 남지 않음)
  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
