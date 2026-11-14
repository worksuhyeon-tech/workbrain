import { describe, expect, it } from 'vitest'
import { askText, calendarNote, calendarPlan, connectResultText, dayLabel, eventTimes, returnTarget, todayLines, weekGroups, type CalEvent } from './calendar'

// 가짜 일정. 지금 = 2026-11-14(토) 10:20 서울
const NOW = new Date('2026-11-14T10:20:00+09:00')
const ev = (id: string, start: string, end: string, allDay = false): CalEvent => ({ id, title: id, start, end, allDay, link: null })
const at = (day: string, hm: string) => `${day}T${hm}:00+09:00`

describe('오늘 일정 카드', () => {
  const events = [
    ev('워크숍', '2026-11-14', '2026-11-15', true),
    ev('데일리', at('2026-11-14', '09:00'), at('2026-11-14', '09:30')),
    ev('리뷰', at('2026-11-14', '13:30'), at('2026-11-14', '14:30')),
    ev('점검', at('2026-11-14', '10:00'), at('2026-11-14', '11:00')),
    ev('통화', at('2026-11-14', '15:00'), at('2026-11-14', '15:30')),
    ev('정리', at('2026-11-14', '17:00'), at('2026-11-14', '17:30')),
  ]

  it('종일 → 지난 일정 접기 → 지금 → 남은 일정, 처음엔 3개', () => {
    const r = todayLines(events, NOW, false)
    expect(r.allDay.map((l) => l.e.id)).toEqual(['워크숍'])
    expect(r.past.map((l) => l.e.id)).toEqual(['데일리'])
    expect(r.rest.map((l) => [l.e.id, l.tone])).toEqual([
      ['점검', 'now'],
      ['리뷰', ''],
    ])
    expect(r.hidden).toBe(2)
  })

  it('펼치면 모두, 숨은 것 없음', () => {
    const r = todayLines(events, NOW, true)
    expect(r.rest.map((l) => l.e.id)).toEqual(['점검', '리뷰', '통화', '정리'])
    expect(r.hidden).toBe(0)
  })

  it('끝나는 순간에는 지난 일정, 시작하는 순간에는 지금', () => {
    const r = todayLines([ev('a', at('2026-11-14', '09:20'), at('2026-11-14', '10:20')), ev('b', at('2026-11-14', '10:20'), at('2026-11-14', '10:50'))], NOW, false)
    expect(r.past.map((l) => l.e.id)).toEqual(['a'])
    expect(r.rest.map((l) => [l.e.id, l.tone])).toEqual([['b', 'now']])
  })

  it('종일이 많으면 종일만으로 3개를 채운다', () => {
    const many = ['가', '나', '다', '라'].map((n) => ev(n, '2026-11-14', '2026-11-15', true))
    const r = todayLines([...many, ev('점검', at('2026-11-14', '10:00'), at('2026-11-14', '11:00'))], NOW, false)
    expect(r.allDay).toHaveLength(3)
    expect(r.rest).toHaveLength(0)
    expect(r.hidden).toBe(2)
  })

  it('시각은 서울로, 종일은 "종일"', () => {
    // UTC로 받은 시각도 서울 시각으로(01:00Z = 10:00 서울)
    expect(eventTimes(ev('x', '2026-11-14T01:00:00Z', '2026-11-14T02:30:00Z'))).toEqual({ start: '10:00', end: '11:30' })
    expect(eventTimes(ev('y', '2026-11-14', '2026-11-15', true))).toEqual({ start: '종일', end: null })
  })
})

describe('다음 7일 일정 카드', () => {
  const events = [
    ev('주간회의', at('2026-11-16', '09:30'), at('2026-11-16', '10:30')),
    ev('데일리', at('2026-11-14', '09:00'), at('2026-11-14', '09:30')),
    ev('워크숍', '2026-11-14', '2026-11-15', true),
    ev('출장', '2026-11-13', '2026-11-15', true), // 어제 시작해 오늘까지 → 오늘에
    ev('점검', at('2026-11-14', '10:00'), at('2026-11-14', '11:00')),
    ev('인터뷰', at('2026-11-19', '11:00'), at('2026-11-19', '12:00')),
    ev('월간', at('2026-11-20', '15:00'), at('2026-11-20', '16:00')),
  ]

  it('날짜별로 묶고 날마다 종일 먼저·시각순, 처음엔 5개', () => {
    const r = weekGroups(events, '2026-11-14', NOW, false)
    expect(r.total).toBe(7)
    expect(r.hidden).toBe(2)
    expect(r.groups.map((g) => [g.label, g.lines.map((l) => l.e.id)])).toEqual([
      ['오늘 · 11/14 (토)', ['출장', '워크숍', '데일리', '점검']],
      ['11/16 (월)', ['주간회의']],
    ])
  })

  it('오늘 끝난 일정은 흐리게(지금인 것은 강조하지 않음)', () => {
    const today = weekGroups(events, '2026-11-14', NOW, true).groups[0]!
    expect(today.lines.map((l) => l.tone)).toEqual(['', '', 'past', ''])
  })

  it('펼치면 모두', () => {
    const r = weekGroups(events, '2026-11-14', NOW, true)
    expect(r.hidden).toBe(0)
    expect(r.groups.map((g) => g.day)).toEqual(['2026-11-14', '2026-11-16', '2026-11-19', '2026-11-20'])
  })

  it('날짜 이름: 오늘·내일·요일', () => {
    expect(dayLabel('2026-11-15', '2026-11-14')).toBe('내일 · 11/15 (일)')
    expect(dayLabel('2026-11-30', '2026-11-14')).toBe('11/30 (월)')
  })
})

describe('서랍 캘린더 줄', () => {
  it('저장할 때 할 일', () => {
    expect(calendarPlan({ connected: true, linked: false, on: true, hasDate: true })).toBe('add')
    expect(calendarPlan({ connected: true, linked: false, on: true, hasDate: false })).toBe('none')
    expect(calendarPlan({ connected: false, linked: false, on: true, hasDate: true })).toBe('none')
    expect(calendarPlan({ connected: true, linked: true, on: true, hasDate: true })).toBe('keep')
    // 끄거나 기한을 지우면 묻는다(11/14 결정 ④)
    expect(calendarPlan({ connected: true, linked: true, on: false, hasDate: true })).toBe('ask')
    expect(calendarPlan({ connected: true, linked: true, on: true, hasDate: false })).toBe('ask')
    // 연결이 끊긴 동안은 건드리지 않는다
    expect(calendarPlan({ connected: false, linked: true, on: false, hasDate: false })).toBe('keep')
  })

  it('안내 문구', () => {
    expect(calendarNote({ connected: false, linked: false, on: false, hasDate: true, withTime: false })).toContain('설정에서 구글 캘린더를 연결')
    expect(calendarNote({ connected: true, linked: false, on: false, hasDate: false, withTime: false })).toBe('기한 날짜를 정하면 넣을 수 있어요.')
    expect(calendarNote({ connected: true, linked: false, on: true, hasDate: true, withTime: true })).toMatch(/^그 시각부터 1시간/)
    expect(calendarNote({ connected: true, linked: false, on: true, hasDate: true, withTime: false })).toMatch(/^종일 일정/)
    // 이미 넣은 일정은 캘린더에서 길이를 바꿨을 수 있어 1시간이라고 말하지 않는다
    expect(calendarNote({ connected: true, linked: true, on: true, hasDate: true, withTime: true })).toMatch(/^제목·기한을 고치면/)
    expect(calendarNote({ connected: true, linked: false, on: false, hasDate: true, withTime: false })).toBe('')
  })

  it('묻는 글', () => {
    expect(askText({ kind: 'save', title: '업체 미팅', hasDate: false })).toContain('기한을 지웠어요')
    expect(askText({ kind: 'delete', titles: ['업체 미팅'], total: 1 })).toMatch(/^‘업체 미팅’ 일정이/)
    expect(askText({ kind: 'delete', titles: ['업체 미팅'], total: 3 })).toMatch(/^지우는 할 일 3개 중 1개/)
  })
})

describe('연결', () => {
  it('돌아올 곳', () => {
    expect(returnTarget('localhost')).toBe('web-local')
    expect(returnTarget('workbrain-web.vercel.app')).toBe('web')
  })
  it('허락 결과 알림', () => {
    expect(connectResultText('ok')).toBe('구글 캘린더를 연결했어요')
    expect(connectResultText('wrong_account')).toContain('다른 Google 계정')
    expect(connectResultText('evil')).toBeNull()
    expect(connectResultText(null)).toBeNull()
  })
})
