import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { PageHead } from '../components/AppShell'
import { QuickAdd, QuickAddMemory } from '../components/QuickAdd'
import { TaskEditDrawer, UNSAVED_TEXT } from '../components/TaskEditDrawer'
import { TaskLine } from '../components/TaskLine'
import { useToast } from '../components/Toast'
import { createSubtask, loadPlaces, loadSources, loadTasks, pendingProposalCount, saveFailText, setTaskDone, updateTask } from '../data/server'
import { isDone, type TaskEdit, type TaskRow, type TaskSource } from '../data/types'
import { TaskDeletes, useDeleteFailure, useHiddenTasks } from '../lib/taskDeletes'
import { seoulToday } from '../rules/dates'
import type { Place } from '../rules/place'
import { parseSubtask, subCountOf, subtaskOrder, subtasksOf, usedNamesOf, without } from '../rules/subtasks'
import { deletedText, groupToday, quickAddedText, seoulDate, UNSORTED } from '../rules/tasks'

const HEAD_DATE = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })
const CONFLICT_TEXT = '다른 기기에서 먼저 바뀌었어요. 새로 불러왔어요'

interface Loaded {
  tasks: TaskRow[]
  places: Place[]
  sources: Map<string, TaskSource>
  pending: number | null
}

/** 오늘 화면에 오는 할 일: 안 끝난 것 + 오늘(서울) 끝낸 것, 보관된 곳의 것은 뺀다(앱 Server.todayTasks) */
function todayPool(all: TaskRow[], today: string): TaskRow[] {
  return all.filter((t) => {
    if (t.project?.archived_at || t.area?.archived_at) return false
    return !isDone(t) || (t.done_at !== null && seoulDate(t.done_at) === today)
  })
}

/** 목업 S02 오늘(dashboard): 왼쪽 '오늘 할 일' 카드, 오른쪽 '검토 대기' 카드 */
export function TodayScreen() {
  const [data, setData] = useState<Loaded | null>(null)
  const [failed, setFailed] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  // 수정 서랍(목업 S07 .drawer): 연 할 일 ID, 저장 중, 저장 실패 띠
  const [editingId, setEditingId] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [editError, setEditError] = useState<string | null>(null)
  // 펼친 상위 할 일(FR-23a, 화면에 있는 동안만 기억)
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
  const hidden = useHiddenTasks()
  const deleteFailure = useDeleteFailure()
  const toast = useToast()
  const showToast = toast.show
  const today = seoulToday()

  // 할 일이 핵심이라 그것만 실패를 알리고, 출처·검토 대기 수는 못 읽으면 비워 둔다
  const load = useCallback(async () => {
    try {
      const [tasks, places, sources, pending] = await Promise.all([
        loadTasks(),
        loadPlaces(),
        loadSources(),
        pendingProposalCount().catch(() => null),
      ])
      setData({ tasks, places, sources, pending })
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // 지우기가 서버에서 실패하면(다른 기기에서 먼저 고침·연결 끊김) 알리고 다시 읽는다 — 그 줄은 다시 보인다
  useEffect(() => {
    if (!deleteFailure) return
    showToast(deleteFailure)
    TaskDeletes.consumeFailure()
    void load()
  }, [deleteFailure, load, showToast])

  const view = useMemo(() => {
    if (!data) return null
    // 지우는 중인 할 일·그 하위는 서버에서 지우기 전에도 바로 뺀다
    const tasks = without(data.tasks, hidden)
    const byId = new Map(tasks.map((t) => [t.id, t]))
    return { tasks, byId, groups: groupToday(todayPool(tasks, today), today, 'priority') }
  }, [data, hidden, today])

  // 서랍은 ID로 열어 두고 매번 새로 읽은 줄을 보여 준다(하위를 넣으면 서버가 끝난 상위를 다시 열어 버전이 바뀜). 지워졌으면 닫힌다
  const editing = editingId ? (view?.byId.get(editingId) ?? null) : null

  const toggle = async (t: TaskRow) => {
    setBusyId(t.id)
    try {
      const ok = await setTaskDone(t, !isDone(t))
      showToast(ok ? (isDone(t) ? `다시 열었어요 · ${t.title}` : '완료했어요 · 아래 ‘오늘 완료’에 남겨 둘게요') : CONFLICT_TEXT)
    } catch {
      showToast('저장하지 못했어요. 인터넷 연결을 확인해 주세요')
    } finally {
      setBusyId(null)
      void load()
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
      const to = data?.places.find((p) => p.id === (e.project_id ?? e.area_id)) ?? null
      // 옮긴 곳을 다음 빠른 입력의 넣을 곳으로 기억한다(DEC-91)
      if (row && moved) QuickAddMemory.save(to)
      showToast(!row ? CONFLICT_TEXT : moved ? (to ? `‘${to.name}’에 넣었어요` : `‘${UNSORTED}’으로 옮겼어요`) : '수정했어요 · 변경 이력에 남아요')
      void load()
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
    void load()
  }

  /** 하위 서랍의 [하위 할 일 삭제](DEC-101 ⑦): 서랍을 닫아 '되돌리기'가 보이게 하고, 알림이 끝나면 서버에서 지운다 */
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

  const line = (t: TaskRow): ReactNode => {
    const kids = t.parent_id ? [] : subtasksOf(view?.tasks ?? [], t.id)
    const expanded = open.has(t.id) && kids.length > 0
    return (
      <div key={t.id}>
        <TaskLine
          t={t}
          today={today}
          source={data?.sources.get(t.id)?.label ?? '직접 추가'}
          showPlace
          parentTitle={t.parent_id ? (view?.byId.get(t.parent_id)?.title ?? '상위 할 일') : null}
          sub={subCountOf(kids)}
          subOpen={expanded}
          onSubtasks={() => toggleOpen(t.id)}
          busy={busyId === t.id}
          onToggle={() => void toggle(t)}
          onOpen={() => openEditor(t)}
        />
        {expanded && (
          <div className="border-b border-border">
            {subtaskOrder(kids).map((c) => (
              <TaskLine key={c.id} t={c} today={today} source="" showPlace={false} compact busy={busyId === c.id} onToggle={() => void toggle(c)} onOpen={() => openEditor(c)} />
            ))}
            <SubtaskAdd onAdd={(text) => void addSubtask(t, text)} />
          </div>
        )}
      </div>
    )
  }

  const g = view?.groups
  const openCount = g ? g.late.length + g.todays.length : 0

  return (
    <>
      <PageHead title="오늘" hand="오늘 챙길 것만 모았어요" sub={`${HEAD_DATE.format(new Date())} · 모든 프로젝트 · 확정 상태 기준`} />
      {failed && (
        <div className="mb-4 flex items-center gap-3 rounded-lg bg-crit-soft p-3 text-sm text-crit">
          <span className="flex-1">불러오지 못했어요. 인터넷 연결을 확인해 주세요.</span>
          <button type="button" className="font-bold underline" onClick={() => void load()}>
            다시 시도
          </button>
        </div>
      )}

      <div className="grid items-start gap-4 min-[1041px]:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
        <section aria-label="오늘 할 일" className="rounded-xl border border-border bg-card shadow-sm">
          <div className="flex items-center gap-2 px-4 pt-4 pb-2">
            <h2 className="text-[17px] font-bold tracking-[-0.09px]">오늘 할 일</h2>
            <span className="text-sm font-semibold text-muted tabular-nums">{g ? `${openCount}건` : ''}</span>
            <Link to="/tasks" className="ml-auto text-[13px] font-bold">
              다음 7일 · 전체 →
            </Link>
          </div>
          {data && (
            <QuickAdd
              places={data.places}
              defaultPlace={QuickAddMemory.last(data.places)}
              noDueMeansToday
              onMessage={toast.show}
              onAdded={(r) => {
                // 오늘 화면에 안 보이는 것(내일 이후·모호)은 알림에 기한을 함께 쓴다
                const shownHere = r.dueDate !== null && r.dueDate <= today
                const due = r.dueDate ? `${Number(r.dueDate.slice(5, 7))}/${Number(r.dueDate.slice(8))}` : `${r.vague} · 확인 필요`
                toast.show(quickAddedText(r) + (shownHere ? '' : ` · ${due} · ‘모든 할 일’에서 보여요`))
                void load()
              }}
            />
          )}
          {!g && !failed && <div className="px-4 py-8 text-center text-sm text-muted">불러오는 중…</div>}
          {g && (
            <>
              {g.late.length > 0 && <SubHead crit>지연 {g.late.length}</SubHead>}
              {g.late.map(line)}
              {g.todays.length > 0 && <SubHead>오늘 {g.todays.length}</SubHead>}
              {g.todays.map(line)}
              {g.follow.length > 0 && <SubHead>챙길 팔로업 {g.follow.length} · 남이 맡은 일</SubHead>}
              {g.follow.map(line)}
              {openCount === 0 && (
                <div className="border-t border-border px-4 py-6 text-center text-sm text-muted">
                  <span className="mb-1 block font-hand text-[26px] text-good">오늘 할 일 끝!</span>
                  다음 7일 일정은 <Link to="/tasks">모든 할 일</Link>에서 봐요.
                </div>
              )}
              {g.doneToday.length > 0 && <SubHead>오늘 완료 {g.doneToday.length}</SubHead>}
              {g.doneToday.map(line)}
            </>
          )}
        </section>

        <section aria-label="검토 대기" className="rounded-xl border border-border bg-card shadow-sm">
          <div className="flex items-center gap-2 px-4 pt-4 pb-2">
            <h2 className="text-[17px] font-bold tracking-[-0.09px]">검토 대기</h2>
            <span className="text-sm font-semibold text-muted tabular-nums">{data?.pending != null ? `${data.pending}건` : ''}</span>
          </div>
          <div className="flex items-center gap-2 border-t border-border px-4 py-3">
            <span className="flex-1 text-xs text-muted">AI 제안은 승인해야 반영돼요. 승인 버튼은 기록 화면과 검토함에만 있어요.</span>
            <Link to="/review" className="inline-flex h-9 items-center rounded-full bg-primary px-5 text-sm font-semibold text-on-primary hover:bg-primary-hover">
              검토함 열기
            </Link>
          </div>
        </section>
      </div>

      <footer className="mt-8 border-t border-border pt-4 text-xs text-muted">
        프로젝트별 상태·요약은 <Link to="/projects">프로젝트</Link>, 비용 상세는 <Link to="/settings">설정</Link>에서 봐요.
      </footer>
      {editing && data && view && (
        <TaskEditDrawer
          key={editing.id}
          task={editing}
          places={data.places}
          error={editError}
          saving={saving}
          usedNames={usedNamesOf(view.tasks)}
          recordId={data.sources.get(editing.id)?.recordId ?? null}
          parentTitle={editing.parent_id ? (view.byId.get(editing.parent_id)?.title ?? '상위 할 일') : null}
          subtasks={editing.parent_id ? null : subtasksOf(view.tasks, editing.id)}
          onToggleSub={(c) => void toggle(c)}
          onOpenSub={openEditor}
          onAddSub={(text) => void addSubtask(editing, text)}
          onDelete={editing.parent_id ? () => deleteSubtask(editing) : null}
          onDismiss={() => setEditingId(null)}
          onSave={(e) => save(editing, e)}
        />
      )}
      {toast.element}
    </>
  )
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

function SubHead({ crit, children }: { crit?: boolean; children: ReactNode }) {
  return <div className={`border-t border-border px-4 pt-2.5 pb-1 text-xs font-bold ${crit ? 'text-crit' : 'text-muted'}`}>{children}</div>
}
