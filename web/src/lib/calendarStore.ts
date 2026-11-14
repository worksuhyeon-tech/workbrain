import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import { CalendarError, loadCalendarLinks, loadCalendarStatus, loadEvents, syncCalendar, type CalendarStatus } from '../data/calendar'
import { EVENTS_TTL_MS, type CalEvent } from '../rules/calendar'
import type { LocalDate } from '../rules/dates'

/*
 * 구글 캘린더 상태(FR-24a, Design §3.16): 연결 상태와 '캘린더에 넣은 할 일' 목록을 화면들이 같이 본다.
 * 할 일 목록을 다시 읽을 때 refresh()를 함께 부르고, 고쳤는데 캘린더에 아직 반영 안 된 할 일(dirty)이 있으면 그때 sync를 한 번 부른다
 * (서버 크론 없음 — 저장·승인 뒤에 목록을 다시 읽으므로 곧바로 반영되고, 실패하면 다음 읽기 때 다시)
 */

export interface CalendarState {
  /** unknown = 아직 못 읽음(카드·칩을 숨긴다) */
  status: CalendarStatus | 'unknown'
  connectedAt: string | null
  /** 캘린더에 넣은 할 일 ID → dirty */
  links: ReadonlyMap<string, boolean>
}

let state: CalendarState = { status: 'unknown', connectedAt: null, links: new Map() }
let syncing = false
const listeners = new Set<() => void>()

function set(next: Partial<CalendarState>) {
  state = { ...state, ...next }
  listeners.forEach((l) => l())
}
const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

export const CalendarStore = {
  /** 연결 상태·일정 연결을 다시 읽는다. 보조 정보라 못 읽으면 조용히 그대로 둔다 */
  async refresh(): Promise<void> {
    try {
      const [s, links] = await Promise.all([loadCalendarStatus(), loadCalendarLinks()])
      set({ ...s, links })
      if (s.status === 'on' && [...links.values()].some(Boolean)) await syncDirty()
    } catch (e) {
      console.warn('calendar state load failed', e)
    }
  },
  /** 함수가 '연결이 끊겼어요'(Google이 열쇠를 거절)를 돌려줬을 때 */
  markBroken() {
    if (state.status === 'on') set({ status: 'broken' })
  },
}

async function syncDirty() {
  if (syncing) return
  syncing = true
  try {
    await syncCalendar()
    set({ links: await loadCalendarLinks() })
  } catch (e) {
    if (e instanceof CalendarError && e.reason === 'broken') CalendarStore.markBroken()
    else console.warn('calendar sync failed', e)
  } finally {
    syncing = false
  }
}

export function useCalendar(): CalendarState {
  return useSyncExternalStore(subscribe, () => state)
}

// ── 일정 읽기(오늘 카드·다음 7일 카드). 같은 기간은 5분 안에 다시 부르지 않는다 ──

export type EventsState = { kind: 'loading' } | { kind: 'ok'; events: CalEvent[] } | { kind: 'fail' } | { kind: 'broken' }

const cache = new Map<string, { at: number; events: CalEvent[] }>()

/** enabled(연결됨)일 때만 부른다. retry는 기억을 버리고 바로 다시 부른다 */
export function useCalendarEvents(from: LocalDate, days: number, enabled: boolean): { state: EventsState; retry: () => void } {
  const key = `${from}/${days}`
  const [result, setResult] = useState<{ key: string; state: EventsState } | null>(null)
  const [nonce, setNonce] = useState(0)

  useEffect(() => {
    if (!enabled) return
    const hit = cache.get(key)
    if (hit && Date.now() - hit.at < EVENTS_TTL_MS) {
      setResult({ key, state: { kind: 'ok', events: hit.events } })
      return
    }
    let alive = true
    setResult({ key, state: { kind: 'loading' } })
    loadEvents(from, days)
      .then((events) => {
        cache.set(key, { at: Date.now(), events })
        if (alive) setResult({ key, state: { kind: 'ok', events } })
      })
      .catch((e: unknown) => {
        const broken = e instanceof CalendarError && e.reason === 'broken'
        if (broken) CalendarStore.markBroken()
        // 연결이 안 된 것으로 나오면(다른 기기에서 끊음) 상태를 다시 읽어 카드를 숨긴다
        else if (e instanceof CalendarError && e.reason === 'not-connected') void CalendarStore.refresh()
        if (alive) setResult({ key, state: broken ? { kind: 'broken' } : { kind: 'fail' } })
      })
    return () => {
      alive = false
    }
  }, [key, from, days, enabled, nonce])

  const retry = useCallback(() => {
    cache.delete(key)
    setNonce((n) => n + 1)
  }, [key])

  return { state: result?.key === key ? result.state : { kind: 'loading' }, retry }
}

/** '지금' 강조·지난 일정 접기가 시간 따라 바뀌게 1분마다 다시 그린다 */
export function useNow(): Date {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 60_000)
    return () => window.clearInterval(id)
  }, [])
  return now
}
