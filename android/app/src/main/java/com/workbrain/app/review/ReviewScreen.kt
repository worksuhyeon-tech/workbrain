package com.workbrain.app.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.InboxRow
import com.workbrain.app.data.ProcessedRow
import com.workbrain.app.data.Server
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.EmptyState
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SegmentPills
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.TwoPane
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.GlassTint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

// 검토함 탭(FR-28, DEC-63, 목업 S06): 오늘 · 미룬 항목 · 처리됨
private const val TAB_TODAY = "today"
private const val TAB_SNOOZED = "snoozed"
private const val TAB_DONE = "done"

/**
 * S06 검토함: 모든 녹음에서 나온 승인 대기 할 일 후보를 기록별로 모아 한 번에 고른다.
 * 승인은 기록 상세와 같은 함수로 하되 위치를 inbox로 남긴다(DEC-23).
 * 오늘 못 볼 후보는 미뤄 두면 고른 날 0시에 '오늘'로 다시 나온다(FR-28). 미뤄도 할 일·근거 보관 기한은 그대로다(DEC-15).
 * detailPane이 있으면(펼친 화면) 왼쪽 목록 / 오른쪽 기록 상세 두 칸. refreshKey가 바뀌면 다시 읽고, 추가·버리기 뒤에는 onChanged로 알린다.
 * onCountChanged: 아래 메뉴 배지 숫자. 오늘 검토할 것만 센다(DEC-63)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    onOpenRecord: (recordId: String, title: String) -> Unit,
    onCountChanged: (Int) -> Unit,
    detailPane: (@Composable () -> Unit)? = null,
    refreshKey: Int = 0,
    onChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(TAB_TODAY) }
    var items by remember { mutableStateOf<List<InboxRow>?>(null) }
    var processed by remember { mutableStateOf<List<ProcessedRow>?>(null) }
    // 체크: 오늘은 처음에 모두 체크(기존 방식), 미룬 항목은 처음에 체크하지 않는다(목업: 미룬 것은 '지금 검토' 뒤 승인)
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    val snoozeChecked = remember { mutableStateMapOf<String, Boolean>() }
    // 고친 값(승인 전까지 이 화면에만): 후보 ID → 승인 함수에 넘길 p_edits
    val edits = remember { mutableStateMapOf<String, JsonObject>() }
    var editing by remember { mutableStateOf<Pair<InboxRow, CandidateField>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    // 미루기 시트 대상, 버리기 확인 창 대상
    var snoozeTarget by remember { mutableStateOf<List<InboxRow>?>(null) }
    var discardTarget by remember { mutableStateOf<List<InboxRow>?>(null) }

    val list = items.orEmpty()
    val (snoozedRows, todayRows) = list.partition { Snooze.isActive(it.snoozedUntil) }

    suspend fun load() {
        runCatching { Server.pendingProposals() }
            .onSuccess { rows ->
                items = rows
                val today = rows.filterNot { Snooze.isActive(it.snoozedUntil) }
                today.forEach { checked.putIfAbsent(it.id, true) }
                onCountChanged(today.size)
            }
            .onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
    }
    suspend fun loadProcessed() {
        runCatching { Server.processedProposals() }
            .onSuccess { processed = it }
            .onFailure { message = "처리한 후보를 불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
    }
    LaunchedEffect(refreshKey) { load() }
    // 처리됨은 그 탭을 열 때만 읽는다(최근 100건)
    LaunchedEffect(tab, refreshKey) { if (tab == TAB_DONE) loadProcessed() }

    val todaySelected = todayRows.filter { checked[it.id] == true }
    val snoozedSelected = snoozedRows.filter { snoozeChecked[it.id] == true }

    // 버리기·추가·미루기 모두 체크한 후보에만 적용한다(사용자 요청 10/26: '체크 안 한 것을 버리는' 방식은 알아보기 어려움)
    fun discard(rows: List<InboxRow>) {
        busy = true
        scope.launch {
            message = runCatching { rows.forEach { Server.reject(it.proposal(), "inbox") } }
                .fold({ "후보 ${rows.size}개를 버렸어요" to Tone.Mute }, { "버리지 못했어요. 다시 시도해 주세요" to Tone.Crit })
            load(); busy = false; onChanged()
        }
    }
    fun approve(rows: List<InboxRow>) {
        busy = true
        scope.launch {
            // 다른 기기에서 먼저 승인했으면 already-approved로 돌아와 중복 없이 넘어간다(TC-08)
            val results = runCatching { rows.map { Server.approve(it.proposal(), "inbox", edits[it.id]) } }.getOrNull()
            message = when {
                results == null -> "추가하지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
                results.all { it == "approved" || it == "already-approved" } ->
                    "할 일 ${rows.size}개를 추가했어요 · 오늘 탭에서 볼 수 있어요" to Tone.Good
                else -> "일부는 추가하지 못했어요(${results.filterNot { it == "approved" }.joinToString()})" to Tone.Warn
            }
            load(); busy = false; onChanged()
        }
    }
    // until = null이면 지금 검토(미룸 해제). 옮긴 후보는 체크를 지워, 다시 '오늘'로 오면 기본값(체크됨)으로 보이게 한다
    fun snooze(rows: List<InboxRow>, until: LocalDate?) {
        busy = true
        scope.launch {
            val updated = runCatching { Server.snoozeProposals(rows.map { it.id }, until) }.getOrNull()
            message = when {
                updated == null -> "옮기지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
                until == null -> "후보 ${updated}개를 오늘 검토로 가져왔어요" to Tone.Good
                else -> "후보 ${updated}개를 ${Snooze.label(until)}까지 미뤘어요 · 그날 ‘오늘’에 다시 나와요" to Tone.Mute
            }
            rows.forEach { checked.remove(it.id); snoozeChecked.remove(it.id) }
            load(); busy = false; onChanged()
        }
    }

    // 기록별 묶음 + 후보 카드. 오늘·미룬 항목이 같이 쓰고, 체크 상태만 따로 둔다.
    // 기록이 하나뿐이면 위 '전체 선택'이 곧 그 기록의 선택이라, 묶음 줄은 체크 없이 기록 이름만 낮게 보인다(11/03 여백 요청)
    fun LazyListScope.candidateGroups(groups: Map<Pair<String?, String>, List<InboxRow>>, marks: SnapshotStateMap<String, Boolean>, snoozedTab: Boolean) {
        groups.entries.forEachIndexed { index, (record, group) ->
            if (groups.size == 1) item(key = "h-$tab-${record.first}") {
                Text(
                    "${record.second} · ${group.size}",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = Wb.colors.muted,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            if (groups.size > 1) item(key = "h-$tab-${record.first}") {
                val count = group.count { marks[it.id] == true }
                GroupHeader(
                    label = "${record.second} · ${group.size}",
                    state = when (count) { group.size -> ToggleableState.On; 0 -> ToggleableState.Off; else -> ToggleableState.Indeterminate },
                    enabled = !busy,
                    // 전부 체크돼 있으면 모두 풀고, 아니면(일부·없음) 모두 체크한다
                    onToggle = { val all = count == group.size; group.forEach { marks[it.id] = !all } },
                    // 낮은 줄로: 체크 칸 40dp, 앞 묶음 카드와는 조금 띄운다
                    rowHeight = 40.dp,
                    modifier = if (index == 0) Modifier else Modifier.padding(top = 8.dp),
                )
            }
            items(group, key = { it.id }) { row ->
                CandidateCard(
                    after = JsonObject(row.after + edits[row.id].orEmpty()),
                    rationale = row.rationale,
                    status = row.status,
                    checked = marks[row.id] == true,
                    onChecked = { marks[row.id] = it },
                    source = "회의록 보기",
                    onOpenSource = record.first?.let { id -> { onOpenRecord(id, record.second) } },
                    edited = row.id in edits,
                    // 변경 제안은 전후를 보고 승인·버림만 한다(고친 값은 새 할 일 후보에만). 미룬 후보는 '지금 검토' 뒤에 고친다
                    onEdit = if (row.targetId == null && !snoozedTab) ({ field -> editing = row to field }) else null,
                    before = row.before,
                    badges = if (snoozedTab) listOfNotNull(Snooze.label(row.snoozedUntil)?.let { "미룸 $it" to Tone.Info }) else emptyList(),
                    footnote = if (snoozedTab) retentionNote(row) else null,
                )
            }
        }
    }

    val inbox: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
            PageHeader("검토함", "꼼꼼히 살펴봐요", Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = {
                    scope.launch { message = null; load(); if (tab == TAB_DONE) loadProcessed() }
                }) { Text("새로고침", color = Wb.colors.link) }
            }
            val tabRows = when (tab) { TAB_TODAY -> todayRows; TAB_SNOOZED -> snoozedRows; else -> emptyList() }
            val marks = if (tab == TAB_SNOOZED) snoozeChecked else checked
            val tabSelected = if (tab == TAB_SNOOZED) snoozedSelected else todaySelected
            val groups = tabRows.groupBy { it.recordId to it.recordTitle }
            // 목업처럼 [오늘 | 미룬 항목 | 처리됨] ☐ 전체 선택을 한 줄에(사용자 요청 11/05: 꽉 찬 탭은 여백이 커 보였음).
            // 전체 선택은 같은 녹음을 여러 번 올려 후보가 쌓였을 때 한 번에 고르고 버리는 용도(10/26). 고른 개수는 아래 버튼에 보인다.
            // 전체 선택은 줄 오른쪽 끝(11/06 사용자 요청). 좁아서 다음 줄로 내려가도 오른쪽에 붙는다
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SegmentPills(
                    listOf(TAB_TODAY to "오늘 ${todayRows.size}", TAB_SNOOZED to "미룬 항목 ${snoozedRows.size}", TAB_DONE to "처리됨"),
                    value = tab,
                    compact = true,
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) { tab = it }
                if (tabRows.isNotEmpty()) {
                    Box(Modifier.weight(1f).align(Alignment.CenterVertically), contentAlignment = Alignment.CenterEnd) {
                        GroupHeader(
                            label = "전체 선택",
                            state = when (tabSelected.size) { tabRows.size -> ToggleableState.On; 0 -> ToggleableState.Off; else -> ToggleableState.Indeterminate },
                            enabled = !busy,
                            onToggle = { val all = tabSelected.size == tabRows.size; tabRows.forEach { marks[it.id] = !all } },
                            rowHeight = 40.dp,
                            fill = false,
                        )
                    }
                }
            }
            if (tabRows.isNotEmpty()) HorizontalDivider(color = Wb.colors.border, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            ) {
                when (tab) {
                    TAB_TODAY -> {
                        if (items != null && todayRows.isEmpty()) item {
                            EmptyState(
                                "검토 끝! 깔끔해요",
                                if (snoozedRows.isEmpty()) "녹음을 올리면 할 일 후보가 여기에 모여요."
                                else "미룬 후보 ${snoozedRows.size}개는 그날 여기에 다시 나와요.",
                                icon = WbIcons.Review, tint = GlassTint.Green,
                            )
                        }
                        candidateGroups(groups, checked, snoozedTab = false)
                    }
                    TAB_SNOOZED -> {
                        if (items != null && snoozedRows.isEmpty()) item { QuietText("미뤄 둔 후보가 없어요.") }
                        if (snoozedRows.isNotEmpty()) item {
                            Text(
                                "미룬 날 0시에 ‘오늘’로 다시 나와요. 미뤄도 근거 보관 기간은 줄어들고, 음성만 지난 후보는 전사로 검토할 수 있어요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Wb.colors.muted,
                            )
                        }
                        candidateGroups(groups, snoozeChecked, snoozedTab = true)
                    }
                    else -> {
                        val done = processed
                        if (done != null && done.isEmpty()) item { QuietText("아직 처리한 후보가 없어요.") }
                        if (!done.isNullOrEmpty()) item {
                            Text("최근에 처리한 후보예요(최대 100개).", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
                        }
                        items(done.orEmpty(), key = { "done-${it.row.id}" }) { p ->
                            CandidateCard(
                                after = p.row.after,
                                rationale = p.row.rationale,
                                status = p.row.status,
                                checked = false,
                                onChecked = null,
                                source = "${p.row.recordTitle} · 회의록 보기",
                                onOpenSource = p.row.recordId?.let { id -> { onOpenRecord(id, p.row.recordTitle) } },
                                before = p.row.before,
                                badges = listOfNotNull(Snooze.date(p.processedAt)?.let { "${it.monthValue}/${it.dayOfMonth} 처리" to Tone.Mute }),
                            )
                        }
                    }
                }
            }
            message?.let { (text, tone) -> Banner(text, tone, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            // 아래 줄: 오늘 = 버리기·미루기·추가, 미룬 항목 = 버리기·미루기 변경·지금 검토(목업), 처리됨 = 없음
            if (tabRows.isNotEmpty()) {
                HorizontalDivider(color = Wb.colors.border)
                Row(
                    Modifier.fillMaxWidth().background(Wb.colors.card).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canAct = !busy && tabSelected.isNotEmpty()
                    SecondaryButton(
                        "버리기",
                        enabled = canAct,
                        onClick = { if (tabSelected.size > 1) discardTarget = tabSelected else discard(tabSelected) },
                    )
                    SecondaryButton(
                        if (tab == TAB_SNOOZED) "미루기 변경" else "미루기",
                        enabled = canAct,
                        onClick = { snoozeTarget = tabSelected },
                    )
                    if (tab == TAB_SNOOZED) {
                        PrimaryButton("지금 검토 (${tabSelected.size})", enabled = canAct, modifier = Modifier.weight(1f), onClick = { snooze(tabSelected, null) })
                    } else {
                        PrimaryButton("선택한 할 일 추가 (${tabSelected.size})", enabled = canAct, modifier = Modifier.weight(1f), onClick = { approve(tabSelected) })
                    }
                }
            }
        }
    }

    if (detailPane != null) TwoPane(leftWeight = 0.45f, left = inbox, right = detailPane) else inbox()

    discardTarget?.let { rows ->
        DiscardConfirmDialog(rows.size, onDismiss = { discardTarget = null }) { discardTarget = null; discard(rows) }
    }

    snoozeTarget?.let { rows ->
        val expiries = rows.map { it.transcriptExpiresAt }
        SnoozeSheet(
            count = rows.size,
            lastAllowed = Snooze.lastAllowed(expiries),
            evidenceUntil = expiries.mapNotNull(Snooze::date).minOrNull(),
            onPick = { date -> snoozeTarget = null; snooze(rows, date) },
            onDismiss = { snoozeTarget = null },
        )
    }

    editing?.let { (row, field) ->
        CandidateEditSheet(
            current = JsonObject(row.after + edits[row.id].orEmpty()),
            field = field,
            onDismiss = { editing = null },
            onApply = { edits[row.id] = it; checked[row.id] = true; editing = null },
        )
    }
}

/**
 * 미룬 후보의 두 보관 기한(Design §3.7): 근거 전사(지나면 승인 불가)와 원본 음성(지나도 전사로 검토 가능).
 * 음성이 없는 기록(가져온 글)은 근거만 보인다
 */
private fun retentionNote(row: InboxRow): String? {
    val evidence = Snooze.label(row.transcriptExpiresAt)?.let { "근거 보관 ${it}까지" }
    val audio = row.audio?.let { a ->
        when {
            a.purgedAt != null || Snooze.isPast(a.expiresAt) -> "음성 보관 끝남"
            else -> Snooze.label(a.expiresAt)?.let { "음성 ${it}까지" }
        }
    }
    return listOfNotNull(evidence, audio).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun QuietText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted, modifier = Modifier.padding(vertical = 16.dp))
}

/**
 * 녹음별 묶음 제목·전체 선택 줄: 왼쪽 체크로 후보를 한 번에 고르거나 푼다(일부만 골랐으면 '–').
 * 줄 전체가 눌리므로 묶음 제목은 낮은 줄(rowHeight 40dp)로 둬도 누르기 쉽다
 */
@Composable
private fun GroupHeader(
    label: String,
    state: ToggleableState,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 48.dp,
    /** false면 줄 전체가 아니라 체크+글자만큼(탭 줄 오른쪽 '전체 선택') */
    fill: Boolean = true,
) {
    Row(
        (if (fill) modifier.fillMaxWidth() else modifier)
            .triStateToggleable(state = state, enabled = enabled, role = Role.Checkbox, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 카드 안 체크와 같은 세로줄에 오도록 카드 안쪽 여백(4dp)만큼 띄운다
        TriStateCheckbox(
            state = state,
            onClick = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Wb.colors.primary, uncheckedColor = Wb.colors.borderStrong),
            modifier = Modifier.padding(start = 4.dp).size(rowHeight),
        )
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = Wb.colors.muted)
    }
}
