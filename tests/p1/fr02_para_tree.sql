-- P1 실측: FR-02 1단계 PARA 트리 (마이그레이션 0030·0031, DEC-22·24·65·66, TC-02) — DB 계층
-- 실행: Supabase SQL(서비스 권한, postgres)에서 통째로 실행. 시작·끝에 시험 데이터를 지워 다시 실행해도 같은 결과.
-- 사용자는 합성 시험 계정 A/B뿐이다(@example.test). 결과는 마지막 SELECT.
create temp table if not exists p1_result (seq serial, name text, pass boolean, detail text);
truncate p1_result;

do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000030a1';
  B uuid := '00000000-0000-4000-a000-0000000030b1';
  wsA uuid; wsA2 uuid; wsB uuid;
  pA uuid; pB uuid; aA uuid; aA2 uuid; rL uuid; rN uuid; rB uuid; tA uuid; tA2 uuid;
  n int; v_err text; v_ts timestamptz; v_snap jsonb;
begin
  -- ── 0. 정리 후 시드(서비스 권한) ──
  delete from auth.users where id in (A, B);
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  values (A, 'authenticated', 'authenticated', 'p1-para-a@example.test', now(), now()),
         (B, 'authenticated', 'authenticated', 'p1-para-b@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p1-para-a@example.test'), (B, 'p1-para-b@example.test') on conflict (id) do nothing;
  select id into wsA from public.workspace where owner_id = A limit 1;
  if wsA is null then insert into public.workspace (owner_id, name) values (A, '본업') returning id into wsA; end if;
  insert into public.workspace (owner_id, name) values (A, '부업(시험)') returning id into wsA2;
  select id into wsB from public.workspace where owner_id = B limit 1;
  if wsB is null then insert into public.workspace (owner_id, name) values (B, '본업') returning id into wsB; end if;

  -- ── 1. A: 만들기 ──
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.project (workspace_id, name) values (wsA, '합성 프로젝트') returning id into pA;
  insert into public.area (workspace_id, name) values (wsA, '합성 반복실행') returning id into aA;
  insert into public.area (workspace_id, name) values (wsA, '보관할 반복실행') returning id into aA2;
  insert into public.resource (workspace_id, kind, title, url) values (wsA, 'link', '합성 링크', 'https://example.com/terms') returning id into rL;
  insert into public.resource (workspace_id, kind, title, note) values (wsA, 'note', '합성 메모', '용어 정리') returning id into rN;
  insert into public.task (title) values ('합성 할 일 1') returning id into tA;
  insert into public.task (title) values ('합성 할 일 2') returning id into tA2;
  reset role;

  select count(*) into n from public.project where id = pA and owner_id = A and version = 0 and archived_at is null;
  insert into p1_result values (default, 'FR-02 프로젝트 만들기(소유자 자동)', n = 1, n || '/1');
  select count(*) into n from public.area where id in (aA, aA2) and owner_id = A;
  insert into p1_result values (default, 'FR-02 반복실행 만들기', n = 2, n || '/2');
  select count(*) into n from public.resource where id in (rL, rN) and owner_id = A;
  insert into p1_result values (default, 'FR-02 자료(링크·메모) 만들기', n = 2, n || '/2');

  -- ── 2. 입력값 검사 ──
  set local role authenticated;
  v_err := null; begin insert into public.resource (workspace_id, kind, title, url) values (wsA, 'link', '위험 링크', 'javascript:alert(1)'); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'M15 http(s)가 아닌 링크 → 거부', v_err is not null, coalesce(v_err, '허용됨'));
  -- 목업 흐름(DEC-66): 이름·종류만으로 먼저 만들고 내용은 '수정'에서 채운다
  set local role authenticated;
  v_err := null; begin insert into public.resource (workspace_id, kind, title) values (wsA, 'link', '주소는 나중에'); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'DEC-66 이름만 있는 링크 자료 먼저 만들기 허용', v_err is null, coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin insert into public.resource (workspace_id, kind, title) values (wsA, 'record-ref', '기록 없는 참조'); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'M15 기록 없는 기록 참조 자료 → 거부', v_err is not null, coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin insert into public.project (workspace_id, name) values (wsA, '   '); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-02 빈 프로젝트 이름 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  -- ── 3. 버전(두 기기 동시 수정, M18) ──
  set local role authenticated;
  update public.project set name = '합성 프로젝트(고침)', purpose = '시험 목적', version = version + 1 where id = pA and version = 0;
  get diagnostics n = row_count;
  reset role;
  insert into p1_result values (default, 'M18 프로젝트 이름·목적 고치기(버전 0→1)', n = 1, n || '행');
  set local role authenticated;
  update public.project set name = '늦게 도착한 고침', version = 1 where id = pA and version = 0;
  get diagnostics n = row_count;
  reset role;
  insert into p1_result values (default, 'M18 같은 기준 버전으로 다시 고치기 → 0행(충돌 인지)', n = 0, n || '행');
  set local role authenticated;
  v_err := null; begin update public.resource set title = '버전 안 올림' where id = rL; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'M18 자료도 버전을 안 올리면 → 거부', v_err like 'WB409%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  update public.resource set title = '합성 링크(고침)', version = version + 1 where id = rL and version = 0;
  get diagnostics n = row_count;
  reset role;
  insert into p1_result values (default, 'M18 자료 고치기(버전 0→1)', n = 1, n || '행');

  -- ── 4. 할 일 소속 옮기기 ──
  set local role authenticated;
  update public.task set project_id = pA, version = version + 1 where id = tA;
  reset role;
  select snapshot into v_snap from public.task_revision where task_id = tA order by version desc limit 1;
  insert into p1_result values (default, 'FR-02 할 일 → 프로젝트로 옮기기, 이력에 소속 남음', v_snap->>'project_id' = pA::text, coalesce(v_snap->>'project_id', 'null'));
  set local role authenticated;
  v_err := null; begin update public.task set area_id = aA, version = version + 1 where id = tA; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-02 할 일이 프로젝트·반복실행 두 곳에 속하기 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  -- ── 5. 자료 연결 ──
  set local role authenticated;
  insert into public.resource_link (resource_id, target_type, target_id) values (rL, 'project', pA);
  insert into public.resource_link (resource_id, target_type, target_id) values (rN, 'area', aA);
  v_err := null; begin insert into public.resource_link (resource_id, target_type, target_id) values (rL, 'project', pA); exception when others then v_err := sqlerrm; end;
  reset role;
  select count(*) into n from public.resource_link where owner_id = A;
  insert into p1_result values (default, 'FR-02 자료 연결 2건(프로젝트·반복실행)', n = 2, n || '건');
  insert into p1_result values (default, 'DEC-24 이미 연결된 곳에 다시 연결 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  -- ── 6. 아카이브 = 읽기 전용 ──
  set local role authenticated;
  update public.project set archived_at = '2000-01-01', version = version + 1 where id = pA;
  reset role;
  select archived_at into v_ts from public.project where id = pA;
  insert into p1_result values (default, 'FR-02 프로젝트 아카이브(보관 시각은 서버 시각)', v_ts > now() - interval '1 minute', coalesce(v_ts::text, 'null'));
  set local role authenticated;
  v_err := null; begin update public.project set name = '보관 중 고침', version = version + 1 where id = pA; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-02 아카이브된 프로젝트 이름 고치기 → 거부', v_err like 'WB409%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin update public.task set project_id = pA, version = version + 1 where id = tA2; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-02 아카이브된 프로젝트로 할 일 옮기기 → 거부', v_err like 'WB409%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin insert into public.resource_link (resource_id, target_type, target_id) values (rN, 'project', pA); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-02 아카이브된 프로젝트에 자료 연결 → 거부', v_err like 'WB409%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  update public.task set title = '합성 할 일 1(보관 중 제목 고침)', version = version + 1 where id = tA;
  get diagnostics n = row_count;
  reset role;
  insert into p1_result values (default, 'FR-02 이미 들어 있던 할 일은 소속 그대로 고칠 수 있음', n = 1, n || '행');

  set local role authenticated;
  update public.project set archived_at = null, version = version + 1 where id = pA;
  update public.project set name = '복원 후 고침', version = version + 1 where id = pA;
  get diagnostics n = row_count;
  reset role;
  insert into p1_result values (default, 'FR-02 복원 후 이름 고치기 가능', n = 1, n || '행');
  select count(*) into n from public.activity_log where entity_type = 'project' and entity_id = pA and action in ('archived', 'restored') and actor = 'user';
  insert into p1_result values (default, 'FR-02 보관·복원 이력 2건(ID 유지)', n = 2, n || '건');

  -- 반복실행 보관: 이름 고치기·새 프로젝트 연결 거부
  set local role authenticated;
  update public.area set description = '매월 말 지급 현황 집계·보고' where id = aA2;
  get diagnostics n = row_count;
  reset role;
  insert into p1_result values (default, 'DEC-66 반복실행 설명 적기', n = 1, n || '행');
  set local role authenticated;
  update public.area set archived_at = now() where id = aA2;
  v_err := null; begin update public.area set name = '보관 중 고침' where id = aA2; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-02 아카이브된 반복실행 이름 고치기 → 거부', v_err like 'WB409%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin update public.area set description = '보관 중 설명 고침' where id = aA2; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'DEC-66 아카이브된 반복실행 설명 고치기 → 거부', v_err like 'WB409%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin update public.project set area_id = aA2, version = version + 1 where id = pA; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-02 프로젝트를 아카이브된 반복실행에 연결 → 거부', v_err like 'WB409%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  update public.project set area_id = aA, version = version + 1 where id = pA;
  get diagnostics n = row_count;
  reset role;
  insert into p1_result values (default, 'FR-02 프로젝트를 반복실행에 연결', n = 1, n || '행');

  -- 자료 보관 뒤 고치기 거부, 연결 해제 이력
  set local role authenticated;
  update public.resource set archived_at = now(), version = version + 1 where id = rL;
  v_err := null; begin update public.resource set title = '보관 중 고침', version = version + 1 where id = rL; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'FR-02 아카이브된 자료 고치기 → 거부', v_err like 'WB409%', coalesce(v_err, '허용됨'));
  set local role authenticated;
  delete from public.resource_link where resource_id = rN and target_type = 'area' and target_id = aA;
  get diagnostics n = row_count;
  reset role;
  insert into p1_result values (default, 'FR-02 자료 연결 해제', n = 1, n || '행');
  select count(*) into n from public.activity_log where entity_type = 'resource' and entity_id in (rL, rN)
    and (action like 'linked:%' or action like 'unlinked:%' or action = 'archived');
  insert into p1_result values (default, 'FR-02 자료 연결 2·해제 1·보관 1 이력', n = 4, n || '건');

  -- ── 7. 권한·공간 격리 ──
  set local role authenticated;
  v_err := null; begin update public.area set workspace_id = wsA2 where id = aA; exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'NFR-01 반복실행의 공간·소유자 열은 앱이 못 고침', v_err is not null, coalesce(v_err, '허용됨'));
  set local role authenticated;
  v_err := null; begin insert into public.project (workspace_id, area_id, name) values (wsA2, aA, '다른 공간 프로젝트'); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'DEC-22 다른 공간의 반복실행에 연결 → 거부', v_err like 'WB422%', coalesce(v_err, '허용됨'));

  perform set_config('request.jwt.claims', json_build_object('sub', B, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.project where id = pA;
  insert into public.resource (workspace_id, kind, title, note) values (wsB, 'note', 'B 메모', 'B') returning id into rB;
  v_err := null; begin insert into public.resource_link (resource_id, target_type, target_id) values (rB, 'project', pA); exception when others then v_err := sqlerrm; end;
  reset role;
  insert into p1_result values (default, 'NFR-01 B는 A의 프로젝트를 못 봄', n = 0, n || '건');
  insert into p1_result values (default, 'NFR-01 B 자료를 A 프로젝트에 연결 → 거부', v_err is not null, coalesce(v_err, '허용됨'));
  set local role authenticated;
  update public.task set project_id = null, version = version + 1 where id = tA;
  get diagnostics n = row_count;
  reset role;
  insert into p1_result values (default, 'NFR-01 B는 A의 할 일 소속을 못 바꿈(0행)', n = 0, n || '행');

  -- 계정을 지우면 자료 연결도 연쇄로 지워진다. 이때 이력 트리거가 지운 주인 앞으로 쓰다 막히면 안 된다
  delete from auth.users where id in (A, B);
  select count(*) into n from public.app_user where id in (A, B);
  insert into p1_result values (default, '계정 삭제(연쇄)가 이력 기록에 막히지 않음', n = 0, n || '건 남음');
end
$$;
select seq, case when pass then 'PASS' else 'FAIL' end as result, name, detail from p1_result order by seq;
