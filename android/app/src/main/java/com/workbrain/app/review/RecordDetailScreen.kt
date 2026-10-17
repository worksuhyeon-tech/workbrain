package com.workbrain.app.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.MeetingRow
import com.workbrain.app.data.ProposalRow
import com.workbrain.app.data.Server
import com.workbrain.app.ui.dueLabel
import com.workbrain.app.ui.ownerLabel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.texts(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.str("text")?.takeIf(String::isNotBlank) }

/**
 * S05 기록 상세(1차): 회의 정리(결정 후보·미결정)와 할 일 후보.
 * 회의록을 본 자리에서 할 일을 골라 바로 추가한다. 승인 기록은 검토함과 같다(DEC-23). AI가 직접 할 일을 만들지 않는다.
 */
@Composable
fun RecordDetailScreen(recordId: String, title: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var meeting by remember { mutableStateOf<MeetingRow?>(null) }
    var proposals by remember { mutableStateOf<List<ProposalRow>>(emptyList()) }
    var transcript by remember { mutableStateOf<String?>(null) }
    var showTranscript by remember { mutableStateOf(false) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        runCatching {
            meeting = Server.meeting(recordId)
            proposals = Server.proposals(recordId)
            transcript = Server.transcript(recordId)
        }.onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." }
        proposals.filter { it.status == "pending" }.forEach { checked.putIfAbsent(it.id, true) }
        loading = false
    }
    LaunchedEffect(recordId) { load() }

    val pending = proposals.filter { it.status == "pending" }
    val done = proposals.filter { it.status != "pending" }
    val selected = pending.filter { checked[it.id] == true }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 목록") }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        if (loading) {
            CircularProgressIndicator(Modifier.padding(32.dp).align(Alignment.CenterHorizontally))
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            val fields = meeting?.fields
            item {
                Section("결정 후보", fields?.texts("decisions").orEmpty(), "회의에서 정한 것처럼 들린 말이에요. 확정이 아니에요.")
                Section("정하지 못한 것", fields?.texts("open_questions").orEmpty(), null)
                Spacer(Modifier.height(8.dp))
                Text("할 일 후보 ${pending.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "체크한 것만 할 일로 추가돼요. 제목·담당·기한은 추가한 뒤 고칠 수 있어요(다음 버전).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pending.isEmpty() && done.isEmpty()) {
                    Text("이 녹음에서는 할 일을 찾지 못했어요.", modifier = Modifier.padding(vertical = 12.dp))
                }
            }
            items(pending, key = { it.id }) { p ->
                CandidateRow(p, checked[p.id] == true) { checked[p.id] = it }
            }
            items(done, key = { it.id }) { p -> CandidateRow(p, checked = false, onChecked = null) }
            item {
                TextButton(onClick = { showTranscript = !showTranscript }) {
                    Text(if (showTranscript) "받아쓴 원문 접기" else "받아쓴 원문 보기")
                }
                if (showTranscript) Text(transcript ?: "원문이 없어요", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp)) }
        if (!loading && pending.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy && selected.size < pending.size,
                    onClick = {
                        busy = true
                        scope.launch {
                            val drop = pending.filter { checked[it.id] != true }
                            runCatching { drop.forEach { Server.reject(it) } }
                                .onSuccess { message = "후보 ${drop.size}개를 버렸어요" }
                                .onFailure { message = "버리지 못했어요. 다시 시도해 주세요" }
                            load(); busy = false
                        }
                    },
                ) { Text("선택 안 한 것 버리기") }
                Button(
                    enabled = !busy && selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true
                        scope.launch {
                            // 다른 기기에서 먼저 승인했으면 already-approved로 돌아와 중복 없이 넘어간다(TC-08)
                            val results = runCatching { selected.map { Server.approve(it) } }.getOrNull()
                            message = when {
                                results == null -> "추가하지 못했어요. 인터넷 연결을 확인해 주세요"
                                results.all { it == "approved" || it == "already-approved" } -> "할 일 ${selected.size}개를 추가했어요 · 할 일 탭에서 볼 수 있어요"
                                else -> "일부는 추가하지 못했어요(${results.filterNot { it == "approved" }.joinToString()})"
                            }
                            load(); busy = false
                        }
                    },
                ) { Text("선택한 할 일 추가 (${selected.size})") }
            }
        }
    }
}

@Composable
private fun Section(title: String, lines: List<String>, note: String?) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    lines.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun CandidateRow(p: ProposalRow, checked: Boolean, onChecked: ((Boolean) -> Unit)?) {
    val a = p.after
    val state = when (p.status) {
        "approved" -> "추가됨" to Color(0xFF1E8E5A)
        "rejected" -> "버림" to MaterialTheme.colorScheme.onSurfaceVariant
        "pending" -> null
        else -> "확인 필요(${p.status})" to MaterialTheme.colorScheme.error
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
            if (onChecked != null) Checkbox(checked = checked, onCheckedChange = onChecked)
            Column(Modifier.weight(1f).padding(start = 4.dp, top = 10.dp)) {
                Row {
                    Text(a.str("title").orEmpty(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    state?.let { (label, color) -> Text(label, color = color, style = MaterialTheme.typography.labelMedium) }
                }
                Text(
                    ownerLabel(a.str("owner_kind"), a.str("assignee")) + " · " +
                        dueLabel(a.str("due_date"), a.str("due_time"), a["due_ambiguous"]?.jsonPrimitive?.booleanOrNull == true, a.str("due_text")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("근거: " + p.rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
