import { useEffect, useState, type ReactNode } from 'react'
import { recordPeople } from '../data/server'
import { isDone, type TaskEdit, type TaskRow } from '../data/types'
import { cn } from '../lib/cn'
import { seoulToday } from '../rules/dates'
import type { Place } from '../rules/place'
import { toEdit } from '../rules/subtasks'
import { dueChipText, seoulDate, seoulTime, UNSORTED } from '../rules/tasks'
import { Icons } from './icons'
import { Seg } from './Seg'

/** 저장이 안 됐을 때 서랍 안 띠(목업 .unsaved): 입력은 그대로 둔다 */
export const UNSAVED_TEXT = '저장하지 못했어요. 입력한 내용은 그대로 있어요 — 연결을 확인하고 다시 저장해 주세요.'

type Owner = 'me' | 'other' | 'unknown'
type DueKind = 'none' | 'date' | 'time' | 'vague'
const OWNERS: { value: Owner; label: string }[] = [
  { value: 'me', label: '나' },
  { value: 'other', label: '다른 사람 (팔로업)' },
  { value: 'unknown', label: '확인 필요' },
]
const DUES: { value: DueKind; label: string }[] = [
  { value: 'none', label: '없음' },
  { value: 'date', label: '날짜' },
  { value: 'time', label: '날짜+시각' },
  { value: 'vague', label: '모호' },
]
const DUE_NOTE: Record<DueKind, string> = {
  none: '',
  date: '날짜만 저장해요(시각·시간대 없음).',
  time: '시각은 서울 시간으로 저장해요.',
  vague: "모호한 기한은 날짜로 바꾸지 않고 '확인 필요'로 둬요.",
}
// 닫힐 때 빠지는 움직임 길이(index.css --animate-drawer-out과 같게)
const CLOSE_MS = 200
const INPUT = 'h-[38px] w-full rounded-md border border-border-strong bg-card px-3 text-sm text-ink'

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mb-4 flex flex-col gap-1.5">
      <div className="text-[13px] font-bold text-ink">{label}</div>
      {children}
    </div>
  )
}

/**
 * 할 일 수정 서랍(목업 S07 .drawer, 앱 TaskEditSheet): 제목 · 어디에 · 담당 · 받는 사람 · 기한 · 우선순위.
 * 하위의 서랍(parentTitle)은 곳·우선순위 칸 없이(상위를 따름) 맨 아래 [하위 할 일 삭제], 상위·보통 할 일은 아래 '하위 할 일' 칸.
 * 저장이 안 되면 서랍을 닫지 않고 입력을 그대로 둔 채 띠를 보여 준다.
 * 열 때 오른쪽에서 밀려 들어오고, 닫힐 때(취소·바깥·Esc·저장 성공·하위 삭제) 다시 오른쪽으로 빠진 뒤에 화면에서 없어진다(11/13 사용자).
 * onSave는 저장했으면 true(서랍을 닫음), 실패하면 false(띠를 보여 주고 그대로 둠)
 */
export function TaskEditDrawer({
  task,
  places,
  error,
  saving,
  usedNames,
  recordId,
  parentTitle,
  subtasks,
  onToggleSub,
  onOpenSub,
  onAddSub,
  onDelete,
  onDismiss,
  onSave,
}: {
  task: TaskRow
  places: Place[]
  error: string | null
  saving: boolean
  usedNames: string[]
  recordId: string | null
  parentTitle: string | null
  subtasks: TaskRow[] | null
  onToggleSub: (t: TaskRow) => void
  onOpenSub: (t: TaskRow) => void
  onAddSub: (text: string) => void
  onDelete: (() => void) | null
  onDismiss: () => void
  onSave: (e: TaskEdit) => Promise<boolean>
}) {
  const start = toEdit(task)
  const today = seoulToday()
  const [title, setTitle] = useState(start.title)
  const [placeKey, setPlaceKey] = useState(start.project_id ? `PROJECT:${start.project_id}` : start.area_id ? `AREA:${start.area_id}` : '')
  const [owner, setOwner] = useState<Owner>((['me', 'other', 'unknown'] as const).find((o) => o === start.owner_kind) ?? 'me')
  const [assignee, setAssignee] = useState(start.assignee ?? '')
  const [recipients, setRecipients] = useState<string[]>(start.recipients)
  const [newName, setNewName] = useState('')
  const [people, setPeople] = useState<string[]>([])
  const [dueKind, setDueKind] = useState<DueKind>(start.due_time ? 'time' : start.due_date ? 'date' : start.due_ambiguous ? 'vague' : 'none')
  const [date, setDate] = useState(start.due_date ?? (start.due_time ? seoulDate(start.due_time) : null) ?? '')
  const [dateTime, setDateTime] = useState(start.due_time ? `${seoulDate(start.due_time)}T${seoulTime(start.due_time)}` : '')
  const [vague, setVague] = useState(start.due_text ?? '')
  const [priority, setPriority] = useState(String(start.priority ?? 2))
  const [subText, setSubText] = useState('')
  const isSub = parentTitle !== null
  // 닫히는 중: 빠지는 움직임(0.2초)이 끝난 뒤에 실제로 닫는다. 두 번 눌러도 한 번만
  const [closing, setClosing] = useState(false)
  const close = (after: () => void) => {
    if (closing) return
    setClosing(true)
    window.setTimeout(after, CLOSE_MS)
  }

  useEffect(() => {
    if (recordId) void recordPeople(recordId).then(setPeople)
  }, [recordId])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close(onDismiss)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const valid = title.trim() !== '' && (owner !== 'other' || assignee.trim() !== '') && (dueKind !== 'date' || date !== '') && (dueKind !== 'time' || dateTime !== '')
  // 지금 곳이 보관됐으면 목록에 없으니 그 이름으로 한 줄 보여 준다(그대로 두면 저장해도 곳은 안 바뀐다)
  const currentName = task.project?.name ?? task.area?.name
  const placeMissing = placeKey !== '' && !places.some((p) => `${p.type}:${p.id}` === placeKey)
  const chipNames = [...new Set(['나', ...people, ...usedNames])]

  const save = () => {
    const [type, id] = placeKey ? placeKey.split(':') : [null, null]
    const timeDate = dateTime.slice(0, 10)
    void onSave({
      title: title.trim(),
      // 하위는 우선순위 칸이 없어 원래 값 그대로
      priority: isSub ? task.priority : Number(priority),
      owner_kind: owner,
      assignee: owner === 'other' ? assignee.trim() : null,
      due_date: dueKind === 'date' ? date : dueKind === 'time' ? timeDate : null,
      due_time: dueKind === 'time' ? `${dateTime}+09:00` : null,
      due_ambiguous: dueKind === 'vague',
      due_text: dueKind === 'vague' ? vague.trim() || '미정' : null,
      project_id: type === 'PROJECT' ? (id ?? null) : null,
      area_id: type === 'AREA' ? (id ?? null) : null,
      recipients,
    }).then((ok) => {
      if (ok) close(onDismiss)
    })
  }

  const toggleName = (n: string) => setRecipients((r) => (r.includes(n) ? r.filter((x) => x !== n) : [...r, n]))

  return (
    <div
      className={cn('fixed inset-0 z-40 flex justify-end bg-[rgba(9,20,30,.4)]', closing ? 'animate-fade-out' : 'animate-fade-in')}
      onClick={(e) => e.target === e.currentTarget && close(onDismiss)}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="drawer-title"
        className={cn('h-full w-[min(420px,100%)] overflow-y-auto border-l border-border bg-card p-6 shadow-lg', closing ? 'animate-drawer-out' : 'animate-drawer-in')}
      >
        <h2 id="drawer-title" className={cn('text-xl font-bold tracking-[-0.1px]', isSub ? 'mb-1' : 'mb-6')}>
          {isSub ? '하위 할 일 수정' : '할 일 수정'}
        </h2>
        {isSub && <p className="mb-5 text-xs text-muted">상위: {parentTitle} · 곳과 우선순위는 상위 할 일을 따라가요</p>}
        {error && <div className="mb-4 rounded-md bg-crit-soft px-2.5 py-2 text-[13px] text-crit">{error}</div>}

        <Field label="제목">
          <input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} className={INPUT} autoFocus />
        </Field>

        {!isSub && (
          <Field label="어디에">
            <select value={placeKey} onChange={(e) => setPlaceKey(e.target.value)} className={INPUT}>
              <option value="">{UNSORTED}</option>
              {placeMissing && <option value={placeKey}>{currentName ?? '보관된 곳'}</option>}
              <optgroup label="1. 프로젝트">
                {places.filter((p) => p.type === 'PROJECT').map((p) => (
                  <option key={p.id} value={`PROJECT:${p.id}`}>{p.name}</option>
                ))}
              </optgroup>
              <optgroup label="2. 반복실행">
                {places.filter((p) => p.type === 'AREA').map((p) => (
                  <option key={p.id} value={`AREA:${p.id}`}>{p.name}</option>
                ))}
              </optgroup>
            </select>
          </Field>
        )}

        <Field label="담당">
          <div>
            <Seg label="담당" items={OWNERS} value={owner} onChange={setOwner} />
          </div>
          {owner === 'other' && <input value={assignee} onChange={(e) => setAssignee(e.target.value)} placeholder="맡은 사람 이름 (예: 박지훈)" className={INPUT} />}
          <span className="text-xs text-muted">팔로업은 남이 맡은 일이에요. 내가 챙길 수 있게 모아 보여 줘요.</span>
        </Field>

        <Field label="받는 사람">
          <div className="flex flex-wrap gap-1.5">
            {[...new Set([...chipNames, ...recipients])].map((n) => (
              <button
                key={n}
                type="button"
                aria-pressed={recipients.includes(n)}
                onClick={() => toggleName(n)}
                className={cn('rounded-full border px-3 py-1 text-[13px] font-semibold', recipients.includes(n) ? 'border-pill bg-pill text-on-pill' : 'border-border bg-surface text-body')}
              >
                {n}
              </button>
            ))}
          </div>
          <div className="flex gap-2">
            <input
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.nativeEvent.isComposing && newName.trim()) {
                  setRecipients((r) => [...new Set([...r, newName.trim()])])
                  setNewName('')
                }
              }}
              placeholder="목록에 없으면 이름 적기 (예: IT 개발팀)"
              className={INPUT}
            />
            <button
              type="button"
              disabled={!newName.trim()}
              onClick={() => {
                setRecipients((r) => [...new Set([...r, newName.trim()])])
                setNewName('')
              }}
              className="shrink-0 px-2 text-sm font-semibold text-link disabled:text-muted"
            >
              추가
            </button>
          </div>
        </Field>

        <Field label="기한">
          <div>
            <Seg label="기한" items={DUES} value={dueKind} onChange={setDueKind} />
          </div>
          {dueKind === 'date' && <input type="date" value={date} onChange={(e) => setDate(e.target.value)} className={INPUT} />}
          {dueKind === 'time' && <input type="datetime-local" value={dateTime} onChange={(e) => setDateTime(e.target.value)} className={INPUT} />}
          {dueKind === 'vague' && <input value={vague} onChange={(e) => setVague(e.target.value)} placeholder="예: 다음 주, 월말" className={INPUT} />}
          {DUE_NOTE[dueKind] && <span className="text-xs text-muted">{DUE_NOTE[dueKind]}</span>}
        </Field>

        {!isSub && (
          <Field label="우선순위">
            <select value={priority} onChange={(e) => setPriority(e.target.value)} className={INPUT}>
              <option value="1">높음</option>
              <option value="2">보통</option>
              <option value="3">낮음</option>
            </select>
          </Field>
        )}

        {subtasks && (
          <Field label={`하위 할 일 ${subtasks.length}`}>
            {subtasks.length === 0 && <span className="text-xs text-muted">이 할 일에 이어지는 일을 아래에 적어요. 기한·@담당은 빠른 입력처럼 알아봐요.</span>}
            <div className="rounded-lg border border-border">
              {subtasks.map((c) => (
                <div key={c.id} className="flex items-center gap-2.5 border-b border-border px-3 py-2 last:border-b-0">
                  <input type="checkbox" checked={isDone(c)} onChange={() => onToggleSub(c)} aria-label={`${c.title} ${isDone(c) ? '다시 열기' : '완료'}`} className="size-4 accent-primary" />
                  <button type="button" onClick={() => onOpenSub(c)} className={cn('min-w-0 flex-1 truncate text-left text-sm text-ink', isDone(c) && 'text-muted line-through')}>
                    {c.title}
                  </button>
                  <span className="shrink-0 rounded-full border border-border bg-surface px-2 py-px text-[11px] font-bold text-muted">{dueChipText(c, today)}</span>
                </div>
              ))}
              <div className="flex gap-2 p-2">
                <input
                  value={subText}
                  onChange={(e) => setSubText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.nativeEvent.isComposing && subText.trim()) {
                      onAddSub(subText)
                      setSubText('')
                    }
                  }}
                  placeholder="+ 하위 할 일 (예: 내일 오류 문구 수정)"
                  className={INPUT}
                />
                <button
                  type="button"
                  disabled={!subText.trim()}
                  onClick={() => {
                    onAddSub(subText)
                    setSubText('')
                  }}
                  className="shrink-0 px-2 text-sm font-semibold text-link disabled:text-muted"
                >
                  추가
                </button>
              </div>
            </div>
          </Field>
        )}

        <div className="mt-6 flex items-center gap-2">
          <div className="flex-1" />
          <button type="button" onClick={() => close(onDismiss)} className="h-9 rounded-full border border-border-strong bg-card px-4 text-sm font-semibold text-body hover:bg-surface">
            취소
          </button>
          <button
            type="button"
            disabled={!valid || saving}
            onClick={save}
            className="h-9 rounded-full bg-primary px-5 text-sm font-semibold text-on-primary hover:bg-primary-hover disabled:opacity-50"
          >
            {saving ? '저장 중…' : '저장'}
          </button>
        </div>
        {onDelete && (
          // 잘못 넣은 하위를 상위째 지우지 않고 하나만 없애는 길(DEC-101 ⑦). 서랍은 닫히고 '되돌리기'가 뜬다
          <button type="button" disabled={saving} onClick={() => close(onDelete)} className="mt-4 flex w-full items-center justify-center gap-1.5 py-2 text-sm font-semibold text-crit">
            <Icons.trash />
            하위 할 일 삭제
          </button>
        )}
      </div>
    </div>
  )
}
