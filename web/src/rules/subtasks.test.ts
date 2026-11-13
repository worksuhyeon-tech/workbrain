import { describe, expect, it } from 'vitest'
import type { TaskRow } from '../data/types'
import { parseSubtask, subCountOf, subtaskOrder, subtasksOf, without } from './subtasks'
import { deletedText } from './tasks'

/** 앱 SubtaskTest 예시를 그대로 옮겼다(DEC-102). 예시는 모두 지어낸 것 */
const today = '2026-11-12'
const task = (id: string, o: Partial<TaskRow> = {}): TaskRow => ({
  id,
  title: `할 일 ${id}`,
  status: 'open',
  version: 1,
  owner_kind: 'me',
  assignee: null,
  due_date: null,
  due_time: null,
  due_ambiguous: false,
  due_text: null,
  done_at: null,
  priority: null,
  project_id: 'p1',
  area_id: null,
  recipients: [],
  parent_id: null,
  created_at: `2026-11-0${id.slice(-1)}T00:00:00Z`,
  ...o,
})
const parent = task('t1')
const kidA = task('t2', { parent_id: 't1', status: 'done' })
const kidB = task('t3', { parent_id: 't1' })
const other = task('t4')
const all = [parent, kidA, kidB, other]

describe('하위 할 일(SubtaskTest)', () => {
  it('하위는 상위 아래에서만, 만든 순서', () => {
    expect(subtasksOf(all, 't1').map((t) => t.id)).toEqual(['t2', 't3'])
  })

  it('하위 개수 칩 - 끝난 수와 전체, 없으면 칩 없음', () => {
    expect(subCountOf(subtasksOf(all, 't1'))).toEqual({ done: 1, total: 2 })
    expect(subCountOf([])).toBeNull()
  })

  it('하위 줄 순서 - 안 끝난 것 먼저, 그 안에서 만든 순서', () => {
    expect(subtaskOrder([kidA, kidB]).map((t) => t.id)).toEqual(['t3', 't2'])
  })

  it('하위 한 줄 입력 - 기한과 @담당을 읽고, 곳·우선순위는 상위를 따라 비운다', () => {
    const e = parseSubtask('내일 오류 문구 수정 @박지훈 !높음', today)!
    expect(e.title).toBe('오류 문구 수정')
    expect(e.due_date).toBe('2026-11-13')
    expect(e.owner_kind).toBe('other')
    expect(e.assignee).toBe('박지훈')
    expect(e.priority).toBeNull()
    expect(e.project_id).toBeNull()
    expect(e.area_id).toBeNull()
    expect(parseSubtask('   ', today)).toBeNull()
  })

  it('지우는 중 - 하위 하나만 빼면 상위·다른 하위는 그대로, 상위를 빼면 그 하위도 빠짐', () => {
    expect(subtasksOf(without(all, new Set(['t3'])), 't1').map((t) => t.id)).toEqual(['t2'])
    expect(without(all, new Set(['t1'])).map((t) => t.id)).toEqual(['t4'])
    expect(without(all, new Set())).toBe(all)
  })

  it('상위를 지우면 알림에 하위 수', () => {
    expect(deletedText([parent], 2)).toBe('할 일 t1 삭제되었어요 · 하위 2개 포함')
    expect(deletedText([other])).toBe('할 일 t4 삭제되었어요')
  })
})
