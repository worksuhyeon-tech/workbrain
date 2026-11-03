-- P1 실측: 할 일 완료 시각(마이그레이션 0029, DEC-64) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 합성 계정 A만 쓰고 끝에 지운다. 결과는 마지막 SELECT.
create temp table if not exists p1_result (seq serial, name text, pass boolean, detail text);
truncate p1_result;

do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000029a1';
  t1 uuid; t2 uuid; v_done timestamptz; v_after timestamptz; n int; v_err text;
begin
  delete from auth.users where id = A;
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  values (A, 'authenticated', 'authenticated', 'p1-done-a@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-done-a@example.test') on conflict (id) do nothing;

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.task (title) values ('합성 할 일 1') returning id into t1;
  insert into public.task (title, status) values ('처음부터 완료', 'done') returning id into t2;
  reset role;

  select count(*) into n from public.task where id = t1 and done_at is null;
  insert into p1_result values (default, 'DEC-64 새 할 일은 완료 시각 없음', n = 1, n || '/1');
  select count(*) into n from public.task where id = t2 and done_at is not null;
  insert into p1_result values (default, 'DEC-64 완료로 만든 할 일은 완료 시각 채움', n = 1, n || '/1');

  -- 앱과 같은 방식: 버전을 1 올리며 완료
  set local role authenticated;
  update public.task set status = 'done', version = version + 1 where id = t1;
  reset role;
  select done_at into v_done from public.task where id = t1;
  insert into p1_result values (default, 'DEC-64 완료 → 완료 시각 채움', v_done is not null, coalesce(v_done::text, 'null'));

  set local role authenticated;
  update public.task set title = '합성 할 일 1 (고침)', version = version + 1 where id = t1;
  reset role;
  select done_at into v_after from public.task where id = t1;
  insert into p1_result values (default, 'DEC-64 완료 상태에서 제목만 고치면 완료 시각 그대로', v_after = v_done, coalesce(v_after::text, 'null'));

  set local role authenticated;
  v_err := null; begin update public.task set done_at = now() - interval '3 days', version = version + 1 where id = t1; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'DEC-64 앱이 완료 시각 직접 쓰기 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  set local role authenticated;
  update public.task set status = 'open', version = version + 1 where id = t1;
  reset role;
  select count(*) into n from public.task where id = t1 and done_at is null and status = 'open';
  insert into p1_result values (default, 'DEC-64 완료 취소 → 완료 시각 비움', n = 1, n || '/1');

  select count(*) into n from public.task_revision where task_id = t1;
  insert into p1_result values (default, 'M18 이력은 그대로 쌓임(생성·완료·고침·취소 4건)', n = 4, n || '건');

  delete from auth.users where id = A;
end
$$;
select seq, case when pass then 'PASS' else 'FAIL' end as result, name, detail from p1_result order by seq;
