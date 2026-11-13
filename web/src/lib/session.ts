import { useEffect, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { supabase } from './supabase'

export type AuthState =
  | { kind: 'loading' }
  | { kind: 'signedOut' }
  | { kind: 'signedIn'; session: Session }

/**
 * 로그인 상태. 저장된 세션을 다 읽기 전(loading)에는 화면이 서버를 부르지 않게 해서,
 * 앱에서 겪은 '로그인 열쇠가 정해지기 전 공개 키로 요청 → 401'(DEC-96)을 처음부터 피한다
 */
export function useAuth(): AuthState {
  const [state, setState] = useState<AuthState>({ kind: 'loading' })

  useEffect(() => {
    let alive = true
    supabase.auth.getSession().then(({ data }) => {
      if (alive) setState(data.session ? { kind: 'signedIn', session: data.session } : { kind: 'signedOut' })
    })
    const { data } = supabase.auth.onAuthStateChange((_event, session) => {
      setState(session ? { kind: 'signedIn', session } : { kind: 'signedOut' })
    })
    return () => {
      alive = false
      data.subscription.unsubscribe()
    }
  }, [])

  return state
}

/**
 * Google 로그인(앱과 같은 Supabase Google 공급자). 돌아올 주소는 지금 웹 주소 — Supabase 허용 목록에 있어야 한다.
 * chooseAccount: 허용 안 된 계정으로 막힌 뒤에는 Google이 같은 계정으로 바로 넘기지 않게 계정 고르기 창을 띄운다
 */
export async function signInWithGoogle(chooseAccount = false): Promise<void> {
  const { error } = await supabase.auth.signInWithOAuth({
    provider: 'google',
    options: {
      redirectTo: window.location.origin,
      ...(chooseAccount && { queryParams: { prompt: 'select_account' } }),
    },
  })
  if (error) throw error
}

/** 로그인 실패 표시(?error=…·#error=…)를 주소에서 지운다. 라이브러리는 지우지 않아, 두면 새로고침할 때마다 다시 뜬다 */
export function clearLoginFailureFromUrl(): void {
  window.history.replaceState(window.history.state, '', window.location.pathname)
}

export async function signOut(): Promise<void> {
  await supabase.auth.signOut()
}
