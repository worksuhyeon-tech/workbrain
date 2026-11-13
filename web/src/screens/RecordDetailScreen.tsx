import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { CandidateRow } from '../components/CandidateRow'
import { Icons } from '../components/icons'
import { MinutesDoc } from '../components/MinutesDoc'
import { Seg } from '../components/Seg'
import { CARD, TONE_CHIP } from '../components/ui'
import { loadRecordDetail, type ProjectName, type ProposalRow, type RecordDetail, type Transcript } from '../data/records'
import { cn } from '../lib/cn'
import {
  CHANNEL_LABEL,
  CLASS_LABEL,
  changeLines,
  clockText,
  decisionsOf,
  durationText,
  fullTimeText,
  mdOf,
  meetingTimeText,
  overlappingDecision,
  processStep,
  proposalSegments,
  snoozeActive,
  snoozeLabel,
  speakerTurns,
  suggestionNote,
  textsOf,
  trackedTitlesOf,
  type Tone,
} from '../rules/records'

const CHIP = 'rounded-full px-2.5 py-[3px] text-xs font-bold whitespace-nowrap'
const LOAD_FAILED = '기록을 불러오지 못했어요. 인터넷 연결을 확인해 주세요.'
// ②-3·②-4에서 열 손대기 기능 자리 안내(그전까지는 앱에서)
const ACT_LATER = '할 일 추가·버리기, 제목·참석자·프로젝트 고치기는 다음 단계에서 열어요. 지금은 휴대폰 앱에서 해 주세요.'

type Tab = 'minutes' | 'transcript' | 'projects'
const TABS: { value: Tab; label: string }[] = [
  { value: 'minutes', label: '회의록 · 할 일' },
  { value: 'transcript', label: '전사' },
  { value: 'projects', label: '관련 프로젝트' },
]

/**
 * 기록 상세(웹 목업 record-detail.html, 앱 RecordDetailScreen): 머리(제목·종류 알약·분류·프로젝트 칩) · 처리 단계 ·
 * 탭(회의록·할 일 | 전사 | 관련 프로젝트). 회의록·할 일 탭은 추출 항목(참석자·결정 후보·정하지 못한 것·할 일 후보)과 회의록 문서.
 * 지금(②-2)은 보기만 — 후보 추가·고치기·연결·회의록 승인은 ②-3·②-4에서 같은 자리에 붙인다
 */
export function RecordDetailScreen() {
  const { id = '' } = useParams()
  // undefined = 읽는 중, null = 없는 기록
  const [d, setD] = useState<RecordDetail | null | undefined>(undefined)
  const [failed, setFailed] = useState(false)
  const [tab, setTab] = useState<Tab>('minutes')

  const reload = useCallback(() => {
    setFailed(false)
    loadRecordDetail(id).then(setD, (e) => {
      console.error('load record detail failed', e)
      setFailed(true)
    })
  }, [id])
  useEffect(() => {
    setD(undefined)
    setTab('minutes')
    reload()
  }, [reload])

  if (d === undefined) {
    return failed ? (
      <div className="flex items-center gap-3 rounded-lg bg-crit-soft p-3 text-sm text-crit">
        <span className="flex-1">{LOAD_FAILED}</span>
        <button type="button" onClick={reload} className="font-semibold underline">
          다시 불러오기
        </button>
      </div>
    ) : (
      <p className="py-10 text-center text-sm text-muted">불러오는 중…</p>
    )
  }
  if (d === null) {
    return (
      <div className={cn(CARD, 'px-6 py-10 text-center')}>
        <p className="text-[15px] font-semibold text-ink">이 기록을 찾지 못했어요</p>
        <p className="mt-1 text-[13px] text-muted">
          지웠거나 다른 계정의 기록이에요. <Link to="/records">기록 목록</Link>으로 가요.
        </p>
      </div>
    )
  }

  const { info } = d
  const pending = d.proposals.filter((p) => p.status === 'pending')
  const names = new Map(d.projects.map((p) => [p.id, p]))
  const linked = d.linkedProjectIds.flatMap((pid) => names.get(pid) ?? [])
  // 이미 이었거나 보관된 프로젝트의 제안은 보이지 않는다(앱 visibleSuggestions)
  const suggested = d.suggestions.flatMap((s) => {
    const p = names.get(s.projectId)
    return p && !p.archived_at && !d.linkedProjectIds.includes(s.projectId) ? [{ s, p }] : []
  })
  const metaParts = [
    info.channel ? CHANNEL_LABEL[info.channel] : null,
    info.durationSec != null ? durationText(info.durationSec) : null,
    meetingTimeText(info.occurred_at ?? info.ingested_at),
  ].filter(Boolean)
  const md = mdOf(info.occurred_at ?? info.ingested_at)

  return (
    <>
      <nav aria-label="경로" className="mb-1 text-[13px] text-muted">
        <Link to="/records" className="text-muted">
          기록
        </Link>{' '}
        › {info.title}
        {md ? ` ${md}` : ''}
      </nav>
      <div className="mb-5 flex flex-wrap items-center gap-2.5">
        <h1 className="mr-1 text-[28px] font-bold tracking-[-0.17px]">{info.title}</h1>
        {metaParts.length > 0 && (
          <span className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1 text-[13px] font-semibold text-body">
            <span className="text-muted">{info.channel === 'text-import' ? <Icons.doc /> : info.channel === 'call-share' ? <Icons.phone /> : <Icons.mic />}</span>
            {metaParts.join(' · ')}
          </span>
        )}
        {info.classification && CLASS_LABEL[info.classification] && (
          <span className={cn(CHIP, TONE_CHIP[info.classification === 'work' ? 'info' : info.classification === 'uncertain' ? 'warn' : 'mute'])}>
            {CLASS_LABEL[info.classification]}
          </span>
        )}
        {linked.map((p) => (
          <ProjectChip key={p.id} p={p} onClick={() => setTab('projects')} />
        ))}
        {suggested.map(({ p }) => (
          <button
            key={p.id}
            type="button"
            onClick={() => setTab('projects')}
            title="AI 연결 제안"
            className="inline-flex items-center gap-1 rounded-full border border-dashed border-border-strong px-3 py-1 text-[13px] font-semibold text-muted"
          >
            <Icons.folder /> {p.name} · 제안
          </button>
        ))}
      </div>

      <ProcessSteps d={d} pendingCount={pending.length} />

      <div role="tablist" aria-label="기록 보기" className="mb-4 flex gap-1 border-b border-border">
        {TABS.map((t) => (
          <button
            key={t.value}
            type="button"
            role="tab"
            aria-selected={tab === t.value}
            onClick={() => setTab(t.value)}
            className={cn(
              '-mb-px border-b-2 px-4 py-2.5 text-[15px] font-semibold',
              tab === t.value ? 'border-primary text-primary' : 'border-transparent text-muted hover:text-ink',
            )}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'minutes' && <MinutesTab d={d} />}
      {tab === 'transcript' && <TranscriptTab corrected={d.corrected} original={d.original} />}
      {tab === 'projects' && <ProjectsTab d={d} linked={linked} suggested={suggested} />}
    </>
  )
}

function ProjectChip({ p, onClick }: { p: ProjectName; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn('inline-flex items-center gap-1 rounded-full border border-border bg-card px-3 py-1 text-[13px] font-semibold', p.archived_at ? 'text-muted' : 'text-ink')}
    >
      <span className={p.archived_at ? 'text-muted' : 'text-tree-folder'}>
        <Icons.folder />
      </span>
      {p.name}
    </button>
  )
}

const STEP_LABELS = ['업로드', '전사', '분석 동의', '분석', '검토 가능']

/** 목업 '처리 단계': 지난 단계는 초록 체크, 지금 단계는 파란 점(실패면 빨강), 남은 단계는 회색 */
function ProcessSteps({ d, pendingCount }: { d: RecordDetail; pendingCount: number }) {
  const job = d.info.job
  const current = processStep(job)
  const failed = job?.stage_state === 'FAILED'
  const subs = ['수신됨', d.info.channel === 'text-import' ? '받아쓴 글' : '한국어 STT', '동의됨', '회의록·제안', '지금']
  const hand = failed ? '멈췄어요' : current < 4 ? '정리하는 중' : pendingCount > 0 ? '거의 다 왔어요' : '정리 끝!'
  return (
    <section aria-label="처리 단계" className={cn(CARD, 'mb-5 px-6 py-5')}>
      <div className="mb-4 flex items-baseline gap-2">
        <h2 className="text-[15px] font-bold">처리 단계</h2>
        <span className="font-hand text-lg text-muted">{hand}</span>
      </div>
      <ol className="flex">
        {STEP_LABELS.map((label, i) => {
          const done = i < current || (i === current && current === 4 && !failed)
          const now = i === current
          return (
            <li key={label} className="relative flex flex-1 flex-col items-center text-center">
              {i > 0 && <span className={cn('absolute top-4 right-1/2 h-0.5 w-full', i <= current ? 'bg-good' : 'bg-border')} aria-hidden="true" />}
              <span
                className={cn(
                  'relative z-10 grid size-8 place-items-center rounded-full text-sm font-bold',
                  failed && now ? 'bg-crit text-white' : done ? 'bg-good text-white' : now ? 'bg-primary text-white ring-4 ring-info-soft' : 'border-2 border-border bg-card text-muted',
                )}
              >
                {failed && now ? '!' : done && !(now && current === 4) ? '✓' : now ? '•' : i + 1}
              </span>
              <span className="mt-2 text-[13px] font-semibold text-ink">{label}</span>
              <span className={cn('text-xs', now ? (failed ? 'text-crit' : 'text-primary') : 'text-muted')}>
                {failed && now ? '실패' : i < current || now ? subs[i] : '대기'}
              </span>
            </li>
          )
        })}
      </ol>
      {failed && <p className="mt-4 text-[13px] text-crit">처리하지 못했어요. 휴대폰 앱에서 이 녹음을 지우고 다시 올려 주세요.</p>}
    </section>
  )
}

/** 추출 항목 표의 한 줄(목업 .kv: 왼쪽 이름 칸 + 오른쪽 내용) */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[120px_1fr] gap-3 py-2">
      <dt className="pt-0.5 text-[13px] font-semibold text-muted">{label}</dt>
      <dd className="min-w-0 text-[15px] text-ink">{children}</dd>
    </div>
  )
}

/** 회의록 · 할 일 탭: 위 줄(추출 항목 ↔ 회의록 문서 · 승인 상태 · 지침), 아래 내용 */
function MinutesTab({ d }: { d: RecordDetail }) {
  const [view, setView] = useState<'fields' | 'doc'>('fields')
  const m = d.meeting
  const madeRev = m?.guideline_rev ?? 0
  const status: { text: string; tone: Tone } | null = !m?.document
    ? null
    : m.state === 'approved'
      ? { text: `승인 ${mdOf(m.approved_at) ?? ''}`.trim(), tone: 'good' }
      : d.approvedEarlierAt
        ? { text: `초안 · 승인본 ${mdOf(d.approvedEarlierAt) ?? ''} 있음`, tone: 'warn' }
        : { text: '초안 · 검토 전', tone: 'warn' }
  const madeWith = madeRev > 0 ? [mdOf(m?.guideline_imported_at ?? null), '가져온 지침 적용'].filter(Boolean).join(' ') : '기본 지침으로 만듦'
  const outdated = d.guidelineRev != null && d.guidelineRev !== madeRev

  return (
    <section className={cn(CARD, 'px-6 py-5')}>
      {m && (
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <Seg
            label="보기"
            items={[
              { value: 'fields' as const, label: '추출 항목' },
              { value: 'doc' as const, label: '회의록 문서' },
            ]}
            value={view}
            onChange={setView}
          />
          {view === 'doc' && status && <span className={cn(CHIP, TONE_CHIP[status.tone])}>{status.text}</span>}
          {view === 'doc' && m.document && (
            <span className={cn(CHIP, TONE_CHIP.mute)}>
              {madeWith}
              {outdated ? ' · 그 뒤 지침이 바뀌었어요' : ''}
            </span>
          )}
        </div>
      )}
      {view === 'doc' && m ? (
        m.document ? (
          <MinutesDoc doc={m.document} />
        ) : (
          <div className="py-6 text-center">
            <p className="text-[15px] font-semibold text-ink">아직 회의록이 없어요</p>
            <p className="mt-1 text-[13px] text-muted">회의록 만들기는 다음 단계에서 PC에도 열어요. 지금은 휴대폰 앱의 이 기록에서 만들 수 있어요.</p>
          </div>
        )
      ) : (
        <Fields d={d} />
      )}
    </section>
  )
}

function Fields({ d }: { d: RecordDetail }) {
  const fields = d.meeting?.fields ?? null
  const decisions = decisionsOf(fields)
  const questions = textsOf(fields, 'open_questions')
  const tracked = trackedTitlesOf(fields)
  const [expanded, setExpanded] = useState<Set<number>>(new Set())

  const pendingNew = d.proposals.filter((p) => p.status === 'pending' && !p.target_id)
  // 결정과 겹치는 후보는 그 결정 밑에 접는다(DEC-48)
  const foldOf = (p: ProposalRow) => overlappingDecision(proposalSegments(p.evidence_link), decisions)
  const loose = pendingNew.filter((p) => foldOf(p) === null)
  const updates = d.proposals.filter((p) => p.status === 'pending' && p.target_id)
  const done = d.proposals.filter((p) => p.status !== 'pending')
  const nothing = pendingNew.length === 0 && updates.length === 0 && done.length === 0 && tracked.length === 0
  // 정리가 끝나기 전(처리 중·실패)에는 후보가 없는 게 당연하니 '찾지 못했어요' 대신 지금 상태를 말한다
  const unfinished = processStep(d.info.job) < 4 || d.info.job?.stage_state === 'FAILED'

  const candidate = (p: ProposalRow) => (
    <CandidateRow
      key={p.id}
      after={p.after_json}
      rationale={p.rationale}
      status={p.status}
      badges={p.status === 'pending' && snoozeActive(p.snoozed_until) ? [{ text: `미룸 ${snoozeLabel(p.snoozed_until) ?? ''}`.trim(), tone: 'info' }] : []}
    />
  )

  return (
    <>
      <dl className="divide-y divide-border/60">
        <Field label="제목">{(typeof fields?.title === 'string' && fields.title.trim()) || d.info.title}</Field>
        <Field label="일시">{fullTimeText(d.info.occurred_at ?? d.info.ingested_at) ?? '모름'}</Field>
        <Field label="참석자">{d.info.attendees.length > 0 ? d.info.attendees.join(', ') : <span className="text-muted">아직 없어요</span>}</Field>
        {decisions.length > 0 && (
          <Field label="결정 후보">
            <ul className="space-y-2.5">
              {decisions.map((dec, i) => {
                const overlaps = pendingNew.filter((p) => foldOf(p) === i)
                const open = expanded.has(i)
                return (
                  <li key={i}>
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="inline-flex items-start gap-2 rounded-lg border border-warn/30 bg-warn-soft px-3 py-1.5">
                        <b className="text-xs text-warn">후보</b>
                        <span className="text-[15px] text-ink">{dec.text}</span>
                      </span>
                      {d.openTaskTitles.has(dec.text.trim()) && <span className={cn(CHIP, TONE_CHIP.good)}>할 일 있음</span>}
                      {overlaps.length > 0 && (
                        <button
                          type="button"
                          aria-expanded={open}
                          onClick={() => setExpanded((s) => (s.has(i) ? new Set([...s].filter((x) => x !== i)) : new Set([...s, i])))}
                          className="inline-flex items-center gap-0.5 text-[13px] font-semibold text-muted"
                        >
                          겹치는 할 일 후보 {overlaps.length}개 <Icons.chevron open={open} />
                        </button>
                      )}
                    </div>
                    {open && <div className="mt-2 rounded-lg border border-border">{overlaps.map(candidate)}</div>}
                  </li>
                )
              })}
            </ul>
            <p className="mt-2 text-xs text-muted">※ 회의에서 정한 것처럼 들린 말이에요. 확정은 아니에요. 챙길 일이면 할 일로 옮겨요.</p>
          </Field>
        )}
        {questions.length > 0 && (
          <Field label="정하지 못한 것">
            <ul className="space-y-1">
              {questions.map((q, i) => (
                <li key={i}>· {q}</li>
              ))}
            </ul>
          </Field>
        )}
      </dl>

      {unfinished && nothing ? (
        <p className="mt-4 text-sm text-muted">
          {d.info.job?.stage_state === 'FAILED' ? '처리하지 못해 회의록·할 일 후보가 없어요.' : '정리가 끝나면 회의록과 할 일 후보가 여기에 나와요.'}
        </p>
      ) : (
        <>
          <h3 className="mt-5 mb-1 text-[15px] font-bold">할 일 후보 {loose.length}</h3>
          {nothing && <p className="text-sm text-muted">이 기록에서는 할 일을 찾지 못했어요.</p>}
          {pendingNew.length > 0 && (
            <p className="mb-2 text-[13px] text-muted">
              {ACT_LATER}
              {loose.length < pendingNew.length ? ' 결정과 겹치는 후보는 결정 밑에 접어 뒀어요.' : ''}
            </p>
          )}
          {loose.length > 0 && <div className="rounded-lg border border-border">{loose.map(candidate)}</div>}
        </>
      )}

      {updates.length > 0 && (
        <>
          <h3 className="mt-5 mb-1 text-[15px] font-bold">기존 할 일 변경 {updates.length}</h3>
          <div className="mb-2 flex flex-wrap items-center gap-2 rounded-lg bg-surface px-4 py-2.5 text-[13px] text-body">
            <span className="flex-1">이미 있는 할 일의 담당·기한이 이번 기록에서 달라졌어요. 전후를 비교해야 해서 검토함에서 승인해요.</span>
            <Link to="/review" className="font-semibold">
              검토함에서 보기 →
            </Link>
          </div>
          <div className="rounded-lg border border-border">
            {updates.map((p) => (
              <ChangeRow key={p.id} p={p} />
            ))}
          </div>
        </>
      )}

      {tracked.length > 0 && (
        <>
          <h3 className="mt-5 mb-1 text-[15px] font-bold">이미 있는 할 일 {tracked.length}</h3>
          <p className="mb-2 text-[13px] text-muted">이번 기록에도 나왔지만 이미 할 일에 있어서 새로 만들지 않았어요.</p>
          <ul className="space-y-1 text-[15px]">
            {tracked.map((t, i) => (
              <li key={i}>· {t}</li>
            ))}
          </ul>
        </>
      )}

      {done.length > 0 && (
        <>
          <h3 className="mt-5 mb-1 text-[15px] font-bold text-muted">처리한 후보 {done.length}</h3>
          <div className="rounded-lg border border-border">{done.map((p) => (p.target_id ? <ChangeRow key={p.id} p={p} /> : candidate(p)))}</div>
        </>
      )}
    </>
  )
}

/** 기존 할 일 변경 한 줄: 대상 제목 + 바뀐 칸 '전 → 후' + 근거(앱 CandidateCard before) */
function ChangeRow({ p }: { p: ProposalRow }) {
  const lines = changeLines(p.before_json, p.after_json)
  const title = (typeof p.before_json?.title === 'string' && p.before_json.title) || (typeof p.after_json.title === 'string' ? p.after_json.title : '')
  return (
    <div className="border-b border-border px-4 py-3 last:border-b-0">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[15px] font-semibold text-ink">{title}</span>
        {p.status === 'approved' && <span className={cn(CHIP, TONE_CHIP.good)}>반영됨</span>}
        {p.status === 'rejected' && <span className={cn(CHIP, TONE_CHIP.mute)}>버림</span>}
      </div>
      {lines.map((l) => (
        <div key={l.label} className="mt-1 flex flex-wrap items-center gap-2 text-[13px]">
          <span className="w-10 text-muted">{l.label}</span>
          {l.from != null && <s className="text-muted">{l.from}</s>}
          {l.from != null && <span className="text-muted">→</span>}
          <span className={cn(CHIP, TONE_CHIP.good)}>{l.to}</span>
        </div>
      ))}
      {p.rationale.trim() && <p className="mt-1 truncate text-[13px] text-muted">“{p.rationale}”</p>}
    </div>
  )
}

/** 전사 탭: 교정본이 있으면 먼저, 받아쓴 원문은 따로(원문은 고치지 않음, DEC-36). 화자가 있으면 차례마다(DEC-54) */
function TranscriptTab({ corrected, original }: { corrected: Transcript | null; original: Transcript | null }) {
  const [showOriginal, setShowOriginal] = useState(false)
  const shown = corrected && !showOriginal ? corrected : original
  const turns = shown ? speakerTurns(shown.segments) : []
  return (
    <section className={cn(CARD, 'px-6 py-5')}>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <h2 className="flex-1 text-[15px] font-bold">{corrected && !showOriginal ? '전사 · 업무 용어 교정본' : '받아쓴 원문'}</h2>
        {corrected && (
          <button type="button" onClick={() => setShowOriginal((o) => !o)} className="text-[13px] font-semibold">
            {showOriginal ? '교정본 보기' : '받아쓴 원문 보기'}
          </button>
        )}
      </div>
      {!shown ? (
        <p className="text-sm text-muted">아직 전사가 없어요.</p>
      ) : turns.length > 0 ? (
        <div className="max-w-[860px] space-y-3">
          {turns.map((t, i) => (
            <div key={i}>
              <div className="text-xs font-semibold text-muted">{[t.speaker, t.offsetMs != null ? clockText(t.offsetMs) : null].filter(Boolean).join(' · ')}</div>
              <p className="mt-0.5 text-[15px] leading-relaxed text-body">{t.text}</p>
            </div>
          ))}
        </div>
      ) : (
        <p className="max-w-[860px] text-[15px] leading-relaxed whitespace-pre-wrap text-body">{shown.content}</p>
      )}
    </section>
  )
}

/** 관련 프로젝트 탭: 분류 · 연결된 프로젝트 · AI 연결 제안(이유·근거 한 문장). 연결·해제는 ②-3 */
function ProjectsTab({ d, linked, suggested }: { d: RecordDetail; linked: ProjectName[]; suggested: { s: RecordDetail['suggestions'][number]; p: ProjectName }[] }) {
  const cls = d.info.classification
  return (
    <section className={cn(CARD, 'px-6 py-5')}>
      <dl className="divide-y divide-border/60">
        <Field label="분류">{cls && CLASS_LABEL[cls] ? CLASS_LABEL[cls] : <span className="text-muted">아직 없어요</span>}</Field>
        <Field label="연결된 프로젝트">
          {linked.length === 0 ? (
            <span className="text-muted">아직 없어요</span>
          ) : (
            <ul className="space-y-1.5">
              {linked.map((p) => (
                <li key={p.id}>
                  <Link to={`/projects/p:${p.id}`} className={cn('inline-flex items-center gap-1.5 font-semibold', p.archived_at && 'text-muted')}>
                    <span className={p.archived_at ? 'text-muted' : 'text-tree-folder'}>
                      <Icons.folderBig />
                    </span>
                    {p.name}
                    {p.archived_at ? ' · 보관됨' : ''}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Field>
        {suggested.length > 0 && (
          <Field label="AI 연결 제안">
            <ul className="space-y-3">
              {suggested.map(({ s, p }) => {
                const note = suggestionNote(s.confidence, s.reasons)
                return (
                  <li key={p.id} className="rounded-lg border border-dashed border-border-strong px-4 py-3">
                    <div className="font-semibold">{p.name}</div>
                    {s.reason && <p className="mt-0.5 text-sm text-body">{s.reason}</p>}
                    {s.quote && <p className="mt-1 text-[13px] text-muted">“{s.quote}”</p>}
                    {note && <p className="mt-1 text-xs font-semibold text-warn">{note}</p>}
                  </li>
                )
              })}
            </ul>
          </Field>
        )}
      </dl>
      <p className="mt-4 text-xs text-muted">연결·해제와 분류 바꾸기는 다음 단계에서 열어요. 지금은 휴대폰 앱의 이 기록에서 해 주세요.</p>
    </section>
  )
}
