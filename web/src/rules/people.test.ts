import { describe, expect, it } from 'vitest'
import { personKey, uniquePeople } from './people'

// 이름은 모두 가짜
describe('사람 이름 맞추기', () => {
  it('띄어쓰기·님·직함을 떼고 같은 사람으로 본다', () => {
    expect(personKey('홍가상실장')).toBe('홍가상')
    expect(personKey('홍가상 실장님')).toBe('홍가상')
    expect(personKey('이예시 본부장')).toBe('이예시')
    expect(personKey('대리')).toBe('대리')
  })

  it('같은 사람은 하나로, 직함 없는 짧은 이름을 남기고 순서는 처음 나온 대로', () => {
    expect(uniquePeople(['홍가상실장', '박예시', '홍가상', ' 박예시 팀장 ', '최샘플'])).toEqual(['홍가상', '박예시', '최샘플'])
  })

  it('내 이름·나는 뺀다', () => {
    expect(uniquePeople(['나', '홍가상', '김본인 과장', '박예시'], ['나', '김본인'])).toEqual(['홍가상', '박예시'])
  })
})
