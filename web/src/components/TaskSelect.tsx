import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { cn } from '../lib/cn'
import { TaskSort, useTaskOrder } from '../lib/taskSort'
import type { Place } from '../rules/place'
import {
  allSelected,
  EMPTY_SELECTION,
  pickedIds,
  startSelect,
  stopSelect,
  toggleAllSelect,
  toggleSelect,
  withVisible,
} from '../rules/selection'
import { UNSORTED, type TaskOrder } from '../rules/tasks'
import { Icons } from './icons'

/*
 * 할 일 목록 오른쪽 위 ⋮ 메뉴와 '선택' 모드(앱 tasks/TaskSelect.kt, DEC-92).
 * ⋮ › 선택 → 줄 앞 체크가 '고르기'로 바뀌고, 위에는 '전체 · N개 선택 · 취소', 아래에는 [이동][삭제].
 * 정렬 방식도 이 메뉴에서 고른다(깃발 = 우선순위순, 달력 = 기한순)
 */

export interface TaskSelection {
  active: boolean
  ids: ReadonlySet<string>
  /** 고른 것 중 지금 보이는 것 */
  picked: string[]
  allSelected: boolean
  canSelectAll: boolean
  start: () => void
  stop: () => void
  toggle: (id: string) => void
  toggleAll: () => void
  /** 화면이 지금 보이는 고를 수 있는 할 일(상위만)을 알린다 */
  setVisible: (ids: readonly string[]) => void
}

/** 선택 모드 상태. Esc를 누르면 끝난다 */
export function useTaskSelection(): TaskSelection {
  const [s, setS] = useState(EMPTY_SELECTION)
  const start = useCallback(() => setS(startSelect), [])
  const stop = useCallback(() => setS(stopSelect), [])
  const toggle = useCallback((id: string) => setS((x) => toggleSelect(x, id)), [])
  const toggleAll = useCallback(() => setS(toggleAllSelect), [])
  const setVisible = useCallback((ids: readonly string[]) => setS((x) => withVisible(x, ids)), [])

  useEffect(() => {
    if (!s.active) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') stop()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [s.active, stop])

  return useMemo(
    () => ({
      active: s.active,
      ids: s.ids,
      picked: pickedIds(s),
      allSelected: allSelected(s),
      canSelectAll: s.visible.length > 0,
      start,
      stop,
      toggle,
      toggleAll,
      setVisible,
    }),
    [s, start, stop, toggle, toggleAll, setVisible],
  )
}

/** 화면이 보여 주는 고를 수 있는 할 일을 선택 상태에 알린다(앱 SideEffect { selection.visible = … }) */
export function useVisibleForSelection(selection: TaskSelection, ids: readonly string[]) {
  const { setVisible } = selection
  const key = ids.join(',')
  useEffect(() => {
    setVisible(key ? key.split(',') : [])
  }, [key, setVisible])
}

const SORTS: { order: TaskOrder; label: string; icon: ReactNode }[] = [
  { order: 'priority', label: '우선순위순', icon: <Icons.flag /> },
  { order: 'due', label: '기한순', icon: <Icons.calendar /> },
]

/**
 * 오른쪽 위 ⋮(앱 TaskListMenu). onSelect가 null이면 '선택'을 뺀다(고를 할 일이 없는 화면·보관된 곳).
 * 정렬은 한 줄에 아이콘 둘을 두고 지금 것을 진하게, 누르면 바로 바뀐다
 */
export function TaskListMenu({ onSelect }: { onSelect: (() => void) | null }) {
  const [open, setOpen] = useState(false)
  const order = useTaskOrder()
  const box = useRef<HTMLDivElement>(null)

  // 메뉴 밖을 누르거나 Esc면 닫는다
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={box} className="relative">
      <button
        type="button"
        aria-label="더보기 메뉴"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="grid size-10 place-items-center rounded-full text-ink hover:bg-card"
      >
        <Icons.more />
      </button>
      {open && (
        <div role="menu" className="absolute top-full right-0 z-40 mt-1 min-w-[190px] rounded-lg border border-border bg-card py-1 shadow-md">
          {onSelect && (
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false)
                onSelect()
              }}
              className="flex h-10 w-full items-center px-4 text-left text-[15px] text-ink hover:bg-surface"
            >
              선택
            </button>
          )}
          <div className="flex h-11 items-center gap-1 pr-2.5 pl-4">
            <span className="mr-auto pr-5 text-[15px] text-ink">정렬</span>
            {SORTS.map((s) => {
              const on = s.order === order
              return (
                <button
                  key={s.order}
                  type="button"
                  role="menuitemradio"
                  aria-checked={on}
                  aria-label={`${s.label} 정렬`}
                  title={s.label}
                  onClick={() => {
                    setOpen(false)
                    TaskSort.set(s.order)
                  }}
                  className={cn('grid size-[34px] place-items-center rounded-full', on ? 'bg-pill text-on-pill' : 'text-muted hover:bg-surface')}
                >
                  {s.icon}
                </button>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

/** 선택 모드 머리 줄(화면 제목 자리): [ ] 전체 · 'N개 선택' · 취소 */
export function SelectionHeader({ selection }: { selection: TaskSelection }) {
  const n = selection.picked.length
  return (
    <div className="mb-6 flex min-h-[45px] items-center gap-3">
      <label className={cn('flex items-center gap-2 text-[13px] text-muted', selection.canSelectAll ? 'cursor-pointer' : 'opacity-50')}>
        <input
          type="checkbox"
          checked={selection.allSelected}
          disabled={!selection.canSelectAll}
          onChange={selection.toggleAll}
          className="size-[18px] accent-primary"
        />
        전체
      </label>
      <h1 className="flex-1 text-[26px] font-semibold tracking-[-0.16px]" aria-live="polite">
        {n === 0 ? '할 일 선택' : `${n}개 선택`}
      </h1>
      <button type="button" onClick={selection.stop} className="rounded-full px-4 py-2 text-[15px] font-semibold text-link hover:bg-card">
        취소
      </button>
    </div>
  )
}

/** 선택 모드 아래 막대: [이동] [삭제]. 아무것도 안 골랐으면 눌리지 않는다 */
export function SelectionBar({ count, onMove, onDelete }: { count: number; onMove: () => void; onDelete: () => void }) {
  const btn = 'inline-flex h-11 items-center gap-2 rounded-full px-6 text-[15px] font-semibold hover:bg-surface disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent'
  return (
    <div role="toolbar" aria-label="고른 할 일" className="fixed bottom-6 left-1/2 z-40 flex -translate-x-1/2 gap-1 rounded-xl border border-border bg-card p-1.5 shadow-lg">
      <button type="button" onClick={onMove} disabled={count === 0} className={cn(btn, 'text-ink')}>
        <Icons.folderBig size={18} />
        이동
      </button>
      <button type="button" onClick={onDelete} disabled={count === 0} className={cn(btn, 'text-crit')}>
        <Icons.trash />
        삭제
      </button>
    </div>
  )
}

/** 옮길 곳 고르기(앱 MoveTargetSheet): 정리 안 됨 + 1. 프로젝트 / 2. 반복실행. Esc·바깥을 누르면 닫힌다 */
export function MoveDialog({ count, places, onDismiss, onPick }: { count: number; places: Place[]; onDismiss: () => void; onPick: (to: Place | null) => void }) {
  const groups: { heading: string; items: Place[] }[] = [
    { heading: '1. 프로젝트', items: places.filter((p) => p.type === 'PROJECT') },
    { heading: '2. 반복실행', items: places.filter((p) => p.type === 'AREA') },
  ]
  const row = 'flex h-11 w-full items-center gap-3 px-5 text-left text-[15px] text-ink hover:bg-surface'
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-[rgba(9,20,30,.4)] p-4" onMouseDown={(e) => e.target === e.currentTarget && onDismiss()}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="move-title"
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            // 선택 모드까지 끝내지 않고 이 창만 닫는다
            e.stopPropagation()
            onDismiss()
          }
        }}
        className="flex max-h-[75vh] w-full max-w-[420px] flex-col rounded-xl border border-border bg-card py-4 shadow-lg"
      >
        <h2 id="move-title" className="px-5 pb-2 text-[17px] font-bold">
          할 일 {count}개를 어디로 옮길까요?
        </h2>
        <div className="overflow-y-auto">
          <button type="button" autoFocus onClick={() => onPick(null)} className={row}>
            <span className="text-muted"><Icons.review /></span>
            {UNSORTED}
          </button>
          {groups.map(
            (g) =>
              g.items.length > 0 && (
                <div key={g.heading} className="border-t border-border">
                  <div className="px-5 pt-2.5 pb-1 text-xs font-bold text-muted">{g.heading}</div>
                  {g.items.map((p) => (
                    <button key={p.id} type="button" onClick={() => onPick(p)} className={row}>
                      <span className="text-muted">{p.type === 'PROJECT' ? <Icons.folderBig /> : <Icons.repeatBig />}</span>
                      <span className="truncate">{p.name}</span>
                    </button>
                  ))}
                </div>
              ),
          )}
        </div>
        <div className="flex justify-end border-t border-border px-4 pt-3">
          <button type="button" onClick={onDismiss} className="rounded-full px-4 py-2 text-[15px] font-semibold text-link hover:bg-surface">
            취소
          </button>
        </div>
      </div>
    </div>
  )
}
