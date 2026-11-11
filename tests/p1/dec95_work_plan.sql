-- P1 실측: DEC-95 업무계획 저장(마이그레이션 0038) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0038 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다(원격 확인 창 만료 회피). 사용자는 합성 시험 계정 A·B뿐이다(@example.test).
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000038a1';
  B uuid := '00000000-0000-4000-a000-0000000038b1';
  plan1 jsonb := '{"kind":"weekly","title":"주간 업무계획 (11.9 ~ 11.20)","ownerName":"김하늘","previous":{"label":"전주 (11.9 ~ 11.13)","start":"2026-11-09","end":"2026-11-15","lines":[]},"next":{"label":"금주 (11.16 ~ 11.20)","start":"2026-11-16","end":"2026-11-22","lines":[]}}';
  plan2 jsonb := '{"kind":"weekly","title":"다시 만든 주간 업무계획","ownerName":"김하늘","previous":{"label":"전주","start":"2026-11-09","end":"2026-11-15","lines":[]},"next":{"label":"금주","start":"2026-11-16","end":"2026-11-22","lines":[]}}';
  v_err text; n int; t text; ai_now text;
  res text[] := '{}';
begin
  -- ── 0. 시드(서비스 권한) ──
  insert into auth.users (id, aud, role, email, created_at, updated_at) values
    (A, 'authenticated', 'authenticated', 'p1-plan-a@example.test', now(), now()),
    (B, 'authenticated', 'authenticated', 'p1-plan-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-plan-a@example.test'), (B, 'p1-plan-b@example.test') on conflict (id) do nothing;

  -- ── 1. 처리 함수(서비스 권한)는 저장할 수 있다 ──
  insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (A, 'weekly', '2026-11-09', plan1, 'done');
  insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (A, 'monthly', '2026-11-01', plan1, 'none');
  insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (B, 'weekly', '2026-11-09', plan1, 'done');
  select count(*) into n from public.work_plan where owner_id = A;
  res := res || format('%s 서비스 권한 저장·주간과 월간은 따로 (%s행)', case when n = 2 then 'PASS' else 'FAIL' end, n);

  -- ── 2. 같은 사람·종류·기간은 한 줄 ──
  v_err := null;
  begin insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (A, 'weekly', '2026-11-09', plan2, 'done');
  exception when others then v_err := sqlstate; end;
  res := res || format('%s 같은 기간 두 줄 거부 (%s)', case when v_err = '23505' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 3. 다시 만들기 = 처리 함수의 upsert(on conflict owner_id, kind, period_start)로 고쳐 쓴다 ──
  insert into public.work_plan (owner_id, kind, period_start, plan, ai, made_at) values (A, 'weekly', '2026-11-09', plan2, 'failed', now())
  on conflict (owner_id, kind, period_start) do update set plan = excluded.plan, ai = excluded.ai, made_at = excluded.made_at;
  select count(*), max(plan->>'title'), max(ai) into n, t, ai_now from public.work_plan where owner_id = A and kind = 'weekly';
  res := res || format('%s 다시 만들기는 같은 줄을 고쳐 씀 (%s행, %s, %s)',
    case when n = 1 and t = '다시 만든 주간 업무계획' and ai_now = 'failed' then 'PASS' else 'FAIL' end, n, t, ai_now);

  -- ── 4. 기간 시작일 검사: 주간은 월요일, 월간은 1일 ──
  v_err := null;
  begin insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (A, 'weekly', '2026-11-10', plan1, 'done');
  exception when others then v_err := sqlstate; end;
  res := res || format('%s 주간인데 화요일 시작 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null;
  begin insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (A, 'monthly', '2026-12-02', plan1, 'done');
  exception when others then v_err := sqlstate; end;
  res := res || format('%s 월간인데 2일 시작 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 5. 종류·AI 값·글 모양 검사 ──
  v_err := null;
  begin insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (A, 'daily', '2026-11-16', plan1, 'done');
  exception when others then v_err := sqlstate; end;
  res := res || format('%s 모르는 종류 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null;
  begin insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (A, 'weekly', '2026-11-16', plan1, 'maybe');
  exception when others then v_err := sqlstate; end;
  res := res || format('%s 모르는 AI 값 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null;
  begin insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (A, 'weekly', '2026-11-16', '[1,2]', 'done');
  exception when others then v_err := sqlstate; end;
  res := res || format('%s 글이 객체가 아니면 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 6. 로그인 사용자는 내 것만 읽는다(RLS) ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.work_plan;
  reset role;
  res := res || format('%s 내 업무계획만 보임 (%s행)', case when n = 2 then 'PASS' else 'FAIL' end, n);

  -- ── 7. 로그인 사용자는 직접 쓰거나 고칠 수 없다(처리 함수로만) ──
  v_err := null;
  begin
    set local role authenticated;
    insert into public.work_plan (owner_id, kind, period_start, plan, ai) values (A, 'weekly', '2026-11-23', plan1, 'done');
  exception when others then v_err := sqlstate;
  end;
  reset role;
  res := res || format('%s 앱이 직접 저장 거부 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null;
  begin
    set local role authenticated;
    update public.work_plan set ai = 'none' where owner_id = A;
  exception when others then v_err := sqlstate;
  end;
  reset role;
  res := res || format('%s 앱이 직접 고치기 거부 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 8. 로그인 안 한 사용자는 못 읽는다 ──
  v_err := null;
  begin
    set local role anon;
    select count(*) into n from public.work_plan;
  exception when others then v_err := sqlstate;
  end;
  reset role;
  res := res || format('%s 로그인 안 하면 읽기 거부 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 9. RLS 켜짐·계정을 지우면 같이 지워짐(외래 키 cascade)·지우기 권한 없음 ──
  res := res || format('%s RLS 켜짐·app_user cascade·앱 지우기 권한 없음',
    case when (select relrowsecurity from pg_class where oid = 'public.work_plan'::regclass)
              and exists (select 1 from pg_constraint where conrelid = 'public.work_plan'::regclass and contype = 'f' and confdeltype = 'c')
              and not has_table_privilege('authenticated', 'public.work_plan', 'delete')
              and has_table_privilege('service_role', 'public.work_plan', 'insert') then 'PASS' else 'FAIL' end);

  -- 결과를 예외로 돌려주며 모든 변경을 되돌린다(합성 계정도 남지 않음)
  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
