import { describe, expect, it } from 'vitest'
import { approveMeetingText, minutesFileName, minutesVerdict, NETWORK_ERROR, RETRY_ERROR } from './minutes'

/** 앱 MinutesVerdictTest 예시 그대로 + 파일 이름·승인 문구 */
describe('회의록 만들기 결과 판단(MinutesVerdictTest)', () => {
  it('서버가 다 만들었으면 실패가 아니라 만들어짐', () => {
    expect(minutesVerdict('DONE', true)).toEqual({ kind: 'made' })
    expect(minutesVerdict('DONE', false)).toEqual({ kind: 'made' })
  })

  it('만드는 중이거나 확인을 못 하면 더 기다린다', () => {
    expect(minutesVerdict('RUNNING', true)).toEqual({ kind: 'wait' })
    expect(minutesVerdict(null, true)).toEqual({ kind: 'wait' })
  })

  it('요청이 서버에 안 닿았으면 연결 문구, 서버가 실패했으면 다시 시도 문구', () => {
    expect(minutesVerdict('NONE', true)).toEqual({ kind: 'failed', message: NETWORK_ERROR })
    expect(minutesVerdict('NONE', false)).toEqual({ kind: 'failed', message: RETRY_ERROR })
    expect(minutesVerdict('FAILED', true)).toEqual({ kind: 'failed', message: RETRY_ERROR })
  })

  it('다른 요청이 만드는 중이면 기다리지 않고 알린다', () => {
    expect(minutesVerdict('BUSY', true)).toEqual({ kind: 'failed', message: '이미 회의록을 만드는 중이에요. 다 되면 화면에 보여요' })
  })
})

describe('Word·승인', () => {
  it('파일 이름은 ASCII, 회의 날짜가 없으면 날짜 없이', () => {
    expect(minutesFileName('2026-11-12')).toBe('meeting_minutes_20261112.docx')
    expect(minutesFileName('')).toBe('meeting_minutes.docx')
  })

  it('승인 결과 문구', () => {
    expect(approveMeetingText('approved')).toContain('승인했어요')
    expect(approveMeetingText('stale-version')).toContain('새로 만들어졌어요')
    expect(approveMeetingText(null)).toContain('인터넷 연결')
  })
})
