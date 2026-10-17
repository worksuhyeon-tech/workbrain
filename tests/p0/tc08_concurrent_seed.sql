-- P0 TC-08 동시 승인 시험 준비(tc08_concurrent_rest.mjs 실행 전, 서비스 권한 SQL)
-- __P0_TEST_PASSWORD__ 를 .env의 P0_TEST_PASSWORD 값으로 바꿔 실행한다. 비밀번호를 이 파일에 적어 커밋하지 않는다.
-- 시험 뒤 정리: delete from auth.users where id = '00000000-0000-4000-a000-0000000000c1';
do $$
declare
  C uuid := '00000000-0000-4000-a000-0000000000c1';
  ws uuid; pj uuid; sr uuid; tv uuid; ar uuid; p uuid; i int;
begin
  delete from auth.users where id = C;
  -- 비밀번호 로그인이 되는 임시 계정(GoTrue는 토큰 칸이 NULL이면 읽기 오류를 내므로 빈 문자열로 둔다)
  insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
    raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
    confirmation_token, email_change, email_change_token_new, recovery_token)
  values ('00000000-0000-0000-0000-000000000000', C, 'authenticated', 'authenticated', 'p0-concurrency@example.test',
    extensions.crypt('__P0_TEST_PASSWORD__', extensions.gen_salt('bf')), now(),
    '{"provider":"email","providers":["email"]}', '{}', now(), now(), '', '', '', '');
  insert into auth.identities (id, provider_id, user_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
  values (gen_random_uuid(), C::text, C, jsonb_build_object('sub', C::text, 'email', 'p0-concurrency@example.test', 'email_verified', true), 'email', now(), now(), now());
  insert into public.app_user (id, email) values (C, 'p0-concurrency@example.test') on conflict (id) do nothing;
  insert into public.settings (user_id) values (C) on conflict (user_id) do nothing;
  insert into public.workspace (owner_id, name) values (C, '본업') returning id into ws;
  insert into public.project (owner_id, workspace_id, name) values (C, ws, '동시성 시험 프로젝트') returning id into pj;
  insert into public.source_record (owner_id, workspace_id, channel, storage_permission) values (C, ws, 'recording', 'sample') returning id into sr;
  insert into public.transcript_version (owner_id, source_record_id, kind, content, retention_expires_at)
    values (C, sr, 'stt-original', '합성 전사', now() + interval '6 months') returning id into tv;
  insert into public.analysis_run (owner_id, input_transcript_version_id, model_id, prompt_version, config_hash, status)
    values (C, tv, 'p0-fake', 'p0', 'h', 'done') returning id into ar;
  -- 새 할 일 제안 20건: 두 기기가 같은 제안을 동시에 승인
  for i in 1..20 loop
    insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale, dedup_key)
      values (C, ar, 'task', jsonb_build_object('project_id', pj, 'title', '동시 승인 할 일 '||i, 'owner_kind', 'unknown'), '합성', 'race-task') returning id into p;
    insert into public.evidence_link (owner_id, proposal_id, transcript_version_id, segment_ref) values (C, p, tv, '{"idx":0}');
  end loop;
  -- 같은 기준 버전(0)의 상태 변경 제안 10건: 동시에 승인하면 하나만 반영, 나머지는 충돌
  for i in 1..10 loop
    insert into public.change_proposal (owner_id, analysis_run_id, target_type, target_id, base_version, after_json, rationale, dedup_key)
      values (C, ar, 'project_state', pj, 0, jsonb_build_object('summary', '상태 후보 '||i, 'status', 'active'), '합성', 'race-state') returning id into p;
    insert into public.evidence_link (owner_id, proposal_id, transcript_version_id, segment_ref) values (C, p, tv, '{"idx":0}');
  end loop;
end
$$;
