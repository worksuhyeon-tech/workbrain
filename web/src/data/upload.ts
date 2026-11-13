import { supabase } from '../lib/supabase'
import type { Turn } from '../rules/transcriptText'
import { AUDIO_TYPES, type ProcessStatus, type RecordKind } from '../rules/upload'

/*
 * PC에서 올리기(③, DEC-102): 앱 Server.hasConsent·grantConsent·createRecord·uploadAudio·recordReady·recordTextReady·process와 같은 표·함수.
 * 음성은 저장소 'audio' 버킷 <사용자ID>/<기록ID>/audio.<확장자>(본인 폴더만), 다 올리면 wb_record_ready가 처리 작업을 만든다
 */

function must<T>(r: { data: unknown; error: { message: string } | null }, what: string): T {
  if (r.error) {
    console.error(`${what} failed`, r.error)
    throw new Error(r.error.message)
  }
  return r.data as T
}

/** 외부 전사·분석 동의 정책 판(앱 CONSENT_POLICY_VERSION과 같음) */
const CONSENT_POLICY_VERSION = '2026-10-18'

/** 음성 외부 전사·텍스트 AI 분석 동의(FR-17). 둘 다 있어야 처리할 수 있다 */
export async function hasConsent(): Promise<boolean> {
  const rows = must<{ purpose: string }[]>(
    await supabase.from('consent_grant').select('purpose').in('purpose', ['voice-stt', 'text-ai']).is('revoked_at', null),
    'load consent',
  )
  return new Set(rows.map((r) => r.purpose)).size === 2
}

export async function grantConsent(): Promise<void> {
  must(
    await supabase.from('consent_grant').insert(['voice-stt', 'text-ai'].map((purpose) => ({ purpose, provider: 'openai', policy_version: CONSENT_POLICY_VERSION }))),
    'grant consent',
  )
}

export interface NewRecord {
  id: string
  channel: 'upload' | 'text-import'
  title: string
  occurredAt: Date
  kind: RecordKind
  attendees: string[]
}

/** 기록 행 만들기. 공간은 첫 공간(앱 workspaceId). 예전의 저장 허용 선택(storage_permission, M11)은 묻지 않아 비워 둔다(DEC-106) */
export async function createRecord(r: NewRecord): Promise<void> {
  const ws = must<{ id: string }[]>(await supabase.from('workspace').select('id').order('created_at', { ascending: true }).limit(1), 'load workspace')[0]
  if (!ws) throw new Error('기본 공간이 없어요. 다시 로그인해 주세요')
  must(
    await supabase.from('source_record').insert({
      id: r.id,
      workspace_id: ws.id,
      channel: r.channel,
      title: r.title,
      occurred_at: r.occurredAt.toISOString(),
      record_kind: r.kind,
      attendees: r.attendees,
    }),
    'create record',
  )
}

/** 음성 올리기 → 받아쓰기 준비(wb_record_ready: 음성 자산·처리 작업). 같은 기록을 다시 올리면 덮어쓴다(끊긴 업로드 재시도) */
export async function uploadAudio(recordId: string, file: File, ext: string, durationSec: number): Promise<void> {
  const uid = (await supabase.auth.getUser()).data.user?.id
  if (!uid) throw new Error('WB401')
  const path = `${uid}/${recordId}/audio.${ext}`
  must(await supabase.storage.from('audio').upload(path, file, { upsert: true, contentType: AUDIO_TYPES[ext] }), 'upload audio')
  const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer()))].map((b) => b.toString(16).padStart(2, '0')).join('')
  must(
    await supabase.rpc('wb_record_ready', { p_record_id: recordId, p_bytes: file.size, p_duration_sec: Math.round(durationSec), p_hash: hash, p_ext: ext }),
    'record ready',
  )
}

/**
 * 받아쓴 글 올리기(DEC-53): 원문을 저장하고 받아쓰기 없이 교정 단계부터 처리를 건다(서버 0022).
 * turns: 전문 보기용 화자·시각(DEC-54, 서버 0023). 서버는 AI 입력에 넣지 않는다
 */
export async function uploadText(recordId: string, text: string, turns: Turn[]): Promise<void> {
  must(await supabase.rpc('wb_record_text_ready', { p_record_id: recordId, p_text: text, ...(turns.length ? { p_turns: turns } : {}) }), 'record text ready')
}

/** 처리 함수를 한 번 불러 다음 단계를 진행한다. 단계가 없는 답은 거부(로그인 만료·기록 없음 등)라 예외 */
export async function processRecord(recordId: string): Promise<ProcessStatus> {
  const { data, error } = await supabase.functions.invoke('process-record', { body: { record_id: recordId } })
  if (error) throw error
  const body = data as { stage?: string; state?: string; error?: string } | null
  if (!body?.stage) throw new Error(body?.error ?? '처리 상태를 알 수 없어요')
  return { stage: body.stage, state: body.state ?? '', error: body.error ?? null }
}
