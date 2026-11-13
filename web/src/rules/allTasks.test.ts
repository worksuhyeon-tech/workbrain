import { describe, expect, it } from 'vitest'
import type { TaskRow } from '../data/types'
import { addedWhereText, dayInRange, groupTasks, inOwner, inRange } from './allTasks'
import { parseQuick } from './quickParse'

/** 모든 할 일 거르기·묶기(앱 AllTasksPane 동작, DEC-22·78). 이름은 가짜 */
const today = '2026-11-13'
const row = (id: string, o: Partial<TaskRow> = {}): TaskRow => ({
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
  project_id: null,
  area_id: null,
  recipients: [],
  parent_id: null,
  created_at: null,
  ...o,
})

describe('기간 탭', () => {
  it('다음 7일은 오늘부터 6일 뒤까지, 기한 없음은 빠짐', () => {
    expect(dayInRange('2026-11-13', 'next7', today)).toBe(true)
    expect(dayInRange('2026-11-19', 'next7', today)).toBe(true)
    expect(dayInRange('2026-11-20', 'next7', today)).toBe(false)
    expect(dayInRange('2026-11-12', 'next7', today)).toBe(false)
    expect(dayInRange(null, 'next7', today)).toBe(false)
  })

  it('지연은 어제까지, 끝낸 일은 지연이 아니다', () => {
    expect(inRange(row('a', { due_date: '2026-11-12' }), 'late', today)).toBe(true)
    expect(inRange(row('b', { due_date: '2026-11-13' }), 'late', today)).toBe(false)
    expect(inRange(row('c', { due_date: '2026-11-10', status: 'done' }), 'late', today)).toBe(false)
  })

  it('전체는 기한 없음·끝낸 일까지 다 든다', () => {
    expect(inRange(row('d'), 'all', today)).toBe(true)
    expect(inRange(row('e', { status: 'done' }), 'all', today)).toBe(true)
  })

  it('시각 있는 기한은 서울 날짜로 본다', () => {
    // 11/20 00:30(서울) = 11/19 15:30(UTC) → 다음 7일 밖
    expect(inRange(row('f', { due_time: '2026-11-19T15:30:00Z' }), 'next7', today)).toBe(false)
    expect(inRange(row('g', { due_time: '2026-11-19T14:30:00Z' }), 'next7', today)).toBe(true)
  })
})

describe('담당 탭', () => {
  it('내 할 일은 팔로업이 아닌 것(담당 확인 포함), 팔로업은 남이 맡은 것', () => {
    const follow = row('a', { owner_kind: 'other', assignee: '김가상' })
    const unknown = row('b', { owner_kind: 'unknown' })
    expect(inOwner(follow, 'follow')).toBe(true)
    expect(inOwner(follow, 'mine')).toBe(false)
    expect(inOwner(unknown, 'mine')).toBe(true)
    expect(inOwner(unknown, 'all')).toBe(true)
  })
})

describe('곳별 묶음', () => {
  const places = [
    { type: 'PROJECT' as const, id: 'p1', name: '01. 가상 ERP구축' },
    { type: 'PROJECT' as const, id: 'p2', name: '02. 가상 청구 개선' },
    { type: 'AREA' as const, id: 'a1', name: '가상 팀 운영' },
  ]

  it('정리 안 됨이 맨 위, 그다음 프로젝트·반복실행 순서, 빈 묶음은 뺀다', () => {
    const rows = [row('1', { area_id: 'a1' }), row('2', { project_id: 'p1' }), row('3'), row('4', { project_id: 'p1' })]
    const g = groupTasks(places, rows)
    expect(g.map((x) => x.place?.name ?? '정리 안 됨')).toEqual(['정리 안 됨', '01. 가상 ERP구축', '가상 팀 운영'])
    expect(g[1]!.rows.map((t) => t.id)).toEqual(['2', '4'])
  })

  it('보관되었거나 없어진 곳의 할 일은 정리 안 됨으로 본다', () => {
    const g = groupTasks(places, [row('x', { project_id: 'gone' })])
    expect(g).toHaveLength(1)
    expect(g[0]!.place).toBeNull()
  })
})

describe('빠른 입력 알림', () => {
  const quick = (text: string) => parseQuick(text, today, [], null)

  it('지금 탭에 보이면 덧붙이지 않는다', () => expect(addedWhereText(quick('내일 자료 정리'), 'next7', 'all', today)).toBe(''))
  it('다음 7일에서 기한 없이 넣으면 맨 아래 기한 없음', () =>
    expect(addedWhereText(quick('자료 정리'), 'next7', 'all', today)).toBe(' · 맨 아래 ‘기한 없음’에 있어요'))
  it('팔로업 탭에서 내 할 일을 넣으면 전체에서 보인다', () =>
    expect(addedWhereText(quick('내일 자료 정리'), 'next7', 'follow', today)).toBe(' · 지금 고른 탭 밖이라 ‘전체’에서 보여요'))
})
