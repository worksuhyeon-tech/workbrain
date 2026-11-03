-- P1 실측: FR-28 검토 미루기 (마이그레이션 0028, DEC-63) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 시작·끝에 시험 데이터를 지워 다시 실행해도 같은 결과.
-- 사용자는 합성 시험 계정 A/B뿐이다(@example.test). 결과는 마지막 SELECT.
create temp table if not exists p1_result (seq serial, name text, pass boolean, detail text);
truncate p1_result;

do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000028a1';
  B uuid := '00000000-0000-4000-a000-0000000028b1';
  wsA uuid; wsB uuid; srA uuid; srB uuid; tvA uuid; tvB uuid; runA uuid; runB uuid;
  pA1 uuid; pA2 uuid; pDone uuid; pB uuid;
  today date := (now() at time zone 'Asia/Seoul')::date;
  r jsonb; n int; v_err text; v_ts timestamptz;
begin
  -- ── 0. 정리 후 시드(서비스 권한) ──
  delete from auth.users where id in (A, B);
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  values (A, 'authenticated', 'authenticated', 'p1-sz-a@example.test', now(), now()),
         (B, 'authenticated', 'authenticated', 'p1-sz-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-sz-a@example.test'), (B, 'p1-sz-b@example.test') on conflict (id) do nothing;
  insert into public.settings (user_id) values (A), (B) on conflict (user_id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;
  select id into wsB from public.workspace where owner_id = B limit 1;
  if wsB is null then insert into public.workspace (owner_id, name) values (B, '본업') returning id into wsB; end if;

  insert into public.source_record (owner_id, workspace_id, channel, storage_permission) values (A, wsA, 'recording', 'sample') returning id into srA;
  insert into public.source_record (owner_id, workspace_id, channel, storage_permission) values (B, wsB, 'recording', 'sample') returning id into srB;
  insert into public.transcript_version (owner_id, source_record_id, kind, content, segments, retention_expires_at)
    values (A, srA, 'stt-original', '합성 전사', '[{"idx":0,"text":"합성 문장"}]', now() + interval '6 months') returning id into tvA;
  insert into public.transcript_version (owner_id, source_record_id, kind, content, retention_expires_at)
    values (B, srB, 'stt-original', '합성 전사', now() + interval '6 months') returning id into tvB;
  insert into public.analysis_run (owner_id, input_transcript_version_id, model_id, prompt_version, config_hash, status)
    values (A, tvA, 'test', 'test', 'test', 'done') returning id into runA;
  insert into public.analysis_run (owner_id, input_transcript_version_id, model_id, prompt_version, config_hash, status)
    values (B, tvB, 'test', 'test', 'test', 'done') returning id into runB;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
    values (A, runA, 'task', '{"title":"합성 할 일 1","owner_kind":"unknown"}', '합성') returning id into pA1;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
    values (A, runA, 'task', '{"title":"합성 할 일 2","owner_kind":"unknown"}', '합성') returning id into pA2;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale, status)
    values (A, runA, 'task', '{"title":"이미 처리"}', '합성', 'rejected') returning id into pDone;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
    values (B, runB, 'task', '{"title":"B 후보"}', '합성') returning id into pB;
  insert into public.evidence_link (owner_id, proposal_id, transcript_version_id, segment_ref)
    values (A, pA2, tvA, '{"idx":[0]}');

  -- ── 1. 권한 ──
  set local role anon;
  v_err := null; begin r := public.wb_snooze_proposals(array[pA1], today + 2); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-28 anon 미루기 호출 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  v_err := null; begin update public.change_proposal set snoozed_until = now() + interval '1 day' where id = pA1; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-28 Data API로 snoozed_until 직접 변경 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  -- ── 2. 날짜 검사 ──
  set local role authenticated;
  v_err := null; begin r := public.wb_snooze_proposals(array[pA1], today); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-28 오늘 날짜로 미루기 → WB400', v_err like 'WB400%', coalesce(v_err, r::text));
  set local role authenticated;
  v_err := null; begin r := public.wb_snooze_proposals(array[pA1], today + 400); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-28 1년 넘게 미루기 → WB400', v_err like 'WB400%', coalesce(v_err, r::text));
  set local role authenticated;
  v_err := null; begin r := public.wb_snooze_proposals(array[]::uuid[], today + 2); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-28 빈 목록 → WB400', v_err like 'WB400%', coalesce(v_err, r::text));

  -- ── 3. 미루기: 내 대기 후보만, 상태·revision 그대로 ──
  set local role authenticated;
  r := public.wb_snooze_proposals(array[pA1, pA2, pDone, pB], today + 2);
  reset role;
  insert into p1_result values (default, 'FR-28 내 대기 후보 2건만 미룸(처리된 것·남의 것 건너뜀)', (r->>'updated')::int = 2, r::text);
  select snoozed_until into v_ts from public.change_proposal where id = pA1;
  insert into p1_result values (default, 'FR-28 미룬 날 0시(서울)', v_ts = (today + 2)::timestamp at time zone 'Asia/Seoul', coalesce(v_ts::text, 'null'));
  select count(*) into n from public.change_proposal where id in (pA1, pA2) and status = 'pending' and revision = 0;
  insert into p1_result values (default, 'DEC-15 미뤄도 상태 pending·revision 그대로', n = 2, n || '/2');
  select count(*) into n from public.change_proposal where id in (pDone, pB) and snoozed_until is not null;
  insert into p1_result values (default, 'FR-28 처리된 후보·B 후보는 안 바뀜', n = 0, n || '건 바뀜');
  select count(*) into n from public.activity_log where owner_id = A and entity_type = 'change_proposal' and action = 'snoozed:' || to_char(today + 2, 'YYYY-MM-DD');
  insert into p1_result values (default, 'FR-28 활동 기록 2건(snoozed:날짜)', n = 2, n || '건');

  -- ── 4. 미루기 변경·지금 검토 ──
  set local role authenticated;
  r := public.wb_snooze_proposals(array[pA2], today + 7);
  reset role;
  select snoozed_until into v_ts from public.change_proposal where id = pA2;
  insert into p1_result values (default, 'FR-28 미루기 변경 → 새 날짜', v_ts = (today + 7)::timestamp at time zone 'Asia/Seoul', coalesce(v_ts::text, 'null'));
  set local role authenticated;
  r := public.wb_snooze_proposals(array[pA1], null);
  reset role;
  select count(*) into n from public.change_proposal where id = pA1 and snoozed_until is null;
  insert into p1_result values (default, 'FR-28 지금 검토(해제) → snoozed_until null', n = 1 and (r->>'updated')::int = 1, r::text);
  select count(*) into n from public.activity_log where owner_id = A and entity_id = pA1 and action = 'unsnoozed';
  insert into p1_result values (default, 'FR-28 해제 활동 기록', n = 1, n || '건');

  -- ── 5. 미룸은 승인과 무관: 미룬 후보도 승인 함수로 추가된다(기록 상세에서 체크해 추가하는 경우) ──
  set local role authenticated;
  r := public.approve_proposal(pA2, 0, 'record', null);
  reset role;
  insert into p1_result values (default, 'DEC-15 미룬 후보 승인 가능', r->>'result' = 'approved', r::text);

  delete from auth.users where id in (A, B);
end
$$;
select seq, case when pass then 'PASS' else 'FAIL' end as result, name, detail from p1_result order by seq;
