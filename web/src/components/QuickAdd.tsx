import { useState } from 'react'
import { CalendarError, insertEvent } from '../data/calendar'
import { createTask, saveFailText } from '../data/server'
import { CalendarStore, useCalendar } from '../lib/calendarStore'
import { cn } from '../lib/cn'
import { PriorityMark } from './TaskLine'
import { seoulToday, type LocalDate } from '../rules/dates'
import type { Place } from '../rules/place'
import { choosePlace, parseQuick, type QuickTask } from '../rules/quickParse'
import { newTaskFields, quickToEdit, UNSORTED } from '../rules/tasks'

const MEMORY_KEY = 'wb-quick-place'

/**
 * 빠른 입력 넣을 곳 기억(앱 QuickAddMemory, DEC-91): 마지막에 넣은 곳을 이 브라우저에 기억한다.
 * 그곳이 지금 보관되었거나 없어졌으면 '정리 안 됨'
 */
export const QuickAddMemory = {
  last(places: Place[]): Place | null {
    try {
      const saved = localStorage.getItem(MEMORY_KEY)
      return places.find((p) => `${p.type}:${p.id}` === saved) ?? null
    } catch {
      return null
    }
  },
  save(place: Place | null) {
    try {
      if (place) localStorage.setItem(MEMORY_KEY, `${place.type}:${place.id}`)
      else localStorage.removeItem(MEMORY_KEY)
    } catch {
      // 기억을 못 해도 넣기는 된다
    }
  },
}

type Chip = { text: string; warn?: boolean; priority?: number }

function previewChips(r: QuickTask, today: LocalDate): Chip[] {
  const out: Chip[] = [{ text: r.title || '제목 없음' }]
  if (r.dueDate) {
    const [, m, d] = r.dueDate.split('-').map(Number)
    out.push({ text: `기한 ${r.dueDate === today ? '오늘' : `${m}/${d}`}${r.dueTime ? ` ${r.dueTime}` : ''}` })
  }
  if (r.vague) out.push({ text: `기한 ${r.vague} · 확인 필요`, warn: true })
  // 줄과 같은 기호(DEC-103, 앱과 같은 칩): 높음 주황 별 · 낮음 회색 아래 화살표
  if (r.priority !== 2) out.push({ text: r.priority === 1 ? '높음' : '낮음', priority: r.priority })
  out.push({ text: r.place?.name ?? UNSORTED })
  if (r.assignee) out.push({ text: `${r.assignee} · 팔로업` })
  for (const w of r.warnings) out.push({ text: w, warn: true })
  return out
}

/** 캘린더에도 넣었는지 알림 꼬리(할 일은 이미 들어갔으니 실패는 짧게 덧붙인다) */
async function addToCalendar(taskId: string): Promise<string> {
  try {
    await insertEvent(taskId)
    return ' · 캘린더에도 넣었어요'
  } catch (e) {
    if (e instanceof CalendarError && e.reason === 'broken') {
      CalendarStore.markBroken()
      return ' · 캘린더 연결이 끊겨 캘린더에는 넣지 못했어요'
    }
    return ' · 캘린더에는 넣지 못했어요'
  }
}

/**
 * 한 줄 빠른 입력(목업 .qa + 미리보기 칩, 앱 QuickAddBar). 규칙 해석만 하고 AI는 부르지 않는다(DEC-23).
 * noDueMeansToday: 오늘 화면 — 기한을 안 적으면 오늘 마감.
 * 기한 날짜를 읽었고 구글 캘린더가 연결돼 있으면 칩 줄 끝에 [캘린더에도] 켜기 칩(매번 꺼진 채 시작, 기억하지 않음 — Design §3.16).
 * onAdded의 calendarText는 알림 끝에 붙일 캘린더 결과(안 넣었으면 '')
 */
export function QuickAdd({
  places,
  defaultPlace,
  noDueMeansToday,
  onAdded,
  onMessage,
}: {
  places: Place[]
  defaultPlace: Place | null
  noDueMeansToday: boolean
  onAdded: (r: QuickTask, calendarText: string) => void
  onMessage: (text: string) => void
}) {
  const [text, setText] = useState('')
  const [saving, setSaving] = useState(false)
  const [calOn, setCalOn] = useState(false)
  const cal = useCalendar()
  const today = seoulToday()
  const parsed = parseQuick(text, today, places, defaultPlace, noDueMeansToday)
  const showPreview = text.trim() !== ''
  const calChip = cal.status === 'on' && parsed.dueDate !== null

  const add = async () => {
    // 자정을 넘겨 미리보기와 다르게 저장되지 않도록 누른 순간 다시 해석한다(TC-26)
    const r = parseQuick(text, seoulToday(), places, defaultPlace, noDueMeansToday)
    if (!r.title) {
      onMessage('할 일 제목을 입력해 주세요')
      return
    }
    setSaving(true)
    try {
      const id = await createTask(newTaskFields(quickToEdit(r)))
      QuickAddMemory.save(r.place)
      setText('')
      const calendarText = calOn && cal.status === 'on' && r.dueDate ? await addToCalendar(id) : ''
      setCalOn(false)
      onAdded(r, calendarText)
    } catch (e) {
      onMessage(saveFailText(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="px-4">
      <div className="flex gap-2">
        <input
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            if (!e.target.value.trim()) setCalOn(false)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.nativeEvent.isComposing) void add()
          }}
          placeholder="+ 빠르게 추가 · 예: 내일 3시 판독 업체 미팅 !높음 #프로젝트 @이름"
          aria-label="할 일 빠르게 추가"
          autoComplete="off"
          className="h-[38px] min-w-0 flex-1 rounded-md border border-border-strong bg-card px-3 text-sm text-ink"
        />
        <button
          type="button"
          onClick={() => void add()}
          disabled={saving}
          className="h-9 rounded-full border border-primary bg-primary px-5 text-sm font-semibold text-on-primary hover:bg-primary-hover disabled:opacity-60"
        >
          {saving ? '추가 중…' : '추가'}
        </button>
      </div>
      <div className="mt-1.5 mb-2 flex min-h-2 flex-wrap gap-1.5" aria-live="polite">
        {showPreview &&
          previewChips(parsed, today).map((c, i) => (
            <span
              key={i}
              className={cn(
                'rounded-full border border-border bg-surface px-[9px] py-0.5 text-xs font-semibold text-body',
                c.warn && 'border-transparent bg-warn-soft text-warn',
              )}
            >
              {c.priority !== undefined && <PriorityMark priority={c.priority} />}
              {c.text}
            </span>
          ))}
        {showPreview &&
          parsed.placeAsked &&
          parsed.placeChoices.map((p) => (
            <button
              key={`${p.type}:${p.id}`}
              type="button"
              onClick={() => setText(choosePlace(text, parsed.placeAsked!, p))}
              className="rounded-full border border-signal/40 bg-sky px-[9px] py-0.5 text-xs font-semibold text-primary"
            >
              {p.name}
            </button>
          ))}
        {showPreview && calChip && (
          <button
            type="button"
            aria-pressed={calOn}
            onClick={() => setCalOn((v) => !v)}
            className={cn(
              'rounded-full border border-dashed border-info/60 px-[9px] py-0.5 text-xs font-semibold text-info',
              calOn && 'border-solid border-info bg-info-soft',
            )}
          >
            {calOn ? '✓ 캘린더에도' : '캘린더에도'}
          </button>
        )}
      </div>
    </div>
  )
}
