import { supabase } from '../lib/supabase'
import type { Place } from '../rules/place'
import type { AreaRow, ProjectRow, TaskRow, TaskSource } from './types'

/*
 * 서버 읽기·쓰기(앱 data/Server.kt·Para.kt와 같은 표·칸). 권한은 서버 RLS가 본인 것만 연다.
 * 실패하면 예외를 던지고, 화면이 사용자 문구로 바꿔 보여 준다
 */

const TASK_WITH_PLACE = '*,project(name,archived_at),area(name,archived_at)'
const SOURCE_COLUMNS = 'task_id,approval_event(change_proposal(analysis_run(transcript_version(source_record(id,title,occurred_at,ingested_at)))))'

// 같은 칸 안은 이름순(사용자가 '01. …'처럼 번호로 순서를 정함, DEC-65). 한글 사전 순서
const byName = new Intl.Collator('ko').compare

function must<T>(r: { data: T | null; error: { message: string } | null }, what: string): T {
  if (r.error) {
    console.error(`${what} failed`, r.error)
    throw new Error(r.error.message)
  }
  return r.data as T
}

/** 할 일 전부(완료 포함, 속한 곳 이름·보관 여부 함께). 오늘·하위 개수·상위 이름을 여기서 다 뽑는다 */
export async function loadTasks(): Promise<TaskRow[]> {
  return must(await supabase.from('task').select(TASK_WITH_PLACE).order('created_at', { ascending: false }), 'load tasks')
}

/** 할 일을 넣거나 옮길 곳: 보관되지 않은 프로젝트, 그다음 반복실행(각각 이름순) */
export async function loadPlaces(): Promise<Place[]> {
  const [projects, areas] = await Promise.all([
    supabase.from('project').select('id,name').is('archived_at', null),
    supabase.from('area').select('id,name').is('archived_at', null),
  ])
  const p = must<Pick<ProjectRow, 'id' | 'name'>[]>(projects, 'load projects')
  const a = must<Pick<AreaRow, 'id' | 'name'>[]>(areas, 'load areas')
  return [
    ...p.sort((x, y) => byName(x.name, y.name)).map((x) => ({ type: 'PROJECT' as const, id: x.id, name: x.name })),
    ...a.sort((x, y) => byName(x.name, y.name)).map((x) => ({ type: 'AREA' as const, id: x.id, name: x.name })),
  ]
}

interface SrcRevision {
  task_id: string
  approval_event: {
    change_proposal: { analysis_run: { transcript_version: { source_record: { id: string; title: string | null; occurred_at: string | null; ingested_at: string | null } | null } | null } | null } | null
  } | null
}

/** 할 일 → 출처 한 줄. AI 승인으로 생긴 할 일만 있고 나머지는 직접 추가. 보조 정보라 못 읽으면 빈 목록 */
export async function loadSources(): Promise<Map<string, TaskSource>> {
  const r = await supabase.from('task_revision').select(SOURCE_COLUMNS).eq('actor', 'ai-approved')
  if (r.error) {
    console.warn('load task sources failed', r.error)
    return new Map()
  }
  const out = new Map<string, TaskSource>()
  for (const x of r.data as unknown as SrcRevision[]) {
    const rec = x.approval_event?.change_proposal?.analysis_run?.transcript_version?.source_record
    if (!rec) continue
    const at = rec.occurred_at ?? rec.ingested_at
    const day = at ? new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' }).formatToParts(new Date(at)) : null
    const md = day ? `${day.find((p) => p.type === 'month')?.value}/${day.find((p) => p.type === 'day')?.value}` : null
    // 목업 표기: 'AI 제안 승인 · IT 개발팀 통화 10/14'
    out.set(x.task_id, { label: `AI 제안 승인 · ${rec.title ?? '기록'}${md ? ` ${md}` : ''}`, recordId: rec.id })
  }
  return out
}

/** 사용자가 직접 만드는 새 할 일. 아카이브된 곳은 서버가 WB409로 막는다 */
export async function createTask(fields: Record<string, unknown>): Promise<void> {
  must(await supabase.from('task').insert(fields), 'create task')
}

/** 완료·완료 취소. 버전이 같을 때만 바꾼다 — 다른 기기에서 먼저 바꿨으면 false(M18) */
export async function setTaskDone(t: TaskRow, done: boolean): Promise<boolean> {
  const rows = must(
    await supabase.from('task').update({ status: done ? 'done' : 'open', version: t.version + 1 }).eq('id', t.id).eq('version', t.version).select('id'),
    'set task done',
  )
  return rows.length > 0
}

/**
 * 오늘 화면 '검토 대기' 수(앱 오늘 화면과 같은 거르기): 승인 전 할 일 후보·연결 제안만.
 * 프로젝트 현재 상태 제안(FR-09)은 프로젝트 상세·검토함에서 보고, 미뤄 둔 동안인 것은 뺀다
 */
export async function pendingProposalCount(): Promise<number> {
  const r = await supabase
    .from('change_proposal')
    .select('id', { count: 'exact', head: true })
    .eq('status', 'pending')
    .or('target_type.is.null,target_type.neq.project_state')
    .or(`snoozed_until.is.null,snoozed_until.lte.${new Date().toISOString()}`)
  if (r.error) throw new Error(r.error.message)
  return r.count ?? 0
}

/** 서버 오류를 사용자 문구로(앱 ui/Labels.kt saveFailText와 같은 문구) */
export function saveFailText(e: unknown): string {
  const m = e instanceof Error ? e.message : String(e)
  if (m.includes('아카이브')) return '아카이브된 곳이라 바꿀 수 없어요. 먼저 복원해 주세요'
  return '저장하지 못했어요. 인터넷 연결을 확인해 주세요'
}
