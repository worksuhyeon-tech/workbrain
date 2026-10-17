-- P0 실측: TC-12 백업·복원 — DB 계층 (내보내기·삭제 원장·임시 스키마 복원)
-- 서비스 권한(postgres)으로 통째로 실행. 합성 계정만 쓰고 끝에 지운다. 결과는 마지막 SELECT.
-- 시나리오: 백업1 → t2 완전 삭제(원장 기록) + t3 삭제 요청(7일 복구 대기) → 백업2·원장 내보내기
--          → 백업1을 복원해도 t2와 t2의 이력이 되살아나지 않는지, 백업2에 t3가 빠졌는지 확인.

create temp table if not exists p0_result (seq serial, name text, pass boolean, detail text);
truncate p0_result;

do $$
declare
  A uuid := '00000000-0000-4000-a000-0000000000d1';
  ws uuid; pj uuid; t1 uuid; t2 uuid; t3 uuid; rs uuid;
  b1 jsonb; b2 jsonb; tomb jsonb; stale jsonb; r jsonb; n int; v_err text;
begin
  delete from auth.users where id = A;
  insert into auth.users (id, aud, role, email, created_at, updated_at) values (A, 'authenticated', 'authenticated', 'p0-backup@example.test', now(), now());
  insert into public.app_user (id, email) values (A, 'p0-backup@example.test') on conflict (id) do nothing;
  insert into public.settings (user_id) values (A) on conflict (user_id) do nothing;
  insert into public.workspace (owner_id, name) values (A, '본업') returning id into ws;
  insert into public.project (owner_id, workspace_id, name) values (A, ws, '백업 시험 프로젝트') returning id into pj;
  insert into public.task (owner_id, project_id, title) values (A, pj, '남을 할 일') returning id into t1;
  insert into public.task (owner_id, project_id, title) values (A, pj, '완전히 지울 할 일') returning id into t2;
  insert into public.task (owner_id, project_id, title) values (A, pj, '복구 대기 할 일') returning id into t3;
  insert into public.resource (owner_id, workspace_id, kind, title, note) values (A, ws, 'note', '연결 자료', '합성') returning id into rs;
  insert into public.resource_link (owner_id, resource_id, target_type, target_id) values (A, rs, 'project', pj);

  -- 한 트랜잭션 안에서는 now()가 같으므로, 백업보다 먼저 뽑은(오래된) 원장은 시각을 하루 앞당겨 흉내 낸다
  stale := jsonb_set(public.wb_tombstone_export(), '{exported_at}', to_jsonb(now() - interval '1 day'));
  b1 := public.wb_backup_export();                -- 1일차 백업

  -- 사용자가 t2를 완전히 삭제(7일 지나 영구 삭제됨): 행 삭제 + 원장 기록
  delete from public.task where id = t2;
  insert into public.tombstone (owner_id, opaque_target_id, scope) values (A, t2::text, 'with-derived');
  -- t3는 삭제 요청만 하고 7일 복구 대기
  insert into public.deletion_request (owner_id, scope, target_ref, recover_until, impact, state)
    values (A, 'with-derived', jsonb_build_object('task', t3), now() + interval '7 days', jsonb_build_object('ids', jsonb_build_object('task', jsonb_build_array(t3))), 'soft-blocked');

  b2 := public.wb_backup_export();                -- 2일차 백업
  tomb := public.wb_tombstone_export();

  -- 1. 백업2에는 복구 대기 중인 t3가 없다
  insert into p0_result values (default, 'TC-12 복구 대기 대상은 백업에서 제외',
    not exists (select 1 from jsonb_array_elements(b2->'tables'->'task') x where x->>'id' = t3::text) and (b2->>'skipped_soft_blocked')::int = 1,
    'skipped=' || (b2->>'skipped_soft_blocked'));

  -- 2. 오래된 백업1 복원 + 최신 원장 → t2와 t2의 이력이 되살아나지 않는다
  r := public.wb_backup_restore(b1, tomb);
  select count(*) into n from wb_restore.task where id = t2;
  insert into p0_result values (default, 'TC-12 영구 삭제한 할 일은 복원되지 않음', n = 0, n || '건 / ' || (r->>'tombstoned_removed') || '건 원장 적용');
  select count(*) into n from wb_restore.task_revision where task_id = t2;
  insert into p0_result values (default, 'TC-12 지운 할 일의 이력(제목 스냅샷)도 남지 않음', n = 0, n || '건 / 부모 없는 행 ' || (r->>'orphans_removed') || '건 정리');
  select count(*) into n from wb_restore.task where id in (t1, t3);
  insert into p0_result values (default, 'TC-12 지우지 않은 할 일은 복원', n = 2, n || '건');
  select count(*) into n from wb_restore.resource_link where target_id = pj;
  insert into p0_result values (default, 'TC-12 연결(자료↔프로젝트) 복원', n = 1, n || '건');
  select count(*) into n from public.task where owner_id = A;
  insert into p0_result values (default, 'TC-12 복원이 운영 테이블을 바꾸지 않음', n = 2, '운영 할 일 ' || n || '건(t1, t3)');

  -- 3. 백업2 복원 → t3도 없다
  r := public.wb_backup_restore(b2, tomb);
  select count(*) into n from wb_restore.task where owner_id = A;
  insert into p0_result values (default, 'TC-12 최신 백업 복원 = 남은 할 일만', n = 1, n || '건');

  -- 4. 원장이 없거나 백업보다 오래되면 복원 거부
  v_err := null; begin r := public.wb_backup_restore(b2, null); exception when others then v_err := sqlerrm; end;
  insert into p0_result values (default, 'TC-12 삭제 원장 없이 복원 → 거부', v_err like 'WB400%', coalesce(v_err, '허용됨'));
  v_err := null; begin r := public.wb_backup_restore(b1, stale); exception when others then v_err := sqlerrm; end;
  insert into p0_result values (default, 'TC-12 백업보다 오래된 원장 → 거부', v_err like 'WB400%', coalesce(v_err, '허용됨'));

  -- 5. 로그인 사용자·anon은 내보내기·복원 불가
  perform set_config('request.jwt.claims', json_build_object('sub', A, 'role', 'authenticated')::text, true);
  set local role authenticated;
  v_err := null; begin r := public.wb_backup_export(); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-12 로그인 사용자 내보내기 → 거부', v_err is not null, coalesce(v_err, '허용됨'));
  set local role anon;
  v_err := null; begin r := public.wb_backup_restore(b1, tomb); exception when others then v_err := sqlerrm; end;
  reset role; insert into p0_result values (default, 'TC-12 anon 복원 → 거부', v_err is not null, coalesce(v_err, '허용됨'));

  -- 정리
  drop schema if exists wb_restore cascade;
  delete from auth.users where id = A;
end
$$;

select seq, case when pass then 'PASS' else 'FAIL' end as result, name, detail from p0_result order by seq;
