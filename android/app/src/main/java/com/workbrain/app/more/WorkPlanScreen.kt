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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/*
 * 업무계획 미리보기(FR-33, DEC-87·88, Design §3.13): [주간|월간] · ‹ 기간 › · 담당자 · [전주|금주] 탭으로 칸 글 보기 · [Word 받기 ↪].
 * 회의록처럼 [업무계획 만들기]를 눌러야 서버가 만들고(□ 줄만 AI, 약 1원, DEC-95) 서버에 남긴다. 이 화면은 그 기간의 저장본을 읽어
 * 보여 주기만 해서, 나갔다 와도·다른 기기에서도 AI를 다시 부르지 않는다. 승인 절차는 없고, 고치기는 Word에서 한다(1단계).
 */

private const val CELL_PREVIOUS = "previous"
private const val CELL_NEXT = "next"

@Composable
fun WorkPlanScreen(onBack: (() -> Unit)?, initialKind: PlanKind, backLabel: String = "더보기") {
    // null이면 펼친 화면의 오른쪽 칸이라 뒤로 가기가 없다(오늘 탭에서 연 것은 펼친 화면에도 '← 오늘', DEC-94)
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kindCode by rememberSaveable(initialKind) { mutableStateOf(initialKind.code) }
    var baseText by rememberSaveable { mutableStateOf(LocalDate.now(SEOUL).toString()) }
    var cell by rememberSaveable { mutableStateOf(CELL_PREVIOUS) }
    val kind = PlanKind.of(kindCode)
    val base = LocalDate.parse(baseText)
    val key = WorkPlanMaker.keyOf(kind, base)

    // 서버 저장본(DEC-95): 들어오거나 기간을 바꿀 때마다 읽는다(다른 기기에서 만든 것도 보이게). 읽기만이라 AI를 부르지 않는다
    var saved by remember(key) { mutableStateOf<WorkPlanResult?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var loadFailed by remember(key) { mutableStateOf(false) }
    var loadTry by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, loadTry) {
        loading = true
        runCatching { Server.savedWorkPlan(kind, base) }
            .onSuccess { saved = it; loadFailed = false }
            .onFailure { loadFailed = true }
        loading = false
    }
    DisposableEffect(key) { onDispose { WorkPlanMaker.forgetFailure(key) } }
    val states by WorkPlanMaker.states.collectAsStateWithLifecycle()
    val job = states[key]
    val making = job == WorkPlanMaker.State.Making
    val failed = job == WorkPlanMaker.State.Failed
    // 이 앱에서 방금 만든 것과 저장본 중 나중 것(그 뒤 다른 기기에서 다시 만들었을 수 있다)
    val result = listOfNotNull((job as? WorkPlanMaker.State.Made)?.result, saved).maxByOrNull { madeInstant(it.madeAt) }
    val make = { WorkPlanMaker.make(kind, base) }

    var sharing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }

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
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Text("← $backLabel", color = Wb.colors.link) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PageHeader("업무계획", "회사 제출용 표 초안")
            SegmentPills(PlanKind.entries.map { it.code to it.label }, kindCode) { kindCode = it; cell = CELL_PREVIOUS }
            PeriodStepper(
                text = PlanDates.periodText(kind, base),
                onPrev = { baseText = PlanDates.shift(kind, base, -1).toString(); message = null },
                onNext = { baseText = PlanDates.shift(kind, base, 1).toString(); message = null },
            )

            val r = result
            when {
                r != null -> {
                    MadeRow(r.madeAt, making, onRemake = make)
                    if (failed) Banner("다시 만들지 못했어요. 인터넷 연결을 확인해 주세요 · 전에 만든 것을 보여 드려요", Tone.Warn)
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
                making -> MakingCard()
                loading -> Note("불러오는 중…")
                loadFailed -> {
                    Banner("만들어 둔 업무계획을 불러오지 못했어요. 인터넷 연결을 확인해 주세요", Tone.Crit)
                    TextButton(onClick = { loadTry++ }) { Text("다시 불러오기", color = Wb.colors.link) }
                }
                else -> EmptyPlanCard(failed, onMake = make)
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

/** 아직 만들지 않은 기간(회의록 '아직 회의록이 없어요'와 같은 모양, DEC-95). 누르기 전에는 AI를 부르지 않는다 */
@Composable
private fun EmptyPlanCard(failed: Boolean, onMake: () -> Unit) {
    WbCard {
        Text("아직 이 기간 업무계획이 없어요", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
        Text(
            "할 일·회의로 회사 양식 표를 만들고, 전주 칸 □ 줄은 AI가 한 줄로 다듬어요. 10~15초쯤 걸리고 약 1원이에요.",
            fontSize = 13.sp, lineHeight = 19.sp, color = Wb.colors.muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (failed) Banner("업무계획을 만들지 못했어요. 인터넷 연결을 확인하고 다시 눌러 주세요", Tone.Crit, Modifier.padding(bottom = 8.dp))
        PrimaryButton("업무계획 만들기", onClick = onMake, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MakingCard() {
    WbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Wb.colors.primary)
            Spacer(Modifier.width(10.dp))
            Text("업무계획 만드는 중 · 10~15초쯤 걸려요. 화면을 나가도 계속 만들어요", fontSize = 14.sp, lineHeight = 20.sp, color = Wb.colors.primary)
        }
    }
}

/** '11/11(수) 10:43 만듦' + [다시 만들기](할 일을 고친 뒤 AI 다듬기 한 번 더, 약 1원). 바뀌었는지는 사용자가 판단한다(DEC-95 ④) */
@Composable
private fun MadeRow(madeAt: String, making: Boolean, onRemake: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${madeText(madeAt)} 만듦", fontSize = 13.sp, color = Wb.colors.muted, modifier = Modifier.weight(1f))
        if (making) {
            CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp), color = Wb.colors.primary)
            Spacer(Modifier.width(6.dp))
            Text("다시 만드는 중", fontSize = 13.sp, color = Wb.colors.primary)
        } else {
            TextButton(onClick = onRemake, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 만들기", color = Wb.colors.link) }
        }
    }
}

private val MADE_AT = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.KOREAN)

/** 서버 시각(ISO, Z·+00:00 모두) → 서울 '11/11(수) 10:43' */
private fun madeText(iso: String): String = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(SEOUL).format(MADE_AT) }.getOrDefault("")

private fun madeInstant(iso: String): Instant = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrDefault(Instant.EPOCH)

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
