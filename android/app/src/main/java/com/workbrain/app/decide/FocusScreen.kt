package com.workbrain.app.decide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.data.Server
import com.workbrain.app.data.toNewTaskFields
import com.workbrain.app.ui.HandAccent
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 집중 타이머 화면(목업 ③④④-2): 앱 어디에 있든 화면 전체로 뜬다(MainShell에서 한 번 둔다).
 * 뒤로 가기로는 닫히지 않는다(실수로 꺼지지 않게). 홈으로 나가도 잠금 화면 알림에서 시간이 간다.
 */
@Composable
fun FocusHost(onResult: (FocusResult) -> Unit = {}) {
    val s by FocusController.state.collectAsStateWithLifecycle()
    val session = s ?: return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Wb.colors.card) {
            FocusScreen(session, onResult)
        }
    }
}

@Composable
private fun FocusScreen(s: FocusSession, onResult: (FocusResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 타이머 화면에서만 화면을 켜 둔다
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.stopped) {
        while (!s.stopped) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val c = s.clock
    val over = c.over(now)
    // 정한 시간이 됐을 때 앱을 보고 있으면 여기서 진동 알림(알람보다 먼저 볼 수 있어서)
    LaunchedEffect(over, s.alerted) { if (over && !s.alerted && !s.stopped) FocusController.onOver(context) }

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> FocusResult?) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            val r = block()
            saving = false
            if (r != null) { if (r.ok) onResult(r) else error = r.message }
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // 머리: ● 집중 · 2분 / 2분 지나서 계속하는 중 / 집중 끝 · [그만]
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!s.stopped) Box(Modifier.size(8.dp).clip(CircleShape).background(Wb.colors.primary))
            Text(
                when { s.stopped -> "집중 끝"; over -> "${c.segMin}분 지나서 계속하는 중"; else -> "집중 · ${c.segMin}분" },
                fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Wb.colors.primary,
                modifier = Modifier.padding(start = if (s.stopped) 0.dp else 6.dp).weight(1f),
            )
            if (!s.stopped) Text("그만", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { FocusController.stop(context) }.padding(6.dp))
        }
        s.place?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.body, modifier = Modifier.padding(top = 10.dp)) }
        Text(
            buildAnnotatedString {
                append(s.title)
                s.piece?.let { withStyle(SpanStyle(color = Wb.colors.muted, fontWeight = FontWeight.SemiBold)) { append(" › $it") } }
            },
            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, lineHeight = 27.sp, modifier = Modifier.padding(top = 2.dp),
        )
        // 이번 N분 동안
        Column(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Wb.colors.primary.copy(alpha = 0.07f))
                .border(1.dp, Wb.colors.primary.copy(alpha = 0.18f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("이번 ${s.plannedMin}분 동안", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Wb.colors.primary)
            Text(s.step, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Wb.colors.ink, modifier = Modifier.padding(top = 2.dp))
        }

        when {
            s.stopped -> StopPart(s, saving, error) { done, next -> run { FocusController.finishHere(context, done, next) } }
            !over -> {
                Ring(progressLeft = (c.leftMs(now).toFloat() / (c.segMin * 60_000f)).coerceIn(0f, 1f), done = false) {
                    Text(mmss(c.leftMs(now)), fontSize = 54.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                    Text(if (c.paused) "잠깐 멈춤" else "남은 시간", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (c.paused) "다시 시작" else "잠깐 멈춤", { FocusController.togglePause(context) }, Modifier.weight(1f),
                        PillKind.Ghost, if (c.paused) WbIcons.Play else WbIcons.Pause, height = 52.dp)
                    PillButton("끝났어요", { run { FocusController.finishDone(context) } }, Modifier.weight(1f), PillKind.Good, WbIcons.Check, height = 52.dp, enabled = !saving)
                }
                error?.let { ErrorLine(it) }
                Spacer(Modifier.height(20.dp))
                CaptureRow()
                Text("${c.segMin}분이 지나도 멈추지 않아요 · 화면을 꺼도 잠금 화면 알림에서 시간이 가요",
                    fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
            }
            else -> {
                Ring(progressLeft = 1f, done = true) {
                    Text("${c.segMin}분 해냈어요!", fontFamily = HandAccent, fontSize = 30.sp, color = Wb.colors.good)
                    Text("+${mmss(c.overMs(now))}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Wb.colors.good)
                    Text("더 하는 중 · 시간은 계속 재요", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted)
                }
                SecretaryBubble(Modifier.padding(top = 4.dp)) { BubbleText("시작이 제일 어려운 부분이었어요. 하던 거 마저 해도 돼요. 멈출 필요 없어요.") }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("끝났어요", { run { FocusController.finishDone(context) } }, Modifier.weight(1f), PillKind.Good, WbIcons.Check, height = 52.dp, enabled = !saving)
                    PillButton("여기까지", { FocusController.stop(context) }, Modifier.weight(1f), PillKind.Ghost, height = 52.dp)
                }
                error?.let { ErrorLine(it) }
                Text("${LONG_FOCUS_MIN}분 타이머로 바꾸기", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).clip(RoundedCornerShape(6.dp))
                        .clickable { FocusController.extend(context) }.padding(6.dp))
                Spacer(Modifier.height(12.dp))
                CaptureRow()
            }
        }
    }
}

/** 원형 남은 시간(목업 .ring): progressLeft = 남은 비율(1 → 0), 다 차면 초록 */
@Composable
private fun Ring(progressLeft: Float, done: Boolean, center: @Composable () -> Unit) {
    val track = Wb.colors.surface
    val color = if (done) Wb.colors.good else Wb.colors.primary
    Box(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(236.dp)) {
            val w = 14.dp.toPx()
            val inset = w / 2
            val arc = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            drawArc(color, -90f, 360f * progressLeft, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { center() }
    }
}

/** 여기까지·그만 뒤: 정한 시간 vs 실제로 한 시간, 동작 끝냈는지, 다음엔 무엇부터(목업 ④-2) */
@Composable
private fun StopPart(s: FocusSession, saving: Boolean, error: String?, onSave: (stepDone: Boolean, next: String?) -> Unit) {
    var stepDone by remember { mutableStateOf(true) }
    // 동작을 끝냈으면 비서가 나눈 다음 조각, 아니면 같은 동작을 다음에 다시
    var next by remember(stepDone) { mutableStateOf(if (stepDone) s.nextHint.orEmpty() else s.step) }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpentBox("${s.plannedMin}분", "정한 시간", Modifier.weight(1f))
        SpentBox(spentText(s.clock.spentMs(System.currentTimeMillis())), "실제로 한 시간", Modifier.weight(1f))
    }
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Wb.colors.border, RoundedCornerShape(14.dp))
            .clickable { stepDone = !stepDone }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (stepDone) Wb.colors.good else Wb.colors.card)
                .border(1.5.dp, if (stepDone) Wb.colors.good else Wb.colors.borderStrong, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { if (stepDone) Icon(WbIcons.Check, contentDescription = null, tint = Wb.colors.onPrimary, modifier = Modifier.size(14.dp)) }
        Text("이번 동작은 끝냈어요", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.ink, modifier = Modifier.padding(start = 10.dp))
    }
    SecretaryBubble(Modifier.padding(top = 12.dp)) {
        BubbleText(if (s.isSubtask) "수고했어요. 이 일은 오늘 할 일에 남겨 둘게요." else "수고했어요. 다음에 이어서 할 동작을 적어 두면, 다음에 이 일을 열 때 제가 먼저 보여 드릴게요.")
    }
    if (!s.isSubtask) {
        Text(buildAnnotatedString {
            append("다음엔 무엇부터 ")
            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Wb.colors.muted)) { append("(선택)") }
        }, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp))
        LineInput(next, { next = it }, "예: 표 틀 만들기", trailing = if (next.isNotBlank() && next == s.nextHint) ({ AiTag() }) else null)
    }
    Text("이 일은 오늘 할 일에 남겨 둘게요.", fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.padding(top = 8.dp))
    error?.let { ErrorLine(it) }
    PillButton("저장하고 다음 장", { onSave(stepDone, next.takeIf { it.isNotBlank() }) }, Modifier.fillMaxWidth().padding(top = 12.dp), height = 56.dp, enabled = !saving, fontSize = 17)
}

@Composable
private fun SpentBox(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, Wb.colors.border, RoundedCornerShape(16.dp)).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted)
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, fontSize = 13.sp, color = Wb.colors.crit, modifier = Modifier.padding(top = 8.dp))
}

/**
 * 딴생각 담기(DEC-111 ⑤): 떠오른 생각을 기한 없는 내 할 일로 넣고 타이머는 그대로. 보관함(FR-10)이 생기면 그리로 옮긴다
 */
@Composable
private fun CaptureRow() {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Wb.colors.card2).border(1.dp, Wb.colors.border, RoundedCornerShape(28.dp))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("딴생각이 떠오르면 여기에 던져 두세요", fontSize = 14.sp, color = Wb.colors.muted)
            BasicTextField(
                text, { text = it.take(200) },
                textStyle = TextStyle(fontSize = 14.sp, color = Wb.colors.ink),
                cursorBrush = SolidColor(Wb.colors.primary),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        PillButton("담기", {
            val t = text.trim()
            if (t.isEmpty() || busy) return@PillButton
            busy = true
            scope.launch {
                note = runCatching { Server.createTask(plainEdit(t).toNewTaskFields()) }
                    .fold({ text = ""; "담았어요 · ‘모든 할 일 › 기한 없음’에 있어요" }, { "담지 못했어요. 인터넷 연결을 확인해 주세요" })
                busy = false
            }
        }, kind = PillKind.Ghost, height = 36.dp, fontSize = 14)
    }
    note?.let { Text(it, fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.padding(start = 16.dp, top = 4.dp)) }
}
