import { describe, expect, it } from 'vitest'
import {
  canSearch,
  cleanQuery,
  KIND_PROPOSAL,
  KIND_RECORD,
  KIND_RESOURCE,
  KIND_TASK,
  matchRanges,
  metaDate,
  periodSince,
  relaxOptions,
  SEARCH_MAX,
  serverKinds,
  splitResults,
  targetOf,
  type SearchRow,
} from './search'

/** 앱 SearchQueryTest 예시 그대로(웹은 갈 곳을 주소로). 예시 이름은 모두 지어낸 것 */
const row = (kind: string, id: string, at: string | null, o: Partial<SearchRow> = {}): SearchRow => ({
  kind,
  id,
  title: `제목 ${id}`,
  snippet: null,
  record_id: null,
  record_title: null,
  project_id: null,
  area_id: null,
  place_name: null,
  at,
  draft: false,
  done: false,
  archived: false,
  audio_expired: false,
  ...o,
})

describe('검색(SearchQueryTest)', () => {
  it('검색어는 앞뒤 공백을 빼고 두 글자부터, 100자까지', () => {
    expect(cleanQuery('  개발 공수 \n')).toBe('개발 공수')
    expect(canSearch(' 청 ')).toBe(false)
    expect(canSearch('청약')).toBe(true)
    expect(cleanQuery('가'.repeat(150))).toHaveLength(SEARCH_MAX)
    // 100번째 글자가 이모지 앞 반쪽이면 그 반쪽은 뺀다
    expect(cleanQuery('가'.repeat(99) + '😀')).toHaveLength(99)
  })

  it('강조 구간은 대소문자를 무시하고 겹치지 않게 모두', () => {
    expect(matchRanges('CRM 이관 · crm 정리', 'crm')).toEqual([
      [0, 3],
      [9, 12],
    ])
    expect(matchRanges('가가가가', '가가')).toEqual([
      [0, 2],
      [2, 4],
    ])
    expect(matchRanges('청약 화면', '청')).toEqual([])
    expect(matchRanges('', '청약')).toEqual([])
  })

  it('기간 칩은 지금부터 30일·7일 전, 전체는 거르지 않음', () => {
    const now = new Date('2026-11-12T00:00:00Z')
    expect(periodSince('all', now)).toBeNull()
    expect(periodSince('30', now)?.toISOString()).toBe('2026-10-13T00:00:00.000Z')
    expect(periodSince('7', now)?.toISOString()).toBe('2026-11-05T00:00:00.000Z')
  })

  it('종류 칩 할 일에는 검토 대기 후보도 함께', () => {
    expect(serverKinds('all')).toBeNull()
    expect(serverKinds('task')).toEqual(['task', 'proposal'])
    expect(serverKinds('record')).toEqual(['record'])
    expect(serverKinds('resource')).toEqual(['resource'])
  })

  it('결과는 최근 것부터, 초안 회의록은 위에 함께 두고 할 일 후보만 아래로, 휴지통 기록과 그 후보는 뺀다', () => {
    const rows = [
      row(KIND_TASK, 't1', '2026-11-01T01:00:00+00:00'),
      row(KIND_RECORD, 'r1', '2026-11-10T01:00:00+00:00', { record_id: 'r1' }),
      row(KIND_RECORD, 'r2', '2026-11-11T01:00:00+00:00', { draft: true, record_id: 'r2' }),
      row(KIND_PROPOSAL, 'c1', '2026-11-09T01:00:00+00:00', { draft: true, record_id: 'trash' }),
      row(KIND_PROPOSAL, 'c2', '2026-11-08T01:00:00+00:00', { draft: true, record_id: 'r1' }),
      row(KIND_RECORD, 'trash', '2026-11-12T01:00:00+00:00', { record_id: 'trash' }),
      row(KIND_RESOURCE, 's1', null),
    ]
    const split = splitResults(rows, new Set(['trash']))
    expect(split.shown.map((r) => r.id)).toEqual(['r2', 'r1', 't1', 's1'])
    expect(split.candidates.map((r) => r.id)).toEqual(['c2'])
  })

  it('결과가 없을 때 넓히기 버튼은 걸린 필터와 접어 둔 할 일 후보만', () => {
    expect(relaxOptions('all', false, false, 0)).toEqual([])
    expect(relaxOptions('7', true, false, 2)).toEqual(['period', 'place', 'candidates'])
    expect(relaxOptions('30', false, true, 2)).toEqual(['period'])
  })

  it('누르면 갈 곳 - 할 일은 속한 곳 상세, 자료는 그 자료, 회의록과 후보는 기록 상세', () => {
    expect(targetOf(row(KIND_TASK, 't', null, { project_id: 'P1' }))).toBe('/projects/p:P1?task=t')
    expect(targetOf(row(KIND_TASK, 't', null, { area_id: 'A1' }))).toBe('/projects/a:A1?task=t')
    expect(targetOf(row(KIND_TASK, 't', null))).toBe('/projects?task=t')
    expect(targetOf(row(KIND_RESOURCE, 'S1', null, { project_id: 'P1' }))).toBe('/projects/r:S1')
    expect(targetOf(row(KIND_PROPOSAL, 'c', null, { record_id: 'R1', record_title: '주간 합성 회의' }))).toBe('/records/R1')
    expect(targetOf(row(KIND_RECORD, 'R2', null, { record_id: 'R2' }))).toBe('/records/R2')
    expect(targetOf(row(KIND_RECORD, 'R3', null))).toBeNull()
  })

  it('메타 날짜는 서울 날짜, 할 일과 자료는 만든 날', () => {
    // UTC 11/10 16시 = 서울 11/11 01시
    expect(metaDate(row(KIND_RECORD, 'r', '2026-11-10T16:00:00+00:00'))).toBe('11/11')
    expect(metaDate(row(KIND_TASK, 't', '2026-11-10T16:00:00+00:00'))).toBe('11/11 만듦')
    expect(metaDate(row(KIND_RESOURCE, 's', null))).toBeNull()
  })
})
