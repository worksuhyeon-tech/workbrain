package com.workbrain.app.data

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** DEC-96 로그인 준비 기다리기: 어떤 요청을 기다리게 하고, 어떤 상태에서 어떤 열쇠를 돌려주는지 */
class LoginWaitTest {
    private val publicKey = "sb_publishable_test"
    private val signedIn = SessionStatus.Authenticated(
        UserSession(accessToken = "user-jwt", refreshToken = "refresh", expiresIn = 3600, tokenType = "bearer"),
    )

    @Test fun `공개 키만 단 데이터·함수·저장소 요청은 기다린다`() {
        assertTrue(LoginWait.needsUserToken("/rest/v1/task", "Bearer $publicKey", publicKey))
        assertTrue(LoginWait.needsUserToken("/functions/v1/process-record", "Bearer $publicKey", publicKey))
        assertTrue(LoginWait.needsUserToken("/storage/v1/object/audio/a.m4a", "Bearer $publicKey", publicKey))
    }

    @Test fun `로그인 요청·사용자 열쇠를 단 요청·열쇠 없는 요청은 그대로 보낸다`() {
        assertFalse(LoginWait.needsUserToken("/auth/v1/token", "Bearer $publicKey", publicKey))
        assertFalse(LoginWait.needsUserToken("/rest/v1/task", "Bearer user-jwt", publicKey))
        assertFalse(LoginWait.needsUserToken("/rest/v1/task", null, publicKey))
    }

    @Test fun `로그인돼 있으면 바로 사용자 열쇠`() = runBlocking {
        assertEquals("user-jwt", LoginWait.awaitUserToken(MutableStateFlow(signedIn), 1.seconds))
    }

    @Test fun `준비 중이면 로그인이 정해질 때까지 기다린다`() = runBlocking {
        val status = MutableStateFlow<SessionStatus>(SessionStatus.Initializing)
        launch { delay(50); status.value = signedIn }
        assertEquals("user-jwt", LoginWait.awaitUserToken(status, 2.seconds))
    }

    @Test fun `갱신 실패 뒤 다시 시도해 성공하면 그 열쇠`() = runBlocking {
        val status = MutableStateFlow<SessionStatus>(SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(RuntimeException("offline"))))
        launch { delay(50); status.value = signedIn }
        assertEquals("user-jwt", LoginWait.awaitUserToken(status, 2.seconds))
    }

    @Test fun `로그아웃이면 기다리지 않고 null`() = runBlocking {
        val started = System.nanoTime()
        assertNull(LoginWait.awaitUserToken(MutableStateFlow(SessionStatus.NotAuthenticated(false)), 5.seconds))
        assertTrue((System.nanoTime() - started) < 1_000_000_000L)
    }

    @Test fun `시간 안에 정해지지 않으면 null`() = runBlocking {
        assertNull(LoginWait.awaitUserToken(MutableStateFlow(SessionStatus.Initializing), 100.milliseconds))
    }
}
