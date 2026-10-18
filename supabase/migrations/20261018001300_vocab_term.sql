-- 내 용어집(DEC-33): 업무에서 자주 쓰는 단어를 받아쓰기 힌트와 회의록 표기 기준으로 쓴다.
-- 클로바노트 단어 등록과 같은 규칙: 사용자당 200개, 한글·영문·숫자·띄어쓰기만, 20자 이내.
-- 추가는 wb_vocab_add 함수 하나로만 한다(중복·형식·개수 검사를 한곳에서 하고, 파일 가져오기의 일부 실패를 건너뛴 개수로 알려 주기 위해).

create table public.vocab_term (
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null default auth.uid() references public.app_user(id) on delete cascade,
  term        text not null check (term ~ '^[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9]([가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 ]{0,18}[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9])?$'),
  created_at  timestamptz not null default now()
);
-- 대소문자만 다른 같은 단어(ARS/ars)는 하나로 본다
create unique index vocab_term_owner_term_uq on public.vocab_term (owner_id, lower(term));

alter table public.vocab_term enable row level security;
create policy own_select on public.vocab_term for select to authenticated using (owner_id = (select auth.uid()));
create policy own_delete on public.vocab_term for delete to authenticated using (owner_id = (select auth.uid()));
grant select, delete on public.vocab_term to authenticated;
-- 처리 함수(process-record)가 받아쓰기·정리 힌트로 읽는다
grant select on public.vocab_term to service_role;

CREATE OR REPLACE FUNCTION public.wb_vocab_add(p_terms text[])
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_count int;
  v_added int := 0;
  v_dup int := 0;
  v_invalid int := 0;
  v_over int := 0;
  v_term text;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'WB401: 로그인이 필요합니다' USING ERRCODE = '28000';
  END IF;
  IF p_terms IS NULL OR cardinality(p_terms) > 1000 THEN
    RAISE EXCEPTION 'WB400: 한 번에 1000개까지 넣을 수 있습니다' USING ERRCODE = '22023';
  END IF;
  -- 같은 사용자의 동시 추가가 200개 상한을 함께 넘지 않게 사용자 단위로 줄 세운다
  PERFORM pg_advisory_xact_lock(hashtext('vocab:' || v_uid::text));
  SELECT count(*) INTO v_count FROM public.vocab_term WHERE owner_id = v_uid;

  FOREACH v_term IN ARRAY p_terms LOOP
    v_term := btrim(regexp_replace(coalesce(v_term, ''), '\s+', ' ', 'g'));
    IF v_term !~ '^[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9]([가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 ]{0,18}[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9])?$' THEN
      v_invalid := v_invalid + 1;
    ELSIF EXISTS (SELECT 1 FROM public.vocab_term WHERE owner_id = v_uid AND lower(term) = lower(v_term)) THEN
      v_dup := v_dup + 1;
    ELSIF v_count >= 200 THEN
      v_over := v_over + 1;
    ELSE
      INSERT INTO public.vocab_term (owner_id, term) VALUES (v_uid, v_term);
      v_added := v_added + 1;
      v_count := v_count + 1;
    END IF;
  END LOOP;

  RETURN jsonb_build_object('added', v_added, 'duplicate', v_dup, 'invalid', v_invalid, 'over_limit', v_over, 'total', v_count);
END;
$$;

REVOKE ALL ON FUNCTION public.wb_vocab_add(text[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.wb_vocab_add(text[]) TO authenticated;

-- 백업 범위에 포함(app_user 다음이면 충분하지만 사용자 설정 묶음으로 settings 뒤에 둔다)
CREATE OR REPLACE FUNCTION public.wb_backup_tables()
RETURNS text[]
LANGUAGE sql
IMMUTABLE
SET search_path = ''
AS $$
  SELECT ARRAY['app_user','consent_grant','settings','vocab_term','workspace','area','project','project_state_version',
    'source_record','audio_asset','transcript_version','meeting_version','mail_dispatch','analysis_run',
    'source_project_link','resource','resource_link','task','change_proposal','evidence_link',
    'approval_event','task_revision','activity_log','processing_job','cost_ledger','budget_reservation',
    'deletion_request','tombstone'];
$$;
