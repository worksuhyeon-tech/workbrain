/** 서버 task 행(앱 data/Server.kt TaskRow와 같은 칸, PostgREST 이름 그대로) */
export interface TaskRow {
  id: string
  title: string
  status: string
  version: number
  owner_kind: string
  assignee: string | null
  due_date: string | null
  /** 시각 있는 기한(ISO, 오프셋 포함). 서울 시각으로 보여 준다 */
  due_time: string | null
  due_ambiguous: boolean
  due_text: string | null
  /** 완료한 시각(서버 트리거가 채움, DEC-64) */
  done_at: string | null
  /** 1 높음 · 2 보통 · 3 낮음. 비어 있으면 보통 */
  priority: number | null
  project_id: string | null
  area_id: string | null
  recipients: string[] | null
  /** 하위 할 일이면 상위 ID(FR-23a, 0040) */
  parent_id: string | null
  created_at: string | null
  /** 함께 읽은 속한 곳(TASK_WITH_PLACE). 다른 조회에서는 없음 */
  project?: PlaceRef | null
  area?: PlaceRef | null
}

export interface PlaceRef {
  name: string
  archived_at: string | null
}

export interface ProjectRow {
  id: string
  name: string
  purpose: string | null
  area_id: string | null
  version: number
  archived_at: string | null
}

export interface AreaRow {
  id: string
  name: string
  description: string | null
  archived_at: string | null
}

/** 할 일 출처 한 줄('AI 제안 승인 · 회의 이름 10/14')과 그 기록. 없으면 직접 추가 */
export interface TaskSource {
  label: string
  recordId: string | null
}

/** 사용자가 고치거나 새로 만드는 할 일 값(앱 TaskEdit) */
export interface TaskEdit {
  title: string
  priority: number | null
  owner_kind: string
  assignee: string | null
  due_date: string | null
  due_time: string | null
  due_ambiguous: boolean
  due_text: string | null
  project_id: string | null
  area_id: string | null
  recipients: string[]
}

export const isDone = (t: TaskRow) => t.status === 'done'
export const isSubtask = (t: TaskRow) => t.parent_id !== null
export const placeNameOf = (t: TaskRow) => t.project?.name ?? t.area?.name ?? null
