package com.workbrain.app.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import com.workbrain.app.data.InboxRow
import com.workbrain.app.data.Server
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.EmptyState
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.TwoPane
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.GlassTint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * S06 검토함(1차): 모든 녹음에서 나온 승인 대기 할 일 후보를 기록별로 모아 한 번에 고른다.
 * 승인은 기록 상세와 같은 함수로 하되 위치를 inbox로 남긴다(DEC-23).
 * detailPane이 있으면(펼친 화면) 왼쪽 목록 / 오른쪽 기록 상세 두 칸. refreshKey가 바뀌면 다시 읽고, 추가·버리기 뒤에는 onChanged로 알린다.
 */
@Composable
fun ReviewScreen(
    onOpenRecord: (recordId: String, title: String) -> Unit,
    onCountChanged: (Int) -> Unit,
    detailPane: (@Composable () -> Unit)? = null,
    refreshKey: Int = 0,
    onChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<InboxRow>?>(null) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    // 고친 값(승인 전까지 이 화면에만): 후보 ID → 승인 함수에 넘길 p_edits
    val edits = remember { mutableStateMapOf<String, JsonObject>() }
    var editing by remember { mutableStateOf<InboxRow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }

    suspend fun load() {
        runCatching { Server.pendingProposals() }
            .onSuccess { rows ->
                items = rows
                rows.forEach { checked.putIfAbsent(it.id, true) }
                onCountChanged(rows.size)
            }
            .onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
    }
    LaunchedEffect(refreshKey) { load() }

    val list = items.orEmpty()
    val selected = list.filter { checked[it.id] == true }

    val inbox: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
            PageHeader("검토함", "꼼꼼히 살펴봐요", Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { scope.launch { message = null; load() } }) { Text("새로고침", color = Wb.colors.link) }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                if (items != null && list.isEmpty()) item {
                    EmptyState("검토 끝! 깔끔해요", "녹음을 올리면 할 일 후보가 여기에 모여요.", icon = WbIcons.Review, tint = GlassTint.Green)
                }
                list.groupBy { it.recordId to it.recordTitle }.forEach { (record, rows) ->
                    item(key = "h-${record.first}") {
                        val count = rows.count { checked[it.id] == true }
                        GroupHeader(
                            label = "${record.second} · ${rows.size}",
                            state = when (count) { rows.size -> ToggleableState.On; 0 -> ToggleableState.Off; else -> ToggleableState.Indeterminate },
                            enabled = !busy,
                            // 전부 체크돼 있으면 모두 풀고, 아니면(일부·없음) 모두 체크한다
                            onToggle = { val all = count == rows.size; rows.forEach { checked[it.id] = !all } },
                        )
                    }
                    items(rows, key = { it.id }) { row ->
                        CandidateCard(
                            after = JsonObject(row.after + edits[row.id].orEmpty()),
                            rationale = row.rationale,
                            status = row.status,
                            checked = checked[row.id] == true,
                            onChecked = { checked[row.id] = it },
                            source = "회의록 보기",
                            onOpenSource = record.first?.let { id -> { onOpenRecord(id, record.second) } },
                            edited = row.id in edits,
                            // 변경 제안은 전후를 보고 승인·버림만 한다(고친 값은 새 할 일 후보에만, approve_proposal 규칙)
                            onEdit = if (row.targetId == null) ({ editing = row }) else null,
                            before = row.before,
                        )
                    }
                }
            }
            message?.let { (text, tone) -> Banner(text, tone, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            if (list.isNotEmpty()) {
                HorizontalDivider(color = Wb.colors.border)
                Row(
                    Modifier.fillMaxWidth().background(Wb.colors.card).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryButton(
                        "나머지 버리기",
                        enabled = !busy && selected.size < list.size,
                        onClick = {
                            busy = true
                            scope.launch {
                                val drop = list.filter { checked[it.id] != true }
                                message = runCatching { drop.forEach { Server.reject(it.proposal(), "inbox") } }
                                    .fold({ "후보 ${drop.size}개를 버렸어요" to Tone.Mute }, { "버리지 못했어요. 다시 시도해 주세요" to Tone.Crit })
                                load(); busy = false; onChanged()
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
                                val results = runCatching { selected.map { Server.approve(it.proposal(), "inbox", edits[it.id]) } }.getOrNull()
                                message = when {
                                    results == null -> "추가하지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
                                    results.all { it == "approved" || it == "already-approved" } ->
                                        "할 일 ${selected.size}개를 추가했어요 · 오늘 탭에서 볼 수 있어요" to Tone.Good
                                    else -> "일부는 추가하지 못했어요(${results.filterNot { it == "approved" }.joinToString()})" to Tone.Warn
                                }
                                load(); busy = false; onChanged()
                            }
                        },
                    )
                }
            }
        }
    }

    if (detailPane != null) TwoPane(leftWeight = 0.45f, left = inbox, right = detailPane) else inbox()

    editing?.let { row ->
        CandidateEditSheet(
            current = JsonObject(row.after + edits[row.id].orEmpty()),
            onDismiss = { editing = null },
            onApply = { edits[row.id] = it; checked[row.id] = true; editing = null },
        )
    }
}

/** 녹음별 묶음 제목: 왼쪽 체크로 그 녹음의 후보를 한 번에 고르거나 푼다(일부만 골랐으면 '–') */
@Composable
private fun GroupHeader(label: String, state: ToggleableState, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .triStateToggleable(state = state, enabled = enabled, role = Role.Checkbox, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 카드 안 체크와 같은 세로줄에 오도록 카드 안쪽 여백(4dp)만큼 띄운다
        TriStateCheckbox(
            state = state,
            onClick = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Wb.colors.primary, uncheckedColor = Wb.colors.borderStrong),
            modifier = Modifier.padding(start = 4.dp).size(48.dp),
        )
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = Wb.colors.muted)
    }
}
