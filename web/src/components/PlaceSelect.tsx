import type { Place } from '../rules/place'
import { UNSORTED } from '../rules/tasks'

/** 곳 고르기 값 'PROJECT:id' · 'AREA:id' · ''(정리 안 됨) */
export const placeKeyOf = (p: Place | null) => (p ? `${p.type}:${p.id}` : '')
export const placeOfKey = (places: Place[], key: string) => places.find((p) => placeKeyOf(p) === key) ?? null

/**
 * 어디에 고르기(정리 안 됨 · 1. 프로젝트 · 2. 반복실행). 할 일 수정 서랍·결정 → 할 일·'추가할 곳'이 같이 쓴다.
 * missing: 지금 곳이 보관돼 목록에 없을 때 그 이름으로 한 줄(그대로 두면 곳은 안 바뀐다)
 */
export function PlaceSelect({
  value,
  places,
  onChange,
  className,
  label,
  missing,
}: {
  value: string
  places: Place[]
  onChange: (key: string) => void
  className: string
  label?: string
  missing?: string
}) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} aria-label={label} className={className}>
      <option value="">{UNSORTED}</option>
      {missing && <option value={value}>{missing}</option>}
      <optgroup label="1. 프로젝트">
        {places
          .filter((p) => p.type === 'PROJECT')
          .map((p) => (
            <option key={p.id} value={placeKeyOf(p)}>
              {p.name}
            </option>
          ))}
      </optgroup>
      <optgroup label="2. 반복실행">
        {places
          .filter((p) => p.type === 'AREA')
          .map((p) => (
            <option key={p.id} value={placeKeyOf(p)}>
              {p.name}
            </option>
          ))}
      </optgroup>
    </select>
  )
}
