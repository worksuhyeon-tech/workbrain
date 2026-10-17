package com.workbrain.app

import android.app.Application
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlin.time.Duration.Companion.seconds

class WorkBrainApp : Application()

/**
 * 앱 전체가 함께 쓰는 Supabase 연결.
 * 공개 키만 쓴다(서버 키는 앱에 없음). 데이터 접근은 서버의 RLS가 로그인한 본인 것으로 제한한다(TC-01).
 */
object Supa {
    val client: SupabaseClient by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            // 긴 회의의 전사는 처리 함수 한 번에 1분 넘게 걸릴 수 있다(함수 제한 150초에 맞춤)
            requestTimeout = 150.seconds
            install(Auth) {
                // 브라우저에서 Google 로그인 후 workbrain://login-callback 으로 돌아온다(PKCE)
                flowType = FlowType.PKCE
                scheme = "workbrain"
                host = "login-callback"
            }
            install(Postgrest)
            install(Storage)
            install(Functions)
        }
    }
}
