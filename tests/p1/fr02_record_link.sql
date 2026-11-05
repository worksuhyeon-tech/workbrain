-- P1 실측: FR-02 2단계 ① 기록↔프로젝트 연결 (마이그레이션 0032, DEC-69) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 시작·끝에 시험 데이터를 지워 다시 실행해도 같은 결과.
-- 사용자는 합성 시험 계정 A/B뿐이다(@example.test). 결과는 마지막 SELECT.
create temp table if not exists p1_result (seq serial, name text, pass boolean, detail text);
truncate p1_result;

do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000032a1';
  B uuid := '00000000-0000-4000-a000-0000000032b1';
  wsA uuid; wsA2 uuid; wsB uuid;
  p1 uuid; p2 uuid; pArch uuid; pOther uuid; pB uuid;
  rA uuid; rA2 uuid; rB uuid;
  n int; v text; v_err text;
begin
  -- ── 0. 정리 후 시드(서비스 권한) ──
  delete from auth.users where id in (A, B);
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  values (A, 'authenticated', 'authenticated', 'p1-link-a@example.test', now(), now()),
         (B, 'authenticated', 'authenticated', 'p1-link-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-link-a@example.test'), (B, 'p1-link-b@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;
  insert into public.workspace (owner_id, name) values (A, '부업(시험)') returning id into wsA2;
  select id into wsB from public.workspace where owner_id = B limit 1;
  if wsB is null then insert into public.workspace (owner_id, name) values (B, '본업') returning id into wsB; end if;

  insert into public.project (owner_id, workspace_id, name) values (A, wsA, '합성 프로젝트 1') returning id into p1;
  insert into public.project (owner_id, workspace_id, name) values (A, wsA, '합성 프로젝트 2') returning id into p2;
  insert into public.project (owner_id, workspace_id, name) values (A, wsA, '보관할 프로젝트') returning id into pArch;
  update public.project set archived_at = now(), version = version + 1 where id = pArch;
  insert into public.project (owner_id, workspace_id, name) values (A, wsA2, '다른 공간 프로젝트') returning id into pOther;
  insert into public.project (owner_id, workspace_id, name) values (B, wsB, 'B 프로젝트') returning id into pB;
  -- 전사 전 기록(녹음 화면에서 고른 프로젝트를 올리기 직후 연결하는 경우)
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission)
  values (A, wsA, 'recording', '합성 회의', 'sample') returning id into rA;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission)
  values (A, wsA, 'upload', '합성 통화', 'sample') returning id into rA2;
  insert into public.source_record (owner_id, workspace_id, channel, title, storage_permission)
  values (B, wsB, 'recording', 'B 회의', 'sample') returning id into rB;

  -- ── 1. A: 연결 ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  v := public.wb_link_record_project(rA, p1, true);
  reset role;
  select count(*) into n from public.source_project_link
   where source_record_id = rA and project_id = p1 and status = 'confirmed' and created_by = 'user'
     and transcript_version_id is null and item_ref->>'scope' = 'record' and owner_id = A;
  insert into p1_result values (default, 'DEC-69 전사 전 기록을 프로젝트에 연결', v = 'linked' and n = 1, v || ' · ' || n || '행');

  set local role authenticated;
  v := public.wb_link_record_project(rA, p1, true);
  reset role;
  select count(*) into n from public.source_project_link where source_record_id = rA and project_id = p1;
  insert into p1_result values (default, 'DEC-69 같은 곳에 다시 연결 → 그대로(중복 없음)', v = 'unchanged' and n = 1, v || ' · ' || n || '행');

  set local role authenticated;
  v := public.wb_link_record_project(rA, p2, true);
  reset role;
  select count(*) into n from public.source_project_link where source_record_id = rA and status = 'confirmed';
  insert into p1_result values (default, 'FR-06 한 기록을 여러 프로젝트에 연결', v = 'linked' and n = 2, v || ' · ' || n || '곳');

  -- ── 2. 해제·다시 연결(행은 남기고 상태만) ──
  set local role authenticated;
  v := public.wb_link_record_project(rA, p2, false);
  reset role;
  select count(*) into n from public.source_project_link where source_record_id = rA and project_id = p2 and status = 'rejected' and version = 1;
  insert into p1_result values (default, 'DEC-69 연결 해제(지우지 않고 rejected, 버전 1)', v = 'unlinked' and n = 1, v || ' · ' || n || '행');
  set local role authenticated;
  v := public.wb_link_record_project(rA, p2, true);
  reset role;
  select count(*) into n from public.source_project_link where source_record_id = rA and project_id = p2 and status = 'confirmed' and version = 2;
  insert into p1_result values (default, 'DEC-69 다시 연결(같은 행, 버전 2)', v = 'linked' and n = 1, v || ' · ' || n || '행');
  set local role authenticated;
  v := public.wb_link_record_project(rA2, p1, false);
  reset role;
  insert into p1_result values (default, 'DEC-69 연결 없는 곳 해제 → 그대로', v = 'unchanged', v);

  -- ── 3. 막아야 하는 것 ──
  set local role authenticated;
  v_err := null; begin perform public.wb_link_record_project(rA2, pArch, true); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-02 아카이브된 프로젝트에 기록 연결 → 거부', v_err like 'WB409%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin perform public.wb_link_record_project(rA2, pOther, true); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'DEC-22 다른 공간 프로젝트에 기록 연결 → 거부', v_err like 'WB422%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin
    insert into public.source_project_link (owner_id, source_record_id, item_ref, project_id, status, created_by)
    values (A, rA2, '{"scope":"record"}', p1, 'confirmed', 'user');
  exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'NFR-01 앱이 연결 표에 직접 쓰기 → 거부(함수로만)', v_err is not null, coalesce(v_err, '허용됨'));
  v_err := null; begin
    insert into public.source_project_link (owner_id, source_record_id, item_ref, project_id, status, created_by)
    values (A, rA2, '{"segment":3}', p1, 'proposed', 'ai');
  exception when others then v_err := sqlerrm; end;
  insert into p1_result values (default, 'M16 항목 단위 연결은 전사 버전 없으면 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  -- ── 4. 옮기기(끌어서 옮기기용, 한 번에) ──
  set local role authenticated;
  v := public.wb_link_record_project(rA2, p1, true);
  v := public.wb_move_record_project(rA2, p1, p2);
  reset role;
  select count(*) into n from public.source_project_link
   where source_record_id = rA2 and ((project_id = p1 and status = 'rejected') or (project_id = p2 and status = 'confirmed'));
  insert into p1_result values (default, 'DEC-24 기록을 다른 프로젝트로 옮기기', v = 'moved' and n = 2, v || ' · ' || n || '/2');
  set local role authenticated;
  v_err := null; begin perform public.wb_move_record_project(rA2, p2, pArch); exception when others then v_err := sqlerrm; end;
  reset role;
  select count(*) into n from public.source_project_link where source_record_id = rA2 and project_id = p2 and status = 'confirmed';
  insert into p1_result values (default, 'DEC-24 아카이브로 옮기기 실패 → 옛 연결 그대로', v_err like 'WB409%' and n = 1, coalesce(v_err, '허용됨') || ' · ' || n);

  -- ── 5. 이력 ──
  select count(*) into n from public.activity_log where owner_id = A and entity_type = 'source_record' and entity_id in (rA, rA2);
  -- 연결 rA·p1, rA·p2, 해제 rA·p2, 다시 rA·p2, 연결 rA2·p1, 옮기기(연결 p2 + 해제 p1) = 7
  insert into p1_result values (default, 'DEC-69 연결·해제·옮기기 이력', n = 7, n || '건');

  -- ── 6. 다른 사용자 ──
  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  v_err := null; begin perform public.wb_link_record_project(rA, pB, true); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'NFR-01 B가 A의 기록을 연결 → 없음과 같게 거부', v_err like 'WB404%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin perform public.wb_link_record_project(rB, p1, true); exception when others then v_err := sqlerrm; end;
  select count(*) into n from public.source_project_link where source_record_id = rA;
  reset role;
  insert into p1_result values (default, 'NFR-01 B가 A의 프로젝트에 연결 → 거부', v_err like 'WB404%', coalesce(v_err, '허용됨'));
  insert into p1_result values (default, 'NFR-01 B는 A의 연결을 못 봄', n = 0, n || '건');

  -- 계정을 지우면 기록·연결·이력이 연쇄로 지워진다. 연결 표가 기록 삭제를 막으면 안 된다
  delete from auth.users where id in (A, B);
  select count(*) into n from public.app_user where id in (A, B);
  insert into p1_result values (default, '계정 삭제(연쇄)가 연결 표에 막히지 않음', n = 0, n || '건 남음');
end
$$;
select seq, case when pass then 'PASS' else 'FAIL' end as result, name, detail from p1_result order by seq;
