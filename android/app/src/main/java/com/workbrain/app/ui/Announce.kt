package com.workbrain.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 알림이 떠 있는 시간(DEC-51, 10/26 사용자 요청으로 4초 → 3초). 게이지도 같은 시간 동안 줄어든다 */
private const val TOAST_MS = 3_000L

/**
 * 화면 아래 잠깐 뜨는 알림 + (있으면) 되돌리기. 녹음 목록의 지움·되살림과 프로젝트 정리(DEC-24 놓은 뒤 되돌리기)가 같이 쓴다.
 * 새 알림이 오면 앞 알림은 바로 닫는다(되돌리기는 마지막 동작에만)
 */
class Announcer(private val scope: CoroutineScope, val host: SnackbarHostState) {
    fun show(message: String, undo: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            // 남은 시간을 게이지로 보여 주려면 사라지는 시점을 앱이 정해야 한다(기본 Short는 접근성 설정에 따라 늘어남)
            val result = withTimeoutOrNull(TOAST_MS) {
                host.showSnackbar(message, actionLabel = if (undo != null) "되돌리기" else null, duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
}

@Composable
fun rememberAnnouncer(): Announcer {
    val scope = rememberCoroutineScope()
    return remember { Announcer(scope, SnackbarHostState()) }
}

/** 알림 자리. 화면 Box 안에서 아래 가운데에 둔다 */
@Composable
fun AnnounceHost(announcer: Announcer, modifier: Modifier = Modifier) {
    SnackbarHost(announcer.host, modifier.padding(16.dp)) { DoneSnackbar(it) }
}

/**
 * 체크 표시 + 문구 + (있으면) 되돌리기 + 아래 남은 시간 게이지.
 * 바탕은 불투명(반투명은 글씨가 덜 보여 사용자가 되돌림, 10/26)
 */
@Composable
private fun DoneSnackbar(data: SnackbarData) {
    val remaining = remember(data) { Animatable(1f) }
    LaunchedEffect(data) { remaining.animateTo(0f, tween(TOAST_MS.toInt(), easing = LinearEasing)) }
    // 글자 길이만큼만 차지하고 가운데 둔다(화면 폭 전체는 너무 큼, 10/26 사용자 요청). 모서리는 카드와 같은 둥글기
    Surface(shape = CardShape, color = Wb.colors.ink, shadowElevation = 4.dp, modifier = Modifier.widthIn(max = 360.dp)) {
        // 게이지가 글줄과 같은 폭이 되도록 가장 넓은 줄에 맞춘다
        Column(Modifier.width(IntrinsicSize.Max)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(WbIcons.Check, contentDescription = null, tint = Wb.colors.surface, modifier = Modifier.size(18.dp))
                Text(
                    data.visuals.message,
                    color = Wb.colors.surface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                )
                data.visuals.actionLabel?.let { label ->
                    TextButton(onClick = data::performAction) { Text(label, color = Wb.colors.surface, fontWeight = FontWeight.Bold) }
                }
            }
            // 남은 시간: 다 줄어들면 알림이 사라지고 되돌리기도 끝난다
            Box(Modifier.fillMaxWidth().height(3.dp).background(Wb.colors.surface.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(remaining.value).fillMaxHeight().background(Wb.colors.surface.copy(alpha = 0.75f)))
            }
        }
    }
}
