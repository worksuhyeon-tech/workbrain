import { supabase } from '../lib/supabase'
import { recordProjectIds } from '../rules/records'
import type { LinkSuggestion, ProjectName, ProposalRow } from './records'

/*
 * 검토함 읽기·쓰기(앱 Server.pendingProposals·processedProposals·snoozeProposals, ProjectState.pendingAll, LinkSuggest.pending,
 * Para.recordLinks와 같은 표·칸). 승인·버림은 records.ts의 approveProposal·rejectProposal을 'inbox'로 부른다(DEC-23)
 */

function must<T>(r: { data: unknown; error: { message: string } | null }, what: string): T {
  if (r.error) {
    console.error(`${what} failed`, r.error)
    throw new Error(r.error.message)
  }
  return r.data as T
}

const one = <T,>(v: T | T[] | null | undefined): T | null => (Array.isArray(v) ? (v[0] ?? null) : (v ?? null))

export interface InboxAudio {
  retention_expires_at: string | null
  purged_at: string | null
}

/** 검토함 한 줄: 제안 + 나온 기록 + 근거 전사·음성 보관 기한 */
export interface InboxRow extends Omit<ProposalRow, 'evidence_link'> {
  created_at: string | null
  recordId: string | null
  recordTitle: string
  recordAt: string | null
  /** 근거 전사 보관 기한(미뤄도 늘지 않음, DEC-15). 지나면 승인할 수 없다 */
  transcriptExpiresAt: string | null
  /** 원본 음성 보관 기한과 지웠는지. 음성이 없는 기록(가져온 글)이면 null */
  audio: InboxAudio | null
}

/** 프로젝트 현재 상태 제안(FR-09): 요약·상태 전후와 근거 한 문장 */
export interface StateProposal {
  id: string
  status: string
  revision: number
  projectId: string
  /** 제안을 만들 때 본 현재 상태 버전(없으면 처음 상태). 지금 버전과 다르면 반영해도 충돌로 닫힌다 */
  baseVersion: number | null
  before: Record<string, unknown> | null
  after: Record<string, unknown>
  rationale: string
  quote: string | null
}

const STATE_COLUMNS = 'id,status,revision,target_id,base_version,before_json,after_json,rationale,evidence_link(quote)'

// 후보 → 분석 → 전사 → 기록 → 음성을 한 번에 따라간다(앱 INBOX_COLUMNS)
const INBOX_COLUMNS =
  'id,status,revision,after_json,rationale,target_id,before_json,snoozed_until,created_at,target_type,' +
  'analysis_run(transcript_version(retention_expires_at,source_record(id,title,occurred_at,ingested_at,audio_asset(retention_expires_at,purged_at))))'

interface InboxRaw extends Omit<InboxRow, 'recordId' | 'recordTitle' | 'recordAt' | 'transcriptExpiresAt' | 'audio'> {
  analysis_run: {
    transcript_version: {
      retention_expires_at: string | null
      source_record: { id: string; title: string | null; occurred_at: string | null; ingested_at: string | null; audio_asset: InboxAudio | InboxAudio[] | null } | null
    } | null
  } | null
}

function toInbox(r: InboxRaw): InboxRow {
  const tv = r.analysis_run?.transcript_version
  const rec = tv?.source_record
  const { analysis_run: _run, ...rest } = r
  return {
    ...rest,
    recordId: rec?.id ?? null,
    recordTitle: rec?.title?.trim() || '제목 없는 기록',
    recordAt: rec ? (rec.occurred_at ?? rec.ingested_at) : null,
    transcriptExpiresAt: tv?.retention_expires_at ?? null,
    audio: one(rec?.audio_asset),
  }
}

function toState(r: { id: string; status: string; revision: number; target_id: string | null; base_version?: number | null; before_json: Record<string, unknown> | null; after_json: Record<string, unknown>; rationale: string; evidence_link?: { quote: string | null }[] | null }): StateProposal {
  return {
    id: r.id,
    status: r.status,
    revision: r.revision,
    projectId: r.target_id ?? '',
    baseVersion: r.base_version ?? null,
    before: r.before_json,
    after: r.after_json,
    rationale: r.rationale,
    // 가장 최근에 더한 근거 문장 하나
    quote: [...(r.evidence_link ?? [])].reverse().find((e) => e.quote?.trim())?.quote ?? null,
  }
}

export interface Inbox {
  /** 대기 중인 할 일 후보·기존 할 일 변경(미룬 것 포함 — 오늘·미룬 항목은 화면이 나눈다), 오래된 것부터 */
  rows: InboxRow[]
  states: StateProposal[]
  projects: ProjectName[]
  /** 기록 → 확정 연결된 프로젝트(추가할 곳 처음 값, DEC-69) */
  recordProjects: Map<string, string[]>
  /** 기록 → 아직 확인하지 않은 AI 연결 제안 */
  suggestions: Map<string, LinkSuggestion[]>
  myNames: string[]
}

export async function loadInbox(): Promise<Inbox> {
  const [rows, states, projects, links, suggestions, settings] = await Promise.all([
    supabase.from('change_proposal').select(INBOX_COLUMNS).eq('status', 'pending').or('target_type.is.null,target_type.neq.project_state').order('created_at', { ascending: true }),
    supabase
      .from('change_proposal')
      .select(STATE_COLUMNS)
      .eq('target_type', 'project_state')
      .eq('status', 'pending')
      .order('created_at', { ascending: true }),
    supabase.from('project').select('id,name,archived_at'),
    supabase.from('source_project_link').select('source_record_id,project_id,status,item_ref').eq('status', 'confirmed'),
    supabase
      .from('source_project_link')
      .select('source_record_id,project_id,item_ref,confidence,uncertainty_reasons,rationale,evidence_link(quote)')
      .eq('status', 'proposed')
      .eq('created_by', 'ai')
      .order('created_at', { ascending: true }),
    supabase.from('settings').select('my_names'),
  ])
  const linkRows = links.error ? [] : (links.data as { source_record_id: string; project_id: string; status: string; item_ref: { scope?: string } | null }[])
  const byRecord = new Map<string, typeof linkRows>()
  for (const l of linkRows) byRecord.set(l.source_record_id, [...(byRecord.get(l.source_record_id) ?? []), l])
  const recordProjects = new Map([...byRecord].map(([rid, ls]) => [rid, recordProjectIds(ls)]))
  const sugg = new Map<string, LinkSuggestion[]>()
  for (const s of suggestions.error
    ? []
    : (suggestions.data as { source_record_id: string; project_id: string; item_ref: { scope?: string } | null; confidence: string | null; uncertainty_reasons: string[] | null; rationale: string | null; evidence_link: { quote: string | null }[] | null }[])) {
    if (s.item_ref?.scope !== 'record') continue
    const list = sugg.get(s.source_record_id) ?? []
    list.push({
      projectId: s.project_id,
      confidence: s.confidence ?? 'unrated',
      reasons: s.uncertainty_reasons ?? [],
      quote: (s.evidence_link ?? []).find((e) => e.quote)?.quote ?? null,
      reason: s.rationale?.trim() || null,
    })
    sugg.set(s.source_record_id, list)
  }
  return {
    // 현재 상태 제안은 할 일 후보 묶음·추가할 곳·미루기에 섞지 않는다(앱 isState)
    rows: must<InboxRaw[]>(rows, 'load inbox').map(toInbox).filter((r) => r.target_type !== 'project_state'),
    // 현재 상태 제안·이름·연결은 보조 정보라 못 읽어도 할 일 후보는 보여 준다
    states: states.error ? [] : (states.data as Parameters<typeof toState>[0][]).map(toState),
    projects: projects.error ? [] : (projects.data as ProjectName[]),
    recordProjects,
    suggestions: sugg,
    myNames: settings.error ? [] : ((settings.data as { my_names: string[] | null }[])[0]?.my_names ?? []),
  }
}

/** 처리됨 탭(FR-28, DEC-63): 최근에 처리한 후보 100건과 처리한 시각(승인 기록이 없으면 만든 시각), 처리한 시각 최신순 */
export async function loadProcessed(): Promise<{ row: InboxRow; processedAt: string | null }[]> {
  const rows = must<InboxRaw[]>(
    await supabase.from('change_proposal').select(INBOX_COLUMNS).neq('status', 'pending').order('created_at', { ascending: false }).limit(100),
    'load processed',
  ).map(toInbox)
  if (rows.length === 0) return []
  const times = await supabase.from('approval_event').select('proposal_id,created_at').in('proposal_id', rows.map((r) => r.id))
  const at = new Map((times.error ? [] : (times.data as { proposal_id: string; created_at: string }[])).map((t) => [t.proposal_id, t.created_at]))
  return rows
    .map((row) => ({ row, processedAt: at.get(row.id) ?? row.created_at }))
    .sort((a, b) => (b.processedAt ?? '').localeCompare(a.processedAt ?? ''))
}

/**
 * 검토 미루기(FR-28, 서버 0028). until = 다시 볼 날('YYYY-MM-DD', 그날 0시에 '오늘'로), null이면 지금 검토(미룸 해제).
 * 대기 중인 내 후보만 바뀌고, 바뀐 개수를 돌려준다
 */
export async function snoozeProposals(ids: string[], until: string | null): Promise<number> {
  const data = must<{ updated?: number } | null>(await supabase.rpc('wb_snooze_proposals', { p_ids: ids, p_until: until }), 'snooze proposals')
  return data?.updated ?? 0
}

/** 이 프로젝트에서 기다리는 현재 상태 제안(대기 제안은 하나, 서버 색인). 없으면 null */
export async function loadPendingState(projectId: string): Promise<StateProposal | null> {
  const rows = must<Parameters<typeof toState>[0][]>(
    await supabase.from('change_proposal').select(STATE_COLUMNS).eq('target_type', 'project_state').eq('target_id', projectId).eq('status', 'pending').limit(1),
    'load pending state',
  )
  return rows[0] ? toState(rows[0]) : null
}

