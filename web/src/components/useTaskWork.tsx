import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { createSubtask, saveFailText, setTaskDone, updateTask } from '../data/server'
import { isDone, type TaskEdit, type TaskRow, type TaskSource } from '../data/types'
import { TaskDeletes, useDeleteFailure } from '../lib/taskDeletes'
import { seoulToday } from '../rules/dates'
import type { Place } from '../rules/place'
import { parseSubtask, subCountOf, subtaskOrder, subtasksOf, usedNamesOf } from '../rules/subtasks'
import { deletedText, UNSORTED } from '../rules/tasks'
import { QuickAddMemory } from './QuickAdd'
import { TaskEditDrawer, UNSAVED_TEXT } from './TaskEditDrawer'
import { TaskLine } from './TaskLine'

const CONFLICT_TEXT = '다른 기기에서 먼저 바뀌었어요. 새로 불러왔어요'

/**
 * 할 일 목록 화면들(오늘·프로젝트 상세·모든 할 일)이 같이 쓰는 할 일 다루기: 완료 체크, 수정 서랍, 하위 펼치기·넣기·지우기.
 * tasks는 지우는 중인 것을 뺀 목록(rules/subtasks.without), 바뀐 뒤에는 reload로 다시 읽는다.
 * line(t)로 줄(펼친 하위 포함)을 그리고, drawer를 화면 끝에 둔다
 */
export function useTaskWork({
  tasks,
  places,
  sources,
  reload,
  showToast,
}: {
  tasks: TaskRow[]
  places: Place[]
  sources: Map<string, TaskSource>
  reload: () => void
  showToast: (text: string, undo?: () => void) => void
}) {
  const [busyId, setBusyId] = useState<string | null>(null)
  // 수정 서랍(목업 S07 .drawer): 연 할 일 ID, 저장 중, 저장 실패 띠
  const [editingId, setEditingId] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [editError, setEditError] = useState<string | null>(null)
  // 펼친 상위 할 일(FR-23a, 화면에 있는 동안만 기억)
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
  const deleteFailure = useDeleteFailure()
  const today = seoulToday()
  const byId = useMemo(() => new Map(tasks.map((t) => [t.id, t])), [tasks])

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

  const toggleOpen = (id: string) =>
    setOpen((o) => {
      const next = new Set(o)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  /** 할 일 한 줄(+ 펼친 하위 줄·'+ 하위 할 일'). readOnly: 보관된 곳의 할 일 — 체크·서랍을 막는다 */
  const line = (t: TaskRow, opts: { showPlace: boolean; readOnly?: boolean }): ReactNode => {
    const kids = t.parent_id ? [] : subtasksOf(tasks, t.id)
    const expanded = open.has(t.id) && kids.length > 0
    const lineProps = (x: TaskRow) => ({
      today,
      busy: busyId === x.id,
      readOnly: opts.readOnly,
      onToggle: () => void toggle(x),
      onOpen: () => openEditor(x),
    })
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
          {...lineProps(t)}
        />
        {expanded && (
          <div className="border-b border-border">
            {subtaskOrder(kids).map((c) => (
              <TaskLine key={c.id} t={c} source="" showPlace={false} compact {...lineProps(c)} />
            ))}
            {!opts.readOnly && <SubtaskAdd onAdd={(text) => void addSubtask(t, text)} />}
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

  return { line, drawer }
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
