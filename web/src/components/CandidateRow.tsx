import { useState } from 'react'
import { cn } from '../lib/cn'
import { candidateDueLabel, ownerLabel, recipientsOf, type Tone } from '../rules/records'
import { TONE_CHIP } from './ui'
import { Icons } from './icons'

const CHIP = 'rounded-full px-2.5 py-[3px] text-xs font-bold whitespace-nowrap'

const STATUS_CHIP: Record<string, { text: string; tone: Tone } | undefined> = {
  approved: { text: '추가됨', tone: 'good' },
  rejected: { text: '버림', tone: 'mute' },
}

/**
 * 기록 상세의 할 일 후보 한 줄(목업 S05 할 일 후보 표, 앱 CandidateRow): 제목 · 담당 · 기한 · 근거 한 줄.
 * 지금은 보기만(②-2). 체크·고치기·추가는 다음 단계(②-3)에서 같은 줄에 붙인다.
 * badges: 미룸 등 덧붙일 칩
 */
export function CandidateRow({
  after,
  rationale,
  status,
  badges = [],
}: {
  after: Record<string, unknown>
  rationale: string
  status: string
  badges?: { text: string; tone: Tone }[]
}) {
  const title = typeof after.title === 'string' ? after.title : ''
  const due = candidateDueLabel(after)
  const ownerKind = after.owner_kind
  const ownerTone: Tone = ownerKind === 'me' ? 'info' : ownerKind === 'other' ? 'mute' : 'warn'
  const recipients = recipientsOf(after)
  const statusChip = STATUS_CHIP[status] ?? (status === 'pending' ? undefined : { text: '확인 필요', tone: 'crit' as const })
  const done = status !== 'pending'

  return (
    <div className="border-b border-border px-4 py-3 last:border-b-0">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <span className={cn('min-w-0 flex-1 basis-[280px] text-[15px]', done ? 'text-muted' : 'text-ink')}>{title}</span>
        <span className={cn(CHIP, TONE_CHIP[ownerTone])}>{ownerLabel(ownerKind, after.assignee)}</span>
        {after.owner_basis === 'speaker' && <span className={cn(CHIP, TONE_CHIP.mute)}>말한 사람 기준</span>}
        {recipients.length > 0 && <span className={cn(CHIP, TONE_CHIP.mute)}>→ {recipients.join(', ')}</span>}
        {due && <span className={cn(CHIP, TONE_CHIP[after.due_ambiguous === true ? 'warn' : 'mute'])}>{due}</span>}
        {statusChip && <span className={cn(CHIP, TONE_CHIP[statusChip.tone])}>{statusChip.text}</span>}
        {badges.map((b) => (
          <span key={b.text} className={cn(CHIP, TONE_CHIP[b.tone])}>
            {b.text}
          </span>
        ))}
      </div>
      {rationale.trim() && <Rationale text={rationale} />}
    </div>
  )
}

/** 근거 문장 회색 한 줄, 누르면 전체(앱 RationaleLine) */
function Rationale({ text }: { text: string }) {
  const [open, setOpen] = useState(false)
  return (
    <button
      type="button"
      onClick={() => setOpen((o) => !o)}
      aria-expanded={open}
      className="mt-1 flex w-full items-start gap-1 text-left text-[13px] text-muted"
    >
      <span className={cn('flex-1', !open && 'truncate')}>“{text}”</span>
      {!open && <Icons.chevron open={false} />}
    </button>
  )
}
