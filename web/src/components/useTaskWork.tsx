import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { createSubtask, loadTask, saveFailText, setTaskDone, updateTask } from '../data/server'
import { isDone, type TaskEdit, type TaskRow, type TaskSource } from '../data/types'
import { TaskDeletes, useDeleteFailure } from '../lib/taskDeletes'
import { seoulToday } from '../rules/dates'
import type { Place } from '../rules/place'
import { parseSubtask, subCountOf, subtaskOrder, subtasksOf, toEdit, usedNamesOf } from '../rules/subtasks'
import { deletedText, movedManyText, needsMove, placeFields, UNSORTED } from '../rules/tasks'
import { QuickAddMemory } from './QuickAdd'
import { TaskEditDrawer, UNSAVED_TEXT } from './TaskEditDrawer'
import { TaskLine } from './TaskLine'
import { MoveDialog, SelectionBar, type TaskSelection } from './TaskSelect'

const CONFLICT_TEXT = '다른 기기에서 먼저 바뀌었어요. 새로 불러왔어요'

/**
 * 할 일 목록 화면들(오늘·프로젝트 상세·모든 할 일)이 같이 쓰는 할 일 다루기: 완료 체크, 수정 서랍, 하위 펼치기·넣기·지우기,
 * 선택 모드의 이동·삭제(DEC-92). tasks는 지우는 중인 것을 뺀 목록(rules/subtasks.without), 바뀐 뒤에는 reload로 다시 읽는다.
 * line(t)로 줄(펼친 하위 포함)을 그리고, drawer·selectionUi를 화면 끝에 둔다
 */
export function useTaskWork({
  tasks,
  places,
  sources,
  reload,
  showToast,
  selection,
  focusId = null,
  onFocused,
}: {
  tasks: TaskRow[]
  places: Place[]
  sources: Map<string, TaskSource>
  reload: () => void
  showToast: (text: string, undo?: () => void) => void
  selection: TaskSelection
  /** 검색에서 찾아간 할 일(목록을 다 읽은 뒤에 넘긴다). 하위면 상위를 펼치고, 그 줄로 내려 잠깐 칠한다 */
  focusId?: string | null
  /** 찾아간 뒤 부른다(주소의 ?task= 지우기) */
  onFocused?: () => void
}) {
  const [busyId, setBusyId] = useState<string | null>(null)
  // 수정 서랍(목업 S07 .drawer): 연 할 일 ID, 저장 중, 저장 실패 띠
  const [editingId, setEditingId] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [editError, setEditError] = useState<string | null>(null)
  // 펼친 상위 할 일(FR-23a, 화면에 있는 동안만 기억)
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
  // 선택 모드 [이동]을 눌러 옮길 곳 창을 연 동안. 선택 모드가 끝나면(Esc·다른 곳으로 이동) 함께 닫는다
  const [moving, setMoving] = useState(false)
  useEffect(() => {
    if (!selection.active) setMoving(false)
  }, [selection.active])
  const deleteFailure = useDeleteFailure()
  const today = seoulToday()
  const byId = useMemo(() => new Map(tasks.map((t) => [t.id, t])), [tasks])
  const [flashId, setFlashId] = useState<string | null>(null)

  // 검색에서 찾아간 할 일: 하위면 상위를 펼치고 칠한다. 그 사이 지워졌으면 조용히 넘어간다
  useEffect(() => {
    if (!focusId) return
    const parentId = byId.get(focusId)?.parent_id
    if (parentId) setOpen((o) => new Set([...o, parentId]))
    if (byId.has(focusId)) setFlashId(focusId)
    onFocused?.()
  }, [focusId, byId, onFocused])

  // 화면이 거르기를 풀고 다시 그린 뒤 그 줄로 내리고, 2.4초 뒤 색을 뺀다
  useEffect(() => {
    if (!flashId) return
    const scroll = window.setTimeout(() => document.querySelector(`[data-task-id="${flashId}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }), 80)
    const clear = window.setTimeout(() => setFlashId(null), 2400)
    return () => {
      window.clearTimeout(scroll)
      window.clearTimeout(clear)
    }
  }, [flashId])

  // 지우기가 서버에서 실패하면(다른 기기에서 먼저 고침·연결 끊김) 알리고 다시 읽는다 — 그 줄은 다시 보인다
  useEffect(() => {
    if (!deleteFailure) return
    showToast(deleteFailure)
    TaskDeletes.consumeFailure()
    reload()
  }, [deleteFailure, reload, showToast])

  // 서랍은 ID로 열어 두고 매번 새로 읽은 줄을 보여 준다(하위를 넣으면 서버가 끝난 상위를 다시 열어 버전이 바뀜). 지워졌으면 닫힌다
  const editing = editingId ? (byId.get(editingId) ?? null) : null

  const toggle = async (t: TaskRow) => {
    setBusyId(t.id)
    try {
      const ok = await setTaskDone(t, !isDone(t))
      showToast(ok ? (isDone(t) ? `다시 열었어요 · ${t.title}` : '완료했어요 · 아래 ‘오늘 완료’에 남겨 둘게요') : CONFLICT_TEXT)
    } catch {
      showToast('저장하지 못했어요. 인터넷 연결을 확인해 주세요')
    } finally {
      setBusyId(null)
      reload()
    }
  }

  const openEditor = (t: TaskRow) => {
    setEditError(null)
    setEditingId(t.id)
  }

  /** 서랍 저장. 성공하면 true(서랍이 빠지는 움직임 뒤에 닫힘), 실패하면 서랍을 닫지 않고 입력을 그대로 둔 채 띠(목업 '미저장 표시') */
  const save = async (t: TaskRow, e: TaskEdit): Promise<boolean> => {
    setSaving(true)
    try {
      const row = await updateTask(t, e)
      const moved = e.project_id !== t.project_id || e.area_id !== t.area_id
      const to = places.find((p) => p.id === (e.project_id ?? e.area_id)) ?? null
      // 옮긴 곳을 다음 빠른 입력의 넣을 곳으로 기억한다(DEC-91)
      if (row && moved) QuickAddMemory.save(to)
      showToast(!row ? CONFLICT_TEXT : moved ? (to ? `‘${to.name}’에 넣었어요` : `‘${UNSORTED}’으로 옮겼어요`) : '수정했어요 · 변경 이력에 남아요')
      reload()
      return true
    } catch (err) {
      setEditError(String(err).includes('아카이브') ? saveFailText(err) : UNSAVED_TEXT)
      return false
    } finally {
      setSaving(false)
    }
  }

  /** 하위 할 일 한 줄 넣기(FR-23a). 끝낸 상위에 넣으면 서버가 상위를 다시 연다(DEC-101 ③) */
  const addSubtask = async (parent: TaskRow, text: string) => {
    const e = parseSubtask(text, seoulToday())
    if (!e) return
    try {
      await createSubtask(parent, e)
      showToast(isDone(parent) ? '하위 할 일을 넣었어요 · 끝낸 상위 할 일을 다시 열었어요' : '하위 할 일을 넣었어요')
      setOpen((o) => new Set([...o, parent.id]))
    } catch (err) {
      showToast(saveFailText(err))
    }
    reload()
  }

  /** 하위 서랍의 [하위 할 일 삭제](DEC-101 ⑦): 서랍이 빠진 뒤 줄을 빼고 '되돌리기'를 띄우며, 알림이 끝나면 서버에서 지운다 */
  const deleteSubtask = (t: TaskRow) => {
    setEditingId(null)
    TaskDeletes.delete(t)
    showToast(deletedText([t]), () => TaskDeletes.undo(t.id))
  }

  /**
   * 고른 할 일을 한 곳으로 옮긴다(앱 moveTasks). 한 건씩 버전을 확인하며 저장하고(M18), 이미 그곳에 있는 것은 건너뛴다.
   * 하위는 서버가 상위를 따라 옮긴다. 옮긴 곳은 다음 빠른 입력의 넣을 곳으로 기억한다(DEC-91)
   */
  const moveMany = async (picked: TaskRow[], to: Place | null) => {
    const targets = picked.filter((t) => needsMove(t, to))
    const results = await Promise.allSettled(targets.map((t) => updateTask(t, { ...toEdit(t), ...placeFields(to) })))
    const moved = targets.filter((_, i) => {
      const r = results[i]
      return r?.status === 'fulfilled' && r.value !== null
    })
    const failed = results.filter((r) => r.status === 'rejected').length
    if (moved.length > 0) QuickAddMemory.save(to)
    reload()
    showToast(
      movedManyText({ moved: moved.length, conflicts: targets.length - moved.length - failed, failed }, to),
      moved.length > 0 ? () => void moveBack(moved) : undefined,
    )
  }

  /** 옮기기 되돌리기: 지금 버전을 다시 읽어 원래 곳으로 */
  const moveBack = async (original: TaskRow[]) => {
    const results = await Promise.allSettled(
      original.map(async (t) => {
        const cur = await loadTask(t.id)
        return cur ? updateTask(cur, { ...toEdit(cur), project_id: t.project_id, area_id: t.area_id }) : null
      }),
    )
    const missed = results.filter((r) => r.status === 'rejected' || r.value === null).length
    reload()
    showToast(missed === 0 ? '되돌렸어요' : `${missed}개는 되돌리지 못했어요 · 다른 기기에서 바뀌었거나 연결을 확인해 주세요`)
  }

  /** 고른 할 일을 지운다: 줄은 바로 빠지고, 3초 '되돌리기'가 지나면 서버에서 지운다(DEC-90). 하위는 서버가 함께 지운다 */
  const deleteMany = (picked: TaskRow[]) => {
    if (picked.length === 0) return
    picked.forEach((t) => TaskDeletes.delete(t))
    const subs = picked.reduce((n, p) => n + subtasksOf(tasks, p.id).length, 0)
    showToast(deletedText(picked, subs), () => picked.forEach((t) => TaskDeletes.undo(t.id)))
  }

  // 고른 것 중 지금 목록에 있는 것(보이는 순서대로)
  const pickedTasks = () => selection.picked.flatMap((id) => byId.get(id) ?? [])

  const toggleOpen = (id: string) =>
    setOpen((o) => {
      const next = new Set(o)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  /**
   * 할 일 한 줄(+ 펼친 하위 줄·'+ 하위 할 일'). readOnly: 보관된 곳의 할 일 — 체크·서랍을 막는다.
   * 선택 모드에서는 상위 줄만 고르고, 하위 줄('상위 › 하위' 포함)은 고르지도 고치지도 않는다(상위를 따라감)
   */
  const line = (t: TaskRow, opts: { showPlace: boolean; readOnly?: boolean }): ReactNode => {
    const kids = t.parent_id ? [] : subtasksOf(tasks, t.id)
    const expanded = open.has(t.id) && kids.length > 0
    const selecting = selection.active
    const lineProps = (x: TaskRow) => ({
      today,
      busy: busyId === x.id,
      readOnly: opts.readOnly || selecting,
      flash: flashId === x.id,
      onToggle: () => void toggle(x),
      onOpen: () => openEditor(x),
    })
    const select = selecting && !t.parent_id && !opts.readOnly ? { selected: selection.ids.has(t.id), onSelect: () => selection.toggle(t.id) } : undefined
    return (
      <div key={t.id}>
        <TaskLine
          t={t}
          source={sources.get(t.id)?.label ?? '직접 추가'}
          showPlace={opts.showPlace}
          parentTitle={t.parent_id ? (byId.get(t.parent_id)?.title ?? '상위 할 일') : null}
          sub={subCountOf(kids)}
          subOpen={expanded}
          onSubtasks={() => toggleOpen(t.id)}
          select={select}
          {...lineProps(t)}
        />
        {expanded && (
          <div className="border-b border-border">
            {subtaskOrder(kids).map((c) => (
              <TaskLine key={c.id} t={c} source="" showPlace={false} compact {...lineProps(c)} />
            ))}
            {!opts.readOnly && !selecting && <SubtaskAdd onAdd={(text) => void addSubtask(t, text)} />}
          </div>
        )}
      </div>
    )
  }

  const drawer = editing ? (
    <TaskEditDrawer
      key={editing.id}
      task={editing}
      places={places}
      error={editError}
      saving={saving}
      usedNames={usedNamesOf(tasks)}
      recordId={sources.get(editing.id)?.recordId ?? null}
      parentTitle={editing.parent_id ? (byId.get(editing.parent_id)?.title ?? '상위 할 일') : null}
      subtasks={editing.parent_id ? null : subtasksOf(tasks, editing.id)}
      onToggleSub={(c) => void toggle(c)}
      onOpenSub={openEditor}
      onAddSub={(text) => void addSubtask(editing, text)}
      onDelete={editing.parent_id ? () => deleteSubtask(editing) : null}
      onDismiss={() => setEditingId(null)}
      onSave={(e) => save(editing, e)}
    />
  ) : null

  // 선택 모드 아래 [이동][삭제]와 옮길 곳 창. 누르면 선택 모드를 끝내고 알림(되돌리기)을 띄운다
  const selectionUi = selection.active ? (
    <>
      {/* 떠 있는 막대가 목록 맨 아래 줄을 가리지 않게 그만큼 아래를 띄운다 */}
      <div aria-hidden="true" className="h-20" />
      <SelectionBar
        count={selection.picked.length}
        onMove={() => setMoving(true)}
        onDelete={() => {
          const picked = pickedTasks()
          selection.stop()
          deleteMany(picked)
        }}
      />
      {moving && (
        <MoveDialog
          count={selection.picked.length}
          places={places}
          onDismiss={() => setMoving(false)}
          onPick={(to) => {
            const picked = pickedTasks()
            setMoving(false)
            selection.stop()
            void moveMany(picked, to)
          }}
        />
      )}
    </>
  ) : null

  return { line, drawer, selectionUi }
}

/** 펼친 하위 줄 끝의 한 줄 입력(빠른 입력과 같은 규칙으로 기한·@담당을 읽음) */
function SubtaskAdd({ onAdd }: { onAdd: (text: string) => void }) {
  const [text, setText] = useState('')
  const add = () => {
    if (!text.trim()) return
    onAdd(text)
    setText('')
  }
  return (
    <div className="flex gap-2 bg-card-2 py-2 pr-4 pl-12">
      <input
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.nativeEvent.isComposing) add()
        }}
        placeholder="+ 하위 할 일 (예: 내일 오류 문구 수정)"
        aria-label="하위 할 일 넣기"
        className="h-[34px] min-w-0 flex-1 rounded-md border border-border-strong bg-card px-3 text-sm text-ink"
      />
      <button type="button" onClick={add} disabled={!text.trim()} className="px-2 text-sm font-semibold text-link disabled:text-muted">
        추가
      </button>
    </div>
  )
}
