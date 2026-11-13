import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { CandidateEditDialog, type CandidateEditMode } from '../components/CandidateEditDialog'
import { CandidateRow } from '../components/CandidateRow'
import { ChangeRow } from '../components/ChangeRow'
import { ConfirmDialog, Dialog, PRIMARY_BUTTON, SECONDARY_BUTTON } from '../components/Dialog'
import { Icons } from '../components/icons'
import { MinutesDoc } from '../components/MinutesDoc'
import { PlaceSelect, placeKeyOf, placeOfKey } from '../components/PlaceSelect'
import { RecipientsDialog } from '../components/RecipientsDialog'
import { Seg } from '../components/Seg'
import { useToast } from '../components/Toast'
import { useMinutesMaker } from '../components/useMinutesMaker'
import { CARD, TONE_CHIP } from '../components/ui'
import {
  approveMeeting,
  approveProposal,
  linkRecord,
  loadRecordDetail,
  minutesWord,
  rejectProposal,
  renameRecord,
  requestProjectState,
  saveAttendees,
  setClassification,
  type ProjectName,
  type ProposalRow,
  type RecordDetail,
  type Transcript,
} from '../data/records'
import { createTask, loadPlaces } from '../data/server'
import { cn } from '../lib/cn'
import type { Place } from '../rules/place'
import {
  CHANNEL_LABEL,
  CLASS_LABEL,
  checkedAtFirst,
  clockText,
  decisionTaskEdit,
  decisionsOf,
  durationText,
  followNamesOf,
  fullTimeText,
  mdOf,
  meetingTimeText,
  overlappingDecision,
  processStep,
  proposalSegments,
  recipientsOf,
  snoozeActive,
  snoozeLabel,
  speakerTurns,
  suggestionNote,
  textsOf,
  trackedTitlesOf,
  withPlace,
  type Tone,
} from '../rules/records'
import { approveMeetingText, minutesFileName } from '../rules/minutes'
import { newTaskFields, UNSORTED } from '../rules/tasks'

const CHIP = 'rounded-full px-2.5 py-[3px] text-xs font-bold whitespace-nowrap'
const INPUT = 'h-[38px] w-full rounded-md border border-border-strong bg-card px-3 text-sm text-ink'
const LOAD_FAILED = '기록을 불러오지 못했어요. 인터넷 연결을 확인해 주세요.'
const NET_FAILED = '인터넷 연결을 확인해 주세요'

type Tab = 'minutes' | 'transcript' | 'projects'
const TABS: { value: Tab; label: string }[] = [
  { value: 'minutes', label: '회의록 · 할 일' },
  { value: 'transcript', label: '전사' },
  { value: 'projects', label: '관련 프로젝트' },
]
type Json = Record<string, unknown>

/** 화면 안 손대기 공용: 다시 읽기(성공하면 새 값) · 알림 · 넣을 곳 목록 */
interface Act {
  refresh: () => Promise<RecordDetail | null | undefined>
  toast: (text: string) => void
  places: Place[]
}

/**
 * 기록 상세(웹 목업 record-detail.html, 앱 RecordDetailScreen): 머리(제목 ✏·종류 알약·분류·프로젝트 칩) · 처리 단계 ·
 * 탭(회의록·할 일 | 전사 | 관련 프로젝트). 할 일 후보를 골라 고치고 바로 추가하고(승인 기록은 검토함과 같다, DEC-23),
 * 결정 후보를 할 일로 만들고, 참석자·분류·프로젝트 연결을 고친다. 회의록은 만들기·다시 만들기·승인·Word 받기(②-4)
 */
export function RecordDetailScreen() {
  const { id = '' } = useParams()
  // undefined = 읽는 중, null = 없는 기록
  const [d, setD] = useState<RecordDetail | null | undefined>(undefined)
  const [failed, setFailed] = useState(false)
  const [tab, setTab] = useState<Tab>('minutes')
  const [places, setPlaces] = useState<Place[]>([])
  const [dialog, setDialog] = useState<'rename' | 'attendees' | null>(null)
  const toast = useToast()

  const refresh = useCallback(async () => {
    try {
      const next = await loadRecordDetail(id)
      setD(next)
      setFailed(false)
      return next
    } catch (e) {
      console.error('load record detail failed', e)
      setFailed(true)
      return undefined
    }
  }, [id])
  useEffect(() => {
    setD(undefined)
    setTab('minutes')
    void refresh()
  }, [refresh])
  // 넣을 곳은 보조 정보라 못 읽으면 '정리 안 됨'만 고를 수 있다
  useEffect(() => {
    loadPlaces().then(setPlaces, (e) => console.warn('load places failed', e))
  }, [])

  const failBanner = failed && (
    <div className="mb-4 flex items-center gap-3 rounded-lg bg-crit-soft p-3 text-sm text-crit">
      <span className="flex-1">{LOAD_FAILED}</span>
      <button type="button" onClick={() => void refresh()} className="font-semibold underline">
        다시 불러오기
      </button>
    </div>
  )
  if (d === undefined) return failed ? failBanner : <p className="py-10 text-center text-sm text-muted">불러오는 중…</p>
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

  const act: Act = { refresh, toast: toast.show, places }
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
      {failBanner}
      <nav aria-label="경로" className="mb-1 text-[13px] text-muted">
        <Link to="/records" className="text-muted">
          기록
        </Link>{' '}
        › {info.title}
        {md ? ` ${md}` : ''}
      </nav>
      <div className="mb-5 flex flex-wrap items-center gap-2.5">
        <h1 className="text-[28px] font-bold tracking-[-0.17px]">{info.title}</h1>
        <button type="button" onClick={() => setDialog('rename')} aria-label="제목 고치기" title="제목 고치기" className="mr-1 grid size-8 place-items-center rounded-full text-muted hover:bg-surface hover:text-ink">
          <Icons.pencil />
        </button>
        {metaParts.length > 0 && (
          <span className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1 text-[13px] font-semibold text-body">
            <span className="text-muted">{info.channel === 'text-import' ? <Icons.doc /> : info.channel === 'call-share' ? <Icons.phone /> : <Icons.mic />}</span>
            {metaParts.join(' · ')}
          </span>
        )}
        {info.classification && CLASS_LABEL[info.classification] && (
          <button type="button" onClick={() => setTab('projects')} title="분류 고치기" className={cn(CHIP, TONE_CHIP[info.classification === 'work' ? 'info' : info.classification === 'uncertain' ? 'warn' : 'mute'])}>
            {CLASS_LABEL[info.classification]}
          </button>
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
        <button type="button" onClick={() => setTab('projects')} className="px-1 text-[13px] font-semibold text-link">
          {linked.length === 0 ? '+ 프로젝트 연결' : '+ 프로젝트'}
        </button>
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
            className={cn('-mb-px border-b-2 px-4 py-2.5 text-[15px] font-semibold', tab === t.value ? 'border-primary text-primary' : 'border-transparent text-muted hover:text-ink')}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'minutes' && <MinutesTab d={d} act={act} onEditAttendees={() => setDialog('attendees')} />}
      {tab === 'transcript' && <TranscriptTab corrected={d.corrected} original={d.original} />}
      {tab === 'projects' && <ProjectsTab d={d} act={act} linked={linked} suggested={suggested} />}

      {dialog === 'rename' && (
        <TitleDialog
          title={info.title}
          onDismiss={() => setDialog(null)}
          onSave={async (t) => {
            try {
              await renameRecord(info.id, t)
              setDialog(null)
              act.toast('제목을 바꿨어요')
              await refresh()
            } catch {
              act.toast(`제목을 바꾸지 못했어요. ${NET_FAILED}`)
            }
          }}
        />
      )}
      {dialog === 'attendees' && (
        <AttendeesDialog
          names={info.attendees}
          onDismiss={() => setDialog(null)}
          onSave={async (list) => {
            try {
              await saveAttendees(info.id, list)
              setDialog(null)
              // 이미 만든 회의록은 저절로 바뀌지 않는다(다시 만들기를 눌러야 새 명단이 반영됨)
              act.toast(d.meeting?.document ? "참석자를 바꿨어요 · 회의록 문서를 '다시 만들기'하면 반영돼요" : '참석자를 바꿨어요')
              await refresh()
            } catch {
              act.toast(`참석자를 바꾸지 못했어요. ${NET_FAILED}`)
            }
          }}
        />
      )}
      {toast.element}
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
              <span className={cn('text-xs', now ? (failed ? 'text-crit' : 'text-primary') : 'text-muted')}>{failed && now ? '실패' : i < current || now ? subs[i] : '대기'}</span>
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

const SPINNER = 'inline-block size-3.5 animate-spin rounded-full border-2 border-primary border-t-transparent'

/**
 * 회의록 · 할 일 탭: 위 줄(추출 항목 ↔ 회의록 문서 · 승인 상태 · 지침 · Word·다시 만들기), 아래 내용.
 * 회의록 문서는 버튼을 눌렀을 때만 만든다(DEC-56, AI 비용·1~2분). 승인은 문서를 다 읽고 누르도록 문서 맨 아래(DEC-61)
 */
function MinutesTab({ d, act, onEditAttendees }: { d: RecordDetail; act: Act; onEditAttendees: () => void }) {
  const [view, setView] = useState<'fields' | 'doc'>('fields')
  const [downloading, setDownloading] = useState(false)
  const [confirmApprove, setConfirmApprove] = useState(false)
  const [approving, setApproving] = useState(false)
  const maker = useMinutesMaker(
    d.info.id,
    async () => {
      act.toast('회의록이 만들어졌어요')
      await act.refresh()
    },
    act.toast,
  )
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

  // 화면에 보이는 회의록 버전으로 Word를 만들어 받는다(FR-29, DEC-60)
  const download = async (versionId: string) => {
    setDownloading(true)
    try {
      const w = await minutesWord(versionId)
      const url = URL.createObjectURL(w.blob)
      const a = document.createElement('a')
      a.href = url
      a.download = minutesFileName(w.meetingDate)
      a.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
      act.toast(w.draft ? 'Word를 받았어요 · 승인 전이라 ‘초안’ 표시가 있어요' : 'Word를 받았어요')
    } catch (e) {
      console.error('minutes word failed', e)
      act.toast('Word를 만들지 못했어요. 인터넷 연결을 확인하고 다시 눌러 주세요')
    }
    setDownloading(false)
  }

  const approve = async (versionId: string) => {
    setApproving(true)
    const result = await approveMeeting(versionId).catch(() => null)
    act.toast(approveMeetingText(result))
    await act.refresh()
    setApproving(false)
  }

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
          {view === 'doc' && m.document && <span className={cn(CHIP, TONE_CHIP.mute)}>{madeWith}</span>}
          <div className="flex-1" />
          {view === 'doc' && m.document && (
            <>
              {maker.making ? (
                <span className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-primary">
                  <span className={SPINNER} aria-hidden="true" /> 다시 만드는 중…
                </span>
              ) : (
                // 지침이 바뀐 문서만 눈에 띄게 알리고, 평소 '다시 만들기'는 글자 단추(앱 ⋮ 메뉴 자리)
                <button type="button" onClick={maker.start} className={cn('text-[13px] font-semibold', outdated ? 'text-link' : 'text-muted hover:text-ink')}>
                  {outdated ? '새 지침으로 다시 만들기 ›' : '다시 만들기'}
                </button>
              )}
              <button type="button" disabled={downloading} onClick={() => void download(m.id)} className={cn(SECONDARY_BUTTON, 'inline-flex items-center gap-1.5')}>
                <Icons.download /> {downloading ? 'Word 만드는 중…' : 'Word 다운로드'}
              </button>
            </>
          )}
        </div>
      )}
      {view === 'doc' && m ? (
        m.document ? (
          <>
            <MinutesDoc doc={m.document} />
            {m.state !== 'approved' && (
              <div className="mt-6 flex flex-wrap items-center gap-3 rounded-lg bg-surface px-4 py-3">
                <span className="flex-1 text-[13px] text-body">다 읽었으면 승인해요. 승인하면 Word의 ‘초안’ 표시가 빠지고 3년 보관돼요. 할 일은 바뀌지 않아요.</span>
                <button type="button" disabled={approving || maker.making} onClick={() => setConfirmApprove(true)} className={PRIMARY_BUTTON}>
                  {approving ? '승인하는 중…' : '이 회의록 승인'}
                </button>
              </div>
            )}
          </>
        ) : (
          <div className="py-8 text-center">
            <p className="text-[15px] font-semibold text-ink">아직 회의록이 없어요</p>
            <p className="mt-1 mb-4 text-[13px] text-muted">회의록 지침대로 회의 개요·핵심 요약·결정사항·후속 조치를 정리해요. 1~2분쯤 걸려요.</p>
            {maker.making ? (
              <span className="inline-flex items-center gap-2 text-sm font-semibold text-primary">
                <span className={SPINNER} aria-hidden="true" /> 회의록 만드는 중 · 다른 화면에 다녀와도 이어서 기다려요
              </span>
            ) : (
              <button type="button" onClick={maker.start} className={PRIMARY_BUTTON}>
                회의록 만들기
              </button>
            )}
          </div>
        )
      ) : (
        <Fields d={d} act={act} onEditAttendees={onEditAttendees} />
      )}
      {confirmApprove && m && (
        <ConfirmDialog
          title="이 회의록 승인"
          text={
            <>
              승인하면 Word의 ‘초안’ 표시가 빠지고 3년 보관돼요. 할 일은 바뀌지 않아요.
              <br />
              <br />
              승인은 되돌릴 수 없어요. 고칠 게 생기면 다시 만들어 새로 승인하면 돼요.
            </>
          }
          confirmLabel="승인"
          onDismiss={() => setConfirmApprove(false)}
          onConfirm={() => {
            setConfirmApprove(false)
            void approve(m.id)
          }}
        />
      )}
    </section>
  )
}

/**
 * 추출 항목 + 할 일 후보 고르기(앱 RecordDetailScreen 'fields'). 고친 값·체크는 추가 전까지 이 화면에만 있다.
 * 체크는 사용자가 누른 것만 저장하고, 누르지 않은 후보는 처음 규칙(결정과 겹침·미룸이면 빼 둠)을 따른다
 */
function Fields({ d, act, onEditAttendees }: { d: RecordDetail; act: Act; onEditAttendees: () => void }) {
  const fields = d.meeting?.fields ?? null
  const decisions = decisionsOf(fields)
  const questions = textsOf(fields, 'open_questions')
  const tracked = trackedTitlesOf(fields)
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const [checked, setChecked] = useState<Record<string, boolean>>({})
  const [edits, setEdits] = useState<Record<string, Json>>({})
  // '추가할 곳'을 사용자가 고르면 그 값, 아니면 연결된 프로젝트가 하나일 때 그 프로젝트(DEC-69)
  const [chosenPlace, setChosenPlace] = useState<{ place: Place | null } | null>(null)
  const [editing, setEditing] = useState<{ p: ProposalRow; mode: Exclude<CandidateEditMode, 'create'> } | null>(null)
  const [recipientsFor, setRecipientsFor] = useState<ProposalRow | null>(null)
  const [creating, setCreating] = useState<string | null>(null)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const [leftover, setLeftover] = useState<ProposalRow[] | null>(null)
  const [busy, setBusy] = useState(false)

  // 결정과 겹치는 후보는 그 결정 밑에 접는다(DEC-48)
  const foldOf = (p: ProposalRow) => overlappingDecision(proposalSegments(p.evidence_link), decisions)
  const isChecked = (p: ProposalRow) => checked[p.id] ?? checkedAtFirst(foldOf(p) !== null, p.snoozed_until)
  const pendingNew = d.proposals.filter((p) => p.status === 'pending' && !p.target_id)
  const loose = pendingNew.filter((p) => foldOf(p) === null)
  const updates = d.proposals.filter((p) => p.status === 'pending' && p.target_id)
  const done = d.proposals.filter((p) => p.status !== 'pending')
  const selected = pendingNew.filter(isChecked)
  const nothing = pendingNew.length === 0 && updates.length === 0 && done.length === 0 && tracked.length === 0
  // 정리가 끝나기 전(처리 중·실패)에는 후보가 없는 게 당연하니 '찾지 못했어요' 대신 지금 상태를 말한다
  const unfinished = processStep(d.info.job) < 4 || d.info.job?.stage_state === 'FAILED'
  const singleProject = d.linkedProjectIds.length === 1 ? (act.places.find((p) => p.type === 'PROJECT' && p.id === d.linkedProjectIds[0]) ?? null) : null
  const place = chosenPlace ? chosenPlace.place : singleProject
  const followNames = followNamesOf(d.info.attendees, d.proposals.map((p) => p.after_json.assignee), d.myNames)

  const current = (p: ProposalRow): Json => ({ ...p.after_json, ...edits[p.id] })
  // 표에서 고친 칸은 고친 값에 덮고 체크한다(고친 것은 추가하려는 것, 검토함 고치기와 같음)
  const edit = (p: ProposalRow, f: Json) => {
    setEdits((e) => ({ ...e, [p.id]: { ...e[p.id], ...f } }))
    setChecked((c) => ({ ...c, [p.id]: true }))
  }

  const discard = async (drop: ProposalRow[]) => {
    setBusy(true)
    try {
      for (const p of drop) await rejectProposal(p, 'record')
      act.toast(`후보 ${drop.length}개를 버렸어요`)
    } catch {
      act.toast('버리지 못했어요. 다시 시도해 주세요')
    }
    await act.refresh()
    setBusy(false)
  }

  const addSelected = async () => {
    const pick = selected
    if (pick.some((p) => typeof current(p).title !== 'string' || !(current(p).title as string).trim())) {
      act.toast('제목이 빈 할 일이 있어요. 제목을 넣어 주세요')
      return
    }
    setBusy(true)
    let results: string[] | null = null
    try {
      results = []
      // 다른 기기에서 먼저 승인했으면 already-approved로 돌아와 중복 없이 넘어간다(TC-08)
      for (const p of pick) results.push(await approveProposal(p, 'record', withPlace(edits[p.id], place)))
    } catch (e) {
      console.error('approve failed', e)
      results = null
    }
    const allAdded = results !== null && results.every((r) => r === 'approved' || r === 'already-approved')
    act.toast(
      results === null
        ? `추가하지 못했어요. ${NET_FAILED}`
        : allAdded
          ? `할 일 ${pick.length}개를 ‘${place?.name ?? UNSORTED}’에 추가했어요`
          : `일부는 추가하지 못했어요(${results.filter((r) => r !== 'approved').join(', ')})`,
    )
    const fresh = await act.refresh()
    setBusy(false)
    // 체크하지 않고 남은 후보는 버릴지 묻는다(사용자 요청 11/05). 미룬 후보와 결정 밑에 접힌 후보(DEC-85)는 묻지 않는다
    if (allAdded && fresh) {
      const decisionsNow = decisionsOf(fresh.meeting?.fields ?? null)
      const rest = fresh.proposals.filter(
        (p) =>
          p.status === 'pending' &&
          !p.target_id &&
          checked[p.id] !== true &&
          !snoozeActive(p.snoozed_until) &&
          overlappingDecision(proposalSegments(p.evidence_link), decisionsNow) === null,
      )
      if (rest.length > 0) setLeftover(rest)
    }
  }

  const candidate = (p: ProposalRow) => {
    const after = current(p)
    return (
      <CandidateRow
        key={p.id}
        after={after}
        rationale={p.rationale}
        status={p.status}
        // 미룸은 승인과 무관해 여기서도 추가할 수 있지만, 미뤄 둔 것임을 보여 준다(FR-28)
        badges={p.status === 'pending' && snoozeActive(p.snoozed_until) ? [{ text: `미룸 ${snoozeLabel(p.snoozed_until) ?? ''}`.trim(), tone: 'info' }] : []}
        editing={{
          checked: isChecked(p),
          onChecked: (v) => setChecked((c) => ({ ...c, [p.id]: v })),
          followNames,
          onTitle: (title) => edit(p, { title }),
          // 담당을 고르면 '말한 사람 기준'은 더 맞지 않아 지운다(DEC-76)
          onOwner: (kind, who) => edit(p, { owner_kind: kind, assignee: who, owner_basis: null }),
          onOwnerOther: () => setEditing({ p, mode: 'owner' }),
          onDue: () => setEditing({ p, mode: 'due' }),
          onRecipients: () => setRecipientsFor(p),
        }}
      />
    )
  }

  return (
    <>
      <dl className="divide-y divide-border/60">
        <Field label="제목">{(typeof fields?.title === 'string' && fields.title.trim()) || d.info.title}</Field>
        <Field label="일시">{fullTimeText(d.info.occurred_at ?? d.info.ingested_at) ?? '모름'}</Field>
        <Field label="참석자">
          <span className={d.info.attendees.length === 0 ? 'text-muted' : undefined}>{d.info.attendees.length > 0 ? d.info.attendees.join(', ') : '아직 없어요'}</span>
          <button type="button" onClick={onEditAttendees} className="ml-3 text-[13px] font-semibold text-link">
            {d.info.attendees.length > 0 ? '고치기' : '넣기'}
          </button>
        </Field>
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
                      {d.openTaskTitles.has(dec.text.trim()) ? (
                        <span className={cn(CHIP, TONE_CHIP.good)}>할 일 있음</span>
                      ) : (
                        <button type="button" onClick={() => setCreating(dec.text)} className="text-[13px] font-semibold text-link">
                          할 일로 만들기
                        </button>
                      )}
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
        <p className="mt-4 text-sm text-muted">{d.info.job?.stage_state === 'FAILED' ? '처리하지 못해 회의록·할 일 후보가 없어요.' : '정리가 끝나면 회의록과 할 일 후보가 여기에 나와요.'}</p>
      ) : (
        <>
          <h3 className="mt-5 mb-1 text-[15px] font-bold">할 일 후보 {loose.length}</h3>
          {nothing && <p className="text-sm text-muted">이 기록에서는 할 일을 찾지 못했어요.</p>}
          {pendingNew.length > 0 && (
            <p className="mb-2 text-[13px] text-muted">
              체크한 것만 할 일로 추가돼요. 제목은 그 자리에서 고치고, 담당·기한은 골라요.
              {loose.length < pendingNew.length ? ' 결정과 겹치는 후보는 결정 밑에 접어 뒀어요.' : ''}
            </p>
          )}
          {loose.length > 0 && <div className="rounded-lg border border-border">{loose.map(candidate)}</div>}
          {pendingNew.length > 0 && (
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <label className="flex items-center gap-2 text-[13px] font-semibold text-muted">
                추가할 곳
                <PlaceSelect
                  value={placeKeyOf(place)}
                  places={act.places}
                  onChange={(k) => setChosenPlace({ place: placeOfKey(act.places, k) })}
                  label="추가할 곳"
                  className="h-[38px] w-[240px] rounded-md border border-border-strong bg-card px-2 text-sm font-semibold text-ink"
                />
              </label>
              <div className="flex-1" />
              <span className="text-[13px] text-muted">
                {pendingNew.length}건 중 {selected.length}건 선택
              </span>
              <button type="button" disabled={busy || selected.length === 0} onClick={() => (selected.length > 1 ? setConfirmDiscard(true) : void discard(selected))} className={SECONDARY_BUTTON}>
                버리기
              </button>
              <button type="button" disabled={busy || selected.length === 0} onClick={() => void addSelected()} className={PRIMARY_BUTTON}>
                {busy ? '처리하는 중…' : `선택한 할 일 추가 (${selected.length})`}
              </button>
            </div>
          )}
        </>
      )}

      {updates.length > 0 && (
        <>
          <h3 className="mt-6 mb-1 text-[15px] font-bold">기존 할 일 변경 {updates.length}</h3>
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

      {editing && (
        <CandidateEditDialog
          current={current(editing.p)}
          mode={editing.mode}
          onDismiss={() => setEditing(null)}
          onApply={(e) => {
            edit(editing.p, e)
            setEditing(null)
          }}
        />
      )}
      {recipientsFor && (
        <RecipientsDialog
          recordId={d.info.id}
          value={recipientsOf(current(recipientsFor))}
          followNames={followNames}
          onDismiss={() => setRecipientsFor(null)}
          onApply={(names) => {
            edit(recipientsFor, { recipients: names })
            setRecipientsFor(null)
          }}
        />
      )}
      {creating !== null && (
        <CandidateEditDialog
          current={{ title: creating.slice(0, 200), owner_kind: 'me' }}
          mode="create"
          places={act.places}
          place={place}
          onDismiss={() => setCreating(null)}
          onApply={async (e, chosen) => {
            setCreating(null)
            try {
              // 사용자가 직접 만드는 할 일이라 AI 후보 승인을 거치지 않는다(DEC-48). 오늘 탭엔 오늘 것만 보여서 들어간 곳을 알린다
              await createTask(newTaskFields(decisionTaskEdit(e, chosen)))
              act.toast(`할 일로 만들었어요 · ‘${chosen?.name ?? UNSORTED}’에 넣었어요`)
            } catch {
              act.toast(`만들지 못했어요. ${NET_FAILED}`)
            }
            await act.refresh()
          }}
        />
      )}
      {confirmDiscard && (
        <ConfirmDialog
          title={`후보 ${selected.length}개 버리기`}
          text={`체크한 할 일 후보 ${selected.length}개를 버려요. 버린 후보는 검토함에서 사라져요.`}
          confirmLabel="버리기"
          danger
          onDismiss={() => setConfirmDiscard(false)}
          onConfirm={() => {
            setConfirmDiscard(false)
            void discard(selected)
          }}
        />
      )}
      {leftover && (
        <ConfirmDialog
          title={`선택하지 않은 후보 ${leftover.length}개`}
          text="할 일로 추가하지 않은 후보예요. 버릴까요? 버린 후보는 검토함 ‘처리됨’에서 볼 수 있어요."
          confirmLabel="버리기"
          cancelLabel="그대로 두기"
          danger
          onDismiss={() => setLeftover(null)}
          onConfirm={() => {
            const rows = leftover
            setLeftover(null)
            void discard(rows)
          }}
        />
      )}
    </>
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

const CLASSES: { value: 'work' | 'personal' | 'uncertain'; label: string }[] = [
  { value: 'work', label: '업무' },
  { value: 'personal', label: '개인' },
  { value: 'uncertain', label: '불확실' },
]
const LINK_BUTTON = 'h-8 rounded-full border border-border-strong bg-card px-3 text-[13px] font-semibold text-body hover:bg-surface disabled:opacity-50'

/**
 * 관련 프로젝트 탭(앱 RecordProjectsSheet): 분류 고치기(바로 저장) · 연결된 프로젝트(연결 풀기) · AI 연결 제안([연결]·[아니요]) · 다른 프로젝트 연결.
 * 새로 연결하면 그 회의로 프로젝트 현재 상태 제안을 만든다(FR-09, 앱 StateWorker와 같은 호출 — 조용히, 검토함에 쌓임)
 */
function ProjectsTab({ d, act, linked, suggested }: { d: RecordDetail; act: Act; linked: ProjectName[]; suggested: { s: RecordDetail['suggestions'][number]; p: ProjectName }[] }) {
  const [busy, setBusy] = useState(false)
  const [pick, setPick] = useState('')
  const cls = d.info.classification
  const others = d.projects.filter((p) => !p.archived_at && !d.linkedProjectIds.includes(p.id)).sort((a, b) => a.name.localeCompare(b.name, 'ko'))

  const classify = async (value: 'work' | 'personal' | 'uncertain') => {
    if (value === cls) return
    setBusy(true)
    try {
      await setClassification(d.info.id, value)
      act.toast(`분류를 바꿨어요 · ${CLASS_LABEL[value]}`)
      await act.refresh()
    } catch {
      act.toast(`바꾸지 못했어요. ${NET_FAILED}`)
    }
    setBusy(false)
  }

  // 연결·해제·AI 제안 확인. 제안 [아니요]도 같은 해제라, 연결돼 있지 않던 곳이면 '제안을 넘겼다'고 알린다
  const setLinked = async (p: ProjectName, on: boolean) => {
    const wasLinked = d.linkedProjectIds.includes(p.id)
    setBusy(true)
    try {
      const result = await linkRecord(d.info.id, p.id, on)
      if (result === 'linked') {
        void requestProjectState(d.info.id, p.id).catch((e) => console.warn('project_state request failed', e))
      }
      act.toast(on ? `‘${p.name}’에 연결했어요` : wasLinked ? `‘${p.name}’ 연결을 풀었어요` : `‘${p.name}’ 연결 제안을 넘겼어요 · 다시 제안하지 않아요`)
      setPick('')
      await act.refresh()
    } catch (e) {
      act.toast(String((e as Error)?.message ?? '').includes('WB409') ? '아카이브된 프로젝트에는 연결할 수 없어요' : `바꾸지 못했어요. ${NET_FAILED}`)
    }
    setBusy(false)
  }

  return (
    <section className={cn(CARD, 'px-6 py-5')}>
      <dl className="divide-y divide-border/60">
        <Field label="분류">
          <Seg label="분류" items={CLASSES} value={(cls as 'work' | 'personal' | 'uncertain') ?? ('' as 'work')} onChange={(v) => void classify(v)} />
          <p className="mt-1.5 text-xs text-muted">개인으로 두면 이 기록에는 새 연결 제안을 만들지 않아요.</p>
        </Field>
        <Field label="연결된 프로젝트">
          {linked.length === 0 ? (
            <span className="text-muted">아직 없어요</span>
          ) : (
            <ul className="space-y-2">
              {linked.map((p) => (
                <li key={p.id} className="flex flex-wrap items-center gap-3">
                  <Link to={`/projects/p:${p.id}`} className={cn('inline-flex items-center gap-1.5 font-semibold', p.archived_at && 'text-muted')}>
                    <span className={p.archived_at ? 'text-muted' : 'text-tree-folder'}>
                      <Icons.folderBig />
                    </span>
                    {p.name}
                    {p.archived_at ? ' · 보관됨' : ''}
                  </Link>
                  <button type="button" disabled={busy} onClick={() => void setLinked(p, false)} className={LINK_BUTTON}>
                    연결 풀기
                  </button>
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
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="flex-1 font-semibold">{p.name}</span>
                      <button type="button" disabled={busy} onClick={() => void setLinked(p, true)} className={cn(LINK_BUTTON, 'border-primary text-primary')}>
                        연결
                      </button>
                      <button type="button" disabled={busy} onClick={() => void setLinked(p, false)} className={LINK_BUTTON}>
                        아니요
                      </button>
                    </div>
                    {s.reason && <p className="mt-0.5 text-sm text-body">{s.reason}</p>}
                    {s.quote && <p className="mt-1 text-[13px] text-muted">“{s.quote}”</p>}
                    {note && <p className="mt-1 text-xs font-semibold text-warn">{note}</p>}
                  </li>
                )
              })}
            </ul>
          </Field>
        )}
        <Field label="다른 프로젝트">
          <div className="flex flex-wrap items-center gap-2">
            <select value={pick} onChange={(e) => setPick(e.target.value)} aria-label="연결할 프로젝트" className="h-[38px] min-w-[240px] rounded-md border border-border-strong bg-card px-2 text-sm text-ink">
              <option value="">연결할 프로젝트 고르기</option>
              {others.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            <button
              type="button"
              disabled={busy || !pick}
              onClick={() => {
                const p = others.find((x) => x.id === pick)
                if (p) void setLinked(p, true)
              }}
              className={LINK_BUTTON}
            >
              연결
            </button>
          </div>
          <p className="mt-1.5 text-xs text-muted">연결하면 그 프로젝트의 현재 상태 제안을 만들어 검토함에 올려요(회의 하나에 약 1~3원).</p>
        </Field>
      </dl>
    </section>
  )
}

/** 기록 제목 고치기(1~200자) */
function TitleDialog({ title, onDismiss, onSave }: { title: string; onDismiss: () => void; onSave: (t: string) => Promise<void> }) {
  const [value, setValue] = useState(title)
  const [saving, setSaving] = useState(false)
  const t = value.trim()
  const save = async () => {
    if (!t || t === title || saving) return
    setSaving(true)
    await onSave(t)
    setSaving(false)
  }
  return (
    <Dialog
      title="제목 고치기"
      onDismiss={onDismiss}
      actions={
        <>
          <button type="button" onClick={onDismiss} className={SECONDARY_BUTTON}>
            취소
          </button>
          <button type="button" disabled={!t || t === title || saving} onClick={() => void save()} className={PRIMARY_BUTTON}>
            {saving ? '저장 중…' : '저장'}
          </button>
        </>
      }
    >
      <input
        value={value}
        maxLength={200}
        autoFocus
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && !e.nativeEvent.isComposing && void save()}
        className={INPUT}
      />
      <p className="mt-2 text-xs text-muted">회의록·검토함도 이 제목을 따라가요.</p>
    </Dialog>
  )
}

/** 참석자 명단 고치기(DEC-59): 이름을 빼고 더한다. 30명까지(서버 제약), 고친 명단은 다음 회의록 만들기부터 */
function AttendeesDialog({ names, onDismiss, onSave }: { names: string[]; onDismiss: () => void; onSave: (n: string[]) => Promise<void> }) {
  const [list, setList] = useState(names)
  const [typed, setTyped] = useState('')
  const [saving, setSaving] = useState(false)
  const add = () => {
    // 줄바꿈·쉼표는 서버 제약·표시와 맞지 않아 이름 하나로 받는다
    const n = typed.replace(/[\r\n,]/g, ' ').trim().slice(0, 40)
    if (n && !list.includes(n) && list.length < 30) setList([...list, n])
    setTyped('')
  }
  return (
    <Dialog
      title="참석자"
      onDismiss={onDismiss}
      actions={
        <>
          <button type="button" onClick={onDismiss} className={SECONDARY_BUTTON}>
            취소
          </button>
          <button
            type="button"
            disabled={saving}
            onClick={async () => {
              setSaving(true)
              await onSave(list)
              setSaving(false)
            }}
            className={PRIMARY_BUTTON}
          >
            {saving ? '저장 중…' : '저장'}
          </button>
        </>
      }
    >
      <div className="mb-3 flex flex-wrap gap-1.5">
        {list.length === 0 && <span className="text-sm text-muted">아직 없어요</span>}
        {list.map((n) => (
          <span key={n} className="inline-flex items-center gap-1 rounded-full border border-border bg-surface py-1 pr-1.5 pl-3 text-[13px] font-semibold text-body">
            {n}
            <button type="button" aria-label={`${n} 빼기`} onClick={() => setList(list.filter((x) => x !== n))} className="grid size-5 place-items-center rounded-full text-muted hover:bg-border hover:text-ink">
              ×
            </button>
          </span>
        ))}
      </div>
      <div className="flex gap-2">
        <input
          value={typed}
          autoFocus
          onChange={(e) => setTyped(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && !e.nativeEvent.isComposing && add()}
          placeholder="이름 적고 Enter (예: 김하늘)"
          className={INPUT}
        />
        <button type="button" disabled={!typed.trim() || list.length >= 30} onClick={add} className="shrink-0 px-2 text-sm font-semibold text-link disabled:text-muted">
          추가
        </button>
      </div>
      <p className="mt-2 text-xs text-muted">회의록 문서는 ‘다시 만들기’를 해야 새 명단이 반영돼요. 이미 뽑은 할 일 후보는 그대로예요.</p>
    </Dialog>
  )
}
