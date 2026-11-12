/**
 * 날짜·시각 값(앱 java.time.LocalDate·LocalTime에 해당). 서버의 날짜만 있는 기한과 같은 모양의 글자로 다룬다.
 * 계산은 UTC 자정 기준 Date로 해서 브라우저 시간대·서머타임에 흔들리지 않게 한다
 */

/** 'YYYY-MM-DD'(서울 날짜, 시각 없음). 글자 비교가 곧 날짜 순서 */
export type LocalDate = string
/** 'HH:mm'(서울 시각) */
export type LocalTime = string

const pad = (n: number) => String(n).padStart(2, '0')

function fromUtc(t: Date): LocalDate {
  return `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`
}

function toUtc(d: LocalDate): Date {
  const [y, m, day] = d.split('-').map(Number)
  return new Date(Date.UTC(y ?? NaN, (m ?? NaN) - 1, day ?? NaN))
}

/** 그 날이 없으면(2/29가 없는 해, 4/31 등) null */
export function dateOf(year: number, month: number, day: number): LocalDate | null {
  const t = new Date(Date.UTC(year, month - 1, day))
  if (t.getUTCFullYear() !== year || t.getUTCMonth() !== month - 1 || t.getUTCDate() !== day) return null
  return fromUtc(t)
}

export function plusDays(d: LocalDate, days: number): LocalDate {
  const t = toUtc(d)
  t.setUTCDate(t.getUTCDate() + days)
  return fromUtc(t)
}

/** 월요일 1 … 일요일 7(java.time과 같음, 한 주는 월요일 시작 DEC-31) */
export function dayOfWeek(d: LocalDate): number {
  const w = toUtc(d).getUTCDay()
  return w === 0 ? 7 : w
}

export function yearOf(d: LocalDate): number {
  return toUtc(d).getUTCFullYear()
}

export function timeOf(hour: number, minute: number): LocalTime {
  return `${pad(hour)}:${pad(minute)}`
}

/** 지금 서울 날짜 */
export function seoulToday(now: Date = new Date()): LocalDate {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}
