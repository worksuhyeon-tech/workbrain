package com.workbrain.app.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.data.Budget
import com.workbrain.app.data.InboxRow
import com.workbrain.app.data.Para
import com.workbrain.app.data.ParaData
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.toEdit
import com.workbrain.app.data.TaskRow
import com.workbrain.app.data.PlanKind
import com.workbrain.app.data.TaskSource
import com.workbrain.app.data.usedNamesOf
import com.workbrain.app.projects.DragAutoScroll
import com.workbrain.app.projects.DragGhost
import com.workbrain.app.projects.DragItem
import com.workbrain.app.projects.DropAction
import com.workbrain.app.projects.MoveTargetsPanel
import com.workbrain.app.projects.OriginRef
import com.workbrain.app.projects.ParaDragState
import com.workbrain.app.projects.dragSource
import com.workbrain.app.record.RecordingSession
import com.workbrain.app.record.RecordingStore
import com.workbrain.app.record.UploadManager
import com.workbrain.app.record.UploadProgress
import com.workbrain.app.review.Snooze
import com.workbrain.app.tasks.QUICK_DOCK_SPACE
import com.workbrain.app.tasks.QuickAddDock
import com.workbrain.app.tasks.QuickAddMemory
import com.workbrain.app.tasks.RecipientsSheet
import com.workbrain.app.tasks.MoveTargetSheet
import com.workbrain.app.tasks.SelectionBar
import com.workbrain.app.tasks.SelectionHeader
import com.workbrain.app.tasks.TaskDeletes
import com.workbrain.app.search.SearchButton
import com.workbrain.app.tasks.TaskListMenu
import com.workbrain.app.tasks.TaskSelection
import com.workbrain.app.tasks.deletedText
import com.workbrain.app.tasks.moveTasks
import com.workbrain.app.tasks.moveTasksBack
import com.workbrain.app.tasks.movedManyText
import com.workbrain.app.tasks.rememberTaskComparator
import com.workbrain.app.tasks.rememberTaskSelection
import com.workbrain.app.tasks.TaskEditSheet
import com.workbrain.app.tasks.quickAddedText
import com.workbrain.app.tasks.quickDueLabel
import com.workbrain.app.tasks.TaskLine
import com.workbrain.app.tasks.UNSAVED_TEXT
import com.workbrain.app.tasks.doneInstant
import com.workbrain.app.tasks.dueDay
import com.workbrain.app.tasks.isLate
import com.workbrain.app.tasks.movedText
import com.workbrain.app.ui.AnnounceHost
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.CONFLICT_TEXT
import com.workbrain.app.ui.HandAccent
import com.workbrain.app.ui.MockCard
import com.workbrain.app.ui.Note
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.SmallButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.isWideWindow
import com.workbrain.app.ui.rememberAnnouncer
import com.workbrain.app.ui.saveFailText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 오늘 화면에서 다른 탭으로 가는 길(목업 링크: 모든 할 일 · 기록 · 검토함 · 프로젝트 · 설정) */
class TodayNav(
    val openRecord: (recordId: String, title: String) -> Unit,
    val openReview: () -> Unit,
    val openRecords: () -> Unit,
    /** 프로젝트 탭에서 이 열쇠를 연다('all' = 모든 할 일, 'p:ID' = 프로젝트) */
    val openProjects: (String?) -> Unit,
    val openMore: () -> Unit,
    /** 더보기의 업무계획을 그 종류로 바로 연다(화요일 카드, FR-33) */
    val openWorkPlan: (PlanKind) -> Unit,
    /** 검색 화면(FR-13, 오른쪽 위 🔍) */
    val openSearch: () -> Unit,
)

private val HEAD_DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREA)

/** 기록별로 묶은 검토 대기(목업 '검토 대기': 새 할 일 후보는 기록 화면에서, 변경은 검토함에서) */
private data class Pending(val recordId: String, val title: String, val day: String?, val newCount: Int, val changes: Map<String, Int>, val expired: Boolean)

/**
 * S02 오늘 — 목업 workbrain-dashboard.html 모양(DEC-67).
 * 왼쪽(접힌 화면은 위) '오늘 할 일': 모든 프로젝트·반복실행에서 오늘 마감 + 지연, 남이 맡은 일은 '챙길 팔로업'으로 나눈다.
 *   오늘 끝낸 일은 맨 아래 '오늘 완료'에 취소선으로 남긴다(DEC-64, 목업에 더한 것). 그 밖의 할 일은 '모든 할 일'에서 본다.
 * 오른쪽(접힌 화면은 아래) '검토 대기'(기록별, 승인 버튼 없음)와 '처리 중인 기록'.
 * 한 줄 빠른 입력은 화면 아래 떠 있는 띠(기한을 안 적으면 오늘 마감, DEC-70·73). 비용이 경고선을 넘을 때만 위에 띠.
 */
@Composable
fun TodayScreen(nav: TodayNav) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val wide = isWideWindow()
    val uploads by UploadManager.progress.collectAsStateWithLifecycle()

    var tasks by remember { mutableStateOf<List<TaskRow>?>(null) }
    var sources by remember { mutableStateOf<Map<String, TaskSource>>(emptyMap()) }
    var pending by remember { mutableStateOf<List<InboxRow>>(emptyList()) }
    var budget by remember { mutableStateOf<Budget?>(null) }
    var cost by remember { mutableStateOf<Int?>(null) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    // 끌어 놓기 규칙(DropRules)이 보는 데이터. 출처·옮길 곳도 여기서 꺼낸다
    var para by remember { mutableStateOf<ParaData?>(null) }
    var sessions by remember { mutableStateOf<List<RecordingSession>>(emptyList()) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var editing by remember { mutableStateOf<TaskRow?>(null) }
    var recipientsEditing by remember { mutableStateOf<TaskRow?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 선택해서 옮기기·지우기 뒤 '되돌리기'(기록 탭과 같은 알림, DEC-92). 그 밖의 결과는 위 띠(message)로 알린다
    val announcer = rememberAnnouncer()
    // ⋮ › 선택(DEC-92): 고른 할 일을 한꺼번에 옮기거나 지운다
    val selection = rememberTaskSelection()
    var moving by remember { mutableStateOf(false) }

    // 할 일이 핵심이라 그것만 실패를 알리고, 나머지(출처·검토 대기·비용·옮길 곳)는 못 읽으면 비워 둔다
    suspend fun load() = coroutineScope {
        val t = async { runCatching { Server.todayTasks() } }
        val pd = async { runCatching { Para.load() }.getOrNull() }
        val pend = async { runCatching { Server.pendingProposals() }.getOrNull() }
        val b = async { runCatching { Server.budget() }.getOrNull() }
        val c = async { runCatching { Server.monthCostKrw() }.getOrNull() }
        t.await().onSuccess { tasks = it }.onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
        pd.await()?.let { d -> para = d; sources = d.sources; places = d.places }
        // 기록별 검토 대기는 할 일 후보만 센다. 프로젝트 현재 상태 제안(FR-09)은 프로젝트 상세·검토함에서 본다
        pend.await()?.let { pending = it.filterNot { r -> r.isState } }
        budget = b.await()
        cost = c.await()
        sessions = RecordingStore.list(context, null)
    }
    LaunchedEffect(Unit) { load() }

    fun setDone(t: TaskRow, done: Boolean) = scope.launch {
        val ok = runCatching { Server.setTaskDone(t, done) }.getOrNull()
        message = when (ok) {
            true -> (if (done) "완료했어요 · 아래 ‘오늘 완료’에 남겨 둘게요" else "다시 열었어요 · ${t.title}") to Tone.Good
            false -> CONFLICT_TEXT to Tone.Warn
            null -> "저장하지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
        }
        load()
    }

    /** 서랍 저장. 실패하면 서랍을 닫지 않고 입력을 그대로 둔 채 띠를 보여 준다(목업 '미저장 표시') */
    fun save(t: TaskRow, e: TaskEdit) = scope.launch {
        saving = true
        val moved = e.projectId != t.projectId || e.areaId != t.areaId
        val result = runCatching { Server.updateTask(t, e) }
        saving = false
        result.onSuccess { row ->
            editing = null
            recipientsEditing = null
            val to = places.firstOrNull { it.id == (e.projectId ?: e.areaId) }
            // 옮긴 곳을 다음 빠른 입력의 넣을 곳으로 기억한다(DEC-91)
            if (row != null && moved) QuickAddMemory.save(context, to)
            message = when {
                row == null -> CONFLICT_TEXT to Tone.Warn
                moved -> movedText(to) to Tone.Good
                else -> "수정했어요 · 변경 이력에 남아요" to Tone.Good
            }
            load()
        }.onFailure {
            editError = if (it.message.orEmpty().contains("아카이브")) saveFailText(it) else UNSAVED_TEXT
        }
    }

    val deleteFailed by TaskDeletes.failed.collectAsStateWithLifecycle()
    LaunchedEffect(deleteFailed) {
        val text = deleteFailed ?: return@LaunchedEffect
        TaskDeletes.consumeFailure()
        message = text to Tone.Warn
        load()
    }

    val today = LocalDate.now(SEOUL)
    val hidden by TaskDeletes.hidden.collectAsStateWithLifecycle()
    val all = tasks.orEmpty().filter { it.id !in hidden }
    // 오늘 할 일 = 오늘 마감 + 지연(목업 dueToday). 기한 없는·모호한·다음 날 이후 할 일은 '모든 할 일'에서
    val due = all.filter { !it.done && dueDay(it)?.let { d -> !d.isAfter(today) } == true }
    val mine = due.filter { it.ownerKind != "other" }
    // 정렬은 ⋮ › 정렬 방식(우선순위순·기한순, DEC-92)
    val order = rememberTaskComparator()
    val late = mine.filter { isLate(it, today) }.sortedWith(order)
    val todays = mine.filter { !isLate(it, today) }.sortedWith(order)
    val follow = due.filter { it.ownerKind == "other" }.sortedWith(order)
    val doneToday = all.filter { it.done }.sortedByDescending { doneInstant(it)?.toInstant() }
    // 전체 선택 대상 = 이 화면에 보이는 할 일
    val shownTasks = late + todays + follow + doneToday
    SideEffect { selection.visible = shownTasks.map { it.id } }
    BackHandler(enabled = selection.active) { selection.stop() }

    /** 할 일을 한 곳으로 옮기고 알린다. 되돌리기는 원래 곳으로(선택 › 이동·끌어 놓기 공용) */
    fun moveAndAnnounce(picked: List<TaskRow>, to: Place?) = scope.launch {
        val r = moveTasks(context, picked, to)
        load()
        val undo: (() -> Unit)? = if (r.moved.isEmpty()) null else {
            { scope.launch { moveTasksBack(r.moved); load(); announcer.show("되돌렸어요") } }
        }
        announcer.show(movedManyText(r, to), undo)
    }

    fun moveSelected(to: Place?) {
        val picked = shownTasks.filter { it.id in selection.ids }
        moving = false
        selection.stop()
        moveAndAnnounce(picked, to)
    }

    // 길게 눌러 끌어 옮기기(DEC-93). 오늘 탭엔 트리가 없어 접은 프로젝트 화면처럼 아래 '옮길 곳' 띠에 놓는다
    val currentMove by rememberUpdatedState(::moveAndAnnounce)
    val drag = remember {
        ParaDragState(onDrop = { a -> (a as? DropAction.MoveTask)?.let { currentMove(listOf(it.task), it.to) } }, onBlocked = { announcer.show(it) })
    }
    SideEffect { drag.data = para }
    DragAutoScroll(drag)
    // 따라오는 이름 칩은 화면 상자 안 좌표로 그린다
    val boxOrigin = remember { OriginRef() }

    /** 고른 할 일을 지운다: 줄은 바로 빠지고, 3초 '되돌리기'가 지나면 서버에서 지운다(DEC-90) */
    fun deleteSelected() {
        val picked = shownTasks.filter { it.id in selection.ids }
        if (picked.isEmpty()) return
        selection.stop()
        picked.forEach(TaskDeletes::delete)
        announcer.show(deletedText(picked)) { picked.forEach { TaskDeletes.undo(it.id) } }
    }

    val pendingGroups = groupPending(pending.filter { !Snooze.isActive(it.snoozedUntil) })
    val processing = sessions.filter { it.recordId != null && it.serverState != "ready" }

    val todayCard: @Composable () -> Unit = {
        TodayCard(
            late, todays, follow, doneToday, loaded = tasks != null, sources,
            onToggle = { t -> setDone(t, !t.done) },
            onOpen = { t -> editError = null; editing = t },
            onRecipients = { t -> editError = null; recipientsEditing = t },
            selection = selection,
            drag = drag,
            onPlace = { p -> nav.openProjects(if (p.type == PlaceType.PROJECT) "p:${p.id}" else "a:${p.id}") },
            onAll = { nav.openProjects("all") },
        )
    }
    val reviewCard: @Composable () -> Unit = { ReviewCard(pendingGroups, nav) }
    val processingCard: @Composable () -> Unit = {
        ProcessingCard(processing, uploads, onResume = { s -> UploadManager.resume(context, s) }, onOpenAll = nav.openRecords)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { boxOrigin.value = it.positionInRoot() }) {
        Column(
            // 맨 아래 내용이 떠 있는 입력 띠에 가리지 않게 띠 높이만큼 더 비운다(DEC-73)
            Modifier.fillMaxSize().background(Wb.colors.surface).verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 24.dp else 16.dp).padding(bottom = 24.dp + QUICK_DOCK_SPACE),
        ) {
            // 오른쪽 위 ⋮(갤럭시 '내 파일'처럼, DEC-92). 선택 중에는 제목 줄 자리에 '전체 · N개 선택 · 취소'
            if (selection.active) {
                SelectionHeader(selection)
            } else {
                PageHeader("오늘", "오늘 챙길 것만 모았어요") {
                    SearchButton(nav.openSearch)
                    TaskListMenu(
                        onSelect = selection::start,
                        more = listOf(
                            "업무계획" to { nav.openWorkPlan(PlanKind.WEEKLY) },
                            "새로고침" to { scope.launch { message = null; load() } },
                        ),
                    )
                }
            }
            // 목업 .page-head .sub
            Text("${today.format(HEAD_DATE)} · 모든 프로젝트 · 확정 상태 기준", fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.padding(bottom = 12.dp))
            // 매주 화요일 업무계획 카드(FR-33, DEC-87: 날짜 줄 바로 아래 맨 위)
            PlanDayCard(today, nav.openWorkPlan, Modifier.padding(bottom = 12.dp))
            CostWarn(budget, cost, nav.openMore)
            message?.let { (text, tone) -> Banner(text, tone, Modifier.padding(bottom = 12.dp)) }

            if (wide) {
                // 목업 .dash: 왼쪽 1.25 : 오른쪽 1
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1.25f)) { todayCard() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { reviewCard(); processingCard() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { todayCard(); reviewCard(); processingCard() }
            }

            // 목업 .page-foot
            HorizontalDivider(color = Wb.colors.border, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note("프로젝트별 상태·요약은 ")
                FootLink("프로젝트") { nav.openProjects(null) }
                Note(", 비용 상세는 ")
                FootLink("더보기") { nav.openMore() }
                Note("에서 봐요.")
            }
        }
        // 한 줄 빠른 입력은 '오늘 할 일' 카드 머리 대신 화면 아래 떠 있는 띠(DEC-73). 기한을 안 적으면 오늘 마감(목업 S02).
        // 오늘 화면에 안 보이는 것(내일 이후·모호)은 알림에 기한을 함께 쓴다
        // 선택 중에는 같은 자리에 [이동][삭제]
        if (selection.active) {
            SelectionBar(selection.ids.size, onMove = { moving = true }, onDelete = ::deleteSelected, modifier = Modifier.align(Alignment.BottomCenter))
        } else {
            QuickAddDock(places, draftKey = "today", Modifier.align(Alignment.BottomCenter), noDueMeansToday = true) { r ->
                val shownHere = r.dueDate?.let { !it.isAfter(today) } == true
                val due = quickDueLabel(r, today) ?: "${r.vague} · 확인 필요"
                message = (quickAddedText(r) + if (shownHere) "" else " · $due · ‘모든 할 일’에서 보여요") to Tone.Good
                scope.launch { load() }
            }
        }
        // 할 일을 끄는 동안만 아래 '옮길 곳' 띠가 입력 띠를 덮고 올라온다(DEC-93)
        MoveTargetsPanel(drag, Modifier.align(Alignment.BottomCenter))
        // 입력 띠에 가리지 않게 띠 위에 띄운다
        AnnounceHost(announcer, Modifier.align(Alignment.BottomCenter).padding(bottom = QUICK_DOCK_SPACE))
        DragGhost(drag) { boxOrigin.value }
    }

    editing?.let { t ->
        TaskEditSheet(
            t.toEdit(), places, currentPlaceName = t.placeName, error = editError, saving = saving, onDismiss = { editing = null },
            recordId = sources[t.id]?.recordId, usedNames = usedNamesOf(all),
        ) { e -> save(t, e) }
    }
    recipientsEditing?.let { t ->
        RecipientsSheet(t.recipients, sources[t.id]?.recordId, usedNamesOf(all), saving, editError, onDismiss = { recipientsEditing = null }) { names ->
            save(t, t.toEdit().copy(recipients = names))
        }
    }
    if (moving) MoveTargetSheet(selection.ids.size, places, onDismiss = { moving = false }, onPick = ::moveSelected)
}

private fun placeOf(t: TaskRow): Place? = when {
    t.projectId != null && t.project != null -> Place(PlaceType.PROJECT, t.projectId, t.project.name)
    t.areaId != null && t.area != null -> Place(PlaceType.AREA, t.areaId, t.area.name)
    else -> null
}

// ── 오늘 할 일 ──

/** 목업 .card-h: 제목 · 건수 · 오른쪽 링크 */
@Composable
private fun CardHead(title: String, count: String?, link: String? = null, onLink: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
        count?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted, modifier = Modifier.padding(start = 8.dp)) }
        Spacer(Modifier.weight(1f))
        link?.let {
            Text("$it →", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onLink).padding(4.dp))
        }
    }
}

/** 목업 .sub-h: 위 구분선 + 작은 굵은 묶음 이름 */
@Composable
private fun SubHead(text: String, color: Color = Wb.colors.muted) {
    HorizontalDivider(color = Wb.colors.border)
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
}

@Composable
private fun TodayCard(
    late: List<TaskRow>,
    todays: List<TaskRow>,
    follow: List<TaskRow>,
    doneToday: List<TaskRow>,
    loaded: Boolean,
    sources: Map<String, TaskSource>,
    onToggle: (TaskRow) -> Unit,
    onOpen: (TaskRow) -> Unit,
    onRecipients: (TaskRow) -> Unit,
    selection: TaskSelection,
    drag: ParaDragState,
    onPlace: (Place) -> Unit,
    onAll: () -> Unit,
) {
    MockCard {
        BoxWithConstraints {
            // 목업은 좁아지면(700px) 우선순위 칸을 숨긴다
            val showPriority = maxWidth >= 480.dp
            Column {
                CardHead("오늘 할 일", "${late.size + todays.size}건", "다음 7일 · 전체", onAll)
                val rows: @Composable ColumnScope.(List<TaskRow>) -> Unit = { list ->
                    list.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = Wb.colors.border)
                        TaskLine(t, sources[t.id]?.label, showPriority, readOnly = false, onToggle = { onToggle(t) }, onOpen = { onOpen(t) }, place = placeOf(t), onPlace = onPlace, onRecipients = { onRecipients(t) },
                            selected = if (selection.active) t.id in selection.ids else null, onSelect = { selection.toggle(t.id) },
                            // 길게 눌러 다른 프로젝트·반복실행으로 끈다(DEC-93). 고르는 중에는 끌지 않는다
                            modifier = Modifier.dragSource(drag, if (selection.active) null else DragItem.Task(t.id, t.title)),
                        )
                    }
                }
                if (late.isNotEmpty()) { SubHead("지연 ${late.size}", Wb.colors.crit); rows(late) }
                if (todays.isNotEmpty()) { SubHead("오늘 ${todays.size}"); rows(todays) }
                if (follow.isNotEmpty()) { SubHead("챙길 팔로업 ${follow.size} · 남이 맡은 일"); rows(follow) }
                if (loaded && late.isEmpty() && todays.isEmpty()) {
                    // 목업 .done-all
                    HorizontalDivider(color = Wb.colors.border)
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("오늘 할 일 끝!", fontFamily = HandAccent, fontSize = 26.sp, color = Wb.colors.good)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("다음 7일 일정은 ", fontSize = 14.sp, color = Wb.colors.muted)
                            Text("모든 할 일", fontSize = 14.sp, color = Wb.colors.link, modifier = Modifier.clickable(onClick = onAll))
                            Text("에서 봐요.", fontSize = 14.sp, color = Wb.colors.muted)
                        }
                    }
                }
                if (doneToday.isNotEmpty()) { SubHead("오늘 완료 ${doneToday.size}"); rows(doneToday) }
            }
        }
    }
}

// ── 검토 대기 ──

private fun changeKind(p: InboxRow): String {
    val keys = p.after.keys
    return when {
        "status" in keys -> "상태 변경"
        keys.any { it.startsWith("due_") } -> "기한 변경"
        keys.any { it == "owner_kind" || it == "assignee" } -> "담당 변경"
        else -> "내용 변경"
    }
}

private fun groupPending(rows: List<InboxRow>): List<Pending> = rows.groupBy { it.recordId ?: "" }.map { (id, list) ->
    val first = list.first()
    val day = first.recordAt?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).let { z -> "${z.monthValue}/${z.dayOfMonth}" } }.getOrNull() }
    val expired = list.any { p -> p.transcriptExpiresAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().isBefore(java.time.Instant.now()) }.getOrNull() } == true }
    Pending(
        recordId = id,
        title = first.recordTitle,
        day = day,
        newCount = list.count { it.targetId == null },
        changes = list.filter { it.targetId != null }.groupingBy(::changeKind).eachCount(),
        expired = expired,
    )
}.sortedByDescending { it.newCount + it.changes.values.sum() }

@Composable
private fun ReviewCard(groups: List<Pending>, nav: TodayNav) {
    MockCard {
        CardHead("검토 대기", "${groups.sumOf { it.newCount + it.changes.values.sum() }}건")
        if (groups.isEmpty()) {
            HorizontalDivider(color = Wb.colors.border)
            Note("검토할 것이 없어요. 녹음을 올리면 할 일 후보가 여기에 기록별로 모여요.", Modifier.padding(16.dp))
        }
        groups.forEach { g ->
            HorizontalDivider(color = Wb.colors.border)
            // 목업 .rv: 아이콘 · 제목·설명 · 버튼. 목업의 칩('할 일 후보 7')은 설명과 같은 내용이라 뺀다(11/06 사용자, 설명이 변경 종류까지 보여 줌)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                Icon(if (g.expired) WbIcons.Trash else WbIcons.Mic, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOfNotNull(if (g.expired) "근거가 지워진 제안" else g.title, g.day.takeIf { !g.expired }).joinToString(" · "),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                    val parts = buildList {
                        if (g.newCount > 0) add("할 일 후보 ${g.newCount}건")
                        g.changes.forEach { (k, n) -> add("$k $n") }
                    }
                    Text(
                        if (g.expired) "${g.title} — 근거 보관 기간이 지나 새 근거 없이는 승인할 수 없어요" else parts.joinToString(" · "),
                        fontSize = 12.sp, color = Wb.colors.muted,
                    )
                }
                // 새 할 일 후보는 기록 화면에서 고르고, 변경·재검토는 검토함에서 본다(목업, DEC-23)
                if (g.newCount > 0 && !g.expired) SmallButton("할 일 고르기", primary = true) { nav.openRecord(g.recordId, g.title) }
                else SmallButton("검토함") { nav.openReview() }
            }
        }
        // 목업 .card-f
        HorizontalDivider(color = Wb.colors.border)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Note("AI 제안은 승인해야 반영돼요. 승인 버튼은 기록 화면과 검토함에만 있어요.", Modifier.weight(1f).padding(end = 8.dp))
            SmallButton("검토함 열기", primary = true) { nav.openReview() }
        }
    }
}

// ── 처리 중인 기록 ──

@Composable
private fun ProcessingCard(
    rows: List<RecordingSession>,
    uploads: Map<String, UploadProgress>,
    onResume: (RecordingSession) -> Unit,
    onOpenAll: () -> Unit,
) {
    MockCard {
        CardHead("처리 중인 기록", "${rows.size}건", "기록 전체", onOpenAll)
        if (rows.isEmpty()) {
            HorizontalDivider(color = Wb.colors.border)
            Note("처리 중인 기록이 없어요.", Modifier.padding(16.dp))
        }
        rows.forEach { s ->
            HorizontalDivider(color = Wb.colors.border)
            val live = uploads[s.id]
            val failed = s.serverState == "failed"
            // 목업 .pr: 아이콘 · 제목/작은 설명/진행 막대 · 오른쪽 상태
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenAll).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (s.channel == "recording") WbIcons.Mic else WbIcons.Document, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                    Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                    Text(
                        listOf(sessionWhen(s), live?.message ?: if (failed) "처리하지 못했어요" else "백그라운드에서 처리 중").joinToString(" · "),
                        fontSize = 12.sp, color = if (failed || live?.isError == true) Wb.colors.crit else Wb.colors.muted,
                    )
                    if (!failed) {
                        // 단계별 퍼센트는 서버가 주지 않아 진행 중임만 보인다(목업 .bar)
                        LinearProgressIndicator(
                            Modifier.padding(top = 5.dp).widthIn(max = 220.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = Wb.colors.primary, trackColor = Wb.colors.surface,
                        )
                    }
                }
                when {
                    failed -> WbChip("처리 실패", Tone.Crit)
                    // 이 화면에서 돌고 있지 않으면(앱을 다시 연 뒤) 바로 다시 돌릴 수 있게 둔다 — 기록 탭 '이어서 처리'와 같음
                    live == null -> SmallButton("이어서 처리") { onResume(s) }
                    else -> WbChip("진행 중", Tone.Info)
                }
            }
        }
    }
}

private fun sessionWhen(s: RecordingSession): String {
    val at = java.time.Instant.ofEpochMilli(s.startedAt).atZone(SEOUL)
    val min = (s.durationMs / 60_000).coerceAtLeast(1)
    return "${if (s.channel == "recording") "녹음" else "파일"} ${min}분 · ${at.monthValue}/${at.dayOfMonth} %02d:%02d".format(at.hour, at.minute)
}

// ── 비용 경고 · 바닥 줄 ──

/** 목업 .cost-warn: 이번 청구 주기 비용이 경고선을 넘었을 때만 */
@Composable
private fun CostWarn(budget: Budget?, cost: Int?, onOpen: () -> Unit) {
    if (budget == null || cost == null || cost < budget.warnKrw) return
    val won = NumberFormat.getNumberInstance(Locale.KOREA)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(8.dp))
            .background(Wb.colors.warn.copy(alpha = 0.10f)).border(1.dp, Wb.colors.warn.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WbIcons.Coin, contentDescription = null, tint = Wb.colors.warn, modifier = Modifier.size(18.dp))
        Text(
            buildAnnotatedString {
                append("이번 청구 주기 비용이 ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("₩${won.format(budget.warnKrw)} 경고선") }
                append("을 넘었어요(₩${won.format(cost)}). ₩${won.format(budget.waitKrw)}을 넘으면 새 전사·분석은 자동으로 기다려요.")
            },
            fontSize = 14.sp, color = Wb.colors.body, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text("더보기에서 보기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link, modifier = Modifier.clickable(onClick = onOpen))
    }
}

@Composable
private fun FootLink(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, color = Wb.colors.link, modifier = Modifier.clickable(onClick = onClick))
}
