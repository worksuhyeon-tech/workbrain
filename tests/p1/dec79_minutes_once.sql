-- P1 실측: DEC-79 회의록 만들기 한 번만 (마이그레이션 0036) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0036 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다. 사용자는 합성 시험 계정 A뿐이다(@example.test).
-- 한 트랜잭션 안에서는 now()가 그대로라, 잠금 만료는 잠금 시각을 과거로 바꿔 흉내 낸다.
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000036a1';
  wsA uuid; rA uuid; job uuid;
  k1 text := '11111111-1111-4111-8111-111111111111';
  k2 text := '22222222-2222-4222-8222-222222222222';
  k3 text := '33333333-3333-4333-8333-333333333333';
  c text; s text; v_err text; n int;
  res text[] := '{}';
begin
  -- ── 0. 시드(서비스 권한) ──
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  values (A, 'authenticated', 'authenticated', 'p1-minutes-once-a@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-minutes-once-a@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification)
  values (A, wsA, 'text-import', '합성 회의', 'sample', 'work') returning id into rA;
  insert into public.processing_job (owner_id, source_record_id, stage, stage_state, execution_key)
  values (A, rA, 'REVIEWABLE', 'DONE', rA::text || ':pipeline') returning id into job;

  -- ── 1. 첫 요청은 잠금을 잡는다 ──
  set local role service_role;
  c := public.wb_claim_document(job, k1);
  reset role;
  select count(*) into n from public.processing_job
   where id = job and document_key = k1 and document_lease_until > now() and document_result is null;
  res := res || format('%s 첫 요청은 잠금을 잡음 (%s, %s)', case when c = 'CLAIMED' and n = 1 then 'PASS' else 'FAIL' end, c, n);

  -- ── 2·3. 만드는 중: 같은 열쇠(앱 작업이 다시 돎)는 RUNNING, 다른 열쇠는 BUSY. 둘 다 다시 만들지 않음 ──
  set local role service_role;
  c := public.wb_claim_document(job, k1);
  s := public.wb_claim_document(job, k2);
  reset role;
  select count(*) into n from public.processing_job where id = job and document_key = k1;
  res := res || format('%s 같은 열쇠는 RUNNING (%s)', case when c = 'RUNNING' then 'PASS' else 'FAIL' end, c);
  res := res || format('%s 다른 열쇠는 BUSY, 잠금 주인 그대로 (%s, %s)', case when s = 'BUSY' and n = 1 then 'PASS' else 'FAIL' end, s, n);

  -- ── 4. 앱 확인(본인): 내 열쇠는 RUNNING, 다른 열쇠는 BUSY ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  c := public.wb_document_status(rA, k1);
  s := public.wb_document_status(rA, k2);
  reset role;
  res := res || format('%s 앱 확인: 만드는 중 (%s / %s)', case when c = 'RUNNING' and s = 'BUSY' then 'PASS' else 'FAIL' end, c, s);

  -- ── 5. 끝 표시(처리 함수가 서버 키로 하는 것과 같음) 뒤 같은 열쇠는 DONE, 비용 없이 ──
  set local role service_role;
  update public.processing_job set document_lease_until = null, document_result = 'DONE' where id = job and document_key = k1;
  c := public.wb_claim_document(job, k1);
  reset role;
  set local role authenticated;
  s := public.wb_document_status(rA, k1);
  reset role;
  res := res || format('%s 다 된 뒤 같은 열쇠는 DONE (%s / 앱 %s)', case when c = 'DONE' and s = 'DONE' then 'PASS' else 'FAIL' end, c, s);

  -- ── 6. 다 된 뒤 '다시 만들기'(새 열쇠)는 잡힌다. 옛 열쇠로 확인하면 BUSY ──
  set local role service_role;
  c := public.wb_claim_document(job, k2);
  reset role;
  set local role authenticated;
  s := public.wb_document_status(rA, k1);
  reset role;
  res := res || format('%s 다시 만들기는 새로 잡힘 (%s / 옛 열쇠 %s)', case when c = 'CLAIMED' and s = 'BUSY' then 'PASS' else 'FAIL' end, c, s);

  -- ── 7. 결과 없이 잠금이 풀리면(함수가 중간에 멈춤) 앱 확인은 FAILED, 새 요청은 잡힌다 ──
  update public.processing_job set document_lease_until = now() - interval '1 second' where id = job;
  set local role authenticated;
  s := public.wb_document_status(rA, k2);
  reset role;
  set local role service_role;
  c := public.wb_claim_document(job, k3);
  reset role;
  res := res || format('%s 멈춘 요청은 FAILED, 새 요청은 잡힘 (앱 %s / %s)', case when s = 'FAILED' and c = 'CLAIMED' then 'PASS' else 'FAIL' end, s, c);

  -- ── 8. 실패로 끝난 요청을 같은 열쇠로 다시 부르면 다시 잡힌다(다시 시도) ──
  set local role service_role;
  update public.processing_job set document_lease_until = null, document_result = 'FAILED' where id = job and document_key = k3;
  c := public.wb_claim_document(job, k3);
  reset role;
  res := res || format('%s 실패한 요청은 같은 열쇠로 다시 잡힘 (%s)', case when c = 'CLAIMED' then 'PASS' else 'FAIL' end, c);

  -- ── 9. 서버에 안 닿은 열쇠는 NONE(잠금 없을 때) ──
  update public.processing_job set document_lease_until = null, document_result = 'DONE' where id = job;
  set local role authenticated;
  s := public.wb_document_status(rA, '44444444-4444-4444-8444-444444444444');
  reset role;
  res := res || format('%s 안 닿은 요청은 NONE (%s)', case when s = 'NONE' then 'PASS' else 'FAIL' end, s);

  -- ── 10. 남의 기록은 확인 결과 없음 ──
  perform set_config('request.jwt.claims', json_build_object('sub', '00000000-0000-4000-a000-0000000036b1', 'role', 'authenticated')::text, true);
  set local role authenticated;
  s := public.wb_document_status(rA, k3);
  reset role;
  res := res || format('%s 남의 기록은 결과 없음 (%s)', case when s is null then 'PASS' else 'FAIL' end, coalesce(s, 'null'));

  -- ── 11. 앱(로그인 사용자)은 잠금을 직접 잡을 수 없고, 익명은 확인도 못 한다 ──
  set local role authenticated;
  v_err := null; begin c := public.wb_claim_document(job, k1); exception when others then v_err := sqlstate; end;
  reset role;
  res := res || format('%s 앱은 잠금 함수 못 부름 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  set local role anon;
  v_err := null; begin s := public.wb_document_status(rA, k1); exception when others then v_err := sqlstate; end;
  reset role;
  res := res || format('%s 익명은 확인 함수 못 부름 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 12. 값 제한: 결과는 DONE·FAILED만, 빈 열쇠는 거부 ──
  v_err := null; begin update public.processing_job set document_result = 'X' where id = job; exception when others then v_err := sqlstate; end;
  s := v_err;
  set local role service_role;
  v_err := null; begin c := public.wb_claim_document(job, ''); exception when others then v_err := sqlstate; end;
  reset role;
  res := res || format('%s 값 제한: 결과 %s, 빈 열쇠 %s', case when s = '23514' and v_err = '22023' then 'PASS' else 'FAIL' end,
    coalesce(s, '허용됨'), coalesce(v_err, '허용됨'));

  -- 결과를 예외로 돌려주며 모든 변경을 되돌린다(합성 계정도 남지 않음)
  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
