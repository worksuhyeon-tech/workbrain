import { useState } from 'react'

const INPUT = 'h-[38px] w-full rounded-md border border-border-strong bg-card px-3 text-sm text-ink'
/** 서버 제약: 30명까지, 줄바꿈 없음 */
const MAX = 30

/** 참석자 명단 칸(DEC-39·59): 이름 칩(× 빼기) + 적고 Enter. 기록 상세 참석자 창과 올리기 창이 같이 쓴다 */
export function AttendeesField({ value, onChange, autoFocus = false }: { value: string[]; onChange: (names: string[]) => void; autoFocus?: boolean }) {
  const [typed, setTyped] = useState('')
  const add = () => {
    // 줄바꿈·쉼표는 서버 제약·표시와 맞지 않아 이름 하나로 받는다
    const n = typed.replace(/[\r\n,]/g, ' ').trim().slice(0, 40)
    if (n && !value.includes(n) && value.length < MAX) onChange([...value, n])
    setTyped('')
  }
  return (
    <>
      <div className="mb-2 flex flex-wrap gap-1.5">
        {value.length === 0 && <span className="text-sm text-muted">아직 없어요</span>}
        {value.map((n) => (
          <span key={n} className="inline-flex items-center gap-1 rounded-full border border-border bg-surface py-1 pr-1.5 pl-3 text-[13px] font-semibold text-body">
            {n}
            <button type="button" aria-label={`${n} 빼기`} onClick={() => onChange(value.filter((x) => x !== n))} className="grid size-5 place-items-center rounded-full text-muted hover:bg-border hover:text-ink">
              ×
            </button>
          </span>
        ))}
      </div>
      <div className="flex gap-2">
        <input
          value={typed}
          autoFocus={autoFocus}
          onChange={(e) => setTyped(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
              e.preventDefault()
              add()
            }
          }}
          placeholder="이름 적고 Enter (예: 홍길동)"
          className={INPUT}
        />
        <button type="button" disabled={!typed.trim() || value.length >= MAX} onClick={add} className="shrink-0 px-2 text-sm font-semibold text-link disabled:text-muted">
          추가
        </button>
      </div>
    </>
  )
}
