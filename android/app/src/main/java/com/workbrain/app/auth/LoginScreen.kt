package com.workbrain.app.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.Supa
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.HandAccent
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import kotlinx.coroutines.launch

/** S01 로그인: 본인 Google 계정만 들어올 수 있다(서버 허용 목록, DEC-26). */
@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(Wb.colors.surface).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("WorkBrain", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, letterSpacing = (-0.6).sp)
        Text("일의 기억", fontFamily = HandAccent, fontSize = 26.sp, color = Wb.colors.muted)
        Spacer(Modifier.height(40.dp))
        WbCard {
            Text("로그인", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
            Text(
                "등록된 본인 Google 계정만 사용할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = Wb.colors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            PrimaryButton(
                if (busy) "브라우저에서 로그인 중…" else "Google로 로그인",
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        // 브라우저가 열리고, 로그인 후 앱으로 돌아오면 세션 상태가 바뀌어 홈으로 넘어간다
                        runCatching { Supa.client.auth.signInWith(Google) }
                            .onFailure { error = "로그인을 시작하지 못했어요. 인터넷 연결을 확인해 주세요." }
                        busy = false
                    }
                },
            )
            error?.let { Banner(it, Tone.Crit, Modifier.padding(top = 12.dp)) }
        }
        Text(
            "녹음은 올리기 전까지 이 휴대폰에만 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = Wb.colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
