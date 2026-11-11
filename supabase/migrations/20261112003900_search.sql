-- 0039 검색(FR-13, DEC-98, 2026-11-11 사용자 결정, Design §3.14)
-- 오늘·프로젝트·기록 탭 오른쪽 위 🔍에서 할 일·회의록(기록 제목 + 그 기록의 가장 새 회의록 글)·자료를 글자로 찾는다.
-- AI·벡터를 쓰지 않아 비용 0원. 전사와 프로젝트 현재 상태는 찾지 않는다(사용자 결정: 중요한 말이면 회의록에 들어간다).
-- 검토 전 초안(승인 전 회의록 글·AI가 뽑은 결정·미결에서만 맞은 기록 + 검토 대기 할 일 후보)은 draft = true로 돌려준다.
-- 화면은 기록 초안을 결과에 '초안' 칩으로 함께 보이고, 할 일 후보만 아래에 접어 둔다(2026-11-11 사용자 결정).
--
-- 권한: SECURITY INVOKER라 부르는 사람(authenticated)의 RLS가 그대로 걸려 내 것만 나온다(TC-01). anon은 실행 권한이 없다.
-- 색인은 두지 않는다(11/11 할 일 57·기록 43건). 느려지면 edge_logs 서버 처리 시간으로 DB 탓인지 가른 뒤 pg_trgm 색인을 따로 붙인다.
-- 원격 SQL 확인 창이 만료되므로 DROP·DELETE 없이 쓴다. 다시 실행해도 같은 결과(CREATE OR REPLACE). 돌려주는 칸을 바꾸려면 새 이름으로 만든다.

-- ① 맞은 곳 앞뒤 약 30자 조각. 줄바꿈·연속 공백은 한 칸으로, 잘린 쪽에 '…'.
-- 대소문자 차이로 위치를 못 찾으면(드묾) 앞 60자를 준다. 글이 비면 null
CREATE OR REPLACE FUNCTION public.wb_search_snippet(p_text text, p_q text)
RETURNS text
LANGUAGE sql
IMMUTABLE
SET search_path = ''
AS $$
  WITH t AS (
    SELECT btrim(regexp_replace(coalesce(p_text, ''), '\s+', ' ', 'g')) AS s
  ), p AS (
    SELECT t.s, strpos(lower(t.s), lower(p_q)) AS pos, char_length(t.s) AS len, char_length(p_q) AS qlen FROM t
  )
  SELECT CASE
    WHEN p.s = '' THEN NULL
    WHEN p.pos = 0 THEN left(p.s, 60) || CASE WHEN p.len > 60 THEN '…' ELSE '' END
    ELSE CASE WHEN p.pos > 31 THEN '…' ELSE '' END
      || substr(p.s, greatest(1, p.pos - 30), (p.pos - greatest(1, p.pos - 30)) + p.qlen + 30)
      || CASE WHEN p.pos + p.qlen + 29 < p.len THEN '…' ELSE '' END
  END
  FROM p
$$;

-- ② 검색. p_q: 검색어(앞뒤 공백을 빼고 두 글자 미만이면 빈 결과, 100자 넘으면 WB400).
-- p_project·p_area: 그곳으로 거르기(둘 중 하나만). 기록·후보는 프로젝트에만 이어지므로 반복실행으로 거르면 할 일·자료만 나온다.
-- p_since: 이 시각 뒤 것만(기록·후보 = 회의 시각, 할 일·자료 = 만든 시각). p_kinds: task·record·resource·proposal 중 고른 것(비면 전부).
-- 종류마다 최신순 최대 30건. 휴지통·보관한 기록(source_record.archived_at)은 뺀다
-- (기록 탭 휴지통은 기기에만 있어서(DEC-51) 그 기기 휴지통의 기록은 앱이 뺀다).
CREATE OR REPLACE FUNCTION public.wb_search(
  p_q text,
  p_project uuid DEFAULT NULL,
  p_area uuid DEFAULT NULL,
  p_since timestamptz DEFAULT NULL,
  p_kinds text[] DEFAULT NULL
)
RETURNS TABLE (
  kind text,            -- task · record · resource · proposal
  id uuid,              -- 할 일·기록·자료·후보 ID
  title text,           -- 제목(기록은 제목이 없을 수 있음)
  snippet text,         -- 제목이 아닌 곳에서 맞았을 때 그 앞뒤 조각, 제목에서 맞으면 null
  record_id uuid,       -- 누르면 열 기록(기록·후보·기록 참조 자료)
  record_title text,    -- 그 기록 제목(후보 줄의 출처 표시·기록 상세 머리)
  project_id uuid,      -- 속한(연결된) 프로젝트 하나
  area_id uuid,         -- 속한(연결된) 반복실행 하나
  place_name text,      -- 표시용 곳 이름(여럿이면 쉼표로)
  at timestamptz,       -- 기록·후보 = 회의 시각(모르면 올린 시각), 할 일·자료 = 만든 시각
  draft boolean,        -- 검토 전 초안: 승인 전 회의록 글·결정·미결에서만 맞은 기록 · 검토 대기 할 일 후보
  done boolean,         -- 끝낸 할 일
  archived boolean,     -- 보관한 곳의 할 일 · 보관한 자료
  audio_expired boolean -- 원본 음성 보관 기간이 지났거나 지운 기록
)
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
#variable_conflict use_column
DECLARE
  v_q text := btrim(coalesce(p_q, ''), E' \t\r\n');
  v_pat text;
  v_all boolean := p_kinds IS NULL OR cardinality(p_kinds) = 0;
BEGIN
  IF char_length(v_q) < 2 THEN
    RETURN;
  END IF;
  IF char_length(v_q) > 100 THEN
    RAISE EXCEPTION 'WB400 search query too long' USING ERRCODE = '22023';
  END IF;
  IF p_project IS NOT NULL AND p_area IS NOT NULL THEN
    RAISE EXCEPTION 'WB400 project and area filter together' USING ERRCODE = '22023';
  END IF;
  IF NOT v_all AND NOT (p_kinds <@ ARRAY['task', 'record', 'resource', 'proposal']) THEN
    RAISE EXCEPTION 'WB400 unknown search kind' USING ERRCODE = '22023';
  END IF;
  -- %·_·\ 를 적어도 글자 그대로 찾게 이스케이프한다(LIKE 와일드카드로 쓰이지 않게)
  v_pat := '%' || replace(replace(replace(v_q, '\', '\\'), '%', '\%'), '_', '\_') || '%';

  -- 할 일: 제목·담당·모호 기한 글
  IF v_all OR 'task' = ANY (p_kinds) THEN
    RETURN QUERY
    SELECT 'task'::text, t.id, t.title,
           CASE WHEN t.title ILIKE v_pat ESCAPE '\' THEN NULL
                WHEN t.assignee ILIKE v_pat ESCAPE '\' THEN public.wb_search_snippet('담당 ' || t.assignee, v_q)
                ELSE public.wb_search_snippet('기한 ' || t.due_text, v_q) END,
           NULL::uuid, NULL::text, t.project_id, t.area_id, coalesce(pj.name, ar.name), t.created_at,
           false, t.status = 'done', coalesce(pj.archived_at, ar.archived_at) IS NOT NULL, false
      FROM public.task t
      LEFT JOIN public.project pj ON pj.id = t.project_id
      LEFT JOIN public.area ar ON ar.id = t.area_id
     WHERE (t.title ILIKE v_pat ESCAPE '\' OR t.assignee ILIKE v_pat ESCAPE '\' OR t.due_text ILIKE v_pat ESCAPE '\')
       AND (p_project IS NULL OR t.project_id = p_project)
       AND (p_area IS NULL OR t.area_id = p_area)
       AND (p_since IS NULL OR t.created_at >= p_since)
     ORDER BY t.created_at DESC
     LIMIT 30;
  END IF;

  -- 기록: 제목 + 그 기록의 가장 새 회의록 문서(기록 상세가 보여 주는 것, Server.meeting과 같은 규칙)의 문자열 값만.
  -- 키 이름(sections·heading 등)이 걸리지 않게 jsonb 값 가운데 문자열만 본다.
  -- 회의록 문서를 아직 안 만든 기록은 AI가 뽑은 결정·미결 사항 글(fields.decisions·open_questions의 text)을 찾는다(2026-11-11 사용자 결정).
  -- 이미 있는 할 일(already_tracked)·참석자·분류는 찾지 않는다. 조각 앞에 '결정 · '·'미결 · '을 붙여 어디서 맞았는지 보인다
  IF v_all OR 'record' = ANY (p_kinds) THEN
    RETURN QUERY
    WITH latest AS (
      SELECT DISTINCT ON (tv.source_record_id) tv.source_record_id AS rid, mv.state AS mstate, mv.document AS doc, mv.fields AS flds
        FROM public.meeting_version mv
        JOIN public.transcript_version tv ON tv.id = mv.transcript_version_id
       ORDER BY tv.source_record_id, mv.created_at DESC
    ), hits AS (
      SELECT r.id AS rid, r.title AS rtitle, coalesce(r.occurred_at, r.ingested_at) AS rat,
             coalesce(r.title ILIKE v_pat ESCAPE '\', false) AS in_title,
             l.mstate,
             (SELECT j.v #>> '{}'
                FROM jsonb_path_query(l.doc, 'strict $.** ? (@.type() == "string")') AS j(v)
               WHERE (j.v #>> '{}') ILIKE v_pat ESCAPE '\'
               LIMIT 1) AS doc_hit,
             CASE WHEN l.doc IS NULL THEN (
               SELECT f.label || ' · ' || public.wb_search_snippet(f.v #>> '{}', v_q)
                 FROM (SELECT '결정'::text AS label, x.v FROM jsonb_path_query(l.flds, 'lax $.decisions[*].text ? (@.type() == "string")') AS x(v)
                       UNION ALL
                       SELECT '미결'::text, y.v FROM jsonb_path_query(l.flds, 'lax $.open_questions[*].text ? (@.type() == "string")') AS y(v)) AS f
                WHERE (f.v #>> '{}') ILIKE v_pat ESCAPE ''
                LIMIT 1)
             END AS field_snip
        FROM public.source_record r
        LEFT JOIN latest l ON l.rid = r.id
       WHERE r.archived_at IS NULL
         AND p_area IS NULL
         AND (p_since IS NULL OR coalesce(r.occurred_at, r.ingested_at) >= p_since)
         AND (p_project IS NULL OR EXISTS (
               SELECT 1 FROM public.source_project_link s
                WHERE s.source_record_id = r.id AND s.project_id = p_project
                  AND s.status = 'confirmed' AND s.item_ref->>'scope' = 'record'))
    )
    SELECT 'record'::text, h.rid, h.rtitle,
           CASE WHEN h.in_title THEN NULL
                WHEN h.doc_hit IS NOT NULL THEN public.wb_search_snippet(h.doc_hit, v_q)
                ELSE h.field_snip END,
           h.rid, h.rtitle, pl.pid, NULL::uuid, pl.names, h.rat,
           NOT h.in_title AND coalesce(h.mstate, '') <> 'approved',
           false, false,
           EXISTS (SELECT 1 FROM public.audio_asset a
                    WHERE a.source_record_id = h.rid AND (a.purged_at IS NOT NULL OR a.retention_expires_at < now()))
      FROM hits h
      LEFT JOIN LATERAL (
        SELECT (array_agg(p.id ORDER BY p.name, p.id))[1] AS pid,
               string_agg(DISTINCT p.name, ', ' ORDER BY p.name) AS names
          FROM public.source_project_link s
          JOIN public.project p ON p.id = s.project_id
         WHERE s.source_record_id = h.rid AND s.status = 'confirmed' AND s.item_ref->>'scope' = 'record'
      ) pl ON true
     WHERE h.in_title OR h.doc_hit IS NOT NULL OR h.field_snip IS NOT NULL
     ORDER BY h.rat DESC
     LIMIT 30;
  END IF;

  -- 자료: 제목·메모·링크 주소. 곳은 연결(resource_link)에서, 프로젝트 먼저
  IF v_all OR 'resource' = ANY (p_kinds) THEN
    RETURN QUERY
    SELECT 'resource'::text, rs.id, rs.title,
           CASE WHEN rs.title ILIKE v_pat ESCAPE '\' THEN NULL
                WHEN rs.note ILIKE v_pat ESCAPE '\' THEN public.wb_search_snippet(rs.note, v_q)
                ELSE public.wb_search_snippet(rs.url, v_q) END,
           rs.source_record_id, sr.title, lk.pid, lk.aid, lk.names, rs.created_at,
           false, false, rs.archived_at IS NOT NULL, false
      FROM public.resource rs
      LEFT JOIN public.source_record sr ON sr.id = rs.source_record_id
      LEFT JOIN LATERAL (
        SELECT (array_agg(rl.target_id ORDER BY rl.target_id) FILTER (WHERE rl.target_type = 'project'))[1] AS pid,
               (array_agg(rl.target_id ORDER BY rl.target_id) FILTER (WHERE rl.target_type = 'area'))[1] AS aid,
               string_agg(coalesce(p.name, a.name), ', ' ORDER BY rl.target_type DESC, coalesce(p.name, a.name)) AS names
          FROM public.resource_link rl
          LEFT JOIN public.project p ON rl.target_type = 'project' AND p.id = rl.target_id
          LEFT JOIN public.area a ON rl.target_type = 'area' AND a.id = rl.target_id
         WHERE rl.resource_id = rs.id
      ) lk ON true
     WHERE (rs.title ILIKE v_pat ESCAPE '\' OR rs.note ILIKE v_pat ESCAPE '\' OR rs.url ILIKE v_pat ESCAPE '\')
       AND (p_project IS NULL OR EXISTS (SELECT 1 FROM public.resource_link x
                                          WHERE x.resource_id = rs.id AND x.target_type = 'project' AND x.target_id = p_project))
       AND (p_area IS NULL OR EXISTS (SELECT 1 FROM public.resource_link x
                                       WHERE x.resource_id = rs.id AND x.target_type = 'area' AND x.target_id = p_area))
       AND (p_since IS NULL OR rs.created_at >= p_since)
     ORDER BY rs.created_at DESC
     LIMIT 30;
  END IF;

  -- 검토 대기 할 일 후보(늘 초안): 새 후보는 after_json, 기존 할 일 변경은 바뀌기 전 제목(before_json)으로.
  -- 곳 = '추가할 곳'(after_json.project_id), 없으면 변경 대상 할 일의 프로젝트. 보관한 기록에서 나온 후보는 뺀다
  IF v_all OR 'proposal' = ANY (p_kinds) THEN
    RETURN QUERY
    WITH c AS (
      SELECT cp.id, coalesce(cp.after_json->>'title', cp.before_json->>'title') AS ctitle,
             cp.after_json->>'assignee' AS cassignee, cp.after_json->>'due_text' AS cdue,
             coalesce(cp.after_json->>'project_id', tt.project_id::text) AS cproject,
             r.id AS rid, r.title AS rtitle, coalesce(r.occurred_at, r.ingested_at) AS rat, cp.created_at AS made
        FROM public.change_proposal cp
        JOIN public.analysis_run ru ON ru.id = cp.analysis_run_id
        JOIN public.transcript_version tv ON tv.id = ru.input_transcript_version_id
        JOIN public.source_record r ON r.id = tv.source_record_id
        LEFT JOIN public.task tt ON tt.id = cp.target_id
       WHERE cp.status = 'pending' AND cp.target_type = 'task' AND r.archived_at IS NULL
    )
    SELECT 'proposal'::text, c.id, c.ctitle,
           CASE WHEN c.ctitle ILIKE v_pat ESCAPE '\' THEN NULL
                WHEN c.cassignee ILIKE v_pat ESCAPE '\' THEN public.wb_search_snippet('담당 ' || c.cassignee, v_q)
                ELSE public.wb_search_snippet('기한 ' || c.cdue, v_q) END,
           c.rid, c.rtitle, pj.id, NULL::uuid, pj.name, c.rat,
           true, false, false,
           EXISTS (SELECT 1 FROM public.audio_asset a
                    WHERE a.source_record_id = c.rid AND (a.purged_at IS NOT NULL OR a.retention_expires_at < now()))
      FROM c
      LEFT JOIN public.project pj ON pj.id::text = c.cproject
     WHERE (c.ctitle ILIKE v_pat ESCAPE '\' OR c.cassignee ILIKE v_pat ESCAPE '\' OR c.cdue ILIKE v_pat ESCAPE '\')
       AND p_area IS NULL
       AND (p_project IS NULL OR c.cproject = p_project::text)
       AND (p_since IS NULL OR c.rat >= p_since)
     ORDER BY c.rat DESC, c.made
     LIMIT 30;
  END IF;
END
$$;

REVOKE ALL ON FUNCTION public.wb_search_snippet(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_search_snippet(text, text) TO authenticated;
REVOKE ALL ON FUNCTION public.wb_search(text, uuid, uuid, timestamptz, text[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_search(text, uuid, uuid, timestamptz, text[]) TO authenticated;
