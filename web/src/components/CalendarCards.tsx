import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { cn } from '../lib/cn'
import { useCalendar, useCalendarEvents, useNow, type EventsState } from '../lib/calendarStore'
import { eventTimes, todayLines, weekGroups, type EventLine } from '../rules/calendar'
import { seoulToday } from '../rules/dates'
import { Dialog, TEXT_BUTTON } from './Dialog'
import { Icons } from './icons'
import { CARD } from './ui'

/**
 * '캘린더 일정도 지울까요?'(11/14 결정 ④·DEC-105): 일정이 있는 할 일의 [캘린더에도 넣기]를 끄거나 기한을 지우고 저장할 때,
 * 일정이 있는 할 일을 지울 때. [남기기] = 일정은 두고 연결만 끊기, [지우기] = 일정도 지우기. 바깥·Esc는 아무것도 하지 않고 닫는다
 */
export function CalendarAskDialog({ text, onAnswer, onDismiss }: { text: string; onAnswer: (deleteEvent: boolean) => void; onDismiss: () => void }) {
  return (
    <Dialog
      title="캘린더 일정도 지울까요?"
      onDismiss={onDismiss}
      actions={
        <>
          <button type="button" autoFocus onClick={() => onAnswer(false)} className={TEXT_BUTTON}>
            남기기
          </button>
          <button type="button" onClick={() => onAnswer(true)} className={cn(TEXT_BUTTON, 'text-crit')}>
            지우기
          </button>
        </>
      }
    >
      <p className="text-sm leading-relaxed text-body">{text}</p>
    </Dialog>
  )
}

/*
 * 구글 캘린더 일정 카드(FR-24a, Design §3.16, 목업 workbrain-dashboard·tasks의 .ev):
 * 오늘 화면 '오늘 일정'(맨 위, 11/14 결정 ①)과 모든 할 일 '다음 7일' 탭의 '다음 7일 일정'. 연결했을 때만 보인다.
 * 줄을 누르면 Google 캘린더 새 탭에서 그 일정을 연다. WB가 넣은 일정은 서버가 빼고 할 일 줄 아이콘으로 보여 준다(결정 ②)
 */

const GOOGLE_DAY = 'https://calendar.google.com/calendar/r/day'
const GOOGLE_WEEK = 'https://calendar.google.com/calendar/r/week'
const FOLD = 'block w-full border-t border-border px-4 py-2 text-left text-[13px] font-bold text-muted hover:text-primary'

function EventRow({ line }: { line: EventLine }) {
  const { e, tone } = line
  const t = eventTimes(e)
  return (
    <a
      href={e.link ?? GOOGLE_DAY}
      target="_blank"
      rel="noopener noreferrer"
      className={cn(
        'group grid grid-cols-[58px_minmax(0,1fr)_auto] items-center gap-3 border-t border-border px-4 py-[9px] text-inherit no-underline hover:bg-surface',
        tone === 'now' && 'bg-primary/8 shadow-[inset_3px_0_0_var(--primary)]',
      )}
    >
      <span className={cn('text-[13px] leading-tight font-bold tabular-nums', tone === 'past' ? 'font-medium text-muted' : 'text-ink')}>
        {t.start}
        {t.end && <small className="block text-[11px] font-medium text-muted">{t.end}</small>}
      </span>
      <span className={cn('truncate text-[15px] font-medium', tone === 'past' ? 'text-muted' : 'text-ink')}>{e.title}</span>
      {tone === 'now' ? (
        <span className="text-xs font-bold whitespace-nowrap text-primary">● 지금</span>
      ) : (
        <span className="text-muted opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100">
          <Icons.external />
          <span className="sr-only">Google 캘린더에서 열기</span>
        </span>
      )}
    </a>
  )
}

/** 불러오는 중·실패·끊김 한 줄. 일정이 있으면 null */
function stateLine(state: EventsState, retry: () => void): ReactNode {
  if (state.kind === 'loading') return <Msg>일정을 불러오는 중…</Msg>
  if (state.kind === 'fail')
    return (
      <Msg>
        일정을 불러오지 못했어요
        <button type="button" onClick={retry} className="font-bold text-link">
          다시 시도
        </button>
      </Msg>
    )
  if (state.kind === 'broken') return <Broken />
  return null
}

function Msg({ crit, children }: { crit?: boolean; children: ReactNode }) {
  return <div className={cn('flex flex-wrap items-center gap-2 border-t border-border px-4 pt-3 pb-3.5 text-sm', crit ? 'text-crit' : 'text-muted')}>{children}</div>
}

function Broken() {
  return (
    <Msg crit>
      캘린더 연결이 끊겼어요
      <Link to="/settings" className="font-bold">
        다시 연결
      </Link>
    </Msg>
  )
}

/** 오늘 화면 '오늘 일정': 종일 → 지난 일정(접힘) → ● 지금 → 남은 일정 3개 + '일정 N개 더' */
export function TodayEventsCard() {
  const cal = useCalendar()
  const today = seoulToday()
  const now = useNow()
  const { state, retry } = useCalendarEvents(today, 1, cal.status === 'on')
  const [pastOpen, setPastOpen] = useState(false)
  const [moreOpen, setMoreOpen] = useState(false)
  if (cal.status !== 'on' && cal.status !== 'broken') return null

  const events = state.kind === 'ok' ? state.events : []
  const r = todayLines(events, now, moreOpen)
  return (
    <section aria-label="오늘 일정" className={CARD}>
      <div className="flex items-center gap-2 px-4 pt-4 pb-2">
        <h2 className="text-[17px] font-bold tracking-[-0.09px]">오늘 일정</h2>
        <span className="text-sm font-semibold text-muted tabular-nums">{state.kind === 'ok' && events.length > 0 ? `${events.length}개` : ''}</span>
        <a href={GOOGLE_DAY} target="_blank" rel="noopener noreferrer" className="ml-auto text-[13px] font-bold">
          Google 캘린더 ↗
        </a>
      </div>
      {cal.status === 'broken' ? (
        <Broken />
      ) : (
        (stateLine(state, retry) ??
        (events.length === 0 ? (
          <Msg>오늘 일정 없음</Msg>
        ) : (
          <>
            {r.allDay.map((l) => <EventRow key={l.e.id} line={l} />)}
            {r.past.length > 0 && (
              <button type="button" onClick={() => setPastOpen((o) => !o)} aria-expanded={pastOpen} className={FOLD}>
                지난 일정 {r.past.length} {pastOpen ? '⌃' : '⌄'}
              </button>
            )}
            {pastOpen && r.past.map((l) => <EventRow key={l.e.id} line={l} />)}
            {r.rest.map((l) => <EventRow key={l.e.id} line={l} />)}
            {(r.hidden > 0 || moreOpen) && (
              <button type="button" onClick={() => setMoreOpen((o) => !o)} aria-expanded={moreOpen} className={FOLD}>
                {moreOpen ? '접기 ⌃' : `일정 ${r.hidden}개 더 ⌄`}
              </button>
            )}
          </>
        )))
      )}
    </section>
  )
}

/** 모든 할 일 '다음 7일' 탭 맨 위 '다음 7일 일정': 날짜별, 처음엔 5개 + '일정 N개 더'(11/14 사용자) */
export function WeekEventsCard() {
  const cal = useCalendar()
  const today = seoulToday()
  const now = useNow()
  const { state, retry } = useCalendarEvents(today, 7, cal.status === 'on')
  const [moreOpen, setMoreOpen] = useState(false)
  if (cal.status !== 'on' && cal.status !== 'broken') return null

  const events = state.kind === 'ok' ? state.events : []
  const w = weekGroups(events, today, now, moreOpen)
  return (
    <section aria-label="다음 7일 일정" className={cn(CARD, 'mb-4')}>
      <div className="flex items-center gap-2 px-4 pt-4 pb-1">
        <h3 className="text-[13px] font-bold tracking-[.5px] text-muted">
          다음 7일 일정 <span className="tabular-nums">{state.kind === 'ok' && w.total > 0 ? w.total : ''}</span>
        </h3>
        <a href={GOOGLE_WEEK} target="_blank" rel="noopener noreferrer" className="ml-auto text-xs text-muted hover:text-primary">
          Google 캘린더 ↗
        </a>
      </div>
      {cal.status === 'broken' ? (
        <Broken />
      ) : (
        (stateLine(state, retry) ??
        (w.total === 0 ? (
          <Msg>다음 7일 동안 일정 없음</Msg>
        ) : (
          <>
            {w.groups.map((g) => (
              <div key={g.day}>
                <div className="border-t border-border px-4 pt-2.5 pb-1 text-xs font-bold text-muted">{g.label}</div>
                {g.lines.map((l) => <EventRow key={l.e.id} line={l} />)}
              </div>
            ))}
            {(w.hidden > 0 || moreOpen) && (
              <button type="button" onClick={() => setMoreOpen((o) => !o)} aria-expanded={moreOpen} className={FOLD}>
                {moreOpen ? '접기 ⌃' : `일정 ${w.hidden}개 더 ⌄`}
              </button>
            )}
          </>
        )))
      )}
    </section>
  )
}
