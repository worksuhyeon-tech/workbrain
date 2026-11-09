-- P1 실측: FR-06 AI 프로젝트 연결 제안 (마이그레이션 0033·0034, DEC-75) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 시작·끝에 시험 데이터를 지워 다시 실행해도 같은 결과.
-- 사용자는 합성 시험 계정 A/B뿐이다(@example.test). 결과는 마지막 SELECT.
create temp table if not exists p1_result (seq serial, name text, pass boolean, detail text);
truncate p1_result;

do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000033a1';
  B uuid := '00000000-0000-4000-a000-0000000033b1';
  wsA uuid; wsB uuid;
  p1 uuid; p2 uuid;
  rA uuid; tv uuid; run uuid; l1 uuid; l2 uuid;
  n int; v text; v_err text; c text;
begin
  -- ── 0. 정리 후 시드(서비스 권한) ──
  delete from auth.users where id in (A, B);
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  values (A, 'authenticated', 'authenticated', 'p1-suggest-a@example.test', now(), now()),
         (B, 'authenticated', 'authenticated', 'p1-suggest-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-suggest-a@example.test'), (B, 'p1-suggest-b@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;
  select id into wsB from public.workspace where owner_id = B limit 1;
  if wsB is null then insert into public.workspace (owner_id, name) values (B, '본업') returning id into wsB; end if;

  insert into public.project (owner_id, workspace_id, name, purpose) values (A, wsA, '합성 보상 ERP', '보상 업무 시스템 구축') returning id into p1;
  insert into public.project (owner_id, workspace_id, name) values (A, wsA, '합성 상담 챗봇') returning id into p2;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification)
  values (A, wsA, 'recording', '합성 ERP 회의', 'sample', 'work') returning id into rA;
  insert into public.transcript_version (source_record_id, owner_id, kind, content, segments, retention_expires_at)
  values (rA, A, 'stt-original', '보상 ERP 일정 점검. 챗봇은 다음에.',
          '[{"idx":0,"text":"보상 ERP 일정 점검."},{"idx":1,"text":"챗봇은 다음에."}]', now() + interval '30 days')
  returning id into tv;
  insert into public.analysis_run (owner_id, input_transcript_version_id, model_id, prompt_version, config_hash, status)
  values (A, tv, 'synthetic', 'link-v1', 'synthetic:link-v1', 'done') returning id into run;

  -- ── 1. 처리 함수(service_role)가 제안을 남긴다 ──
  set local role service_role;
  v_err := null; begin
    insert into public.source_project_link (owner_id, source_record_id, transcript_version_id, item_ref, project_id, status, created_by,
                                            confidence, uncertainty_reasons, analysis_run_id, rationale)
    values (A, rA, tv, '{"scope":"record"}', p1, 'proposed', 'ai', 'high', '{}', run, '보상 ERP 일정 점검을 논의함') returning id into l1;
    insert into public.evidence_link (owner_id, source_project_link_id, transcript_version_id, segment_ref, quote)
    values (A, l1, tv, '{"idx":[0]}', '보상 ERP 일정 점검.');
  exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'DEC-75 처리 함수가 기록 단위 연결 제안·근거를 남김', v_err is null and l1 is not null, coalesce(v_err, '저장됨'));

  set local role service_role;
  v_err := null; begin
    insert into public.source_project_link (owner_id, source_record_id, transcript_version_id, item_ref, project_id, status, created_by, confidence)
    values (A, rA, tv, '{"scope":"record"}', p1, 'proposed', 'ai', 'medium');
  exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'DEC-75 같은 기록·프로젝트 제안 두 번 → 거부(하나만)', v_err is not null, coalesce(v_err, '허용됨'));

  set local role service_role;
  v_err := null; begin
    insert into public.source_project_link (owner_id, source_record_id, transcript_version_id, item_ref, project_id, status, created_by, confidence)
    values (A, rA, tv, '{"scope":"record"}', p2, 'proposed', 'ai', 'certain');
  exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'M12 확신 등급은 high·medium·low·unrated만', v_err like '%confidence_check%', coalesce(v_err, '허용됨'));

  set local role service_role;
  v_err := null; begin
    insert into public.source_project_link (owner_id, source_record_id, transcript_version_id, item_ref, project_id, status, created_by, rationale)
    values (A, rA, tv, '{"scope":"record"}', p2, 'proposed', 'ai', repeat('가', 201));
  exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'DEC-75 이유 한 줄은 200자까지', v_err like '%rationale_check%', coalesce(v_err, '허용됨'));

  set local role service_role;
  insert into public.source_project_link (owner_id, source_record_id, transcript_version_id, item_ref, project_id, status, created_by,
                                          confidence, uncertainty_reasons, analysis_run_id)
  values (A, rA, tv, '{"scope":"record"}', p2, 'proposed', 'ai', 'low', '{brief_mention,name_not_mentioned}', run) returning id into l2;
  reset role;

  -- ── 2. 사용자 A: 보고, 확인한다(0032 함수 그대로) ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.source_project_link l
    join public.evidence_link e on e.source_project_link_id = l.id
   where l.source_record_id = rA and l.status = 'proposed' and l.created_by = 'ai' and e.quote is not null and l.rationale is not null;
  reset role;
  insert into p1_result values (default, 'DEC-75 앱이 제안·이유 한 줄·근거 문장을 읽음', n = 1, n || '건');

  set local role authenticated;
  v := public.wb_link_record_project(rA, p1, true);
  reset role;
  select count(*) into n from public.source_project_link
   where id = l1 and status = 'confirmed' and created_by = 'ai' and version = 1;
  insert into p1_result values (default, 'DEC-75 [연결] → 같은 행이 확정(만든 주체 ai 유지)', v = 'linked' and n = 1, v || ' · ' || n || '행');

  set local role authenticated;
  v := public.wb_link_record_project(rA, p2, false);
  reset role;
  select count(*) into n from public.source_project_link where id = l2 and status = 'rejected';
  insert into p1_result values (default, 'DEC-75 [아니요] → rejected(행은 남아 다시 제안 안 함)', v = 'unlinked' and n = 1, v || ' · ' || n || '행');

  -- ── 3. 분류 고치기(앱이 직접, 기존 권한) ──
  set local role authenticated;
  update public.source_record set classification = 'personal' where id = rA;
  reset role;
  select classification into c from public.source_record where id = rA;
  insert into p1_result values (default, 'DEC-75 분류를 개인으로 고침', c = 'personal', c);
  set local role authenticated;
  v_err := null; begin update public.source_record set classification = 'family' where id = rA; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-06 분류는 업무·개인·불확실만', v_err is not null, coalesce(v_err, '허용됨'));

  -- ── 4. 다른 사용자 ──
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.source_project_link where source_record_id = rA;
  update public.source_record set classification = 'work' where id = rA;
  reset role;
  select classification into c from public.source_record where id = rA;
  insert into p1_result values (default, 'NFR-01 B는 A의 제안을 못 봄', n = 0, n || '건');
  insert into p1_result values (default, 'NFR-01 B는 A의 분류를 못 고침', c = 'personal', c);
  set local role authenticated;
  v_err := null; begin perform public.wb_link_record_project(rA, p1, false); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'NFR-01 B가 A의 제안을 거절 → 없음과 같게 거부', v_err like 'WB404%', coalesce(v_err, '허용됨'));

  -- 계정을 지우면 기록·제안·근거·실행이 연쇄로 지워진다
  delete from auth.users where id in (A, B);
  select count(*) into n from public.app_user where id in (A, B);
  insert into p1_result values (default, '계정 삭제(연쇄)가 제안·근거 표에 막히지 않음', n = 0, n || '건 남음');
end
$$;
select seq, case when pass then 'PASS' else 'FAIL' end as result, name, detail from p1_result order by seq;
