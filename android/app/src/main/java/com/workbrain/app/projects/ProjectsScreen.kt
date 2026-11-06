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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.Para
import com.workbrain.app.data.ParaData
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.TaskRow
import com.workbrain.app.data.toEdit
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
 * 결과는 화면 아래 알림으로 알리고, 되돌릴 수 있으면 '되돌리기'(DEC-24). 끌어서 옮기기는 다음 단계(DEC-69 ③).
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
    val announcer = rememberAnnouncer()
    val wide = isWideWindow()
    var data by remember { mutableStateOf<ParaData?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    // 고른 것과 펼친 칸은 접기·펴기·회전에도 남는다
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var openSections by rememberSaveable { mutableStateOf("123") }
    // 하위 기록을 펼친 프로젝트(쉼표로 이은 ID). 프로젝트를 고르면 함께 펼친다(목업: 고른 프로젝트 아래 기록이 보임)
    var openProjectIds by rememberSaveable { mutableStateOf("") }
    val openProjects = openProjectIds.split(',').filter { it.isNotEmpty() }.toSet()
    fun toggleProject(id: String) { openProjectIds = (if (id in openProjects) openProjects - id else openProjects + id).joinToString(",") }
    fun select(key: String) {
        selected = key
        if (key.startsWith("p:")) openProjectIds = (openProjects + key.substringAfter(':')).joinToString(",")
    }
    var adding by remember { mutableStateOf<ParaSection?>(null) }

    var editKey by remember { mutableStateOf<String?>(null) }
    var linkFor by remember { mutableStateOf<String?>(null) }
    var archiveAsk by remember { mutableStateOf<ArchiveAsk?>(null) }
    var editingTask by remember { mutableStateOf<TaskRow?>(null) }
    var taskError by remember { mutableStateOf<String?>(null) }
    var taskSaving by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { Para.load() }
            .onSuccess { data = it; loadFailed = false }
            .onFailure { loadFailed = true }
    }
    LaunchedEffect(Unit) { load() }
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

    fun create(d: ParaData, s: ParaSection, kind: String, name: String) {
        adding = null
        val ws = d.workspace.id
        when (s) {
            ParaSection.PROJECTS -> act("만들었어요 · $name") { Para.createProject(ws, name).also { selected = projectKey(it.id) } }
            ParaSection.AREAS -> act("만들었어요 · $name") { Para.createArea(ws, name).also { selected = areaKey(it.id) } }
            ParaSection.RESOURCES -> act("만들었어요 · $name") { Para.createResource(ws, kind, name).also { selected = resourceKey(it.id) } }
            ParaSection.ARCHIVE -> Unit
        }
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
                    when {
                        row == null -> announcer.show(CONFLICT_TEXT)
                        moved -> announcer.show(movedText(d.places.firstOrNull { it.id == (e.projectId ?: e.areaId) })) {
                            act("되돌렸어요") { Server.task(t.id)?.let { cur -> Server.updateTask(cur, cur.toEdit().copy(projectId = t.projectId, areaId = t.areaId)) } }
                        }
                        else -> announcer.show("수정했어요 · 변경 이력에 남아요")
                    }
                },
                // 서랍을 닫지 않고 입력을 그대로 둔다(목업 '미저장 표시'). 아카이브된 곳으로 옮기려 했으면 그 이유를 쓴다
                onFailure = { taskError = if (it.message.orEmpty().contains("아카이브")) saveFailText(it) else UNSAVED_TEXT },
            )
        }
    }

    fun onEvent(e: ParaEvent) {
        val d = data ?: return
        when (e) {
            is ParaEvent.Select -> select(e.key)
            is ParaEvent.OpenRecord -> onOpenRecord(e.recordId, e.title, selected)
            is ParaEvent.OpenTask -> { taskError = null; editingTask = e.task }
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

    // 위쪽 '+ 새로 만들기'는 가장 자주 만드는 1. 프로젝트 칸의 (+)와 같다(목업)
    val newProject = {
        if (ParaSection.PROJECTS.no !in openSections) openSections += ParaSection.PROJECTS.no
        adding = ParaSection.PROJECTS
        if (!wide) selected = null
    }
    val header: @Composable (Modifier) -> Unit = { m ->
        PageHeader("프로젝트", "하고 있는 일 한눈에", m) {
            PrimaryButton("+ 새로 만들기", onClick = newProject)
        }
    }

    Box(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        val d = data
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
                    // 펼친 화면은 아무것도 안 골랐으면 '모든 할 일'을 오른쪽에 연다
                    selectedKey = selected ?: if (wide) ALL_TASKS else null,
                    openSections = openSections,
                    adding = adding,
                    openProjects = openProjects,
                    onToggleSection = { s -> openSections = if (s.no in openSections) openSections.replace(s.no.toString(), "") else openSections + s.no },
                    onAdd = { s -> adding = s; if (s != null && s.no !in openSections) openSections += s.no },
                    onCreate = { s, kind, name -> create(d, s, kind, name) },
                    onSelect = { select(it); adding = null },
                    onToggleProject = ::toggleProject,
                    // 트리에서 연 기록을 닫으면 그 기록이 속한 프로젝트로 돌아온다
                    onOpenRecord = { id, title -> onOpenRecord(id, title, selected) },
                    modifier = m,
                )
            }
            val current = selected
            when {
                wide -> Column(Modifier.fillMaxSize()) {
                    header(Modifier.padding(horizontal = 24.dp))
                    Row(Modifier.fillMaxSize().padding(start = 24.dp)) {
                        // 목업 .side 288px. 트리는 패널째 따로 스크롤한다
                        Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { tree(Modifier) }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f).fillMaxHeight()) { ParaDetail(d, current ?: ALL_TASKS, onBack = null, onEvent = ::onEvent) }
                    }
                }
                current == null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                    header(Modifier)
                    tree(Modifier)
                }
                else -> ParaDetail(d, current, onBack = { selected = null }, onEvent = ::onEvent)
            }
        }
        AnnounceHost(announcer, Modifier.align(Alignment.BottomCenter))
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
        TaskEditSheet(t, d.places, currentName, taskError, taskSaving, onDismiss = { editingTask = null }) { e -> saveTask(t, e) }
    }
}
