import { describe, expect, it } from 'vitest'
import type { TaskRow } from '../data/types'
import { deletedText, dueChipText, groupToday, isEarlierDone, movedManyText, needsMove, placeFields, quickToEdit, taskComparator } from './tasks'
import { parseQuick } from './quickParse'

/** 앱 TaskSelectTest·EarlierDoneTest 예시를 그대로 옮기고, 오늘 묶기·기한 칩을 더했다(DEC-102) */
const row = (id: string, o: Partial<TaskRow> = {}): TaskRow => ({
  id,
  title: `할 일 ${id}`,
  status: 'open',
  version: 0,
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

describe('정렬(TaskSelectTest)', () => {
  const urgentLate = row('a', { priority: 1, due_date: '2026-11-20' })
  const normalSoon = row('b', { priority: 2, due_date: '2026-11-12' })
  const lowTimed = row('c', { priority: 3, due_time: '2026-11-12T09:00:00+09:00' })
  const noDue = row('d', { priority: 1 })

  it('우선순위순은 높음 먼저, 같은 우선순위 안에서 기한 순', () => {
    const sorted = [lowTimed, normalSoon, noDue, urgentLate].sort(taskComparator('priority'))
    expect(sorted.map((t) => t.id)).toEqual(['a', 'd', 'b', 'c'])
  })

  it('기한순은 이른 날 먼저, 같은 날은 시각 있는 일이 먼저, 기한 없음은 맨 뒤', () => {
    const sorted = [noDue, urgentLate, normalSoon, lowTimed].sort(taskComparator('due'))
    expect(sorted.map((t) => t.id)).toEqual(['c', 'b', 'a', 'd'])
  })

  it('옮긴 뒤·지운 뒤 알림 문구', () => {
    const to = { type: 'PROJECT' as const, id: 'p1', name: '가상 프로젝트' }
    expect(movedManyText({ moved: 2, conflicts: 0, failed: 0 }, to)).toBe('할 일 2개를 ‘가상 프로젝트’(으)로 옮겼어요')
    expect(movedManyText({ moved: 1, conflicts: 1, failed: 0 }, null)).toBe('할 일 1개를 ‘정리 안 됨’(으)로 옮겼어요 · 1개는 다른 기기에서 먼저 바뀌어 건너뜀')
    expect(deletedText([urgentLate])).toBe('할 일 a 삭제되었어요')
    expect(deletedText([urgentLate, normalSoon])).toBe('할 일 2개 삭제되었어요')
    expect(deletedText([urgentLate], 2)).toBe('할 일 a 삭제되었어요 · 하위 2개 포함')
  })

  it('이동: 이미 그곳에 있는 할 일은 건너뛰고, 정리 안 됨으로도 옮긴다', () => {
    const project = { type: 'PROJECT' as const, id: 'p1', name: '가상 프로젝트' }
    const area = { type: 'AREA' as const, id: 'a1', name: '가상 반복실행' }
    const inProject = row('x', { project_id: 'p1' })
    expect(needsMove(inProject, project)).toBe(false)
    expect(needsMove(inProject, area)).toBe(true)
    expect(needsMove(inProject, null)).toBe(true)
    expect(needsMove(row('y'), null)).toBe(false)
    expect(placeFields(area)).toEqual({ project_id: null, area_id: 'a1' })
    expect(placeFields(null)).toEqual({ project_id: null, area_id: null })
  })
})

describe('이전 완료(EarlierDoneTest)', () => {
  const today = '2026-11-08'
  const task = (status: string, doneAt: string | null = null) => row('t1', { title: '보험금 지급 심사표 정리', status, done_at: doneAt })

  it('안 끝난 할 일은 접지 않는다', () => expect(isEarlierDone(task('open'), today)).toBe(false))
  it('오늘 끝낸 할 일은 목록에 남긴다', () => expect(isEarlierDone(task('done', '2026-11-08T03:00:00+00:00'), today)).toBe(false))
  it('어제 끝낸 할 일은 접는다', () => expect(isEarlierDone(task('done', '2026-11-07T03:00:00+00:00'), today)).toBe(true))
  it('서울 자정 직후(UTC로는 전날 15시)에 끝낸 것은 오늘 완료', () => expect(isEarlierDone(task('done', '2026-11-07T15:00:00+00:00'), today)).toBe(false))
  it('서울 자정 직전(UTC로는 전날 14시 59분)에 끝낸 것은 이전 완료', () =>
    expect(isEarlierDone(task('done', '2026-11-07T14:59:59.5+00:00'), today)).toBe(true))
  it('완료 시각이 없는 옛 완료는 접는다', () => expect(isEarlierDone(task('done', null), today)).toBe(true))
})

describe('오늘 묶기·기한 칩', () => {
  const today = '2026-11-12'

  it('지연·오늘은 내 일만, 팔로업은 따로, 기한 없음·내일 이후·모호는 빠짐', () => {
    const tasks = [
      row('late', { due_date: '2026-11-10' }),
      row('today', { due_date: today }),
      row('timed', { due_time: '2026-11-12T22:00:00+09:00' }),
      row('follow', { due_date: today, owner_kind: 'other', assignee: '박지훈' }),
      row('tomorrow', { due_date: '2026-11-13' }),
      row('none'),
      row('vague', { due_ambiguous: true, due_text: '다음 주' }),
      row('done', { status: 'done', due_date: today, done_at: '2026-11-12T01:00:00Z' }),
    ]
    const g = groupToday(tasks, today, 'priority')
    expect(g.late.map((t) => t.id)).toEqual(['late'])
    expect(g.todays.map((t) => t.id)).toEqual(['timed', 'today'])
    expect(g.follow.map((t) => t.id)).toEqual(['follow'])
    expect(g.doneToday.map((t) => t.id)).toEqual(['done'])
  })

  it('기한 칩 글자', () => {
    expect(dueChipText(row('a', { due_date: today }), today)).toBe('오늘')
    expect(dueChipText(row('a', { due_date: '2026-11-11' }), today)).toBe('지연 · 11/11')
    expect(dueChipText(row('a', { due_time: '2026-11-09T06:00:00Z' }), today)).toBe('지연 · 11/9 15:00')
    expect(dueChipText(row('a'), today)).toBe('기한 없음')
    expect(dueChipText(row('a', { due_ambiguous: true, due_text: '다음 주' }), today)).toBe('다음 주 · 확인 필요')
  })

  it('빠른 입력 결과 → 저장 값(시각은 서울 +09:00, 담당이 있으면 팔로업)', () => {
    const place = { type: 'AREA' as const, id: 'a1', name: '심사' }
    const e = quickToEdit(parseQuick('내일 3시 자료 요청 @박지훈', '2026-11-06', [place], place))
    expect(e).toMatchObject({ title: '자료 요청', due_date: '2026-11-07', due_time: '2026-11-07T15:00+09:00', owner_kind: 'other', assignee: '박지훈', area_id: 'a1', project_id: null })
  })
})
