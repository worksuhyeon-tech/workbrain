-- P0 실측: TC-01(인증·격리) + TC-08(승인·충돌) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 다시 실행해도 같은 결과가 나오도록 시작 때 시험 데이터를 지운다.
-- 사용자는 합성 시험 계정 A/B뿐이다(@example.test). 실제 개인·업무 데이터 없음.
-- 사용자 흉내: `set local role authenticated` + request.jwt.claims.sub. anon은 `set local role anon`.
-- 결과: 마지막 SELECT가 시험별 PASS/FAIL과 상세를 돌려준다.

create temp table if not exists p0_result (seq serial, name text, pass boolean, detail text);
truncate p0_result;

do $$
declare
  A uuid := '00000000-0000-4000-a000-00000000000a';
  B uuid := '00000000-0000-4000-a000-00000000000b';
  wsA uuid; wsB uuid; pjA uuid; pjA2 uuid; arA uuid; pjB uuid; tkA uuid; rsB uuid; srA uuid; tvA uuid; tvGone uuid; arunA uuid;
  pNewTask uuid; pState uuid; pStale uuid; pNoEvidence uuid; pDue uuid;
  n int; v_err text; r jsonb; t text;
begin
  -- ── 0. 정리 후 시드(서비스 권한) ──
  delete from auth.users where id in (A, B);
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  values (A, 'authenticated', 'authenticated', 'p0-a@example.test', now(), now()),
         (B, 'authenticated', 'authenticated', 'p0-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p0-a@example.test'), (B, 'p0-b@example.test') on conflict (id) do nothing;
  insert into public.settings (user_id) values (A), (B) on conflict (user_id) do nothing;
  insert into public.workspace (owner_id, name, description) values (A, '본업', '보상시스템 기획') returning id into wsA;
  insert into public.workspace (owner_id, name) values (B, '본업') returning id into wsB;
  insert into public.area (owner_id, workspace_id, name) values (A, wsA, '보상 프로세스 운영') returning id into arA;
  insert into public.project (owner_id, workspace_id, area_id, name) values (A, wsA, arA, '자동차 사고접수 개편') returning id into pjA;
  insert into public.project (owner_id, workspace_id, name) values (A, wsA, '장기보험 지급심사 자동화') returning id into pjA2;
  insert into public.project (owner_id, workspace_id, name) values (B, wsB, 'B의 프로젝트') returning id into pjB;
  insert into public.resource (owner_id, workspace_id, kind, title, note) values (B, wsB, 'note', 'B 메모', '합성') returning id into rsB;
  insert into public.task (owner_id, project_id, title) values (A, pjA, '접수 화면 1차 피드백 반영') returning id into tkA;
  insert into public.source_record (owner_id, workspace_id, channel, storage_permission) values (A, wsA, 'recording', 'sample') returning id into srA;
  insert into public.transcript_version (owner_id, source_record_id, kind, content, retention_expires_at)
    values (A, srA, 'stt-original', '합성 전사: 개발 공수는 박지훈이 다시 산정', now() + interval '6 months') returning id into tvA;
  insert into public.transcript_version (owner_id, source_record_id, kind, content, retention_expires_at)
    values (A, srA, 'stt-original', null, now()) returning id into tvGone;   -- 본문 만료된 근거
  insert into public.analysis_run (owner_id, input_transcript_version_id, model_id, prompt_version, config_hash, status)
    values (A, tvA, 'p0-fake', 'p0', 'h', 'done') returning id into arunA;
  -- 제안 5종: 새 할 일 / 프로젝트 상태 / 오래된 기준 버전 / 근거 만료 / 기한 변경
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
    values (A, arunA, 'task', jsonb_build_object('project_id', pjA, 'title', '개발 공수 다시 산정', 'owner_kind', 'other', 'assignee', '박지훈', 'due_date', '2026-10-22'), '07:20 발언') returning id into pNewTask;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, target_id, base_version, after_json, rationale)
    values (A, arunA, 'project_state', pjA, 0, '{"summary":"개선안 확정 후 현업 리뷰","status":"active"}', '02:14 발언') returning id into pState;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, target_id, base_version, after_json, rationale)
    values (A, arunA, 'project_state', pjA, 0, '{"summary":"오래된 제안","status":"paused"}', '05:41 발언') returning id into pStale;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
    values (A, arunA, 'task', jsonb_build_object('project_id', pjA, 'title', '근거 없는 할 일'), '삭제된 구간') returning id into pNoEvidence;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, target_id, base_version, after_json, rationale)
    values (A, arunA, 'task', tkA, 0, '{"due_date":"2026-10-23"}', '05:41 발언') returning id into pDue;
  insert into public.evidence_link (owner_id, proposal_id, transcript_version_id, segment_ref)
    select A, p, tvA, '{"idx":3}' from unnest(array[pNewTask, pState, pStale, pDue]) p;
  insert into public.evidence_link (owner_id, proposal_id, transcript_version_id, segment_ref) values (A, pNoEvidence, tvGone, '{"idx":9}');

  -- ── 1. B로 로그인: A의 행이 하나도 보이지 않아야 한다(모든 테이블) ──
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  foreach t in array array['consent_grant','workspace','area','project','project_state_version','source_record','audio_asset',
    'transcript_version','meeting_version','mail_dispatch','analysis_run','source_project_link','resource','resource_link','task',
    'change_proposal','evidence_link','approval_event','task_revision','activity_log','processing_job','cost_ledger',
    'budget_reservation','deletion_request','tombstone'] loop
    execute format('select count(*) from public.%I where owner_id = $1', t) into n using A;
    reset role; insert into p0_result(name, pass, detail) values ('TC-01 B가 A 행 읽기 0건: '||t, n = 0, n||'건 보임'); set local role authenticated;
  end loop;
  select count(*) into n from public.app_user where id = A;
  reset role; insert into p0_result(name, pass, detail) values ('TC-01 B가 A 계정 읽기 0건: app_user', n = 0, n||'건'); set local role authenticated;
  select count(*) into n from public.settings where user_id = A;
  reset role; insert into p0_result(name, pass, detail) values ('TC-01 B가 A 설정 읽기 0건: settings', n = 0, n||'건'); set local role authenticated;

  -- 2. 쓰기 시도: 남의 행 수정·삭제는 0행
  update public.task set title = '탈취', version = version + 1 where id = tkA; get diagnostics n = row_count;
  reset role; insert into p0_result values (default, 'TC-01 B가 A 할 일 수정 → 0행', n = 0, n||'행'); set local role authenticated;
  delete from public.project where id = pjA; get diagnostics n = row_count;
  reset role; insert into p0_result values (default, 'TC-01 B가 A 프로젝트 삭제 → 0행', n = 0, n||'행'); set local role authenticated;

  -- 3. FK 참조 공격: 남의 ID를 넣어 연결 → 거부
  v_err := null; begin insert into public.task (project_id, title) values (pjA, 'A 프로젝트에 끼워 넣기'); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 B가 A 프로젝트에 할 일 추가 → 거부', v_err is not null, coalesce(v_err,'허용됨')); set local role authenticated;
  v_err := null; begin insert into public.resource_link (resource_id, target_type, target_id) values (rsB, 'project', pjA); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 B 자료를 A 프로젝트에 연결 → 거부', v_err is not null, coalesce(v_err,'허용됨')); set local role authenticated;
  v_err := null; begin insert into public.project (workspace_id, name) values (wsA, 'A 공간에 끼워 넣기'); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 B가 A 공간에 프로젝트 생성 → 거부', v_err is not null, coalesce(v_err,'허용됨')); set local role authenticated;
  v_err := null; begin insert into public.workspace (owner_id, name) values (A, '소유자 위조'); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 owner_id를 A로 위조해 생성 → 거부', v_err is not null, coalesce(v_err,'허용됨')); set local role authenticated;

  -- 4. Data API로 승인 건너뛰기 → 거부(운영 테이블 직접 쓰기 금지, M13)
  v_err := null; begin insert into public.approval_event (owner_id, target_type, proposal_id, result) values (B, 'proposal', pNewTask, 'approved'); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 approval_event 직접 쓰기 → 거부', v_err is not null, coalesce(v_err,'허용됨')); set local role authenticated;
  v_err := null; begin insert into public.project_state_version (owner_id, project_id, version, status, applied_by) values (B, pjB, 1, 'active', 'user'); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 project_state_version 직접 쓰기 → 거부', v_err is not null, coalesce(v_err,'허용됨')); set local role authenticated;
  v_err := null; begin update public.project set current_state_version = 9 where id = pjB; exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 current_state_version 직접 수정 → 거부', v_err is not null, coalesce(v_err,'허용됨')); set local role authenticated;
  v_err := null; begin update public.change_proposal set status = 'approved' where owner_id = B; exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 제안 상태 직접 수정 → 거부', v_err is not null, coalesce(v_err,'허용됨')); set local role authenticated;
  v_err := null; begin r := public.approve_proposal(pNewTask, 0, 'inbox', null); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 B가 A 제안 승인 RPC → 없음으로 거부', v_err like 'WB404%', coalesce(v_err, r::text)); set local role authenticated;

  -- 5. anon(로그인 안 함): 아무것도 못 읽고 승인 함수도 못 부른다
  reset role;
  perform set_config('request.jwt.claims', '{"role":"anon"}', true);
  set local role anon;
  v_err := null; begin select count(*) into n from public.task; exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 anon 테이블 읽기 → 거부', v_err is not null, coalesce(v_err, n||'건')); set local role anon;
  v_err := null; begin r := public.approve_proposal(pNewTask, 0, 'inbox', null); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-01 anon 승인 RPC → 거부', v_err is not null, coalesce(v_err, r::text));

  -- ── 6. A로 로그인: 승인·충돌(TC-08) ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;

  -- 6-1. 기록 상세에서 상태 변경 승인 → 거부(DEC-23)
  v_err := null; begin r := public.approve_proposal(pState, 0, 'record', null); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-08 기록 상세에서 상태 변경 승인 → 거부', v_err like 'WB403%', coalesce(v_err, r::text)); set local role authenticated;

  -- 6-2. 기록 상세에서 새 할 일 + 사용자 수정값 → 할 일 1건, 이력 actor=ai-approved
  r := public.approve_proposal(pNewTask, 0, 'record', '{"title":"개발 공수 다시 산정(수정)","due_date":"2026-10-21"}');
  reset role;
  insert into p0_result values (default, 'TC-08 새 할 일 승인(수정값 포함)', r->>'result' = 'approved', r::text);
  select count(*) into n from public.task where owner_id = A and title = '개발 공수 다시 산정(수정)' and due_date = '2026-10-21' and owner_kind = 'other' and assignee = '박지훈';
  insert into p0_result values (default, 'TC-08 수정값이 할 일에 반영', n = 1, n||'건');
  select count(*) into n from public.task_revision tr join public.task tk on tk.id = tr.task_id
    where tk.title = '개발 공수 다시 산정(수정)' and tr.actor = 'ai-approved' and tr.approval_event_id = (r->>'approval_event_id')::uuid;
  insert into p0_result values (default, 'TC-08 할 일 이력에 승인 기록 연결', n = 1, n||'건');
  set local role authenticated;

  -- 6-3. 같은 제안 다시 승인(두 번째 기기) → 효과 0, already-approved
  r := public.approve_proposal(pNewTask, 0, 'inbox', null);
  reset role;
  select count(*) into n from public.task where owner_id = A and title like '개발 공수 다시 산정%';
  insert into p0_result values (default, 'TC-08 같은 제안 재승인 → 할 일 1건 유지', r->>'result' = 'already-approved' and n = 1, r::text||' / 할 일 '||n||'건');
  select count(*) into n from public.approval_event where proposal_id = pNewTask;
  insert into p0_result values (default, 'TC-08 승인 기록 1건', n = 1, n||'건');
  set local role authenticated;

  -- 6-4. 프로젝트 상태 승인 → 버전 1. 같은 기준(0)의 다른 제안 → 충돌
  r := public.approve_proposal(pState, 0, 'inbox', null);
  reset role; insert into p0_result values (default, 'TC-08 상태 변경 승인 → 버전 1', r->>'result' = 'approved' and (r->>'applied_version')::int = 1, r::text); set local role authenticated;
  r := public.approve_proposal(pStale, 0, 'inbox', null);
  reset role;
  insert into p0_result values (default, 'TC-08 오래된 기준 버전 → 충돌', r->>'result' = 'conflict', r::text);
  select count(*) into n from public.approval_event where proposal_id = pStale;
  insert into p0_result values (default, 'TC-08 충돌이면 승인 기록 없음', n = 0, n||'건');
  set local role authenticated;

  -- 6-5. 사용자가 본 뒤 제안이 바뀜(revision) → stale-revision
  reset role; update public.change_proposal set revision = 1 where id = pDue; set local role authenticated;
  r := public.approve_proposal(pDue, 0, 'inbox', null);
  reset role; insert into p0_result values (default, 'TC-08 본 revision이 오래됨 → 다시 보기', r->>'result' = 'stale-revision', r::text); set local role authenticated;

  -- 6-6. 할 일을 사용자가 먼저 고침(version 1) → 기한 변경 제안(base 0) 충돌
  update public.task set title = '접수 화면 1차 피드백 반영(직접 수정)', version = version + 1 where id = tkA and version = 0;
  reset role;
  -- 승인 함수가 켠 'AI 제안 승인' 표시가 남아 있으면 이 수동 편집도 ai-approved로 잘못 기록된다(0400에서 수정)
  select count(*) into n from public.task_revision where task_id = tkA and version = 1 and actor = 'user' and approval_event_id is null;
  insert into p0_result values (default, 'TC-08 승인 뒤 같은 트랜잭션 수동 편집 → 이력 actor=user', n = 1, n||'건');
  set local role authenticated;
  r := public.approve_proposal(pDue, 1, 'inbox', null);
  reset role; insert into p0_result values (default, 'TC-08 다른 기기 직접 수정 후 기한 변경 → 충돌', r->>'result' = 'conflict', r::text); set local role authenticated;

  -- 6-7. 근거 전사 본문이 모두 만료 → 승인 차단·격리
  r := public.approve_proposal(pNoEvidence, 0, 'inbox', null);
  reset role;
  select count(*) into n from public.change_proposal where id = pNoEvidence and status = 'quarantined';
  insert into p0_result values (default, 'TC-08 근거 없음 → 승인 차단·격리', r->>'result' = 'evidence-missing' and n = 1, r::text);
  set local role authenticated;

  -- 6-8. 수동 편집: 버전을 안 올린 덮어쓰기 → 거부, 오래된 버전 조건 → 0행
  v_err := null; begin update public.task set title = '버전 안 올림' where id = tkA; exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-08 버전 안 올린 수정 → 거부', v_err like 'WB409%', coalesce(v_err,'허용됨')); set local role authenticated;
  update public.task set title = '오래된 화면에서 수정', version = version + 1 where id = tkA and version = 0; get diagnostics n = row_count;
  reset role; insert into p0_result values (default, 'TC-08 오래된 버전 조건 수정 → 0행(충돌 처리)', n = 0, n||'행'); set local role authenticated;

  -- 6-9. 공간이 다른 반복실행에 프로젝트 연결 → 거부(DEC-22)
  reset role;
  insert into public.workspace (owner_id, name) values (A, '부업') returning id into wsB;   -- A의 두 번째 공간
  set local role authenticated;
  v_err := null; begin insert into public.project (workspace_id, area_id, name) values (wsB, arA, '공간 섞기'); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-02 다른 공간 반복실행에 연결 → 거부', v_err like 'WB422%', coalesce(v_err,'허용됨'));

  -- ── 정리: 시험 계정 삭제(연쇄 삭제) ──
  delete from auth.users where id in (A, B);
end
$$;

select seq, case when pass then 'PASS' else 'FAIL' end as result, name, detail from p0_result order by seq;
