import { describe, expect, it } from 'vitest'
import { loginFailureOf } from './login'

const BASE = 'https://workbrain.example.test/'
const HOOK_TEXT = encodeURIComponent('이 계정은 WorkBrain을 사용할 수 없어요.')

describe('로그인 실패 알아보기', () => {
  it('보통 주소·로그인 성공 코드는 실패가 아니다', () => {
    expect(loginFailureOf(BASE)).toBeNull()
    expect(loginFailureOf(`${BASE}projects?task=t1`)).toBeNull()
    expect(loginFailureOf(`${BASE}?code=abc`)).toBeNull()
  })

  it('허용 목록 훅의 거절은 notAllowed — ?와 # 어느 쪽으로 와도', () => {
    expect(loginFailureOf(`${BASE}?error=access_denied&error_code=unexpected_failure&error_description=${HOOK_TEXT}`)).toBe('notAllowed')
    expect(loginFailureOf(`${BASE}#error=access_denied&error_description=${HOOK_TEXT}`)).toBe('notAllowed')
  })

  it('Google 창 취소 등 다른 실패는 other — 주소의 글은 쓰지 않는다', () => {
    expect(loginFailureOf(`${BASE}?error=access_denied&error_description=${encodeURIComponent('여기로 전화하세요')}`)).toBe('other')
    expect(loginFailureOf(`${BASE}?error=server_error`)).toBe('other')
    expect(loginFailureOf(`${BASE}#error_code=bad_oauth_state`)).toBe('other')
  })
})
