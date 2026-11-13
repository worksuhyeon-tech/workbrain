import { describe, expect, it } from 'vitest'
import {
  candidateDueKind,
  candidateDueLabel,
  candidateEdits,
  candidateValid,
  checkedAtFirst,
  decisionTaskEdit,
  followNamesOf,
  withPlace,
  changeLines,
  clockText,
  dayLabel,
  decisionsOf,
  durationText,
  fullTimeText,
  mdOf,
  processStep,
  snoozeActive,
  snoozeLabel,
  groupByDay,
  inFilter,
  markedParts,
  meetingTimeText,
  overlappingDecision,
  proposalSegments,
  recordProjectIds,
  recordStage,
  speakerTurns,
  stageChip,
  stageNote,
  textsOf,
  type ProposalBrief,
} from './records'

const DONE = { stage: 'REVIEWABLE', stage_state: 'DONE' }
const p = (status: string, target_type = 'task', target_id: string | null = null): ProposalBrief => ({ status, target_type, target_id })

describe('기록 목록 상태', () => {
  it('처리 실패 · 처리 중 · 정리 필요 · 정리 끝', () => {
    expect(recordStage({ stage: 'STT', stage_state: 'FAILED' }, [])).toBe('failed')
    expect(recordStage(null, [])).toBe('processing')
    expect(recordStage({ stage: 'EXTRACT', stage_state: 'RUNNING' }, [p('pending')])).toBe('processing')
    expect(recordStage(DONE, [p('approved'), p('pending')])).toBe('review')
    expect(recordStage(DONE, [p('approved'), p('rejected')])).toBe('done')
  })

  it('처리 중 칩은 지금 단계로', () => {
    expect(stageChip('processing', null).text).toBe('올리는 중')
    expect(stageChip('processing', { stage: 'STT', stage_state: 'RUNNING' }).text).toBe('전사 중')
    expect(stageChip('processing', { stage: 'EXTRACT', stage_state: 'PENDING' }).text).toBe('분석 중')
    expect(stageChip('processing', { stage: 'EXTRACT', stage_state: 'BUDGET_WAIT' })).toEqual({ text: '예산 대기', tone: 'warn' })
    expect(stageChip('failed', null)).toEqual({ text: '처리 실패', tone: 'crit' })
  })

  it('덧말: 새 후보와 변경(기존 할 일·현재 상태)을 나눠 센다, 끝났으면 추가한 수', () => {
    const rows = [p('pending'), p('pending'), p('pending', 'task', 't1'), p('pending', 'project_state', 'p1'), p('approved')]
    expect(stageNote('review', rows)).toBe('할 일 후보 2 · 변경 2')
    expect(stageNote('review', [p('pending', 'task', 't1')])).toBe('변경 1')
    expect(stageNote('done', [p('approved'), p('approved'), p('approved', 'task', 't1'), p('rejected')])).toBe('할 일 2 추가됨')
    expect(stageNote('done', [p('rejected')])).toBe('회의록만 보관')
    expect(stageNote('processing', [])).toBeNull()
  })

  it('처리 중 탭에는 실패도 들어간다', () => {
    expect(inFilter('failed', 'processing')).toBe(true)
    expect(inFilter('failed', 'review')).toBe(false)
    expect(inFilter('done', 'all')).toBe(true)
  })

  it('기록 전체 확정 연결만 프로젝트 칩으로', () => {
    expect(
      recordProjectIds([
        { project_id: 'a', status: 'confirmed', item_ref: { scope: 'record' } },
        { project_id: 'a', status: 'confirmed', item_ref: { scope: 'record' } },
        { project_id: 'b', status: 'proposed', item_ref: { scope: 'record' } },
        { project_id: 'c', status: 'confirmed', item_ref: { scope: 'item' } },
      ]),
    ).toEqual(['a'])
  })
})

describe('날짜·길이 글자', () => {
  it('길이(앱 durationText)', () => {
    expect(durationText(42 * 60)).toBe('42분')
    expect(durationText(3600 + 47 * 60 + 5)).toBe('1시간 47분')
    expect(durationText(7200)).toBe('2시간')
    expect(durationText(30)).toBe('1분 미만')
  })

  it('서울 날짜 묶음, 최신 먼저, 시각 없는 기록은 끝', () => {
    const rows = [
      { id: 'a', at: '2026-10-16T06:00:00Z' },
      { id: 'b', at: '2026-10-16T16:30:00Z' }, // 서울 10/17 01:30
      { id: 'c', at: null },
      { id: 'd', at: '2026-10-16T00:30:00Z' },
    ]
    const g = groupByDay(rows, (r) => r.at)
    expect(g.map((x) => [x.day, x.rows.map((r) => r.id)])).toEqual([
      ['2026-10-17', ['b']],
      ['2026-10-16', ['a', 'd']],
      [null, ['c']],
    ])
    expect(dayLabel('2026-10-17')).toBe('10/17 (토)')
    expect(meetingTimeText('2026-10-16T06:00:00Z')).toBe('10/16 15:00')
    expect(meetingTimeText(null)).toBeNull()
    expect(mdOf('2026-10-16T16:30:00Z')).toBe('10/17')
    expect(fullTimeText('2026-10-16T06:00:00Z')).toBe('2026-10-16 (금) 15:00')
  })

  it('미루기·처리 단계', () => {
    const now = new Date('2026-11-13T12:00:00Z')
    expect(snoozeActive('2026-11-16T15:00:00Z', now)).toBe(true)
    expect(snoozeActive('2026-11-12T15:00:00Z', now)).toBe(false)
    expect(snoozeActive(null, now)).toBe(false)
    expect(snoozeLabel('2026-11-16T15:00:00Z')).toBe('11/17(화)')
    expect(processStep(null)).toBe(0)
    expect(processStep({ stage: 'STT', stage_state: 'RUNNING' })).toBe(1)
    expect(processStep({ stage: 'EXTRACT', stage_state: 'FAILED' })).toBe(3)
    expect(processStep({ stage: 'REVIEWABLE', stage_state: 'DONE' })).toBe(4)
  })
})

describe('기록 상세', () => {
  const fields = {
    decisions: [{ text: '개선안 확정 후 리뷰 착수', segments: [3, 4] }, { text: '  ' }, { text: '업체는 다음 주에', segments: [9] }],
    open_questions: [{ text: '판독 솔루션 업체' }, { text: '' }],
  }

  it('결정 후보·정하지 못한 것(빈 줄 뺌)', () => {
    expect(decisionsOf(fields).map((d) => d.text)).toEqual(['개선안 확정 후 리뷰 착수', '업체는 다음 주에'])
    expect(textsOf(fields, 'open_questions')).toEqual(['판독 솔루션 업체'])
    expect(decisionsOf(null)).toEqual([])
  })

  it('근거가 결정과 겹치는 후보는 그 결정 번호(DEC-48)', () => {
    const decisions = decisionsOf(fields)
    expect(overlappingDecision(proposalSegments([{ segment_ref: { idx: [9, 10] } }]), decisions)).toBe(1)
    expect(overlappingDecision(proposalSegments([{ segment_ref: { idx: [20] } }, { segment_ref: null }]), decisions)).toBeNull()
  })

  it('화자 차례: 같은 화자·시각을 잇고, 화자가 없으면 빈 목록', () => {
    expect(
      speakerTurns([
        { idx: 0, text: '안녕하세요.', speaker: '화자 1', offsetMs: 0 },
        { idx: 1, text: '시작할게요.', speaker: '화자 1', offsetMs: 0 },
        { idx: 2, text: '네.', speaker: '화자 2', offsetMs: 3000 },
      ]),
    ).toEqual([
      { speaker: '화자 1', offsetMs: 0, text: '안녕하세요. 시작할게요.' },
      { speaker: '화자 2', offsetMs: 3000, text: '네.' },
    ])
    expect(speakerTurns([{ idx: 0, text: '그냥 글' }])).toEqual([])
    expect(clockText(3000)).toBe('00:03')
    expect(clockText(3723000)).toBe('1:02:03')
  })

  it('회의록 표기 [확인 필요]·[미정]', () => {
    expect(markedParts('담당 [확인 필요] · 기한 [미정]')).toEqual([
      { text: '담당 ', mark: null },
      { text: '[확인 필요]', mark: 'crit' },
      { text: ' · 기한 ', mark: null },
      { text: '[미정]', mark: 'muted' },
    ])
  })

  it('기존 할 일 변경: 달라진 칸만 전후로', () => {
    const before = { title: '요건서 범위 잡기', owner_kind: 'me', due_date: '2026-10-20' }
    const after = { title: '요건서 범위 잡기', owner_kind: 'other', assignee: '김하늘', due_date: '2026-10-23' }
    expect(changeLines(before, after)).toEqual([
      { label: '담당', from: '내 할 일', to: '팔로업 · 김하늘' },
      { label: '기한', from: '10/20(화)', to: '10/23(금)' },
    ])
    // 전 값이 없는 옛 제안은 들어 있는 칸만
    expect(changeLines(null, { due_date: '2026-10-23' })).toEqual([{ label: '기한', from: null, to: '10/23(금)' }])
  })

  it('후보 기한(앱 dueLabel): 시각·날짜·모호·없음', () => {
    expect(candidateDueLabel({ due_time: '2026-10-23T06:00:00Z' })).toBe('10/23(금) 15:00')
    expect(candidateDueLabel({ due_date: '2026-10-23' })).toBe('10/23(금)')
    expect(candidateDueLabel({ due_ambiguous: true, due_text: '다음 주' })).toBe('다음 주 · 확인 필요')
    expect(candidateDueLabel({})).toBeNull()
  })
})

describe('후보 고치기·추가(앱 CandidateEditSheet·AddPlace)', () => {
  const ai = { title: '흐름도 공유', owner_kind: 'other', assignee: '김가상', owner_basis: 'speaker', due_date: '2026-11-17', due_time: '2026-11-17T06:00:00Z' }

  it('날짜를 그대로 두면 시각을 지키고, 바꾸면 버린다', () => {
    const same = candidateEdits(ai, { title: '흐름도 공유', owner: 'other', assignee: '김가상', due: 'date', date: '2026-11-17' })
    expect(same).toMatchObject({ due_date: '2026-11-17', due_time: '2026-11-17T06:00:00Z', owner_basis: 'speaker' })
    const moved = candidateEdits(ai, { title: '흐름도 공유', owner: 'other', assignee: '김가상', due: 'date', date: '2026-11-18' })
    expect(moved).toMatchObject({ due_date: '2026-11-18', due_time: null })
  })

  it('담당을 바꾸면 말한 사람 기준을 지우고, 나면 이름을 비운다', () => {
    expect(candidateEdits(ai, { title: '흐름도 공유', owner: 'me', assignee: '김가상', due: 'none', date: '' })).toMatchObject({
      owner_kind: 'me', assignee: null, owner_basis: null, due_date: null, due_ambiguous: false,
    })
  })

  it('모호한 기한은 글 그대로', () => {
    const vague = { title: '회신', owner_kind: 'me', due_ambiguous: true, due_text: '다음 주 초' }
    expect(candidateDueKind(vague)).toBe('vague')
    expect(candidateEdits(vague, { title: '회신', owner: 'me', assignee: '', due: 'vague', date: '' })).toMatchObject({ due_ambiguous: true, due_text: '다음 주 초' })
  })

  it('적용 가능: 제목·다른 사람 이름·날짜', () => {
    expect(candidateValid(' ', 'me', '', 'none', '')).toBe(false)
    expect(candidateValid('a', 'other', ' ', 'none', '')).toBe(false)
    expect(candidateValid('a', 'me', '', 'date', '')).toBe(false)
    expect(candidateValid('a', 'other', '김', 'date', '2026-11-17')).toBe(true)
  })

  it('추가할 곳: 정리 안 됨이면 두 키를 비워 AI가 적은 곳으로 새지 않게', () => {
    expect(withPlace({ title: 'x', project_id: 'ai' }, null)).toEqual({ title: 'x', project_id: null, area_id: null })
    expect(withPlace(undefined, { type: 'AREA', id: 'a1' })).toEqual({ project_id: null, area_id: 'a1' })
  })

  it('팔로업 목록: 참석자 + AI가 짚은 사람, 내 이름 뺌', () => {
    expect(followNamesOf(['나', '김가상', ' 박예시 '], ['김가상', null, '이예시'], ['나'])).toEqual(['김가상', '박예시', '이예시'])
  })

  it('처음 체크: 결정과 겹치거나 아직 미룬 후보는 빼 둔다', () => {
    const now = new Date('2026-11-13T12:00:00Z')
    expect(checkedAtFirst(false, null, now)).toBe(true)
    expect(checkedAtFirst(true, null, now)).toBe(false)
    expect(checkedAtFirst(false, '2026-11-16T15:00:00Z', now)).toBe(false)
    expect(checkedAtFirst(false, '2026-11-10T15:00:00Z', now)).toBe(true)
  })

  it('결정 → 새 할 일은 task 칸만(후보 전용 owner_basis는 뺌)', () => {
    const e = candidateEdits({ title: '흐름도 공유', owner_kind: 'me' }, { title: '흐름도 공유', owner: 'other', assignee: '김가상', due: 'date', date: '2026-11-17' })
    const t = decisionTaskEdit(e, { type: 'PROJECT', id: 'p1' })
    expect(t).toEqual({
      title: '흐름도 공유', priority: null, owner_kind: 'other', assignee: '김가상', due_date: '2026-11-17', due_time: null,
      due_ambiguous: false, due_text: null, project_id: 'p1', area_id: null, recipients: [],
    })
    expect('owner_basis' in t).toBe(false)
  })
})
