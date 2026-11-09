-- P1 실측: DEC-77 할 일 받는 사람 (마이그레이션 0035) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0035 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다(원격 확인 창 만료 회피). 사용자는 합성 시험 계정 A뿐이다(@example.test).
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000035a1';
  wsA uuid; rA uuid; tv uuid; run uuid; mv uuid;
  pNew uuid; pEdit uuid; pBad uuid; tId uuid;
  r jsonb; v_err text; n int; arr text[]; snap jsonb;
  res text[] := '{}';
begin
  -- ── 0. 시드(서비스 권한) ──
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  values (A, 'authenticated', 'authenticated', 'p1-recipients-a@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-recipients-a@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification, attendees)
  values (A, wsA, 'recording', '합성 메일 회의', 'sample', 'work', array['박서연', '박지훈']) returning id into rA;
  insert into public.transcript_version (source_record_id, owner_id, kind, content, segments, retention_expires_at)
  values (rA, A, 'stt-original', '회의록은 박서연 님께 메일로 보내 주세요.',
          '[{"idx":0,"text":"회의록은 박서연 님께 메일로 보내 주세요.","speaker":"이도윤"}]', now() + interval '30 days')
  returning id into tv;
  insert into public.analysis_run (owner_id, input_transcript_version_id, model_id, prompt_version, config_hash, status)
  values (A, tv, 'synthetic', 'prompt-v4', 'synthetic:prompt-v4', 'done') returning id into run;
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, retention_expires_at)
  values (A, tv, 'draft', '{}'::jsonb, now() + interval '30 days') returning id into mv;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
  values (A, run, 'task', '{"title":"회의록 메일 보내기","owner_kind":"unknown","recipients":["박서연","나"],"owner_basis":"speaker"}', '근거')
  returning id into pNew;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
  values (A, run, 'task', '{"title":"자료 전달","owner_kind":"unknown","recipients":["박서연"]}', '근거') returning id into pEdit;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
  values (A, run, 'task', '{"title":"형식 틀린 받는 사람","owner_kind":"unknown","recipients":"박서연"}', '근거') returning id into pBad;
  insert into public.evidence_link (owner_id, proposal_id, meeting_version_id, transcript_version_id, segment_ref)
  select A, p, mv, tv, '{"idx":[0]}'::jsonb from unnest(array[pNew, pEdit, pBad]) as p;

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);

  -- ── 1. 승인: AI 받는 사람이 할 일로 옮겨진다(owner_basis는 할 일 칸이 아니라 옮기지 않음) ──
  set local role authenticated;
  r := public.approve_proposal(pNew, 0, 'record', null);
  reset role;
  select recipients into arr from public.task where owner_id = A and title = '회의록 메일 보내기';
  res := res || format('%s 승인하면 받는 사람이 할 일에 옮겨짐 (%s / %s)',
    case when r->>'result' = 'approved' and arr = array['박서연','나'] then 'PASS' else 'FAIL' end, r->>'result', arr);

  -- ── 2. 승인 전에 고친 받는 사람(p_edits)이 AI 값을 덮는다 ──
  set local role authenticated;
  r := public.approve_proposal(pEdit, 0, 'record', '{"recipients":["이도윤","  "]}'::jsonb);
  reset role;
  select recipients into arr from public.task where owner_id = A and title = '자료 전달';
  res := res || format('%s 고친 받는 사람으로 승인(빈 이름은 뺌) (%s)', case when arr = array['이도윤'] then 'PASS' else 'FAIL' end, arr);

  -- ── 3. 배열이 아닌 받는 사람은 빈 목록 ──
  set local role authenticated;
  r := public.approve_proposal(pBad, 0, 'record', null);
  reset role;
  select recipients into arr from public.task where owner_id = A and title = '형식 틀린 받는 사람';
  res := res || format('%s 배열이 아닌 받는 사람은 빈 목록 (%s)', case when arr = '{}'::text[] then 'PASS' else 'FAIL' end, arr);

  -- ── 4. 앱이 직접 넣은 할 일(소유자는 기본값 auth.uid): 받는 사람 없으면 빈 목록, 있으면 그대로 ──
  set local role authenticated;
  insert into public.task (title) values ('받는 사람 없음');
  insert into public.task (title, recipients) values ('받는 사람 있음', array['협력사']) returning id into tId;
  reset role;
  select count(*) into n from public.task where owner_id = A and title = '받는 사람 없음' and recipients = '{}'::text[];
  select recipients into arr from public.task where id = tId;
  res := res || format('%s 앱 직접 넣기: 기본 빈 목록·값 그대로 (%s / %s)', case when n = 1 and arr = array['협력사'] then 'PASS' else 'FAIL' end, n, arr);

  -- ── 5. 앱이 받는 사람을 고친다(버전 올림 규칙 그대로) ──
  set local role authenticated;
  update public.task set recipients = array['협력사','보험사'], version = version + 1 where id = tId;
  reset role;
  select recipients into arr from public.task where id = tId;
  res := res || format('%s 앱이 받는 사람 고치기 (%s)', case when arr = array['협력사','보험사'] then 'PASS' else 'FAIL' end, arr);

  -- ── 6. 이력 스냅샷에 받는 사람이 남는다(M18) ──
  select snapshot into snap from public.task_revision where task_id = tId order by version desc limit 1;
  res := res || format('%s 이력 스냅샷에 받는 사람 (%s)', case when snap->'recipients' = '["협력사","보험사"]'::jsonb then 'PASS' else 'FAIL' end, snap->'recipients');

  -- ── 7~9. 표 제약: 6명·빈 이름·41자는 거부 ──
  set local role authenticated;
  v_err := null; begin update public.task set recipients = array['a','b','c','d','e','f'], version = version + 1 where id = tId; exception when others then v_err := sqlstate; end;
  res := res || format('%s 받는 사람 6명 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null; begin update public.task set recipients = array[' '], version = version + 1 where id = tId; exception when others then v_err := sqlstate; end;
  res := res || format('%s 빈 이름 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  v_err := null; begin update public.task set recipients = array[repeat('가', 41)], version = version + 1 where id = tId; exception when others then v_err := sqlstate; end;
  res := res || format('%s 41자 이름 거부 (%s)', case when v_err = '23514' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));
  reset role;

  -- ── 10. 다른 사용자(익명)는 받는 사람을 고칠 수 없다 ──
  set local role anon;
  v_err := null; begin update public.task set recipients = array['x'], version = version + 1 where id = tId; get diagnostics n = row_count; exception when others then v_err := sqlstate; n := 0; end;
  reset role;
  select recipients into arr from public.task where id = tId;
  res := res || format('%s 익명은 고칠 수 없음 (%s건, %s)', case when n = 0 and arr = array['협력사','보험사'] then 'PASS' else 'FAIL' end, n, coalesce(v_err, '오류 없음'));

  -- ── 11. 받는 사람 고르기 칩: 참석자 + 이름 붙은 화자, 번호 화자·'(나)' 빼고 '님' 떼기 ──
  insert into public.transcript_version (source_record_id, owner_id, kind, content, segments, retention_expires_at)
  values (rA, A, 'term-corrected', 'a b c d',
          '[{"idx":0,"text":"a","speaker":"이도윤 님"},{"idx":1,"text":"b","speaker":"참석자 3"},{"idx":2,"text":"c","speaker":"하늘 김 (나)"},{"idx":3,"text":"d","speaker":"박서연"}]',
          now() + interval '30 days');
  set local role authenticated;
  arr := public.wb_record_people(rA);
  reset role;
  res := res || format('%s 고르기 칩 이름 (%s)', case when arr = array['박서연','박지훈','이도윤'] then 'PASS' else 'FAIL' end, arr);

  -- ── 12. 남의 기록이면 빈 목록(RLS) ──
  perform set_config('request.jwt.claims', json_build_object('sub', '00000000-0000-4000-a000-0000000035b1', 'role', 'authenticated')::text, true);
  set local role authenticated;
  arr := public.wb_record_people(rA);
  reset role;
  res := res || format('%s 남의 기록은 빈 목록 (%s)', case when arr = '{}'::text[] then 'PASS' else 'FAIL' end, arr);

  -- 결과를 예외로 돌려주며 모든 변경을 되돌린다(합성 계정도 남지 않음)
  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
