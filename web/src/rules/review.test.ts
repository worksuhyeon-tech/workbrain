import { describe, expect, it } from 'vitest'
import { evidenceUntil, groupByRecord, initRecords, lastAllowed, nextMonday, retentionNote, snoozeText, stateDecisionText, stateStale } from './review'

describe('검토 미루기(앱 Snooze·SnoozeSheet)', () => {
  it('다음 주 월요일: 월요일이어도 다음 주, 일요일이면 곧 내일', () => {
    expect(nextMonday('2026-11-16')).toBe('2026-11-23') // 월
    expect(nextMonday('2026-11-13')).toBe('2026-11-16') // 금
    expect(nextMonday('2026-11-15')).toBe('2026-11-16') // 일
  })

  it('마지막 날: 1년 뒤와 근거 보관 끝나기 전날 중 이른 날', () => {
    const today = '2026-11-13'
    expect(lastAllowed([], today)).toBe('2027-11-13')
    // 서울 11/30에 끝나는 근거 → 11/29까지
    expect(lastAllowed(['2026-11-30T03:00:00Z', null, '2026-12-20T00:00:00Z'], today)).toBe('2026-11-29')
    expect(evidenceUntil(['2026-12-20T00:00:00Z', '2026-11-30T03:00:00Z'])).toBe('2026-11-30')
  })

  it('보관 기한 안내: 근거 · 음성(지났거나 지웠으면 끝남), 음성 없는 기록은 근거만', () => {
    const now = new Date('2026-11-13T12:00:00Z')
    expect(retentionNote('2026-11-30T03:00:00Z', { retention_expires_at: '2026-11-20T03:00:00Z', purged_at: null }, now)).toBe('근거 보관 11/30(월)까지 · 음성 11/20(금)까지')
    expect(retentionNote('2026-11-30T03:00:00Z', { retention_expires_at: '2026-11-01T00:00:00Z', purged_at: null }, now)).toBe('근거 보관 11/30(월)까지 · 음성 보관 끝남')
    expect(retentionNote('2026-11-30T03:00:00Z', null, now)).toBe('근거 보관 11/30(월)까지')
    expect(retentionNote(null, null, now)).toBeNull()
  })

  it('알림 문구', () => {
    expect(snoozeText(2, '2026-11-16')).toBe('후보 2개를 11/16(월)까지 미뤘어요 · 그날 ‘오늘’에 다시 나와요')
    expect(snoozeText(1, null)).toBe('후보 1개를 오늘 검토로 가져왔어요')
    expect(snoozeText(null, null)).toContain('인터넷 연결')
    expect(stateDecisionText('conflict')).toContain('반영하지 않았어요')
  })
})

describe('기록별 묶음', () => {
  it('처음 나온 순서대로, 기록 없는 후보는 한 묶음', () => {
    const rows = [
      { id: 'a', rec: 'r1' },
      { id: 'b', rec: 'r2' },
      { id: 'c', rec: 'r1' },
      { id: 'd', rec: null },
    ]
    const g = groupByRecord(rows, (r) => ({ id: r.rec, title: r.rec ?? '제목 없는 기록' }))
    expect(g.map((x) => [x.id, x.rows.map((r) => r.id)])).toEqual([
      ['r1', ['a', 'c']],
      ['r2', ['b']],
      [null, ['d']],
    ])
  })
})

describe('프로젝트 현재 상태 제안(앱 StateCard·StateWorker)', () => {
  it('정리할 회의: 최근 3개를 오래된 것부터, 시각 없는 기록은 가장 오래된 것으로', () => {
    const linked = [
      { id: 'a', at: '2026-11-01T00:00:00Z' },
      { id: 'b', at: '2026-11-10T00:00:00Z' },
      { id: 'c', at: null },
      { id: 'd', at: '2026-11-12T00:00:00Z' },
      { id: 'e', at: '2026-11-05T00:00:00Z' },
    ]
    expect(initRecords(linked)).toEqual(['e', 'b', 'd'])
    expect(initRecords([])).toEqual([])
  })

  it('기준 버전이 다르면 반영해도 충돌, 기준이 없으면(처음 상태) 아님', () => {
    expect(stateStale(2, 3)).toBe(true)
    expect(stateStale(3, 3)).toBe(false)
    expect(stateStale(null, 3)).toBe(false)
  })

  it('카드와 검토함 문구', () => {
    expect(stateDecisionText('rejected', 'project')).toContain('기록을 또 연결하면')
    expect(stateDecisionText('rejected')).toBe('현재 상태 제안을 넘겼어요')
    expect(stateDecisionText('evidence-missing', 'project')).toContain('만료')
  })
})

