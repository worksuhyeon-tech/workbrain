import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { PageHead } from '../components/AppShell'
import { useToast } from '../components/Toast'
import { CARD } from '../components/ui'
import { connectUrl, disconnectCalendar } from '../data/calendar'
import { CalendarStore, useCalendar } from '../lib/calendarStore'
import { cn } from '../lib/cn'
import { connectResultText, returnTarget } from '../rules/calendar'

const MD = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' })
const PILL = 'rounded-full px-2.5 py-[3px] text-xs font-bold whitespace-nowrap'
const PRIMARY = 'h-9 rounded-full bg-primary px-5 text-sm font-semibold text-on-primary hover:bg-primary-hover disabled:opacity-60'
const GHOST = 'h-9 rounded-full px-4 text-sm font-semibold text-muted hover:bg-surface hover:text-ink disabled:opacity-60'

/**
 * 설정(목업 workbrain-settings.html). PC 웹은 지금 '구글 캘린더'만 — 사용량·동의·회의록 지침·메일 발송은 휴대폰 앱 더보기에서.
 * 구글 캘린더(FR-24a, Design §3.16): [연결] → 서버가 만든 Google 허락 주소로 이동 → 허락 뒤 이 화면으로 ?calendar=결과를 붙여 돌아온다
 */
export function SettingsScreen({ email }: { email: string }) {
  const cal = useCalendar()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [params, setParams] = useSearchParams()
  const result = params.get('calendar')

  useEffect(() => {
    void CalendarStore.refresh()
  }, [])

  // 허락하고 돌아온 결과를 한 번 알리고 주소에서 지운다(새로고침해도 다시 뜨지 않게)
  const showToast = toast.show
  useEffect(() => {
    if (!result) return
    const text = connectResultText(result)
    if (text) showToast(text)
    setParams({}, { replace: true })
    void CalendarStore.refresh()
  }, [result, setParams, showToast])

  const connect = async () => {
    setBusy(true)
    try {
      window.location.assign(await connectUrl(returnTarget(window.location.hostname)))
    } catch {
      toast.show('연결을 시작하지 못했어요. 잠시 뒤 다시 해 주세요')
      setBusy(false)
    }
  }

  const disconnect = async () => {
    setBusy(true)
    try {
      await disconnectCalendar()
      await CalendarStore.refresh()
      toast.show('연결을 끊었어요 · 캘린더에 넣은 일정은 그대로 있어요')
    } catch {
      toast.show('연결을 끊지 못했어요. 잠시 뒤 다시 해 주세요')
    } finally {
      setBusy(false)
    }
  }

  // 목업 표기 '11/14 연결'(Intl 그대로면 '11. 14.')
  const sinceParts = cal.connectedAt ? MD.formatToParts(new Date(cal.connectedAt)) : null
  const since = sinceParts ? `${sinceParts.find((p) => p.type === 'month')?.value}/${sinceParts.find((p) => p.type === 'day')?.value} 연결` : ''

  return (
    <>
      <PageHead title="설정" hand="내 방식대로 맞추기" />

      <section aria-labelledby="cal-title" className={cn(CARD, 'mb-4 p-6')}>
        <h2 id="cal-title" className="text-xl font-bold">
          구글 캘린더
        </h2>
        <p className="mt-1 mb-4 text-sm leading-relaxed text-muted">
          오늘 화면에 <b className="text-ink">오늘 일정</b>을 보여 주고, 고른 할 일을 <b className="text-ink">캘린더에도 넣어요</b>. 로그인한 Google 계정의 기본 캘린더 하나만 써요.
        </p>

        <div
          className={cn(
            'mb-6 flex flex-wrap items-center gap-3 rounded-lg border p-3 text-sm',
            cal.status === 'on' && 'border-good/30 bg-good-soft/60',
            cal.status === 'broken' && 'border-crit/30 bg-crit-soft/60',
            (cal.status === 'off' || cal.status === 'unknown') && 'border-border bg-surface',
          )}
        >
          {cal.status === 'unknown' && <span className="text-muted">연결 상태를 불러오는 중…</span>}
          {cal.status === 'on' && (
            <>
              <span className={cn(PILL, 'bg-good-soft text-good')}>연결됨</span>
              <span className="min-w-[200px] flex-1 text-body">{[email, '기본 캘린더', since].filter(Boolean).join(' · ')}</span>
              <button type="button" disabled={busy} onClick={() => void disconnect()} className={GHOST}>
                연결 끊기
              </button>
            </>
          )}
          {cal.status === 'broken' && (
            <>
              <span className={cn(PILL, 'bg-crit-soft text-crit')}>연결이 끊겼어요</span>
              <span className="min-w-[200px] flex-1 text-body">Google 쪽에서 권한이 취소됐어요. 다시 연결해 주세요.</span>
              <button type="button" disabled={busy} onClick={() => void connect()} className={PRIMARY}>
                다시 연결
              </button>
            </>
          )}
          {cal.status === 'off' && (
            <>
              <span className={cn(PILL, 'border border-border bg-card text-muted')}>연결 안 됨</span>
              <span className="min-w-[200px] flex-1 text-body">연결하면 오늘 일정 카드와 할 일의 [캘린더에도 넣기]가 켜져요.</span>
              <button type="button" disabled={busy} onClick={() => void connect()} className={PRIMARY}>
                {busy ? '여는 중…' : '연결'}
              </button>
            </>
          )}
        </div>

        <ul className="list-disc border-t border-border pt-4 pl-5 text-[13px] leading-relaxed text-muted">
          <li>[연결]을 누르면 Google 허락 화면이 한 번 열려요. 로그인은 다시 하지 않아요. <b className="text-body">로그인한 계정과 같은 계정</b>으로 허락해야 연결돼요.</li>
          <li>‘Google에서 확인하지 않은 앱’ 화면이 나오면 [고급] → [WorkBrain(으)로 이동]을 눌러요. 직접 만든 앱이라 나오는 화면이에요.</li>
          <li>일정은 볼 때마다 Google에서 읽고, WorkBrain 서버에 저장하지 않아요.</li>
          <li>WorkBrain이 넣은 일정만 고치거나 지워요. 다른 일정은 건드리지 않아요.</li>
          <li>연결 열쇠는 서버에 암호화해 두고, 화면에는 보이지 않아요. 앱과 웹이 같은 연결을 함께 써요.</li>
        </ul>
      </section>

      <div className={cn(CARD, 'px-4 py-6 text-center text-sm text-muted')}>
        사용량·동의·회의록 지침·메일 발송 설정은 아직 PC 웹에 없어요. 휴대폰 앱의 더보기에서 써 주세요.
      </div>
      {toast.element}
    </>
  )
}
