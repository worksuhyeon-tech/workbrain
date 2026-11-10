-- P1 실측: FR-09 프로젝트 현재 상태(DEC-86, 마이그레이션 0037) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0037 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다(원격 확인 창 만료 회피). 사용자는 합성 시험 계정 A·B뿐이다(@example.test).
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000037a1';
  B uuid := '00000000-0000-4000-a000-0000000037b1';
  wsA uuid; wsB uuid; pA uuid; pB uuid; pArch uuid; rA uuid; tv uuid; run uuid;
  prop uuid; prop2 uuid; propStale uuid;
  r jsonb; v_err text; n int; cur int; row public.project_state_version%rowtype;
  res text[] := '{}';
begin
  -- ── 0. 시드(서비스 권한) ──
  insert into auth.users (id, aud, role, email, created_at, updated_at) values
    (A, 'authenticated', 'authenticated', 'p1-state-a@example.test', now(), now()),
    (B, 'authenticated', 'authenticated', 'p1-state-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-state-a@example.test'), (B, 'p1-state-b@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;
  select id into wsB from public.workspace where owner_id = B limit 1;
  if wsB is null then insert into public.workspace (owner_id, name) values (B, '본업') returning id into wsB; end if;
  insert into public.project (owner_id, workspace_id, name) values (A, wsA, 'CRM) 이관 12건') returning id into pA;
  insert into public.project (owner_id, workspace_id, name) values (A, wsA, '끝난 합성 프로젝트') returning id into pArch;
  update public.project set archived_at = now(), version = version + 1 where id = pArch;
  insert into public.project (owner_id, workspace_id, name) values (B, wsB, '남의 프로젝트') returning id into pB;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification, occurred_at)
  values (A, wsA, 'recording', '합성 이관 점검 회의', 'sample', 'work', '2026-11-05 10:00+09') returning id into rA;
  insert into public.transcript_version (source_record_id, owner_id, kind, content, segments, retention_expires_at)
  values (rA, A, 'stt-original', '12건 중 8건은 이관을 끝냈어요. 남은 4건은 정산 규칙이 정해질 때까지 멈춥니다.',
          '[{"idx":0,"text":"12건 중 8건은 이관을 끝냈어요."},{"idx":1,"text":"남은 4건은 정산 규칙이 정해질 때까지 멈춥니다."}]',
          now() + interval '30 days')
  returning id into tv;
  insert into public.analysis_run (owner_id, input_transcript_version_id, model_id, prompt_version, config_hash, status)
  values (A, tv, 'synthetic', 'state-v1', 'synthetic:state-v1', 'done') returning id into run;

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);

  -- ── 1. 빈 프로젝트에 직접 쓰기 → 버전 1, 직접 수정·근거 시각 ──
  set local role authenticated;
  r := public.wb_set_project_state(pA, '  이관 범위를 정하는 중.  ', 'active', 0);
  reset role;
  select current_state_version into cur from public.project where id = pA;
  select * into row from public.project_state_version where project_id = pA and version = 1;
  res := res || format('%s 직접 쓰기 → 버전 1·앞뒤 공백 정리·직접 수정 (%s / %s / %s / %s)',
    case when r->>'result' = 'saved' and cur = 1 and row.summary = '이관 범위를 정하는 중.' and row.applied_by = 'user'
              and row.basis_at is not null and row.source_record_ids = '{}'::uuid[] then 'PASS' else 'FAIL' end,
    r->>'result', cur, row.summary, row.applied_by);

  -- ── 2. 본 버전이 낡았으면 쓰지 않고 충돌 ──
  set local role authenticated;
  r := public.wb_set_project_state(pA, '다른 기기에서 쓴 요약', 'paused', 0);
  reset role;
  select count(*) into n from public.project_state_version where project_id = pA;
  res := res || format('%s 낡은 버전으로 쓰면 충돌·안 씀 (%s, 지금 %s, %s행)',
    case when r->>'result' = 'conflict' and (r->>'current_version')::int = 1 and n = 1 then 'PASS' else 'FAIL' end, r->>'result', r->>'current_version', n);

  -- ── 3. 상태·요약 검사 ──
  v_err := null; begin set local role authenticated; r := public.wb_set_project_state(pA, '요약', 'waiting', 1); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 모르는 상태 거부 (%s)', case when v_err = '22023' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null; begin set local role authenticated; r := public.wb_set_project_state(pA, '   ', 'active', 1); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 빈 요약 거부 (%s)', case when v_err = '22023' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null; begin set local role authenticated; r := public.wb_set_project_state(pA, repeat('가', 601), 'active', 1); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 601자 요약 거부 (%s)', case when v_err = '22023' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 4. 남의 프로젝트·아카이브한 프로젝트 ──
  v_err := null; begin set local role authenticated; r := public.wb_set_project_state(pB, '요약', 'active', 0); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 남의 프로젝트는 없음으로 (%s)', case when v_err = 'P0002' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null; begin set local role authenticated; r := public.wb_set_project_state(pArch, '요약', 'done', 0); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 아카이브한 프로젝트 거부 (%s)', case when v_err = '40001' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 5. 현재 상태 제안 승인(검토함·상세 둘 다 inbox) → 버전 2, 근거 회의 시각·반영한 기록 ──
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, target_id, base_version, before_json, after_json, rationale)
  values (A, run, 'project_state', pA, 1, '{"summary":"이관 범위를 정하는 중.","status":"active"}',
          jsonb_build_object('summary', '12건 중 8건 이관 끝. 남은 4건은 정산 규칙 확정 대기 중.', 'status', 'paused',
                             'basis_at', '2026-11-05T01:00:00Z', 'records', jsonb_build_array(rA), 'past', false),
          '남은 4건이 정산 규칙 확정을 기다리게 됨') returning id into prop;
  insert into public.evidence_link (owner_id, proposal_id, transcript_version_id, segment_ref) values (A, prop, tv, '{"idx":[0,1]}');
  v_err := null; begin set local role authenticated; r := public.approve_proposal(prop, 0, 'record', null); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 기록 상세 승인 위치는 그대로 막힘 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  set local role authenticated;
  r := public.approve_proposal(prop, 0, 'inbox', null);
  reset role;
  select current_state_version into cur from public.project where id = pA;
  select * into row from public.project_state_version where project_id = pA and version = 2;
  res := res || format('%s 상태 제안 승인 → 버전 2·멈춤·근거 시각·기록 (%s / %s / %s / %s)',
    case when r->>'result' = 'approved' and cur = 2 and row.status = 'paused' and row.applied_by = 'ai-approved'
              and row.basis_at = '2026-11-05T01:00:00Z'::timestamptz and row.source_record_ids = array[rA] then 'PASS' else 'FAIL' end,
    r->>'result', cur, row.status, row.source_record_ids);

  -- ── 6. 직접 고쳐도 반영한 기록은 이어 간다 ──
  set local role authenticated;
  r := public.wb_set_project_state(pA, '12건 중 8건 이관 끝. 남은 4건은 11/20 리뷰 뒤 진행.', 'active', 2);
  reset role;
  select * into row from public.project_state_version where project_id = pA and version = 3;
  res := res || format('%s 직접 고치기 → 버전 3·반영한 기록 그대로 (%s / %s)',
    case when r->>'result' = 'saved' and row.source_record_ids = array[rA] and row.applied_by = 'user' then 'PASS' else 'FAIL' end, r->>'result', row.source_record_ids);

  -- ── 7. 직접 고친 뒤 예전 버전 기준 제안은 승인할 때 충돌 ──
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, target_id, base_version, after_json, rationale)
  values (A, run, 'project_state', pA, 2, '{"summary":"예전 기준 요약","status":"active","records":[]}', '근거') returning id into propStale;
  insert into public.evidence_link (owner_id, proposal_id, transcript_version_id, segment_ref) values (A, propStale, tv, '{"idx":[0]}');
  set local role authenticated;
  r := public.approve_proposal(propStale, 0, 'inbox', null);
  reset role;
  select current_state_version into cur from public.project where id = pA;
  res := res || format('%s 낡은 제안 승인 → 충돌·상태 그대로 (%s / 버전 %s / %s)',
    case when r->>'result' = 'conflict' and cur = 3 and (select status from public.change_proposal where id = propStale) = 'conflict' then 'PASS' else 'FAIL' end,
    r->>'result', cur, (select status from public.change_proposal where id = propStale));

  -- ── 8. 프로젝트마다 대기 제안은 하나 ──
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, target_id, base_version, after_json, rationale)
  values (A, run, 'project_state', pA, 3, '{"summary":"대기 1","status":"active"}', '근거') returning id into prop2;
  v_err := null;
  begin
    insert into public.change_proposal (owner_id, analysis_run_id, target_type, target_id, base_version, after_json, rationale)
    values (A, run, 'project_state', pA, 3, '{"summary":"대기 2","status":"active"}', '근거');
  exception when others then v_err := sqlstate;
  end;
  res := res || format('%s 같은 프로젝트에 대기 제안 둘은 거부 (%s)', case when v_err = '23505' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 9. 승인·거절된 제안은 하나로 세지 않는다(새 대기 제안 가능) ──
  set local role authenticated;
  r := public.reject_proposal(prop2, 0, 'inbox');
  reset role;
  v_err := null;
  begin
    insert into public.change_proposal (owner_id, analysis_run_id, target_type, target_id, base_version, after_json, rationale)
    values (A, run, 'project_state', pA, 3, '{"summary":"대기 3","status":"active"}', '근거');
  exception when others then v_err := sqlstate;
  end;
  res := res || format('%s 거절 뒤에는 새 대기 제안 가능 (%s / %s)', case when r->>'result' = 'rejected' and v_err is null then 'PASS' else 'FAIL' end, r->>'result', coalesce(v_err, '허용됨'));

  -- ── 10. 권한: 익명은 직접 쓰기 함수를 부를 수 없고, 로그인 사용자는 부를 수 있다 ──
  res := res || format('%s 실행 권한(익명 불가·로그인 가능)',
    case when not has_function_privilege('anon', 'public.wb_set_project_state(uuid, text, text, int)', 'execute')
              and has_function_privilege('authenticated', 'public.wb_set_project_state(uuid, text, text, int)', 'execute') then 'PASS' else 'FAIL' end);
  -- 처리 함수는 대기 제안의 내용만 고쳐 쓸 수 있고 상태(승인·거절)는 못 바꾼다
  res := res || format('%s 처리 함수 권한(지금 상태 읽기·제안 내용 고치기만)',
    case when has_table_privilege('service_role', 'public.project_state_version', 'select')
              and has_column_privilege('service_role', 'public.change_proposal', 'after_json', 'update')
              and not has_column_privilege('service_role', 'public.change_proposal', 'status', 'update')
              and not has_table_privilege('service_role', 'public.project_state_version', 'insert') then 'PASS' else 'FAIL' end);

  -- ── 11. 로그인 사용자는 현재 상태 표에 직접 쓸 수 없다(함수로만) ──
  v_err := null;
  begin
    set local role authenticated;
    insert into public.project_state_version (owner_id, project_id, version, summary, status, applied_by) values (A, pA, 9, '몰래', 'done', 'user');
  exception when others then v_err := sqlstate;
  end;
  reset role;
  res := res || format('%s 표에 직접 쓰기 거부 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 12. 남의 현재 상태는 안 보인다(RLS) ──
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.project_state_version where project_id = pA;
  reset role;
  res := res || format('%s 남의 현재 상태는 안 보임 (%s행)', case when n = 0 then 'PASS' else 'FAIL' end, n);

  -- 결과를 예외로 돌려주며 모든 변경을 되돌린다(합성 계정도 남지 않음)
  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
