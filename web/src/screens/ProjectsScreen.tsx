import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { PageHead } from '../components/AppShell'
import { Icons } from '../components/icons'
import { ParaTree, resourceIcon } from '../components/ParaTree'
import { QuickAdd } from '../components/QuickAdd'
import { useToast } from '../components/Toast'
import { useTaskWork } from '../components/useTaskWork'
import {
  loadPara,
  loadState,
  openCount,
  placesOf,
  placesOfResource,
  recordMeta,
  recordsOf,
  resourcesOf,
  stateLabel,
  tasksOf,
  type ParaData,
  type StateVersionRow,
} from '../data/para'
import { isDone, type TaskRow } from '../data/types'
import { cn } from '../lib/cn'
import { useHiddenTasks } from '../lib/taskDeletes'
import { seoulToday } from '../rules/dates'
import type { Place } from '../rules/place'
import { without } from '../rules/subtasks'
import { isEarlierDone, quickAddedText, taskComparator } from '../rules/tasks'
import { SubHead } from './TodayScreen'

const CARD = 'rounded-xl border border-border bg-card shadow-sm'

/**
 * 목업 S03 프로젝트: 왼쪽 PARA 트리, 오른쪽 고른 것의 상세(프로젝트·반복실행·자료). 주소 /projects/p:ID · a:ID · r:ID.
 * 1차(DEC-102 ①): 보기 + 할 일 다루기. 만들기·수정·보관·현재 상태 쓰기·끌어 옮기기·선택 이동은 다음 차례
 */
export function ProjectsScreen() {
  const { key = null } = useParams()
  const [data, setData] = useState<ParaData | null>(null)
  const [failed, setFailed] = useState(false)
  const hidden = useHiddenTasks()
  const toast = useToast()

  const load = useCallback(async () => {
    try {
      setData(await loadPara())
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }, [])
  const reload = useCallback(() => void load(), [load])
  useEffect(() => {
    void load()
  }, [load])

  // 지우는 중인 할 일·그 하위는 서버에서 지우기 전에도 바로 뺀다
  const d = useMemo(() => (data ? { ...data, tasks: without(data.tasks, hidden) } : null), [data, hidden])
  const places = useMemo(() => (d ? placesOf(d) : []), [d])
  const work = useTaskWork({ tasks: d?.tasks ?? [], places, sources: d?.sources ?? new Map(), reload, showToast: toast.show })

  return (
    <>
      <PageHead title="프로젝트" hand="하고 있는 일 한눈에" />
      {failed && (
        <div className="mb-4 flex items-center gap-3 rounded-lg bg-crit-soft p-3 text-sm text-crit">
          <span className="flex-1">불러오지 못했어요. 인터넷 연결을 확인해 주세요.</span>
          <button type="button" className="font-bold underline" onClick={reload}>
            다시 시도
          </button>
        </div>
      )}
      {!d && !failed && <div className="py-10 text-center text-sm text-muted">불러오는 중…</div>}
      {d && (
        <div className="grid items-start gap-6 min-[901px]:grid-cols-[288px_minmax(0,1fr)]">
          <aside className={cn(CARD, 'p-3')}>
            <ParaTree d={d} selected={key} />
          </aside>
          <div className="min-w-0">
            <Detail d={d} k={key} places={places} line={work.line} reload={reload} showToast={toast.show} />
          </div>
        </div>
      )}
      {work.drawer}
      {toast.element}
    </>
  )
}

type LineFn = (t: TaskRow, opts: { showPlace: boolean; readOnly?: boolean }) => ReactNode

function Detail({ d, k, places, line, reload, showToast }: { d: ParaData; k: string | null; places: Place[]; line: LineFn; reload: () => void; showToast: (t: string) => void }) {
  const id = k?.slice(2) ?? ''
  if (k?.startsWith('p:')) {
    const p = d.projects.find((x) => x.id === id)
    if (p) return <PlaceDetail d={d} place={{ type: 'PROJECT', id: p.id, name: p.name }} places={places} line={line} reload={reload} showToast={showToast} />
  }
  if (k?.startsWith('a:')) {
    const a = d.areas.find((x) => x.id === id)
    if (a) return <PlaceDetail d={d} place={{ type: 'AREA', id: a.id, name: a.name }} places={places} line={line} reload={reload} showToast={showToast} />
  }
  if (k?.startsWith('r:')) {
    const r = d.resources.find((x) => x.id === id)
    if (r) return <ResourceDetail d={d} id={r.id} />
  }
  return (
    <div className={cn(CARD, 'px-6 py-12 text-center text-sm text-muted')}>
      {k ? '찾는 항목이 없어요. 다른 기기에서 지웠을 수 있어요.' : '왼쪽에서 프로젝트·반복실행·자료를 고르면 여기에 열려요.'}
    </div>
  )
}

function fmtDay(iso: string): string {
  const p = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' }).formatToParts(new Date(iso))
  return `${p.find((x) => x.type === 'month')?.value}/${p.find((x) => x.type === 'day')?.value}`
}

/** 프로젝트·반복실행 상세(목업 S03 상세, 앱 ParaPanes): 머리 · (프로젝트) 현재 상태·관련 기록 · 할 일 · 자료 */
function PlaceDetail({ d, place, places, line, reload, showToast }: { d: ParaData; place: Place; places: Place[]; line: LineFn; reload: () => void; showToast: (t: string) => void }) {
  const isProject = place.type === 'PROJECT'
  const project = isProject ? d.projects.find((x) => x.id === place.id) : undefined
  const area = isProject ? d.areas.find((x) => x.id === project?.area_id) : d.areas.find((x) => x.id === place.id)
  const archived = Boolean(isProject ? project?.archived_at : area?.archived_at)
  const [earlierOpen, setEarlierOpen] = useState(false)
  const [state, setState] = useState<StateVersionRow | null | undefined>(undefined)
  const today = seoulToday()

  useEffect(() => {
    if (!isProject) return
    setState(undefined)
    loadState(place.id)
      .then(setState)
      .catch(() => setState(null))
  }, [isProject, place.id])

  const tasks = tasksOf(d, place.type, place.id)
  const by = taskComparator('priority')
  const open = tasks.filter((t) => !isDone(t)).sort(by)
  // 오늘 끝낸 것은 남기고, 어제 이전에 끝낸 것은 '이전 완료'로 접는다(DEC-74)
  const doneToday = tasks.filter((t) => isDone(t) && !isEarlierDone(t, today))
  const earlier = tasks.filter((t) => isEarlierDone(t, today))
  const resources = resourcesOf(d, place.type, place.id)
  const areaProjects = isProject ? [] : d.projects.filter((p) => p.area_id === place.id && !p.archived_at)
  // 보관된 곳은 서버가 읽기 전용으로 막으니 화면도 체크·서랍·입력을 막는다(복원 후 고치기)
  const readOnly = archived

  return (
    <>
      <div className="mb-1 text-xs text-muted">
        {d.workspace.name} › {archived ? '4. 아카이브' : isProject ? '1. 프로젝트' : '2. 반복실행'}
      </div>
      <div className="mb-2 flex flex-wrap items-center gap-2.5">
        <span className={isProject ? 'text-tree-folder' : 'text-area-icon'}>{isProject ? <Icons.folderBig size={24} /> : <Icons.repeatBig size={24} />}</span>
        <h2 className="text-[26px] font-bold tracking-[-0.16px]">{place.name}</h2>
        {isProject && area && (
          <Link to={`/projects/a:${area.id}`} className="inline-flex items-center gap-1 rounded-full border border-border bg-surface px-3 py-1 text-[13px] font-semibold text-body">
            <span className="text-area-icon"><Icons.repeat /></span>
            {area.name}
          </Link>
        )}
        {archived && <span className="rounded-full border border-border bg-surface px-3 py-1 text-xs font-bold text-muted">보관됨 · 읽기만</span>}
      </div>
      {(isProject ? project?.purpose : area?.description) && (
        <p className="mb-5 flex items-center gap-1.5 text-[15px] text-body">
          <span className="text-crit"><Icons.target /></span>
          {isProject ? project?.purpose : area?.description}
        </p>
      )}

      {isProject && (
        <div className="mb-4 grid items-start gap-4 min-[1101px]:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
          <section aria-label="현재 상태" className={cn(CARD, 'p-5')}>
            <div className="mb-3 text-sm font-bold text-muted">현재 상태</div>
            {state === undefined && <p className="text-sm text-muted">불러오는 중…</p>}
            {state === null && (
              <div className="text-center">
                <p className="text-[15px] font-semibold text-ink">아직 확정된 상태가 없어요</p>
                <p className="mt-1 text-[13px] text-muted">기록을 연결하면 AI가 정리해 제안해요. 직접 쓰기는 휴대폰 앱에서 할 수 있어요.</p>
              </div>
            )}
            {state && (
              <>
                <p className="text-[15px] leading-relaxed whitespace-pre-wrap text-ink">{state.summary || '내용 없음'}</p>
                <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-muted">
                  <span
                    className={cn(
                      'rounded-full px-2.5 py-[3px] text-xs font-bold',
                      state.status === 'paused' ? 'bg-warn-soft text-warn' : state.status === 'done' ? 'border border-border bg-surface text-muted' : 'bg-good-soft text-good',
                    )}
                  >
                    {stateLabel(state.status)}
                  </span>
                  마지막 반영 {fmtDay(state.created_at)} · {state.applied_by === 'user' ? '직접 수정' : 'AI 제안 승인'}
                </div>
              </>
            )}
          </section>
          <section aria-label="관련 기록" className={cn(CARD, 'p-5')}>
            <div className="mb-2 text-sm font-bold text-muted">관련 기록</div>
            {recordsOf(d, place.id).length === 0 && <p className="text-[13px] text-muted">연결된 기록이 없어요. 기록 화면 머리의 ‘+ 프로젝트 연결’로 이어요.</p>}
            {recordsOf(d, place.id).map((r) => (
              <Link key={r.id} to={`/records/${r.id}`} className="flex items-center gap-2 border-b border-border py-2.5 last:border-b-0">
                <span className="text-muted"><Icons.mic /></span>
                <span className="min-w-0 flex-1 truncate text-sm font-semibold text-ink">{r.title}</span>
                <span className="flex-none text-xs text-muted">{recordMeta(r)}</span>
              </Link>
            ))}
          </section>
        </div>
      )}

      {!isProject && areaProjects.length > 0 && (
        <div className="mb-4 flex flex-wrap gap-1.5">
          {areaProjects.map((p) => (
            <Link key={p.id} to={`/projects/p:${p.id}`} className="inline-flex items-center gap-1 rounded-full border border-border bg-surface px-3 py-1 text-[13px] font-semibold text-body">
              <span className="text-tree-folder"><Icons.folder /></span>
              {p.name}
            </Link>
          ))}
        </div>
      )}

      <section aria-label="할 일" className={cn(CARD, 'mb-4 pb-1')}>
        <div className="flex items-center gap-2 px-4 pt-4 pb-2">
          <h3 className="text-[17px] font-bold tracking-[-0.09px]">할 일</h3>
          <span className="text-sm font-semibold text-muted tabular-nums">{openCount(d, place.type, place.id)}</span>
        </div>
        {!readOnly && (
          <QuickAdd
            places={places}
            defaultPlace={place}
            noDueMeansToday={false}
            onMessage={showToast}
            onAdded={(r) => {
              showToast(quickAddedText(r))
              reload()
            }}
          />
        )}
        {open.length === 0 && doneToday.length === 0 && <p className="border-t border-border px-4 py-5 text-center text-sm text-muted">남은 할 일이 없어요.</p>}
        {open.map((t) => line(t, { showPlace: false, readOnly }))}
        {doneToday.length > 0 && <SubHead>오늘 완료 {doneToday.length}</SubHead>}
        {doneToday.map((t) => line(t, { showPlace: false, readOnly }))}
        {earlier.length > 0 && (
          <button type="button" onClick={() => setEarlierOpen((o) => !o)} aria-expanded={earlierOpen} className="flex w-full items-center gap-1 border-t border-border px-4 py-2.5 text-left text-xs font-bold text-muted">
            이전 완료 {earlier.length}
            <Icons.chevron open={earlierOpen} />
          </button>
        )}
        {earlierOpen && earlier.map((t) => line(t, { showPlace: false, readOnly }))}
      </section>

      {resources.length > 0 && (
        <section aria-label="자료" className={cn(CARD, 'p-5')}>
          <div className="mb-2 text-sm font-bold text-muted">자료</div>
          {resources.map((r) => (
            <Link key={r.id} to={`/projects/r:${r.id}`} className="flex items-center gap-2 border-b border-border py-2.5 last:border-b-0">
              <span className="text-muted">{resourceIcon(r.kind)}</span>
              <span className="truncate text-sm font-semibold text-ink">{r.title}</span>
            </Link>
          ))}
        </section>
      )}
    </>
  )
}

const KIND_LABEL: Record<string, string> = { link: '링크', note: '메모', 'record-ref': '기록 참조' }

/** 자료 상세(앱 자료 칸): 종류 · 내용(링크는 새 창, 메모는 글, 기록 참조는 그 기록) · 연결된 곳 */
function ResourceDetail({ d, id }: { d: ParaData; id: string }) {
  const r = d.resources.find((x) => x.id === id)!
  const linked = placesOfResource(d, r.id)
  const body = (r.kind === 'link' ? r.url : r.note)?.trim()
  return (
    <>
      <div className="mb-1 text-xs text-muted">
        {d.workspace.name} › {r.archived_at ? '4. 아카이브' : '3. 관심영역'}
      </div>
      <div className="mb-4 flex flex-wrap items-center gap-2.5">
        <span className="text-muted">{resourceIcon(r.kind)}</span>
        <h2 className="text-[26px] font-bold tracking-[-0.16px]">{r.title}</h2>
        <span className="rounded-full border border-border bg-surface px-3 py-1 text-xs font-bold text-muted">{KIND_LABEL[r.kind] ?? r.kind}</span>
        {r.archived_at && <span className="rounded-full border border-border bg-surface px-3 py-1 text-xs font-bold text-muted">보관됨 · 읽기만</span>}
      </div>
      <section className={cn(CARD, 'mb-4 p-5')}>
        {r.kind === 'record-ref' && r.record ? (
          <Link to={`/records/${r.record.id}`} className="flex items-center gap-2">
            <span className="text-muted"><Icons.mic /></span>
            <span className="text-[15px] font-semibold text-ink">{r.record.title}</span>
            <span className="text-xs text-muted">{recordMeta(r.record)}</span>
          </Link>
        ) : body ? (
          r.kind === 'link' && /^https?:\/\//.test(body) ? (
            // 서버가 http(s) 주소만 받는다. 새 창으로 연다
            <a href={body} target="_blank" rel="noopener noreferrer" className="text-[15px] break-all">
              {body}
            </a>
          ) : (
            <p className="text-[15px] leading-relaxed whitespace-pre-wrap text-ink">{body}</p>
          )
        ) : (
          <p className="text-sm text-muted">아직 내용이 없어요 · 휴대폰 앱의 [수정]에서 {r.kind === 'link' ? '주소' : '메모'}를 적어요</p>
        )}
        {r.note && r.kind === 'record-ref' && <p className="mt-3 text-[13px] whitespace-pre-wrap text-muted">{r.note}</p>}
      </section>
      <div className="mb-1 text-sm font-bold text-muted">연결된 곳</div>
      {linked.length === 0 ? (
        <p className="text-[13px] text-muted">연결된 프로젝트·반복실행이 없어요.</p>
      ) : (
        <div className="flex flex-wrap gap-1.5">
          {linked.map((p) => (
            <Link key={`${p.type}:${p.id}`} to={`/projects/${p.type === 'PROJECT' ? 'p' : 'a'}:${p.id}`} className="inline-flex items-center gap-1 rounded-full border border-border bg-surface px-3 py-1 text-[13px] font-semibold text-body">
              <span className={p.type === 'PROJECT' ? 'text-tree-folder' : 'text-area-icon'}>{p.type === 'PROJECT' ? <Icons.folder /> : <Icons.repeat />}</span>
              {p.name}
            </Link>
          ))}
        </div>
      )}
    </>
  )
}
