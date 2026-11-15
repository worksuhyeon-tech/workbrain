package com.workbrain.app.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 빠른 입력 아이콘 줄과 칸(DEC-107, Design §3.18, 목업 workbrain-quickadd-android.html).
 * 삼성 리마인더처럼 이름표 없는 아이콘을 누르면 키보드 자리에 그 칸만 열린다. 값은 QuickPicks 규칙으로 단축어와 합친다.
 */

/** 빠른 입력 한 벌의 아이콘 값과 동작(rememberQuickAdd가 만든다) */
class QuickToolState(
    /** 이 입력 줄에서 보이는 아이콘. 프로젝트·반복실행 상세는 곳이 정해져 있어 [어디에]가 없다 */
    val shown: List<QuickTool>,
    val places: List<Place>,
    /** 이 기기에서 마지막에 넣거나 옮긴 곳('최근', DEC-70·91) */
    val recent: Place?,
    val subtasks: List<String>,
    val setSubtasks: (List<String>) -> Unit,
    val pickDate: (LocalDate) -> Unit,
    /** null = 시각 없음(날짜만) */
    val pickTime: (LocalTime?) -> Unit,
    val pickHourLater: () -> Unit,
    val pickNoDue: () -> Unit,
    val choosePlace: (Place?) -> Unit,
    val choosePriority: (Int) -> Unit,
    /** 칸을 열 때의 기한 값([취소]로 되돌리려고) */
    val duePick: Picked<Due?>?,
    val restoreDue: (Picked<Due?>?) -> Unit,
)

// ── 부드럽게(11/15 사용자: 칸·탭·곳 고르기가 뚝뚝 끊김) — 밝아지기/사라지기 + 크기 이어 바꾸기 ──

private const val FADE_IN_MS = 220
private const val FADE_OUT_MS = 140
/** 칸을 고르고 닫힐 때 고른 줄이 바뀐 것을 잠깐 보여 준다 */
private const val PICK_PAUSE_MS = 160L

/** 칸·탭·달이 바뀔 때: 새 것이 밝아지고 옛 것이 사라지며, 높이는 이어서 바뀐다 */
fun <S> AnimatedContentTransitionScope<S>.quickFade(): ContentTransform =
    (fadeIn(tween(FADE_IN_MS, delayMillis = 60)) togetherWith fadeOut(tween(FADE_OUT_MS))).using(SizeTransform(clip = false))

@Composable
private fun animatedColor(target: Color) = animateColorAsState(target, tween(FADE_IN_MS), label = "quick-color").value

/** 값이 있는 아이콘(적은 단축어로 읽었든 아이콘으로 골랐든)은 파랗게 */
private fun QuickAdd.hasValue(tool: QuickTool): Boolean = when (tool) {
    QuickTool.SUB -> cleanSubtasks(tools.subtasks).isNotEmpty()
    QuickTool.DUE -> parsed.dueDate != null || parsed.vague != null
    QuickTool.PLACE -> parsed.place != null
    QuickTool.PRIO -> parsed.priority != 2
}

private fun toolIcon(tool: QuickTool): ImageVector = when (tool) {
    QuickTool.SUB -> WbIcons.Checklist
    QuickTool.DUE -> WbIcons.Calendar
    QuickTool.PLACE -> WbIcons.Folder
    QuickTool.PRIO -> WbIcons.Star
}

/** 아이콘 줄(목업 .tools): 42dp 동그라미. 값 있음 = 옅은 파랑 + 점, 열림 = 파랑 바탕 */
@Composable
fun QuickToolRow(quick: QuickAdd, open: QuickTool?, onToggle: (QuickTool) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        quick.tools.shown.forEach { tool ->
            val isOpen = open == tool
            val set = quick.hasValue(tool)
            val bg = animatedColor(
                when {
                    isOpen -> Wb.colors.primary
                    set -> Wb.colors.primary.copy(alpha = 0.13f)
                    else -> Wb.colors.surface
                },
            )
            val tint = animatedColor(
                when {
                    isOpen -> Wb.colors.onPrimary
                    set -> Wb.colors.primary
                    else -> Wb.colors.ink
                },
            )
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(bg)
                    .clickable(role = Role.Button, onClickLabel = if (isOpen) "${tool.label} 칸 닫기" else "${tool.label} 칸 열기") { onToggle(tool) }
                    .semantics {
                        contentDescription = tool.label + if (set) " · 정해짐" else ""
                        selected = isOpen
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(toolIcon(tool), contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
                if (set && !isOpen) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp).size(8.dp)
                            .clip(CircleShape).background(Wb.colors.card).padding(1.5.dp).clip(CircleShape).background(Wb.colors.primary),
                    )
                }
            }
        }
    }
}

/**
 * 아이콘을 누르면 키보드 자리에 여는 칸. onDone: 칸을 닫고 키보드로(계속 적게). 접은 화면에서 넘치면 칸 안에서 스크롤.
 * [취소]는 칸을 열 때의 값으로 되돌리고 닫는다(삼성 리마인더처럼). 여닫기·칸 바꾸기의 애니메이션은 부르는 쪽(AnimatedContent)
 */
@Composable
fun QuickToolPanel(quick: QuickAdd, tool: QuickTool, onDone: () -> Unit, modifier: Modifier = Modifier, horizontalPadding: Dp = 16.dp) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 280).coerceIn(240, 460).dp
    val dueAtOpen = remember(tool) { quick.tools.duePick }
    val subsAtOpen = remember(tool) { quick.tools.subtasks }
    // 하위 할 일 칸의 적다 만 줄: [완료]가 아래 고정 줄에 있어서 여기서 들고 있는다
    var subDraft by remember(tool) { mutableStateOf("") }
    Column(modifier.heightIn(max = maxHeight).padding(horizontal = horizontalPadding, vertical = 12.dp)) {
        // 본문만 스크롤하고 [취소]·[완료]는 늘 아래에 보인다(접은 화면에서 달력이 길어도, 11/15 사용자)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            when (tool) {
                QuickTool.DUE -> DuePanel(quick)
                QuickTool.SUB -> SubtaskPanel(quick, subDraft) { subDraft = it }
                QuickTool.PLACE -> PlacePanel(quick, onDone)
                QuickTool.PRIO -> PriorityPanel(quick, onDone)
            }
        }
        when (tool) {
            QuickTool.DUE -> PanelFoot(onCancel = { quick.tools.restoreDue(dueAtOpen); onDone() }, onDone = onDone)
            QuickTool.SUB -> PanelFoot(
                onCancel = { quick.tools.setSubtasks(subsAtOpen); onDone() },
                onDone = {
                    val lines = quick.tools.subtasks
                    if (subDraft.isNotBlank() && lines.size < MAX_SUBTASKS) quick.tools.setSubtasks(lines + subDraft.trim())
                    onDone()
                },
            )
            QuickTool.PLACE, QuickTool.PRIO -> Unit
        }
    }
}

// ── 기한: 삼성 리마인더처럼 [날짜 | 시간] 두 칸, 달력·휠, 바로 가기 ──

private const val DOW = "일월화수목금토"

private fun dayLabel(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일 (${DOW[d.dayOfWeek.value % 7]})"

@Composable
private fun DuePanel(quick: QuickAdd) {
    var timeTab by rememberSaveable { mutableStateOf(false) }
    val r = quick.parsed
    val today = quick.shownDay
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DueTab("날짜", r.dueDate?.let(::dayLabel) ?: r.vague?.let { "$it · 확인 필요" } ?: "없음", !timeTab, Modifier.weight(1f)) { timeTab = false }
        // 넣은 시각만 빼기: 값 옆 ⊖(삼성 리마인더 시간 줄처럼). 기한 전체를 빼는 ⊖는 미리보기 기한 칩에
        DueTab(
            "시간", r.dueTime?.let(::ampmLabel) ?: "시간 설정", timeTab, Modifier.weight(1f),
            onClear = if (r.dueTime != null) ({ quick.tools.pickTime(null) }) else null,
        ) { timeTab = true }
    }
    // 날짜 ↔ 시간: 밝아지고 사라지며 높이가 이어서 바뀐다
    AnimatedContent(timeTab, transitionSpec = { quickFade() }, label = "due-tab") { time ->
        Column {
            if (!time) {
                MonthGrid(selected = r.dueDate, today = today, onPick = quick.tools.pickDate)
                QuickChips(QUICK_DAYS.map { (label, f) -> Triple(label, r.dueDate == f(today)) { quick.tools.pickDate(f(today)) } })
            } else {
                TimeWheel(r.dueTime, quick.tools.pickTime)
                QuickChips(
                    listOf(Triple("1시간 후", false, quick.tools.pickHourLater)) +
                        QUICK_TIMES.map { (label, t) -> Triple(label, r.dueTime == t) { quick.tools.pickTime(t) } },
                )
            }
        }
    }
}

@Composable
private fun DueTab(title: String, value: String, on: Boolean, modifier: Modifier, onClear: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(animatedColor(if (on) Wb.colors.surface else Wb.colors.card))
            .clickable(role = Role.Tab, onClick = onClick).semantics { selected = on }.padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = animatedColor(if (on) Wb.colors.primary else Wb.colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            onClear?.let { MinusButton("시각 빼기", it, Modifier.padding(start = 2.dp)) }
        }
    }
}

/** 삼성 리마인더의 ⊖: 흰 동그라미에 빨간 가로줄. touch = 누르는 자리, visual = 보이는 동그라미(칩 안에서는 칩 높이에 맞춰 작게) */
@Composable
fun MinusButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, touch: Dp = 36.dp, visual: Dp = 20.dp) {
    Box(
        modifier.size(touch).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(visual).clip(CircleShape).background(Wb.colors.card).border(1.dp, Wb.colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(visual / 2).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Wb.colors.crit)) }
    }
}

/** 달력: 일요일부터, 일 빨강·토 파랑, 오늘 테두리, 고른 날 파란 원, 앞뒤 달 흐리게 */
@Composable
private fun MonthGrid(selected: LocalDate?, today: LocalDate, onPick: (LocalDate) -> Unit) {
    var month by remember(selected) { mutableStateOf(YearMonth.from(selected ?: today)) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MonthArrow("‹", "이전 달") { month = month.minusMonths(1) }
        Text("${month.year}년 ${month.monthValue}월", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        MonthArrow("›", "다음 달") { month = month.plusMonths(1) }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        DOW.forEachIndexed { i, c ->
            Text(
                c.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = weekdayColor(i, Wb.colors.muted),
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            )
        }
    }
    AnimatedContent(month, transitionSpec = { quickFade() }, label = "month") { shown ->
        MonthDays(shown, selected, today, onPick)
    }
}

@Composable
private fun MonthDays(month: YearMonth, selected: LocalDate?, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
    val weeks = (first.dayOfWeek.value % 7 + month.lengthOfMonth() + 6) / 7
    Column {
        repeat(weeks) { w ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { i ->
                    val d = start.plusDays((w * 7 + i).toLong())
                    val isSel = d == selected
                    Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(animatedColor(if (isSel) Wb.colors.primary else Wb.colors.card))
                                .then(if (d == today && !isSel) Modifier.border(1.5.dp, Wb.colors.borderStrong, CircleShape) else Modifier)
                                .clickable(onClickLabel = "${dayLabel(d)} 고르기") { onPick(d) }
                                .alpha(if (YearMonth.from(d) == month) 1f else 0.35f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${d.dayOfMonth}", fontSize = 14.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = animatedColor(if (isSel) Wb.colors.onPrimary else weekdayColor(i, Wb.colors.ink)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun weekdayColor(index: Int, normal: Color) = when (index) {
    0 -> Wb.colors.crit
    6 -> Wb.colors.info
    else -> normal
}

@Composable
private fun MonthArrow(text: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 20.sp, color = Wb.colors.body) }
}

/** 바로 가기 알약(목업 .quick): (글자, 고른 것인지, 누를 때) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickChips(items: List<Triple<String, Boolean, () -> Unit>>) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, on, onClick) ->
            Box(
                Modifier.heightIn(min = 34.dp).clip(PillShape)
                    .background(if (on) Wb.colors.primary.copy(alpha = 0.08f) else Wb.colors.card, PillShape)
                    .border(1.dp, if (on) Wb.colors.primary else Wb.colors.borderStrong, PillShape)
                    .clickable(onClick = onClick).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Wb.colors.primary else Wb.colors.body) }
        }
    }
}

// ── 시간 휠: [오전/오후 | 시 | 분(5분 단위)], 가운데 줄이 고른 값. 멈추면 그 값으로 ──

private val WHEEL_ROW = 48.dp
private val AMPM = listOf("오전", "오후")
private val HOURS = (1..12).map { "$it" }
private val MINUTES = (0..55 step 5).map { "%02d".format(it) }

@Composable
private fun TimeWheel(time: LocalTime?, onPick: (LocalTime?) -> Unit) {
    // 시각이 없으면 오전 9:00에 놓고, 돌리면 그때 정해진다
    val t = time ?: LocalTime.of(9, 0)
    val pm = t.hour >= 12
    val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        WheelColumn(AMPM, if (pm) 1 else 0, Modifier.weight(1f), "오전 오후") { onPick(wheelTime(it == 1, h12, t.minute)) }
        WheelColumn(HOURS, h12 - 1, Modifier.weight(1f), "시") { onPick(wheelTime(pm, it + 1, t.minute)) }
        Text(":", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
        WheelColumn(MINUTES, wheelMinuteIndex(t), Modifier.weight(1f), "분") { onPick(wheelTime(pm, h12, it * 5)) }
    }
}

/**
 * 휠 한 칸: 위아래에 빈 줄을 두고 맨 위 줄에 맞춰 멈추게 해(SnapPosition.Start) 가운데 줄 = 첫 줄 번호.
 * 손으로 돌리다 멈추면 onSettled, 바깥에서 값이 바뀌면(바로 가기) 그 줄로 옮긴다
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelColumn(items: List<String>, selected: Int, modifier: Modifier, label: String, onSettled: (Int) -> Unit) {
    val rowPx = with(LocalDensity.current) { WHEEL_ROW.roundToPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val center by remember { derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0 } }
    val settled by rememberUpdatedState(onSettled)
    LaunchedEffect(selected) { if (!state.isScrollInProgress && center != selected) state.scrollToItem(selected) }
    LaunchedEffect(state) {
        var moved = false
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) moved = true
            else if (moved) {
                moved = false
                settled(center.coerceIn(items.indices))
            }
        }
    }
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
        modifier = modifier.height(WHEEL_ROW * 3).semantics { contentDescription = "$label ${items.getOrNull(center).orEmpty()}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { Spacer(Modifier.height(WHEEL_ROW)) }
        itemsIndexed(items) { i, s ->
            Box(Modifier.fillMaxWidth().height(WHEEL_ROW), contentAlignment = Alignment.Center) {
                val on = i == center
                Text(
                    s, fontSize = if (on) 28.sp else 20.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) Wb.colors.ink else Wb.colors.muted.copy(alpha = 0.6f),
                )
            }
        }
        item { Spacer(Modifier.height(WHEEL_ROW)) }
    }
}

// ── 하위 할 일: 한 줄씩, Enter = 다음 줄 ──

@Composable
private fun SubtaskPanel(quick: QuickAdd, draft: String, onDraft: (String) -> Unit) {
    PanelHead(WbIcons.Checklist, "하위 할 일")
    val lines = quick.tools.subtasks
    lines.forEachIndexed { i, s ->
        SubLine(
            value = s,
            placeholder = "",
            onChange = { v -> quick.tools.setSubtasks(lines.toMutableList().also { it[i] = v.take(MAX_SUBTASK_TITLE) }) },
            onEnter = {},
            onRemove = { quick.tools.setSubtasks(lines.toMutableList().also { it.removeAt(i) }) },
        )
    }
    if (lines.size < MAX_SUBTASKS) {
        SubLine(
            value = draft,
            placeholder = "하위 할 일 적기",
            onChange = { onDraft(it.take(MAX_SUBTASK_TITLE)) },
            // Enter: 적은 줄을 넣고 같은 칸을 비워 다음 줄을 이어 적는다
            onEnter = {
                if (draft.isNotBlank()) {
                    quick.tools.setSubtasks(lines + draft.trim())
                    onDraft("")
                }
            },
            onRemove = null,
        )
    }
    Text(
        "추가하면 이 할 일 아래에 하위 할 일로 같이 만들어요. 줄 끝에 '내일3시'·'@이름'을 적으면 기한·담당도 읽어요.",
        fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SubLine(value: String, placeholder: String, onChange: (String) -> Unit, onEnter: () -> Unit, onRemove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).border(1.5.dp, Wb.colors.borderStrong, CircleShape))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = Wb.colors.ink),
            cursorBrush = SolidColor(Wb.colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onEnter() }, onDone = { onEnter() }),
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 15.sp, color = Wb.colors.muted)
                    inner()
                }
            },
        )
        onRemove?.let {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "하위 할 일 빼기", onClick = it),
                contentAlignment = Alignment.Center,
            ) { Icon(WbIcons.Close, contentDescription = "빼기", tint = Wb.colors.muted, modifier = Modifier.size(16.dp)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Wb.colors.border))
}

// ── 어디에: 최근 → 프로젝트 → 반복실행 → 정리 안 됨. 고르면 닫힘 ──

@Composable
private fun PlacePanel(quick: QuickAdd, onDone: () -> Unit) {
    PanelHead(WbIcons.Folder, "어디에 넣을까요?")
    val current = quick.parsed.place
    val scope = rememberCoroutineScope()
    fun pick(p: Place?) {
        quick.tools.choosePlace(p)
        scope.launch {
            delay(PICK_PAUSE_MS)
            onDone()
        }
    }
    quick.tools.recent?.let {
        PlaceSection("최근")
        PlaceRow(placeIcon(it.type), it.name, it == current) { pick(it) }
    }
    val projects = quick.tools.places.filter { it.type == PlaceType.PROJECT }
    val areas = quick.tools.places.filter { it.type == PlaceType.AREA }
    if (projects.isNotEmpty()) PlaceSection("프로젝트")
    projects.forEach { p -> PlaceRow(WbIcons.Folder, p.name, p == current) { pick(p) } }
    if (areas.isNotEmpty()) PlaceSection("반복실행")
    areas.forEach { p -> PlaceRow(WbIcons.Repeat, p.name, p == current) { pick(p) } }
    PlaceSection("그 밖")
    PlaceRow(placeIcon(null), UNSORTED, current == null) { pick(null) }
}

@Composable
private fun PlaceSection(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun PlaceRow(icon: ImageVector, name: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .background(animatedColor(if (on) Wb.colors.primary.copy(alpha = 0.10f) else Wb.colors.card))
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = on }.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) Wb.colors.primary else Wb.colors.body, modifier = Modifier.size(18.dp))
        Text(
            name, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = Wb.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        if (on) Icon(WbIcons.Check, contentDescription = "고름", tint = Wb.colors.primary, modifier = Modifier.size(18.dp))
    }
}

// ── 우선순위: 줄 기호와 같게(DEC-103). 고르면 닫힘 ──

@Composable
private fun PriorityPanel(quick: QuickAdd, onDone: () -> Unit) {
    PanelHead(WbIcons.Star, "우선순위")
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple(1, "높음", WbIcons.Star), Triple(2, "보통", null), Triple(3, "낮음", WbIcons.PriorityLow)).forEach { (value, label, icon) ->
            val on = quick.parsed.priority == value
            Row(
                Modifier.heightIn(min = 44.dp).clip(PillShape)
                    .background(animatedColor(if (on) Wb.colors.pillActive else Wb.colors.card), PillShape)
                    .border(1.dp, animatedColor(if (on) Wb.colors.pillActive else Wb.colors.borderStrong), PillShape)
                    .clickable(role = Role.RadioButton) {
                        quick.tools.choosePriority(value)
                        scope.launch {
                            delay(PICK_PAUSE_MS)
                            onDone()
                        }
                    }
                    .semantics { selected = on }.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.let {
                    Icon(
                        it, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 2.dp),
                        tint = if (value == 1) Wb.colors.star else if (on) Wb.colors.onPillActive else Wb.colors.muted,
                    )
                }
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) Wb.colors.onPillActive else Wb.colors.ink, modifier = Modifier.padding(start = if (icon != null) 4.dp else 0.dp))
            }
        }
    }
}

// ── 칸 머리·아래 줄 ──

@Composable
private fun PanelHead(icon: ImageVector, text: String) {
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Wb.colors.ink, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 아래 줄: 반씩 나눈 넓은 [취소](열 때 값으로 되돌리고 닫기) · [완료](그대로 닫기) — 둘 다 키보드로 돌아간다(11/15 사용자) */
@Composable
private fun PanelFoot(onCancel: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.weight(1f).height(48.dp).clip(PillShape).background(Wb.colors.surface).border(1.dp, Wb.colors.border, PillShape)
                .clickable(onClickLabel = "고친 것을 되돌리고 칸 닫기", onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) { Text("취소", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink) }
        Box(
            Modifier.weight(1f).height(48.dp).clip(PillShape).background(Wb.colors.pillActive).clickable(onClickLabel = "칸 닫기", onClick = onDone),
            contentAlignment = Alignment.Center,
        ) { Text("완료", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.onPillActive) }
    }
}

/** 단축어 설명 한 줄(DEC-107 ②): 줄을 바꾸지 않고, 좁으면 옆으로 밀어 본다 */
@Composable
fun QuickHintLine(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QUICK_HINTS.forEach { (token, meaning) ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Wb.colors.body)) { append(token) }
                    append(" $meaning")
                },
                fontSize = 12.sp, color = Wb.colors.muted, maxLines = 1,
            )
        }
    }
}

/** 설명 줄: 단축어 → 뜻. '마이크 길게'·'띄어 쓰기' 안내는 뺐다(11/15 사용자) */
private val QUICK_HINTS = listOf("#프로젝트" to "넣을 곳", "!높음·!낮음" to "우선순위", "@이름" to "팔로업", "내일3시·금요일·10/24" to "기한")
