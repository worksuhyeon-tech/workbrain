import { isDone, type TaskRow } from '../data/types'
import { plusDays, type LocalDate } from './dates'
import type { Place } from './place'
import type { QuickTask } from './quickParse'
import { dueDay } from './tasks'

/*
 * 모든 할 일(목업 S07, 앱 ParaPanes AllTasksPane, DEC-22·78): 기간·담당 거르기, 곳별 묶음, '기한 없음' 접힌 줄.
 * 목업 기본은 다음 7일 · 전체 · 완료 빼고
 */

export type TaskRange = 'next7' | 'late' | 'all'
export type TaskOwner = 'all' | 'mine' | 'follow'

export const RANGES: { value: TaskRange; label: string }[] = [
  { value: 'next7', label: '다음 7일' },
  { value: 'late', label: '지연' },
  { value: 'all', label: '전체' },
]
export const OWNERS: { value: TaskOwner; label: string }[] = [
  { value: 'all', label: '전체' },
  { value: 'mine', label: '내 할 일' },
  { value: 'follow', label: '팔로업' },
]

/** 기한 날짜가 기간 탭 안인지(다음 7일 = 오늘부터 6일 뒤까지, 지연 = 어제까지). 줄과 빠른 입력 알림이 같이 쓴다 */
export function dayInRange(day: LocalDate | null, range: TaskRange, today: LocalDate): boolean {
  if (range === 'late') return day !== null && day < today
  if (range === 'next7') return day !== null && day >= today && day <= plusDays(today, 6)
  return true
}

/** 끝낸 일은 늦은 게 아니라 '지연'에서 뺀다 */
export const inRange = (t: TaskRow, range: TaskRange, today: LocalDate) => (range !== 'late' || !isDone(t)) && dayInRange(dueDay(t), range, today)

/** follow = 남이 맡은 일(팔로업) */
export function ownerIn(follow: boolean, owner: TaskOwner): boolean {
  if (owner === 'mine') return !follow
  if (owner === 'follow') return follow
  return true
}

export const inOwner = (t: TaskRow, owner: TaskOwner) => ownerIn(t.owner_kind === 'other', owner)

/** place가 null이면 '정리 안 됨' 묶음 */
export interface TaskGroup {
  place: Place | null
  rows: TaskRow[]
}

/** '정리 안 됨' → 프로젝트 → 반복실행 순 묶음(places 순서 그대로, 보관된 곳은 places에 없음). 빈 묶음은 뺀다 */
export function groupTasks(places: Place[], rows: TaskRow[]): TaskGroup[] {
  const placeOf = (t: TaskRow) => places.find((p) => (p.type === 'PROJECT' ? t.project_id === p.id : t.area_id === p.id))
  const unsorted = rows.filter((t) => !placeOf(t))
  const groups: TaskGroup[] = unsorted.length > 0 ? [{ place: null, rows: unsorted }] : []
  for (const p of places) {
    const mine = rows.filter((t) => (p.type === 'PROJECT' ? t.project_id === p.id : t.area_id === p.id))
    if (mine.length > 0) groups.push({ place: p, rows: mine })
  }
  return groups
}

/** 빠른 입력으로 넣은 할 일이 지금 고른 탭에 안 보이면 어디서 보이는지 알림에 붙인다(앱 QuickAddDock 알림) */
export function addedWhereText(r: QuickTask, range: TaskRange, owner: TaskOwner, today: LocalDate): string {
  const ownerHere = ownerIn(r.assignee !== null, owner)
  if (ownerHere && dayInRange(r.dueDate, range, today)) return ''
  if (ownerHere && range === 'next7' && r.dueDate === null) return ' · 맨 아래 ‘기한 없음’에 있어요'
  return ' · 지금 고른 탭 밖이라 ‘전체’에서 보여요'
}
