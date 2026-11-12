import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { PageHead } from '../components/AppShell'
import { QuickAdd, QuickAddMemory } from '../components/QuickAdd'
import { TaskLine, type SubCount } from '../components/TaskLine'
import { useToast } from '../components/Toast'
import { loadPlaces, loadSources, loadTasks, pendingProposalCount, setTaskDone } from '../data/server'
import { isDone, type TaskRow, type TaskSource } from '../data/types'
import { seoulToday } from '../rules/dates'
import type { Place } from '../rules/place'
import { groupToday, quickAddedText, seoulDate } from '../rules/tasks'

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

function subCountOf(kids: TaskRow[]): SubCount | null {
  return kids.length === 0 ? null : { done: kids.filter(isDone).length, total: kids.length }
}

/** 목업 S02 오늘(dashboard): 왼쪽 '오늘 할 일' 카드, 오른쪽 '검토 대기' 카드 */
export function TodayScreen() {
  const [data, setData] = useState<Loaded | null>(null)
  const [failed, setFailed] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const toast = useToast()
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

  const view = useMemo(() => {
    if (!data) return null
    const byId = new Map(data.tasks.map((t) => [t.id, t]))
    const kids = new Map<string, TaskRow[]>()
    for (const t of data.tasks) if (t.parent_id) kids.set(t.parent_id, [...(kids.get(t.parent_id) ?? []), t])
    return { byId, kids, groups: groupToday(todayPool(data.tasks, today), today, 'priority') }
  }, [data, today])

  const toggle = async (t: TaskRow) => {
    setBusyId(t.id)
    try {
      const ok = await setTaskDone(t, !isDone(t))
      toast.show(ok ? (isDone(t) ? `다시 열었어요 · ${t.title}` : '완료했어요 · 아래 ‘오늘 완료’에 남겨 둘게요') : CONFLICT_TEXT)
    } catch {
      toast.show('저장하지 못했어요. 인터넷 연결을 확인해 주세요')
    } finally {
      setBusyId(null)
      void load()
    }
  }

  const line = (t: TaskRow) => (
    <TaskLine
      key={t.id}
      t={t}
      today={today}
      source={data?.sources.get(t.id)?.label ?? '직접 추가'}
      showPlace
      parentTitle={t.parent_id ? (view?.byId.get(t.parent_id)?.title ?? '상위 할 일') : null}
      sub={t.parent_id ? null : subCountOf(view?.kids.get(t.id) ?? [])}
      busy={busyId === t.id}
      onToggle={() => void toggle(t)}
    />
  )

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
      {toast.element}
    </>
  )
}

function SubHead({ crit, children }: { crit?: boolean; children: ReactNode }) {
  return <div className={`border-t border-border px-4 pt-2.5 pb-1 text-xs font-bold ${crit ? 'text-crit' : 'text-muted'}`}>{children}</div>
}
