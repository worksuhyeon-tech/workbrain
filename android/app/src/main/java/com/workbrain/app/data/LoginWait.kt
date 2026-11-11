package com.workbrain.app.data

import android.util.Log
import com.workbrain.app.BuildConfig
import com.workbrain.app.Supa
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 로그인 준비 기다리기(DEC-96).
 * 로그인 라이브러리(supabase-kt 3.1.4)는 앱이 뒤로 가면 메모리의 로그인 상태를 '준비 중'(Initializing)으로 비우고,
 * 다시 앞으로 오면 저장된 열쇠를 읽어 만료됐으면 새로 받는다. 그 사이에 나간 요청은 사용자 열쇠 없이 공개 키만 달고 나가
 * 서버가 401로 거절했다(11/11 서버 기록: 열쇠 갱신 1초 뒤 오늘 화면 요청 8개가 모두 401 → '불러오지 못했어요').
 * 그래서 우리 서버의 데이터·함수·저장소 요청이 공개 키만 달고 나가려 하면, 로그인 상태가 정해질 때까지(최대 10초) 기다렸다가
 * 사용자 열쇠로 바꿔 보낸다. 로그아웃 상태면 기다리지 않는다(로그인 화면이 맞으므로).
 */
object LoginWait {
    const val TAG = "WorkBrainNet"
    val MAX_WAIT: Duration = 10.seconds

    // 사용자 열쇠가 있어야 하는 길. 로그인 요청(/auth/v1/)은 공개 키로 나가는 게 맞아 빼야 한다(기다리면 갱신이 막힘)
    private val USER_PATHS = listOf("/rest/v1/", "/functions/v1/", "/storage/v1/")

    fun isUserPath(path: String): Boolean = USER_PATHS.any { path.startsWith(it) }

    /** 공개 키만 달고 사용자 데이터 길로 가는 요청인가. 사용자 열쇠를 이미 단 요청은 그대로 보낸다 */
    fun needsUserToken(path: String, authorization: String?, publicKey: String): Boolean =
        authorization == "Bearer $publicKey" && isUserPath(path)

    /**
     * 로그인 상태가 정해질 때까지 기다려 사용자 열쇠를 돌려준다.
     * 준비 중·갱신 실패(라이브러리가 다시 시도함)는 아직 정해지지 않은 것으로 본다. 로그아웃이거나 시간 안에 안 정해지면 null
     */
    suspend fun awaitUserToken(status: StateFlow<SessionStatus>, maxWait: Duration = MAX_WAIT): String? {
        val settled = withTimeoutOrNull(maxWait) {
            status.first { it !is SessionStatus.Initializing && it !is SessionStatus.RefreshFailure }
        }
        return (settled as? SessionStatus.Authenticated)?.session?.accessToken
    }
}

/**
 * Supabase 연결에 끼우는 요청 고리: 열쇠 바꿔 달기 + 실패 개발 기록(logcat WorkBrainNet).
 * 화면은 실패를 모두 '인터넷 연결을 확인해 주세요'로 보여 주므로, 실제 까닭(401·시간 초과·주소 못 찾음 등)은 여기 남긴다.
 * 주소의 검색 조건(사용자 데이터가 들어갈 수 있음)은 남기지 않고 경로·상태 코드·예외 종류만 쓴다
 */
val LoginWaitPlugin = createClientPlugin("LoginWait") {
    onRequest { request, _ ->
        val path = request.url.encodedPath
        if (!LoginWait.needsUserToken(path, request.headers[HttpHeaders.Authorization], BuildConfig.SUPABASE_KEY)) return@onRequest
        val token = LoginWait.awaitUserToken(Supa.client.auth.sessionStatus)
        if (token == null) {
            Log.w(LoginWait.TAG, "no user session after wait, sent with public key: $path")
        } else {
            request.headers[HttpHeaders.Authorization] = "Bearer $token"
            Log.i(LoginWait.TAG, "waited for login: $path")
        }
    }
    onResponse { response ->
        val path = response.call.request.url.encodedPath
        if (response.status.value >= 400 && LoginWait.isUserPath(path)) {
            Log.w(LoginWait.TAG, "${response.status.value} $path")
        }
    }
    on(Send) { request ->
        try {
            proceed(request)
        } catch (e: Exception) {
            // 화면을 떠나 요청이 취소된 것은 실패가 아니다
            if (e !is CancellationException) Log.w(LoginWait.TAG, "${e.javaClass.simpleName} ${request.url.encodedPath}")
            throw e
        }
    }
}
