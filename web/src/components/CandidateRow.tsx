import { useState } from 'react'
import { cn } from '../lib/cn'
import { candidateDueLabel, ownerLabel, recipientsOf, type OwnerKind, type Tone } from '../rules/records'
import { Icons } from './icons'
import { TONE_CHIP } from './ui'

const CHIP = 'rounded-full px-2.5 py-[3px] text-xs font-bold whitespace-nowrap'
const OTHER = '__other'

const STATUS_CHIP: Record<string, { text: string; tone: Tone } | undefined> = {
  approved: { text: '추가됨', tone: 'good' },
  rejected: { text: '버림', tone: 'mute' },
}

/** 고칠 수 있는 줄(대기 중 후보)에 붙이는 것. 없으면 보기만 */
export interface CandidateEditing {
  checked: boolean
  onChecked: (v: boolean) => void
  /** 담당 고르기에 바로 보일 사람(기록 참석자 등, 내 이름 제외) */
  followNames: string[]
  onTitle: (title: string) => void
  onOwner: (kind: OwnerKind, assignee: string | null) => void
  /** 목록에 없는 사람: 담당 고치기 창 */
  onOwnerOther: () => void
  onDue: () => void
  /** '→ 받는 사람' 칩(DEC-77): 고른 값은 승인 때 덮는다 */
  onRecipients: () => void
}

/**
 * 기록 상세의 할 일 후보 한 줄(목업 S05 할 일 후보 표, 앱 CandidateRow): 체크 · 제목(그 자리에서 고침) · 담당 ▾ · 기한 · 근거 한 줄.
 * editing이 없거나 이미 처리된 후보(추가됨·버림)면 글자와 칩만 보인다.
 * 고친 값은 승인 전까지 화면에만 있고, 승인 때 AI 값 위에 덮는다(approve_proposal p_edits, FR-10)
 */
export function CandidateRow({
  after,
  rationale,
  status,
  badges = [],
  editing,
}: {
  after: Record<string, unknown>
  rationale: string
  status: string
  badges?: { text: string; tone: Tone }[]
  editing?: CandidateEditing
}) {
  const title = typeof after.title === 'string' ? after.title : ''
  const due = candidateDueLabel(after)
  const ownerKind = after.owner_kind
  const assignee = typeof after.assignee === 'string' ? after.assignee : null
  const ownerTone: Tone = ownerKind === 'me' ? 'info' : ownerKind === 'other' ? 'mute' : 'warn'
  const recipients = recipientsOf(after)
  const statusChip = STATUS_CHIP[status] ?? (status === 'pending' ? undefined : { text: '확인 필요', tone: 'crit' as const })
  const edit = status === 'pending' ? editing : undefined
  const extras = (
    <>
      {after.owner_basis === 'speaker' && <span className={cn(CHIP, TONE_CHIP.mute)}>말한 사람 기준</span>}
      {recipients.length > 0 &&
        (edit ? (
          <button type="button" onClick={edit.onRecipients} title="받는 사람 고치기" className={cn(CHIP, TONE_CHIP.mute, 'hover:text-ink')}>
            → {recipients.join(', ')}
          </button>
        ) : (
          <span className={cn(CHIP, TONE_CHIP.mute)}>→ {recipients.join(', ')}</span>
        ))}
      {statusChip && <span className={cn(CHIP, TONE_CHIP[statusChip.tone])}>{statusChip.text}</span>}
      {badges.map((b) => (
        <span key={b.text} className={cn(CHIP, TONE_CHIP[b.tone])}>
          {b.text}
        </span>
      ))}
    </>
  )

  if (!edit) {
    return (
      <div className="border-b border-border px-4 py-3 last:border-b-0">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
          <span className={cn('min-w-0 flex-1 basis-[280px] text-[15px]', status !== 'pending' ? 'text-muted' : 'text-ink')}>{title}</span>
          <span className={cn(CHIP, TONE_CHIP[ownerTone])}>{ownerLabel(ownerKind, assignee)}</span>
          {due && <span className={cn(CHIP, TONE_CHIP[after.due_ambiguous === true ? 'warn' : 'mute'])}>{due}</span>}
          {extras}
        </div>
        {rationale.trim() && <Rationale text={rationale} />}
      </div>
    )
  }

  // 담당 ▾ 값: 나 · 확인 필요 · 팔로업(이름) · 다른 사람…(창). 지금 담당이 목록에 없으면 그 이름도 보이게 더한다
  const names = [...new Set([...edit.followNames, ...(ownerKind === 'other' && assignee ? [assignee] : [])])]
  const ownerValue = ownerKind === 'me' ? 'me' : ownerKind === 'other' && assignee ? `other:${assignee}` : 'unknown'
  return (
    <div className={cn('border-b border-border px-4 py-2.5 last:border-b-0', edit.checked && 'bg-info-soft/40')}>
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="checkbox"
          checked={edit.checked}
          onChange={(e) => edit.onChecked(e.target.checked)}
          aria-label={`${title || '제목 없는 후보'} 고르기`}
          className="size-[18px] flex-none accent-primary"
        />
        <input
          value={title}
          maxLength={200}
          onChange={(e) => edit.onTitle(e.target.value)}
          aria-label="할 일 제목"
          className={cn('h-[38px] min-w-0 flex-1 basis-[240px] rounded-md border bg-card px-3 text-[15px] text-ink', title.trim() ? 'border-border-strong' : 'border-crit')}
        />
        <select
          value={ownerValue}
          onChange={(e) => {
            const v = e.target.value
            if (v === OTHER) edit.onOwnerOther()
            else if (v.startsWith('other:')) edit.onOwner('other', v.slice(6))
            else edit.onOwner(v as OwnerKind, null)
          }}
          aria-label="담당"
          className="h-[38px] w-[170px] rounded-md border border-border-strong bg-card px-2 text-sm text-ink"
        >
          <option value="me">나</option>
          <option value="unknown">확인 필요</option>
          {names.map((n) => (
            <option key={n} value={`other:${n}`}>
              팔로업 · {n}
            </option>
          ))}
          <option value={OTHER}>다른 사람…</option>
        </select>
        <button
          type="button"
          onClick={edit.onDue}
          title="기한 고르기"
          className={cn(
            'h-[38px] w-[150px] truncate rounded-md border px-3 text-left text-sm',
            after.due_ambiguous === true && !due?.match(/\d/) ? 'border-warn/50 bg-warn-soft text-warn' : due ? 'border-border-strong bg-card text-ink' : 'border-border-strong bg-card text-muted',
          )}
        >
          {due ?? '+ 기한'}
        </button>
      </div>
      <div className="mt-1 flex flex-wrap items-center gap-1.5 pl-[26px]">
        {extras}
        {rationale.trim() && <Rationale text={rationale} />}
      </div>
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
      className="mt-1 flex w-full min-w-0 items-start gap-1 text-left text-[13px] text-muted"
    >
      <span className={cn('min-w-0 flex-1', !open && 'truncate')}>“{text}”</span>
      {!open && <Icons.chevron open={false} />}
    </button>
  )
}
