-- WorkBrain P1 기준 스키마 (Design v0.1.7 §4.3, DEC-20/22/23)
-- 설계 DDL을 생성 순서대로 정렬했다(앞 테이블이 뒤 테이블을 참조하지 않도록).
-- 열거형은 text + CHECK로 둔다(DESIGN-OPEN-ISSUES M1). 시각은 UTC timestamptz.
-- owner_id 기본값을 auth.uid()로 두어 클라이언트가 소유자를 직접 넣지 않게 한다. RLS가 다시 검사한다.

create table public.app_user (                       -- Supabase Auth 사용자와 1:1(M13)
  id            uuid primary key references auth.users(id) on delete cascade,
  email         text not null unique,                -- 허용 목록 계정만(FR-01)
  created_at    timestamptz not null default now()
);

create table public.consent_grant (                  -- 외부 전송 동의(FR-17, M19)
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null default auth.uid() references public.app_user(id) on delete cascade,
  purpose       text not null check (purpose in ('voice-stt','text-ai','minutes-guideline-refs')),
  provider      text not null,
  policy_version text not null,
  granted_at    timestamptz not null default now(),
  revoked_at    timestamptz
);

create table public.settings (
  user_id              uuid primary key references public.app_user(id) on delete cascade,
  timezone             text not null default 'Asia/Seoul',
  retention_audio_days int not null default 30 check (retention_audio_days in (30,60,90)),
  retention_transcript_months int not null default 6 check (retention_transcript_months in (6,12)),
  retention_knowledge_years   int not null default 1 check (retention_knowledge_years in (1,2,3)),
  budget_warn_krw      int not null default 20000,
  budget_wait_krw      int not null default 24000,
  minutes_guideline    text check (char_length(minutes_guideline) <= 8000),
  minutes_guideline_refs jsonb,
  guideline_rev        int not null default 0,
  guideline_source_name text,
  guideline_imported_at timestamptz,
  mail_sender_address  text,
  mail_recipients      text[] not null default '{}',
  mail_last_test_at    timestamptz,
  updated_at           timestamptz not null default now(),
  check (cardinality(mail_recipients) <= 3)
);

create table public.workspace (                      -- 업무 공간(DEC-22)
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null default auth.uid() references public.app_user(id) on delete cascade,
  name        text not null,
  description text,
  created_at  timestamptz not null default now()
);

create table public.area (
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null default auth.uid() references public.app_user(id) on delete cascade,
  workspace_id uuid not null references public.workspace(id),
  name        text not null,
  archived_at timestamptz,
  created_at  timestamptz not null default now()
);

create table public.project (
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null default auth.uid() references public.app_user(id) on delete cascade,
  workspace_id uuid not null references public.workspace(id),
  area_id     uuid references public.area(id),
  name        text not null,
  purpose     text,
  version     int not null default 0,                -- 이름·목적·영역·보관 수정의 낙관적 동시성(M18)
  current_state_version int not null default 0,      -- 승인 함수만 올린다
  archived_at timestamptz,
  created_at  timestamptz not null default now()
);

create table public.project_state_version (
  id             uuid primary key default gen_random_uuid(),
  owner_id       uuid not null references public.app_user(id) on delete cascade,
  project_id     uuid not null references public.project(id),
  version        int not null,
  summary        text,
  status         text not null check (status in ('active','paused','done')),
  applied_by     text not null check (applied_by in ('user','ai-approved')),
  change_reason  text,
  created_at     timestamptz not null default now(),
  unique (project_id, version)
);

create table public.source_record (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null default auth.uid() references public.app_user(id) on delete cascade,
  workspace_id  uuid not null references public.workspace(id),
  channel       text not null check (channel in ('recording','upload','call-share')),
  title         text,                                -- 녹음 중 고친 제목(S04)
  occurred_at   timestamptz,
  occurred_unknown boolean not null default false,
  ingested_at   timestamptz not null default now(),
  classification text check (classification in ('work','personal','uncertain')),
  storage_permission text not null check (storage_permission in ('personal','sample','company-approved')), -- 금지·모름이면 행을 만들지 않음(M11)
  archived_at   timestamptz,
  retention_expires_at timestamptz
);

create table public.audio_asset (
  id            uuid primary key default gen_random_uuid(),
  source_record_id uuid not null references public.source_record(id),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  storage_key   text not null,
  file_hash     text not null,
  bytes         bigint not null check (bytes > 0 and bytes <= 200 * 1024 * 1024),
  duration_sec  int check (duration_sec <= 120 * 60),
  mime          text not null check (mime in ('audio/mp4','audio/x-m4a','audio/mpeg','audio/wav','audio/webm')),
  retention_expires_at timestamptz not null,
  unique (owner_id, file_hash)                       -- 중복 업로드 멱등(NFR-03)
);

create table public.transcript_version (
  id            uuid primary key default gen_random_uuid(),
  source_record_id uuid not null references public.source_record(id),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  kind          text not null check (kind in ('stt-original','user-edited')),
  parent_version_id uuid references public.transcript_version(id),
  content       text,                                -- 만료 시 본문 제거, 메타 유지
  segments      jsonb,
  retention_expires_at timestamptz not null,
  created_at    timestamptz not null default now()
);

create table public.meeting_version (
  id            uuid primary key default gen_random_uuid(),
  transcript_version_id uuid not null references public.transcript_version(id),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  state         text not null check (state in ('draft','user-edited','approved')),
  fields        jsonb not null,
  parent_version_id uuid references public.meeting_version(id),
  document      jsonb,
  guideline_rev int,
  guideline_imported_at timestamptz,
  guideline_input_hash text,
  approved_at   timestamptz,
  superseded_at timestamptz,
  retention_expires_at timestamptz not null,
  created_at    timestamptz not null default now()
);

create table public.mail_dispatch (                  -- FR-22
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  meeting_version_id uuid not null references public.meeting_version(id),
  idempotency_key text not null,
  recipients    text[] not null,
  recipient_results jsonb,
  state         text not null check (state in ('queued','sending','sent','partial','unknown','failed-retryable','failed-auth','failed')),
  attempt_count int not null default 0 check (attempt_count <= 3),
  provider_message_id text,
  last_error_code text,
  created_at    timestamptz not null default now(),
  sent_at       timestamptz,
  unique (owner_id, idempotency_key)
);

create table public.analysis_run (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  input_transcript_version_id uuid not null references public.transcript_version(id),
  model_id      text not null,
  prompt_version text not null,
  config_hash   text not null,
  status        text not null,
  retry_count   int not null default 0,
  provider_request_id text,
  usage         jsonb,
  created_at    timestamptz not null default now()
);

create table public.source_project_link (            -- 기록 항목 ↔ 프로젝트(FR-06, M16)
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null references public.app_user(id) on delete cascade,
  source_record_id uuid not null references public.source_record(id),
  transcript_version_id uuid not null references public.transcript_version(id),
  item_ref    jsonb not null,
  project_id  uuid not null references public.project(id),
  status      text not null default 'proposed' check (status in ('proposed','confirmed','rejected')),
  created_by  text not null check (created_by in ('ai','user')),
  confidence  text not null default 'unrated',
  uncertainty_reasons text[] not null default '{}',
  analysis_run_id uuid references public.analysis_run(id),
  version     int not null default 0,
  created_at  timestamptz not null default now(),
  unique (source_record_id, transcript_version_id, item_ref, project_id)
);

create table public.resource (                       -- P1 최소 표현(M15)
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null default auth.uid() references public.app_user(id) on delete cascade,
  workspace_id uuid not null references public.workspace(id),
  kind        text not null check (kind in ('link','note','record-ref')),
  title       text not null,
  url         text,
  note        text check (char_length(note) <= 2000),
  source_record_id uuid references public.source_record(id),
  version     int not null default 0,
  archived_at timestamptz,
  created_at  timestamptz not null default now()
);

create table public.resource_link (                  -- 자료 ↔ 프로젝트/반복실행 N:M
  owner_id    uuid not null default auth.uid() references public.app_user(id) on delete cascade,
  resource_id uuid not null references public.resource(id) on delete cascade,
  target_type text not null check (target_type in ('project','area')),
  target_id   uuid not null,
  primary key (resource_id, target_type, target_id)
);

create table public.task (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null default auth.uid() references public.app_user(id) on delete cascade,
  project_id    uuid references public.project(id),
  area_id       uuid references public.area(id),
  title         text not null check (char_length(title) between 1 and 200),
  status        text not null default 'open' check (status in ('open','doing','done')),
  priority      int check (priority in (1,2,3)),
  owner_kind    text not null default 'me' check (owner_kind in ('me','other','unknown')), -- DEC-23
  assignee      text,
  due_date      date,                                -- 날짜만 보존
  due_time      timestamptz,                         -- 시각 포함 시
  due_ambiguous boolean not null default false,
  due_text      text,                                -- 모호한 기한 원문('다음 주'). 날짜로 바꾸지 않음
  version       int not null default 0,
  created_at    timestamptz not null default now(),
  check (project_id is null or area_id is null),
  check (owner_kind <> 'other' or assignee is not null)
);

create table public.change_proposal (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  analysis_run_id uuid not null references public.analysis_run(id),
  target_type   text not null check (target_type in ('project_state','task','source_link')),
  target_id     uuid,
  base_version  int,
  before_json   jsonb,
  after_json    jsonb not null,
  rationale     text not null,
  revision      int not null default 0,
  confidence    text not null default 'unrated',
  uncertainty_reasons text[] not null default '{}',
  dedup_key     text,
  status        text not null default 'pending' check (status in ('pending','approved','rejected','conflict','quarantined')),
  snoozed_until timestamptz,
  created_at    timestamptz not null default now()
);

create table public.evidence_link (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  proposal_id   uuid references public.change_proposal(id),
  source_project_link_id uuid references public.source_project_link(id),
  meeting_version_id uuid references public.meeting_version(id),
  transcript_version_id uuid not null references public.transcript_version(id),
  segment_ref   jsonb not null,
  created_at    timestamptz not null default now()
);

create table public.approval_event (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  target_type   text not null check (target_type in ('proposal','meeting')),
  proposal_id   uuid references public.change_proposal(id),
  meeting_version_id uuid references public.meeting_version(id),
  seen_revision int,
  result        text not null check (result in ('approved','rejected')),
  actor         text not null default 'user' check (actor = 'user'),
  context       text check (context in ('inbox','record')),  -- 검토함/기록 상세 어디서 승인했는지(DEC-23)
  applied_version int,
  created_at    timestamptz not null default now(),
  unique (proposal_id),                              -- 승인 효과 1회(FR-10)
  unique (meeting_version_id)
);

create table public.task_revision (                  -- 할 일 수정 전후 보존(M18). 트리거만 쓴다
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  task_id       uuid not null references public.task(id) on delete cascade,
  version       int not null,
  snapshot      jsonb not null,
  actor         text not null check (actor in ('user','ai-approved')),
  approval_event_id uuid references public.approval_event(id),
  created_at    timestamptz not null default now(),
  unique (task_id, version)
);

create table public.activity_log (                   -- 원문·사람 이름 미기록, ID 중심
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  entity_type   text not null,
  entity_id     uuid not null,
  action        text not null,
  actor         text not null check (actor in ('user','ai-approved')),
  at            timestamptz not null default now()
);

create table public.processing_job (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  source_record_id uuid not null references public.source_record(id),
  stage         text not null check (stage in ('UPLOAD','STT','EXTRACT','DOCUMENT','REVIEWABLE')),
  stage_state   text not null check (stage_state in ('PENDING','RESERVED','RUNNING','BUDGET_WAIT','DONE','FAILED','CANCELED')),
  execution_key text not null,
  lease_until   timestamptz,
  retry_count   int not null default 0 check (retry_count <= 3),
  last_error    text,
  updated_at    timestamptz not null default now(),
  unique (owner_id, execution_key)
);

create table public.cost_ledger (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  billing_cycle text not null,
  confirmed_krw int not null default 0,
  estimated_krw int not null default 0,
  created_at    timestamptz not null default now()
);

create table public.budget_reservation (             -- 삽입은 reserve_budget() 함수로만(C7)
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  processing_job_id uuid not null references public.processing_job(id),
  stage         text not null check (stage in ('STT','EXTRACT','DOCUMENT')),
  billing_cycle text not null,
  analysis_run_id uuid references public.analysis_run(id),
  reserved_krw  int not null check (reserved_krw >= 0),
  settled_krw   int,
  state         text not null check (state in ('held','consumed','released','expired')),
  expires_at    timestamptz,
  created_at    timestamptz not null default now()
);

create table public.deletion_request (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  scope         text not null check (scope in ('retention-expiry','source-only','with-derived')),
  target_ref    jsonb not null,
  recover_until timestamptz not null,
  impact        jsonb not null,
  state         text not null check (state in ('soft-blocked','restored','purging','purged','partial-failed')),
  restored_at   timestamptz,
  purged_at     timestamptz,
  created_at    timestamptz not null default now()
);

create table public.tombstone (                      -- 삭제 원장(C9). 본문·이름 미포함
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references public.app_user(id) on delete cascade,
  opaque_target_id text not null,
  scope         text not null,
  at            timestamptz not null default now()
);

-- RLS 조건(owner_id)과 자주 쓰는 연결의 인덱스
create index on public.consent_grant (owner_id);
create index on public.workspace (owner_id);
create index on public.area (owner_id);
create index on public.area (workspace_id);
create index on public.project (owner_id);
create index on public.project (workspace_id);
create index on public.project (area_id);
create index on public.project_state_version (owner_id);
create index on public.source_record (owner_id);
create index on public.source_record (workspace_id);
create index on public.audio_asset (source_record_id);
create index on public.transcript_version (owner_id);
create index on public.transcript_version (source_record_id);
create index on public.transcript_version (parent_version_id);
create index on public.meeting_version (owner_id);
create index on public.meeting_version (transcript_version_id);
create index on public.meeting_version (parent_version_id);
create index on public.mail_dispatch (meeting_version_id);
create index on public.analysis_run (owner_id);
create index on public.analysis_run (input_transcript_version_id);
create index on public.source_project_link (owner_id);
create index on public.source_project_link (project_id);
create index on public.source_project_link (transcript_version_id);
create index on public.source_project_link (analysis_run_id);
create index on public.resource (owner_id);
create index on public.resource (workspace_id);
create index on public.resource (source_record_id);
create index on public.resource_link (owner_id);
create index on public.resource_link (target_type, target_id);
create index on public.task (owner_id);
create index on public.task (project_id);
create index on public.task (area_id);
create index on public.change_proposal (owner_id, status);
create index on public.change_proposal (analysis_run_id);
create index on public.evidence_link (owner_id);
create index on public.evidence_link (proposal_id);
create index on public.evidence_link (source_project_link_id);
create index on public.evidence_link (meeting_version_id);
create index on public.evidence_link (transcript_version_id);
create index on public.approval_event (owner_id);
create index on public.task_revision (owner_id);
create index on public.task_revision (approval_event_id);
create index on public.activity_log (owner_id, entity_type, entity_id);
create index on public.processing_job (source_record_id);
create index on public.cost_ledger (owner_id);
create index on public.budget_reservation (owner_id);
create index on public.budget_reservation (processing_job_id);
create index on public.budget_reservation (analysis_run_id);
create index on public.deletion_request (owner_id);
create index on public.tombstone (owner_id);
