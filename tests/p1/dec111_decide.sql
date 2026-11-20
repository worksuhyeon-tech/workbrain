-- P1.5 실측: 비서와 결정 + 집중 기록(DEC-111, 마이그레이션 0045) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0045 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- 사용자는 합성 계정 A·B(@example.test). 지난 날수는 실제 오늘(서울) 기준이라 기한도 오늘에서 빼서 넣는다.
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000045a1';
  B uuid := '00000000-0000-4000-a000-0000000045b1';
  today date := (now() at time zone 'Asia/Seoul')::date;
  wsA uuid; pA uuid; pArch uuid; t1 uuid; t2 uuid; t3 uuid; tB uuid; k1 uuid; k2 uuid; dec uuid; drop2 uuid; drop3 uuid;
  re2 uuid; re2b uuid; re3 uuid; f1 uuid; f2 uuid; v int; n int; ok boolean; v_err text; r record; s jsonb;
  res text[] := '{}';
begin
  -- ── 0. 시드 ──
  insert into auth.users (id, aud, role, email, created_at, updated_at) values
    (A, 'authenticated', 'authenticated', 'p1-decide-a@example.test', now(), now()),
    (B, 'authenticated', 'authenticated', 'p1-decide-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-decide-a@example.test'), (B, 'p1-decide-b@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;

  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.task (title, due_date) values ('B의 할 일', today - 1) returning id into tB;
  reset role;

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.project (workspace_id, name) values (wsA, '합성 결정 프로젝트') returning id into pA;
  insert into public.project (workspace_id, name) values (wsA, '곧 보관할 프로젝트') returning id into pArch;
  -- t1: 기한을 두 번 늦추고(미룸 2) 한 번 앞당김(세지 않음)
  insert into public.task (title, project_id, due_date) values ('견적 비교표 정리', pA, today - 9) returning id into t1;
  update public.task set due_date = today - 5, version = version + 1 where id = t1;
  update public.task set due_date = today - 2, version = version + 1 where id = t1;
  update public.task set due_date = today - 3, version = version + 1 where id = t1;

  -- ── 1. 미룬 횟수 ──
  select d.defers, d.revisions into r from public.wb_task_defer_counts(array[t1]) d;
  res := res || format('%s 미룬 횟수 = 늦춘 횟수만 (미룸 %s, 이력 %s)', case when r.defers = 2 and r.revisions = 4 then 'PASS' else 'FAIL' end, r.defers, r.revisions);
  select count(*) into n from public.wb_task_defer_counts(array[tB]);
  res := res || format('%s 남의 할 일 이력은 안 보임 (%s줄)', case when n = 0 then 'PASS' else 'FAIL' end, n);

  -- ── 2. 결정 남기기·되돌리기 ──
  dec := public.wb_task_decide(t1, 'later');
  select * into r from public.task_decision where id = dec;
  res := res || format('%s 결정 남김 (종류 %s, 미룸 %s, 지난 날 %s, 사본 없음 %s)',
    case when r.kind = 'later' and r.defer_count = 2 and r.days_late = 3 and r.snapshot is null and r.owner_id = A and r.task_id = t1 then 'PASS' else 'FAIL' end,
    r.kind, r.defer_count, r.days_late, r.snapshot is null);
  begin
    perform public.wb_task_decide(t1, 'drop');
    res := res || 'FAIL 내려놓음은 wb_task_decide로 못 남김 (통과됨)';
  exception when others then
    res := res || format('%s 내려놓음은 wb_task_decide로 못 남김 (%s)', case when sqlerrm like 'WB400%' then 'PASS' else 'FAIL' end, sqlerrm);
  end;
  begin
    perform public.wb_task_decide(tB, 'start');
    res := res || 'FAIL 남의 할 일 결정 거부 (통과됨)';
  exception when others then
    res := res || format('%s 남의 할 일 결정 거부 (%s)', case when sqlerrm like 'WB404%' then 'PASS' else 'FAIL' end, sqlerrm);
  end;
  ok := public.wb_task_decide_undo(dec);
  select count(*) into n from public.task_decision where id = dec;
  res := res || format('%s 결정 되돌리기 (%s, 남은 줄 %s)', case when ok and n = 0 then 'PASS' else 'FAIL' end, ok, n);
  ok := public.wb_task_decide_undo(dec);
  res := res || format('%s 두 번 되돌리면 false (%s)', case when not ok then 'PASS' else 'FAIL' end, ok);

  -- ── 3. 내려놓기: 버전 검사·사본·하위 함께 지움 ──
  insert into public.task (title, project_id, priority, due_date, recipients) values ('문구 검토 의견 회신', pA, 1, today - 7, array['개발 담당']) returning id into t2;
  insert into public.task (title, parent_id) values ('걸리는 문구 표시', t2) returning id into k1;
  insert into public.task (title, parent_id) values ('의견 메일 보내기', t2) returning id into k2;
  update public.task set status = 'done', version = version + 1 where id = k1;
  select version into v from public.task where id = t2;
  drop2 := public.wb_task_drop(t2, v + 5);
  select count(*) into n from public.task where id = t2;
  res := res || format('%s 버전이 다르면 내려놓지 않음 (%s, 남음 %s)', case when drop2 is null and n = 1 then 'PASS' else 'FAIL' end, coalesce(drop2::text, 'NULL'), n);
  drop2 := public.wb_task_drop(t2, v);
  select count(*) into n from public.task where id in (t2, k1, k2);
  select snapshot into s from public.task_decision where id = drop2;
  res := res || format('%s 내려놓으면 할 일·하위 지움 (남은 %s)', case when drop2 is not null and n = 0 then 'PASS' else 'FAIL' end, n);
  res := res || format('%s 사본 (제목 %s, 곳 같음 %s, 우선 %s, 받는 사람 %s, 하위 %s개, 끝낸 하위 %s개)',
    case when s ->> 'title' = '문구 검토 의견 회신' and (s ->> 'project_id')::uuid = pA and (s ->> 'priority')::int = 1
              and s -> 'recipients' = '["개발 담당"]'::jsonb and jsonb_array_length(s -> 'subtasks') = 2
              and (select count(*) from jsonb_array_elements(s -> 'subtasks') e where (e ->> 'done')::boolean) = 1 then 'PASS' else 'FAIL' end,
    s ->> 'title', (s ->> 'project_id')::uuid = pA, s ->> 'priority', s -> 'recipients', jsonb_array_length(s -> 'subtasks'),
    (select count(*) from jsonb_array_elements(s -> 'subtasks') e where (e ->> 'done')::boolean));
  select * into r from public.task_decision where id = drop2;
  res := res || format('%s 내려놓음 결정 (종류 %s, 지난 날 %s)', case when r.kind = 'drop' and r.days_late = 7 then 'PASS' else 'FAIL' end, r.kind, r.days_late);
  ok := public.wb_task_decide_undo(drop2);
  res := res || format('%s 내려놓음은 되돌리기 함수로 못 지움 (%s)', case when not ok then 'PASS' else 'FAIL' end, ok);

  -- ── 4. 되살리기 ──
  re2 := public.wb_task_restore(drop2);
  select * into r from public.task where id = re2;
  res := res || format('%s 되살린 할 일 (제목 %s, 곳 같음 %s, 우선 %s, 기한 %s, 상태 %s)',
    case when r.title = '문구 검토 의견 회신' and r.project_id = pA and r.priority = 1 and r.due_date = today - 7 and r.status = 'open' and r.owner_id = A then 'PASS' else 'FAIL' end,
    r.title, r.project_id = pA, r.priority, r.due_date, r.status);
  select count(*) as total, count(*) filter (where status = 'done') as done_n into r from public.task where parent_id = re2;
  res := res || format('%s 하위도 되살림 (%s개, 끝낸 것 %s)', case when r.total = 2 and r.done_n = 1 then 'PASS' else 'FAIL' end, r.total, r.done_n);
  re2b := public.wb_task_restore(drop2);
  select count(*) into n from public.task where title = '문구 검토 의견 회신' and owner_id = A;
  res := res || format('%s 두 번 되살려도 하나 (%s개, 같은 ID %s)', case when n = 1 and re2b = re2 then 'PASS' else 'FAIL' end, n, re2b = re2);

  -- 보관된 곳이면 '정리 안 됨'으로 되살림
  insert into public.task (title, project_id, due_date) values ('보관될 곳의 할 일', pArch, today - 1) returning id into t3;
  select version into v from public.task where id = t3;
  drop3 := public.wb_task_drop(t3, v);
  reset role;
  update public.project set archived_at = now(), version = version + 1 where id = pArch;
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  re3 := public.wb_task_restore(drop3);
  select * into r from public.task where id = re3;
  res := res || format('%s 보관된 곳이면 정리 안 됨으로 (프로젝트 %s)', case when r.id is not null and r.project_id is null and r.area_id is null then 'PASS' else 'FAIL' end, coalesce(r.project_id::text, 'NULL'));

  -- ── 5. 집중 남기기 ──
  f1 := public.wb_focus_log(t1, '  받은 견적 메일 모으기  ', 2, 252, now() - interval '5 minutes', 'here', true);
  select * into r from public.focus_session where id = f1;
  res := res || format('%s 집중 남김 (동작 %s, 정한 %s분, 실제 %s초, %s, 첫 동작 %s)',
    case when r.task_id = t1 and r.step = '받은 견적 메일 모으기' and r.planned_min = 2 and r.actual_sec = 252 and r.ended_how = 'here' and r.step_done then 'PASS' else 'FAIL' end,
    r.step, r.planned_min, r.actual_sec, r.ended_how, r.step_done);
  f2 := public.wb_focus_log(tB, null, 5, 99999, now() - interval '3 minutes', 'done', null);
  select * into r from public.focus_session where id = f2;
  res := res || format('%s 남의 할 일은 빼고, 실제 초는 시작~지금 안으로 (할 일 %s, %s초)',
    case when r.task_id is null and r.actual_sec <= 240 then 'PASS' else 'FAIL' end, coalesce(r.task_id::text, 'NULL'), r.actual_sec);
  begin
    perform public.wb_focus_log(t1, null, 2, 60, now() - interval '2 days', 'here', null);
    res := res || 'FAIL 이틀 전 시작은 거부 (통과됨)';
  exception when others then
    res := res || format('%s 이틀 전 시작은 거부 (%s)', case when sqlerrm like 'WB400%' then 'PASS' else 'FAIL' end, sqlerrm);
  end;
  begin
    perform public.wb_focus_log(t1, null, 2, 60, now(), 'stop', null);
    res := res || 'FAIL 끝난 방식 모양 거부 (통과됨)';
  exception when others then
    res := res || format('%s 끝난 방식 모양 거부 (%s)', case when sqlerrm like 'WB400%' then 'PASS' else 'FAIL' end, sqlerrm);
  end;

  -- ── 6. 권한: 직접 쓰기 막힘, 남의 행 안 보임 ──
  begin
    insert into public.task_decision (owner_id, task_id, kind) values (A, t1, 'start');
    res := res || 'FAIL 결정 표 직접 쓰기 막힘 (통과됨)';
  exception when others then
    res := res || format('%s 결정 표 직접 쓰기 막힘 (%s)', case when sqlstate = '42501' then 'PASS' else 'FAIL' end, sqlstate);
  end;
  begin
    insert into public.focus_session (owner_id, planned_min, actual_sec, started_at, ended_at, ended_how) values (A, 2, 1, now(), now(), 'here');
    res := res || 'FAIL 집중 표 직접 쓰기 막힘 (통과됨)';
  exception when others then
    res := res || format('%s 집중 표 직접 쓰기 막힘 (%s)', case when sqlstate = '42501' then 'PASS' else 'FAIL' end, sqlstate);
  end;
  reset role;
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.task_decision;
  res := res || format('%s B에게 A의 결정 안 보임 (%s줄)', case when n = 0 then 'PASS' else 'FAIL' end, n);
  select count(*) into n from public.focus_session where owner_id = A;
  res := res || format('%s B에게 A의 집중 안 보임 (%s줄)', case when n = 0 then 'PASS' else 'FAIL' end, n);
  drop2 := public.wb_task_drop(t1, 3);
  reset role;
  select count(*) into n from public.task where id = t1;
  res := res || format('%s B는 A의 할 일을 못 내려놓음 (%s, 남음 %s)', case when drop2 is null and n = 1 then 'PASS' else 'FAIL' end, coalesce(drop2::text, 'NULL'), n);
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  begin
    perform public.wb_task_restore(drop3);
    res := res || 'FAIL B는 A의 내려놓음을 못 되살림 (통과됨)';
  exception when others then
    res := res || format('%s B는 A의 내려놓음을 못 되살림 (%s)', case when sqlerrm like 'WB404%' then 'PASS' else 'FAIL' end, sqlerrm);
  end;
  reset role;

  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
