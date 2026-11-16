-- P1.5 실측: 할 일 미리 알림(DEC-108, 마이그레이션 0043) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0043 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다. 사용자는 합성 계정 A·B(@example.test).
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000043a1';
  B uuid := '00000000-0000-4000-a000-0000000043b1';
  tA uuid; tB uuid;
  v_err text; n int; v int; snap jsonb;
  res text[] := '{}';
begin
  -- ── 0. 시드 ──
  insert into auth.users (id, aud, role, email, created_at, updated_at) values
    (A, 'authenticated', 'authenticated', 'p1-remind-a@example.test', now(), now()),
    (B, 'authenticated', 'authenticated', 'p1-remind-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-remind-a@example.test'), (B, 'p1-remind-b@example.test') on conflict (id) do nothing;

  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.task (title, due_date, remind_before_min) values ('남의 할 일', '2026-11-20', 30) returning id into tB;
  reset role;

  -- ── 1. 앱 권한으로 알림을 넣고 고칠 수 있다(정각 0 · 1일 전 1440 · 비우기). 버전은 0에서 시작 ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.task (title, due_date, due_time, remind_before_min) values ('업체 미팅', '2026-11-16', '2026-11-16T06:00:00Z', 60) returning id into tA;
  update public.task set remind_before_min = 0, version = version + 1 where id = tA;
  update public.task set remind_before_min = 1440, version = version + 1 where id = tA;
  select remind_before_min, version into n, v from public.task where id = tA;
  reset role;
  res := res || format('%s 앱 권한으로 넣고 고침 (값 %s, 버전 %s)', case when n = 1440 and v = 2 then 'PASS' else 'FAIL' end, n, v);

  set local role authenticated;
  update public.task set remind_before_min = null, version = version + 1 where id = tA;
  select count(*) into n from public.task where id = tA and remind_before_min is null;
  reset role;
  res := res || format('%s 알림 비우기 (%s)', case when n = 1 then 'PASS' else 'FAIL' end, n);

  -- ── 2. 범위 밖 값 거부(음수·30일 넘음) ──
  v_err := null;
  begin set local role authenticated; update public.task set remind_before_min = -5, version = version + 1 where id = tA; exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 음수 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null;
  begin set local role authenticated; update public.task set remind_before_min = 43201, version = version + 1 where id = tA; exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 30일 넘음 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null;
  begin set local role authenticated; update public.task set remind_before_min = 43200, version = version + 1 where id = tA; exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 30일 정확히는 허용 (%s)', case when v_err is null then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 3. 버전을 안 올리면 거부(기존 규칙 그대로) ──
  v_err := null;
  begin set local role authenticated; update public.task set remind_before_min = 10 where id = tA; exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 버전 안 올리면 거부 (%s)', case when v_err is not null then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 4. 이력 스냅샷에 알림 값이 남음 ──
  select snapshot into snap from public.task_revision where task_id = tA order by version desc limit 1;
  res := res || format('%s 이력에 알림 값 (%s)', case when snap ? 'remind_before_min' and (snap->>'remind_before_min')::int = 43200 then 'PASS' else 'FAIL' end, snap->>'remind_before_min');

  -- ── 5. 남의 할 일 알림은 못 바꿈·안 보임 ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  update public.task set remind_before_min = 5, version = version + 1 where id = tB;
  select count(*) into n from public.task where id = tB;
  reset role;
  select remind_before_min into v from public.task where id = tB;
  res := res || format('%s 남의 할 일 못 바꿈·안 보임 (보임 %s, 값 %s)', case when n = 0 and v = 30 then 'PASS' else 'FAIL' end, n, v);

  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
