package com.workbrain.app.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.Server
import com.workbrain.app.data.Para
import com.workbrain.app.data.Place
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.TaskRow
import com.workbrain.app.tasks.TaskCard
import com.workbrain.app.tasks.TaskEditSheet
import com.workbrain.app.tasks.UNSAVED_TEXT
import com.workbrain.app.tasks.movedText
import com.workbrain.app.ui.CONFLICT_TEXT
import com.workbrain.app.ui.saveFailText
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.DueGroup
import com.workbrain.app.ui.EmptyState
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.SectionLabel
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.dueGroup
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

/**
 * S02 오늘(1차): 승인된 할 일을 기한 묶음(지남·오늘·이번 주·다음에·기한 없음)으로 보고 완료로 표시한다.
 * 한 주는 월요일에 시작한다(DEC-31). 팔로업(남이 맡은 일)도 같은 묶음 안에 칩으로 구분한다(DEC-23).
 * 오늘 완료한 일은 사라지지 않고 맨 아래 '오늘 완료'에 취소선·회색으로 남는다. 체크를 다시 누르면 할 일로 돌아간다(DEC-64)
 */
@Composable
fun TodayScreen() {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TaskRow>?>(null) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    // 할 일 고치기 서랍의 '어디에' 목록(FR-02). 못 읽으면 빈 목록이라 '정리 안 됨'만 고를 수 있다
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var editing by remember { mutableStateOf<TaskRow?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { tasks = Server.todayTasks() }
            .onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
        runCatching { places = Para.places() }
    }
    LaunchedEffect(Unit) { load() }

    /** 서랍 저장. 실패하면 서랍을 닫지 않고 입력을 그대로 둔 채 띠를 보여 준다(목업 '미저장 표시') */
    fun save(t: TaskRow, e: TaskEdit) = scope.launch {
        saving = true
        val moved = e.projectId != t.projectId || e.areaId != t.areaId
        val result = runCatching { Server.updateTask(t, e) }
        saving = false
        result.onSuccess { row ->
            editing = null
            message = when {
                row == null -> CONFLICT_TEXT to Tone.Warn
                moved -> movedText(places.firstOrNull { it.id == (e.projectId ?: e.areaId) }) to Tone.Good
                else -> "수정했어요 · 변경 이력에 남아요" to Tone.Good
            }
            load()
        }.onFailure {
            editError = if (it.message.orEmpty().contains("아카이브")) saveFailText(it) else UNSAVED_TEXT
        }
    }

    fun setDone(t: TaskRow, done: Boolean) = scope.launch {
        val ok = runCatching { Server.setTaskDone(t, done) }.getOrNull()
        message = when (ok) {
            true -> (if (done) "완료했어요 · 아래 ‘오늘 완료’에 남겨 둘게요" else "다시 할 일로 돌렸어요 · ${t.title}") to Tone.Good
            false -> CONFLICT_TEXT to Tone.Warn
            null -> "저장하지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
        }
        load()
    }

    val (doneToday, open) = tasks.orEmpty().partition { it.done }
    val groups = open.groupBy { dueGroup(it.dueDate) }
    // 방금 끝낸 것이 위에 오도록
    val doneSorted = doneToday.sortedByDescending { t -> t.doneAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } }

    Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        PageHeader("오늘", "오늘 챙길 것만 모았어요", Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = { scope.launch { message = null; load() } }) { Text("새로고침", color = Wb.colors.link) }
        }
        message?.let { (text, tone) -> Banner(text, tone, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            if (tasks != null && open.isEmpty()) item {
                EmptyState(
                    "오늘 할 일 끝!",
                    if (doneSorted.isEmpty()) "녹음을 올리고 검토함에서 할 일을 추가하면 여기에 모여요." else "오늘 ${doneSorted.size}개를 끝냈어요.",
                    icon = WbIcons.Home, tint = GlassTint.Blue,
                )
            }
            DueGroup.entries.forEach { group ->
                val rows = groups[group].orEmpty()
                if (rows.isNotEmpty()) {
                    item(key = "h-${group.name}") { SectionLabel("${group.label} ${rows.size}") }
                    items(rows, key = { it.id }) { t ->
                        TaskCard(t, overdue = group == DueGroup.OVERDUE, onToggle = { setDone(t, true) }, onOpen = { editError = null; editing = t }, place = t.placeName)
                    }
                }
            }
            if (doneSorted.isNotEmpty()) {
                item(key = "h-done") { SectionLabel("오늘 완료 ${doneSorted.size}") }
                items(doneSorted, key = { it.id }) { t -> TaskCard(t, overdue = false, onToggle = { setDone(t, false) }, place = t.placeName) }
            }
        }
    }

    editing?.let { t ->
        TaskEditSheet(t, places, currentPlaceName = t.placeName, error = editError, saving = saving, onDismiss = { editing = null }) { e -> save(t, e) }
    }
}
