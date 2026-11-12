import { isDone, type TaskEdit, type TaskRow } from '../data/types'
import type { LocalDate } from './dates'
import type { Place } from './place'
import type { QuickTask } from './quickParse'

/*
 * 할 일 목록 규칙(앱 tasks/TaskLine.kt·TaskSelect.kt·QuickAddBar.kt를 옮김 — 고치면 두 곳을 같이, DEC-102)
 */

export const UNSORTED = '정리 안 됨'

const seoulParts = (iso: string) => {
  const t = new Date(iso)
  if (Number.isNaN(t.getTime())) return null
  const p = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
      .formatToParts(t)
      .map((x) => [x.type, x.value]),
  )
  return { date: `${p.year}-${p.month}-${p.day}`, time: `${p.hour}:${p.minute}` }
}

/** ISO 시각 → 서울 날짜·시각 */
export const seoulDate = (iso: string): LocalDate | null => seoulParts(iso)?.date ?? null
export const seoulTime = (iso: string): string | null => seoulParts(iso)?.time ?? null

/** 기한 날짜: 날짜만 있는 기한이면 그대로, 시각 있는 기한이면 서울 날짜 */
export function dueDay(t: TaskRow): LocalDate | null {
  if (t.due_date) return t.due_date
  return t.due_time ? seoulDate(t.due_time) : null
}

/** 지났는데 안 끝남 */
export const isLate = (t: TaskRow, today: LocalDate) => {
  const d = dueDay(t)
  return !isDone(t) && d !== null && d < today
}

/**
 * 어제 이전에 끝낸 할 일: 상세 할 일 카드에서 '이전 완료'로 접는다(DEC-74). 오늘(서울) 끝낸 것은 남긴다.
 * 완료 시각이 없는 옛 완료도 언제 끝냈는지 모르니 이전 완료로 본다
 */
export function isEarlierDone(t: TaskRow, today: LocalDate): boolean {
  if (!isDone(t)) return false
  const doneDay = t.done_at ? seoulDate(t.done_at) : null
  return doneDay === null || doneDay < today
}

/** 목업 기한 칩 글자: 기한 없음 / '다음 주 · 확인 필요' / (지연 · )오늘·M/d( HH:mm) */
export function dueChipText(t: TaskRow, today: LocalDate): string {
  if (t.due_ambiguous && !t.due_date && !t.due_time) return `${t.due_text ?? '모호'} · 확인 필요`
  const day = dueDay(t)
  if (!day) return '기한 없음'
  const [, m, d] = day.split('-').map(Number)
  const label = day === today ? '오늘' : `${m}/${d}`
  const time = t.due_time ? seoulTime(t.due_time) : null
  return (isLate(t, today) ? '지연 · ' : '') + label + (time ? ` ${time}` : '')
}

export type TaskOrder = 'priority' | 'due'

const FAR: LocalDate = '9999-12-31'
const timeKey = (t: TaskRow) => (t.due_time ? new Date(t.due_time).getTime() : Number.MAX_SAFE_INTEGER)
const cmp = <T,>(a: T, b: T) => (a < b ? -1 : a > b ? 1 : 0)

/** 정렬 기준(완료를 뒤로 보내는 것은 부르는 쪽). 기한이 없거나 모호한 할 일은 어느 순서든 맨 뒤 */
export function taskComparator(order: TaskOrder): (a: TaskRow, b: TaskRow) => number {
  const keys: ((t: TaskRow) => number | string)[] =
    order === 'priority'
      ? [(t) => t.priority ?? 2, (t) => dueDay(t) ?? FAR, timeKey, (t) => t.title]
      : [(t) => dueDay(t) ?? FAR, timeKey, (t) => t.priority ?? 2, (t) => t.title]
  return (a, b) => {
    for (const k of keys) {
      const c = cmp(k(a), k(b))
      if (c !== 0) return c
    }
    return 0
  }
}

/** 오늘 화면 묶음(앱 TodayScreen): 지연 · 오늘 · 챙길 팔로업 · 오늘 완료 */
export interface TodayGroups {
  late: TaskRow[]
  todays: TaskRow[]
  follow: TaskRow[]
  doneToday: TaskRow[]
}

/** tasks: 안 끝난 할 일 + 오늘 끝낸 할 일(보관된 곳 것은 뺀 상태). 오늘 할 일 = 오늘 마감 + 지연(목업 dueToday) */
export function groupToday(tasks: TaskRow[], today: LocalDate, order: TaskOrder): TodayGroups {
  const by = taskComparator(order)
  const due = tasks.filter((t) => {
    const d = dueDay(t)
    return !isDone(t) && d !== null && d <= today
  })
  const mine = due.filter((t) => t.owner_kind !== 'other')
  return {
    late: mine.filter((t) => isLate(t, today)).sort(by),
    todays: mine.filter((t) => !isLate(t, today)).sort(by),
    follow: due.filter((t) => t.owner_kind === 'other').sort(by),
    doneToday: tasks.filter(isDone).sort((a, b) => cmp(b.done_at ?? '', a.done_at ?? '')),
  }
}

/** 해석 결과 → 저장 값. 시각이 있는 기한은 서울 시각(+09:00)으로, 날짜만 있으면 날짜만 */
export function quickToEdit(r: QuickTask): TaskEdit {
  return {
    title: r.title,
    priority: r.priority,
    owner_kind: r.assignee ? 'other' : 'me',
    assignee: r.assignee,
    due_date: r.dueDate,
    due_time: r.dueDate && r.dueTime ? `${r.dueDate}T${r.dueTime}+09:00` : null,
    due_ambiguous: r.vague !== null,
    due_text: r.vague,
    project_id: r.place?.type === 'PROJECT' ? r.place.id : null,
    area_id: r.place?.type === 'AREA' ? r.place.id : null,
    recipients: [],
  }
}

/** 새 할 일 행(앱 TaskEdit.toNewTaskFields): 빈 값은 보내지 않아 DB 기본값을 쓴다. 소유자는 DB 기본값 auth.uid */
export function newTaskFields(e: TaskEdit): Record<string, unknown> {
  const f: Record<string, unknown> = { title: e.title, owner_kind: e.owner_kind }
  if (e.priority !== null) f.priority = e.priority
  if (e.assignee) f.assignee = e.assignee
  if (e.due_date) f.due_date = e.due_date
  if (e.due_time) f.due_time = e.due_time
  if (e.due_ambiguous) {
    f.due_ambiguous = true
    if (e.due_text) f.due_text = e.due_text
  }
  if (e.project_id) f.project_id = e.project_id
  if (e.area_id) f.area_id = e.area_id
  if (e.recipients.length > 0) f.recipients = e.recipients
  return f
}

/** 넣은 뒤 알림(앱 quickAddedText) */
export const quickAddedText = (r: QuickTask) => `추가했어요 · ${r.place?.name ?? UNSORTED}${r.assignee ? ' · 팔로업' : ''}`

/** 지운 뒤 알림(앱 deletedText) */
export function deletedText(tasks: TaskRow[], subtasks = 0): string {
  const head = tasks.length === 1 ? `${tasks[0]!.title} 삭제되었어요` : `할 일 ${tasks.length}개 삭제되었어요`
  return head + (subtasks > 0 ? ` · 하위 ${subtasks}개 포함` : '')
}

/** 옮긴 뒤 알림(앱 movedManyText) */
export function movedManyText(r: { moved: number; conflicts: number; failed: number }, to: Place | null): string {
  const where = to?.name ?? UNSORTED
  const head = r.moved === 0 ? '옮긴 할 일이 없어요' : `할 일 ${r.moved}개를 ‘${where}’(으)로 옮겼어요`
  const notes: string[] = []
  if (r.conflicts > 0) notes.push(`${r.conflicts}개는 다른 기기에서 먼저 바뀌어 건너뜀`)
  if (r.failed > 0) notes.push(`${r.failed}개는 저장 못 함 · 연결 확인`)
  return [head, ...notes].join(' · ')
}
