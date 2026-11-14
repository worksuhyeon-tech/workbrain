import { useSyncExternalStore } from 'react'
import { deleteTask } from '../data/server'
import type { TaskRow } from '../data/types'

/*
 * 할 일 지우기(앱 tasks/TaskDeletes.kt와 같은 동작): 줄은 바로 빠지고 '삭제되었어요 · 되돌리기'가 뜬다.
 * 서버에서는 그 알림이 끝난 뒤에 지운다 — 되돌리기를 누르면 서버에는 아무 일도 없었던 것이 된다(이력·버전 그대로).
 * 다른 화면으로 가도 지우기가 끝나도록 화면이 아니라 이 모듈이 기다린다. 창을 그 사이 닫으면 지우지 않은 채 남는다(잃는 쪽보다 안전)
 */

// 알림(2.8초)의 되돌리기가 끝난 뒤에 지운다
const COMMIT_MS = 3300

let hidden: ReadonlySet<string> = new Set()
let failure: string | null = null
const waiting = new Map<string, number>()
const listeners = new Set<() => void>()

const emit = () => listeners.forEach((l) => l())
const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

function setHidden(next: ReadonlySet<string>) {
  hidden = next
  emit()
}

function fail(t: TaskRow, text: string) {
  const next = new Set(hidden)
  next.delete(t.id)
  failure = text
  setHidden(next)
}

export const TaskDeletes = {
  /**
   * beforeCommit: 되돌리기가 지나 서버에서 지우기 직전에 할 일(캘린더 일정 지우기 — 할 일을 지우면 일정 연결도 함께 지워져
   * 그 뒤에는 어느 일정인지 알 수 없다, DEC-105). 실패해도 할 일은 지운다(일정만 캘린더에 남음)
   */
  delete(t: TaskRow, beforeCommit?: () => Promise<void>) {
    if (waiting.has(t.id)) return
    setHidden(new Set([...hidden, t.id]))
    waiting.set(
      t.id,
      window.setTimeout(async () => {
        waiting.delete(t.id)
        if (beforeCommit) await beforeCommit().catch((e: unknown) => console.warn('before delete failed', e))
        try {
          if (!(await deleteTask(t))) fail(t, `‘${t.title}’은(는) 다른 기기에서 먼저 바뀌어 지우지 않았어요. 새로 불러왔어요`)
        } catch {
          fail(t, `‘${t.title}’을(를) 지우지 못했어요. 인터넷 연결을 확인해 주세요`)
        }
      }, COMMIT_MS),
    )
  },
  /** 알림의 되돌리기. 아직 서버에 보내기 전이라 숨긴 것만 푼다 */
  undo(id: string) {
    window.clearTimeout(waiting.get(id))
    waiting.delete(id)
    const next = new Set(hidden)
    next.delete(id)
    setHidden(next)
  },
  /** 화면이 실패 문구를 띄운 뒤 비운다 */
  consumeFailure() {
    failure = null
    emit()
  },
}

/** 목록에서 숨길 할 일(지우는 중이거나 지운 것). 바뀌면 화면이 다시 그려진다 */
export function useHiddenTasks(): ReadonlySet<string> {
  return useSyncExternalStore(subscribe, () => hidden)
}

/** 지우지 못했을 때 알릴 문구(그 할 일은 목록에 다시 보인다). 화면이 띄우고 consumeFailure로 비운다 */
export function useDeleteFailure(): string | null {
  return useSyncExternalStore(subscribe, () => failure)
}
