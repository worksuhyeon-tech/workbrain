import { useState } from 'react'
import { cn } from '../lib/cn'
import { personKey, uniquePeople } from '../rules/people'

const INPUT = 'h-[38px] w-full rounded-md border border-border-strong bg-card px-3 text-sm text-ink'

/**
 * 받는 사람 고르기 칸(DEC-77, 앱 RecipientsField): 이름 칩을 눌러 넣고 빼며, 목록에 없으면 적어서 더한다. 같은 사람은 한 칩.
 * 할 일 수정 서랍과 후보 받는 사람 창이 같이 쓴다
 */
export function RecipientsField({ value, choices, onChange }: { value: string[]; choices: string[]; onChange: (names: string[]) => void }) {
  const [typed, setTyped] = useState('')
  // 같은 사람(홍가상·홍가상 실장)은 한 칩: 이미 고른 사람은 고른 표기로 보여 준다. 누를 때 칩 순서가 바뀌지 않게
  // 고를 이름 순서를 먼저, 목록에 없던 고른 이름을 뒤에 둔다
  const picked = new Set(value.map(personKey))
  const base = uniquePeople(choices)
  const baseKeys = new Set(base.map(personKey))
  const chips = [...base.map((n) => value.find((v) => personKey(v) === personKey(n)) ?? n), ...value.filter((v) => !baseKeys.has(personKey(v)))]
  const toggle = (n: string) => onChange(value.includes(n) ? value.filter((x) => x !== n) : [...value, n])
  const add = () => {
    const n = typed.trim()
    if (!n) return
    if (!picked.has(personKey(n))) onChange([...value, n])
    setTyped('')
  }
  return (
    <>
      <div className="flex flex-wrap gap-1.5">
        {chips.map((n) => (
          <button
            key={n}
            type="button"
            aria-pressed={value.includes(n)}
            onClick={() => toggle(n)}
            className={cn('rounded-full border px-3 py-1 text-[13px] font-semibold', value.includes(n) ? 'border-pill bg-pill text-on-pill' : 'border-border bg-surface text-body')}
          >
            {n}
          </button>
        ))}
      </div>
      <div className="flex gap-2">
        <input
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.nativeEvent.isComposing) add()
          }}
          placeholder="목록에 없으면 이름 적기 (예: IT 개발팀)"
          className={INPUT}
        />
        <button type="button" disabled={!typed.trim()} onClick={add} className="shrink-0 px-2 text-sm font-semibold text-link disabled:text-muted">
          추가
        </button>
      </div>
    </>
  )
}
