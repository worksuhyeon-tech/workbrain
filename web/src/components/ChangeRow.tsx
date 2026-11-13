import { cn } from '../lib/cn'
import { changeLines } from '../rules/records'
import type { ProposalRow } from '../data/records'
import { TONE_CHIP } from './ui'

const CHIP = 'rounded-full px-2.5 py-[3px] text-xs font-bold whitespace-nowrap'

/** 기존 할 일 변경 한 줄: 대상 제목 + 바뀐 칸 '전 → 후' + 근거(앱 CandidateCard before) */
export function ChangeRow({ p }: { p: Pick<ProposalRow, 'before_json' | 'after_json' | 'status' | 'rationale'> }) {
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
