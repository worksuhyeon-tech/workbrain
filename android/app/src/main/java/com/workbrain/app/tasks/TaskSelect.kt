package com.workbrain.app.tasks

import android.content.Context
import androidx.compose.foundation.BorderStroke
import com.workbrain.app.ui.PillShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskRow
import com.workbrain.app.data.toEdit
import com.workbrain.app.ui.CardShape
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * 할 일 목록 오른쪽 위 ⋮ 메뉴와 '선택' 모드(DEC-92, 갤럭시 '내 파일' 앱 참고).
 * ⋮ › 선택 → 줄 앞 체크가 '고르기'로 바뀌고, 위에는 'N개 선택 · 전체 · 취소', 아래에는 빠른 입력 띠 대신 [이동][삭제].
 * 오늘 탭과 프로젝트 탭(모든 할 일·프로젝트/반복실행 상세)이 같이 쓴다. 정렬 방식도 이 메뉴에서 고른다
 */

/** 할 일 정렬(DEC-92). 우선순위순이 목업 기본이고, 기한순은 날짜·시각이 이른 것부터 */
enum class TaskOrder(val code: String, val label: String) {
    PRIORITY("priority", "우선순위순"),
    DUE("due", "기한순"),
}

/** 시각까지 있는 기한은 그 순간, 날짜만 있으면 그날 맨 끝으로 본다(같은 날이면 시각 있는 일이 먼저) */
private fun dueTimeKey(t: TaskRow): Long =
    t.dueTime?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() } ?: Long.MAX_VALUE

/** 정렬 기준(완료를 뒤로 보내는 것은 부르는 쪽). 기한이 없거나 모호한 할 일은 어느 순서든 맨 뒤 */
fun taskComparator(order: TaskOrder): Comparator<TaskRow> = when (order) {
    TaskOrder.PRIORITY -> compareBy({ it.priority ?: 2 }, { dueDay(it) ?: LocalDate.MAX }, { dueTimeKey(it) }, { it.title })
    TaskOrder.DUE -> compareBy({ dueDay(it) ?: LocalDate.MAX }, { dueTimeKey(it) }, { it.priority ?: 2 }, { it.title })
}

/** 고른 정렬은 기기에 하나 두고 오늘·프로젝트 탭이 같이 본다. 바꾸면 보고 있는 화면이 바로 다시 그려지게 Compose 상태로 둔다 */
object TaskSort {
    private const val PREFS = "task_list"
    private const val KEY = "order"
    private var changed by mutableStateOf<TaskOrder?>(null)

    fun order(context: Context): TaskOrder =
        changed ?: prefs(context).getString(KEY, null).let { code -> TaskOrder.entries.firstOrNull { it.code == code } } ?: TaskOrder.PRIORITY

    fun set(context: Context, order: TaskOrder) {
        changed = order
        prefs(context).edit().putString(KEY, order.code).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** 지금 고른 정렬 기준(화면에서 읽으면 정렬을 바꿀 때 다시 그려진다) */
@Composable
fun rememberTaskComparator(): Comparator<TaskRow> {
    val order = TaskSort.order(LocalContext.current)
    return remember(order) { taskComparator(order) }
}

/**
 * 선택 모드 상태. 접기·펼치기·회전에도 남는다(NFR-05).
 * visible: 지금 화면에 보이는 할 일 ID(전체 선택 대상). 목록을 그리는 쪽이 SideEffect로 채운다
 */
@Stable
class TaskSelection(active: Boolean, ids: Set<String>) {
    var active by mutableStateOf(active)
        private set
    var ids by mutableStateOf(ids)
        private set
    var visible by mutableStateOf<List<String>>(emptyList())

    val allSelected: Boolean get() = visible.isNotEmpty() && ids.containsAll(visible)

    fun start() { active = true; ids = emptySet() }
    fun stop() { active = false; ids = emptySet() }
    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun toggleAll() { ids = if (allSelected) ids - visible.toSet() else ids + visible }

    companion object {
        val Saver = listSaver<TaskSelection, String>(
            save = { listOf(if (it.active) "1" else "0") + it.ids },
            restore = { TaskSelection(it.first() == "1", it.drop(1).toSet()) },
        )
    }
}

@Composable
fun rememberTaskSelection(): TaskSelection = rememberSaveable(saver = TaskSelection.Saver) { TaskSelection(false, emptySet()) }

/** 메뉴 한 줄 높이·좌우 여백(기본 48dp·12dp + 최소 폭 200dp는 항목 셋에 비해 너무 컸음, 11/12 사용자) */
private val MENU_ROW_HEIGHT = 40.dp
private val MENU_ROW_PADDING = PaddingValues(horizontal = 16.dp)

/**
 * 오른쪽 위 ⋮(갤럭시 '내 파일'처럼). onSelect가 null이면 '선택'을 뺀다(할 일 목록이 안 보이는 화면).
 * 정렬은 한 줄에 아이콘 둘(깃발 = 우선순위순, 달력 = 기한순)을 두고 지금 것을 진하게, 누르면 바로 바뀐다
 * (11/12 사용자: '우선순위순' 글자가 자리를 많이 차지하고 작은 목록을 한 번 더 열어야 했음).
 * more: 화면마다 더하는 항목(오늘 탭: 업무계획). 누르면 메뉴를 닫고 부른다
 */
@Composable
fun TaskListMenu(onSelect: (() -> Unit)?, more: List<Pair<String, () -> Unit>> = emptyList()) {
    val context = LocalContext.current
    val order = TaskSort.order(context)
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(WbIcons.More, contentDescription = "더보기 메뉴", tint = Wb.colors.ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onSelect?.let { select -> MenuRow("선택") { open = false; select() } }
            Row(
                Modifier.height(MENU_ROW_HEIGHT).padding(start = 16.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("정렬", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 20.dp))
                SortIcon(WbIcons.Flag, TaskOrder.PRIORITY, order) { open = false; TaskSort.set(context, it) }
                SortIcon(WbIcons.Calendar, TaskOrder.DUE, order) { open = false; TaskSort.set(context, it) }
            }
            more.forEach { (label, action) -> MenuRow(label) { open = false; action() } }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        contentPadding = MENU_ROW_PADDING,
        modifier = Modifier.height(MENU_ROW_HEIGHT),
    )
}

/** 정렬 아이콘 하나: 지금 기준이면 진한 알약 바탕. 읽어 주기는 '우선순위순 정렬, 선택됨'처럼 */
@Composable
private fun SortIcon(icon: ImageVector, value: TaskOrder, current: TaskOrder, onPick: (TaskOrder) -> Unit) {
    val on = value == current
    Box(
        Modifier.size(34.dp).clip(PillShape)
            .background(if (on) Wb.colors.pillActive else Color.Transparent)
            .selectable(selected = on, role = Role.RadioButton) { onPick(value) }
            .semantics { contentDescription = "${value.label} 정렬" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) Wb.colors.onPillActive else Wb.colors.muted, modifier = Modifier.size(18.dp))
    }
}

/** 선택 모드 머리 줄: [ ] 전체 · 'N개 선택' · 취소. 화면 제목 줄 자리에 둔다 */
@Composable
fun SelectionHeader(selection: TaskSelection, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val canAll = selection.visible.isNotEmpty()
        Row(
            Modifier.clip(ControlShape).clickable(enabled = canAll, onClickLabel = "전체 선택") { selection.toggleAll() }.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selection.allSelected, onCheckedChange = { selection.toggleAll() }, enabled = canAll,
                colors = CheckboxDefaults.colors(checkedColor = Wb.colors.primary, uncheckedColor = Wb.colors.borderStrong),
            )
            Text("전체", fontSize = 13.sp, color = Wb.colors.muted)
        }
        Text(
            if (selection.ids.isEmpty()) "할 일 선택" else "${selection.ids.size}개 선택",
            style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextButton(onClick = selection::stop) { Text("취소", color = Wb.colors.link) }
    }
}

/** 선택 모드 아래 막대(빠른 입력 띠 자리): [이동] [삭제]. 아무것도 안 골랐으면 눌리지 않는다 */
@Composable
fun SelectionBar(count: Int, onMove: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 640.dp).fillMaxWidth(),
        shape = CardShape,
        color = Wb.colors.card,
        border = BorderStroke(1.dp, Wb.colors.border),
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BarAction(WbIcons.Folder, "이동", count > 0, Wb.colors.ink, onMove, Modifier.weight(1f))
            BarAction(WbIcons.Trash, "삭제", count > 0, Wb.colors.crit, onDelete, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean, tint: Color, onClick: () -> Unit, modifier: Modifier) {
    val color = if (enabled) tint else Wb.colors.muted.copy(alpha = 0.5f)
    Column(
        modifier.height(56.dp).clip(ControlShape).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 옮길 곳 고르기: 정리 안 됨 + 1. 프로젝트 / 2. 반복실행(할 일 수정 서랍 '어디에'와 같은 묶음) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveTargetSheet(count: Int, places: List<Place>, onDismiss: () -> Unit, onPick: (Place?) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x6609141E),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                "할 일 ${count}개를 어디로 옮길까요?", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            TargetRow(null, UNSORTED) { onPick(null) }
            listOf(PlaceType.PROJECT to "1. 프로젝트", PlaceType.AREA to "2. 반복실행").forEach { (kind, heading) ->
                val group = places.filter { it.type == kind }
                if (group.isNotEmpty()) {
                    HorizontalDivider(color = Wb.colors.border, modifier = Modifier.padding(top = 4.dp))
                    Text(heading, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    group.forEach { p -> TargetRow(p.type, p.name) { onPick(p) } }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(type: PlaceType?, name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(placeIcon(type), contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
        Text(name, fontSize = 15.sp, color = Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}

/** 여러 할 일 옮긴 결과. moved: 옮긴 것(옮기기 전 모습, 되돌리기용), conflicts: 다른 기기에서 먼저 바뀌어 건너뛴 수 */
class MoveResult(val moved: List<TaskRow>, val conflicts: Int, val failed: Int)

/**
 * 고른 할 일을 한 곳으로 옮긴다. 한 건씩 버전을 확인하며 저장하고(M18), 이미 그곳에 있는 할 일은 건너뛴다.
 * 옮긴 곳은 다음 빠른 입력의 넣을 곳으로 기억한다(DEC-91)
 */
suspend fun moveTasks(context: Context, tasks: List<TaskRow>, to: Place?): MoveResult {
    val projectId = to?.id?.takeIf { to.type == PlaceType.PROJECT }
    val areaId = to?.id?.takeIf { to.type == PlaceType.AREA }
    val moved = mutableListOf<TaskRow>()
    var conflicts = 0
    var failed = 0
    tasks.filter { it.projectId != projectId || it.areaId != areaId }.forEach { t ->
        runCatching { Server.updateTask(t, t.toEdit().copy(projectId = projectId, areaId = areaId)) }
            .onSuccess { row -> if (row != null) moved += t else conflicts++ }
            .onFailure { failed++ }
    }
    if (moved.isNotEmpty()) QuickAddMemory.save(context, to)
    return MoveResult(moved, conflicts, failed)
}

/** 옮기기 되돌리기: 지금 버전을 다시 읽어 원래 곳으로 */
suspend fun moveTasksBack(original: List<TaskRow>) {
    original.forEach { t ->
        runCatching { Server.task(t.id)?.let { cur -> Server.updateTask(cur, cur.toEdit().copy(projectId = t.projectId, areaId = t.areaId)) } }
    }
}

/** 옮긴 뒤 알림 문구. 건너뛴 것이 있으면 이유를 붙인다 */
fun movedManyText(r: MoveResult, to: Place?): String {
    val where = to?.name ?: UNSORTED
    val head = if (r.moved.isEmpty()) "옮긴 할 일이 없어요" else "할 일 ${r.moved.size}개를 ‘$where’(으)로 옮겼어요"
    val notes = buildList {
        if (r.conflicts > 0) add("${r.conflicts}개는 다른 기기에서 먼저 바뀌어 건너뜀")
        if (r.failed > 0) add("${r.failed}개는 저장 못 함 · 연결 확인")
    }
    return (listOf(head) + notes).joinToString(" · ")
}

/** 지운 뒤 알림 문구: 한 개면 제목, 여러 개면 개수 */
fun deletedText(tasks: List<TaskRow>, subtasks: Int = 0): String =
    (if (tasks.size == 1) "${tasks.first().title} 삭제되었어요" else "할 일 ${tasks.size}개 삭제되었어요") +
        // 상위를 지우면 하위도 함께 지워진다(FR-23a, 서버 cascade)
        if (subtasks > 0) " · 하위 ${subtasks}개 포함" else ""
