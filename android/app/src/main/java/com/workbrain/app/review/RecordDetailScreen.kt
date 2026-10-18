package com.workbrain.app.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.MeetingRow
import com.workbrain.app.data.ProposalRow
import com.workbrain.app.data.Server
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.HandAccent
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SectionLabel
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private fun JsonObject.texts(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.str("text")?.takeIf(String::isNotBlank) }

/**
 * S05 기록 상세: 회의 정리(결정 후보·미결정)와 할 일 후보.
 * 회의록을 본 자리에서 할 일을 골라 바로 추가한다. 승인 기록은 검토함과 같다(DEC-23). AI가 직접 할 일을 만들지 않는다.
 */
@Composable
fun RecordDetailScreen(recordId: String, title: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var firstLoad by remember { mutableStateOf(true) }
    var meeting by remember { mutableStateOf<MeetingRow?>(null) }
    var proposals by remember { mutableStateOf<List<ProposalRow>>(emptyList()) }
    var transcripts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showTranscript by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }

    suspend fun load() {
        runCatching {
            meeting = Server.meeting(recordId)
            proposals = Server.proposals(recordId)
            transcripts = Server.transcripts(recordId)
        }.onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
        proposals.filter { it.status == "pending" }.forEach { checked.putIfAbsent(it.id, true) }
        firstLoad = false
    }
    LaunchedEffect(recordId) { load() }

    val pending = proposals.filter { it.status == "pending" }
    val done = proposals.filter { it.status != "pending" }
    val selected = pending.filter { checked[it.id] == true }

    Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 4.dp)) { Text("← 녹음", color = Wb.colors.link) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink)
            Text(
                if (!firstLoad && pending.isEmpty()) "정리 끝!" else "거의 다 왔어요",
                fontFamily = HandAccent,
                fontSize = 20.sp,
                color = Wb.colors.muted,
            )
        }
        if (firstLoad) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Wb.colors.primary) }
        } else LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            val fields = meeting?.fields
            val decisions = fields?.texts("decisions").orEmpty()
            val questions = fields?.texts("open_questions").orEmpty()
            if (decisions.isNotEmpty() || questions.isNotEmpty()) item {
                WbCard {
                    MeetingSection("결정 후보", "회의에서 정한 것처럼 들린 말이에요. 확정은 아니에요.", decisions)
                    if (decisions.isNotEmpty() && questions.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Wb.colors.border)
                    MeetingSection("정하지 못한 것", null, questions)
                }
            }
            item {
                SectionLabel("할 일 후보 ${pending.size}")
                if (pending.isEmpty() && done.isEmpty()) {
                    Text("이 녹음에서는 할 일을 찾지 못했어요.", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted)
                } else if (pending.isNotEmpty()) {
                    Text(
                        "체크한 것만 할 일로 추가돼요. 제목·담당·기한 고치기는 다음 버전에서 돼요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Wb.colors.muted,
                    )
                }
            }
            items(pending, key = { it.id }) { p ->
                CandidateCard(p.after, p.rationale, p.status, checked[p.id] == true, onChecked = { checked[p.id] = it })
            }
            items(done, key = { it.id }) { p -> CandidateCard(p.after, p.rationale, p.status, checked = false, onChecked = null) }
            item {
                // 교정본이 있으면 그것을 먼저 보여 주고, 받아쓴 원문은 따로 볼 수 있게 둔다(원문은 고치지 않음, DEC-36)
                val corrected = transcripts["term-corrected"]
                val original = transcripts["stt-original"]
                val shown = if (corrected != null && !showOriginal) corrected else original
                SectionLabel(if (corrected != null && !showOriginal) "전사 · 업무 용어 교정본" else "받아쓴 원문")
                WbCard {
                    if (showTranscript) {
                        Text(shown ?: "원문이 없어요", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.body)
                    }
                    Row {
                        TextButton(onClick = { showTranscript = !showTranscript }) {
                            Text(if (showTranscript) "접기" else "전사 펼치기", color = Wb.colors.link)
                        }
                        if (showTranscript && corrected != null) {
                            TextButton(onClick = { showOriginal = !showOriginal }) {
                                Text(if (showOriginal) "교정본 보기" else "받아쓴 원문 보기", color = Wb.colors.link)
                            }
                        }
                    }
                }
            }
        }
        message?.let { (text, tone) -> Banner(text, tone, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        if (!firstLoad && pending.isNotEmpty()) {
            HorizontalDivider(color = Wb.colors.border)
            Row(
                Modifier.fillMaxWidth().background(Wb.colors.card).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(
                    "나머지 버리기",
                    enabled = !busy && selected.size < pending.size,
                    onClick = {
                        busy = true
                        scope.launch {
                            val drop = pending.filter { checked[it.id] != true }
                            message = runCatching { drop.forEach { Server.reject(it, "record") } }
                                .fold({ "후보 ${drop.size}개를 버렸어요" to Tone.Mute }, { "버리지 못했어요. 다시 시도해 주세요" to Tone.Crit })
                            load(); busy = false
                        }
                    },
                )
                PrimaryButton(
                    "선택한 할 일 추가 (${selected.size})",
                    enabled = !busy && selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true
                        scope.launch {
                            // 다른 기기에서 먼저 승인했으면 already-approved로 돌아와 중복 없이 넘어간다(TC-08)
                            val results = runCatching { selected.map { Server.approve(it, "record") } }.getOrNull()
                            message = when {
                                results == null -> "추가하지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
                                results.all { it == "approved" || it == "already-approved" } ->
                                    "할 일 ${selected.size}개를 추가했어요 · 오늘 탭에서 볼 수 있어요" to Tone.Good
                                else -> "일부는 추가하지 못했어요(${results.filterNot { it == "approved" }.joinToString()})" to Tone.Warn
                            }
                            load(); busy = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MeetingSection(title: String, note: String?, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted) }
    lines.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.body, modifier = Modifier.padding(top = 4.dp)) }
}
