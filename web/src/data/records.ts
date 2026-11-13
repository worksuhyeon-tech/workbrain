import { SUPABASE_PUBLISHABLE_KEY, SUPABASE_URL, supabase } from '../lib/supabase'
import { recordProjectIds, type JobBrief, type ProposalBrief, type Segment } from '../rules/records'

/*
 * 기록 목록·상세 읽기(앱 data/Server.kt의 meeting·proposals·transcripts·recordInfo, LinkSuggest.pending과 같은 표·칸).
 * 권한은 서버 RLS가 본인 것만 연다. 실패하면 예외를 던지고 화면이 사용자 문구로 바꾼다
 */

// 고른 칸 글이 길거나 관계 이름을 적으면 라이브러리가 칸 모양을 못 읽어, 결과 모양은 부르는 쪽이 정한다
function must<T>(r: { data: unknown; error: { message: string } | null }, what: string): T {
  if (r.error) {
    console.error(`${what} failed`, r.error)
    throw new Error(r.error.message)
  }
  return r.data as T
}

/** 기록당 하나인 줄(음성·처리 작업)을 PostgREST가 객체나 한 칸 배열로 준다 */
const one = <T,>(v: T | T[] | null | undefined): T | null => (Array.isArray(v) ? (v[0] ?? null) : (v ?? null))

export interface LinkRow {
  project_id: string
  status: string
  item_ref: { scope?: string } | null
}

export interface ProjectName {
  id: string
  name: string
  archived_at: string | null
}

/** 기록 목록 한 줄 */
export interface RecordListRow {
  id: string
  title: string
  channel: string | null
  /** 회의 시각(모르면 올린 시각) */
  at: string | null
  attendees: string[]
  classification: string | null
  durationSec: number | null
  audioPurged: boolean
  job: JobBrief | null
  /** 기록 전체로 확정 연결된 프로젝트 */
  projectIds: string[]
  proposals: ProposalBrief[]
}

// 관계 이름을 적는다: source_project_link가 기록·전사·분석을 모두 잇는 표라, 이름 없이는 길이 둘로 보여 서버가 거부한다
const LIST_COLUMNS =
  'id,title,channel,occurred_at,ingested_at,classification,attendees,' +
  'audio_asset(duration_sec,purged_at),processing_job(stage,stage_state),source_project_link(project_id,status,item_ref),' +
  'transcript_version!transcript_version_source_record_id_fkey(analysis_run!analysis_run_input_transcript_version_id_fkey(change_proposal(status,target_type,target_id)))'

interface ListRaw {
  id: string
  title: string | null
  channel: string | null
  occurred_at: string | null
  ingested_at: string | null
  classification: string | null
  attendees: string[] | null
  audio_asset: { duration_sec: number | null; purged_at: string | null } | { duration_sec: number | null; purged_at: string | null }[] | null
  processing_job: JobBrief | JobBrief[] | null
  source_project_link: LinkRow[] | null
  transcript_version: { analysis_run: { change_proposal: ProposalBrief[] | null }[] | null }[] | null
}

/** 내 기록 전부(보관 안 한 것) + 프로젝트 이름(보관된 것 포함 — 보관된 곳에 연결된 기록도 이름이 보이게) */
export async function loadRecords(): Promise<{ records: RecordListRow[]; projects: ProjectName[] }> {
  const [recs, projects] = await Promise.all([
    supabase.from('source_record').select(LIST_COLUMNS).is('archived_at', null),
    supabase.from('project').select('id,name,archived_at'),
  ])
  const rows = must<ListRaw[]>(recs, 'load records')
  return {
    records: rows.map((r) => {
      const audio = one(r.audio_asset)
      return {
        id: r.id,
        title: r.title?.trim() || '제목 없는 기록',
        channel: r.channel,
        at: r.occurred_at ?? r.ingested_at,
        attendees: r.attendees ?? [],
        classification: r.classification,
        durationSec: audio?.duration_sec ?? null,
        audioPurged: !!audio?.purged_at,
        job: one(r.processing_job),
        projectIds: recordProjectIds(r.source_project_link ?? []),
        proposals: (r.transcript_version ?? []).flatMap((tv) => (tv.analysis_run ?? []).flatMap((run) => run.change_proposal ?? [])),
      }
    }),
    projects: must<ProjectName[]>(projects, 'load projects'),
  }
}

// ── 상세 ──

export interface RecordInfo {
  id: string
  title: string
  channel: string | null
  occurred_at: string | null
  ingested_at: string | null
  classification: string | null
  attendees: string[]
  durationSec: number | null
  job: JobBrief | null
}

/** 회의록 문서(서버 document.ts의 MinutesDocument와 같은 모양) */
export interface MinutesDocument {
  title?: string
  sections?: { heading: string; lines?: string[]; table?: { columns: string[]; rows?: string[][] } | null }[]
}

export interface MeetingRow {
  id: string
  fields: Record<string, unknown> | null
  document: MinutesDocument | null
  guideline_rev: number | null
  guideline_imported_at: string | null
  /** draft | user-edited | approved */
  state: string
  approved_at: string | null
}

export interface ProposalRow {
  id: string
  status: string
  revision: number
  after_json: Record<string, unknown>
  before_json: Record<string, unknown> | null
  rationale: string
  target_id: string | null
  target_type: string
  snoozed_until: string | null
  evidence_link: { segment_ref: { idx?: unknown } | null }[] | null
}

export interface Transcript {
  content: string
  segments: Segment[]
}

export interface LinkSuggestion {
  projectId: string
  confidence: string
  reasons: string[]
  quote: string | null
  reason: string | null
}

export interface RecordDetail {
  info: RecordInfo
  meeting: MeetingRow | null
  /** 화면의 회의록이 초안인데 승인본이 따로 있으면 그 승인 시각(승인 뒤 다시 만든 경우, DEC-61) */
  approvedEarlierAt: string | null
  /** 할 일 후보·기존 할 일 변경(현재 상태 제안은 프로젝트 상세·검토함에서 본다) */
  proposals: ProposalRow[]
  /** 종류별 최신 전사: 교정본(term-corrected)·받아쓴 원문(stt-original) */
  corrected: Transcript | null
  original: Transcript | null
  linkedProjectIds: string[]
  suggestions: LinkSuggestion[]
  projects: ProjectName[]
  /** 안 끝난 할 일 제목(결정 줄의 '할 일 있음') */
  openTaskTitles: Set<string>
  /** 지금 설정의 지침 번호(문서를 만들 때 쓴 번호와 다르면 '새 지침으로 다시 만들기') */
  guidelineRev: number | null
  /** 내 이름·호칭(DEC-49): 담당 고르기의 팔로업 목록에서 뺀다 */
  myNames: string[]
}

interface InfoRaw extends Omit<RecordInfo, 'title' | 'attendees' | 'durationSec' | 'job'> {
  title: string | null
  attendees: string[] | null
  audio_asset: { duration_sec: number | null } | { duration_sec: number | null }[] | null
  processing_job: JobBrief | JobBrief[] | null
}

interface SuggestionRaw {
  project_id: string
  item_ref: { scope?: string } | null
  confidence: string | null
  uncertainty_reasons: string[] | null
  rationale: string | null
  evidence_link: { quote: string | null }[] | null
}

/** 기록 상세 전부. 없는(지웠거나 남의) 기록이면 null */
export async function loadRecordDetail(id: string): Promise<RecordDetail | null> {
  const infoRes = await supabase
    .from('source_record')
    .select('id,title,channel,occurred_at,ingested_at,classification,attendees,audio_asset(duration_sec),processing_job(stage,stage_state)')
    .eq('id', id)
  const raw = must<InfoRaw[]>(infoRes, 'load record')[0]
  if (!raw) return null

  const tvs = must<{ id: string; kind: string; content: string | null; segments: Segment[] | null }[]>(
    await supabase.from('transcript_version').select('id,kind,content,segments').eq('source_record_id', id).order('created_at', { ascending: false }),
    'load transcripts',
  )
  const tvIds = tvs.map((t) => t.id)
  const latest = (kind: string): Transcript | null => {
    const t = tvs.find((x) => x.kind === kind && x.content != null)
    return t ? { content: t.content!, segments: t.segments ?? [] } : null
  }

  const [meetings, approved, runs, links, suggestions, projects, openTasks, settings] = await Promise.all([
    tvIds.length
      ? supabase.from('meeting_version').select('id,fields,document,guideline_rev,guideline_imported_at,state,approved_at').in('transcript_version_id', tvIds).order('created_at', { ascending: false }).limit(1)
      : Promise.resolve({ data: [], error: null }),
    tvIds.length
      ? supabase.from('meeting_version').select('approved_at').in('transcript_version_id', tvIds).eq('state', 'approved').is('superseded_at', null).limit(1)
      : Promise.resolve({ data: [], error: null }),
    tvIds.length ? supabase.from('analysis_run').select('id').in('input_transcript_version_id', tvIds) : Promise.resolve({ data: [], error: null }),
    supabase.from('source_project_link').select('project_id,status,item_ref').eq('source_record_id', id),
    supabase
      .from('source_project_link')
      .select('project_id,item_ref,confidence,uncertainty_reasons,rationale,evidence_link(quote)')
      .eq('source_record_id', id)
      .eq('status', 'proposed')
      .eq('created_by', 'ai')
      .order('created_at', { ascending: true }),
    supabase.from('project').select('id,name,archived_at'),
    supabase.from('task').select('title').neq('status', 'done'),
    supabase.from('settings').select('guideline_rev,my_names'),
  ])
  const meeting = must<MeetingRow[]>(meetings, 'load meeting')[0] ?? null
  const runIds = must<{ id: string }[]>(runs, 'load runs').map((r) => r.id)
  // 기록 상세는 할 일 후보·변경만 다룬다. 이 기록으로 만든 현재 상태 제안(FR-09)은 프로젝트 상세·검토함에서 본다
  const proposals = runIds.length
    ? must<ProposalRow[]>(
        await supabase.from('change_proposal').select('*,evidence_link(segment_ref)').in('analysis_run_id', runIds).eq('target_type', 'task').order('created_at', { ascending: true }),
        'load proposals',
      )
    : []
  const audio = one(raw.audio_asset)
  return {
    info: {
      id: raw.id,
      title: raw.title?.trim() || '제목 없는 기록',
      channel: raw.channel,
      occurred_at: raw.occurred_at,
      ingested_at: raw.ingested_at,
      classification: raw.classification,
      attendees: raw.attendees ?? [],
      durationSec: audio?.duration_sec ?? null,
      job: one(raw.processing_job),
    },
    meeting,
    approvedEarlierAt:
      meeting?.state === 'approved' ? null : (must<{ approved_at: string | null }[]>(approved, 'load approved meeting')[0]?.approved_at ?? null),
    proposals,
    corrected: latest('term-corrected'),
    original: latest('stt-original'),
    linkedProjectIds: recordProjectIds(must<LinkRow[]>(links, 'load record links')),
    // 연결 제안·이름·할 일 제목·지침 번호는 보조 정보라 못 읽어도 화면은 그린다
    suggestions: (suggestions.error ? [] : (suggestions.data as SuggestionRaw[]))
      .filter((s) => s.item_ref?.scope === 'record')
      .map((s) => ({
        projectId: s.project_id,
        confidence: s.confidence ?? 'unrated',
        reasons: s.uncertainty_reasons ?? [],
        quote: (s.evidence_link ?? []).find((e) => e.quote)?.quote ?? null,
        reason: s.rationale?.trim() || null,
      })),
    projects: projects.error ? [] : (projects.data as ProjectName[]),
    openTaskTitles: new Set(openTasks.error ? [] : (openTasks.data as { title: string }[]).map((t) => t.title.trim())),
    guidelineRev: settings.error ? null : ((settings.data as { guideline_rev: number }[])[0]?.guideline_rev ?? null),
    myNames: settings.error ? [] : ((settings.data as { my_names: string[] | null }[])[0]?.my_names ?? []),
  }
}

// ── 손대기(②-3): 앱 Server.approve·reject·renameRecord·saveAttendees·setClassification, Para.linkRecord와 같은 서버 함수 ──

/** 결과 글(approved·already-approved·stale-revision 등)을 돌려주는 승인 RPC의 { result } */
function resultOf(r: { data: unknown; error: { message: string } | null }, what: string): string {
  const data = must<{ result?: string } | null>(r, what)
  return data?.result ?? ''
}

/**
 * 후보 승인 → 할 일(FR-07). context: record(기록 상세, 새 할 일만 DEC-23) · inbox(검토함).
 * edits는 사용자가 고친 칸(FR-10): AI 후보 원본 위에 덮어 할 일을 만든다. 보던 버전(revision)이 달라졌으면 서버가 거부한다
 */
export async function approveProposal(p: Pick<ProposalRow, 'id' | 'revision'>, context: 'record' | 'inbox', edits?: Record<string, unknown>): Promise<string> {
  return resultOf(
    await supabase.rpc('approve_proposal', { p_proposal_id: p.id, p_seen_revision: p.revision, p_context: context, ...(edits ? { p_edits: edits } : {}) }),
    'approve proposal',
  )
}

export async function rejectProposal(p: Pick<ProposalRow, 'id' | 'revision'>, context: 'record' | 'inbox'): Promise<string> {
  return resultOf(await supabase.rpc('reject_proposal', { p_proposal_id: p.id, p_seen_revision: p.revision, p_context: context }), 'reject proposal')
}

/** 기록 제목(1~200자, 서버 제약). 회의록·검토함은 기록 제목을 따라 보여 준다 */
export async function renameRecord(id: string, title: string): Promise<void> {
  must(await supabase.from('source_record').update({ title }).eq('id', id), 'rename record')
}

/** 참석자 명단(DEC-39·59, 30명·줄바꿈 없음 서버 제약). 고친 명단은 다음 회의록 만들기부터 쓰인다 */
export async function saveAttendees(id: string, names: string[]): Promise<void> {
  must(await supabase.from('source_record').update({ attendees: names }).eq('id', id), 'save attendees')
}

/** 분류(FR-06): work · personal · uncertain. 개인으로 바꾸면 그 기록에는 새 연결 제안을 만들지 않는다 */
export async function setClassification(id: string, value: 'work' | 'personal' | 'uncertain'): Promise<void> {
  must(await supabase.from('source_record').update({ classification: value }).eq('id', id), 'set classification')
}

/**
 * 기록 전체를 프로젝트에 연결(linked) 또는 해제. 결과: linked · unlinked · unchanged.
 * AI 제안 [아니요]도 해제와 같다(제안이 'rejected'로 남아 다시 제안하지 않음). 아카이브·남의 것은 서버가 WB409·WB404로 거부
 */
export async function linkRecord(recordId: string, projectId: string, linked: boolean): Promise<string> {
  return must<string>(await supabase.rpc('wb_link_record_project', { p_record_id: recordId, p_project_id: projectId, p_linked: linked }), 'link record')
}

/**
 * 연결을 확정한 기록으로 그 프로젝트의 현재 상태 제안을 만들거나 고쳐 쓴다(FR-09, DEC-86 — 앱 StateWorker와 같은 호출, 회의 하나에 약 1~3원).
 * 결과: PROPOSED · NO_CHANGE · ALREADY · NOT_LINKED · NO_TRANSCRIPT · BUDGET_WAIT · FAILED 등
 */
export async function requestProjectState(recordId: string, projectId: string): Promise<string> {
  const { data, error } = await supabase.functions.invoke('process-record', { body: { action: 'project_state', record_id: recordId, project_id: projectId } })
  if (error) throw error
  return (data as { state?: string } | null)?.state ?? 'FAILED'
}

// ── 회의록(②-4): 앱 Server.makeMinutes·minutesStatus·approveMeeting·minutesWord와 같은 함수 ──

/** 회의록 만들기 응답: 다 됨 · 같은 요청을 아직 만드는 중 · 실패(보여 줄 문구) */
export type MinutesAnswer = { kind: 'done' } | { kind: 'running' } | { kind: 'failed'; message: string }

/**
 * 회의록 만들기(FR-30, DEC-56): 처리 함수가 이 호출 안에서 문서를 만든다(짧은 회의도 1~2분, AI 비용).
 * requestKey는 누를 때 한 번 만든 열쇠: 응답을 못 받아 다시 확인해도 같은 열쇠라 서버가 두 번 만들지 않는다(DEC-79).
 * 응답을 못 받으면(통신·시간 초과) 예외 — 서버는 만들고 있을 수 있어 부른 쪽이 상태를 확인한다
 */
export async function makeMinutes(recordId: string, requestKey: string): Promise<MinutesAnswer> {
  const { data, error } = await supabase.functions.invoke('process-record', { body: { record_id: recordId, action: 'document', request_key: requestKey } })
  if (error) throw error
  const body = data as { document?: string; error?: string } | null
  if (body?.document === 'DONE') return { kind: 'done' }
  if (body?.document === 'RUNNING') return { kind: 'running' }
  return { kind: 'failed', message: body?.error ?? '회의록을 만들지 못했어요' }
}

/** 응답을 못 받은 회의록 요청이 서버에서 어떻게 됐는지: DONE·FAILED·RUNNING·BUSY·NONE, 내 기록이 아니면 null(DEC-79) */
export async function minutesStatus(recordId: string, requestKey: string): Promise<string | null> {
  return must<string | null>(await supabase.rpc('wb_document_status', { p_record_id: recordId, p_key: requestKey }), 'minutes status')
}

/**
 * 회의록 버전 승인(FR-07). 결과: approved | already-approved | stale-version | no-document | evidence-missing.
 * 화면에 보이는 버전 ID를 보내 다른 기기에서 새로 만든 버전을 모르고 승인하지 않게 한다(M14)
 */
export async function approveMeeting(meetingVersionId: string): Promise<string> {
  return resultOf(await supabase.rpc('approve_meeting', { p_meeting_version_id: meetingVersionId }), 'approve meeting')
}

/**
 * 회의록 Word(FR-29, DEC-60): 화면에 보이는 회의록 버전을 서버가 .docx로 만든다(외부 AI 없음·비용 없음).
 * 파일 바이트를 그대로 받아야 해서 함수 주소를 직접 부른다(supabase-js는 이 형식을 글자로 읽어 깨뜨린다)
 */
export async function minutesWord(meetingVersionId: string): Promise<{ blob: Blob; meetingDate: string; draft: boolean }> {
  const token = (await supabase.auth.getSession()).data.session?.access_token
  if (!token) throw new Error('WB401')
  const res = await fetch(`${SUPABASE_URL}/functions/v1/minutes-word`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, apikey: SUPABASE_PUBLISHABLE_KEY, 'content-type': 'application/json' },
    body: JSON.stringify({ meeting_version_id: meetingVersionId }),
  })
  if (!res.ok) throw new Error(`minutes-word ${res.status}`)
  return { blob: await res.blob(), meetingDate: res.headers.get('x-wb-meeting-date') ?? '', draft: res.headers.get('x-wb-draft') !== 'false' }
}

