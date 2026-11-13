import { dayOfWeek, plusDays, type LocalDate } from './dates'
import { seoulDate } from './tasks'
import { snoozeLabel } from './records'

/*
 * 검토함 규칙(FR-28, DEC-63, 앱 review/ReviewScreen.kt·Snooze.kt·SnoozeSheet.kt를 옮김 — 고치면 두 곳을 같이, DEC-102)
 */

/** 한 주는 월요일 시작(DEC-31): 오늘이 월요일이어도 다음 주 월요일 */
export function nextMonday(today: LocalDate): LocalDate {
  return plusDays(today, 8 - dayOfWeek(today))
}

/**
 * 미룰 수 있는 마지막 날: 1년 뒤와, 고른 후보들의 근거 전사 보관이 끝나는 날의 전날 중 이른 날.
 * 보관이 끝나면 승인할 수 없으므로(DEC-15·21) 그 뒤로 미루지 않고, 다시 나온 뒤 하루는 검토할 수 있게 한다
 */
export function lastAllowed(transcriptExpiries: (string | null)[], today: LocalDate): LocalDate {
  const yearLater = `${Number(today.slice(0, 4)) + 1}${today.slice(4)}`
  const days = transcriptExpiries.flatMap((iso) => (iso ? (seoulDate(iso) ?? []) : []))
  if (days.length === 0) return yearLater
  const evidenceLast = plusDays(days.sort()[0]!, -1)
  return evidenceLast < yearLater ? evidenceLast : yearLater
}

/** 고른 후보 중 가장 이른 근거 보관 마지막 날(안내 줄). 없으면 null */
export function evidenceUntil(transcriptExpiries: (string | null)[]): LocalDate | null {
  const days = transcriptExpiries.flatMap((iso) => (iso ? (seoulDate(iso) ?? []) : []))
  return days.length ? days.sort()[0]! : null
}

/**
 * 미룬 후보의 두 보관 기한(Design §3.7): 근거 전사(지나면 승인 불가)와 원본 음성(지나도 전사로 검토 가능).
 * 음성이 없는 기록(가져온 글)은 근거만
 */
export function retentionNote(transcriptExpiresAt: string | null, audio: { retention_expires_at: string | null; purged_at: string | null } | null, now: Date = new Date()): string | null {
  const evidence = snoozeLabel(transcriptExpiresAt)
  const parts = [evidence ? `근거 보관 ${evidence}까지` : null]
  if (audio) {
    const expired = audio.purged_at != null || (audio.retention_expires_at != null && new Date(audio.retention_expires_at) < now)
    const until = snoozeLabel(audio.retention_expires_at)
    parts.push(expired ? '음성 보관 끝남' : until ? `음성 ${until}까지` : null)
  }
  const text = parts.filter(Boolean).join(' · ')
  return text || null
}

/** 기록별 묶음(기록 순서는 처음 나온 후보 순 — 오래된 것부터) */
export function groupByRecord<T>(rows: T[], recordOf: (r: T) => { id: string | null; title: string }): { id: string | null; title: string; rows: T[] }[] {
  const groups = new Map<string, { id: string | null; title: string; rows: T[] }>()
  for (const r of rows) {
    const rec = recordOf(r)
    const key = rec.id ?? '(없음)'
    const g = groups.get(key)
    if (g) g.rows.push(r)
    else groups.set(key, { id: rec.id, title: rec.title, rows: [r] })
  }
  return [...groups.values()]
}

/** 미루기·지금 검토 결과 알림 */
export function snoozeText(updated: number | null, until: LocalDate | null): string {
  if (updated === null) return '옮기지 못했어요. 인터넷 연결을 확인해 주세요'
  if (until === null) return `후보 ${updated}개를 오늘 검토로 가져왔어요`
  return `후보 ${updated}개를 ${snoozeLabel(`${until}T00:00:00+09:00`)}까지 미뤘어요 · 그날 ‘오늘’에 다시 나와요`
}

/** 프로젝트 현재 상태 제안 [반영]·[아니요] 결과(앱 decideState) */
export function stateDecisionText(result: string | null): string {
  switch (result) {
    case null:
      return '바꾸지 못했어요. 인터넷 연결을 확인해 주세요'
    case 'approved':
    case 'already-approved':
      return '프로젝트 현재 상태에 반영했어요'
    case 'rejected':
    case 'already-rejected':
      return '현재 상태 제안을 넘겼어요'
    case 'conflict':
      return '그사이 현재 상태가 바뀌어 반영하지 않았어요 · 이 제안은 닫혔어요'
    case 'stale-revision':
      return '제안이 방금 새 회의로 바뀌었어요 · 다시 보고 골라 주세요'
    default:
      return `바꾸지 못했어요(${result})`
  }
}

/** 처리됨 탭의 현재 상태 제안 상태 이름 */
export function stateDoneLabel(status: string): string {
  return status === 'approved' ? '반영함' : status === 'rejected' ? '넘김' : status === 'conflict' ? '충돌로 닫힘' : status
}
