-- 검토 미루기(FR-28, DEC-15·63) — Design v0.1.10 §3.7
-- 미루기·미루기 변경·지금 검토(p_until = null)를 함수 하나로 한다. change_proposal은 로그인 사용자에게 SELECT만 열려 있어(M13)
-- 이 함수로만 바꾼다. 미룸은 검토 메타라 승인 상태·revision을 바꾸지 않고, 근거 보관 기한도 늘리지 않는다(DEC-15).
-- 결과: {"updated": 바뀐 후보 수, "snoozed_until": 다시 나올 시각(해제면 null)}

CREATE OR REPLACE FUNCTION public.wb_snooze_proposals(p_ids uuid[], p_until date)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_tz text;
  v_today date;
  v_until timestamptz;
  v_count int;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;
  IF p_ids IS NULL OR cardinality(p_ids) = 0 OR cardinality(p_ids) > 200 THEN
    RAISE EXCEPTION 'WB400: 미룰 후보를 1~200개 골라 주세요' USING ERRCODE = '22023';
  END IF;

  -- 날짜는 사용자 시간대 기준. 고른 날 0시부터 '오늘' 목록에 다시 나온다
  SELECT s.timezone INTO v_tz FROM public.settings s WHERE s.user_id = v_uid;
  v_tz := coalesce(v_tz, 'Asia/Seoul');
  v_today := (now() AT TIME ZONE v_tz)::date;
  IF p_until IS NOT NULL THEN
    IF p_until <= v_today OR p_until > (v_today + interval '1 year')::date THEN
      RAISE EXCEPTION 'WB400: 미룰 날짜는 내일부터 1년 안이어야 합니다' USING ERRCODE = '22023';
    END IF;
    v_until := p_until::timestamp AT TIME ZONE v_tz;
  END IF;

  -- 대기 중인 내 후보만 바꾼다. 남의 후보·이미 처리된 후보는 조용히 건너뛰고 바뀐 개수만 알려 준다
  WITH changed AS (
    UPDATE public.change_proposal
       SET snoozed_until = v_until
     WHERE id = ANY(p_ids) AND owner_id = v_uid AND status = 'pending'
    RETURNING id
  ), logged AS (
    INSERT INTO public.activity_log (owner_id, entity_type, entity_id, action, actor)
    SELECT v_uid, 'change_proposal', c.id,
           CASE WHEN p_until IS NULL THEN 'unsnoozed' ELSE 'snoozed:' || to_char(p_until, 'YYYY-MM-DD') END,
           'user'
      FROM changed c
    RETURNING 1
  )
  SELECT count(*) INTO v_count FROM logged;

  RETURN jsonb_build_object('updated', v_count, 'snoozed_until', v_until);
END;
$$;

REVOKE ALL ON FUNCTION public.wb_snooze_proposals(uuid[], date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_snooze_proposals(uuid[], date) TO authenticated;
