/*
 * PC에서 올리기(③, DEC-102) 규칙. 앱 record/FileImporter.kt·UploadManager.processLoop·UploadSheet와 같은 기준(고치면 두 곳을 같이)
 */

/** 받아쓰기가 읽는 형식과 저장소 형식(서버 0019와 같은 목록, DEC-40) */
export const AUDIO_TYPES: Record<string, string> = {
  m4a: 'audio/mp4',
  mp4: 'audio/mp4',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  ogg: 'audio/ogg',
  webm: 'audio/webm',
  flac: 'audio/flac',
}
/** 받아쓰기 한 번 한도(처리 함수 STT_MAX_BYTES와 같음). 넘으면 나눠 처리하는 기능이 생길 때까지 거절 */
export const AUDIO_MAX_BYTES = 25 * 1024 * 1024
/** 받아쓴 글 상한(서버 30만 자, 한글 UTF-8 약 3바이트) */
export const TEXT_MAX_BYTES = 1024 * 1024

export type PickedFile = { kind: 'audio'; ext: string } | { kind: 'text' } | { kind: 'rejected'; reason: string }

/** 파일 이름·크기로 올릴 수 있는지(앱 FileImporter.import·importText) */
export function checkFile(name: string, size: number): PickedFile {
  let ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : ''
  if (ext === 'mpga' || ext === 'mpeg') ext = 'mp3'
  if (ext === 'txt') return size > TEXT_MAX_BYTES ? { kind: 'rejected', reason: '글 파일이 1MB를 넘어요.' } : { kind: 'text' }
  if (!AUDIO_TYPES[ext]) return { kind: 'rejected', reason: `이 형식(${ext || '알 수 없음'})은 올릴 수 없어요. m4a·mp3·wav 음성이나 클로바노트 txt 파일을 골라 주세요.` }
  if (size > AUDIO_MAX_BYTES) return { kind: 'rejected', reason: '파일이 25MB를 넘어요. 지금은 25MB(약 100분)까지 처리할 수 있어요.' }
  return { kind: 'audio', ext }
}

/** 파일 이름에서 제목(확장자 뗌, 80자) */
export function titleFromName(name: string): string | null {
  const base = (name.includes('.') ? name.slice(0, name.lastIndexOf('.')) : name).trim().slice(0, 80)
  return base || null
}

export type RecordKind = 'meeting' | 'call' | 'memo'
export const RECORD_KINDS: { value: RecordKind; label: string }[] = [
  { value: 'meeting', label: '회의' },
  { value: 'call', label: '통화' },
  { value: 'memo', label: '혼자 메모' },
]
/** 종류 처음 값: 제목에 '통화'가 있으면 통화, 나머지는 회의(앱 defaultKind) */
export const defaultKind = (title: string): RecordKind => (title.includes('통화') ? 'call' : 'meeting')

/** 처리 함수 한 번의 답: stage(STT·CORRECT·EXTRACT·REVIEWABLE), state(PENDING·RUNNING·DONE·FAILED·BUDGET_WAIT·WAITING_KEY) */
export interface ProcessStatus {
  stage: string
  state: string
  error: string | null
}

export type ProcessNext = { done: true; message: string; error: boolean } | { done: false; label: string }

/**
 * 한 번 부른 결과로 다음 걸음(앱 UploadManager.processLoop): 끝남(검토 가능) · 멈춤(키·예산 대기, 서버가 더 시도하지 않는 실패) · 계속(단계 이름)
 */
export function processNext(s: ProcessStatus): ProcessNext {
  if (s.stage === 'REVIEWABLE') return { done: true, message: '회의록·할 일 후보가 나왔어요', error: false }
  if (s.state === 'WAITING_KEY') return { done: true, message: "올리기는 끝났어요. 서버 AI 설정이 끝나면 '이어서 처리'를 눌러 주세요", error: true }
  if (s.state === 'BUDGET_WAIT') return { done: true, message: '이번 달 AI 예산 대기선을 넘어 기다리는 중이에요', error: true }
  if (s.state === 'FAILED' && (s.error?.includes('세 번') || s.error?.includes('말소리'))) return { done: true, message: s.error ?? '처리에 실패했어요', error: true }
  const label = s.stage === 'STT' ? '받아쓰는 중' : s.stage === 'CORRECT' ? '업무 용어 맞추는 중' : '회의록·할 일 정리 중'
  return { done: false, label: s.state === 'FAILED' ? `${label} · 다시 시도 중` : label }
}

/** 처리 함수를 부르는 간격·횟수: 3초마다 80번(약 4분, 앱과 같음). 그 뒤는 '이어서 처리'로 */
export const PROCESS_INTERVAL_MS = 3000
export const PROCESS_TRIES = 80
