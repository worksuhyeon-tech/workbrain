import { useState } from 'react'
import { cn } from '../lib/cn'
import { dayOfWeek, monthGrid, seoulToday, type LocalDate } from '../rules/dates'

const WEEK = ['월', '화', '수', '목', '금', '토', '일']
// 시각 고르기: 시(07~22시) 한 번 + 분(10분 단위) 한 번이면 끝난다(11/13 사용자: 마우스로 바로)
const HOURS = Array.from({ length: 16 }, (_, i) => String(i + 7).padStart(2, '0'))
const MINUTES = ['00', '10', '20', '30', '40', '50']
const CELL = 'grid h-8 place-items-center rounded-md text-[13px] tabular-nums'

/** 고른 날 머리 줄 '11/18(수)' */
function dayText(d: LocalDate): string {
  const [, m, day] = d.split('-').map(Number)
  return `${m}/${day}(${WEEK[dayOfWeek(d) - 1]})`
}

/**
 * 서랍 안에 펼쳐 둔 달력(+ 시각): 날짜를 한 번 누르면 정해지고, withTime이면 아래 시·분을 눌러 시각까지 정한다.
 * 텍스트 칸을 열고 닫는 브라우저 기본 날짜 입력 대신(11/13 사용자: 몇 번을 눌러야 정해짐). 한 주는 월요일부터(DEC-31)
 */
export function DuePicker({
  date,
  time = '',
  withTime = false,
  onChange,
}: {
  /** 'YYYY-MM-DD' 또는 '' */
  date: string
  /** 'HH:mm' 또는 '' */
  time?: string
  withTime?: boolean
  onChange: (date: string, time: string) => void
}) {
  const today = seoulToday()
  const base = date || today
  const [view, setView] = useState({ y: Number(base.slice(0, 4)), m: Number(base.slice(5, 7)) })
  const move = (delta: number) => setView(({ y, m }) => (m + delta < 1 ? { y: y - 1, m: 12 } : m + delta > 12 ? { y: y + 1, m: 1 } : { y, m: m + delta }))
  const [hh = '', mm = ''] = time ? time.split(':') : []
  // 날짜 없이 시각부터 고르면 오늘로 둔다
  const pickTime = (h: string, m: string) => onChange(date || today, `${h}:${m}`)

  return (
    <div className="rounded-lg border border-border bg-card p-3">
      <div className="mb-2 flex items-center gap-1">
        <button type="button" onClick={() => move(-1)} aria-label="이전 달" className="grid size-8 place-items-center rounded-md text-muted hover:bg-surface hover:text-ink">
          ‹
        </button>
        <span className="flex-1 text-center text-sm font-bold tabular-nums">
          {view.y}년 {view.m}월
        </span>
        <button type="button" onClick={() => move(1)} aria-label="다음 달" className="grid size-8 place-items-center rounded-md text-muted hover:bg-surface hover:text-ink">
          ›
        </button>
        <button
          type="button"
          onClick={() => {
            setView({ y: Number(today.slice(0, 4)), m: Number(today.slice(5, 7)) })
            onChange(today, time)
          }}
          className="ml-1 h-7 rounded-full border border-border px-2.5 text-xs font-semibold text-body hover:bg-surface"
        >
          오늘
        </button>
      </div>
      <div className="grid grid-cols-7 gap-0.5 text-center">
        {WEEK.map((w, i) => (
          <span key={w} className={cn('pb-1 text-[11px] font-bold', i === 6 ? 'text-crit' : i === 5 ? 'text-info' : 'text-muted')}>
            {w}
          </span>
        ))}
        {monthGrid(view.y, view.m).map((d) => {
          const inMonth = Number(d.slice(5, 7)) === view.m
          const picked = d === date
          return (
            <button
              key={d}
              type="button"
              aria-pressed={picked}
              aria-label={dayText(d)}
              onClick={() => {
                onChange(d, time)
                if (!inMonth) setView({ y: Number(d.slice(0, 4)), m: Number(d.slice(5, 7)) })
              }}
              className={cn(
                CELL,
                picked ? 'bg-primary font-bold text-on-primary' : 'hover:bg-surface',
                !picked && d === today && 'font-bold text-primary ring-1 ring-primary',
                !picked && !inMonth && 'text-muted/60',
              )}
            >
              {Number(d.slice(8))}
            </button>
          )
        })}
      </div>
      {withTime && (
        <div className="mt-3 border-t border-border pt-3">
          <div className="mb-1.5 text-xs font-bold text-muted">시</div>
          <div className="grid grid-cols-8 gap-1">
            {HOURS.map((h) => (
              // 시를 처음 고르면 정각으로
              <button key={h} type="button" aria-pressed={h === hh} onClick={() => pickTime(h, mm || '00')} className={cn(CELL, 'border border-border', h === hh ? 'border-primary bg-primary font-bold text-on-primary' : 'hover:bg-surface')}>
                {h}
              </button>
            ))}
          </div>
          <div className="mt-2 mb-1.5 text-xs font-bold text-muted">분</div>
          <div className="grid grid-cols-6 gap-1">
            {MINUTES.map((m) => (
              <button
                key={m}
                type="button"
                aria-pressed={m === mm}
                disabled={!hh}
                onClick={() => pickTime(hh, m)}
                className={cn(CELL, 'border border-border disabled:opacity-40', m === mm ? 'border-primary bg-primary font-bold text-on-primary' : 'hover:bg-surface')}
              >
                {m}
              </button>
            ))}
          </div>
        </div>
      )}
      <div className="mt-2.5 text-[13px] font-semibold text-ink">
        {date ? `${dayText(date)}${withTime ? (time ? ` ${time}` : ' · 시각을 골라 주세요') : ''}` : <span className="text-muted">날짜를 골라 주세요</span>}
      </div>
    </div>
  )
}
