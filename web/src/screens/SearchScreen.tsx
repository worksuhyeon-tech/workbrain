import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { PageHead } from '../components/AppShell'
import { Icons } from '../components/icons'
import { Seg } from '../components/Seg'
import { loadPlaces, searchRows } from '../data/server'
import { cn } from '../lib/cn'
import type { Place } from '../rules/place'
import {
  cleanQuery,
  KIND_PROPOSAL,
  KIND_RECORD,
  KIND_RESOURCE,
  KIND_TASK,
  KINDS,
  kindLabel,
  matchRanges,
  metaDate,
  PERIODS,
  periodSince,
  RELAX_LABEL,
  relaxOptions,
  SEARCH_MAX,
  SEARCH_MIN,
  serverKinds,
  splitResults,
  targetOf,
  type Relax,
  type SearchKind,
  type SearchPeriod,
  type SearchRow,
} from '../rules/search'
import { UNSORTED } from '../rules/tasks'
import { CARD } from '../components/ui'

const MUTE_CHIP = 'rounded-full border border-border bg-surface px-2 py-px text-[11px] font-bold whitespace-nowrap text-muted'
// 적는 동안 요청을 줄이려고 멈춘 뒤에만 찾는다(DEC-98: 입력하면 바로)
const TYPE_PAUSE_MS = 400
// 웹에는 기록 휴지통이 없다(휴대폰 기기에만, DEC-51)
const NO_TRASH: ReadonlySet<string> = new Set()

/** 결과 카드 종류 칩 색(앱 kindTone, 11/12 사용자): 할 일·후보 파랑, 회의록 보라, 자료 베이지(경고 톤) */
function kindChip(kind: string): string {
  if (kind === KIND_RECORD) return 'bg-purple-soft text-purple'
  if (kind === KIND_RESOURCE) return 'bg-warn-soft text-warn'
  return 'bg-info-soft text-info'
}

/** 주소의 in(p:ID·a:ID) → 찾을 곳. 목록에 없으면(보관됨·불러오는 중) null */
const placeOfKey = (places: Place[] | null, key: string | null) => places?.find((p) => `${p.type === 'PROJECT' ? 'p' : 'a'}:${p.id}` === key) ?? null

/** 맞은 글자에 형광펜(분홍 50%, 앱과 같은 색) */
function Highlighted({ text, q }: { text: string; q: string }) {
  const out: ReactNode[] = []
  let at = 0
  for (const [s, e] of matchRanges(text, q)) {
    out.push(text.slice(at, s))
    out.push(
      <mark key={s} className="rounded-[2px] bg-mark px-px text-inherit">
        {text.slice(s, e)}
      </mark>,
    )
    at = e
  }
  out.push(text.slice(at))
  return <>{out}</>
}

/**
 * 검색(FR-13, DEC-98, 목업 S08 · 앱 SearchScreen): 두 글자 이상 적고 0.4초 멈추면 저절로 찾는다.
 * 결과는 종류를 섞어 최근 것부터, 승인 전 회의록은 '초안'. 검토 대기 할 일 후보만 아래에 접어 둔다.
 * 주소 /search?q=…&in=p:ID — 위 막대 검색 칸·프로젝트 상세에서 열면 그곳으로 미리 거른다
 */
export function SearchScreen() {
  const [params, setParams] = useSearchParams()
  const urlQ = params.get('q') ?? ''
  const placeKey = params.get('in')
  const [text, setText] = useState(urlQ)
  const [period, setPeriod] = useState<SearchPeriod>('all')
  const [kind, setKind] = useState<SearchKind>('all')
  const [draftsOpen, setDraftsOpen] = useState(false)
  // 찾을 곳 목록. null = 불러오는 중(못 불러오면 빈 목록 — '모든 프로젝트'만)
  const [places, setPlaces] = useState<Place[] | null>(null)
  const [rows, setRows] = useState<SearchRow[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  // [검색]·Enter·[다시 시도]는 기다리지 않고 바로 찾는다
  const [runNow, setRunNow] = useState(0)
  const handledRun = useRef(0)
  const seq = useRef(0)
  const q = cleanQuery(text)
  const place = placeOfKey(places, placeKey)

  // 위 막대 검색 칸에서 다시 찾으면 주소가 바뀐다 → 입력 칸도 따라간다
  useEffect(() => setText(urlQ), [urlQ])

  useEffect(() => {
    loadPlaces()
      .then(setPlaces)
      .catch(() => setPlaces([]))
  }, [])

  const setParam = (key: string, value: string | null) =>
    setParams(
      (p) => {
        const next = new URLSearchParams(p)
        if (value) next.set(key, value)
        else next.delete(key)
        return next
      },
      { replace: true },
    )

  // 검색어·필터가 바뀌면 0.4초 기다렸다 찾는다. 그사이 또 바뀌면 앞 요청 결과는 버린다
  useEffect(() => {
    setFailed(false)
    if (q.length < SEARCH_MIN) {
      setRows(null)
      setLoading(false)
      return
    }
    // 고른 곳이 아직 목록에 없으면(불러오는 중) 기다린다 — 모든 곳에서 찾은 결과를 잠깐 보여 주지 않게
    if (placeKey && places === null) return
    const pressed = runNow !== handledRun.current
    handledRun.current = runNow
    const my = ++seq.current
    const timer = window.setTimeout(
      async () => {
        setLoading(true)
        try {
          const found = await searchRows(q, placeOfKey(places, placeKey), periodSince(period, new Date()), serverKinds(kind))
          if (my === seq.current) setRows(found)
        } catch (e) {
          console.warn('search failed', e)
          if (my === seq.current) setFailed(true)
        } finally {
          if (my === seq.current) setLoading(false)
        }
      },
      pressed ? 0 : TYPE_PAUSE_MS,
    )
    return () => window.clearTimeout(timer)
  }, [q, period, kind, placeKey, places, runNow])

  const results = rows ? splitResults(rows, NO_TRASH) : null
  const relax = results ? relaxOptions(period, place !== null, draftsOpen, results.candidates.length) : []
  const applyRelax = (r: Relax) => {
    if (r === 'period') setPeriod('all')
    else if (r === 'place') setParam('in', null)
    else setDraftsOpen(true)
  }

  return (
    <>
      <PageHead title="검색" hand="그때 뭐라고 했더라" />
      <form
        role="search"
        className="mb-3 flex gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          setRunNow((n) => n + 1)
        }}
      >
        <div className="flex h-[46px] min-w-0 flex-1 items-center gap-2.5 rounded-lg border border-border-strong bg-card pl-3.5 focus-within:outline-2 focus-within:outline-offset-1 focus-within:outline-primary">
          <span className="text-muted"><Icons.search /></span>
          <input
            value={text}
            autoFocus
            maxLength={SEARCH_MAX + 20}
            onChange={(e) => {
              setText(e.target.value)
              setParam('q', e.target.value.trim() ? e.target.value : null)
            }}
            placeholder="키워드로 찾기 (예: 개발 공수)"
            aria-label="검색어"
            className="h-full min-w-0 flex-1 border-0 bg-transparent text-base text-ink outline-none"
          />
          {text && (
            <button
              type="button"
              aria-label="검색어 지우기"
              onClick={() => {
                setText('')
                setParam('q', null)
              }}
              className="grid size-10 place-items-center text-muted hover:text-ink"
            >
              ✕
            </button>
          )}
        </div>
        <button type="submit" className="h-[46px] rounded-full bg-primary px-6 text-[15px] font-semibold text-on-primary hover:bg-primary-hover">
          검색
        </button>
      </form>

      <div className="mb-5 flex flex-wrap items-center gap-2">
        <label className="flex h-[38px] items-center gap-1.5 rounded-full border border-border-strong bg-card pr-2 pl-3 text-[13px] font-semibold text-body">
          <span className="text-muted"><Icons.folderBig /></span>
          <select value={place ? placeKey ?? '' : ''} onChange={(e) => setParam('in', e.target.value || null)} aria-label="찾을 곳" className="max-w-[240px] cursor-pointer bg-transparent outline-none">
            <option value="">모든 프로젝트</option>
            <optgroup label="1. 프로젝트">
              {(places ?? []).filter((p) => p.type === 'PROJECT').map((p) => (
                <option key={p.id} value={`p:${p.id}`}>
                  {p.name}
                </option>
              ))}
            </optgroup>
            <optgroup label="2. 반복실행">
              {(places ?? []).filter((p) => p.type === 'AREA').map((p) => (
                <option key={p.id} value={`a:${p.id}`}>
                  {p.name}
                </option>
              ))}
            </optgroup>
          </select>
        </label>
        <Seg label="기간" items={PERIODS} value={period} onChange={setPeriod} />
        <Seg label="종류" items={KINDS} value={kind} onChange={setKind} />
      </div>

      {q === '' && <Note>할 일·회의록·자료에서 글자 그대로 찾아요. 전사(받아쓴 원문)는 찾지 않아요.</Note>}
      {q !== '' && q.length < SEARCH_MIN && <Note>두 글자 이상 적어 주세요</Note>}
      {q.length >= SEARCH_MIN && failed && (
        <div className="mb-4 flex items-center gap-3 rounded-lg bg-crit-soft p-3 text-sm text-crit">
          <span className="flex-1">검색하지 못했어요. 인터넷 연결을 확인해 주세요.</span>
          <button type="button" className="font-bold underline" onClick={() => setRunNow((n) => n + 1)}>
            다시 시도
          </button>
        </div>
      )}
      {q.length >= SEARCH_MIN && !failed && !results && <Note>찾는 중…</Note>}
      {q.length >= SEARCH_MIN && !failed && results && (
        <>
          {results.shown.length === 0 && !(draftsOpen && results.candidates.length > 0) ? (
            <div className={cn(CARD, 'px-6 py-8 text-center')}>
              <p className="text-base font-bold text-ink">찾는 결과가 없어요</p>
              {relax.length > 0 && (
                <div className="mt-3 flex flex-wrap justify-center gap-2">
                  {relax.map((r) => (
                    <button key={r} type="button" onClick={() => applyRelax(r)} className="h-9 rounded-full border border-border-strong bg-card px-4 text-[13px] font-semibold text-body hover:bg-surface">
                      {r === 'candidates' ? `할 일 후보 ${results.candidates.length}건도 보기` : RELAX_LABEL[r]}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <>
              <p className="mb-2 text-[13px] text-muted" aria-live="polite">
                찾은 결과 {results.shown.length}건
                {results.shown.some((r) => r.draft) && ` · 그중 초안 ${results.shown.filter((r) => r.draft).length}건`}
                {loading && ' · 다시 찾는 중…'}
              </p>
              <ResultCard rows={results.shown} q={q} emptyText="할 일 후보 말고는 없어요" />
            </>
          )}
          {results.candidates.length > 0 && (
            <div className="mt-6">
              <button
                type="button"
                onClick={() => setDraftsOpen((o) => !o)}
                aria-expanded={draftsOpen}
                className={cn('flex w-full items-center gap-1 py-2 text-left text-[13px] font-semibold', draftsOpen ? 'text-muted' : 'text-link')}
              >
                <span className="flex-1">
                  {draftsOpen ? `검토 대기 할 일 후보 ${results.candidates.length}건 · 아직 확정 사실이 아니에요` : `검토 대기 할 일 후보 ${results.candidates.length}건 · 보기`}
                </span>
                <Icons.chevron open={draftsOpen} />
              </button>
              {draftsOpen && <ResultCard rows={results.candidates} q={q} emptyText="" />}
            </div>
          )}
        </>
      )}

      <p className="mt-6 text-xs text-muted">
        지금은 <b>키워드 검색</b>이에요. 뜻으로 찾기(의미 검색)는 다음 단계(P2)예요. 휴지통에 넣은 기록과 다른 사람의 자료는 결과에 나오지 않아요.
      </p>
    </>
  )
}

function Note({ children }: { children: ReactNode }) {
  return <p className="py-2 text-sm text-muted">{children}</p>
}

/** 결과 묶음 카드(목업 .card 안 .res 줄들) */
function ResultCard({ rows, q, emptyText }: { rows: SearchRow[]; q: string; emptyText: string }) {
  return (
    <div className={CARD}>
      {rows.length === 0 && <p className="p-6 text-sm text-muted">{emptyText}</p>}
      {rows.map((r) => (
        <ResultRow key={`${r.kind}:${r.id}`} row={r} q={q} />
      ))}
    </div>
  )
}

/** 목업 .res: 종류 칩 · 제목(강조) · 완료·보관됨 / 조각(강조) / 📁 곳 · 날짜 · 초안 · 원본 음성 만료 */
function ResultRow({ row, q }: { row: SearchRow; q: string }) {
  const to = targetOf(row)
  const placeName = row.place_name ?? (row.kind === KIND_TASK ? UNSORTED : null)
  const from = row.kind === KIND_PROPOSAL && row.record_title ? `회의 ‘${row.record_title}’` : null
  const meta = [metaDate(row), row.draft ? '초안' : null, from].filter((x): x is string => x !== null)
  const body = (
    <>
      <div className="flex items-start gap-2">
        <span className={cn('mt-0.5 flex-none rounded-full px-2 py-px text-[11px] font-bold whitespace-nowrap', kindChip(row.kind))}>{kindLabel(row.kind)}</span>
        <span className={cn('min-w-0 text-base leading-6 font-bold', row.done ? 'text-muted line-through' : 'text-ink')}>
          <Highlighted text={row.title ?? (row.kind === KIND_RECORD ? '제목 없는 기록' : '제목 없음')} q={q} />
        </span>
        {row.done && <span className={cn(MUTE_CHIP, 'mt-0.5')}>완료</span>}
        {row.archived && <span className={cn(MUTE_CHIP, 'mt-0.5')}>보관됨</span>}
      </div>
      {row.snippet && (
        <p className="mt-1 text-sm leading-relaxed text-body">
          <Highlighted text={row.snippet} q={q} />
        </p>
      )}
      <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-xs text-muted">
        {placeName && (
          <span className="inline-flex items-center gap-1">
            <Icons.folder />
            {placeName}
          </span>
        )}
        {meta.map((m, i) => (
          <span key={m}>{placeName || i > 0 ? `· ${m}` : m}</span>
        ))}
        {row.audio_expired && <span className={MUTE_CHIP}>원본 음성 만료</span>}
      </div>
    </>
  )
  const cls = 'block border-b border-border p-4 last:border-b-0'
  return to ? (
    <Link to={to} className={cn(cls, 'text-inherit hover:bg-surface')}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  )
}
