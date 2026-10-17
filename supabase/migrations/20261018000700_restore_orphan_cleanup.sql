-- 복원 수정(TC-12 시험 작성 중 발견): 삭제 원장으로 부모 행만 지우면 그 자식 행(예: 지운 할 일의 task_revision —
-- 제목 스냅샷 포함)이 임시 스키마에 남아 지운 내용이 되살아난다.
-- 원장 적용 뒤, 운영 스키마의 외래 키 정의를 따라 부모가 없는 자식 행을 더 지울 것이 없을 때까지 반복해 지운다.
-- resource_link.target_id는 외래 키가 없는 다형 연결이라 따로 정리한다.

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
  v_orphans int := 0;
  v_round int;
  v_n int;
  v_counts jsonb := '{}'::jsonb;
  fk record;
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

  -- 1) 구조만 복사해 넣고 원장에 있는 ID를 지운다
  FOREACH t IN ARRAY public.wb_backup_tables() LOOP
    EXECUTE format('CREATE TABLE wb_restore.%I (LIKE public.%I INCLUDING DEFAULTS)', t, t);
    EXECUTE format('INSERT INTO wb_restore.%I SELECT * FROM jsonb_populate_recordset(NULL::public.%I, $1)', t, t)
      USING coalesce(p_backup->'tables'->t, '[]'::jsonb);
    IF t NOT IN ('settings', 'resource_link', 'tombstone') THEN
      EXECUTE format('DELETE FROM wb_restore.%I WHERE id::text = ANY($1)', t) USING v_ids;
      GET DIAGNOSTICS v_n = ROW_COUNT;
      v_removed := v_removed + v_n;
    END IF;
  END LOOP;

  -- 2) 부모가 사라진 자식 행을 연쇄로 지운다(한 열짜리 외래 키, auth 참조 제외)
  LOOP
    v_round := 0;
    FOR fk IN
      SELECT ch.relname AS child, a.attname AS col, pa.relname AS parent, af.attname AS pcol
      FROM pg_catalog.pg_constraint c
      JOIN pg_catalog.pg_class ch ON ch.oid = c.conrelid
      JOIN pg_catalog.pg_class pa ON pa.oid = c.confrelid
      JOIN pg_catalog.pg_namespace pn ON pn.oid = pa.relnamespace
      JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
      JOIN pg_catalog.pg_attribute af ON af.attrelid = c.confrelid AND af.attnum = c.confkey[1]
      WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
        AND pn.nspname = 'public' AND array_length(c.conkey, 1) = 1
    LOOP
      EXECUTE format('DELETE FROM wb_restore.%I ch WHERE ch.%I IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wb_restore.%I p WHERE p.%I = ch.%I)',
                     fk.child, fk.col, fk.parent, fk.pcol, fk.col);
      GET DIAGNOSTICS v_n = ROW_COUNT;
      v_round := v_round + v_n;
    END LOOP;
    DELETE FROM wb_restore.resource_link l
    WHERE (l.target_type = 'project' AND NOT EXISTS (SELECT 1 FROM wb_restore.project p WHERE p.id = l.target_id))
       OR (l.target_type = 'area' AND NOT EXISTS (SELECT 1 FROM wb_restore.area a WHERE a.id = l.target_id));
    GET DIAGNOSTICS v_n = ROW_COUNT;
    v_round := v_round + v_n;
    v_orphans := v_orphans + v_round;
    EXIT WHEN v_round = 0;
  END LOOP;

  FOREACH t IN ARRAY public.wb_backup_tables() LOOP
    EXECUTE format('SELECT count(*) FROM wb_restore.%I', t) INTO v_n;
    v_counts := v_counts || jsonb_build_object(t, v_n);
  END LOOP;

  RETURN jsonb_build_object('schema', 'wb_restore', 'tombstoned_removed', v_removed,
                            'orphans_removed', v_orphans, 'counts', v_counts);
END;
$$;

REVOKE ALL ON FUNCTION public.wb_backup_restore(jsonb, jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.wb_backup_restore(jsonb, jsonb) TO service_role;
