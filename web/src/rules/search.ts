/*
 * 검색(FR-13, DEC-98) 화면 밖 규칙(앱 search/SearchQuery.kt와 같음): 검색어 다듬기·강조 구간·필터 값·결과 나누기·누르면 갈 곳.
 * 서버 규칙(0039 wb_search)과 같게 맞춘다
 */

/**
 * 검색 결과 한 줄(서버 wb_search). kind: task · record · resource · proposal(검토 대기 할 일 후보).
 * snippet은 제목이 아닌 곳에서 맞았을 때 그 앞뒤 조각, at은 기록·후보 = 회의 시각, 할 일·자료 = 만든 시각
 */
export interface SearchRow {
  kind: string
  id: string
  title: string | null
  snippet: string | null
  record_id: string | null
  /** 그 기록 제목(후보 줄의 출처). 제목 없는 기록이면 null */
  record_title: string | null
  project_id: string | null
  area_id: string | null
  place_name: string | null
  at: string | null
  /** 검토 전 초안: 승인 전 회의록 글에서만 맞은 기록 · 검토 대기 할 일 후보 */
  draft: boolean
  done: boolean
  /** 보관한 곳의 할 일 · 보관한 자료 */
  archived: boolean
  audio_expired: boolean
}

export const KIND_TASK = 'task'
export const KIND_RECORD = 'record'
export const KIND_RESOURCE = 'resource'
export const KIND_PROPOSAL = 'proposal'

/** 서버와 같은 규칙: 두 글자 미만이면 찾지 않고, 100자까지 */
export const SEARCH_MIN = 2
export const SEARCH_MAX = 100

/** 앞뒤 공백·줄바꿈을 빼고 100자까지(잘린 끝이 이모지 반쪽이면 그 반쪽도 뺀다 — 서버로 깨진 글자를 보내지 않음) */
export function cleanQuery(raw: string): string {
  const t = raw.trim().slice(0, SEARCH_MAX)
  const last = t.charCodeAt(t.length - 1)
  return t.length > 0 && last >= 0xd800 && last <= 0xdbff ? t.slice(0, -1) : t
}

export const canSearch = (raw: string) => cleanQuery(raw).length >= SEARCH_MIN

/** 글에서 검색어가 나온 곳 [시작, 끝)(대소문자 무시, 겹치지 않게 앞에서부터). 강조 표시에 쓴다 */
export function matchRanges(text: string, raw: string): [number, number][] {
  const needle = cleanQuery(raw).toLowerCase()
  if (needle.length < SEARCH_MIN || text === '') return []
  const hay = text.toLowerCase()
  const out: [number, number][] = []
  let from = 0
  for (;;) {
    const i = hay.indexOf(needle, from)
    if (i < 0) break
    out.push([i, i + needle.length])
    from = i + needle.length
  }
  return out
}

/** 기간 칩(처음 값 전체, DEC-98) */
export type SearchPeriod = 'all' | '30' | '7'
export const PERIODS: { value: SearchPeriod; label: string }[] = [
  { value: 'all', label: '전체' },
  { value: '30', label: '최근 30일' },
  { value: '7', label: '최근 7일' },
]

/** 그 기간의 시작 시각(지금부터 30일·7일 전). 전체면 null */
export function periodSince(p: SearchPeriod, now: Date): Date | null {
  return p === 'all' ? null : new Date(now.getTime() - Number(p) * 24 * 60 * 60 * 1000)
}

/** 종류 칩. 할 일 후보(검토 대기)는 '할 일'에 함께 넣는다. 서버 종류가 null이면 모든 종류 */
export type SearchKind = 'all' | 'task' | 'record' | 'resource'
export const KINDS: { value: SearchKind; label: string }[] = [
  { value: 'all', label: '전체' },
  { value: 'task', label: '할 일' },
  { value: 'record', label: '회의록' },
  { value: 'resource', label: '자료' },
]

export function serverKinds(k: SearchKind): string[] | null {
  if (k === 'task') return [KIND_TASK, KIND_PROPOSAL]
  if (k === 'record') return [KIND_RECORD]
  if (k === 'resource') return [KIND_RESOURCE]
  return null
}

const timeOf = (at: string | null) => (at ? new Date(at).getTime() : Number.NEGATIVE_INFINITY)

/**
 * 위에 보일 결과(최근 것부터, 종류를 섞어)와 아래에 접어 둘 검토 대기 할 일 후보.
 * 승인 전 회의록은 위 결과에 '초안'으로 함께 둔다(2026-11-11 사용자 결정). 기록 휴지통은 휴대폰에만 있어서(DEC-51)
 * 그 기기에서 넣은 기록과 후보를 빼는 자리 — 웹은 휴지통이 없어 빈 집합을 넘긴다
 */
export function splitResults(rows: SearchRow[], trashedRecordIds: ReadonlySet<string>): { shown: SearchRow[]; candidates: SearchRow[] } {
  const kept = rows
    .filter((r) => !((r.kind === KIND_RECORD || r.kind === KIND_PROPOSAL) && r.record_id !== null && trashedRecordIds.has(r.record_id)))
    .sort((a, b) => timeOf(b.at) - timeOf(a.at))
  return { shown: kept.filter((r) => r.kind !== KIND_PROPOSAL), candidates: kept.filter((r) => r.kind === KIND_PROPOSAL) }
}

/** 결과가 없을 때 넓혀 볼 버튼 */
export type Relax = 'period' | 'place' | 'candidates'
export const RELAX_LABEL: Record<Relax, string> = { period: '기간을 전체로', place: '모든 프로젝트에서', candidates: '할 일 후보도 보기' }

/** 위 결과가 없을 때 보여 줄 넓히기 버튼: 걸린 필터만, 접어 둔 할 일 후보가 있으면 후보 보기 */
export function relaxOptions(period: SearchPeriod, placeFiltered: boolean, candidatesOpen: boolean, candidateCount: number): Relax[] {
  const out: Relax[] = []
  if (period !== 'all') out.push('period')
  if (placeFiltered) out.push('place')
  if (!candidatesOpen && candidateCount > 0) out.push('candidates')
  return out
}

const SEOUL_MD = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' })

/** 카드 메타 줄의 날짜(서울): 회의록·후보는 회의 날 'M/D', 할 일·자료는 'M/D 만듦'. 시각이 없으면 null */
export function metaDate(row: SearchRow): string | null {
  if (!row.at) return null
  const parts = SEOUL_MD.formatToParts(new Date(row.at))
  const day = `${parts.find((p) => p.type === 'month')?.value}/${parts.find((p) => p.type === 'day')?.value}`
  return row.kind === KIND_TASK || row.kind === KIND_RESOURCE ? `${day} 만듦` : day
}

/** 결과 카드 종류 칩 이름 */
export function kindLabel(kind: string): string {
  if (kind === KIND_TASK) return '할 일'
  if (kind === KIND_PROPOSAL) return '할 일 후보'
  if (kind === KIND_RECORD) return '회의록'
  if (kind === KIND_RESOURCE) return '자료'
  return '기타'
}

/**
 * 누르면 갈 주소: 할 일 → 속한 프로젝트·반복실행 상세(정리 안 됨이면 모든 할 일)에서 그 줄을 칠한다(?task=),
 * 자료 → 그 자료, 회의록·후보 → 기록 상세. 기록 ID가 없는 줄은 갈 곳이 없다(null)
 */
export function targetOf(row: SearchRow): string | null {
  if (row.kind === KIND_TASK) {
    const key = row.project_id ? `/p:${row.project_id}` : row.area_id ? `/a:${row.area_id}` : ''
    return `/projects${key}?task=${row.id}`
  }
  if (row.kind === KIND_RESOURCE) return `/projects/r:${row.id}`
  if (row.kind === KIND_RECORD || row.kind === KIND_PROPOSAL) return row.record_id ? `/records/${row.record_id}` : null
  return null
}
