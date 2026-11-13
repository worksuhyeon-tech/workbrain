import { useCallback, useEffect, useRef, useState } from 'react'
import { makeMinutes, minutesStatus, type MinutesAnswer } from '../data/records'
import { minutesVerdict, NETWORK_ERROR, STATUS_CHECKS, STATUS_INTERVAL_MS } from '../rules/minutes'

const KEY_PREFIX = 'wb-minutes-key:'
// 서버 잠금(170초)과 확인 시간을 넘긴 열쇠는 다시 이어 보지 않는다
const RESUME_MS = 4 * 60_000

const sleep = (ms: number) => new Promise((r) => window.setTimeout(r, ms))

function saveKey(recordId: string, key: string | null) {
  try {
    if (key) localStorage.setItem(KEY_PREFIX + recordId, JSON.stringify({ key, at: Date.now() }))
    else localStorage.removeItem(KEY_PREFIX + recordId)
  } catch {
    // 저장소를 못 쓰는 창(사생활 보호 등)이면 이어 보기만 못 한다
  }
}

function savedKey(recordId: string): string | null {
  try {
    const raw = localStorage.getItem(KEY_PREFIX + recordId)
    const v = raw ? (JSON.parse(raw) as { key?: string; at?: number }) : null
    return v?.key && v.at && Date.now() - v.at < RESUME_MS ? v.key : null
  } catch {
    return null
  }
}

/**
 * 회의록 만들기(FR-30, 앱 MinutesWorker와 같은 흐름): 누를 때 요청 열쇠를 하나 만들고(DEC-79), 응답을 못 받으면 실패로 단정하지 않고
 * 10초마다 서버에 그 요청이 끝났는지 확인한다. 열쇠는 이 브라우저에 잠깐 적어 둬서, 만드는 중에 화면을 나갔다 와도 이어서 기다린다.
 * onMade: 다 되면(화면을 다시 읽음), onFail: 보여 줄 문구
 */
export function useMinutesMaker(recordId: string, onMade: () => Promise<unknown>, onFail: (message: string) => void) {
  const [making, setMaking] = useState(false)
  const alive = useRef(true)
  const done = useRef({ onMade, onFail })
  done.current = { onMade, onFail }

  /** 서버 상태를 확인하며 기다린다. resumed: 이전에 시작한 요청을 이어 보는 것(닿지 않았으면 조용히 끝냄) */
  const follow = useCallback(
    async (key: string, answer: MinutesAnswer | null, resumed: boolean) => {
      let message: string | null = null
      if (answer?.kind === 'failed') message = answer.message
      else if (answer?.kind !== 'done') {
        message = NETWORK_ERROR
        for (let i = 0; i < STATUS_CHECKS && alive.current; i++) {
          if (!(resumed && i === 0)) await sleep(STATUS_INTERVAL_MS)
          const status = await minutesStatus(recordId, key).catch(() => null)
          const verdict = minutesVerdict(status, answer === null)
          if (verdict.kind === 'wait') continue
          message = verdict.kind === 'made' ? null : status === 'NONE' && resumed ? '' : verdict.message
          break
        }
        // 화면을 떠났으면 다음에 열 때 이어 본다(열쇠를 지우지 않음)
        if (!alive.current) return
      }
      saveKey(recordId, null)
      setMaking(false)
      if (message === null) await done.current.onMade()
      else if (message) done.current.onFail(message)
    },
    [recordId],
  )

  const start = useCallback(() => {
    const key = crypto.randomUUID()
    saveKey(recordId, key)
    setMaking(true)
    void makeMinutes(recordId, key)
      .catch(() => null) // 응답을 못 받음: 서버는 만들고 있을 수 있다
      .then((answer) => follow(key, answer, false))
  }, [recordId, follow])

  // 만드는 중에 나갔다 돌아왔으면 그 요청을 이어서 기다린다
  useEffect(() => {
    alive.current = true
    const key = savedKey(recordId)
    if (key) {
      setMaking(true)
      void follow(key, null, true)
    }
    return () => {
      alive.current = false
    }
  }, [recordId, follow])

  return { making, start }
}
