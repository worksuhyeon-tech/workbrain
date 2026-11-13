import { useState } from 'react'
import { plusDays, seoulToday, type LocalDate } from '../rules/dates'
import { snoozeLabel } from '../rules/records'
import { nextMonday } from '../rules/review'
import { Dialog, PRIMARY_BUTTON, SECONDARY_BUTTON } from './Dialog'
import { DuePicker } from './DuePicker'

const OPTION = 'flex h-12 w-full items-center gap-3 rounded-lg px-3 text-left text-[15px] text-ink hover:bg-surface'
const label = (d: LocalDate) => snoozeLabel(`${d}T00:00:00+09:00`) ?? d

/**
 * 언제 다시 볼까요?(FR-28, 앱 SnoozeSheet): 내일 · 다음 주 월요일 · 날짜 고르기(펼친 달력). 그날 0시에 '오늘'로 다시 나온다.
 * lastAllowed: 고를 수 있는 마지막 날(근거 보관이 끝나기 전날, 1년 안). evidenceUntil: 고른 후보 중 가장 이른 근거 보관 마지막 날
 */
export function SnoozeDialog({
  count,
  lastAllowed,
  evidenceUntil,
  onPick,
  onDismiss,
}: {
  count: number
  lastAllowed: LocalDate
  evidenceUntil: LocalDate | null
  onPick: (day: LocalDate) => void
  onDismiss: () => void
}) {
  const today = seoulToday()
  const tomorrow = plusDays(today, 1)
  const monday = nextMonday(today)
  const [picking, setPicking] = useState(false)
  const [chosen, setChosen] = useState('')
  const chosenOk = chosen > today && chosen <= lastAllowed

  return (
    <Dialog
      title="언제 다시 볼까요?"
      onDismiss={onDismiss}
      actions={
        picking ? (
          <>
            <button type="button" onClick={() => setPicking(false)} className={SECONDARY_BUTTON}>
              뒤로
            </button>
            <button type="button" disabled={!chosenOk} onClick={() => onPick(chosen)} className={PRIMARY_BUTTON}>
              {chosenOk ? `${label(chosen)}까지 미루기` : '미루기'}
            </button>
          </>
        ) : (
          <button type="button" onClick={onDismiss} className={SECONDARY_BUTTON}>
            취소
          </button>
        )
      }
    >
      <p className="mb-3 text-[13px] text-muted">후보 {count}개를 그날 0시에 ‘오늘’로 다시 보여 드려요. 미뤄도 할 일은 바뀌지 않아요.</p>
      {lastAllowed < tomorrow ? (
        // 근거 전사가 내일 안에 지워지면 미룬 날에는 승인할 수 없다
        <p className="mb-2 text-sm text-crit">근거 보관 기간이 곧 끝나 미룰 수 없어요. 오늘 검토해 주세요.</p>
      ) : picking ? (
        <>
          <DuePicker date={chosen} onChange={(d) => setChosen(d)} />
          {chosen && !chosenOk && <p className="mt-2 text-xs text-crit">내일부터 {label(lastAllowed)}까지 고를 수 있어요</p>}
        </>
      ) : (
        <div className="flex flex-col">
          <button type="button" onClick={() => onPick(tomorrow)} className={OPTION}>
            <span className="flex-1">내일</span>
            <span className="text-sm text-muted">{label(tomorrow)}</span>
          </button>
          {/* 일요일에는 다음 주 월요일이 곧 내일이라 한 번만(한 주는 월요일 시작, DEC-31) */}
          {monday !== tomorrow && monday <= lastAllowed && (
            <button type="button" onClick={() => onPick(monday)} className={OPTION}>
              <span className="flex-1">다음 주 월요일</span>
              <span className="text-sm text-muted">{label(monday)}</span>
            </button>
          )}
          <button type="button" onClick={() => setPicking(true)} className={OPTION}>
            <span className="flex-1">날짜 고르기</span>
            <span className="text-muted">›</span>
          </button>
        </div>
      )}
      {evidenceUntil && <p className="mt-3 text-xs text-muted">근거 보관 {label(evidenceUntil)}까지 · 미뤄도 늘지 않고, 지나면 승인할 수 없어요</p>}
    </Dialog>
  )
}
