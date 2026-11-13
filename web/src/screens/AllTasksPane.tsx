import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Icons } from '../components/icons'
import { QuickAdd, QuickAddMemory } from '../components/QuickAdd'
import { Seg } from '../components/Seg'
import { useVisibleForSelection, type TaskSelection } from '../components/TaskSelect'
import { liveTasks, topIdOf, type ParaData } from '../data/para'
import { isDone, type TaskRow } from '../data/types'
import { cn } from '../lib/cn'
import { useTaskOrder } from '../lib/taskSort'
import { addedWhereText, groupTasks, inOwner, inRange, OWNERS, RANGES, type TaskGroup, type TaskOwner, type TaskRange } from '../rules/allTasks'
import { seoulToday } from '../rules/dates'
import type { Place } from '../rules/place'
import { dueDay, quickAddedText, taskComparator, UNSORTED } from '../rules/tasks'
import { CARD } from '../components/ui'

const MUTE_CHIP = 'rounded-full border border-border bg-surface px-2.5 py-[3px] text-xs font-bold text-muted'

// 보기 전환: 목록만 지금. 캘린더·매트릭스는 자리만 두고 P4에서 연다(FR-24/25, 앱과 같은 안내)
type View = 'list' | 'cal' | 'matrix'
const VIEWS = [
  { value: 'list' as const, label: '목록' },
  { value: 'cal' as const, label: '캘린더', later: 'P4' },
  { value: 'matrix' as const, label: '매트릭스', later: 'P4' },
]
const LATER_TEXT: Record<View, string> = {
  list: '',
  cal: '캘린더 보기는 P4예요 · 날짜·시각 기한을 그대로 달력에 놓아요(모호한 기한은 옆 칸에 따로)',
  matrix: '매트릭스는 P4예요 · 지금의 우선순위(중요)와 기한(급함)으로 4칸을 계산해 데이터 추가가 없어요',
}

type LineFn = (t: TaskRow, opts: { showPlace: boolean; readOnly?: boolean }) => ReactNode

/**
 * 모든 할 일(목업 S07 '모든 할 일', 앱 AllTasksPane): 프로젝트 화면에서 아무것도 안 골랐을 때 오른쪽.
 * 빠른 입력 · 기간(다음 7일·지연·전체)·담당(전체·내 할 일·팔로업) · 완료 포함 · 곳별 묶음 · 맨 아래 '기한 없음' 접힌 줄(DEC-78)
 */
export function AllTasksPane({
  d,
  places,
  line,
  reload,
  showToast,
  selection,
  focusId,
}: {
  d: ParaData
  places: Place[]
  line: LineFn
  reload: () => void
  showToast: (t: string) => void
  selection: TaskSelection
  /** 검색에서 찾아간 정리 안 된 할 일 */
  focusId: string | null
}) {
  const [range, setRange] = useState<TaskRange>('next7')
  const [owner, setOwner] = useState<TaskOwner>('all')
  const [showDone, setShowDone] = useState(false)
  const [showNoDue, setShowNoDue] = useState(false)
  const today = seoulToday()
  const by = taskComparator(useTaskOrder())
  // 묶음 안은 고른 정렬, 끝낸 것은 아래로
  const sorted = (rows: TaskRow[]) => [...rows].sort((a, b) => Number(isDone(a)) - Number(isDone(b)) || by(a, b))

  const live = liveTasks(d)
  const open = live.filter((t) => !isDone(t))
  const doneOk = (t: TaskRow) => showDone || !isDone(t)
  const groups = groupTasks(
    places,
    live.filter((t) => inRange(t, range, today) && inOwner(t, owner) && doneOk(t)),
  ).map((g): TaskGroup => ({ ...g, rows: sorted(g.rows) }))
  // '다음 7일'에서는 날짜 없는 할 일(기한 없음·날짜 없는 모호 기한)이 안 보여 맨 아래 접힌 줄로 둔다(DEC-78).
  // '전체'는 이미 묶음 안에 있고, '지연'은 날짜가 있어야 해서 이 줄이 없다. 순서는 위 묶음과 같은 곳 순서
  const noDue =
    range === 'next7'
      ? groupTasks(
          places,
          live.filter((t) => dueDay(t) === null && inOwner(t, owner) && doneOk(t)),
        ).flatMap((g) => sorted(g.rows))
      : []
  // 검색에서 찾아간 할 일이 지금 거르기에 가려 있으면 풀어 보이게 한다(앱 TaskFocus). 찾아간 그때 한 번만
  const focusTop = focusId ? topIdOf(d, focusId) : null
  useEffect(() => {
    const t = live.find((x) => x.id === focusTop)
    if (!t) return
    if (noDue.some((x) => x.id === t.id)) setShowNoDue(true)
    else if (!groups.some((g) => g.rows.some((x) => x.id === t.id))) {
      setRange('all')
      setOwner('all')
      if (isDone(t)) setShowDone(true)
    }
  }, [focusTop])
  useVisibleForSelection(selection, [...groups.flatMap((g) => g.rows), ...(showNoDue ? noDue : [])].map((t) => t.id))

  return (
    <>
      <div className="mb-1 text-xs text-muted">{d.workspace.name}</div>
      <div className="mb-2 flex flex-wrap items-center gap-2.5">
        <span className="text-all-tasks">
          <Icons.allTasks size={24} />
        </span>
        <h2 className="text-[26px] font-bold tracking-[-0.16px]">모든 할 일</h2>
        <div className="ml-auto">
          <Seg label="보기" items={VIEWS} value="list" onChange={() => {}} onLater={(v) => showToast(LATER_TEXT[v])} />
        </div>
      </div>
      <p className="mb-5 text-[15px] text-body">
        이 공간의 <b className="text-ink">프로젝트·반복실행</b> 할 일을 프로젝트별로 묶어 봐요. 오늘 할 일은 <Link to="/">오늘</Link> 화면에서 봐요.
      </p>

      {!selection.active && (
        <section aria-label="빠른 입력" className={cn(CARD, 'mb-4 pt-4 pb-1')}>
          <QuickAdd
            places={places}
            defaultPlace={QuickAddMemory.last(places)}
            noDueMeansToday={false}
            onMessage={showToast}
            onAdded={(r) => {
              showToast(quickAddedText(r) + addedWhereText(r, range, owner, today))
              reload()
            }}
          />
        </section>
      )}

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Seg
          label="기간"
          items={RANGES.map((x) => ({ ...x, count: open.filter((t) => inRange(t, x.value, today) && inOwner(t, owner)).length, alert: x.value === 'late' }))}
          value={range}
          onChange={setRange}
        />
        <Seg
          label="담당"
          items={OWNERS.map((x) => ({ ...x, count: open.filter((t) => inRange(t, range, today) && inOwner(t, x.value)).length }))}
          value={owner}
          onChange={setOwner}
        />
        <label className="ml-auto flex cursor-pointer items-center gap-1.5 text-[13px] text-muted">
          <input type="checkbox" checked={showDone} onChange={(e) => setShowDone(e.target.checked)} className="size-4 accent-primary" />
          완료 포함
        </label>
      </div>

      {groups.length === 0 && (
        <div className={cn(CARD, 'mb-4 px-6 py-10 text-center')}>
          <p className="text-[15px] font-semibold text-ink">{range === 'late' ? '지연된 할 일이 없어요' : '여기엔 할 일이 없어요'}</p>
          <p className="mt-1 text-[13px] text-muted">다른 탭을 보거나 위 칸에 한 줄로 추가해 보세요.</p>
        </div>
      )}

      {groups.map((g) => (
        <section key={g.place ? `${g.place.type}:${g.place.id}` : 'unsorted'} aria-label={g.place?.name ?? UNSORTED} className={cn(CARD, 'mb-4')}>
          <GroupHead g={g} />
          {!g.place && <p className="border-b border-border px-4 pb-2 text-[13px] text-muted">할 일을 눌러 ‘어디에’를 고르면 그 프로젝트·반복실행으로 옮겨져요.</p>}
          {g.rows.map((t) => line(t, { showPlace: false }))}
        </section>
      ))}

      {noDue.length > 0 && (
        <section aria-label="기한 없음" className={cn(CARD, 'mb-4')}>
          <button
            type="button"
            onClick={() => setShowNoDue((o) => !o)}
            aria-expanded={showNoDue}
            className="flex min-h-12 w-full items-center gap-1 px-4 text-left text-[13px] font-semibold text-muted"
          >
            {showNoDue ? `기한 없음 ${noDue.length}개 접기` : `기한 없음 ${noDue.length}개`}
            <Icons.chevron open={showNoDue} />
          </button>
          {showNoDue && <div className="border-t border-border">{noDue.map((t) => line(t, { showPlace: true }))}</div>}
        </section>
      )}

      <p className="text-xs leading-relaxed text-muted">
        빠른 입력은 <b>오늘·내일·모레, 10/24, 금요일, 다음 주 월요일, 3시·15:00</b>, <b>!높음</b>, <b>#프로젝트</b>, <b>@사람(팔로업)</b>을 규칙으로 알아봐요(AI 비용 없음). 못 알아본
        말은 제목에 그대로 남아요. 기한은 <b>날짜만</b> / <b>날짜+시각</b> / <b>모호(확인 필요)</b>를 구분해 보존해요. 회의에서 나온 할 일은 기록 화면에서 골라 추가해요.
      </p>
    </>
  )
}

/** 목업 .grp-h: 이름(누르면 그 상세) · N건 · 오른쪽 종류 칩 */
function GroupHead({ g }: { g: TaskGroup }) {
  const p = g.place
  return (
    <div className={cn('flex items-center gap-2 px-4 py-3', p && 'border-b border-border')}>
      {p ? (
        <Link to={`/projects/${p.type === 'PROJECT' ? 'p' : 'a'}:${p.id}`} className="flex min-w-0 items-center gap-1.5 text-[15px] font-bold text-ink hover:text-primary">
          <span className={p.type === 'PROJECT' ? 'text-tree-folder' : 'text-area-icon'}>{p.type === 'PROJECT' ? <Icons.folderBig /> : <Icons.repeatBig />}</span>
          <span className="truncate">{p.name}</span>
        </Link>
      ) : (
        <span className="flex items-center gap-1.5 text-[15px] font-bold text-ink">
          <span className="text-muted"><Icons.review /></span>
          {UNSORTED}
        </span>
      )}
      <span className="flex-none text-xs font-semibold text-muted tabular-nums">{g.rows.length}건</span>
      {p && <span className={cn(MUTE_CHIP, 'ml-auto flex-none')}>{p.type === 'PROJECT' ? '프로젝트' : '반복실행'}</span>}
    </div>
  )
}
