import { createClient } from '@supabase/supabase-js'

// 앱(android/gradle.properties)과 같은 프로젝트·공개 키. 공개 키는 브라우저에 실려도 되는 값이고,
// 데이터는 서버 RLS가 본인 것만 열어 준다. 관리자 키는 웹에 두지 않는다
export const SUPABASE_URL = 'https://xupzzlpmysnljxlmygjy.supabase.co'
export const SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_1EH7MYbMTbzSVv24uTd0DQ_MQNcsV8R'

export const supabase = createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, {
  auth: {
    // 앱과 같은 PKCE: Google 로그인 뒤 ?code=로 돌아오면 라이브러리가 세션으로 바꾼다
    flowType: 'pkce',
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: true,
  },
})
