import { supabase } from '../lib/supabase'
import type { Place, PlaceType } from '../rules/place'
import { loadSources, loadTasks } from './server'
import { isDone, isSubtask, type AreaRow, type ProjectRow, type TaskRow, type TaskSource } from './types'

/*
 * PARA 트리(FR-02, 앱 data/Para.kt와 같은 표·칸): 업무 공간 → 1. 프로젝트 / 2. 반복실행 / 3. 관심영역(자료) / 4. 아카이브.
 * 아카이브는 따로 있는 표가 아니라 각 항목의 archived_at이다(보관해도 ID·이력 유지). 보관된 항목은 서버가 읽기 전용으로 막는다
 */

export interface WorkspaceRow {
  id: string
  name: string
  description: string | null
}

/** 기록 한 건 요약: 트리 아래 기록·'관련 기록' 카드·기록 참조 자료. at = 회의 시각(모르면 올린 시각) */
export interface RecordBrief {
  id: string
  title: string
  at: string | null
  channel: string | null
  durationSec: number | null
}

export interface ResourceRow {
  id: string
  kind: string
  title: string
  url: string | null
  note: string | null
  version: number
  archived_at: string | null
  source_record_id: string | null
  record: RecordBrief | null
}

export interface ResourceLinkRow {
  resource_id: string
  target_type: string
  target_id: string
}

export interface LinkedRecord {
  projectId: string
  record: RecordBrief
}

export interface ParaData {
  workspace: WorkspaceRow
  projects: ProjectRow[]
  areas: AreaRow[]
  resources: ResourceRow[]
  links: ResourceLinkRow[]
  tasks: TaskRow[]
  sources: Map<string, TaskSource>
  recordLinks: LinkedRecord[]
}

/** 프로젝트 현재 상태 한 판(FR-09, project_state_version). 가장 큰 version이 지금 상태 */
export interface StateVersionRow {
  version: number
  summary: string | null
  status: string
  applied_by: string
  created_at: string
}

const RECORD_BRIEF_COLUMNS = 'id,title,channel,occurred_at,ingested_at,audio_asset(duration_sec)'
// 같은 칸 안은 이름순(사용자가 '01. …'처럼 번호로 순서를 정함, DEC-65). 한글 사전 순서
const byName = new Intl.Collator('ko').compare

interface RecordBriefRow {
  id: string
  title: string | null
  channel: string | null
  occurred_at: string | null
  ingested_at: string | null
  audio_asset: { duration_sec: number | null } | { duration_sec: number | null }[] | null
}

function toBrief(r: RecordBriefRow): RecordBrief {
  // 기록당 음성은 하나라 PostgREST가 객체나 한 칸짜리 배열로 준다
  const audio = Array.isArray(r.audio_asset) ? r.audio_asset[0] : r.audio_asset
  return { id: r.id, title: r.title ?? '제목 없는 기록', at: r.occurred_at ?? r.ingested_at, channel: r.channel, durationSec: audio?.duration_sec ?? null }
}

// 스키마 타입을 만들어 두지 않아 라이브러리가 연결 표(source_record 등)를 배열로 짐작한다 — 모양은 여기서 정한 대로 받는다
function must<T>(r: { data: unknown; error: { message: string } | null }, what: string): T {
  if (r.error) {
    console.error(`${what} failed`, r.error)
    throw new Error(r.error.message)
  }
  return r.data as T
}

/** 트리·상세를 그리는 데 필요한 것 전부. 출처·연결된 기록은 보조 정보라 못 읽어도 화면은 그린다 */
export async function loadPara(): Promise<ParaData> {
  const [ws, projects, areas, resources, links, tasks, sources, recordLinks] = await Promise.all([
    supabase.from('workspace').select('id,name,description').order('created_at', { ascending: true }).limit(1),
    supabase.from('project').select('id,name,purpose,area_id,version,archived_at'),
    supabase.from('area').select('id,name,description,archived_at'),
    supabase.from('resource').select(`id,kind,title,url,note,version,archived_at,source_record_id,source_record(${RECORD_BRIEF_COLUMNS})`),
    supabase.from('resource_link').select('resource_id,target_type,target_id'),
    loadTasks(),
    loadSources(),
    loadRecordLinks().catch(() => [] as LinkedRecord[]),
  ])
  const workspace = must<WorkspaceRow[]>(ws, 'load workspace')[0]
  if (!workspace) throw new Error('기본 공간이 없어요. 다시 로그인해 주세요')
  const res = must<(Omit<ResourceRow, 'record'> & { source_record: RecordBriefRow | null })[]>(resources, 'load resources')
  return {
    workspace,
    projects: must<ProjectRow[]>(projects, 'load projects').sort((a, b) => byName(a.name, b.name)),
    areas: must<AreaRow[]>(areas, 'load areas').sort((a, b) => byName(a.name, b.name)),
    resources: res.map(({ source_record, ...r }) => ({ ...r, record: source_record ? toBrief(source_record) : null })).sort((a, b) => byName(a.title, b.title)),
    links: must<ResourceLinkRow[]>(links, 'load resource links'),
    tasks,
    sources,
    recordLinks,
  }
}

/** 프로젝트에 확정 연결된 기록(기록 전체 연결만 — 항목 단위 AI 연결은 따로) */
async function loadRecordLinks(): Promise<LinkedRecord[]> {
  const rows = must<{ project_id: string; item_ref: { scope?: string } | null; source_record: RecordBriefRow | null }[]>(
    await supabase.from('source_project_link').select(`project_id,item_ref,source_record(${RECORD_BRIEF_COLUMNS})`).eq('status', 'confirmed'),
    'load record links',
  )
  return rows.filter((l) => l.item_ref?.scope === 'record' && l.source_record).map((l) => ({ projectId: l.project_id, record: toBrief(l.source_record!) }))
}

/** 지금 현재 상태(없으면 null) */
export async function loadState(projectId: string): Promise<StateVersionRow | null> {
  const rows = must<StateVersionRow[]>(
    await supabase.from('project_state_version').select('version,summary,status,applied_by,created_at').eq('project_id', projectId).order('version', { ascending: false }).limit(1),
    'load project state',
  )
  return rows[0] ?? null
}

// ── 계산(앱 ParaData와 같은 규칙) ──

/** 보관된 프로젝트·반복실행의 할 일은 '모든 할 일'과 '오늘'에서 빠진다(복원하면 다시 보임, DEC-65) */
export function inArchived(d: ParaData, t: TaskRow): boolean {
  return d.projects.some((p) => p.id === t.project_id && p.archived_at) || d.areas.some((a) => a.id === t.area_id && a.archived_at)
}

/** 그곳의 상위 할 일(하위는 상위 아래에서만, FR-23a) */
export function tasksOf(d: ParaData, type: PlaceType, id: string): TaskRow[] {
  return d.tasks.filter((t) => !isSubtask(t) && (type === 'PROJECT' ? t.project_id === id : t.area_id === id))
}

/** 트리 숫자·'할 일 N'은 상위의 안 끝난 것만 센다(DEC-101) */
export function openCount(d: ParaData, type: PlaceType, id: string): number {
  return tasksOf(d, type, id).filter((t) => !isDone(t)).length
}

/** '모든 할 일'에 올라가는 할 일(완료 포함): 보관된 곳의 것과 하위는 뺀다 */
export function liveTasks(d: ParaData): TaskRow[] {
  return d.tasks.filter((t) => !isSubtask(t) && !inArchived(d, t))
}

/** 이 프로젝트의 기록, 최근 회의부터 */
export function recordsOf(d: ParaData, projectId: string): RecordBrief[] {
  return d.recordLinks
    .filter((l) => l.projectId === projectId)
    .map((l) => l.record)
    .sort((a, b) => (b.at ?? '').localeCompare(a.at ?? ''))
}

/** 이곳에 연결된 자료 */
export function resourcesOf(d: ParaData, type: PlaceType, id: string): ResourceRow[] {
  const table = type === 'PROJECT' ? 'project' : 'area'
  const ids = new Set(d.links.filter((l) => l.target_type === table && l.target_id === id).map((l) => l.resource_id))
  return d.resources.filter((r) => ids.has(r.id))
}

/** 자료가 연결된 곳 */
export function placesOfResource(d: ParaData, resourceId: string): Place[] {
  return d.links
    .filter((l) => l.resource_id === resourceId)
    .flatMap((l): Place[] => {
      if (l.target_type === 'project') {
        const p = d.projects.find((x) => x.id === l.target_id)
        return p ? [{ type: 'PROJECT', id: p.id, name: p.name }] : []
      }
      const a = d.areas.find((x) => x.id === l.target_id)
      return a ? [{ type: 'AREA', id: a.id, name: a.name }] : []
    })
}

/** 할 일을 넣거나 옮길 곳: 보관 안 된 프로젝트, 그다음 반복실행 */
export function placesOf(d: ParaData): Place[] {
  return [
    ...d.projects.filter((p) => !p.archived_at).map((p) => ({ type: 'PROJECT' as const, id: p.id, name: p.name })),
    ...d.areas.filter((a) => !a.archived_at).map((a) => ({ type: 'AREA' as const, id: a.id, name: a.name })),
  ]
}

/** 현재 상태 이름(앱 stateLabel) */
export function stateLabel(status: string): string {
  return status === 'paused' ? '멈춤' : status === 'done' ? '완료' : '진행 중'
}

const CHANNEL: Record<string, string> = { recording: '녹음', upload: '파일', 'call-share': '통화', 'text-import': '받아쓴 글' }

/** 기록 한 줄 덧말(앱 RecordPick.recordMeta): 'M/D · 녹음 42분' / '통화' / '받아쓴 글' */
export function recordMeta(r: RecordBrief): string {
  const day = r.at ? new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' }).formatToParts(new Date(r.at)) : null
  const md = day ? `${day.find((p) => p.type === 'month')?.value}/${day.find((p) => p.type === 'day')?.value}` : null
  const kind = r.channel ? CHANNEL[r.channel] : undefined
  const what = kind ? (r.durationSec ? `${kind} ${Math.max(1, Math.floor(r.durationSec / 60))}분` : kind) : null
  return [md, what].filter(Boolean).join(' · ')
}
