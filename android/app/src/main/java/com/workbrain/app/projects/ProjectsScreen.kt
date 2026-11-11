package com.workbrain.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.data.Para
import com.workbrain.app.data.ParaData
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.TaskRow
import com.workbrain.app.data.toEdit
import com.workbrain.app.tasks.QUICK_DOCK_SPACE
import com.workbrain.app.tasks.QuickAddMemory
import com.workbrain.app.tasks.RecipientsSheet
import com.workbrain.app.tasks.MoveTargetSheet
import com.workbrain.app.tasks.SelectionBar
import com.workbrain.app.tasks.SelectionHeader
import com.workbrain.app.tasks.TaskDeletes
import com.workbrain.app.tasks.TaskListMenu
import com.workbrain.app.tasks.deletedText
import com.workbrain.app.tasks.moveTasks
import com.workbrain.app.tasks.moveTasksBack
import com.workbrain.app.tasks.movedManyText
import com.workbrain.app.tasks.rememberTaskSelection
import com.workbrain.app.tasks.TaskEditSheet
import com.workbrain.app.tasks.UNSAVED_TEXT
import com.workbrain.app.tasks.movedText
import com.workbrain.app.ui.AnnounceHost
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.CONFLICT_TEXT
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.isWideWindow
import com.workbrain.app.ui.rememberAnnouncer
import com.workbrain.app.ui.saveFailText
import kotlinx.coroutines.launch

/** 트리·상세에서 일어나는 일. 서랍·창과 서버 호출은 ProjectsScreen 한곳에서 처리한다 */
sealed interface ParaEvent {
    data class Select(val key: String) : ParaEvent
    data class OpenTask(val task: TaskRow) : ParaEvent
    /** 할 일 줄의 '→ 받는 사람' 칩: 작은 서랍에서 고르기(DEC-77) */
    data class EditRecipients(val task: TaskRow) : ParaEvent
    data class ToggleTask(val task: TaskRow) : ParaEvent
    /** 상세 머리 [수정]: 프로젝트(이름·반복실행·목적) / 반복실행(이름·설명) / 자료(이름·내용) */
    data class Edit(val key: String) : ParaEvent
    data class SavePurpose(val project: ProjectRow, val purpose: String) : ParaEvent
    data class Archive(val key: String, val archived: Boolean) : ParaEvent
    data class EditLinks(val resourceId: String) : ParaEvent
    /** P4 자리(캘린더·매트릭스)를 눌렀을 때 안내만 */
    data class Later(val which: String) : ParaEvent
    /** 트리 하위 기록·'관련 기록' 카드에서 기록 상세 열기(DEC-69) */
    data class OpenRecord(val recordId: String, val title: String) : ParaEvent
    /** 한 줄 빠른 입력으로 넣었음(저장은 입력 줄이 이미 함): 다시 읽고 알림만(DEC-70) */
    data class QuickAdded(val message: String) : ParaEvent
}

/** 아카이브 전에 묻기: 안 끝난 할 일이 '오늘'·'모든 할 일'에서 빠진다는 것을 알린다(DEC-65) */
private data class ArchiveAsk(val key: String, val name: String, val openTasks: Int)

/**
 * S03 프로젝트(PARA 트리) — 목업 workbrain-project.html 모양(DEC-66), 기능 범위는 1단계(DEC-65).
 * 펼친 화면: 머리('프로젝트' + [+ 새로 만들기]) 아래 왼쪽 트리 패널 / 오른쪽 상세(목업 .pj-layout, DEC-47).
 * 접힌 화면: 머리 + 트리 패널 → 항목을 누르면 상세 전체 화면(뒤로 가기로 트리, Design §6.3).
 * 결과는 화면 아래 알림으로 알리고, 되돌릴 수 있으면 '되돌리기'(DEC-24). 길게 눌러 끌어서 옮기기(DEC-72)는 같은 동작의 버튼과 함께 둔다.
 * startKey: 다른 탭에서 열 항목('all'·'p:ID'·'a:ID', 오늘 화면 링크). 쓰고 나면 onStartUsed로 비운다.
 * onOpenRecord(기록 ID, 제목, 돌아올 항목): 기록 상세를 연다. 상세를 닫으면 돌아올 항목이 startKey로 다시 온다
 */
@Composable
fun ProjectsScreen(
    startKey: String? = null,
    onStartUsed: () -> Unit = {},
    onOpenRecord: (recordId: String, title: String, returnKey: String?) -> Unit = { _, _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val announcer = rememberAnnouncer()
    val wide = isWideWindow()
    var data by remember { mutableStateOf<ParaData?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    // 고른 것과 펼친 칸은 접기·펴기·회전에도 남는다
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var openSections by rememberSaveable { mutableStateOf("123") }
    // 하위 기록을 펼친 프로젝트(쉼표로 이은 ID). 프로젝트를 고르면 함께 펼친다(목업: 고른 프로젝트 아래 기록이 보임)
    var openProjectIds by rememberSaveable { mutableStateOf("") }
    // ⋮ › 선택(DEC-92): 보고 있는 할 일 목록에서 골라 한꺼번에 옮기거나 지운다. 다른 항목으로 가면 끝난다
    val selection = rememberTaskSelection()
    var moving by remember { mutableStateOf(false) }
    // 펼침을 바꾸는 함수는 그릴 때 계산한 값이 아니라 누른 순간의 상태를 읽어야 한다.
    // 트리에 넘긴 함수 참조(::toggleProject)는 처음 그릴 때 것이 계속 쓰여, 그때 값을 보면 다시 눌러도 안 접혔다(11/08 사용자 제보)
    fun openSet(): Set<String> = openProjectIds.split(',').filter { it.isNotEmpty() }.toSet()
    fun toggleProject(id: String) {
        val now = openSet()
        openProjectIds = (if (id in now) now - id else now + id).joinToString(",")
    }
    fun openProject(id: String) { openProjectIds = (openSet() + id).joinToString(",") }
    fun select(key: String) {
        selection.stop()
        selected = key
        if (key.startsWith("p:")) openProject(key.substringAfter(':'))
    }
    var adding by remember { mutableStateOf<ParaSection?>(null) }

    var editKey by remember { mutableStateOf<String?>(null) }
    var linkFor by remember { mutableStateOf<String?>(null) }
    var archiveAsk by remember { mutableStateOf<ArchiveAsk?>(null) }
    var editingTask by remember { mutableStateOf<TaskRow?>(null) }
    var taskError by remember { mutableStateOf<String?>(null) }
    var taskSaving by remember { mutableStateOf(false) }
    var recipientsTask by remember { mutableStateOf<TaskRow?>(null) }

    suspend fun load() {
        runCatching { Para.load() }
            .onSuccess { data = it; loadFailed = false }
            .onFailure { loadFailed = true }
    }
    LaunchedEffect(Unit) { load() }
    // 지우지 못한 할 일은 목록에 다시 보이고 이유를 알린다(DEC-90)
    val deleteFailed by TaskDeletes.failed.collectAsStateWithLifecycle()
    LaunchedEffect(deleteFailed) {
        val text = deleteFailed ?: return@LaunchedEffect
        TaskDeletes.consumeFailure()
        load()
        announcer.show(text)
    }
    // 지우는 중·지운 할 일은 화면에서 뺀다(서버에서 지우기 전이라도)
    val hidden by TaskDeletes.hidden.collectAsStateWithLifecycle()
    // 오늘 화면의 '다음 7일 · 전체 →'·프로젝트 이름에서 왔으면 그 항목을 연다(한 번만)
    LaunchedEffect(startKey) {
        if (startKey != null) { select(startKey); onStartUsed() }
    }

    /**
     * 서버 작업 하나: 끝나면 다시 읽고 결과를 알린다. work가 null을 돌려주면 버전이 달라 저장하지 않은 것(다른 기기에서 먼저 고침).
     * undo는 지금 값을 다시 읽어 그 위에 되돌린다(알림이 떠 있는 동안만)
     */
    fun act(done: String, undo: (suspend () -> Any?)? = null, work: suspend () -> Any?) {
        scope.launch {
            val result = runCatching { work() }
            load()
            result.fold(
                onSuccess = { r ->
                    if (r == null) announcer.show(CONFLICT_TEXT)
                    else announcer.show(done, undo?.let { u -> { act("되돌렸어요", null, u) } })
                },
                onFailure = { announcer.show(saveFailText(it)) },
            )
        }
    }

    fun setArchived(d: ParaData, key: String, archived: Boolean) {
        val id = key.substringAfter(':')
        // 목업 알림 문구
        val back = when { key.startsWith("p:") -> "1. 프로젝트로"; key.startsWith("a:") -> "2. 반복실행으로"; else -> "3. 관심영역으로" }
        val doneText = if (archived) "아카이브로 옮겼어요 · ID와 이력은 그대로, 4. 아카이브에서 복원 가능" else "복원했어요 · $back 돌아가요"
        when {
            key.startsWith("p:") -> d.project(id)?.let { p ->
                act(doneText, undo = { Para.project(id)?.let { Para.setProjectArchived(it, !archived) } }) { Para.setProjectArchived(p, archived) }
            }
            key.startsWith("a:") -> d.area(id)?.let { a ->
                act(doneText, undo = { Para.area(id)?.let { Para.setAreaArchived(it, !archived) } }) { Para.setAreaArchived(a, archived) }
            }
            key.startsWith("r:") -> d.resource(id)?.let { r ->
                act(doneText, undo = { Para.resource(id)?.let { Para.setResourceArchived(it, !archived) } }) { Para.setResourceArchived(r, archived) }
            }
        }
    }

    /** recordId: 기록 참조 자료일 때 고른 기록(DEC-71) */
    fun create(d: ParaData, s: ParaSection, kind: String, name: String, recordId: String?) {
        adding = null
        val ws = d.workspace.id
        when (s) {
            ParaSection.PROJECTS -> act("만들었어요 · $name") { Para.createProject(ws, name).also { selected = projectKey(it.id) } }
            ParaSection.AREAS -> act("만들었어요 · $name") { Para.createArea(ws, name).also { selected = areaKey(it.id) } }
            ParaSection.RESOURCES -> act("만들었어요 · $name") { Para.createResource(ws, kind, name, recordId).also { selected = resourceKey(it.id) } }
            ParaSection.ARCHIVE -> Unit
        }
    }

    /** 할 일을 원래 곳으로 되돌리기: 지금 버전을 다시 읽어 그 위에 고친다(서랍 '어디에'·끌어 놓기 공용) */
    fun moveBack(t: TaskRow): suspend () -> Any? = {
        Server.task(t.id)?.let { cur -> Server.updateTask(cur, cur.toEdit().copy(projectId = t.projectId, areaId = t.areaId)) }
    }

    fun saveTask(t: TaskRow, e: TaskEdit) {
        val d = data ?: return
        taskSaving = true
        scope.launch {
            val result = runCatching { Server.updateTask(t, e) }
            taskSaving = false
            result.fold(
                onSuccess = { row ->
                    editingTask = null
                    load()
                    val moved = e.projectId != t.projectId || e.areaId != t.areaId
                    val to = d.places.firstOrNull { it.id == (e.projectId ?: e.areaId) }
                    // 옮긴 곳을 다음 빠른 입력의 넣을 곳으로 기억한다(DEC-91)
                    if (row != null && moved) QuickAddMemory.save(context, to)
                    when {
                        row == null -> announcer.show(CONFLICT_TEXT)
                        moved -> announcer.show(movedText(to)) {
                            act("되돌렸어요", work = moveBack(t))
                        }
                        else -> announcer.show("수정했어요 · 변경 이력에 남아요")
                    }
                },
                // 서랍을 닫지 않고 입력을 그대로 둔다(목업 '미저장 표시'). 아카이브된 곳으로 옮기려 했으면 그 이유를 쓴다
                onFailure = { taskError = if (it.message.orEmpty().contains("아카이브")) saveFailText(it) else UNSAVED_TEXT },
            )
        }
    }

    /** 고른 할 일(지금 목록에 있는 것만) */
    fun pickedTasks(): List<TaskRow> = data?.tasks.orEmpty().filter { it.id in selection.ids && it.id !in TaskDeletes.hidden.value }

    /** 고른 할 일을 한 곳으로 옮긴다. 되돌리기는 원래 곳으로(끌어 놓기와 같은 알림) */
    fun moveSelected(to: Place?) {
        val picked = pickedTasks()
        moving = false
        selection.stop()
        scope.launch {
            val r = moveTasks(context, picked, to)
            load()
            val undo: (() -> Unit)? = if (r.moved.isEmpty()) null else {
                { act("되돌렸어요") { moveTasksBack(r.moved) } }
            }
            announcer.show(movedManyText(r, to), undo)
        }
    }

    /** 고른 할 일을 지운다: 줄은 바로 빠지고, 3초 '되돌리기'가 지나면 서버에서 지운다(DEC-90) */
    fun deleteSelected() {
        val picked = pickedTasks()
        if (picked.isEmpty()) return
        selection.stop()
        picked.forEach(TaskDeletes::delete)
        announcer.show(deletedText(picked)) { picked.forEach { TaskDeletes.undo(it.id) } }
    }

    /** 받는 사람만 저장(다른 칸은 지금 값 그대로). 실패하면 서랍을 닫지 않고 띠를 보여 준다 */
    fun saveRecipients(t: TaskRow, names: List<String>) {
        taskSaving = true
        scope.launch {
            val result = runCatching { Server.updateTask(t, t.toEdit().copy(recipients = names)) }
            taskSaving = false
            result.fold(
                onSuccess = { row ->
                    recipientsTask = null
                    load()
                    announcer.show(if (row == null) CONFLICT_TEXT else "받는 사람을 저장했어요 · 변경 이력에 남아요")
                },
                onFailure = { taskError = UNSAVED_TEXT },
            )
        }
    }

    fun onEvent(e: ParaEvent) {
        val d = data ?: return
        when (e) {
            is ParaEvent.Select -> select(e.key)
            is ParaEvent.OpenRecord -> onOpenRecord(e.recordId, e.title, selected)
            is ParaEvent.OpenTask -> { taskError = null; editingTask = e.task }
            is ParaEvent.EditRecipients -> { taskError = null; recipientsTask = e.task }
            is ParaEvent.ToggleTask -> {
                val t = e.task
                act(if (t.done) "다시 열었어요" else "완료로 표시했어요") { t.takeIf { Server.setTaskDone(t, !t.done) } }
            }
            is ParaEvent.Edit -> editKey = e.key
            is ParaEvent.SavePurpose -> act("목적을 저장했어요") { Para.setPurpose(e.project, e.purpose) }
            is ParaEvent.Archive -> {
                val id = e.key.substringAfter(':')
                // 안 끝난 할 일이 있는 프로젝트·반복실행을 보관할 때만 한 번 묻는다. 나머지는 되돌리기로 충분하다
                val (name, open) = when {
                    e.key.startsWith("p:") -> d.project(id)?.name to d.openCount(PlaceType.PROJECT, id)
                    e.key.startsWith("a:") -> d.area(id)?.name to d.openCount(PlaceType.AREA, id)
                    else -> null to 0
                }
                if (e.archived && open > 0 && name != null) archiveAsk = ArchiveAsk(e.key, name, open)
                else setArchived(d, e.key, e.archived)
            }
            is ParaEvent.EditLinks -> linkFor = e.resourceId
            is ParaEvent.QuickAdded -> scope.launch { load(); announcer.show(e.message) }
            is ParaEvent.Later -> announcer.show(
                if (e.which == "cal") "캘린더 보기는 P4예요 · 날짜·시각 기한을 그대로 달력에 놓아요(모호한 기한은 옆 칸에 따로)"
                else "매트릭스는 P4예요 · 지금의 우선순위(중요)와 기한(급함)으로 4칸을 계산해 데이터 추가가 없어요",
            )
        }
    }

    /**
     * 끌어 놓은 뒤(DEC-72): 버튼과 같은 서버 호출을 하고 3초 '되돌리기'를 준다.
     * 안 끝난 할 일이 있는 프로젝트를 4. 아카이브에 놓으면 [아카이브로] 버튼과 같은 확인 창(Archive 이벤트가 맡음)
     */
    fun drop(a: DropAction) {
        val d = data ?: return
        when (a) {
            is DropAction.Archive -> onEvent(ParaEvent.Archive(projectKey(a.project.id), true))
            is DropAction.Restore -> setArchived(d, projectKey(a.project.id), false)
            is DropAction.MoveTask -> {
                val to = a.to
                val edit = a.task.toEdit().copy(
                    projectId = to.id.takeIf { to.type == PlaceType.PROJECT },
                    areaId = to.id.takeIf { to.type == PlaceType.AREA },
                )
                act(movedText(to), undo = moveBack(a.task)) {
                    Server.updateTask(a.task, edit)?.also { QuickAddMemory.save(context, to) }
                }
            }
            is DropAction.MoveRecord -> {
                // 받은 프로젝트를 펼쳐 옮겨 간 기록이 보이게 한다(목업)
                openProject(a.to.id)
                act(
                    "기록 ‘${a.title}’을(를) ‘${a.to.name}’에 연결했어요 · 그 기록에서 나온 할 일은 그대로",
                    undo = { Para.moveRecord(a.recordId, a.to.id, a.fromProjectId) },
                ) {
                    // 옮겨 간 프로젝트의 현재 상태 제안을 만든다(FR-09). 되돌리면 원래 곳은 이미 반영한 회의라 서버가 건너뛴다
                    Para.moveRecord(a.recordId, a.fromProjectId, a.to.id).also { if (it == "moved") StateWorker.start(context, a.to.id, listOf(a.recordId)) }
                }
            }
            is DropAction.LinkResource -> act(
                "‘${a.resource.title}’을(를) ‘${a.to.name}’에 연결했어요",
                undo = { Para.unlink(a.resource.id, a.to.type, a.to.id) },
            ) { Para.link(a.resource.id, a.to.type, a.to.id) }
        }
    }
    val currentDrop by rememberUpdatedState(::drop)
    val drag = remember { ParaDragState(onDrop = { currentDrop(it) }, onBlocked = { announcer.show(it) }) }
    SideEffect { drag.data = data }
    DragAutoScroll(drag)
    // 놓을 수 있는 접힌 칸 머리(1·4)에 올라가면 그 칸을 펼친다(목업)
    LaunchedEffect(drag.hot) {
        val s = (drag.hot as? DropKey.Section)?.section ?: return@LaunchedEffect
        if (s.no !in openSections) openSections += s.no
    }
    // 따라오는 이름 칩은 화면 상자 안 좌표로 그린다
    val boxOrigin = remember { OriginRef() }

    // 위쪽 '+ 새로 만들기'는 가장 자주 만드는 1. 프로젝트 칸의 (+)와 같다(목업)
    val newProject = {
        if (ParaSection.PROJECTS.no !in openSections) openSections += ParaSection.PROJECTS.no
        adding = ParaSection.PROJECTS
        if (!wide) selected = null
    }
    // 지금 오른쪽(접은 화면은 전체)에 할 일 목록이 보이고 고칠 수 있는 곳인지. 트리만 보이거나 자료·보관된 곳이면 '선택'을 뺀다
    val shownKey = selected ?: if (wide) ALL_TASKS else null
    val canSelect = data?.let { d ->
        val id = shownKey?.substringAfter(':')
        when {
            shownKey == ALL_TASKS -> true
            shownKey?.startsWith("p:") == true -> d.project(id)?.archived == false
            shownKey?.startsWith("a:") == true -> d.area(id)?.archived == false
            else -> false
        }
    } == true
    // 오른쪽 위 ⋮(DEC-92): 선택 · 정렬 방식
    val menu: @Composable (Boolean) -> Unit = { can -> TaskListMenu(onSelect = if (can) selection::start else null) }
    val header: @Composable (Modifier) -> Unit = { m ->
        if (selection.active) {
            SelectionHeader(selection, m)
        } else {
            PageHeader("프로젝트", "하고 있는 일 한눈에", m) {
                menu(canSelect)
                PrimaryButton("+ 새로 만들기", onClick = newProject)
            }
        }
    }

    CompositionLocalProvider(LocalTaskPaneTools provides TaskPaneTools(selection, menu)) {
    Box(Modifier.fillMaxSize().background(Wb.colors.surface).onGloballyPositioned { boxOrigin.value = it.positionInRoot() }) {
        val d = data?.let { all -> if (hidden.isEmpty()) all else all.copy(tasks = all.tasks.filter { it.id !in hidden }) }
        if (d == null) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                PageHeader("프로젝트", "하고 있는 일 한눈에")
                if (loadFailed) {
                    Banner("불러오지 못했어요. 인터넷 연결을 확인해 주세요.", Tone.Crit)
                    TextButton(onClick = { scope.launch { load() } }) { Text("다시 시도", color = Wb.colors.link) }
                } else {
                    Text("불러오는 중…", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted)
                }
            }
        } else {
            val tree: @Composable (Modifier) -> Unit = { m ->
                ParaTreePanel(
                    d,
                    drag,
                    // 펼친 화면은 아무것도 안 골랐으면 '모든 할 일'을 오른쪽에 연다
                    selectedKey = selected ?: if (wide) ALL_TASKS else null,
                    openSections = openSections,
                    adding = adding,
                    openProjects = openSet(),
                    onToggleSection = { s -> openSections = if (s.no in openSections) openSections.replace(s.no.toString(), "") else openSections + s.no },
                    onAdd = { s -> adding = s; if (s != null && s.no !in openSections) openSections += s.no },
                    onCreate = { s, kind, name, recordId -> create(d, s, kind, name, recordId) },
                    onSelect = { select(it); adding = null },
                    onToggleProject = ::toggleProject,
                    // 트리에서 연 기록을 닫으면 그 기록이 속한 프로젝트로 돌아온다
                    onOpenRecord = { id, title -> onOpenRecord(id, title, selected) },
                    modifier = m,
                )
            }
            val current = selected
            val treeScroll = rememberScrollState()
            when {
                wide -> Column(Modifier.fillMaxSize()) {
                    header(Modifier.padding(horizontal = 24.dp))
                    Row(Modifier.fillMaxSize().padding(start = 24.dp)) {
                        // 목업 .side 288px. 트리는 패널째 따로 스크롤한다
                        Column(
                            Modifier.width(300.dp).fillMaxHeight().autoScrollZone(drag, "tree", treeScroll).verticalScroll(treeScroll).padding(bottom = 24.dp),
                        ) { tree(Modifier) }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f).fillMaxHeight()) { ParaDetail(d, drag, current ?: ALL_TASKS, onBack = null, onEvent = ::onEvent) }
                    }
                }
                current == null -> Column(
                    Modifier.fillMaxSize().autoScrollZone(drag, "tree", treeScroll).verticalScroll(treeScroll).padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
                ) {
                    header(Modifier)
                    tree(Modifier)
                }
                else -> ParaDetail(d, drag, current, onBack = { selected = null }, onEvent = ::onEvent)
            }
            // 접은 화면에서 할 일을 끌 때만 아래 '옮길 곳' 띠(DEC-72 ①). 펼친 화면은 왼쪽 트리에 놓는다
            if (!wide) MoveTargetsPanel(drag, Modifier.align(Alignment.BottomCenter))
        }
        // 고르는 중에는 화면 아래 [이동][삭제](모든 할 일에서는 입력 띠 자리)
        if (selection.active) {
            SelectionBar(selection.ids.size, onMove = { moving = true }, onDelete = ::deleteSelected, modifier = Modifier.align(Alignment.BottomCenter))
        }
        // '모든 할 일'을 볼 때는 아래 떠 있는 입력 띠(DEC-73), 고르는 중에는 [이동][삭제] 막대 위로 알림을 올린다
        val barShown = shownKey == ALL_TASKS || selection.active
        AnnounceHost(announcer, Modifier.align(Alignment.BottomCenter).padding(bottom = if (barShown) QUICK_DOCK_SPACE else 0.dp))
        DragGhost(drag) { boxOrigin.value }
    }
    }

    val d = data ?: return

    editKey?.let { key ->
        val id = key.substringAfter(':')
        when {
            key.startsWith("p:") -> d.project(id)?.let { p ->
                ProjectEditSheet(p, d.areas, onDismiss = { editKey = null }) { name, areaId, purpose ->
                    editKey = null
                    act("수정했어요") { Para.saveProject(p, name, areaId, purpose) }
                }
            }
            key.startsWith("a:") -> d.area(id)?.let { a ->
                AreaEditSheet(a, onDismiss = { editKey = null }) { name, desc ->
                    editKey = null
                    act("수정했어요") { Para.saveArea(a, name, desc) }
                }
            }
            key.startsWith("r:") -> d.resource(id)?.let { r ->
                ResourceEditSheet(r, onDismiss = { editKey = null }) { title, url, note ->
                    editKey = null
                    act("수정했어요") { Para.editResource(r, title, url, note) }
                }
            }
        }
    }

    linkFor?.let { rid ->
        val current = d.placesOf(rid)
        LinkEditDialog(d.places, current, onDismiss = { linkFor = null }) { wanted ->
            linkFor = null
            act("연결을 바꿨어요") { Para.setLinks(rid, current, wanted) }
        }
    }

    archiveAsk?.let { a ->
        AlertDialog(
            onDismissRequest = { archiveAsk = null },
            title = { Text("아카이브로 옮길까요?") },
            text = {
                Text(
                    "‘${a.name}’의 안 끝난 할 일 ${a.openTasks}개는 ‘오늘’과 ‘모든 할 일’에서 빠져요. 복원하면 다시 보여요.\n\n기록과 이력은 그대로 남아요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { archiveAsk = null; setArchived(d, a.key, true) }) { Text("아카이브로") } },
            dismissButton = { TextButton(onClick = { archiveAsk = null }) { Text("취소") } },
        )
    }

    editingTask?.let { t ->
        val currentName = d.project(t.projectId)?.name ?: d.area(t.areaId)?.name
        TaskEditSheet(
            t.toEdit(), d.places, currentName, taskError, taskSaving, onDismiss = { editingTask = null },
            recordId = d.sources[t.id]?.recordId, usedNames = d.usedNames,
        ) { e -> saveTask(t, e) }
    }
    recipientsTask?.let { t ->
        RecipientsSheet(
            t.recipients, d.sources[t.id]?.recordId, d.usedNames, saving = taskSaving, error = taskError,
            onDismiss = { recipientsTask = null },
        ) { names -> saveRecipients(t, names) }
    }
    if (moving) MoveTargetSheet(selection.ids.size, d.places, onDismiss = { moving = false }, onPick = ::moveSelected)
}
