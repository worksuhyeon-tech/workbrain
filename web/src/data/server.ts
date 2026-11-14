import { supabase } from '../lib/supabase'
import { uniquePeople } from '../rules/people'
import type { Place } from '../rules/place'
import type { SearchRow } from '../rules/search'
import { newTaskFields } from '../rules/tasks'
import type { AreaRow, ProjectRow, TaskEdit, TaskRow, TaskSource } from './types'

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

/** 할 일 하나의 지금 모습(옮기기 되돌리기는 그 사이 바뀐 버전으로 다시 저장한다). 없어졌으면 null */
export async function loadTask(id: string): Promise<TaskRow | null> {
  const rows = must(await supabase.from('task').select(TASK_WITH_PLACE).eq('id', id), 'load task') as TaskRow[]
  return rows[0] ?? null
}

/** 할 일을 넣거나 옮길 곳:보관되지 않은 프로젝트, 그다음 반복실행(각각 이름순) */
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

/** 사용자가 직접 만드는 새 할 일. 만든 할 일 ID를 돌려준다(캘린더에도 넣을 때). 아카이브된 곳은 서버가 WB409로 막는다 */
export async function createTask(fields: Record<string, unknown>): Promise<string> {
  return must<{ id: string }>(await supabase.from('task').insert(fields).select('id').single(), 'create task').id
}

/**
 * 할 일 고치기(제목·우선순위·담당·기한·어디에·받는 사람). 버전이 같을 때만 바꾸고 바뀐 행을 돌려준다 —
 * 다른 기기에서 먼저 바꿨으면 null(M18). 수정 전후는 서버 트리거가 task_revision에 쌓고, 보관된 곳으로 옮기면 서버가 막는다
 */
export async function updateTask(t: TaskRow, e: TaskEdit): Promise<TaskRow | null> {
  const rows = must(
    await supabase
      .from('task')
      .update({
        title: e.title,
        priority: e.priority,
        owner_kind: e.owner_kind,
        assignee: e.assignee,
        due_date: e.due_date,
        due_time: e.due_time,
        due_ambiguous: e.due_ambiguous,
        due_text: e.due_text,
        project_id: e.project_id,
        area_id: e.area_id,
        recipients: e.recipients,
        version: t.version + 1,
      })
      .eq('id', t.id)
      .eq('version', t.version)
      .select(TASK_WITH_PLACE),
    'update task',
  ) as TaskRow[]
  return rows[0] ?? null
}

/** 하위 할 일 넣기(FR-23a): 곳은 서버가 상위 것을 넣고, 끝난 상위면 서버가 다시 연다 */
export async function createSubtask(parent: TaskRow, e: TaskEdit): Promise<void> {
  must(await supabase.from('task').insert({ ...newTaskFields({ ...e, project_id: null, area_id: null, priority: null }), parent_id: parent.id }), 'create subtask')
}

/** 할 일 지우기. 버전이 같을 때만 — 다른 기기에서 먼저 고쳤으면 false. 하위·변경 이력은 서버가 함께 지운다 */
export async function deleteTask(t: TaskRow): Promise<boolean> {
  const rows = must(await supabase.from('task').delete().eq('id', t.id).eq('version', t.version).select('id'), 'delete task')
  return rows.length > 0
}

/**
 * 검색(FR-13, DEC-98): 서버가 내 것(RLS)에서 글자로 찾아 종류마다 최신 30건씩 준다. AI를 쓰지 않아 비용 0원.
 * q는 두 글자 이상(미만이면 서버가 빈 결과), 곳은 프로젝트·반복실행 중 하나만, kinds가 null이면 모든 종류
 */
export async function searchRows(q: string, place: Place | null, since: Date | null, kinds: string[] | null): Promise<SearchRow[]> {
  const r = await supabase.rpc('wb_search', {
    p_q: q,
    p_project: place?.type === 'PROJECT' ? place.id : null,
    p_area: place?.type === 'AREA' ? place.id : null,
    p_since: since?.toISOString() ?? null,
    p_kinds: kinds,
  })
  return must(r as { data: SearchRow[] | null; error: { message: string } | null }, 'search')
}

/** 받는 사람 고르기 칩(DEC-77): 그 기록의 참석자 + 이름 붙은 화자. 보조 정보라 못 읽으면 빈 목록 */
export async function recordPeople(recordId: string): Promise<string[]> {
  const r = await supabase.rpc('wb_record_people', { p_record_id: recordId })
  if (r.error) {
    console.warn('load record people failed', r.error)
    return []
  }
  return (r.data as string[] | null) ?? []
}

/**
 * 받는 사람 고르기 칩(DEC-77, 11/13 사용자 B안): 그 프로젝트의 기록에 나온 사람 — 프로젝트에 확정 연결된 기록 + 그 프로젝트 할 일이 나온 기록의
 * 참석자와 이름 붙은 화자(회의록 txt의 참석자 등, wb_record_people가 '화자 1'·'(나)'는 뺌). 같은 사람은 하나로, 내 이름은 뺀다.
 * 프로젝트가 아니면(반복실행·정리 안 됨) 빈 목록 — 직접 적어 넣는다. 보조 정보라 못 읽어도 빈 목록
 */
export async function projectPeople(projectId: string | null): Promise<string[]> {
  if (!projectId) return []
  const [links, sources, settings] = await Promise.all([
    supabase.from('source_project_link').select('source_record_id,item_ref').eq('project_id', projectId).eq('status', 'confirmed'),
    supabase
      .from('task_revision')
      .select('task!inner(project_id),approval_event(change_proposal(analysis_run(transcript_version(source_record_id))))')
      .eq('actor', 'ai-approved')
      .eq('task.project_id', projectId),
    supabase.from('settings').select('my_names'),
  ])
  if (links.error || sources.error) {
    console.warn('load project people failed', links.error ?? sources.error)
    return []
  }
  const linked = (links.data as { source_record_id: string; item_ref: { scope?: string } | null }[]).filter((l) => l.item_ref?.scope === 'record').map((l) => l.source_record_id)
  const fromTasks = (
    sources.data as unknown as { approval_event: { change_proposal: { analysis_run: { transcript_version: { source_record_id: string } | null } | null } | null } | null }[]
  ).flatMap((r) => r.approval_event?.change_proposal?.analysis_run?.transcript_version?.source_record_id ?? [])
  const recordIds = [...new Set([...linked, ...fromTasks])]
  const names = await Promise.all(recordIds.map(recordPeople))
  const myNames = settings.error ? [] : ((settings.data as { my_names: string[] | null }[])[0]?.my_names ?? [])
  return uniquePeople(names.flat(), ['나', ...myNames])
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
