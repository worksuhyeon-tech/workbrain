import { describe, expect, it } from 'vitest'
import { allSelected, EMPTY_SELECTION, pickedIds, startSelect, stopSelect, toggleAllSelect, toggleSelect, withVisible } from './selection'

/** 앱 TaskSelectTest '선택' 예시 그대로 + 웹에서 더한 보이는 것만 세기 */
describe('선택(TaskSelectTest)', () => {
  it('선택 - 하나씩 고르고 전체 선택은 보이는 것만, 끝내면 비운다', () => {
    let s = startSelect(EMPTY_SELECTION)
    s = withVisible(s, ['a', 'b', 'c'])
    s = toggleSelect(s, 'b')
    expect([...s.ids]).toEqual(['b'])
    expect(allSelected(s)).toBe(false)
    s = toggleAllSelect(s)
    expect([...s.ids].sort()).toEqual(['a', 'b', 'c'])
    expect(allSelected(s)).toBe(true)
    s = toggleAllSelect(s)
    expect(s.ids.size).toBe(0)
    s = toggleSelect(s, 'a')
    s = stopSelect(s)
    expect(s.active).toBe(false)
    expect(s.ids.size).toBe(0)
  })

  it('고른 것 중 안 보이게 된 것(지우는 중·다른 기기에서 지움)은 세지 않는다', () => {
    let s = withVisible(startSelect(EMPTY_SELECTION), ['a', 'b', 'c'])
    s = toggleSelect(toggleSelect(s, 'a'), 'c')
    s = withVisible(s, ['a', 'b'])
    expect(pickedIds(s)).toEqual(['a'])
  })

  it('보이는 것이 그대로면 같은 상태를 돌려준다(다시 그리기 멈춤)', () => {
    const s = withVisible(EMPTY_SELECTION, ['a', 'b'])
    expect(withVisible(s, ['a', 'b'])).toBe(s)
    expect(withVisible(s, ['b', 'a'])).not.toBe(s)
  })

  it('보이는 할 일이 없으면 전체 선택이 아니다', () => {
    expect(allSelected(startSelect(EMPTY_SELECTION))).toBe(false)
  })
})
