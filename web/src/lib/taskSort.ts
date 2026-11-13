import { useSyncExternalStore } from 'react'
import type { TaskOrder } from '../rules/tasks'

/*
 * 고른 정렬(앱 TaskSort, DEC-92): 이 브라우저에 하나 두고 오늘·프로젝트·모든 할 일이 같이 본다.
 * 바꾸면 보고 있는 화면이 바로 다시 그려진다. 기억을 못 하는 브라우저(사생활 보호 창 등)에서는 우선순위순으로 돌아간다
 */

const KEY = 'wb-task-order'
const listeners = new Set<() => void>()

function read(): TaskOrder {
  try {
    return localStorage.getItem(KEY) === 'due' ? 'due' : 'priority'
  } catch {
    return 'priority'
  }
}

let current: TaskOrder = read()

const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

export const TaskSort = {
  set(order: TaskOrder) {
    current = order
    try {
      localStorage.setItem(KEY, order)
    } catch {
      // 기억을 못 해도 이번 창에서는 바뀐다
    }
    listeners.forEach((l) => l())
  },
}

export function useTaskOrder(): TaskOrder {
  return useSyncExternalStore(subscribe, () => current)
}
