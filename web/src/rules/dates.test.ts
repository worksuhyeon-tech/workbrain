import { describe, expect, it } from 'vitest'
import { monthGrid } from './dates'

describe('달력 칸', () => {
  it('월요일부터 6주 42칸, 앞뒤 달로 채운다', () => {
    const g = monthGrid(2026, 11)
    expect(g).toHaveLength(42)
    // 2026-11-01은 일요일 → 그 주 월요일 10/26부터
    expect(g[0]).toBe('2026-10-26')
    expect(g[6]).toBe('2026-11-01')
    expect(g[41]).toBe('2026-12-06')
  })

  it('1일이 월요일이면 그날부터', () => {
    expect(monthGrid(2026, 6)[0]).toBe('2026-06-01')
  })
})
