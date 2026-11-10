package com.workbrain.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.LinkedRecord
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.ProjectState
import com.workbrain.app.data.StateProposalRow
import com.workbrain.app.data.StateVersionRow
import com.workbrain.app.data.stateLabel
import com.workbrain.app.review.ReviewBadge
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.EmptyNote
import com.workbrain.app.ui.MockCard
import com.workbrain.app.ui.Note
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.SecH
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SegmentPills
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import java.time.OffsetDateTime
import kotlinx.coroutines.launch

/*
 * 프로젝트 상세 '현재 상태' 카드(FR-09, DEC-86, 목업 S03): 프로젝트 전체가 지금 어디쯤인지 적은 메모.
 * - 지금 상태: 요약 2~3줄 + 상태 칩 + 마지막 반영 · 변경 이력. [고치기]는 아래 서랍(직접 쓰기, 승인 없이 바로 새 버전)
 * - AI 제안(기록을 연결하면 생김): 지금 → 제안 전후, 이유·근거 한 줄, [반영]·[아니요]. 검토함에도 같은 제안이 보인다
 * - 비었을 때: [연결된 기록으로 정리하기](최근 회의 3개로 제안 → 승인해야 반영) · [직접 쓰기]
 * 아카이브한 프로젝트는 읽기만 한다
 */

private val BoxShape = RoundedCornerShape(10.dp)
private const val MAX_SUMMARY = 600

/** 상태 칩 색: 진행 중 초록 · 멈춤 노랑 · 완료 회색 */
fun stateTone(status: String?): Tone = when (status) {
    "paused" -> Tone.Warn
    "done" -> Tone.Mute
    else -> Tone.Good
}

/** ISO 시각 → 서울 'M/D'(못 읽으면 null) */
internal fun monthDay(iso: String?): String? = iso?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).let { z -> "${z.monthValue}/${z.dayOfMonth}" } }.getOrNull()
}

@Composable
fun StateCard(project: ProjectRow, linked: List<LinkedRecord>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember(project.id) { mutableStateOf<StateVersionRow?>(null) }
    var pending by remember(project.id) { mutableStateOf<StateProposalRow?>(null) }
    var loaded by remember(project.id) { mutableStateOf(false) }
    var failed by remember(project.id) { mutableStateOf(false) }
    var message by remember(project.id) { mutableStateOf<Pair<String, Tone>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val working by remember(project.id) { StateWorker.running(context, project.id) }.collectAsState(false)

    suspend fun load() {
        runCatching { ProjectState.current(project.id) to ProjectState.pending(project.id) }
            .onSuccess { (c, p) -> current = c; pending = p; failed = false }
            .onFailure { failed = true }
        loaded = true
    }
    // 처음 열 때, 정리 작업이 끝났을 때(working → false), 반영·저장 뒤에 다시 읽는다
    LaunchedEffect(project.id, working, reloadKey) { if (!working) load() }

    fun decide(p: StateProposalRow, approve: Boolean) {
        busy = true
        scope.launch {
            val result = runCatching { if (approve) ProjectState.approve(p) else ProjectState.reject(p) }.getOrNull()
            message = when (result) {
                null -> "바꾸지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
                "approved", "already-approved" -> "현재 상태에 반영했어요" to Tone.Good
                "rejected", "already-rejected" -> "제안을 넘겼어요 · 기록을 또 연결하면 새로 제안해요" to Tone.Mute
                "conflict" -> "그사이 현재 상태가 바뀌어 반영하지 않았어요 · 이 제안은 닫혔어요" to Tone.Warn
                "stale-revision" -> "제안이 방금 새 회의로 바뀌었어요 · 다시 보고 골라 주세요" to Tone.Warn
                "evidence-missing" -> "근거 전사가 만료돼 반영할 수 없어요" to Tone.Warn
                else -> "바꾸지 못했어요($result)" to Tone.Crit
            }
            load(); busy = false
            // 검토함 배지는 메뉴를 바꿀 때만 세므로, 여기서 처리한 제안이 숫자에 남지 않게 알린다
            ReviewBadge.changed()
        }
    }

    MockCard {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SecH("현재 상태") {
                WbChip("확정", Tone.Mute)
                Spacer(Modifier.weight(1f))
                if (current != null && !project.archived) {
                    TextButton(onClick = { editing = true }, enabled = !busy) { Text("고치기", color = Wb.colors.link) }
                }
            }
            val now = current
            when {
                !loaded -> Note("불러오는 중…")
                failed && now == null -> Note("불러오지 못했어요. 인터넷 연결을 확인해 주세요.")
                now != null -> StateNow(now) { history = true }
                else -> EmptyState(project.archived, linked.isNotEmpty(), hasPending = pending != null, working, busy,
                    onAi = { StateWorker.startFromLinked(context, project.id, linked); message = null },
                    onWrite = { editing = true },
                )
            }
            if (working) WorkingLine()
            pending?.let { p ->
                StateProposalBox(
                    p, currentVersion = now?.version ?: 0, busy = busy || project.archived,
                    title = "AI 제안",
                    onApprove = { decide(p, true) }, onReject = { decide(p, false) },
                )
            }
            message?.let { (text, tone) -> MessageLine(text, tone) }
        }
    }

    if (editing) {
        StateEditSheet(
            project.id, current, onDismiss = { editing = false },
            onSaved = { editing = false; message = "현재 상태를 저장했어요" to Tone.Good; reloadKey++ },
        )
    }
    if (history) StateHistorySheet(project.id) { history = false }
}

@Composable
private fun StateNow(s: StateVersionRow, onHistory: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.summary.orEmpty(), fontSize = 15.sp, lineHeight = 22.sp, color = Wb.colors.body)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WbChip(stateLabel(s.status), stateTone(s.status))
            Text(
                "마지막 반영 ${monthDay(s.createdAt) ?: ""} · ${if (s.appliedBy == "user") "직접 수정" else "AI 제안 승인"}",
                fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onHistory) { Text("변경 이력 ›", fontSize = 13.sp, color = Wb.colors.link) }
        }
    }
}

/**
 * 빈 카드. AI 제안이 이미 기다리고 있으면(hasPending) 정리 버튼을 숨기고 아래 제안을 보라고만 한다
 * (다시 눌러도 서버가 건너뛰지만, 버튼과 제안 상자가 함께 보여 헷갈렸음 — 11/10 사용자 선택)
 */
@Composable
private fun EmptyState(
    archived: Boolean,
    hasLinked: Boolean,
    hasPending: Boolean,
    working: Boolean,
    busy: Boolean,
    onAi: () -> Unit,
    onWrite: () -> Unit,
) {
    val showAi = hasLinked && !hasPending
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EmptyNote(
            "아직 확정된 상태가 없어요",
            when {
                archived -> "아카이브한 프로젝트라 읽기만 할 수 있어요."
                hasPending -> "아래 AI 제안을 확인해 반영하거나, 직접 적을 수 있어요."
                hasLinked -> "연결된 회의로 AI가 정리하거나, 직접 적을 수 있어요. AI 정리는 승인해야 반영돼요."
                else -> "기록을 연결하면 AI가 정리해 제안해요. 지금 직접 적어도 돼요."
            },
        )
        if (!archived) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showAi) {
                PrimaryButton(
                    if (working) "정리하는 중…" else "연결된 기록으로 정리하기",
                    enabled = !working && !busy, modifier = Modifier.weight(1f), onClick = onAi,
                )
            }
            SecondaryButton("직접 쓰기", enabled = !busy, modifier = if (showAi) Modifier else Modifier.weight(1f), onClick = onWrite)
        }
        if (!archived && showAi) Note("최근 회의 ${StateWorker.INIT_RECORDS}개까지 봐요 · 회의 하나에 약 1~3원")
    }
}

@Composable
private fun WorkingLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = Wb.colors.primary)
        Note("AI가 연결된 회의로 현재 상태를 정리하는 중…")
    }
}

@Composable
private fun MessageLine(text: String, tone: Tone) {
    val color = when (tone) {
        Tone.Good -> Wb.colors.good
        Tone.Warn -> Wb.colors.warn
        Tone.Crit -> Wb.colors.crit
        else -> Wb.colors.muted
    }
    Text(text, fontSize = 13.sp, color = color)
}

/**
 * 현재 상태 제안 한 덩어리(프로젝트 상세·검토함 공용): 출처 회의(과거 회의 표시) · 지금 → 제안 · 이유 · 근거 문장 · [반영][아니요].
 * 기준 버전이 지금과 다르면(직접 고친 뒤) 반영해도 충돌로 돌아간다고 알려 준다. onApprove가 null이면 처리한 제안(버튼 없음)
 */
@Composable
fun StateProposalBox(
    p: StateProposalRow,
    currentVersion: Int?,
    busy: Boolean,
    title: String,
    onApprove: (() -> Unit)?,
    onReject: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth().clip(BoxShape).background(Wb.colors.surface).border(1.dp, Wb.colors.border, BoxShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val from = listOfNotNull(p.recordTitle, monthDay(p.recordAt)).joinToString(" ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
            if (p.past) WbChip("과거 회의 기준", Tone.Warn)
            if (p.recordCount > 1) WbChip("회의 ${p.recordCount}개", Tone.Info)
        }
        if (from.isNotBlank()) Note("최근 반영 회의: $from")
        p.beforeSummary?.let { b ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("지금", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted)
                Text(b, fontSize = 13.sp, lineHeight = 19.sp, color = Wb.colors.muted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (p.beforeSummary == null) "처음 상태" else "바뀐 상태", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
            Text(p.summary, fontSize = 15.sp, lineHeight = 22.sp, color = Wb.colors.body)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            p.beforeStatus?.takeIf { it != p.stateStatus }?.let {
                WbChip(stateLabel(it), Tone.Mute)
                Text("→", fontSize = 13.sp, color = Wb.colors.muted)
            }
            WbChip(stateLabel(p.stateStatus), stateTone(p.stateStatus))
        }
        Text("이유: ${p.rationale}", fontSize = 13.sp, color = Wb.colors.body)
        p.quote?.let { Note("“$it”") }
        if (onApprove != null && currentVersion != null && p.staleAgainst(currentVersion)) {
            Note("이 제안을 만든 뒤 현재 상태가 바뀌었어요. 반영하면 충돌로 닫혀요 · [아니요] 뒤 기록을 다시 연결하면 새로 만들어요")
        }
        if (onApprove != null && onReject != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("아니요", enabled = !busy, onClick = onReject, modifier = Modifier.height(40.dp))
                PrimaryButton("반영", enabled = !busy, modifier = Modifier.weight(1f).height(40.dp), onClick = onApprove)
            }
        }
    }
}

/** 직접 쓰기·고치기 서랍(DEC-86 ⑧): 요약 칸 + 상태 3개 + [저장]. 본 버전을 함께 보내 다른 기기와 겹치면 저장하지 않는다 */
@Composable
private fun StateEditSheet(projectId: String, current: StateVersionRow?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(current?.summary.orEmpty()) }
    var status by remember { mutableStateOf(current?.status ?: "active") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    EditSheet(
        if (current == null) "현재 상태 쓰기" else "현재 상태 고치기",
        "프로젝트 전체가 지금 어디쯤인지 2~3줄로 적어요. 끝난 것·남은 것·막힌 이유·다음 고비.",
        canSave = summary.isNotBlank(),
        onDismiss = onDismiss,
        saving = saving, error = error,
        onSave = {
            saving = true; error = null
            scope.launch {
                val result = runCatching { ProjectState.save(projectId, summary.trim(), status, current?.version ?: 0) }
                saving = false
                result.onSuccess {
                    if (it == "saved") onSaved()
                    else error = "다른 기기에서 먼저 바뀌었어요. 닫고 다시 열어 확인해 주세요"
                }.onFailure { error = "저장하지 못했어요. 인터넷 연결을 확인해 주세요" }
            }
        },
    ) {
        OutlinedTextField(
            summary, { summary = it.take(MAX_SUMMARY) }, minLines = 3,
            placeholder = { Text("예: 12건 중 8건 이관 끝. 남은 4건은 정산 규칙 확정 대기 중.") },
            supportingText = { Text("${summary.length}/$MAX_SUMMARY") },
            shape = ControlShape, modifier = Modifier.fillMaxWidth(),
        )
        Text("상태", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(top = 6.dp))
        SegmentPills(listOf("active" to "진행 중", "paused" to "멈춤", "done" to "완료"), status) { status = it }
        Note("완료로 바꿔도 아카이브되지는 않아요. 끝난 프로젝트는 [아카이브로]에서 옮겨요.")
    }
}

/** 변경 이력: 버전마다 날짜 · 직접 수정/AI 제안 승인 · 상태 · 요약(최근 것부터) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateHistorySheet(projectId: String, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf<List<StateVersionRow>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(projectId) {
        runCatching { ProjectState.history(projectId) }.onSuccess { rows = it }.onFailure { failed = true }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x6609141E),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("현재 상태 변경 이력", style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink)
            when {
                failed -> Note("불러오지 못했어요. 인터넷 연결을 확인해 주세요.")
                rows == null -> Note("불러오는 중…")
                rows!!.isEmpty() -> Note("아직 이력이 없어요.")
                else -> rows!!.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = Wb.colors.border)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WbChip(stateLabel(r.status), stateTone(r.status))
                        Note("${monthDay(r.createdAt) ?: ""} · ${if (r.appliedBy == "user") "직접 수정" else "AI 제안 승인"}")
                    }
                    Text(r.summary.orEmpty(), fontSize = 14.sp, lineHeight = 20.sp, color = Wb.colors.body)
                    r.changeReason?.takeIf { r.appliedBy != "user" }?.let { Note("이유: $it") }
                }
            }
        }
    }
}
