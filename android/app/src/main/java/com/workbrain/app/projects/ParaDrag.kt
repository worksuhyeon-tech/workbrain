package com.workbrain.app.projects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.ParaData
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.Note
import com.workbrain.app.ui.ParaGlyph
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/*
 * 길게 눌러 끌어서 옮기기(DEC-24, DEC-72, 오늘 탭 DEC-93) — 화면 쪽.
 * 무엇을 어디에 놓을 수 있는지는 DropRules.kt, 놓은 뒤 서버 호출·알림·되돌리기는 ProjectsScreen·TodayScreen이 맡는다.
 * 좌표는 모두 화면 루트 기준이라 트리(왼쪽)·상세(오른쪽)·'옮길 곳' 띠가 한 상태를 같이 본다.
 */

/**
 * 끄는 동안의 상태. onDrop은 놓을 수 있는 곳에서 손을 뗐을 때만 불린다.
 * onBlocked는 놓을 수 없는 곳(이미 연결된 곳·지금 있는 곳 등)에서 뗐을 때 그 이유(blockReason)를 받는다
 */
@Stable
class ParaDragState(private val onDrop: (DropAction) -> Unit, private val onBlocked: (String) -> Unit) {
    /** 놓기 규칙을 따질 최신 데이터(ProjectsScreen이 읽을 때마다 넣는다) */
    var data: ParaData? by mutableStateOf(null)
    var item: DragItem? by mutableStateOf(null)
        private set
    var pointer: Offset by mutableStateOf(Offset.Unspecified)
        private set

    private val targets = mutableStateMapOf<DropKey, Rect>()
    // 자동 스크롤 칸은 그릴 때 읽지 않으므로 상태로 둘 필요가 없다
    private val zones = mutableMapOf<String, Pair<Rect, ScrollState>>()

    /** 손가락 아래에서 놓을 수 있는 곳과 그때 할 일. 끄지 않을 땐 아무것도 읽지 않아 줄 위치가 바뀌어도 다시 그리지 않는다 */
    private val hotDrop by derivedStateOf {
        val i = item ?: return@derivedStateOf null
        val d = data ?: return@derivedStateOf null
        val p = pointer.takeIf { it.isSpecified } ?: return@derivedStateOf null
        targets.entries.firstOrNull { it.value.contains(p) }?.let { (key, _) -> dropAction(d, i, key)?.let { key to it } }
    }

    /** 놓을 수 있는 곳 위에 있을 때 그 열쇠(목업 .drop-ok). 놓을 수 없는 곳은 강조하지 않는다 */
    val hot: DropKey? get() = hotDrop?.first

    fun isDragging(key: String): Boolean = item?.key == key

    internal fun start(i: DragItem, at: Offset) {
        item = i
        pointer = at
    }

    internal fun move(at: Offset) {
        pointer = at
    }

    internal fun end() {
        val action = hotDrop?.second
        val reason = if (action == null) blockedHere() else null
        clear()
        if (action != null) onDrop(action) else reason?.let(onBlocked)
    }

    /** 손가락 아래가 놓는 곳이긴 한데 놓을 수 없을 때 그 이유. 놓는 곳이 아닌 빈 곳이면 null(조용히 취소) */
    private fun blockedHere(): String? {
        val i = item ?: return null
        val d = data ?: return null
        val p = pointer.takeIf { it.isSpecified } ?: return null
        return targets.entries.firstOrNull { it.value.contains(p) }?.let { blockReason(d, i, it.key) }
    }

    internal fun cancel() = clear()

    private fun clear() {
        item = null
        pointer = Offset.Unspecified
    }

    internal fun place(key: DropKey, r: Rect) {
        if (targets[key] != r) targets[key] = r
    }

    internal fun forget(key: DropKey) {
        targets.remove(key)
    }

    internal fun zone(id: String, r: Rect, s: ScrollState) {
        zones[id] = r to s
    }

    internal fun forgetZone(id: String) {
        zones.remove(id)
    }

    /**
     * 한 프레임만큼 자동 스크롤: 손가락이 스크롤 칸 위·아래 가장자리(edgePx) 안에 있으면 그쪽으로 민다.
     * 가장자리에 가까울수록 빠르다(최대 maxStepPx)
     */
    suspend fun autoScroll(edgePx: Float, maxStepPx: Float) {
        val p = pointer.takeIf { it.isSpecified } ?: return
        zones.values.toList().forEach { (r, s) ->
            if (p.x < r.left || p.x >= r.right || p.y < r.top - edgePx || p.y > r.bottom + edgePx) return@forEach
            val step = when {
                p.y < r.top + edgePx -> -maxStepPx * ((r.top + edgePx - p.y) / edgePx).coerceAtMost(1f)
                p.y > r.bottom - edgePx -> maxStepPx * ((p.y - (r.bottom - edgePx)) / edgePx).coerceAtMost(1f)
                else -> 0f
            }
            if (step != 0f) s.scrollBy(step)
        }
    }
}

/** 줄의 지금 위치(누를 때마다 루트 좌표로 바꾸는 데 쓴다) */
private class CoordsRef {
    var value: LayoutCoordinates? = null
}

/** 따라오는 이름 칩을 그리는 상자의 루트 좌표. 그릴 때만 읽으므로 상태로 두지 않는다 */
class OriginRef {
    var value: Offset = Offset.Zero
}

/**
 * 길게 눌러 끄는 줄(item이 null이면 끌지 않음). 끄는 동안 그 줄은 흐려진다(목업 .is-dragging).
 * 누르기(열기·체크)와 스크롤은 길게 누르기 전까지 그대로 둔다
 */
fun Modifier.dragSource(state: ParaDragState, item: DragItem?): Modifier = if (item == null) this else composed {
    val haptic = LocalHapticFeedback.current
    val coords = remember { CoordsRef() }
    val current by rememberUpdatedState(item)
    this
        .onGloballyPositioned { coords.value = it }
        .graphicsLayer { alpha = if (state.isDragging(current.key)) 0.45f else 1f }
        .pointerInput(state, item.key) {
            longPressDrag(
                onStart = { local ->
                    coords.value?.let { c ->
                        state.start(current, c.localToRoot(local))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onMove = { local -> coords.value?.let { state.move(it.localToRoot(local)) } },
                onEnd = state::end,
                onCancel = state::cancel,
            )
        }
}

/**
 * 길게 누른 뒤 끌기. 기본 detectDragGesturesAfterLongPress와 달리 끄는 동안의 사건을 처음 단계(Initial)에서 소비해,
 * 손을 뗄 때 줄 누르기(할 일 열기·체크·트리 고르기)가 같이 일어나지 않게 한다.
 * 길게 누른 뒤 거의 움직이지 않고 떼면 놓지 않는다(띠가 손가락 아래에 올라와 실수로 놓는 일 방지)
 */
private suspend fun PointerInputScope.longPressDrag(
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        var last = down.position
        // 길게 누르기 전: 아무것도 소비하지 않는다. 떼거나 움직이면(스크롤) 끌기가 아니다 → true
        val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var gone = false
            while (!gone) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                gone = c == null || !c.pressed || c.isConsumed || (c.position - down.position).getDistance() > slop
                if (!gone && c != null) last = c.position
            }
            true
        }
        if (endedEarly == true) return@awaitEachGesture

        onStart(last)
        val startAt = last
        var travelled = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                val c = event.changes.firstOrNull { it.id == down.id }
                if (c == null) { onCancel(); break }
                if (!c.pressed) { if (travelled) onEnd() else onCancel(); break }
                if ((c.position - startAt).getDistance() > slop) travelled = true
                onMove(c.position)
            }
        } catch (e: CancellationException) {
            onCancel()
            throw e
        }
    }
}

/** 놓는 곳(key가 null이면 아님): 화면에서의 위치를 상태에 알린다 */
fun Modifier.dropTarget(state: ParaDragState, key: DropKey?): Modifier = if (key == null) this else composed {
    DisposableEffect(state, key) { onDispose { state.forget(key) } }
    onGloballyPositioned { state.place(key, it.boundsInRoot()) }
}

/** 끄는 동안 저절로 스크롤할 칸. verticalScroll보다 앞에 둬야 보이는 칸 크기를 잰다 */
fun Modifier.autoScrollZone(state: ParaDragState, id: String, scroll: ScrollState): Modifier = composed {
    DisposableEffect(state, id) { onDispose { state.forgetZone(id) } }
    onGloballyPositioned { state.zone(id, it.boundsInRoot(), scroll) }
}

/** 끄는 동안 매 프레임 스크롤 칸(트리·'옮길 곳' 띠) 가장자리를 보고 저절로 스크롤한다(프로젝트·오늘 탭 공용) */
@Composable
fun DragAutoScroll(state: ParaDragState) {
    val density = LocalDensity.current
    val dragging = state.item != null
    LaunchedEffect(state, dragging) {
        if (!dragging) return@LaunchedEffect
        val edge = with(density) { 48.dp.toPx() }
        val step = with(density) { 14.dp.toPx() }
        while (true) {
            withFrameNanos { }
            state.autoScroll(edge, step)
        }
    }
}

/** 목업 .drop-ok: 2px 점선 테두리 + 옅은 파랑 바탕 */
fun Modifier.dropHighlight(on: Boolean, color: Color, corner: Float = 4f): Modifier = if (!on) this else drawBehind {
    val r = CornerRadius(corner.dp.toPx())
    drawRoundRect(color = color.copy(alpha = 0.10f), cornerRadius = r)
    val w = 2.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = size.copy(width = size.width - w, height = size.height - w),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        cornerRadius = r,
    )
}

/**
 * 손가락 위로 따라오는 이름 칩(브라우저가 그려 주는 끌기 그림 대신). 손가락에 가리지 않게 왼쪽 위로 띄운다.
 * origin: 이 칩을 그리는 상자의 루트 좌표(손가락 좌표를 상자 안 좌표로 바꾼다)
 */
@Composable
fun DragGhost(state: ParaDragState, origin: () -> Offset) {
    val item = state.item ?: return
    val icon = when (item) {
        is DragItem.Project -> ParaGlyph.project()
        is DragItem.Record -> WbIcons.Mic
        is DragItem.Resource -> ParaGlyph.resource(state.data?.resource(item.id)?.kind.orEmpty())
        is DragItem.Task -> WbIcons.Check
    }
    Surface(
        modifier = Modifier
            .offset {
                val p = state.pointer
                if (!p.isSpecified) return@offset IntOffset.Zero
                val o = origin()
                IntOffset((p.x - o.x - 16.dp.toPx()).coerceAtLeast(0f).roundToInt(), (p.y - o.y - 64.dp.toPx()).coerceAtLeast(0f).roundToInt())
            }
            .widthIn(max = 260.dp),
        shape = ControlShape,
        color = Wb.colors.card,
        border = BorderStroke(1.dp, Wb.colors.primary),
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Wb.colors.primary, modifier = Modifier.size(16.dp))
            Text(
                item.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * '옮길 곳' 띠(DEC-72 ①, 목업에 없음): 접은 프로젝트 화면은 트리가 다른 화면이고 오늘 탭은 트리가 없어서(DEC-93), 할 일을 끄는 동안 아래에서 올라온다.
 * 보관 안 된 프로젝트·반복실행 이름 칩(지금 있는 곳 빼고 이름순). 높이는 화면 절반까지, 넘치면 띠 안에서 스크롤
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoveTargetsPanel(state: ParaDragState, modifier: Modifier = Modifier) {
    val task = state.item as? DragItem.Task
    val d = state.data
    // 닫히는 동안에도 마지막 내용을 그린다(끄기가 끝나면 item은 바로 비워진다)
    val last = remember { LastTask() }
    if (task != null) last.value = task
    AnimatedVisibility(
        visible = task != null && d != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val t = last.value ?: return@AnimatedVisibility
        val data = d ?: return@AnimatedVisibility
        val targets = data.places.filter { dropAction(data, t, DropKey.Node(it.type, it.id)) != null }
        val scroll = rememberScrollState()
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Wb.colors.card,
            border = BorderStroke(1.dp, Wb.colors.border),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp / 2).dp)
                    .autoScrollZone(state, "move-targets", scroll)
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("여기에 놓으면 옮겨요", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                Text(
                    "‘${t.label}’ · 띠 밖에서 손을 떼면 취소돼요", fontSize = 12.sp, color = Wb.colors.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (targets.isEmpty()) Note("옮길 곳이 없어요 · 프로젝트나 반복실행을 먼저 만들어 주세요")
                listOf(PlaceType.PROJECT to ParaSection.PROJECTS.label, PlaceType.AREA to ParaSection.AREAS.label).forEach { (type, label) ->
                    val group = targets.filter { it.type == type }
                    if (group.isEmpty()) return@forEach
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { TargetChip(state, it) }
                    }
                }
            }
        }
    }
}

private class LastTask {
    var value: DragItem.Task? = null
}

@Composable
private fun TargetChip(state: ParaDragState, place: Place) {
    val key = DropKey.Node(place.type, place.id)
    val hot = state.hot == key
    Row(
        Modifier
            .dropTarget(state, key)
            .clip(ControlShape)
            .background(Wb.colors.surface)
            .then(if (hot) Modifier.dropHighlight(true, Wb.colors.primary, corner = 8f) else Modifier.border(1.dp, Wb.colors.border, ControlShape))
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ParaGlyph.place(place.type), contentDescription = null, tint = ParaGlyph.tint(place.type), modifier = Modifier.size(16.dp))
        Text(
            place.name, fontSize = 14.sp, fontWeight = if (hot) FontWeight.Bold else FontWeight.SemiBold, color = Wb.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
        )
    }
}
