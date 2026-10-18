package com.workbrain.app.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.BuildConfig
import com.workbrain.app.Supa
import com.workbrain.app.data.Server
import com.workbrain.app.record.RecStatus
import com.workbrain.app.record.RecordingService
import com.workbrain.app.ui.GlassIcon
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SectionLabel
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

/** 더보기: 계정·이번 달 AI 비용·앱 정보·글꼴 라이선스·로그아웃 */
@Composable
fun MoreScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by RecordingService.state.collectAsStateWithLifecycle()
    var costKrw by remember { mutableStateOf<Int?>(null) }
    var license by remember { mutableStateOf<String?>(null) }
    val email = Supa.client.auth.currentUserOrNull()?.email.orEmpty()

    LaunchedEffect(Unit) { costKrw = runCatching { Server.monthCostKrw() }.getOrNull() }

    Column(
        Modifier.fillMaxSize().background(Wb.colors.surface).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeader("더보기", "내 방식대로 맞추기")
        WbCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 목업 아바타: 보라 그라데이션 원 + 첫 글자
                Box(
                    Modifier.size(40.dp).background(Brush.linearGradient(listOf(Color(0xFF8B7BF2), Color(0xFFB9A7FF))), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(email.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("내 계정", style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                    Text(email, style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
                }
                SecondaryButton(
                    "로그아웃",
                    enabled = rec.status == RecStatus.IDLE,
                    onClick = { scope.launch { runCatching { Supa.client.auth.signOut() } } },
                )
            }
            if (rec.status != RecStatus.IDLE) {
                Text("녹음 중에는 로그아웃할 수 없어요", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }

        SectionLabel("AI 사용")
        WbCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(WbIcons.Coin, GlassTint.Orange, size = 44.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("이번 달 AI 처리 비용", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
                    Text(
                        costKrw?.let { "%,d원".format(it) } ?: "—",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Wb.colors.ink,
                    )
                }
            }
            Text("받아쓰기·회의록 정리에 쓴 금액이에요. 예산 대기선을 넘으면 새 처리를 멈추고 알려 드려요.", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
        }

        SectionLabel("앱 정보")
        WbCard {
            InfoRow("버전", BuildConfig.VERSION_NAME, null)
            HorizontalDivider(color = Wb.colors.border)
            InfoRow("글꼴 라이선스", "Pretendard · 나눔손글씨 펜 (SIL OFL 1.1)") {
                license = listOf("Pretendard-OFL.txt", "NanumPenScript-OFL.txt").joinToString("\n\n──────────\n\n") { name ->
                    runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("")
                }
            }
        }
    }

    license?.let { text ->
        AlertDialog(
            onDismissRequest = { license = null },
            confirmButton = { TextButton(onClick = { license = null }) { Text("닫기") } },
            title = { Text("글꼴 라이선스") },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.ink, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = if (onClick != null) Wb.colors.link else Wb.colors.muted)
    }
}
