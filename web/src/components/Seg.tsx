import { cn } from '../lib/cn'

export interface SegItem<T extends string> {
  value: T
  label: string
  /** 탭 옆 숫자(목업 .count) */
  count?: number
  /** 숫자가 있으면 빨강(목업 .late-c — 지연) */
  alert?: boolean
  /** 아직 안 여는 보기(목업 .dis): 흐리게 + 작은 꼬리표(P4). 누르면 onLater */
  later?: string
}

/** 목업 .seg: 둥근 바탕 안에서 고른 것만 진한 알약 */
export function Seg<T extends string>({
  items,
  value,
  onChange,
  onLater,
  label,
}: {
  items: SegItem<T>[]
  value: T
  onChange: (v: T) => void
  onLater?: (v: T) => void
  label: string
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex flex-wrap gap-[3px] rounded-full border border-border bg-surface p-[3px]">
      {items.map((it) => {
        const on = it.value === value
        return (
          <button
            key={it.value}
            type="button"
            role="radio"
            aria-checked={on}
            onClick={() => (it.later ? onLater?.(it.value) : onChange(it.value))}
            className={cn('h-[30px] rounded-full px-3 text-[13px] font-semibold whitespace-nowrap text-muted', on && 'bg-pill text-on-pill', it.later && 'opacity-75')}
          >
            {it.label}
            {it.count !== undefined && (
              <span className={cn('ml-1 tabular-nums', it.alert && it.count > 0 && !on ? 'text-crit' : !on && 'opacity-80')}>{it.count}</span>
            )}
            {it.later && <small className="ml-1 text-[10px] font-bold">{it.later}</small>}
          </button>
        )
      })}
    </div>
  )
}
