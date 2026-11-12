import { dateOf, dayOfWeek, plusDays, timeOf, yearOf, type LocalDate, type LocalTime } from './dates'
import { samePlace, type Place } from './place'

/**
 * 한 줄 빠른 입력 해석(앱 tasks/QuickParse.kt를 그대로 옮김 — 규칙을 고치면 두 곳을 같이 고친다, DEC-102).
 * 기한은 셋 중 하나: 날짜(+시각) / 모호한 원문(vague, 날짜로 바꾸지 않음) / 없음.
 * place가 null이면 '정리 안 됨'. assignee가 있으면 팔로업(owner_kind = other).
 * '#'이 여러 곳에 걸리면 placeChoices(최대 5곳)를 칩으로 보여 고르게 하고, 고르면 입력의 placeAsked를 그 이름으로 바꾼다(DEC-80)
 */
export interface QuickTask {
  title: string
  dueDate: LocalDate | null
  dueTime: LocalTime | null
  vague: string | null
  priority: number
  place: Place | null
  assignee: string | null
  warnings: string[]
  placeChoices: Place[]
  placeAsked: string | null
}

export const MAX_TITLE = 200

// 낱말 끝에 붙어도 기한으로 보는 조사: '금요일까지', '3시에'. 낱말은 앞뒤가 띄어쓰기여야 알아본다('3시간'·'C#'·메일의 '@')
const TAIL = '(?:까지|에)?(?=\\s)'
const HEAD = '(?<=\\s)'

const PRIORITY = new RegExp(`${HEAD}!(높음|보통|낮음|[123])(?=\\s)`, 'u')
const PLACE = new RegExp(`${HEAD}#(\\S+)`, 'u')
const PERSON = new RegExp(`${HEAD}@(\\S+)`, 'u')
const RELATIVE = new RegExp(`${HEAD}(오늘|내일|모레)${TAIL}`, 'u')
const MONTH_DAY = new RegExp(`${HEAD}(\\d{1,2})/(\\d{1,2})${TAIL}`, 'u')
const WEEKDAY = new RegExp(`${HEAD}(?:(이번|다음)\\s?주\\s?)?([월화수목금토일])요일${TAIL}`, 'u')
// 시각 앞에 붙는 낱말: 오전·오후 + 시간대 낱말(DEC-97)
const DAYPART = '오전|오후|아침|새벽|점심|저녁|밤'
const TIME = new RegExp(`${HEAD}(?:(${DAYPART})\\s?)?(\\d{1,2})(?::(\\d{2})|시(?:\\s?(\\d{1,2})분|\\s?(반))?)${TAIL}`, 'u')
const VAGUE = new RegExp(`${HEAD}(다음\\s?주|이번\\s?주|다음\\s?달|이번\\s?달|월말)${TAIL}`, 'u')

// 숫자 없이 날짜 바로 뒤에 쓰면 정해 둔 시각으로 읽는 낱말(DEC-97). 저녁·밤·새벽·오전은 숫자가 있어야 읽는다
const SLOT_WORDS = '아침|점심|오후|퇴근\\s?전'
const SLOT = new RegExp(`${HEAD}(${SLOT_WORDS})${TAIL}`, 'u')
const SLOT_TIME: Record<string, LocalTime> = { 아침: '09:00', 점심: '13:00', 오후: '16:00', 퇴근전: '18:00' }
// '밤 12시'는 그날 밤 자정 = 그날 마감 끝으로 본다(다음 날 0시로 넘기지 않음)
const END_OF_DAY: LocalTime = '23:59'

/**
 * 날짜 바로 뒤에 붙여 쓴 시각·시간대 낱말을 떼어 놓는다: '내일3시 미팅' → '내일 3시 미팅'(DEC-70),
 * '내일아침9시' → '내일 아침9시'(DEC-97). '내일3시미팅'·'회의금요일3시'·'내일3시간'은 그대로 둔다
 */
const GLUED = new RegExp(
  `${HEAD}(오늘|내일|모레|\\d{1,2}/\\d{1,2}|다음\\s?주|이번\\s?주|다음\\s?달|이번\\s?달|월말|(?:(?:이번|다음)\\s?주\\s?)?[월화수목금토일]요일)` +
    `(?=(?:(?:${DAYPART})\\s?)?\\d{1,2}(?::\\d{2}|시(?:\\d{1,2}분|반)?)(?:까지|에)?\\s|(?:${SLOT_WORDS})(?:까지|에)?\\s)`,
  'gu',
)

const DAYS = '월화수목금토일'
const MAX_CHOICES = 5
const MAX_EXTRA_WORDS = 3
const NEXT_WORD = /\s+(\S+)/uy
const NOT_WORD_CHAR = /[^\p{L}\p{N}]+/u
const LETTER_OR_DIGIT = /[\p{L}\p{Nd}]/u
const DIGIT = /\p{Nd}/u

/** Kotlin Regex.find(s, from)과 같음: from부터 처음 맞는 곳(앞보기·뒤보기는 문자열 전체를 본다) */
function findFrom(re: RegExp, s: string, from = 0): RegExpExecArray | null {
  const g = new RegExp(re.source, re.flags.includes('g') ? re.flags : `${re.flags}g`)
  g.lastIndex = from
  return g.exec(s)
}

const group = (m: RegExpExecArray, i: number): string => m[i] ?? ''
const endOf = (m: RegExpExecArray): number => m.index + m[0].length
const replaceRange = (s: string, start: number, end: number, by: string) => s.slice(0, start) + by + s.slice(end)

/**
 * raw: 입력 한 줄, today: 서울 오늘, places: 보관 안 된 프로젝트·반복실행,
 * defaultPlace: '#'이 없을 때 넣을 곳(상세 화면이면 그곳, 오늘·모든 할 일은 마지막에 넣은 곳),
 * noDueMeansToday: 오늘 화면 — 기한을 안 적으면 오늘 마감(목업 S02, 안 그러면 넣자마자 화면에서 사라진다)
 */
export function parseQuick(raw: string, today: LocalDate, places: Place[], defaultPlace: Place | null, noDueMeansToday = false): QuickTask {
  let s = ` ${raw.replace(/\s/g, ' ')} `.replace(GLUED, '$1 ')
  const warnings: string[] = []

  /** 처음 맞는 곳 하나만 본다. accept가 false면 지우지 않고 제목에 남긴다 */
  const takeFirst = (re: RegExp, accept: (m: RegExpExecArray) => boolean) => {
    const m = findFrom(re, s)
    if (m && accept(m)) s = replaceRange(s, m.index, endOf(m), ' ')
  }

  let priority = 2
  takeFirst(PRIORITY, (m) => {
    const v = group(m, 1)
    priority = v === '높음' || v === '1' ? 1 : v === '낮음' || v === '3' ? 3 : 2
    return true
  })

  // '#'은 여러 번 쓸 수 있어 하나씩 본다. 처음 찾은 곳만 쓰고, 못 찾거나 여럿 걸리면 제목에 남기고 경고(여럿이면 고를 칩)
  let place: Place | null = null
  let placeChoices: Place[] = []
  let placeAsked: string | null = null
  let searchFrom = 0
  for (;;) {
    const m = findFrom(PLACE, s, searchFrom)
    if (!m) break
    const word = group(m, 1)
    if (place) {
      warnings.push(`#${word} · 넣을 곳은 하나만 골라요`)
      searchFrom = endOf(m)
      continue
    }
    const next = followingWords(s, endOf(m))
    const found = matchPlace(word, next.map((w) => w.word), places)
    if (found.place) {
      place = found.place
      // 좁히는 데 쓴 다음 낱말까지 제목에서 뺀다
      const end = found.extra > 0 ? next[found.extra - 1]!.end : endOf(m)
      s = replaceRange(s, m.index, end, ' ')
      searchFrom = m.index
      placeChoices = []
      placeAsked = null
      continue
    }
    const asked = '#' + [word, ...next.slice(0, found.extra).map((w) => w.word)].join(' ')
    if (found.hits.length === 0) {
      warnings.push(`${asked} 프로젝트를 못 찾았어요`)
    } else {
      const more = found.hits.length > MAX_CHOICES ? '고르거나 더 적어 주세요' : '골라 주세요'
      warnings.push(`${asked}: 맞는 곳이 ${found.hits.length}곳이에요 · 아래에서 ${more}`)
      if (placeChoices.length === 0) {
        placeChoices = found.hits.slice(0, MAX_CHOICES)
        placeAsked = asked
      }
    }
    searchFrom = endOf(m)
  }

  let assignee: string | null = null
  takeFirst(PERSON, (m) => {
    const who = Array.from(group(m, 1)).slice(0, 40).join('')
    assignee = who
    // '@'는 담당자, '#'은 넣을 곳. 프로젝트 이름과 맞으면 헷갈렸을 수 있어 알려만 준다(저장은 막지 않음, DEC-97)
    if (Array.from(who).length >= 2 && placesFor(who, places).length > 0) warnings.push(`@${who}${topic(who)} 담당자로 들어가요 · 프로젝트면 #${who}`)
    return true
  })

  let date: LocalDate | null = null
  // 날짜 낱말이 있던 자리: 바로 뒤에 숫자 없이 쓴 '아침'·'퇴근전'을 찾는 데 쓴다
  let dateAt = -1
  takeFirst(RELATIVE, (m) => {
    const w = group(m, 1)
    date = plusDays(today, w === '내일' ? 1 : w === '모레' ? 2 : 0)
    dateAt = m.index
    return true
  })
  if (date === null) {
    takeFirst(MONTH_DAY, (m) => {
      const mo = Number(group(m, 1))
      const d = Number(group(m, 2))
      const found = monthDay(mo, d, today)
      if (found === null) {
        warnings.push(`${mo}/${d}는 없는 날짜예요`)
        return false
      }
      date = found
      dateAt = m.index
      return true
    })
  }
  if (date === null) {
    takeFirst(WEEKDAY, (m) => {
      date = weekday(group(m, 1), DAYS.indexOf(group(m, 2)) + 1, today)
      dateAt = m.index
      return true
    })
  }

  let time: LocalTime | null = null
  let timeText: string | null = null
  let timeRejected = false
  takeFirst(TIME, (m) => {
    const parsed = timeFrom(m)
    if (parsed === null) {
      warnings.push(timeWarning(m))
      timeRejected = true
      return false
    }
    time = parsed
    timeText = m[0].trim()
    return true
  })
  // 시각을 안 적었을 때만: 날짜 바로 뒤(사이엔 띄어쓰기만)의 시간대 낱말. 적은 시각이 틀렸으면 덮지 않는다(경고만)
  if (date !== null && time === null && !timeRejected) {
    const m = findFrom(SLOT, s, dateAt)
    if (m && s.slice(dateAt, m.index).trim() === '') {
      time = SLOT_TIME[group(m, 1).replace(/ /g, '')] ?? null
      s = replaceRange(s, m.index, endOf(m), ' ')
    }
  }

  let vague: string | null = null
  if (date === null) {
    takeFirst(VAGUE, (m) => {
      vague = group(m, 1)
      return true
    })
  }
  if (vague !== null && timeText !== null) {
    // '다음 주 3시'는 날짜를 모르니 시각까지 원문으로 둔다(날짜를 지어내지 않음, NFR-02)
    vague = `${vague} ${timeText}`
    time = null
  } else if (date === null && time !== null) {
    date = today
  } else if (date === null && vague === null && noDueMeansToday) {
    date = today
  }

  let title = s.replace(/\s+/g, ' ').trim()
  if (title.length > MAX_TITLE) {
    title = title.slice(0, MAX_TITLE)
    warnings.push(`제목은 ${MAX_TITLE}자까지만 저장해요`)
  }
  return {
    title,
    dueDate: date,
    dueTime: date !== null ? time : null,
    vague,
    priority,
    place: place ?? defaultPlace,
    assignee,
    warnings,
    placeChoices,
    placeAsked,
  }
}

/**
 * 후보 칩을 눌렀을 때: 입력에서 asked('#CRM 12'처럼 좁히는 데 쓴 낱말까지)를 '#이름'(띄어쓰기 뺀 정확한 이름)으로 바꾼다.
 * 그 사이에 다른 단축어가 끼어 통째로 못 찾으면 '#' 낱말 하나만 바꾼다
 */
export function choosePlace(raw: string, asked: string, place: Place): string {
  const words = asked.replace(/^#/, '').split(' ')
  const replacement = '#' + place.name.replace(/\s/g, '')
  for (const ws of [words, words.slice(0, 1)]) {
    const re = new RegExp(`(?<=^|\\s)#${ws.map(escapeRegex).join('\\s+')}(?=\\s|$)`, 'u')
    const m = re.exec(raw)
    if (m) return replaceRange(raw, m.index, endOf(m), replacement)
  }
  return raw
}

function escapeRegex(x: string): string {
  return x.replace(/[.*+?^${}()|[\]\\/-]/g, '\\$&')
}

/** '#낱말' 뒤에 띄어 쓴 낱말들(최대 3개)과 각 끝 위치. 다른 단축어(#·@·!)가 나오면 멈춘다 */
function followingWords(s: string, from: number): { word: string; end: number }[] {
  const out: { word: string; end: number }[] = []
  let at = from
  while (out.length < MAX_EXTRA_WORDS) {
    NEXT_WORD.lastIndex = at
    const m = NEXT_WORD.exec(s)
    if (!m) break
    const word = group(m, 1)
    if ('#@!'.includes(word[0] ?? '')) break
    out.push({ word, end: endOf(m) })
    at = endOf(m)
  }
  return out
}

/**
 * '#낱말'로 찾고, 여럿이면 띄어 쓴 다음 낱말을 하나씩 붙여 좁힌다(DEC-80). 붙여서 하나도 안 맞으면 그 낱말은 본문이라 멈춘다.
 * 하나로 정해진 뒤에도 다음 낱말이 그 이름에 이어 들어 있으면 이름의 일부로 보고 함께 뺀다(DEC-82). 다른 곳으로 바뀌면 멈춘다.
 * 하나로 좁혀질 때만 고른다(엉뚱한 곳에 몰래 넣지 않게)
 */
function matchPlace(word: string, next: string[], places: Place[]): { place: Place | null; extra: number; hits: Place[] } {
  let hits = placesFor(word, places)
  let extra = 0
  while (hits.length > 0 && extra < next.length) {
    const narrowed = placesFor([word, ...next.slice(0, extra + 1)].join(' '), places)
    if (narrowed.length === 0 || (hits.length === 1 && !samePlaces(narrowed, hits))) break
    hits = narrowed
    extra++
  }
  return { place: hits.length === 1 ? hits[0]! : null, extra, hits }
}

function samePlaces(a: Place[], b: Place[]): boolean {
  return a.length === b.length && a.every((p, i) => samePlace(p, b[i]))
}

/**
 * 맞는 곳을 세 단계로 찾고, 앞 단계에서 하나라도 맞으면 거기서 멈춘다:
 * ① 이름이 똑같음 ② 이름에 그대로 들어 있음(띄어쓰기·대소문자 무시)
 * ③ 조각 맞추기 — 영문·숫자·한글 덩어리가 이름에 순서대로 다 있음(문장부호 무시): 'CRM 12건' → 'CRM) 이관 12건'
 */
function placesFor(query: string, places: Place[]): Place[] {
  const k = key(query)
  const same = places.filter((p) => key(p.name) === k)
  if (same.length > 0) return same
  const inside = places.filter((p) => key(p.name).includes(k))
  if (inside.length > 0) return inside
  const ps = pieces(query)
  if (ps.length === 0) return []
  return places.filter((p) => inOrder(loose(p.name), ps))
}

const key = (x: string) => x.replace(/\s/g, '').toLowerCase()

/** 글자·숫자만(문장부호·띄어쓰기 뺌) */
const loose = (x: string) => Array.from(x.toLowerCase()).filter((c) => LETTER_OR_DIGIT.test(c)).join('')

/** 띄어쓰기·문장부호에서 자르고, 영문·숫자·그 밖의 글자(한글 등)가 바뀌는 곳에서 한 번 더 자른 덩어리 */
function pieces(query: string): string[] {
  const kind = (c: string) => (c >= 'a' && c <= 'z' ? 0 : DIGIT.test(c) ? 1 : 2)
  const out: string[] = []
  for (const word of query.split(NOT_WORD_CHAR)) {
    let last = -1
    let run = ''
    for (const c of Array.from(loose(word))) {
      const k = kind(c)
      if (k !== last && run) {
        out.push(run)
        run = ''
      }
      run += c
      last = k
    }
    if (run) out.push(run)
  }
  return out
}

function inOrder(name: string, ps: string[]): boolean {
  let from = 0
  for (const p of ps) {
    const at = name.indexOf(p, from)
    if (at < 0) return false
    from = at + p.length
  }
  return true
}

/** 올해 그 날. 이미 지났으면 다음 해(DEC-70). 없는 날짜면 null */
function monthDay(month: number, day: number, today: LocalDate): LocalDate | null {
  const year = yearOf(today)
  const thisYear = dateOf(year, month, day)
  if (thisYear !== null && thisYear >= today) return thisYear
  // 2/29처럼 올해엔 없지만 내년엔 있을 수 있는 날도 있다
  return month >= 1 && month <= 12 ? dateOf(year + 1, month, day) : null
}

/** 한 주는 월요일 시작(DEC-31). 그냥 'X요일'은 오늘을 포함해 가장 가까운 X요일 */
function weekday(which: string, dow: number, today: LocalDate): LocalDate {
  const monday = plusDays(today, -(dayOfWeek(today) - 1))
  if (which === '이번') return plusDays(monday, dow - 1)
  if (which === '다음') return plusDays(monday, 7 + dow - 1)
  return plusDays(today, (dow - dayOfWeek(today) + 7) % 7)
}

/**
 * 오전·오후가 없는 1~7시는 업무 시간대로 보고 오후로 한다(목업). '15:00'처럼 쌍점으로 쓴 것은 그대로.
 * 아침·새벽은 오전, 저녁·밤은 오후, 점심은 앞 낱말이 없는 것과 같다(DEC-97).
 * 저녁·밤 12시는 그날 23:59, 밤 1~5시는 날짜가 넘어가 헷갈리므로 읽지 않는다(새벽으로 적게 안내)
 */
function timeFrom(m: RegExpExecArray): LocalTime | null {
  const part = group(m, 1)
  let hour = Number(group(m, 2))
  const colonMin = group(m, 3)
  const min = group(m, 4)
  const half = group(m, 5)
  const minute = colonMin ? Number(colonMin) : min ? Number(min) : half ? 30 : 0
  if (hour > 23 || minute > 59) return null
  const morning = part === '오전' || part === '아침' || part === '새벽'
  const evening = part === '저녁' || part === '밤'
  if (part === '밤' && hour >= 1 && hour <= 5) return null
  if (evening && hour === 12) return END_OF_DAY
  if ((part === '오후' || evening) && hour < 12) hour += 12
  else if (morning && hour === 12) hour = 0
  else if ((part === '' || part === '점심') && !colonMin && hour >= 1 && hour <= 7) hour += 12
  return timeOf(hour, minute)
}

function timeWarning(m: RegExpExecArray): string {
  const text = m[0].trim()
  const hour = Number(group(m, 2))
  return group(m, 1) === '밤' && hour >= 1 && hour <= 5 ? `${text}: '새벽 ${hour}시'로 적어 주세요` : `${text}: 없는 시각이에요`
}

/** 이름 뒤 조사 '은/는'(받침 있으면 은). 한글이 아니면 '은(는)' */
function topic(word: string): string {
  const code = word.codePointAt(word.length - 1) ?? 0
  if (code < 0xac00 || code > 0xd7a3) return '은(는)'
  return (code - 0xac00) % 28 !== 0 ? '은' : '는'
}
