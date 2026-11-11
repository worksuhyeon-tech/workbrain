package com.workbrain.app.tasks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 다른 화면(검색, FR-13)에서 고른 할 일 하나를 프로젝트 탭 상세에서 찾아 보이게 하고 잠깐 칠한다
 * (2026-11-12 사용자 요청: 휴대폰 설정 검색에서 항목으로 갈 때처럼 그 줄까지 내려가 형광펜처럼 칠했다 사라지게).
 * 화면마다 인자로 내려보내지 않으려고 화면 밖 신호로 둔다(RecorderLaunch와 같은 방식).
 * 그 줄이 그려지지 않으면(지운 할 일 등) 프로젝트 탭이 몇 초 뒤 비워, 나중에 엉뚱하게 칠해지지 않게 한다
 */
object TaskFocus {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    /** 칠하는 데 걸리는 넉넉한 시간(내리기 + 칠하기). 지나면 비운다 */
    const val TIMEOUT_MS = 12_000L

    fun request(taskId: String) { _pending.value = taskId }

    fun consume(taskId: String) { if (_pending.value == taskId) _pending.value = null }
}

/**
 * 고른 할 일 줄: 목록 안에서 그 줄이 위아래 여백까지 보이게 끌어온 뒤 강조색(Wb.colors.highlight, 검색에서 맞은 글자와 같은 분홍 20%)을
 * 두 번 깜빡이고 천천히 지운다. focused가 아니면 아무것도 하지 않는다.
 * 위쪽 카드(현재 상태 등)가 늦게 불러와져 커지면 줄이 다시 밀려나므로, 줄 위치가 멈출 때까지 맞추기를 되풀이한 뒤 칠한다
 * (11/12 Fold8: 할 일 칸이 화면 아래에 걸쳐 보인 채 위 카드가 커져 화면 밖에서 깜빡이고 끝났음)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusFlash(taskId: String, focused: Boolean, color: Color): Modifier {
    if (!focused) return this
    val requester = remember { BringIntoViewRequester() }
    val alpha = remember { Animatable(0f) }
    // 줄의 화면 위치·크기(그리기와 상관없어 상태로 두지 않는다)
    val box = remember { FloatArray(3) { Float.NaN } }
    val density = LocalDensity.current
    LaunchedEffect(taskId) {
        // 위로 한 줄, 아래로 화면 아래 입력 띠(모든 할 일)만큼 여백을 두고 보이게 한다
        val above = with(density) { 48.dp.toPx() }
        val below = with(density) { 160.dp.toPx() }
        delay(250)
        var lastTop = Float.NaN
        var still = 0
        var tries = 0
        while (still < 2 && tries < 14) {
            if (!box[1].isNaN()) requester.bringIntoView(Rect(0f, -above, box[1], box[2] + below))
            delay(250)
            val top = box[0]
            still = if (!top.isNaN() && !lastTop.isNaN() && abs(top - lastTop) < 1f) still + 1 else 0
            lastTop = top
            tries++
        }
        alpha.animateTo(1f, tween(220))
        alpha.animateTo(0.35f, tween(220))
        alpha.animateTo(1f, tween(220))
        delay(600)
        alpha.animateTo(0f, tween(900))
        TaskFocus.consume(taskId)
    }
    return this
        .onGloballyPositioned { c -> box[0] = c.positionInRoot().y; box[1] = c.size.width.toFloat(); box[2] = c.size.height.toFloat() }
        .bringIntoViewRequester(requester)
        .drawBehind { drawRect(color.copy(alpha = color.alpha * alpha.value)) }
}
