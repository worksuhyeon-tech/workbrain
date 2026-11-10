package com.workbrain.app.more

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.PlanDates
import com.workbrain.app.data.PlanKind
import com.workbrain.app.data.PlanLine
import com.workbrain.app.data.Server
import com.workbrain.app.data.WorkPlanResult
import com.workbrain.app.review.DocxShare
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.Note
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.SegmentPills
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import java.time.LocalDate
import kotlinx.coroutines.launch

/*
 * 업무계획 미리보기(FR-33, DEC-87·88, Design §3.13): [주간|월간] · ‹ 기간 › · 담당자 · [전주|금주] 탭으로 칸 글 보기 · [Word 받기 ↪].
 * 글은 서버가 만들고(□ 줄만 AI, 약 1원) 이 화면은 보여 주기만 한다. 같은 기간을 다시 볼 때 AI를 또 부르지 않게 화면에 둔다.
 * 고치기는 Word에서 한다(1단계).
 */

private const val CELL_PREVIOUS = "previous"
private const val CELL_NEXT = "next"

@Composable
fun WorkPlanScreen(onBack: (() -> Unit)?, initialKind: PlanKind) {
    // null이면 펼친 화면의 오른쪽 칸이라 뒤로 가기가 없다
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kindCode by rememberSaveable(initialKind) { mutableStateOf(initialKind.code) }
    var baseText by rememberSaveable { mutableStateOf(LocalDate.now(SEOUL).toString()) }
    var cell by rememberSaveable { mutableStateOf(CELL_PREVIOUS) }
    val kind = PlanKind.of(kindCode)
    val base = LocalDate.parse(baseText)

    val made = remember { mutableStateMapOf<String, WorkPlanResult>() }
    var result by remember { mutableStateOf<WorkPlanResult?>(null) }
    var failed by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val key = "$kindCode:$baseText"

    LaunchedEffect(key, reloadKey) {
        message = null
        made[key]?.let { result = it; failed = false; return@LaunchedEffect }
        result = null; failed = false
        runCatching { Server.workPlan(kind, base) }
            .onSuccess { made[key] = it; result = it }
            .onFailure { failed = true }
    }

    fun share() {
        val plan = result?.plan ?: return
        sharing = true
        scope.launch {
            runCatching { Server.planWord(plan) }
                .onSuccess { DocxShare.share(context, it, PlanDates.fileName(plan), "[WorkBrain] ${plan.title}", "업무계획 Word 공유") }
                .onFailure { message = "Word 파일을 받지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit }
            sharing = false
        }
    }

    Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Text("← 더보기", color = Wb.colors.link) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PageHeader("업무계획", "회사 제출용 표 초안") {
                // 할 일을 고친 뒤 다시 만들 때(AI 다듬기 한 번 더, 약 1원)
                TextButton(onClick = { made.remove(key); reloadKey++ }, enabled = result != null) { Text("새로 만들기", color = Wb.colors.link) }
            }
            SegmentPills(PlanKind.entries.map { it.code to it.label }, kindCode) { kindCode = it; cell = CELL_PREVIOUS }
            PeriodStepper(
                text = result?.plan?.let { "${inner(it.previous.label)} · ${inner(it.next.label)}" } ?: "…",
                onPrev = { baseText = PlanDates.shift(kind, base, -1).toString() },
                onNext = { baseText = PlanDates.shift(kind, base, 1).toString() },
            )

            val r = result
            when {
                failed -> Banner("업무계획을 만들지 못했어요. 인터넷 연결을 확인하고 '새로 만들기'를 눌러 주세요", Tone.Crit)
                r == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = Wb.colors.primary)
                    Note("할 일·회의로 표를 만들고 □ 줄을 다듬는 중…")
                }
                else -> {
                    if (r.plan.ownerName.isBlank()) {
                        Banner("담당자 칸이 비어요 · 더보기 '내 이름·호칭'에 이름을 적어 주세요", Tone.Warn)
                    } else {
                        Note("담당자 ${r.plan.ownerName} · 더보기 '내 이름·호칭'의 첫 이름")
                    }
                    WbCard {
                        SegmentPills(
                            listOf(CELL_PREVIOUS to r.plan.previous.label, CELL_NEXT to r.plan.next.label), cell,
                        ) { cell = it }
                        Spacer(Modifier.height(8.dp))
                        PlanLines(if (cell == CELL_PREVIOUS) r.plan.previous.lines else r.plan.next.lines)
                    }
                    Note(aiNote(r.ai))
                }
            }
            message?.let { (text, tone) -> Banner(text, tone) }
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider(color = Wb.colors.border)
        PrimaryButton(
            if (sharing) "Word 만드는 중…" else "Word 받기 ↪",
            onClick = ::share,
            enabled = result != null && !sharing,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding(),
        )
    }
}

/** '전주 (11.9 ~ 11.13)' → '11.9 ~ 11.13' */
private fun inner(label: String): String = label.substringAfter('(').substringBefore(')').ifBlank { label }

private fun aiNote(ai: String): String = when (ai) {
    "done" -> "□ 줄은 AI가 이름과 진행 상태를 한 줄로 다듬었어요(약 1원). 나머지는 할 일·회의로 규칙에 따라 만든 초안이라 숫자·결론은 Word에서 확인해 주세요."
    "budget" -> "이번 달 AI 예산 대기선을 넘어 □ 줄은 '이름 : 현재 상태'로 만들었어요. Word에서 고쳐 주세요."
    "none" -> "할 일·회의로 규칙에 따라 만든 초안이에요. Word에서 고쳐 주세요."
    else -> "AI 다듬기를 못 해 □ 줄은 '이름 : 현재 상태'로 만들었어요. '새로 만들기'로 다시 해 볼 수 있어요."
}

@Composable
private fun PeriodStepper(text: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrev) { Text("‹", fontSize = 22.sp, color = Wb.colors.link) }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        TextButton(onClick = onNext) { Text("›", fontSize = 22.sp, color = Wb.colors.link) }
    }
}

/** 칸 글: □ 굵게 · - 한 단 들여 씀(줄이 넘으면 기호 뒤에 맞춤) · * 작은 파란 글씨(Word와 같은 계층) */
@Composable
private fun PlanLines(lines: List<PlanLine>) {
    if (lines.isEmpty()) {
        Note("이 기간에 넣을 할 일·회의가 없어요.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            when (line.level) {
                "head" -> Text("□ ${line.text}", fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(top = 6.dp))
                "item" -> Marked("-", line.text, 14.sp, Wb.colors.body, start = 10)
                else -> Marked("*", line.text, 12.sp, Wb.colors.link, start = 24)
            }
        }
    }
}

@Composable
private fun Marked(mark: String, text: String, size: TextUnit, color: Color, start: Int) {
    Row(Modifier.padding(start = start.dp)) {
        Text("$mark ", fontSize = size, color = color)
        Text(text, fontSize = size, lineHeight = size * 1.45f, color = color, modifier = Modifier.weight(1f))
    }
}
