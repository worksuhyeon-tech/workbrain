-- P1 실측: FR-07 회의록 승인 + Q5 승인 회의록 3년·근거 문장 저장 (마이그레이션 0027, DEC-61) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 시작·끝에 시험 데이터를 지워 다시 실행해도 같은 결과.
-- 사용자는 합성 시험 계정 A/B뿐이다(@example.test). 실제 개인·업무 데이터 없음.
-- 사용자 흉내: `set local role authenticated` + request.jwt.claims.sub. 결과는 마지막 SELECT.
create temp table if not exists p1_result (seq serial, name text, pass boolean, detail text);
truncate p1_result;

do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000007a1';
  B uuid := '00000000-0000-4000-a000-0000000007b1';
  wsA uuid; srA uuid; srNoDoc uuid; srGone uuid; tvA uuid; tvNoDoc uuid; tvGone uuid;
  mvOld uuid; mvNew uuid; mvNew2 uuid; mvNoDoc uuid; mvGone uuid; arun uuid; pTask uuid;
  segs jsonb := '[{"idx":0,"text":"첫 문장: 개선안은 이번 주에 확정합니다."},{"idx":1,"text":"둘째 문장: 공수는 박지훈이 다시 봅니다."},{"idx":2,"text":"셋째 문장: 안내 문구는 다음 회의에서."}]';
  doc jsonb := '{"title":"합성 회의록","sections":[{"heading":"1. 회의 개요","lines":["합성"]}]}';
  flds jsonb := '{"title":"합성","decisions":[{"text":"개선안 이번 주 확정","segments":[2,0,1]}],"open_questions":[{"text":"안내 문구","segments":[]}]}';
  r jsonb; n int; v_err text; v_text text; v_ts timestamptz;
begin
  -- ── 0. 정리 후 시드(서비스 권한) ──
  delete from auth.users where id in (A, B);
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  values (A, 'authenticated', 'authenticated', 'p1-mv-a@example.test', now(), now()),
         (B, 'authenticated', 'authenticated', 'p1-mv-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-mv-a@example.test'), (B, 'p1-mv-b@example.test') on conflict (id) do nothing;
  -- 계정 생성 훅이 이미 만들었을 수 있어 기본값 그대로 둔다(설정 기본값 3년 확인용)
  insert into public.settings (user_id) values (A), (B) on conflict (user_id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then
    insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA;
  end if;

  insert into public.source_record (owner_id, workspace_id, channel, storage_permission) values (A, wsA, 'recording', 'sample') returning id into srA;
  insert into public.source_record (owner_id, workspace_id, channel, storage_permission) values (A, wsA, 'recording', 'sample') returning id into srNoDoc;
  insert into public.source_record (owner_id, workspace_id, channel, storage_permission) values (A, wsA, 'recording', 'sample') returning id into srGone;
  insert into public.transcript_version (owner_id, source_record_id, kind, content, segments, retention_expires_at)
    values (A, srA, 'stt-original', '합성 전사', segs, now() + interval '6 months') returning id into tvA;
  insert into public.transcript_version (owner_id, source_record_id, kind, content, segments, retention_expires_at)
    values (A, srNoDoc, 'stt-original', '합성 전사', segs, now() + interval '6 months') returning id into tvNoDoc;
  insert into public.transcript_version (owner_id, source_record_id, kind, content, segments, retention_expires_at)
    values (A, srGone, 'stt-original', null, null, now() - interval '1 day') returning id into tvGone;
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, document, retention_expires_at, created_at)
    values (A, tvA, 'draft', flds, doc, now() + interval '6 months', now() - interval '2 hours') returning id into mvOld;
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, document, parent_version_id, retention_expires_at, created_at)
    values (A, tvA, 'draft', flds, doc, mvOld, now() + interval '6 months', now() - interval '1 hour') returning id into mvNew;
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, retention_expires_at)
    values (A, tvNoDoc, 'draft', flds, now() + interval '6 months') returning id into mvNoDoc;
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, document, retention_expires_at)
    values (A, tvGone, 'draft', flds, doc, now() + interval '6 months') returning id into mvGone;
  insert into public.analysis_run (owner_id, input_transcript_version_id, model_id, prompt_version, config_hash, status)
    values (A, tvA, 'test', 'test', 'test', 'done') returning id into arun;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
    values (A, arun, 'task', '{"title":"공수 재산정","owner_kind":"other","assignee":"박지훈"}', '합성 근거') returning id into pTask;
  insert into public.evidence_link (owner_id, proposal_id, meeting_version_id, transcript_version_id, segment_ref)
    values (A, pTask, mvNew, tvA, '{"idx":[1]}');

  -- ── 1. 설정 기본값 ──
  select column_default into v_text from information_schema.columns
   where table_schema = 'public' and table_name = 'settings' and column_name = 'retention_knowledge_years';
  insert into p1_result values (default, 'Q5 승인 보관 기본값 3년', v_text = '3', coalesce(v_text, 'null'));
  select retention_knowledge_years into n from public.settings where user_id = A;
  insert into p1_result values (default, 'Q5 새 계정 설정 값 3', n = 3, n::text);

  -- ── 2. 권한·격리 ──
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  v_err := null; begin r := public.approve_meeting(mvNew); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-07 B가 A 회의록 승인 → 없음(WB404)', v_err like 'WB404%', coalesce(v_err, r::text));

  set local role anon;
  v_err := null; begin r := public.approve_meeting(mvNew); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-07 anon 승인 함수 호출 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  v_err := null; begin update public.meeting_version set state = 'approved' where id = mvNew; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-07 Data API로 state 직접 승인 → 거부', v_err is not null, coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin v_text := public.wb_evidence_quote(tvA, '[0]'); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'Q5 근거 문장 함수 직접 호출 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  -- ── 3. 승인 전 검사 ──
  set local role authenticated;
  r := public.approve_meeting(mvOld);
  reset role;
  insert into p1_result values (default, 'FR-07 더 새 버전이 있는 옛 버전 승인 → stale-version', r->>'result' = 'stale-version', r::text);
  set local role authenticated;
  r := public.approve_meeting(mvNoDoc);
  reset role;
  insert into p1_result values (default, 'FR-07 문서 없는 회의록 승인 → no-document', r->>'result' = 'no-document', r::text);
  set local role authenticated;
  r := public.approve_meeting(mvGone);
  reset role;
  insert into p1_result values (default, 'FR-07 전사 본문 만료 → evidence-missing', r->>'result' = 'evidence-missing', r::text);
  select count(*) into n from public.meeting_version where id in (mvOld, mvNoDoc, mvGone) and state <> 'draft';
  insert into p1_result values (default, 'FR-07 거절된 승인은 상태 변화 0', n = 0, n || '건 바뀜');

  -- ── 4. 승인 ──
  set local role authenticated;
  r := public.approve_meeting(mvNew);
  reset role;
  insert into p1_result values (default, 'FR-07 최신 버전 승인 → approved', r->>'result' = 'approved', r::text);
  select retention_expires_at into v_ts from public.meeting_version where id = mvNew and state = 'approved' and approved_at is not null;
  insert into p1_result values (default, 'Q5 보관 기한 = 승인 시각 + 3년', v_ts = now() + interval '3 years', coalesce(v_ts::text, '승인 안 됨'));
  select count(*) into n from public.approval_event where meeting_version_id = mvNew and target_type = 'meeting' and context = 'record';
  insert into p1_result values (default, 'FR-07 승인 기록 1건(context=record)', n = 1, n || '건');
  select quote into v_text from public.evidence_link where meeting_version_id = mvNew and segment_ref->>'item' = 'decisions';
  insert into p1_result values (default, 'Q5 결정 근거 = 번호 앞선 2구간 문장(0,1)',
    v_text = '첫 문장: 개선안은 이번 주에 확정합니다. 둘째 문장: 공수는 박지훈이 다시 봅니다.', coalesce(v_text, 'null'));
  select count(*) into n from public.evidence_link where meeting_version_id = mvNew and segment_ref ? 'item';
  insert into p1_result values (default, 'Q5 근거 구간 없는 미결은 건너뜀(근거 1건)', n = 1, n || '건');

  set local role authenticated;
  r := public.approve_meeting(mvNew);
  reset role;
  select count(*) into n from public.approval_event where meeting_version_id = mvNew;
  insert into p1_result values (default, 'FR-07 다시 승인 → already-approved, 기록 1건 유지',
    r->>'result' = 'already-approved' and n = 1, r::text || ' / ' || n || '건');

  -- ── 5. 승인본 보호 ──
  v_err := null; begin update public.meeting_version set document = '{"title":"바꿈","sections":[]}' where id = mvNew; exception when others then v_err := sqlerrm; end;
  insert into p1_result values (default, 'FR-11 서버 키로도 승인본 문서 수정 → WB409', v_err like 'WB409%', coalesce(v_err, '바뀜'));
  v_err := null; begin update public.meeting_version set fields = '{}' where id = mvNew; exception when others then v_err := sqlerrm; end;
  insert into p1_result values (default, 'FR-11 승인본 추출 항목 수정 → WB409', v_err like 'WB409%', coalesce(v_err, '바뀜'));

  -- ── 6. 승인 뒤 다시 만들기 = 새 초안 → 그것을 승인하면 이전 승인본은 대체됨 ──
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, document, parent_version_id, retention_expires_at, created_at)
    values (A, tvA, 'draft', flds, doc, mvNew, now() + interval '6 months', now() + interval '1 minute') returning id into mvNew2;
  set local role authenticated;
  r := public.approve_meeting(mvNew2);
  reset role;
  select count(*) into n from public.meeting_version where id = mvNew and state = 'approved' and superseded_at is not null and document = doc;
  insert into p1_result values (default, 'FR-07 새 초안 승인 → 이전 승인본은 남고 superseded',
    r->>'result' = 'approved' and n = 1, r->>'result' || ' / 이전 승인본 ' || n || '건');

  -- ── 7. 할 일 후보 승인 → 근거 문장 저장 ──
  select count(*) into n from public.evidence_link where proposal_id = pTask and quote is not null;
  insert into p1_result values (default, 'Q5 승인 전 후보에는 근거 문장 없음', n = 0, n || '건');
  set local role authenticated;
  r := public.approve_proposal(pTask, 0, 'record', null);
  reset role;
  select quote into v_text from public.evidence_link where proposal_id = pTask;
  insert into p1_result values (default, 'Q5 할 일 후보 승인 → 근거 문장 저장',
    r->>'result' = 'approved' and v_text = '둘째 문장: 공수는 박지훈이 다시 봅니다.', r->>'result' || ' / ' || coalesce(v_text, 'null'));

  -- ── 8. 근거 문장 300자 상한 ──
  update public.transcript_version set segments = jsonb_build_array(jsonb_build_object('idx', 0, 'text', repeat('가', 250)), jsonb_build_object('idx', 1, 'text', repeat('나', 250)))
   where id = tvNoDoc;
  v_text := public.wb_evidence_quote(tvNoDoc, '[1,0,1]');
  insert into p1_result values (default, 'Q5 근거 문장 300자 자름·중복 번호 1번만', char_length(v_text) = 300 and left(v_text, 1) = '가', char_length(v_text)::text);

  delete from auth.users where id in (A, B);
end
$$;
select seq, case when pass then 'PASS' else 'FAIL' end as result, name, detail from p1_result order by seq;
