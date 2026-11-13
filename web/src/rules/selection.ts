/*
 * 할 일 '선택' 모드 상태(앱 tasks/TaskSelect.kt TaskSelection, DEC-92).
 * visible = 지금 화면에 보이는 고를 수 있는 할 일(상위만 — 하위는 상위를 따라간다). 전체 선택·고른 것 세기는 이 안에서만 한다
 */

export interface SelectionState {
  active: boolean
  ids: ReadonlySet<string>
  visible: readonly string[]
}

export const EMPTY_SELECTION: SelectionState = { active: false, ids: new Set(), visible: [] }

export const startSelect = (s: SelectionState): SelectionState => ({ ...s, active: true, ids: new Set() })
export const stopSelect = (s: SelectionState): SelectionState => ({ ...s, active: false, ids: new Set() })

export function toggleSelect(s: SelectionState, id: string): SelectionState {
  const ids = new Set(s.ids)
  if (ids.has(id)) ids.delete(id)
  else ids.add(id)
  return { ...s, ids }
}

export const allSelected = (s: SelectionState) => s.visible.length > 0 && s.visible.every((id) => s.ids.has(id))

/** 보이는 것이 다 골라져 있으면 그것만 풀고, 아니면 보이는 것을 다 고른다 */
export function toggleAllSelect(s: SelectionState): SelectionState {
  const ids = new Set(s.ids)
  if (allSelected(s)) s.visible.forEach((id) => ids.delete(id))
  else s.visible.forEach((id) => ids.add(id))
  return { ...s, ids }
}

/** 화면이 그릴 때마다 알려 준다. 같으면 그대로 돌려줘 다시 그리기를 멈춘다 */
export function withVisible(s: SelectionState, visible: readonly string[]): SelectionState {
  const same = visible.length === s.visible.length && visible.every((id, i) => id === s.visible[i])
  return same ? s : { ...s, visible }
}

/** 고른 것 중 지금 보이는 것(다른 기기에서 지워졌거나 지우는 중인 것은 빠진다) */
export const pickedIds = (s: SelectionState): string[] => s.visible.filter((id) => s.ids.has(id))
