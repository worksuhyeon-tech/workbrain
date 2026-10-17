package com.workbrain.app

import android.app.Application
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient

class WorkBrainApp : Application()

/**
 * 앱 전체가 함께 쓰는 Supabase 연결.
 * 공개 키만 쓴다(서버 키는 앱에 없음). 데이터 접근은 서버의 RLS가 로그인한 본인 것으로 제한한다(TC-01).
 */
object Supa {
    val client: SupabaseClient by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            install(Auth) {
                // 브라우저에서 Google 로그인 후 workbrain://login-callback 으로 돌아온다(PKCE)
                flowType = FlowType.PKCE
                scheme = "workbrain"
                host = "login-callback"
            }
        }
    }
}
