package com.workbrain.app.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskRow
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.DueGroup
import com.workbrain.app.ui.EmptyState
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.SectionLabel
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.dueGroup
import com.workbrain.app.ui.dueLabel
import com.workbrain.app.ui.dueTone
import com.workbrain.app.ui.ownerLabel
import com.workbrain.app.ui.ownerTone
import kotlinx.coroutines.launch

/**
 * S02 오늘(1차): 승인된 할 일을 기한 묶음(지남·오늘·이번 주·다음에·기한 없음)으로 보고 완료로 표시한다.
 * 한 주는 월요일에 시작한다(DEC-31). 팔로업(남이 맡은 일)도 같은 묶음 안에 칩으로 구분한다(DEC-23).
 */
@Composable
fun TodayScreen() {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TaskRow>?>(null) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }

    suspend fun load() {
        runCatching { tasks = Server.openTasks() }
            .onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
    }
    LaunchedEffect(Unit) { load() }

    fun complete(t: TaskRow) = scope.launch {
        val ok = runCatching { Server.completeTask(t) }.getOrNull()
        message = when (ok) {
            true -> "완료로 표시했어요 · ${t.title}" to Tone.Good
            false -> "다른 기기에서 먼저 바뀌었어요. 새로 불러왔어요" to Tone.Warn
            null -> "저장하지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
        }
        load()
    }

    val list = tasks.orEmpty()
    val groups = list.groupBy { dueGroup(it.dueDate) }

    Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        PageHeader("오늘", "오늘 챙길 것만 모았어요", Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = { scope.launch { message = null; load() } }) { Text("새로고침", color = Wb.colors.link) }
        }
        message?.let { (text, tone) -> Banner(text, tone, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            if (tasks != null && list.isEmpty()) item {
                EmptyState("오늘 할 일 끝!", "녹음을 올리고 검토함에서 할 일을 추가하면 여기에 모여요.")
            }
            DueGroup.entries.forEach { group ->
                val rows = groups[group].orEmpty()
                if (rows.isNotEmpty()) {
                    item(key = "h-${group.name}") { SectionLabel("${group.label} ${rows.size}") }
                    items(rows, key = { it.id }) { t -> TaskCard(t, overdue = group == DueGroup.OVERDUE) { complete(t) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(t: TaskRow, overdue: Boolean, onDone: () -> Unit) {
    WbCard(padding = PaddingValues(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = false,
                onCheckedChange = { onDone() },
                colors = CheckboxDefaults.colors(uncheckedColor = Wb.colors.borderStrong),
            )
            Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WbChip(ownerLabel(t.ownerKind, t.assignee), ownerTone(t.ownerKind))
                    dueLabel(t.dueDate, t.dueTime, t.dueAmbiguous, t.dueText)?.let {
                        WbChip(it, if (overdue) Tone.Crit else dueTone(t.dueAmbiguous))
                    }
                }
            }
        }
    }
}
