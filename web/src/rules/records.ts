import { dayOfWeek, type LocalDate } from './dates'
import { seoulDate, seoulTime } from './tasks'

/*
 * 기록 목록·상세 규칙. 상세는 앱 review/RecordDetailScreen.kt·CandidateRow.kt·MinutesView.kt를 옮겼다(고치면 두 곳을 같이, DEC-102).
 * 목록은 앱이 휴대폰 안 녹음 목록이라 옮길 것이 없어, 웹 목업 record.html대로 서버 처리 상태로 정했다
 */

// ── 목록 ──

/** 처리 작업 한 줄(processing_job). 기록당 하나 */
export interface JobBrief {
  stage: string
  stage_state: string
}

/** 이 기록에서 나온 제안 한 줄(할 일 후보·기존 할 일 변경·프로젝트 현재 상태 제안) */
export interface ProposalBrief {
  status: string
  target_type: string
  target_id: string | null
}

/** 처리 중(실패 포함은 아님) · 처리 실패 · 정리 필요(검토 대기 제안이 있음) · 정리 끝 */
export type RecordStage = 'processing' | 'failed' | 'review' | 'done'
export type Tone = 'good' | 'warn' | 'crit' | 'info' | 'mute'

export function recordStage(job: JobBrief | null, proposals: ProposalBrief[]): RecordStage {
  if (job?.stage_state === 'FAILED') return 'failed'
  if (job?.stage !== 'REVIEWABLE') return 'processing'
  return proposals.some((p) => p.status === 'pending') ? 'review' : 'done'
}

/** 상태 칩 글자·색. 처리 중은 지금 단계로(STT 전사 · EXTRACT 분석, 예산·키 대기는 따로) */
export function stageChip(stage: RecordStage, job: JobBrief | null): { text: string; tone: Tone } {
  if (stage === 'failed') return { text: '처리 실패', tone: 'crit' }
  if (stage === 'review') return { text: '정리 필요', tone: 'warn' }
  if (stage === 'done') return { text: '정리 끝', tone: 'good' }
  if (!job) return { text: '올리는 중', tone: 'info' }
  if (job.stage_state === 'BUDGET_WAIT') return { text: '예산 대기', tone: 'warn' }
  if (job.stage_state === 'WAITING_KEY') return { text: '처리 대기', tone: 'info' }
  if (job.stage === 'STT') return { text: '전사 중', tone: 'info' }
  if (job.stage === 'EXTRACT') return { text: '분석 중', tone: 'info' }
  return { text: '처리 중', tone: 'info' }
}

const isNewTask = (p: ProposalBrief) => p.target_type === 'task' && !p.target_id

/** 상태 칩 아래 덧말(목업): '할 일 후보 5 · 변경 2' / '할 일 3 추가됨' / '회의록만 보관'. 처리 중·실패는 없음 */
export function stageNote(stage: RecordStage, proposals: ProposalBrief[]): string | null {
  if (stage === 'review') {
    const pending = proposals.filter((p) => p.status === 'pending')
    const fresh = pending.filter(isNewTask).length
    const changes = pending.length - fresh
    return [fresh > 0 && `할 일 후보 ${fresh}`, changes > 0 && `변경 ${changes}`].filter(Boolean).join(' · ')
  }
  if (stage === 'done') {
    const added = proposals.filter((p) => p.status === 'approved' && isNewTask(p)).length
    return added > 0 ? `할 일 ${added} 추가됨` : '회의록만 보관'
  }
  return null
}

/** 목록 탭: 처리 중 탭에는 실패도 넣는다(목업 '처리 중 2' = 전사 중 + 전사 실패) */
export type RecordFilter = 'all' | 'review' | 'processing' | 'done'
export const RECORD_FILTERS: { value: RecordFilter; label: string }[] = [
  { value: 'all', label: '전체' },
  { value: 'review', label: '정리 필요' },
  { value: 'processing', label: '처리 중' },
  { value: 'done', label: '정리 끝' },
]

export function inFilter(stage: RecordStage, f: RecordFilter): boolean {
  if (f === 'all') return true
  if (f === 'processing') return stage === 'processing' || stage === 'failed'
  return stage === f
}

/** 기록 머리·목록에 쓰는 종류 이름(앱 RecordMetaPill) */
export const CHANNEL_LABEL: Record<string, string> = { recording: '녹음', upload: '가져온 파일', 'call-share': '통화', 'text-import': '받아쓴 글' }

/** 42분 · 1시간 47분 · 1시간 · 1분 미만(앱 durationText) */
export function durationText(sec: number): string {
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  if (h > 0) return m > 0 ? `${h}시간 ${m}분` : `${h}시간`
  return m > 0 ? `${m}분` : '1분 미만'
}

const WEEKDAY = ['월', '화', '수', '목', '금', '토', '일']

/** 날짜 묶음 머리: '10/17 (토)' */
export function dayLabel(day: LocalDate): string {
  const [, m, d] = day.split('-').map(Number)
  return `${m}/${d} (${WEEKDAY[dayOfWeek(day) - 1]})`
}

/** 회의 시각(서울) 'M/D HH:mm'(앱 meetingTimeText). 시각이 없거나 깨졌으면 null */
export function meetingTimeText(iso: string | null): string | null {
  if (!iso) return null
  const day = seoulDate(iso)
  const time = seoulTime(iso)
  if (!day || !time) return null
  const [, m, d] = day.split('-').map(Number)
  return `${m}/${d} ${time}`
}

/** 서울 날짜 'M/D'. 시각이 없거나 깨졌으면 null */
export function mdOf(iso: string | null): string | null {
  const day = iso ? seoulDate(iso) : null
  if (!day) return null
  const [, m, d] = day.split('-').map(Number)
  return `${m}/${d}`
}

/** 기록 상세 '일시': '2026-10-16 (금) 15:00'(서울) */
export function fullTimeText(iso: string | null): string | null {
  const day = iso ? seoulDate(iso) : null
  const time = iso ? seoulTime(iso) : null
  return day && time ? `${day} (${WEEKDAY[dayOfWeek(day) - 1]}) ${time}` : null
}

/** 검토 미루기(FR-28): 미룬 날 0시(서울)가 아직 안 왔는지(앱 Snooze.isActive) */
export function snoozeActive(iso: string | null, now: Date = new Date()): boolean {
  const t = iso ? new Date(iso) : null
  return !!t && !Number.isNaN(t.getTime()) && t > now
}

/** 미룬 날 '11/5(목)'(앱 Snooze.label) */
export function snoozeLabel(iso: string | null): string | null {
  const day = iso ? seoulDate(iso) : null
  if (!day) return null
  const [, m, d] = day.split('-').map(Number)
  return `${m}/${d}(${WEEKDAY[dayOfWeek(day) - 1]})`
}

/** 처리 단계(목업 S05 '처리 단계'): 업로드 0 · 전사 1 · 분석 동의 2 · 분석 3 · 검토 가능 4 중 지금 단계 */
export function processStep(job: JobBrief | null): number {
  if (!job) return 0
  if (job.stage === 'STT') return 1
  if (job.stage === 'EXTRACT') return 3
  return job.stage === 'REVIEWABLE' ? 4 : 0
}

/** 시각 순서(최신 먼저)를 지킨 채 서울 날짜로 묶는다. 시각이 없는 기록은 맨 끝 '날짜 모름' */
export function groupByDay<T>(rows: T[], at: (r: T) => string | null): { day: LocalDate | null; rows: T[] }[] {
  const sorted = [...rows].sort((a, b) => (at(b) ?? '').localeCompare(at(a) ?? ''))
  const groups: { day: LocalDate | null; rows: T[] }[] = []
  for (const r of sorted) {
    const iso = at(r)
    const day = iso ? seoulDate(iso) : null
    const last = groups[groups.length - 1]
    if (last && last.day === day) last.rows.push(r)
    else groups.push({ day, rows: [r] })
  }
  return groups
}

/** 업무 · 개인 · 불확실(source_record.classification, 앱 RecordClass) */
export const CLASS_LABEL: Record<string, string> = { work: '업무', personal: '개인', uncertain: '불확실' }

const REASON_TEXT: Record<string, string> = {
  name_not_mentioned: '프로젝트 이름이 직접 나오지 않았어요',
  brief_mention: '짧게만 다뤘어요',
  project_ambiguous: '비슷한 프로젝트가 여럿이에요',
  transcript_low_quality: '받아쓰기가 불분명해요',
  conflicting_evidence: '다른 이야기와 섞여 있어요',
  no_evidence: '근거 문장을 찾지 못했어요',
}

/**
 * AI 연결 제안의 '확인 필요 · 이유' 한 줄(앱 suggestionNote, 확률 숫자는 보여 주지 않음 §3.10). high면 null.
 * 모르는 이유 코드는 건너뛴다(서버가 나중에 코드를 늘려도 화면이 깨지지 않게)
 */
export function suggestionNote(confidence: string, reasons: string[]): string | null {
  if (confidence === 'high') return null
  const texts = [...new Set(reasons.flatMap((r) => REASON_TEXT[r] ?? []))]
  if (texts.length > 0) return `확인 필요 · ${texts.join(' · ')}`
  if (confidence === 'low') return '확인 필요 · 관련 있어 보이지만 확실하지 않아요'
  if (confidence === 'unrated') return '확인 필요 · AI가 확신 정도를 매기지 않았어요'
  return null
}

/** 확정 연결 중 기록 전체 연결만(항목 단위 AI 연결은 따로, 앱 isRecordScope) */
export function recordProjectIds(links: { project_id: string; status: string; item_ref: { scope?: string } | null }[]): string[] {
  return [...new Set(links.filter((l) => l.status === 'confirmed' && l.item_ref?.scope === 'record').map((l) => l.project_id))]
}

// ── 상세 ──

/** 결정 후보 한 줄과 근거 구간(전사 문장 번호) */
export interface Decision {
  text: string
  segments: Set<number>
}

type Json = Record<string, unknown>
const objects = (v: unknown): Json[] => (Array.isArray(v) ? v.filter((x): x is Json => typeof x === 'object' && x !== null) : [])
const textOf = (o: Json, key: string): string | null => (typeof o[key] === 'string' && (o[key] as string).trim() ? (o[key] as string) : null)

/** 회의 정리의 글 목록(정하지 못한 것 등): [{text}] 중 빈 줄 뺌 */
export function textsOf(fields: Json | null, key: string): string[] {
  return objects(fields?.[key]).flatMap((o) => textOf(o, 'text') ?? [])
}

export function decisionsOf(fields: Json | null): Decision[] {
  return objects(fields?.decisions).flatMap((o) => {
    const text = textOf(o, 'text')
    if (!text) return []
    const segs = Array.isArray(o.segments) ? o.segments.filter((n): n is number => Number.isInteger(n)) : []
    return [{ text, segments: new Set(segs) }]
  })
}

/** 이미 있는 할 일이라 새로 만들지 않은 것(DEC-38) */
export function trackedTitlesOf(fields: Json | null): string[] {
  return objects(fields?.already_tracked).flatMap((o) => textOf(o, 'title') ?? [])
}

/** 후보 근거 구간: evidence_link[].segment_ref.idx */
export function proposalSegments(evidence: { segment_ref: { idx?: unknown } | null }[] | null | undefined): Set<number> {
  const out = new Set<number>()
  for (const e of evidence ?? []) {
    const idx = e.segment_ref?.idx
    if (Array.isArray(idx)) idx.forEach((n) => Number.isInteger(n) && out.add(n as number))
  }
  return out
}

/** 근거가 결정과 겹치는 첫 결정의 번호(DEC-48: 그 결정 밑에 접는다). 없으면 null */
export function overlappingDecision(segments: Set<number>, decisions: Decision[]): number | null {
  const i = decisions.findIndex((d) => [...segments].some((s) => d.segments.has(s)))
  return i >= 0 ? i : null
}

/** 전사 문장 구간. speaker·offsetMs는 가져온 txt의 화자·시각(DEC-54) */
export interface Segment {
  idx: number
  text: string
  speaker?: string | null
  offsetMs?: number | null
}

export interface SpeakerTurn {
  speaker: string | null
  offsetMs: number | null
  text: string
}

/** 같은 화자·시각이 이어지는 문장을 한 차례로 묶는다. 화자가 하나도 없으면 빈 목록(본문을 그대로 보여 줌) */
export function speakerTurns(segments: Segment[]): SpeakerTurn[] {
  if (!segments.some((s) => s.speaker)) return []
  const turns: SpeakerTurn[] = []
  for (const s of segments) {
    const speaker = s.speaker ?? null
    const offsetMs = s.offsetMs ?? null
    const last = turns[turns.length - 1]
    if (last && last.speaker === speaker && last.offsetMs === offsetMs) last.text += ` ${s.text}`
    else turns.push({ speaker, offsetMs, text: s.text })
  }
  return turns
}

const two = (n: number) => String(n).padStart(2, '0')

/** 00:03 · 1:02:03 */
export function clockText(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${two(m)}:${two(s)}`
}

/** 회의록 표기 규칙: [확인 필요]는 빨간 굵게, [미정]은 회색 굵게. 글을 조각으로 나눠 표시는 화면이 한다 */
export function markedParts(text: string): { text: string; mark: 'crit' | 'muted' | null }[] {
  return text
    .split(/(\[확인 필요\]|\[미정\])/)
    .filter((s) => s !== '')
    .map((s) => ({ text: s, mark: s === '[확인 필요]' ? 'crit' : s === '[미정]' ? 'muted' : null }))
}

/** 후보 담당 표시(앱 ownerLabel) */
export function ownerLabel(kind: unknown, assignee: unknown): string {
  if (kind === 'me') return '내 할 일'
  if (kind === 'other') return `팔로업 · ${typeof assignee === 'string' ? assignee : ''}`
  return '담당 확인'
}

/** 후보 기한 표시(앱 dueLabel): 날짜+시각 / 날짜만 'M/D(요일)' / 모호 '…· 확인 필요' / 없음 null */
export function candidateDueLabel(after: Json): string | null {
  const time = typeof after.due_time === 'string' ? after.due_time : null
  const date = typeof after.due_date === 'string' ? after.due_date : null
  const day = (d: LocalDate) => {
    const [, m, dd] = d.split('-').map(Number)
    return `${m}/${dd}(${WEEKDAY[dayOfWeek(d) - 1]})`
  }
  if (time) {
    const d = seoulDate(time)
    const t = seoulTime(time)
    return d && t ? `${day(d)} ${t}` : time
  }
  if (date) return day(date)
  if (after.due_ambiguous === true) return `${typeof after.due_text === 'string' ? after.due_text : '모호'} · 확인 필요`
  return null
}

/**
 * 기존 할 일 변경 제안의 바뀐 칸(DEC-38: 전후를 나란히). 제목·담당·기한 중 달라진 것만,
 * 바뀌기 전 값이 없으면(옛 제안) 바뀐 뒤 값만 보여 주도록 from = null
 */
export function changeLines(before: Json | null, after: Json): { label: string; from: string | null; to: string }[] {
  const owner = (o: Json) => ownerLabel(o.owner_kind, o.assignee)
  const due = (o: Json) => candidateDueLabel(o) ?? '기한 없음'
  const title = (o: Json) => (typeof o.title === 'string' ? o.title : '')
  const lines: { label: string; from: string | null; to: string }[] = []
  for (const [label, read, key] of [
    ['제목', title, 'title'],
    ['담당', owner, 'owner_kind'],
    ['기한', due, 'due_date'],
  ] as const) {
    const to = read(after)
    const from = before ? read(before) : null
    if (from === to) continue
    // 옛 제안처럼 전 값이 없으면 바뀐 칸을 알 수 없어, 제안에 들어 있는 칸만 보여 준다
    if (!before && !(key in after) && !(key === 'due_date' && ('due_time' in after || 'due_ambiguous' in after))) continue
    lines.push({ label, from, to })
  }
  return lines
}

/** 후보의 받는 사람(DEC-77). 배열이 아니면 빈 목록 */
export function recipientsOf(after: Json): string[] {
  return Array.isArray(after.recipients) ? after.recipients.flatMap((x) => (typeof x === 'string' && x.trim() ? [x.trim()] : [])) : []
}
