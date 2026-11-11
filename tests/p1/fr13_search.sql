-- P1 실측: FR-13 검색(DEC-98, 마이그레이션 0039 wb_search) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 끝에서 결과를 담은 예외를 일으켜 모든 변경(합성 계정 포함)을 되돌린다.
--   반영 전 시험은 0039 본문을 이 파일 앞에 붙여 한 번에 실행한다(같이 되돌려져 서버에 남지 않음).
-- DROP·DELETE를 쓰지 않는다(원격 확인 창 만료 회피). 사용자는 합성 시험 계정 A·B뿐이다(@example.test).
-- 결과: 'P1RESULT' 예외 메시지의 줄마다 PASS/FAIL.
do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000039a1';
  B uuid := '00000000-0000-4000-a000-0000000039b1';
  wsA uuid; wsB uuid; pA uuid; pArch uuid; aA uuid;
  tDone uuid; tArch uuid; tPct uuid; tUnder uuid; tOther uuid;
  rNew uuid; rAppr uuid; rTitle uuid; rOld uuid; rArch uuid; rFields uuid;
  tvNew uuid; tvAppr uuid; tvFields uuid; runNew uuid; propOpen uuid;
  rsNote uuid; rsLink uuid;
  v_err text; n int; n2 int; s text; f1 boolean; f2 boolean;
  res text[] := '{}';
begin
  -- ── 0. 시드(서비스 권한) ──
  insert into auth.users (id, aud, role, email, created_at, updated_at) values
    (A, 'authenticated', 'authenticated', 'p1-search-a@example.test', now(), now()),
    (B, 'authenticated', 'authenticated', 'p1-search-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-search-a@example.test'), (B, 'p1-search-b@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;
  select id into wsB from public.workspace where owner_id = B limit 1;
  if wsB is null then insert into public.workspace (owner_id, name) values (B, '본업') returning id into wsB; end if;

  -- A가 앱에서 만드는 것(소유자는 auth.uid 기본값)
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.project (workspace_id, name) values (wsA, '합성 검색 프로젝트') returning id into pA;
  insert into public.project (workspace_id, name) values (wsA, '보관 합성 프로젝트') returning id into pArch;
  insert into public.area (workspace_id, name) values (wsA, '합성 반복 점검') returning id into aA;
  insert into public.task (title, project_id) values ('청약 화면 시안 공유', pA);
  insert into public.task (title, project_id, status) values ('청약 일정 재확인', pA, 'done') returning id into tDone;
  insert into public.task (title, project_id) values ('청약 보관 할 일', pArch) returning id into tArch;
  insert into public.task (title, area_id) values ('청약 반복 점검', aA);
  insert into public.task (title) values ('진척 50% 보고') returning id into tPct;
  insert into public.task (title) values ('진척 500 보고');
  insert into public.task (title) values ('a_b 규칙 정리') returning id into tUnder;
  insert into public.task (title) values ('axb 규칙 정리');
  insert into public.task (title, owner_kind, assignee) values ('외부 회신 받기', 'other', '박지훈') returning id into tOther;
  for i in 1..31 loop
    insert into public.task (title) values ('한도시험 ' || i);
  end loop;
  insert into public.resource (workspace_id, kind, title, note) values (wsA, 'note', '약관 정리', '청약 약관 메모: 해지 조건 확인') returning id into rsNote;
  insert into public.resource (workspace_id, kind, title, url) values (wsA, 'link', '안내 링크', 'https://example.com/chungyak-guide') returning id into rsLink;
  insert into public.resource_link (resource_id, target_type, target_id) values (rsNote, 'project', pA);
  update public.resource set archived_at = now(), version = version + 1 where id = rsLink;
  reset role;
  update public.project set archived_at = now(), version = version + 1 where id = pArch;

  -- B의 같은 낱말 할 일(A에게 안 보여야 함)
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.task (title) values ('청약 남의 할 일');
  reset role;

  -- 기록(서비스 권한): 새 회의록 초안 / 승인 회의록 / 제목만 / 오래된 / 보관된
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification, occurred_at)
  values (A, wsA, 'recording', '주간 합성 회의', 'sample', 'work', '2026-11-10 10:00+09') returning id into rNew;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification, occurred_at)
  values (A, wsA, 'recording', '승인 합성 회의', 'sample', 'work', '2026-11-09 10:00+09') returning id into rAppr;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification)
  values (A, wsA, 'upload', '청약 킥오프 회의', 'sample', 'work') returning id into rTitle;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification, occurred_at)
  values (A, wsA, 'recording', '청약 오래된 회의', 'sample', 'work', '2026-09-01 10:00+09') returning id into rOld;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification, occurred_at)
  values (A, wsA, 'recording', '청약 보관된 회의', 'sample', 'work', '2026-11-08 10:00+09') returning id into rArch;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission, classification, occurred_at)
  values (A, wsA, 'call-share', '합성 통화 메모', 'sample', 'work', '2026-11-07 10:00+09') returning id into rFields;
  update public.source_record set archived_at = now() where id = rArch;

  insert into public.transcript_version (source_record_id, owner_id, kind, content, retention_expires_at)
  values (rNew, A, 'stt-original', '전사에만 있는 전사전용낱말', now() + interval '30 days') returning id into tvNew;
  insert into public.transcript_version (source_record_id, owner_id, kind, content, retention_expires_at)
  values (rAppr, A, 'stt-original', '합성 전사', now() + interval '30 days') returning id into tvAppr;
  insert into public.transcript_version (source_record_id, owner_id, kind, content, retention_expires_at)
  values (rFields, A, 'stt-original', '합성 통화 전사', now() + interval '30 days') returning id into tvFields;
  -- 회의록 문서를 아직 안 만든 기록: AI가 뽑은 결정·미결만 찾는다(이미 있는 할 일·제목 칸은 안 찾음)
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, retention_expires_at)
  values (A, tvFields, 'draft',
          '{"title":"필드제목낱말","decisions":[{"text":"결정전용낱말 다음 주 확정","segments":[1]}],"open_questions":[{"text":"미결전용낱말 아직 남음","segments":[2]}],"already_tracked":[{"title":"이미할일낱말"}]}',
          now() + interval '30 days');
  -- 옛 판(하루 전)에만 있는 낱말은 찾지 않는다. 새 판은 승인 전 초안
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, document, retention_expires_at, created_at)
  values (A, tvNew, 'draft', '{}', '{"title":"주간 합성 회의록","sections":[{"heading":"결정","lines":["옛날키워드 결정"]}]}', now() + interval '30 days', now() - interval '1 day');
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, document, retention_expires_at, created_at)
  values (A, tvNew, 'draft', '{"decisions":[{"text":"필드전용낱말"}]}',
          '{"title":"주간 합성 회의록","sections":[{"heading":"결정","lines":["청약 화면은 두 가지 안으로 간다"],"table":null}]}',
          now() + interval '30 days', now());
  insert into public.meeting_version (owner_id, transcript_version_id, state, fields, document, approved_at, retention_expires_at)
  values (A, tvAppr, 'approved', '{}', '{"title":"승인 회의록","sections":[{"heading":"결정","lines":["청약 범위 확정"]}]}', now(), now() + interval '30 days');
  insert into public.source_project_link (owner_id, source_record_id, transcript_version_id, item_ref, project_id, status, created_by)
  values (A, rAppr, tvAppr, '{"scope":"record"}', pA, 'confirmed', 'user');
  -- 원본 음성 보관 기한이 지난 기록
  insert into public.audio_asset (source_record_id, owner_id, storage_key, file_hash, bytes, mime, retention_expires_at)
  values (rTitle, A, A || '/' || rTitle || '/audio.m4a', 'p1-search-hash', 1000, 'audio/mp4', now() - interval '1 day');
  -- 검토 대기 후보 하나 + 이미 거절한 후보 하나
  insert into public.analysis_run (owner_id, input_transcript_version_id, model_id, prompt_version, config_hash, status)
  values (A, tvNew, 'synthetic', 'prompt-v5', 'synthetic:prompt-v5', 'done') returning id into runNew;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale)
  values (A, runNew, 'task', jsonb_build_object('title', '청약 후보 확인하기', 'owner_kind', 'unknown', 'project_id', pA), '근거') returning id into propOpen;
  insert into public.change_proposal (owner_id, analysis_run_id, target_type, after_json, rationale, status)
  values (A, runNew, 'task', '{"title":"청약 거절된 후보","owner_kind":"unknown"}', '근거', 'rejected');

  -- 아래 검색은 모두 A로 로그인한 앱처럼(RLS) 부른다
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);

  -- ── 1. 두 글자 미만은 빈 결과(공백만 빼면 한 글자) ──
  set local role authenticated;
  select count(*) into n from public.wb_search('청');
  select count(*) into n2 from public.wb_search('  청  ');
  reset role;
  res := res || format('%s 두 글자 미만 빈 결과 (%s / %s)', case when n = 0 and n2 = 0 then 'PASS' else 'FAIL' end, n, n2);

  -- ── 2. 내 것만: B의 할 일·보관한 기록·거절한 후보는 안 나온다 ──
  set local role authenticated;
  select count(*) filter (where kind = 'task'), count(*) filter (where title in ('청약 남의 할 일', '청약 보관된 회의', '청약 거절된 후보'))
    into n, n2 from public.wb_search('청약');
  reset role;
  res := res || format('%s 남의 것·보관 기록·거절 후보 빠짐, 내 할 일 4건 (%s건, 빠져야 할 것 %s)', case when n = 4 and n2 = 0 then 'PASS' else 'FAIL' end, n, n2);

  -- ── 3. 종류별: 기록 4(제목 2·회의록 글 2) · 후보 1 · 자료 1 ──
  set local role authenticated;
  select count(*) filter (where kind = 'record'), count(*) filter (where kind = 'proposal' and draft and record_id = rNew and record_title = '주간 합성 회의')
    into n, n2 from public.wb_search('청약');
  select count(*) into s from public.wb_search('청약') where kind = 'resource' and id = rsNote and snippet like '%청약 약관 메모%' and place_name = '합성 검색 프로젝트';
  reset role;
  res := res || format('%s 기록 4·대기 후보 1(초안)·자료 메모 조각과 곳 (%s / %s / %s)', case when n = 4 and n2 = 1 and s = '1' then 'PASS' else 'FAIL' end, n, n2, s);

  -- ── 4. 끝낸 할 일·보관한 곳의 할 일도 찾고 표시만 다르게 ──
  set local role authenticated;
  select done into f1 from public.wb_search('청약') where id = tDone;
  select archived into f2 from public.wb_search('청약') where id = tArch;
  reset role;
  res := res || format('%s 끝낸 할 일 done·보관한 곳 archived (%s / %s)', case when f1 and f2 then 'PASS' else 'FAIL' end, f1, f2);

  -- ── 5. %·_ 는 글자 그대로(LIKE 와일드카드가 아님) ──
  set local role authenticated;
  select count(*), max(id::text) into n, s from public.wb_search('0%');
  select count(*), max(id::text) into n2, v_err from public.wb_search('a_b');
  reset role;
  res := res || format('%s %%·_ 글자 그대로 (0%% → %s건, a_b → %s건)',
    case when n = 1 and s = tPct::text and n2 = 1 and v_err = tUnder::text then 'PASS' else 'FAIL' end, n, n2);

  -- ── 6. 담당으로 찾으면 조각 '담당 …' ──
  set local role authenticated;
  select snippet into s from public.wb_search('박지훈') where id = tOther;
  reset role;
  res := res || format('%s 담당으로 찾기 조각 (%s)', case when s = '담당 박지훈' then 'PASS' else 'FAIL' end, s);

  -- ── 7. 승인 전 회의록 글에서만 맞으면 초안 + 조각 ──
  set local role authenticated;
  select draft, snippet, record_id = rNew into f1, s, f2 from public.wb_search('두 가지 안') where id = rNew;
  reset role;
  res := res || format('%s 초안 회의록 글 → draft·조각·기록 ID (%s / %s / %s)', case when f1 and s like '%두 가지 안%' and f2 then 'PASS' else 'FAIL' end, f1, s, f2);

  -- ── 8. 승인한 회의록 글은 확정, 제목에서 맞으면 확정·조각 없음·원본 만료 표시 ──
  set local role authenticated;
  select draft into f1 from public.wb_search('범위 확정') where id = rAppr;
  select (not draft) and snippet is null and audio_expired into f2 from public.wb_search('킥오프') where id = rTitle;
  reset role;
  res := res || format('%s 승인 회의록 확정(draft=%s)·제목 맞음 확정+원본 만료(%s)', case when f1 = false and f2 then 'PASS' else 'FAIL' end, f1, f2);

  -- ── 9. 옛 회의록 판·jsonb 키 이름·전사·추출 칸(fields)은 찾지 않는다 ──
  set local role authenticated;
  select count(*) into n from public.wb_search('옛날키워드');
  select count(*) into n2 from public.wb_search('sections');
  select count(*) + n2 into n2 from public.wb_search('heading');
  select count(*) into s from public.wb_search('전사전용낱말');
  select count(*) || '/' || s into s from public.wb_search('필드전용낱말');
  reset role;
  res := res || format('%s 옛 판·키 이름·전사·추출 칸 안 찾음 (%s / %s / %s)', case when n = 0 and n2 = 0 and s = '0/0' then 'PASS' else 'FAIL' end, n, n2, s);

  -- ── 9-2. 회의록 문서가 없는 기록은 결정·미결 글을 찾고 초안·'결정 · '/'미결 · ' 조각, 이미 있는 할 일·제목 칸은 안 찾음 ──
  set local role authenticated;
  select string_agg(kind || '|' || draft || '|' || snippet, ',') into s from public.wb_search('결정전용낱말');
  select count(*) into n from public.wb_search('미결전용낱말') where id = rFields and draft and snippet = '미결 · 미결전용낱말 아직 남음';
  select count(*) into n2 from public.wb_search('이미할일낱말');
  select count(*) + n2 into n2 from public.wb_search('필드제목낱말');
  reset role;
  res := res || format('%s 문서 없는 기록의 결정·미결 찾기 (%s / 미결 %s / 안 찾을 칸 %s)',
    case when s = 'record|true|결정 · 결정전용낱말 다음 주 확정' and n = 1 and n2 = 0 then 'PASS' else 'FAIL' end, s, n, n2);

  -- ── 10. 기간: 9월 회의는 10월부터로 거르면 빠짐(회의 시각을 모르면 올린 시각) ──
  set local role authenticated;
  select count(*) filter (where id = rOld), count(*) filter (where id = rTitle) into n, n2 from public.wb_search('청약', p_since => '2026-10-01 00:00+09');
  reset role;
  res := res || format('%s 기간 거르기 (9월 회의 %s건, 시각 모르는 기록 %s건)', case when n = 0 and n2 = 1 then 'PASS' else 'FAIL' end, n, n2);

  -- ── 11. 프로젝트로 거르기: 그 프로젝트 할 일 2·연결 기록 1·추가할 곳 후보 1·연결 자료 1 ──
  set local role authenticated;
  select string_agg(kind || ':' || coalesce(title, ''), ',' order by kind, title) into s from public.wb_search('청약', p_project => pA);
  reset role;
  res := res || format('%s 프로젝트로 거르기 (%s)',
    case when s = 'proposal:청약 후보 확인하기,record:승인 합성 회의,resource:약관 정리,task:청약 일정 재확인,task:청약 화면 시안 공유' then 'PASS' else 'FAIL' end, s);

  -- ── 12. 반복실행으로 거르기: 할 일만(기록·후보는 프로젝트에만 이어짐) ──
  set local role authenticated;
  select string_agg(kind || ':' || title, ',') into s from public.wb_search('청약', p_area => aA);
  reset role;
  res := res || format('%s 반복실행으로 거르기 (%s)', case when s = 'task:청약 반복 점검' then 'PASS' else 'FAIL' end, s);

  -- ── 13. 종류 고르기·종류마다 최대 30건(최신순) ──
  set local role authenticated;
  select count(*) filter (where kind <> 'task'), count(*) into n, n2 from public.wb_search('청약', p_kinds => array['task']);
  select count(*) into s from public.wb_search('한도시험');
  reset role;
  res := res || format('%s 종류 고르기(할 일만 %s건, 다른 종류 %s)·30건 한도 (%s)', case when n = 0 and n2 = 4 and s = '30' then 'PASS' else 'FAIL' end, n2, n, s);

  -- ── 14. 보관한 자료는 표시만, 링크 주소 조각 ──
  set local role authenticated;
  select archived and snippet like '%chungyak%' into f1 from public.wb_search('chungyak') where id = rsLink;
  reset role;
  res := res || format('%s 보관 자료 archived·주소 조각 (%s)', case when f1 then 'PASS' else 'FAIL' end, f1);

  -- ── 15. 잘못된 요청은 빨리 거부(WB400 = 22023) ──
  v_err := null; begin set local role authenticated; perform * from public.wb_search(repeat('가', 101)); exception when others then v_err := sqlstate; end; reset role;
  s := v_err;
  v_err := null; begin set local role authenticated; perform * from public.wb_search('청약', pA, aA); exception when others then v_err := sqlstate; end; reset role;
  s := s || '/' || coalesce(v_err, '허용됨');
  v_err := null; begin set local role authenticated; perform * from public.wb_search('청약', p_kinds => array['transcript']); exception when others then v_err := sqlstate; end; reset role;
  s := s || '/' || coalesce(v_err, '허용됨');
  res := res || format('%s 101자·프로젝트+반복실행·모르는 종류 거부 (%s)', case when s = '22023/22023/22023' then 'PASS' else 'FAIL' end, s);

  -- ── 16. 로그인 안 하면 실행 거부 ──
  v_err := null; begin set local role anon; perform * from public.wb_search('청약'); exception when others then v_err := sqlstate; end; reset role;
  res := res || format('%s 로그인 안 하면 거부 (%s)', case when v_err = '42501' then 'PASS' else 'FAIL' end, coalesce(v_err, '허용됨'));

  -- ── 17. 조각: 앞뒤 30자 + 잘린 쪽 '…', 줄바꿈은 한 칸 ──
  s := public.wb_search_snippet(repeat('가', 50) || '키워드' || E'\n' || repeat('나', 50), '키워드');
  res := res || format('%s 조각 모양 (%s)',
    case when s = '…' || repeat('가', 30) || '키워드 ' || repeat('나', 29) || '…' then 'PASS' else 'FAIL' end, s);

  -- ── 18. 함수 성질: 부른 사람 권한(SECURITY INVOKER)·search_path 고정 ──
  res := res || format('%s SECURITY INVOKER·search_path 고정',
    case when (select not prosecdef and proconfig @> array['search_path=""'] from pg_proc where oid = 'public.wb_search(text, uuid, uuid, timestamptz, text[])'::regprocedure)
         then 'PASS' else 'FAIL' end);

  -- 결과를 예외로 돌려주며 모든 변경을 되돌린다(합성 계정도 남지 않음)
  raise exception 'P1RESULT%', E'\n' || array_to_string(res, E'\n');
end
$$;
