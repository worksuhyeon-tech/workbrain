import { supabase } from '../lib/supabase'
import type { CalEvent, CalendarReason } from '../rules/calendar'
import type { LocalDate } from '../rules/dates'

/*
 * 구글 캘린더(FR-24a, DEC-105, Design §3.16): 처리 함수 `calendar`와 연결 상태 표(앱과 같은 함수·표).
 * 연결 열쇠는 서버(Vault)에만 있고, 여기서는 연결 상태(calendar_link)와 할 일 ↔ 일정 연결(task_calendar)만 읽는다
 */

export type CalendarStatus = 'off' | 'on' | 'broken'

/** 기대한 실패(연결 안 됨·끊김 등)와 주고받기 실패. reason으로 문구를 고른다(rules/calendar.calendarFailText) */
export class CalendarError extends Error {
  constructor(readonly reason: CalendarReason) {
    super(`calendar ${reason}`)
  }
}

const REASONS: readonly string[] = ['not-connected', 'broken', 'no-due', 'not-found']

async function call<T>(body: Record<string, unknown>): Promise<T> {
  const { data, error } = await supabase.functions.invoke('calendar', { body })
  if (error) {
    console.error('calendar call failed', body.action, error)
    throw new CalendarError('failed')
  }
  const reason = (data as { reason?: unknown } | null)?.reason
  if (typeof reason === 'string') throw new CalendarError(REASONS.includes(reason) ? (reason as CalendarReason) : 'failed')
  return data as T
}

/** 내 연결 상태와 연결한 때. 줄이 없거나 끊었으면 off, Google이 열쇠를 거절했으면 broken */
export async function loadCalendarStatus(): Promise<{ status: CalendarStatus; connectedAt: string | null }> {
  const r = await supabase.from('calendar_link').select('connected_at,broken_at')
  if (r.error) throw new Error(r.error.message)
  const row = (r.data as { connected_at: string | null; broken_at: string | null }[])[0]
  if (!row?.connected_at) return { status: 'off', connectedAt: null }
  return { status: row.broken_at ? 'broken' : 'on', connectedAt: row.connected_at }
}

/** 캘린더에 넣은 내 할 일 → '고쳤는데 아직 반영 안 됨'(dirty) */
export async function loadCalendarLinks(): Promise<Map<string, boolean>> {
  const r = await supabase.from('task_calendar').select('task_id,dirty')
  if (r.error) throw new Error(r.error.message)
  return new Map((r.data as { task_id: string; dirty: boolean }[]).map((x) => [x.task_id, x.dirty]))
}

/** Google 허락 화면 주소(1회용 확인값 포함, 10분). 허락 뒤 서버가 returnTo(웹 설정 화면)로 돌려보낸다 */
export async function connectUrl(returnTo: 'web' | 'web-local'): Promise<string> {
  return (await call<{ url: string }>({ action: 'connect', return_to: returnTo })).url
}

/** 연결 끊기: Google에 열쇠 취소를 요청하고 서버 열쇠를 지운다. 넣은 일정·일정 연결은 남는다 */
export async function disconnectCalendar(): Promise<void> {
  await call({ action: 'disconnect' })
}

/** 서울 날짜 from부터 days일(1~8)의 보여 줄 일정(취소·거절·WB가 넣은 일정은 서버가 뺌) */
export async function loadEvents(from: LocalDate, days: number): Promise<CalEvent[]> {
  return (await call<{ events: CalEvent[] }>({ action: 'events', from, days })).events
}

/** 할 일을 일정으로 넣기(이미 넣었으면 고치기). 기한 날짜가 없으면 no-due */
export async function insertEvent(taskId: string): Promise<void> {
  await call({ action: 'insert', task_id: taskId })
}

/** 제목·기한을 고친 할 일(dirty)을 캘린더에 반영 */
export async function syncCalendar(): Promise<{ synced: number; failed: number }> {
  return await call({ action: 'sync' })
}

/** 일정 연결 끊기. deleteEvent면 WB가 넣은 일정도 지운다(다른 일정은 서버가 지키며 건너뜀) */
export async function removeEvents(taskIds: string[], deleteEvent: boolean): Promise<void> {
  await call({ action: 'remove', task_ids: taskIds, delete_event: deleteEvent })
}
