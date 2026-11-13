import { useSyncExternalStore } from 'react'
import { createRecord, grantConsent, hasConsent, processRecord, uploadAudio, uploadText, type NewRecord } from '../data/upload'
import type { Turn } from '../rules/transcriptText'
import { PROCESS_INTERVAL_MS, PROCESS_TRIES, processNext } from '../rules/upload'

/*
 * 올리기·처리 진행(③, 앱 UploadManager·ProcessWorker의 웹판). 앱은 백그라운드 작업이 이어 가지만, 서버는 스스로 처리를 진행하지 않아
 * 웹은 이 탭이 열려 있는 동안만 처리 함수를 불러 진행한다. 화면을 옮겨 다녀도 이어지고(모듈에 둠), 탭을 닫으려 하면 브라우저가 한 번 묻는다.
 * 닫혀서 멈춘 기록은 기록 상세의 [이어서 처리]로 다시 건다
 */

export interface Progress {
  title: string
  message: string
  /** 지금 진행 중(돌아가는 표시) */
  busy: boolean
  error: boolean
  /** 서버 기록이 만들어졌는지(목록에 줄이 생김) */
  created: boolean
}

let state = new Map<string, Progress>()
const listeners = new Set<() => void>()
const running = new Set<string>()
const sleep = (ms: number) => new Promise((r) => window.setTimeout(r, ms))

function set(id: string, p: Partial<Progress> & { title?: string }) {
  const prev = state.get(id)
  state = new Map(state).set(id, { title: '', message: '', busy: true, error: false, created: false, ...prev, ...p })
  listeners.forEach((l) => l())
  updateLeaveGuard()
}

// 진행 중일 때 탭을 닫으면 처리가 멈추므로 한 번 묻는다(나중에 [이어서 처리]로 이어 갈 수는 있다)
const onBeforeUnload = (e: BeforeUnloadEvent) => {
  e.preventDefault()
}
let guarded = false
function updateLeaveGuard() {
  const busy = [...state.values()].some((p) => p.busy)
  if (busy && !guarded) window.addEventListener('beforeunload', onBeforeUnload)
  if (!busy && guarded) window.removeEventListener('beforeunload', onBeforeUnload)
  guarded = busy
}

/** 처리 함수를 3초마다 불러 검토 가능·멈춤까지(최대 약 4분). 통신이 잠깐 끊기면 다섯 번까지 다시 해 본다 */
async function loop(id: string) {
  let misses = 0
  for (let i = 0; i < PROCESS_TRIES; i++) {
    let next
    try {
      next = processNext(await processRecord(id))
      misses = 0
    } catch (e) {
      console.warn('process call failed', e)
      if (++misses >= 5) {
        set(id, { message: "처리를 이어 가지 못했어요 · 기록 화면에서 '이어서 처리'를 눌러 주세요", busy: false, error: true })
        return
      }
      set(id, { message: '연결이 잠깐 끊겨 다시 시도하는 중', busy: true })
      await sleep(PROCESS_INTERVAL_MS)
      continue
    }
    if (next.done) {
      set(id, { message: next.message, busy: false, error: next.error })
      return
    }
    set(id, { message: next.label, busy: true, error: false })
    await sleep(PROCESS_INTERVAL_MS)
  }
  set(id, { message: "아직 처리 중이에요 · 기록 화면에서 '이어서 처리'를 눌러 주세요", busy: false })
}

function runOnce(id: string, work: () => Promise<void>) {
  if (running.has(id)) return
  running.add(id)
  void work().finally(() => running.delete(id))
}

export interface UploadJob {
  record: Omit<NewRecord, 'id'>
  /** 음성(확장자·길이) 또는 받아쓴 글(본문·화자 차례) */
  content: { kind: 'audio'; file: File; ext: string; durationSec: number } | { kind: 'text'; body: string; turns: Turn[] }
  /** 처음 한 번 동의를 받았으면 true */
  consentGiven: boolean
}

export const ProcessRunner = {
  /** 새로 올리기: 동의 확인 → 기록 만들기 → 올리기 → 처리. 기록 ID를 돌려준다 */
  upload(job: UploadJob): string {
    const id = crypto.randomUUID()
    set(id, { title: job.record.title, message: '준비 중' })
    runOnce(id, async () => {
      try {
        if (job.consentGiven && !(await hasConsent())) await grantConsent()
        if (!(await hasConsent())) throw new Error('외부 AI 처리 동의가 필요해요')
        await createRecord({ ...job.record, id })
        set(id, { created: true })
        if (job.content.kind === 'audio') {
          set(id, { message: `올리는 중 · ${(job.content.file.size / 1_048_576).toFixed(1)}MB` })
          await uploadAudio(id, job.content.file, job.content.ext, job.content.durationSec)
        } else {
          set(id, { message: '글 올리는 중' })
          await uploadText(id, job.content.body, job.content.turns)
        }
      } catch (e) {
        console.error('upload failed', e)
        const msg = String((e as Error)?.message ?? '')
        set(id, { message: msg.includes('동의') ? msg : '올리지 못했어요. 인터넷 연결을 확인하고 다시 올려 주세요', busy: false, error: true })
        return
      }
      await loop(id)
    })
    return id
  },

  /** 멈춘 처리를 이어서(이미 돌고 있으면 그대로) */
  follow(id: string, title: string) {
    if (running.has(id)) return
    set(id, { title, message: '다시 이어서 하는 중', busy: true, error: false, created: true })
    runOnce(id, () => loop(id))
  },

  /** 다 본 줄 치우기(끝났거나 멈춘 것만) */
  dismiss(id: string) {
    if (state.get(id)?.busy) return
    state = new Map(state)
    state.delete(id)
    listeners.forEach((l) => l())
  },
}

const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => {
    listeners.delete(l)
  }
}

/** 이 탭에서 올리거나 처리 중인 기록(기록 ID → 진행) */
export function useProcessing(): ReadonlyMap<string, Progress> {
  return useSyncExternalStore(subscribe, () => state)
}
