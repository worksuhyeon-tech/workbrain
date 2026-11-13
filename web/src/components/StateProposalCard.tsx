import type { StateProposal } from '../data/review'
import { stateLabel } from '../data/para'
import { cn } from '../lib/cn'
import { mdOf } from '../rules/records'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from './Dialog'
import { TONE_CHIP } from './ui'

const CHIP = 'rounded-full px-2.5 py-[3px] text-xs font-bold whitespace-nowrap'
const stateTone = (s: string) => (s === 'paused' ? TONE_CHIP.warn : s === 'done' ? TONE_CHIP.mute : TONE_CHIP.good)
const text = (o: Record<string, unknown> | null, k: string) => (o && typeof o[k] === 'string' ? (o[k] as string) : null)

/**
 * 프로젝트 현재 상태 제안 상자(FR-09, 앱 StateProposalBox): 프로젝트 이름 · '과거 회의 기준'·'회의 N개' · 최근 반영 회의 ·
 * 지금(회색) → 바뀐 상태 · 상태 칩 전후 · 이유 · 근거 한 문장 · [아니요] [반영]. 상태 전환이라 하나씩 고른다(묶어 승인하지 않음, 목업).
 * onApprove가 없으면 처리됨 탭의 읽기 전용
 */
export function StateProposalCard({
  p,
  title,
  busy,
  onApprove,
  onReject,
}: {
  p: StateProposal
  title: string
  busy: boolean
  onApprove?: () => void
  onReject?: () => void
}) {
  const summary = text(p.after, 'summary') ?? ''
  const status = text(p.after, 'status') ?? 'active'
  const beforeSummary = text(p.before, 'summary')
  const beforeStatus = text(p.before, 'status')
  const from = [text(p.after, 'record_title'), mdOf(text(p.after, 'record_at'))].filter(Boolean).join(' ')
  const records = Array.isArray(p.after.records) ? p.after.records.length : 0
  return (
    <div className="rounded-xl border border-border bg-card px-5 py-4 shadow-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[15px] font-bold text-ink">{title}</span>
        {p.after.past === true && <span className={cn(CHIP, TONE_CHIP.warn)}>과거 회의 기준</span>}
        {records > 1 && <span className={cn(CHIP, TONE_CHIP.info)}>회의 {records}개</span>}
      </div>
      {from && <p className="mt-0.5 text-xs text-muted">최근 반영 회의: {from}</p>}
      {beforeSummary && (
        <div className="mt-3">
          <div className="text-xs font-bold text-muted">지금</div>
          <p className="text-[13px] leading-relaxed whitespace-pre-wrap text-muted">{beforeSummary}</p>
        </div>
      )}
      <div className="mt-3">
        <div className="text-xs font-bold text-ink">{beforeSummary ? '바뀐 상태' : '처음 상태'}</div>
        <p className="text-[15px] leading-relaxed whitespace-pre-wrap text-body">{summary}</p>
      </div>
      <div className="mt-2 flex items-center gap-1.5">
        {beforeStatus && beforeStatus !== status && (
          <>
            <span className={cn(CHIP, TONE_CHIP.mute)}>{stateLabel(beforeStatus)}</span>
            <span className="text-[13px] text-muted">→</span>
          </>
        )}
        <span className={cn(CHIP, stateTone(status))}>{stateLabel(status)}</span>
      </div>
      {p.rationale.trim() && <p className="mt-2 text-[13px] text-body">이유: {p.rationale}</p>}
      {p.quote && <p className="mt-1 text-xs text-muted">“{p.quote}”</p>}
      {onApprove && onReject && (
        <div className="mt-3 flex justify-end gap-2">
          <button type="button" disabled={busy} onClick={onReject} className={SECONDARY_BUTTON}>
            아니요
          </button>
          <button type="button" disabled={busy} onClick={onApprove} className={PRIMARY_BUTTON}>
            반영
          </button>
        </div>
      )}
    </div>
  )
}
