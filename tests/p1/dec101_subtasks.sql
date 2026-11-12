-- P1.5 실측: FR-23a 하위 할 일(DEC-101, 마이그레이션 0040) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0040 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다(원격 확인 창 만료 회피 — 상위를 지우면 하위도 지워지는 것은 외래 키 규칙으로 확인). 사용자는 합성 계정 A·B(@example.test).
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000040a1';
  B uuid := '00000000-0000-4000-a000-0000000040b1';
  wsA uuid; wsB uuid; pA uuid; pA2 uuid; pArch uuid; aA uuid;
  parent uuid; child1 uuid; child2 uuid; other uuid; tB uuid; archParent uuid; doneParent uuid; kid uuid;
  v_err text; n int; n2 int; s text; f boolean; ver int; ver2 int;
  res text[] := '{}';
begin
  -- ── 0. 시드 ──
  insert into auth.users (id, aud, role, email, created_at, updated_at) values
    (A, 'authenticated', 'authenticated', 'p1-subtask-a@example.test', now(), now()),
    (B, 'authenticated', 'authenticated', 'p1-subtask-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-subtask-a@example.test'), (B, 'p1-subtask-b@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;
  select id into wsB from public.workspace where owner_id = B limit 1;
  if wsB is null then insert into public.workspace (owner_id, name) values (B, '본업') returning id into wsB; end if;

  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.task (title) values ('남의 할 일') returning id into tB;
  reset role;

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.project (workspace_id, name) values (wsA, '합성 하위 프로젝트') returning id into pA;
  insert into public.project (workspace_id, name) values (wsA, '옮겨 갈 합성 프로젝트') returning id into pA2;
  insert into public.project (workspace_id, name) values (wsA, '보관할 합성 프로젝트') returning id into pArch;
  insert into public.area (workspace_id, name) values (wsA, '합성 반복실행') returning id into aA;
  insert into public.task (title, project_id) values ('결제 화면 개편', pA) returning id into parent;
  insert into public.task (title, project_id) values ('보관될 곳의 할 일', pArch) returning id into archParent;
  insert into public.task (title, project_id, status) values ('끝낸 상위', pA, 'done') returning id into doneParent;
  -- 하위는 다른 곳(반복실행)을 보내도 상위의 곳을 물려받는다
  insert into public.task (title, area_id, parent_id) values ('오류 문구 수정', aA, parent) returning id into child1;
  insert into public.task (title, parent_id, due_date, owner_kind, assignee) values ('버튼 색 확인', parent, '2026-11-20', 'other', '박지훈') returning id into child2;
  reset role;
  update public.project set archived_at = now(), version = version + 1 where id = pArch;

  -- ── 1. 하위는 상위의 곳을 물려받는다 ──
  select count(*) into n from public.task where parent_id = parent and project_id = pA and area_id is null;
  res := res || format('%s 하위는 상위의 곳을 물려받음 (%s/2)', case when n = 2 then 'PASS' else 'FAIL' end, n);

  -- ── 2. 한 단계만: 하위 아래 하위 거부 ──
  v_err := null;
  begin set local role authenticated; insert into public.task (title, parent_id) values ('손자', child1); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 하위 아래 하위 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 3. 하위가 있는 할 일은 하위가 될 수 없음·자기 자신 안 됨(서비스 권한으로 직접 바꿔 봄) ──
  v_err := null;
  begin update public.task set parent_id = doneParent where id = parent; exception when others then v_err := sqlstate; end;
  s := coalesce(v_err, '허용됨');
  v_err := null;
  begin update public.task set parent_id = doneParent, version = version + 1 where id = doneParent; exception when others then v_err := sqlstate; end;
  res := res || format('%s 하위 있는 할 일을 하위로·자기 자신 거부 (%s / %s)', case when s = '23514' and v_err = '23514' then 'PASS' else 'FAIL' end, s, coalesce(v_err, '허용됨'));

  -- ── 4. 남의 할 일 아래 거부 ──
  v_err := null;
  begin set local role authenticated; insert into public.task (title, parent_id) values ('남의 아래', tB); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 남의 할 일 아래 거부 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 5. 보관된 곳의 할 일 아래 거부 ──
  v_err := null;
  begin set local role authenticated; insert into public.task (title, parent_id) values ('보관된 곳 아래', archParent); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 보관된 곳의 할 일 아래 거부 (%s)', case when v_err = '55000' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 6. 상위를 옮기면 하위도 같은 곳으로(버전 +1·이력) ──
  select version into ver from public.task where id = child1;
  set local role authenticated;
  update public.task set project_id = pA2, version = version + 1 where id = parent;
  reset role;
  select count(*) into n from public.task where parent_id = parent and project_id = pA2;
  select version into ver2 from public.task where id = child1;
  select count(*) into n2 from public.task_revision where task_id = child1 and version = ver2 and snapshot->>'project_id' = pA2::text;
  res := res || format('%s 상위를 옮기면 하위도·버전 +1·이력 (%s/2, %s→%s, 이력 %s)', case when n = 2 and ver2 = ver + 1 and n2 = 1 then 'PASS' else 'FAIL' end, n, ver, ver2, n2);

  -- ── 7. 하위의 곳만 따로 옮기기 거부 ──
  v_err := null;
  begin set local role authenticated; update public.task set project_id = pA, version = version + 1 where id = child1; exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 하위의 곳만 옮기기 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 8. 끝낸 상위에 안 끝난 하위를 넣으면 상위가 다시 열림(완료 시각 지움), 끝난 하위로 넣으면 그대로 ──
  set local role authenticated;
  insert into public.task (title, parent_id, status) values ('이미 끝난 하위', doneParent, 'done');
  reset role;
  select status into s from public.task where id = doneParent;
  set local role authenticated;
  insert into public.task (title, parent_id) values ('후속 일', doneParent) returning id into kid;
  reset role;
  select status = 'open' and done_at is null into f from public.task where id = doneParent;
  res := res || format('%s 끝난 하위는 그대로(%s)·안 끝난 하위는 상위 다시 열기(%s)', case when s = 'done' and f then 'PASS' else 'FAIL' end, s, f);

  -- ── 9. 하위를 다 끝내도 상위는 그대로, 끝난 하위를 다시 열면 끝난 상위도 다시 열림 ──
  set local role authenticated;
  update public.task set status = 'done', version = version + 1 where id = kid;
  reset role;
  select status into s from public.task where id = doneParent;
  set local role authenticated;
  update public.task set status = 'done', version = version + 1 where id = doneParent;
  update public.task set status = 'open', version = version + 1 where id = kid;
  reset role;
  select status = 'open' into f from public.task where id = doneParent;
  res := res || format('%s 하위 다 끝나도 상위 그대로(%s)·하위 다시 열면 상위도(%s)', case when s = 'open' and f then 'PASS' else 'FAIL' end, s, f);

  -- ── 10. 앱은 상위를 바꿀 수 없음(넣을 때만) ──
  v_err := null;
  begin set local role authenticated; update public.task set parent_id = null, version = version + 1 where id = child1; exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 앱이 상위 바꾸기 거부 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 11. 이력 스냅샷에 상위 ──
  select count(*) into n from public.task_revision where task_id = child2 and snapshot->>'parent_id' = parent::text;
  res := res || format('%s 이력 스냅샷에 parent_id (%s)', case when n >= 1 then 'PASS' else 'FAIL' end, n);

  -- ── 12. 상위를 지우면 하위도 지워짐(외래 키 cascade), 하위 이력은 할 일 이력 규칙대로 함께 지워짐 ──
  select count(*) into n from pg_constraint
   where conname = 'task_parent_id_fkey' and conrelid = 'public.task'::regclass and confrelid = 'public.task'::regclass and confdeltype = 'c';
  select count(*) into n2 from pg_constraint
   where conrelid = 'public.task_revision'::regclass and confrelid = 'public.task'::regclass and confdeltype = 'c';
  res := res || format('%s 상위 지우면 하위도·이력도(외래 키 cascade %s / %s)', case when n = 1 and n2 = 1 then 'PASS' else 'FAIL' end, n, n2);

  -- ── 13. B에게는 A의 하위가 안 보임 ──
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.task where parent_id is not null;
  reset role;
  res := res || format('%s 남의 하위 안 보임 (%s)', case when n = 0 then 'PASS' else 'FAIL' end, n);

  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
