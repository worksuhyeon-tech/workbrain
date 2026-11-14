package com.workbrain.app.more

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.Supa
import com.workbrain.app.data.Server
import com.workbrain.app.tasks.CalendarStatus
import com.workbrain.app.tasks.CalendarStore
import com.workbrain.app.tasks.connectResultText
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import com.workbrain.app.ui.WbChip
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

/**
 * 더보기 '구글 캘린더'(FR-24a, DEC-105, Design §3.16, 목업 workbrain-settings.html #calendar — 앱은 더보기, 웹은 설정).
 * [연결] → 서버가 만든 Google 허락 주소를 앱 안 브라우저 탭으로 연다 → 허락 뒤 workbrain://calendar-callback?calendar=결과로 돌아온다
 * (MainActivity가 받아 CalendarStore에 넘기고, 더보기 탭으로 온다). 연결은 앱·웹이 같이 쓴다
 */
@Composable
fun CalendarSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cal by CalendarStore.state.collectAsStateWithLifecycle()
    val result by CalendarStore.connectResult.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    val email = Supa.client.auth.currentUserOrNull()?.email.orEmpty()

    LaunchedEffect(Unit) { CalendarStore.refresh() }
    // 허락하고 돌아온 결과를 한 번 알린다
    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        CalendarStore.consumeConnectResult()
        connectResultText(r)?.let { message = it to if (r == "ok") Tone.Good else Tone.Warn }
        busy = false
    }

    fun connect() {
        busy = true
        message = null
        scope.launch {
            runCatching { Server.calendarConnectUrl() }
                .onSuccess { url -> openConsent(context, url) }
                .onFailure { message = "연결을 시작하지 못했어요. 잠시 뒤 다시 해 주세요" to Tone.Crit }
            busy = false
        }
    }

    fun disconnect() {
        busy = true
        message = null
        scope.launch {
            message = runCatching { Server.calendarDisconnect() }.fold(
                onSuccess = { "연결을 끊었어요 · 캘린더에 넣은 일정은 그대로 있어요" to Tone.Good },
                onFailure = { "연결을 끊지 못했어요. 잠시 뒤 다시 해 주세요" to Tone.Crit },
            )
            CalendarStore.refresh()
            busy = false
        }
    }

    WbCard {
        Text(
            buildAnnotatedString {
                append("오늘 화면에 ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Wb.colors.ink)) { append("오늘 일정") }
                append("을 보여 주고, 고른 할 일을 ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Wb.colors.ink)) { append("캘린더에도 넣어요") }
                append(". 로그인한 Google 계정의 기본 캘린더 하나만 써요.")
            },
            style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted,
        )
        message?.let { (text, tone) -> Banner(text, tone, Modifier.padding(top = 10.dp)) }

        // 목업 .status-row: 상태 알약 · 설명 · 버튼
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            when (cal.status) {
                null -> Text("연결 상태를 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
                CalendarStatus.ON -> StatusRow(
                    pill = { WbChip("연결됨", Tone.Good) },
                    text = listOfNotNull(email.ifEmpty { null }, "기본 캘린더", since(cal.connectedAt)).joinToString(" · "),
                ) { SecondaryButton("연결 끊기", onClick = ::disconnect, enabled = !busy) }
                CalendarStatus.BROKEN -> StatusRow(
                    pill = { WbChip("연결이 끊겼어요", Tone.Crit) },
                    text = "Google 쪽에서 권한이 취소됐어요. 다시 연결해 주세요.",
                ) { PrimaryButton("다시 연결", onClick = ::connect, enabled = !busy) }
                CalendarStatus.OFF -> StatusRow(
                    pill = { WbChip("연결 안 됨", Tone.Mute) },
                    text = "연결하면 오늘 일정 카드와 할 일의 [캘린더에도 넣기]가 켜져요.",
                ) { PrimaryButton(if (busy) "여는 중…" else "연결", onClick = ::connect, enabled = !busy) }
            }
        }

        HorizontalDivider(color = Wb.colors.border, modifier = Modifier.padding(vertical = 12.dp))
        listOf(
            "[연결]을 누르면 Google 허락 화면이 한 번 열려요. 로그인은 다시 하지 않아요. 로그인한 계정과 같은 계정으로 허락해야 연결돼요.",
            "‘Google에서 확인하지 않은 앱’ 화면이 나오면 [고급] → [WorkBrain(으)로 이동]을 눌러요. 직접 만든 앱이라 나오는 화면이에요.",
            "일정은 볼 때마다 Google에서 읽고, WorkBrain 서버에 저장하지 않아요.",
            "WorkBrain이 넣은 일정만 고치거나 지워요. 다른 일정은 건드리지 않아요.",
            "연결 열쇠는 서버에 암호화해 두고, 화면에는 보이지 않아요. 앱과 웹이 같은 연결을 함께 써요.",
        ).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

@Composable
private fun StatusRow(pill: @Composable () -> Unit, text: String, button: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            pill()
            Text(text, style = MaterialTheme.typography.bodySmall, color = Wb.colors.body, modifier = Modifier.padding(top = 4.dp))
        }
        button()
    }
}

/** 목업 표기 '11/14 연결' */
private fun since(connectedAt: String?): String? = connectedAt?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).let { t -> "${t.monthValue}/${t.dayOfMonth} 연결" } }.getOrNull()
}

/** Google 로그인처럼 앱 안 브라우저 탭으로 연다. 탭을 못 열면 기본 브라우저 */
private fun openConsent(context: Context, url: String) {
    val uri = Uri.parse(url)
    runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) }
        .onFailure { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) } }
}
