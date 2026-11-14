import { dayOfWeek, plusDays, type LocalDate } from './dates'
import { seoulDate, seoulTime } from './tasks'

/*
 * 구글 캘린더 보기·넣기 화면 규칙(FR-24a, DEC-105, Design §3.16). 앱 calendar 규칙과 같게 — 고치면 두 곳을 같이.
 * 일정은 서버에 저장하지 않고 함수 `calendar`가 부를 때마다 Google에서 읽어 준다
 */

/** 함수 calendar가 돌려주는 일정 한 줄(서버 _shared/calendar.ts CalEvent와 같은 칸) */
export interface CalEvent {
  id: string
  title: string
  /** 종일 = 'YYYY-MM-DD', 시각 있음 = ISO(오프셋 포함) */
  start: string
  /** 종일이면 다음 날(끝 날 미포함) */
  end: string
  allDay: boolean
  link: string | null
}

/** 오늘 카드에서 처음 보이는 수(종일·지금·남은 일정, Design §3.16) */
export const TODAY_SHOWN = 3
/** 다음 7일 카드에서 처음 보이는 수 — 할 일 목록이 밀리지 않게(11/14 사용자) */
export const WEEK_SHOWN = 5
/** 같은 기간 일정을 다시 부르지 않는 시간(끌어 새로고침·[다시 시도]는 바로) */
export const EVENTS_TTL_MS = 5 * 60_000

/** 줄 색: past = 끝남(흐리게), now = 진행 중(강조) */
export type EventTone = 'past' | 'now' | ''
export interface EventLine {
  e: CalEvent
  tone: EventTone
}

const ms = (iso: string) => Date.parse(iso)

/** 줄 왼쪽 시각: 종일은 '종일', 시각 있음은 서울 시작·끝 */
export function eventTimes(e: CalEvent): { start: string; end: string | null } {
  if (e.allDay) return { start: '종일', end: null }
  return { start: seoulTime(e.start) ?? '', end: seoulTime(e.end) }
}

function toneOf(e: CalEvent, now: number): EventTone {
  if (e.allDay) return ''
  if (ms(e.end) <= now) return 'past'
  return ms(e.start) <= now ? 'now' : ''
}

/**
 * 오늘 카드: 종일 → '지난 일정 N'(접힘) → 지금 → 남은 일정. 처음에는 종일·지금·남은 일정 가운데 TODAY_SHOWN개만,
 * 나머지는 '일정 N개 더'. moreOpen이면 모두
 */
export function todayLines(events: CalEvent[], now: Date, moreOpen: boolean): { allDay: EventLine[]; past: EventLine[]; rest: EventLine[]; hidden: number } {
  const t = now.getTime()
  const timed = events.filter((e) => !e.allDay).sort((a, b) => ms(a.start) - ms(b.start))
  const lines = [...events.filter((e) => e.allDay), ...timed.filter((e) => toneOf(e, t) !== 'past')].map((e) => ({ e, tone: toneOf(e, t) }))
  const shown = moreOpen ? lines : lines.slice(0, TODAY_SHOWN)
  const nAllDay = shown.filter((l) => l.e.allDay).length
  return {
    allDay: shown.slice(0, nAllDay),
    past: timed.filter((e) => toneOf(e, t) === 'past').map((e) => ({ e, tone: 'past' as const })),
    rest: shown.slice(nAllDay),
    hidden: lines.length - shown.length,
  }
}

const DOW = '월화수목금토일'

/** 날짜 묶음 이름: '오늘 · 11/14 (금)', '내일 · 11/15 (토)', '11/17 (월)' */
export function dayLabel(day: LocalDate, today: LocalDate): string {
  const md = `${Number(day.slice(5, 7))}/${Number(day.slice(8))} (${DOW[dayOfWeek(day) - 1]})`
  if (day === today) return `오늘 · ${md}`
  if (day === plusDays(today, 1)) return `내일 · ${md}`
  return md
}

/** 일정이 놓이는 날: 시작한 날(서울). 이미 시작해 오늘까지 이어지는 일정은 오늘에 */
function dayOf(e: CalEvent, today: LocalDate): LocalDate {
  const d = e.allDay ? e.start : (seoulDate(e.start) ?? today)
  return d < today ? today : d
}

export interface DayGroup {
  day: LocalDate
  label: string
  lines: EventLine[]
}

/**
 * 다음 7일 카드: 날짜별(일정 없는 날은 건너뜀), 날마다 종일 먼저·시각순. 처음에는 WEEK_SHOWN개만(moreOpen이면 모두).
 * 오늘 이미 끝난 일정은 흐리게 둔다(지운 것처럼 빼지 않음 — 이번 주 계획을 보는 곳)
 */
export function weekGroups(events: CalEvent[], today: LocalDate, now: Date, moreOpen: boolean): { groups: DayGroup[]; total: number; hidden: number } {
  const t = now.getTime()
  const sorted = events
    .map((e) => ({ e, day: dayOf(e, today) }))
    .sort((a, b) => a.day.localeCompare(b.day) || Number(b.e.allDay) - Number(a.e.allDay) || ms(a.e.start) - ms(b.e.start))
  const shown = moreOpen ? sorted : sorted.slice(0, WEEK_SHOWN)
  const groups: DayGroup[] = []
  for (const { e, day } of shown) {
    let g = groups[groups.length - 1]
    if (!g || g.day !== day) {
      g = { day, label: dayLabel(day, today), lines: [] }
      groups.push(g)
    }
    g.lines.push({ e, tone: toneOf(e, t) === 'past' ? 'past' : '' })
  }
  return { groups, total: sorted.length, hidden: sorted.length - shown.length }
}

// ── 연결 ──

/** 허락 뒤 돌아올 곳(서버가 정해 둔 셋 중 웹 둘). 로컬 개발 서버는 localhost:5173 */
export function returnTarget(hostname: string): 'web' | 'web-local' {
  return hostname === 'localhost' || hostname === '127.0.0.1' ? 'web-local' : 'web'
}

/** 허락하고 돌아온 결과(?calendar=) 알림. 모르는 값이면 null */
export function connectResultText(result: string | null): string | null {
  switch (result) {
    case 'ok':
      return '구글 캘린더를 연결했어요'
    case 'denied':
      return '캘린더 허락을 하지 않아 연결하지 않았어요'
    case 'wrong_account':
      return '로그인한 계정과 다른 Google 계정이에요 · 같은 계정으로 다시 연결해 주세요'
    case 'error':
      return '연결하지 못했어요. 잠시 뒤 다시 해 주세요'
    default:
      return null
  }
}

/** 함수가 200으로 돌려주는 기대한 실패 + 주고받기 실패 */
export type CalendarReason = 'not-connected' | 'broken' | 'no-due' | 'not-found' | 'failed'

export function calendarFailText(reason: CalendarReason): string {
  switch (reason) {
    case 'broken':
      return '캘린더 연결이 끊겼어요 · 설정에서 다시 연결해 주세요'
    case 'not-connected':
      return '구글 캘린더가 연결돼 있지 않아요 · 설정에서 연결해 주세요'
    case 'no-due':
      return '기한 날짜가 없어 캘린더에 넣지 못했어요'
    case 'not-found':
      return '할 일을 찾지 못했어요. 새로 불러와 주세요'
    default:
      return '캘린더와 주고받지 못했어요. 잠시 뒤 다시 해 주세요'
  }
}

// ── 할 일 서랍 '캘린더' 줄 ──

/**
 * 저장할 때 캘린더에 할 일:
 * add = 새로 넣기 · keep = 그대로(제목·기한을 고쳤으면 서버 표시를 보고 목록을 다시 읽을 때 반영) · ask = 끄거나 기한을 지워 '일정도 지울까요?'
 */
export type CalendarPlan = 'none' | 'add' | 'keep' | 'ask'
/** 묻고 난 뒤까지 포함한 최종 처리: unlink = 일정은 남기고 연결만 끊기, delete = 일정도 지우기 */
export type CalendarIntent = 'none' | 'add' | 'keep' | 'unlink' | 'delete'

export function calendarPlan(s: { connected: boolean; linked: boolean; on: boolean; hasDate: boolean }): CalendarPlan {
  if (!s.linked) return s.connected && s.on && s.hasDate ? 'add' : 'none'
  // 연결이 끊긴 동안에는 일정을 건드릴 수 없어 그대로 둔다(다시 연결하면 이어짐)
  if (!s.connected) return 'keep'
  return s.on && s.hasDate ? 'keep' : 'ask'
}

/** 스위치 아래 안내 한 줄 */
export function calendarNote(s: { connected: boolean; linked: boolean; on: boolean; hasDate: boolean; withTime: boolean }): string {
  if (!s.connected) return s.linked ? '캘린더 연결이 끊겨 있어요. 설정에서 다시 연결하면 이어서 반영해요.' : '설정에서 구글 캘린더를 연결하면 넣을 수 있어요.'
  if (!s.hasDate) return s.linked ? '기한 날짜를 지우고 저장하면 캘린더 일정을 어떻게 할지 물어봐요.' : '기한 날짜를 정하면 넣을 수 있어요.'
  if (!s.on) return s.linked ? '끄고 저장하면 캘린더 일정을 어떻게 할지 물어봐요.' : ''
  const how = s.linked ? '' : s.withTime ? '그 시각부터 1시간 일정으로 넣어요. ' : '종일 일정으로 넣어요. '
  return `${how}제목·기한을 고치면 캘린더도 따라 바뀌고, 완료해도 일정은 남아요.`
}

/** '캘린더 일정도 지울까요?' 설명 */
export function askText(s: { kind: 'save'; title: string; hasDate: boolean } | { kind: 'delete'; titles: string[]; total: number }): string {
  if (s.kind === 'save') return `‘${s.title}’ 일정이 Google 캘린더에 있어요. ${s.hasDate ? '[캘린더에도 넣기]를 껐어요.' : '기한을 지웠어요.'} [남기기]를 누르면 일정은 그대로 두고 연결만 끊어요.`
  const first = s.titles[0] ?? ''
  if (s.total === 1) return `‘${first}’ 일정이 Google 캘린더에 있어요. [남기기]를 누르면 할 일만 지우고 일정은 그대로 둬요.`
  return `지우는 할 일 ${s.total}개 중 ${s.titles.length}개(‘${first}’${s.titles.length > 1 ? ' 등' : ''})가 Google 캘린더에 있어요. [남기기]를 누르면 할 일만 지우고 일정은 그대로 둬요.`
}
