/** Google 로그인에서 돌아왔는데 실패한 까닭. notAllowed = 서버 허용 목록에 없는 계정 */
export type LoginFailure = 'notAllowed' | 'other'

/**
 * 서버 허용 목록 훅(supabase/migrations/20261018000800 wb_before_user_created)의 거절 문구 일부.
 * 같은 'access_denied'라도 Google 창에서 취소한 경우와 구분하려고 문구로 본다 — 훅 문구를 바꾸면 같이 바꾼다
 */
const NOT_ALLOWED_MARK = 'WorkBrain을 사용할 수 없어요'

export const LOGIN_FAILURE_TEXT: Record<LoginFailure, string> = {
  notAllowed: '이 Google 계정은 쓸 수 없어요. 허용된 계정으로 로그인해 주세요.',
  other: '로그인을 마치지 못했어요. 다시 눌러 주세요.',
}

/**
 * Supabase는 로그인 실패를 돌아온 주소의 ?error=…(흐름에 따라 #error=…)로 알려 준다.
 * 주소 글을 그대로 화면에 띄우지 않고(누가 만든 링크일 수 있음) 정해 둔 두 문구 중 하나로만 바꾼다
 */
export function loginFailureOf(href: string): LoginFailure | null {
  const url = new URL(href)
  const params = new URLSearchParams(url.hash.slice(1))
  url.searchParams.forEach((value, key) => params.set(key, value))
  if (!params.get('error') && !params.get('error_code') && !params.get('error_description')) return null
  return (params.get('error_description') ?? '').includes(NOT_ALLOWED_MARK) ? 'notAllowed' : 'other'
}
