package com.workbrain.app.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskRow
import com.workbrain.app.ui.dueLabel
import com.workbrain.app.ui.ownerLabel
import kotlinx.coroutines.launch

/** S07 할 일(1차): 승인된 할 일을 내 할 일 / 팔로업으로 나눠 보고 완료로 표시한다(DEC-23). */
@Composable
fun TasksScreen() {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TaskRow>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        runCatching { tasks = Server.openTasks() }.onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." }
    }
    LaunchedEffect(Unit) { load() }

    val mine = tasks.filter { it.ownerKind != "other" }
    val follow = tasks.filter { it.ownerKind == "other" }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("할 일", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { message = null; load() } }) { Text("새로고침") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (tasks.isEmpty()) {
            Text("아직 할 일이 없어요. 녹음을 올리고 기록 화면에서 할 일을 추가해 보세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mine.isNotEmpty()) item { Header("내 할 일 ${mine.size}") }
            items(mine, key = { it.id }) { t -> TaskCard(t) { scope.launch { complete(t) { message = it }; load() } } }
            if (follow.isNotEmpty()) item { Header("챙길 팔로업 ${follow.size} · 남이 맡은 일") }
            items(follow, key = { it.id }) { t -> TaskCard(t) { scope.launch { complete(t) { message = it }; load() } } }
        }
    }
}

private suspend fun complete(t: TaskRow, say: (String) -> Unit) {
    val ok = runCatching { Server.completeTask(t) }.getOrNull()
    say(
        when (ok) {
            true -> "완료로 표시했어요 · ${t.title}"
            false -> "다른 기기에서 먼저 바뀌었어요. 새로 불러왔어요"
            null -> "저장하지 못했어요. 인터넷 연결을 확인해 주세요"
        },
    )
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun TaskCard(t: TaskRow, onDone: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = false, onCheckedChange = { onDone() })
            Column(Modifier.weight(1f)) {
                Text(t.title, fontWeight = FontWeight.Medium)
                Text(
                    ownerLabel(t.ownerKind, t.assignee) + " · " + dueLabel(t.dueDate, t.dueTime, t.dueAmbiguous, t.dueText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
