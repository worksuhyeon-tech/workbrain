import { supabase } from '../lib/supabase'
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
    supabase.from('settings').select('guideline_rev'),
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
  }
}
