/*
 * 회의록 만들기·승인·Word(FR-29·30·07) 규칙. 앱 review/MinutesWorker.kt·MinutesShare.kt·RecordDetailScreen.approveMinutes를 옮겼다(고치면 두 곳을 같이, DEC-102)
 */

export const NETWORK_ERROR = '회의록을 만들지 못했어요. 인터넷 연결을 확인해 주세요'
export const RETRY_ERROR = '회의록을 만들지 못했어요. 다시 시도해 주세요'

export type MinutesVerdict = { kind: 'wait' } | { kind: 'made' } | { kind: 'failed'; message: string }

/**
 * wb_document_status 값으로 다음 걸음을 정한다(앱 minutesVerdict). lost = 처음 요청의 응답을 못 받음.
 * NONE은 내 요청이 서버에 닿지 않은 것(응답을 못 받았으면 연결 문제), 확인 실패(null)·RUNNING은 더 기다린다
 */
export function minutesVerdict(status: string | null, lost: boolean): MinutesVerdict {
  if (status === 'DONE') return { kind: 'made' }
  if (status === 'FAILED') return { kind: 'failed', message: RETRY_ERROR }
  if (status === 'BUSY') return { kind: 'failed', message: '이미 회의록을 만드는 중이에요. 다 되면 화면에 보여요' }
  if (status === 'NONE') return { kind: 'failed', message: lost ? NETWORK_ERROR : RETRY_ERROR }
  return { kind: 'wait' }
}

/** 응답을 못 받았을 때 서버 잠금(170초)이 풀릴 때까지 10초마다 확인(앱과 같은 18번) */
export const STATUS_INTERVAL_MS = 10_000
export const STATUS_CHECKS = 18

/** Word 파일 이름: ASCII만(한글 이름은 받는 쪽에서 다운로드가 실패한 적이 있음, 앱과 같음) */
export function minutesFileName(meetingDate: string): string {
  const ymd = meetingDate.replace(/-/g, '')
  return /^\d{8}$/.test(ymd) ? `meeting_minutes_${ymd}.docx` : 'meeting_minutes.docx'
}

/** 회의록 승인 결과 → 알림(FR-07, DEC-61). 다른 기기에서 새로 만든 버전이면 새 내용을 보고 다시 누르게 한다(M14) */
export function approveMeetingText(result: string | null): string {
  switch (result) {
    case 'approved':
      return "회의록을 승인했어요 · 이제 Word에 '초안' 표시가 없어요"
    case 'already-approved':
      return '이미 승인된 회의록이에요'
    case 'stale-version':
      return '회의록이 새로 만들어졌어요. 새 내용을 확인하고 승인해 주세요'
    case 'no-document':
      return '회의록 문서를 먼저 만들어 주세요'
    case 'evidence-missing':
      return '전사 보관 기간이 지나 승인할 수 없어요'
    default:
      return '승인하지 못했어요. 인터넷 연결을 확인하고 다시 눌러 주세요'
  }
}
