-- 백업 내보내기·복원 (Design §4.7 C9, TC-12, DEC-29)
-- Supabase Free는 자동 백업이 없어 매일 1회 이 함수로 내보내 GCS에 암호화 보관한다.
-- 원칙: (1) 7일 복구 대기(soft-blocked) 중인 삭제 대상은 백업에 넣지 않는다.
--       (2) 복원은 임시 스키마(wb_restore)에만 하고, 최신 삭제 원장(tombstone)을 먼저 적용해 지운 자료가 되살아나지 않게 한다.
--       (3) 운영 스키마로 옮기는 마지막 단계는 사람이 결과를 확인한 뒤 따로 한다.
-- 세 함수 모두 service_role(서버 작업)만 실행할 수 있다. 로그인 사용자·anon은 실행 불가.
-- 범위: public 업무 테이블. auth 사용자는 Supabase가 관리하므로 포함하지 않는다(복원 시 같은 Google 계정으로 다시 로그인).

-- 생성 순서(부모 → 자식). 복원도 이 순서로 넣는다
CREATE OR REPLACE FUNCTION public.wb_backup_tables()
RETURNS text[]
LANGUAGE sql
IMMUTABLE
SET search_path = ''
AS $$
  SELECT ARRAY['app_user','consent_grant','settings','workspace','area','project','project_state_version',
    'source_record','audio_asset','transcript_version','meeting_version','mail_dispatch','analysis_run',
    'source_project_link','resource','resource_link','task','change_proposal','evidence_link',
    'approval_event','task_revision','activity_log','processing_job','cost_ledger','budget_reservation',
    'deletion_request','tombstone'];
$$;

CREATE OR REPLACE FUNCTION public.wb_backup_export()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_blocked jsonb;
  v_tables jsonb := '{}'::jsonb;
  v_rows jsonb;
  v_skipped int := 0;
  v_total int;
  t text;
BEGIN
  -- 복구 대기 중인 삭제 대상: deletion_request.impact = {"ids": {"테이블": ["uuid", ...]}}
  SELECT coalesce(jsonb_object_agg(tbl, ids), '{}'::jsonb) INTO v_blocked
  FROM (
    SELECT x.tbl, jsonb_agg(DISTINCT e.id) AS ids
    FROM public.deletion_request d
    CROSS JOIN LATERAL jsonb_each(coalesce(d.impact->'ids', '{}'::jsonb)) AS x(tbl, arr)
    CROSS JOIN LATERAL jsonb_array_elements_text(x.arr) AS e(id)
    WHERE d.state = 'soft-blocked'
    GROUP BY x.tbl
  ) s;

  FOREACH t IN ARRAY public.wb_backup_tables() LOOP
    IF t IN ('settings', 'resource_link') THEN
      -- id 열이 없는 테이블은 소유자 단위로만 다룬다(삭제 대상 제외 없음)
      EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(x)), ''[]''::jsonb) FROM public.%I x', t) INTO v_rows;
    ELSE
      EXECUTE format('SELECT count(*) FROM public.%I', t) INTO v_total;
      EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(x)), ''[]''::jsonb) FROM public.%I x WHERE NOT (coalesce($1->%L, ''[]''::jsonb) ? x.id::text)', t, t)
        INTO v_rows USING v_blocked;
      v_skipped := v_skipped + v_total - jsonb_array_length(v_rows);
    END IF;
    v_tables := v_tables || jsonb_build_object(t, v_rows);
  END LOOP;

  RETURN jsonb_build_object('format', 'workbrain-backup-v1', 'exported_at', now(),
                            'skipped_soft_blocked', v_skipped, 'tables', v_tables);
END;
$$;

-- 삭제 원장만 따로(백업보다 오래, 30일 이상 보관)
CREATE OR REPLACE FUNCTION public.wb_tombstone_export()
RETURNS jsonb
LANGUAGE sql
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT jsonb_build_object('format', 'workbrain-tombstones-v1', 'exported_at', now(),
    'tombstones', coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.at), '[]'::jsonb))
  FROM public.tombstone t;
$$;

-- 임시 스키마에 복원하고 삭제 원장을 먼저 적용한다. 운영 테이블은 건드리지 않는다
CREATE OR REPLACE FUNCTION public.wb_backup_restore(p_backup jsonb, p_tombstones jsonb)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  t text;
  v_ids text[];
  v_removed int := 0;
  v_n int;
  v_counts jsonb := '{}'::jsonb;
BEGIN
  IF p_backup->>'format' IS DISTINCT FROM 'workbrain-backup-v1' THEN
    RAISE EXCEPTION 'WB400: 백업 형식이 아닙니다' USING ERRCODE = '22023';
  END IF;
  -- 최신 원장을 찾지 못하면 복원 데이터에 접근을 열지 않는다(Design §4.7)
  IF p_tombstones IS NULL OR p_tombstones->>'format' IS DISTINCT FROM 'workbrain-tombstones-v1' THEN
    RAISE EXCEPTION 'WB400: 최신 삭제 원장이 필요합니다' USING ERRCODE = '22023';
  END IF;
  IF (p_tombstones->>'exported_at')::timestamptz < (p_backup->>'exported_at')::timestamptz THEN
    RAISE EXCEPTION 'WB400: 삭제 원장이 백업보다 오래됐습니다' USING ERRCODE = '22023';
  END IF;

  DROP SCHEMA IF EXISTS wb_restore CASCADE;
  CREATE SCHEMA wb_restore;
  REVOKE ALL ON SCHEMA wb_restore FROM PUBLIC, anon, authenticated;

  SELECT coalesce(array_agg(x->>'opaque_target_id'), '{}') INTO v_ids
  FROM jsonb_array_elements(p_tombstones->'tombstones') x;

  FOREACH t IN ARRAY public.wb_backup_tables() LOOP
    -- 제약 없이 구조만 복사해 넣고, 확인 뒤 운영으로 옮길 때 제약을 다시 검사받는다
    EXECUTE format('CREATE TABLE wb_restore.%I (LIKE public.%I INCLUDING DEFAULTS)', t, t);
    EXECUTE format('INSERT INTO wb_restore.%I SELECT * FROM jsonb_populate_recordset(NULL::public.%I, $1)', t, t)
      USING coalesce(p_backup->'tables'->t, '[]'::jsonb);
    IF t NOT IN ('settings', 'resource_link', 'tombstone') THEN
      EXECUTE format('DELETE FROM wb_restore.%I WHERE id::text = ANY($1)', t) USING v_ids;
      GET DIAGNOSTICS v_n = ROW_COUNT;
      v_removed := v_removed + v_n;
    END IF;
    EXECUTE format('SELECT count(*) FROM wb_restore.%I', t) INTO v_n;
    v_counts := v_counts || jsonb_build_object(t, v_n);
  END LOOP;

  RETURN jsonb_build_object('schema', 'wb_restore', 'tombstoned_removed', v_removed, 'counts', v_counts);
END;
$$;

REVOKE ALL ON FUNCTION public.wb_backup_tables() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_backup_export() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_tombstone_export() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wb_backup_restore(jsonb, jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.wb_backup_tables() TO service_role;
GRANT EXECUTE ON FUNCTION public.wb_backup_export() TO service_role;
GRANT EXECUTE ON FUNCTION public.wb_tombstone_export() TO service_role;
GRANT EXECUTE ON FUNCTION public.wb_backup_restore(jsonb, jsonb) TO service_role;
