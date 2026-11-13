import { isDone, type TaskEdit, type TaskRow } from '../data/types'
import type { LocalDate } from './dates'
import { parseQuick } from './quickParse'
import { quickToEdit } from './tasks'

/*
 * 하위 할 일 규칙(FR-23a, 앱 tasks/Subtasks.kt·data/Para.kt를 옮김 — 고치면 두 곳을 같이, DEC-102)
 */

export interface SubCount {
  done: number
  total: number
}

/** 상위 줄 '⤷ 1/3' 칩. 하위가 없으면 칩 없음 */
export function subCountOf(children: TaskRow[]): SubCount | null {
  return children.length === 0 ? null : { done: children.filter(isDone).length, total: children.length }
}

/** 하위 줄 순서: 안 끝난 것 먼저(만든 순서), 끝난 것은 뒤 */
export function subtaskOrder(children: TaskRow[]): TaskRow[] {
  return [...children].sort((a, b) => Number(isDone(a)) - Number(isDone(b)) || (a.created_at ?? '').localeCompare(b.created_at ?? ''))
}

/** 이 할 일의 하위 할 일(만든 순서) */
export function subtasksOf(tasks: TaskRow[], parentId: string): TaskRow[] {
  return tasks.filter((t) => t.parent_id === parentId).sort((a, b) => (a.created_at ?? '').localeCompare(b.created_at ?? ''))
}

/**
 * 하위 할 일 한 줄 입력 해석: 빠른 입력과 같은 규칙으로 기한·@담당만 읽는다.
 * 곳('#')·우선순위('!')는 상위를 따르므로 쓰지 않는다. 제목이 비면 null
 */
export function parseSubtask(text: string, today: LocalDate): TaskEdit | null {
  const q = parseQuick(text, today, [], null)
  if (!q.title.trim()) return null
  return { ...quickToEdit(q), priority: null, project_id: null, area_id: null }
}

/**
 * 지우는 중인 할 일을 뺀 목록(앱 ParaData.without). 서버에서 지우기 전 3초 동안에도 화면에서 바로 사라지게 한다.
 * 상위를 지우면 하위도 함께 지워지므로 그 하위도 뺀다
 */
export function without(tasks: TaskRow[], ids: ReadonlySet<string>): TaskRow[] {
  if (ids.size === 0) return tasks
  return tasks.filter((t) => !ids.has(t.id) && !(t.parent_id !== null && ids.has(t.parent_id)))
}

/** 서랍에 채울 지금 값 */
export function toEdit(t: TaskRow): TaskEdit {
  return {
    title: t.title,
    priority: t.priority,
    owner_kind: t.owner_kind,
    assignee: t.assignee,
    due_date: t.due_date,
    due_time: t.due_time,
    due_ambiguous: t.due_ambiguous,
    due_text: t.due_text,
    project_id: t.project_id,
    area_id: t.area_id,
    recipients: t.recipients ?? [],
  }
}
