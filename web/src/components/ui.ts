import type { Tone } from '../rules/records'

/** 목업 .card: 흰 바탕·옅은 테두리·작은 그림자(화면 공용) */
export const CARD = 'rounded-xl border border-border bg-card shadow-sm'

/** 상태 칩 색(목업 .chip.good·warn·crit·info, 회색은 테두리만) */
export const TONE_CHIP: Record<Tone, string> = {
  good: 'bg-good-soft text-good',
  warn: 'bg-warn-soft text-warn',
  crit: 'bg-crit-soft text-crit',
  info: 'bg-info-soft text-info',
  mute: 'border border-border bg-surface text-muted',
}
