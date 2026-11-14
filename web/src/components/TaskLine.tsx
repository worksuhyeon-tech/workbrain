import type { MouseEvent } from 'react'
import { Link } from 'react-router-dom'
import { isDone, type TaskRow } from '../data/types'
import { cn } from '../lib/cn'
import type { LocalDate } from '../rules/dates'
import type { SubCount } from '../rules/subtasks'
import { dueChipText, isLate } from '../rules/tasks'
import { Icons } from './icons'

/**
 * 우선순위 표시(DEC-103, 사용자 2026-11-12): 높음은 제목 앞 주황 별, 보통은 표시 없음, 낮음은 회색 아래 화살표.
 * 목업의 오른쪽 '높음·보통·낮음' 글자 칸 대신이다
 */
export function PriorityMark({ priority }: { priority: number | null }) {
  if (priority === 1)
    return (
      <span title="우선순위 높음" className="mr-1 inline-block align-[-2px] text-star">
        <Icons.star />
        <span className="sr-only">우선순위 높음</span>
      </span>
    )
  if (priority === 3)
    return (
      <span title="우선순위 낮음" className="mr-1 inline-block align-[-2px] text-muted">
        <Icons.low />
        <span className="sr-only">우선순위 낮음</span>
      </span>
    )
  return null
}

/**
 * 할 일 한 줄(목업 .trow, 앱 TaskLine): 체크 · (우선순위 표시) 제목(+팔로업) · (속한 곳) 출처 · 기한 칩.
 * 체크는 완료 ↔ 다시 열기, 줄을 누르면 수정 서랍 — 체크·하위 칩·폴더 이름은 각자 동작(앱 TaskLine과 같게, 11/14 사용자.
 * 목업은 제목만 링크였음). 키보드는 제목 버튼으로 연다. parentTitle이 있으면 오늘 화면의 '상위 › 하위' 줄,
 * sub가 있으면 제목 옆 '⤷ 1/3' 칩(누르면 아래로 펼침·접힘), compact는 상위 아래 펼친 하위 줄(들여 쓰고 곳·출처 줄 없이).
 * select가 있으면 선택 모드(DEC-92): 체크·제목이 '고르기'가 되고 고른 줄은 하늘색 바탕
 */
export function TaskLine({
  t,
  today,
  source,
  showPlace,
  parentTitle,
  sub,
  subOpen,
  onSubtasks,
  compact,
  busy,
  readOnly,
  select,
  flash,
  onCalendar,
  onToggle,
  onOpen,
}: {
  t: TaskRow
  today: LocalDate
  source: string
  showPlace: boolean
  parentTitle?: string | null
  sub?: SubCount | null
  subOpen?: boolean
  onSubtasks?: () => void
  compact?: boolean
  busy?: boolean
  /** 보관된 곳의 할 일: 체크·서랍을 막는다(복원 후 고치기) */
  readOnly?: boolean
  /** 선택 모드에서 고를 수 있는 줄(상위만). 고를 수 없는 줄은 readOnly로 막는다 */
  select?: { selected: boolean; onSelect: () => void }
  /** 검색에서 찾아간 줄: 잠깐 분홍으로 칠한다(앱 highlight) */
  flash?: boolean
  /** 구글 캘린더에 넣은 할 일: 기한 칩 옆 작은 캘린더 아이콘(오늘 일정 카드에서는 빼는 대신, 11/14 결정 ②) */
  onCalendar?: boolean
  onToggle: () => void
  onOpen: () => void
}) {
  const done = isDone(t)
  const late = isLate(t, today)
  const placeId = t.project_id ? `p:${t.project_id}` : t.area_id ? `a:${t.area_id}` : null
  const placeName = t.project?.name ?? t.area?.name
  const canOpen = select !== undefined || !readOnly

  const openFromRow = (e: MouseEvent<HTMLDivElement>) => {
    if (!canOpen) return
    // 체크칸·제목·하위 칩·폴더 이름은 각자 누른 것으로 처리된다(두 번 열지 않게)
    if ((e.target as HTMLElement).closest('a, button, input, label')) return
    // 글자를 끌어 고른 뒤 손을 뗀 것(복사하려는 것)은 누른 것으로 보지 않는다
    if (window.getSelection()?.toString()) return
    if (select) select.onSelect()
    else onOpen()
  }

  return (
    <div
      data-task-id={t.id}
      onClick={openFromRow}
      className={cn(
        'grid grid-cols-[24px_minmax(0,1fr)_auto] items-center gap-3 border-b border-border px-4 transition-colors duration-700 last:border-b-0',
        compact ? 'bg-card-2 py-2 pl-12' : 'py-[11px]',
        canOpen && 'cursor-pointer',
        canOpen && !select?.selected && !flash && 'hover:bg-surface hover:duration-150',
        select?.selected && 'bg-sky',
        flash && 'bg-flash',
      )}
    >
      {select ? (
        <input type="checkbox" checked={select.selected} onChange={select.onSelect} aria-label={`${t.title} 고르기`} className="size-[18px] accent-primary" />
      ) : (
        <input
          type="checkbox"
          checked={done}
          disabled={busy || readOnly}
          onChange={onToggle}
          aria-label={`${t.title} ${done ? '다시 열기' : '완료'}`}
          className="size-[18px] accent-primary"
        />
      )}
      <span className={cn('font-medium text-ink', compact ? 'text-sm' : 'text-[15px]', done && 'text-muted line-through')}>
        <button
          type="button"
          onClick={select ? select.onSelect : onOpen}
          disabled={readOnly && !select}
          className={cn('text-left enabled:cursor-pointer', done && 'line-through')}
        >
          {!t.parent_id && <PriorityMark priority={t.priority} />}
          {parentTitle && <span className="font-normal text-muted">{parentTitle} › </span>}
          {t.title}
        </button>
        {sub && (
          <button
            type="button"
            onClick={onSubtasks}
            aria-expanded={subOpen}
            aria-label={`하위 할 일 ${sub.total}개 중 ${sub.done}개 끝남, ${subOpen ? '접기' : '펼치기'}`}
            className={cn(
              'ml-1.5 inline-flex items-center gap-0.5 rounded-full px-2 py-px align-[1px] text-[11px] font-bold whitespace-nowrap no-underline',
              sub.done === sub.total ? 'bg-good-soft text-good' : 'border border-border bg-surface text-muted',
            )}
          >
            <Icons.sub />
            {sub.done === sub.total ? '하위 다 끝남' : `${sub.done}/${sub.total}`}
            <span aria-hidden="true" className="ml-0.5">{subOpen ? '⌄' : '›'}</span>
          </button>
        )}
        {/* 담당 칩은 늘 제목 다음 줄 — 제목이 짧으면 옆에 붙고 길면 내려가 들쭉날쭉하던 것(11/13 사용자, 앱과 같음) */}
        {(t.owner_kind === 'other' || t.owner_kind === 'unknown') && (
          <span className="mt-1 flex flex-wrap gap-1.5">
            {t.owner_kind === 'other' && t.assignee && (
              <span className="rounded-full bg-info-soft px-2 py-px text-[11px] font-bold whitespace-nowrap text-info">{t.assignee} · 팔로업</span>
            )}
            {t.owner_kind === 'unknown' && <span className="rounded-full bg-warn-soft px-2 py-px text-[11px] font-bold whitespace-nowrap text-warn">담당 확인</span>}
          </span>
        )}
        {!compact && (
        <span className="mt-0.5 block text-xs font-normal text-muted no-underline">
          {showPlace && placeId && placeName && (
            <Link to={`/projects/${placeId}`} className="mr-1.5 inline-flex items-center gap-1 text-xs font-bold text-body hover:text-primary">
              {t.project_id ? (
                // 폴더 색은 프로젝트 화면 트리와 같게(11/13 사용자: 오늘 화면 폴더가 프로젝트 화면과 달라 보임)
                <span className="text-tree-folder">
                  <Icons.folder />
                </span>
              ) : (
                <span className="text-area-icon">
                  <Icons.repeat />
                </span>
              )}
              {placeName}
            </Link>
          )}
          {source}
        </span>
        )}
      </span>
      <span className="inline-flex items-center gap-1.5">
        {onCalendar && (
          <span title="캘린더에 넣은 할 일" className="text-info">
            <Icons.calendar size={13} />
            <span className="sr-only">캘린더에 넣은 할 일</span>
          </span>
        )}
        <span
          className={cn(
            'rounded-full border border-border bg-surface px-2.5 py-[3px] text-xs font-bold whitespace-nowrap text-muted tabular-nums',
            late && 'border-transparent bg-crit-soft text-crit',
            t.due_ambiguous && !t.due_date && !t.due_time && 'border-transparent bg-warn-soft text-warn',
          )}
        >
          {dueChipText(t, today)}
        </span>
      </span>
    </div>
  )
}
