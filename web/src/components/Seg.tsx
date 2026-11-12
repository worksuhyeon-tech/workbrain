import { cn } from '../lib/cn'

/** 목업 .seg: 둥근 바탕 안에서 고른 것만 진한 알약 */
export function Seg<T extends string>({ items, value, onChange, label }: { items: { value: T; label: string }[]; value: T; onChange: (v: T) => void; label: string }) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex flex-wrap gap-[3px] rounded-full border border-border bg-surface p-[3px]">
      {items.map((it) => (
        <button
          key={it.value}
          type="button"
          role="radio"
          aria-checked={it.value === value}
          onClick={() => onChange(it.value)}
          className={cn('h-[30px] rounded-full px-3 text-[13px] font-semibold text-muted', it.value === value && 'bg-pill text-on-pill')}
        >
          {it.label}
        </button>
      ))}
    </div>
  )
}
