-- P1.5 실측: 할 일 반복(DEC-109, 마이그레이션 0044) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0044 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다. 사용자는 합성 계정 A·B(@example.test). 트리거는 실제 오늘(서울)을 쓰므로 기한도 오늘로 넣는다.
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000044a1';
  B uuid := '00000000-0000-4000-a000-0000000044b1';
  today date := (now() at time zone 'Asia/Seoul')::date;
  wsA uuid; pA uuid; t uuid; nxt uuid; nxt2 uuid; sub1 uuid; tB uuid; kid uuid;
  v_err text; n int; d date; s text; tm text; r record;
  res text[] := '{}';
begin
  -- ── 1. 다음 날짜 계산(함수만, 날짜 고정) ──
  res := res || format('%s 매일 (%s)', case when public.wb_repeat_next('FREQ=DAILY', '2026-11-16', '2026-11-16') = '2026-11-17' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=DAILY', '2026-11-16', '2026-11-16'));
  res := res || format('%s 평일 금→월 (%s)', case when public.wb_repeat_next('FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR', '2026-11-20', '2026-11-16') = '2026-11-23' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR', '2026-11-20', '2026-11-16'));
  res := res || format('%s 매주(기한 요일) (%s)', case when public.wb_repeat_next('FREQ=WEEKLY', '2026-11-16', '2026-11-16') = '2026-11-23' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=WEEKLY', '2026-11-16', '2026-11-16'));
  res := res || format('%s 2주 월·수: 월→같은 주 수 (%s)', case when public.wb_repeat_next('FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE', '2026-11-16', '2026-11-16') = '2026-11-18' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE', '2026-11-16', '2026-11-16'));
  res := res || format('%s 2주 월·수: 수→2주 뒤 월 (%s)', case when public.wb_repeat_next('FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE', '2026-11-18', '2026-11-16') = '2026-11-30' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE', '2026-11-18', '2026-11-16'));
  res := res || format('%s 매월 31일→2월 말 (%s)', case when public.wb_repeat_next('FREQ=MONTHLY', '2026-01-31', '2026-01-01') = '2026-02-28' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=MONTHLY', '2026-01-31', '2026-01-01'));
  res := res || format('%s 매년 2/29→평년 2/28 (%s)', case when public.wb_repeat_next('FREQ=YEARLY', '2028-02-29', '2028-01-01') = '2029-02-28' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=YEARLY', '2028-02-29', '2028-01-01'));
  res := res || format('%s 늦게 끝내면 건너뜀: 매일 (%s)', case when public.wb_repeat_next('FREQ=DAILY', '2026-11-10', '2026-11-16') = '2026-11-16' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=DAILY', '2026-11-10', '2026-11-16'));
  res := res || format('%s 늦게 끝내면 건너뜀: 매주 월 (%s)', case when public.wb_repeat_next('FREQ=WEEKLY', '2026-11-02', '2026-11-18') = '2026-11-23' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=WEEKLY', '2026-11-02', '2026-11-18'));
  res := res || format('%s 늦게 끝내면 건너뜀: 매월 15일 (%s)', case when public.wb_repeat_next('FREQ=MONTHLY', '2026-09-15', '2026-11-16') = '2026-12-15' then 'PASS' else 'FAIL' end, public.wb_repeat_next('FREQ=MONTHLY', '2026-09-15', '2026-11-16'));

  -- ── 0. 시드 ──
  insert into auth.users (id, aud, role, email, created_at, updated_at) values
    (A, 'authenticated', 'authenticated', 'p1-repeat-a@example.test', now(), now()),
    (B, 'authenticated', 'authenticated', 'p1-repeat-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-repeat-a@example.test'), (B, 'p1-repeat-b@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.project (workspace_id, name) values (wsA, '합성 반복 프로젝트') returning id into pA;
  -- 오늘 15:00(서울) 마감, 매주, 10분 전 알림, 높음, 하위 둘
  insert into public.task (title, project_id, priority, due_date, due_time, remind_before_min, repeat_rule)
    values ('주간 보고', pA, 1, today, (today + time '15:00') at time zone 'Asia/Seoul', 10, 'FREQ=WEEKLY') returning id into t;
  insert into public.task (title, parent_id, due_date) values ('자료 모으기', t, today) returning id into sub1;
  insert into public.task (title, parent_id) values ('초안 쓰기', t);
  update public.task set status = 'done', version = version + 1 where id = sub1;

  -- ── 2. 완료하면 다음 회차 ──
  update public.task set status = 'done', version = version + 1 where id = t;
  select repeat_next_id, repeat_rule into nxt, s from public.task where id = t;
  reset role;
  select * into r from public.task where id = nxt;
  tm := to_char(r.due_time at time zone 'Asia/Seoul', 'HH24:MI');
  res := res || format('%s 다음 회차 생김 (기한 %s %s, 규칙 %s, 알림 %s, 우선 %s, 곳 같음 %s, 상태 %s, 버전 %s)',
    case when r.id is not null and r.due_date = today + 7 and tm = '15:00' and r.repeat_rule = 'FREQ=WEEKLY' and r.remind_before_min = 10
              and r.priority = 1 and r.project_id = pA and r.status = 'open' and r.version = 0 and r.owner_id = A then 'PASS' else 'FAIL' end,
    r.due_date, tm, r.repeat_rule, r.remind_before_min, r.priority, r.project_id = pA, r.status, r.version);
  res := res || format('%s 끝낸 쪽은 규칙 비움 (%s)', case when s is null then 'PASS' else 'FAIL' end, coalesce(s, 'NULL'));
  select count(*) into n from public.task where parent_id = nxt and status = 'open' and due_date is null;
  res := res || format('%s 하위 둘 안 끝난 채·기한 없이 (%s/2)', case when n = 2 then 'PASS' else 'FAIL' end, n);

  -- ── 3. 완료를 되돌리면 손대지 않은 다음 회차를 지우고 규칙을 되돌림 ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  update public.task set status = 'open', version = version + 1 where id = t;
  reset role;
  select count(*) into n from public.task where id = nxt;
  select repeat_rule, repeat_next_id::text into s, tm from public.task where id = t;
  res := res || format('%s 되돌리면 다음 회차 지움·규칙 되돌림 (남은 %s, 규칙 %s, 다음 %s)', case when n = 0 and s = 'FREQ=WEEKLY' and tm is null then 'PASS' else 'FAIL' end, n, s, coalesce(tm, 'NULL'));
  select count(*) into n from public.task where parent_id = nxt;
  res := res || format('%s 다음 회차의 하위도 함께 지움 (%s)', case when n = 0 then 'PASS' else 'FAIL' end, n);

  -- ── 4. 다시 끝낸 뒤 다음 회차를 고치면 되돌려도 남김 ──
  set local role authenticated;
  update public.task set status = 'done', version = version + 1 where id = t;
  select repeat_next_id into nxt2 from public.task where id = t;
  update public.task set title = '주간 보고(고침)', version = version + 1 where id = nxt2;
  update public.task set status = 'open', version = version + 1 where id = t;
  reset role;
  select count(*) into n from public.task where id = nxt2;
  select repeat_rule into s from public.task where id = t;
  res := res || format('%s 고친 다음 회차는 남김·규칙은 그쪽에 (남은 %s, 끝낸 쪽 규칙 %s)', case when n = 1 and s is null then 'PASS' else 'FAIL' end, n, coalesce(s, 'NULL'));

  -- ── 5. 하위 할 일은 규칙이 있어도 회차를 만들지 않음 ──
  set local role authenticated;
  insert into public.task (title, parent_id, due_date, repeat_rule) values ('하위 반복', nxt2, today, 'FREQ=DAILY') returning id into kid;
  update public.task set status = 'done', version = version + 1 where id = kid;
  reset role;
  select count(*) into n from public.task where title = '하위 반복';
  res := res || format('%s 하위는 회차 안 만듦 (%s)', case when n = 1 then 'PASS' else 'FAIL' end, n);

  -- ── 6. 규칙 모양 거부 ──
  v_err := null;
  begin set local role authenticated; insert into public.task (title, due_date, repeat_rule) values ('잘못', today, 'FREQ=HOURLY'); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 없는 주기 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null;
  begin set local role authenticated; insert into public.task (title, due_date, repeat_rule) values ('잘못', today, 'FREQ=DAILY;BYDAY=MO'); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 매일+요일 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null;
  begin set local role authenticated; update public.task set repeat_next_id = gen_random_uuid(), version = version + 1 where id = t; exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 다음 회차 칸은 앱이 못 씀 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 7. 남에게는 안 보임 ──
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.task where title like '주간 보고%';
  reset role;
  res := res || format('%s 남의 회차 안 보임 (%s)', case when n = 0 then 'PASS' else 'FAIL' end, n);

  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
