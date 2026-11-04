package com.workbrain.app.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.data.Budget
import com.workbrain.app.data.InboxRow
import com.workbrain.app.data.Para
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.TaskRow
import com.workbrain.app.record.RecordingSession
import com.workbrain.app.record.RecordingStore
import com.workbrain.app.record.UploadManager
import com.workbrain.app.record.UploadProgress
import com.workbrain.app.review.Snooze
import com.workbrain.app.tasks.TaskEditSheet
import com.workbrain.app.tasks.TaskLine
import com.workbrain.app.tasks.UNSAVED_TEXT
import com.workbrain.app.tasks.dueDay
import com.workbrain.app.tasks.isLate
import com.workbrain.app.tasks.movedText
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.CONFLICT_TEXT
import com.workbrain.app.ui.HandAccent
import com.workbrain.app.ui.MockCard
import com.workbrain.app.ui.Note
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.SmallButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.isWideWindow
import com.workbrain.app.ui.saveFailText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 오늘 화면에서 다른 탭으로 가는 길(목업 링크: 모든 할 일 · 기록 · 검토함 · 프로젝트 · 설정) */
class TodayNav(
    val openRecord: (recordId: String, title: String) -> Unit,
    val openReview: () -> Unit,
    val openRecords: () -> Unit,
    /** 프로젝트 탭에서 이 열쇠를 연다('all' = 모든 할 일, 'p:ID' = 프로젝트) */
    val openProjects: (String?) -> Unit,
    val openMore: () -> Unit,
)

private val HEAD_DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREA)

/** 기록별로 묶은 검토 대기(목업 '검토 대기': 새 할 일 후보는 기록 화면에서, 변경은 검토함에서) */
private data class Pending(val recordId: String, val title: String, val day: String?, val newCount: Int, val changes: Map<String, Int>, val expired: Boolean)

/**
 * S02 오늘 — 목업 workbrain-dashboard.html 모양(DEC-67).
 * 왼쪽(접힌 화면은 위) '오늘 할 일': 모든 프로젝트·반복실행에서 오늘 마감 + 지연, 남이 맡은 일은 '챙길 팔로업'으로 나눈다.
 *   오늘 끝낸 일은 맨 아래 '오늘 완료'에 취소선으로 남긴다(DEC-64, 목업에 더한 것). 그 밖의 할 일은 '모든 할 일'에서 본다.
 * 오른쪽(접힌 화면은 아래) '검토 대기'(기록별, 승인 버튼 없음)와 '처리 중인 기록'.
 * 비용이 경고선을 넘을 때만 위에 띠. 한 줄 빠른 추가는 입력 해석(FR-08)과 함께 넣는다.
 */
@Composable
fun TodayScreen(nav: TodayNav) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val wide = isWideWindow()
    val uploads by UploadManager.progress.collectAsStateWithLifecycle()

    var tasks by remember { mutableStateOf<List<TaskRow>?>(null) }
    var sources by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pending by remember { mutableStateOf<List<InboxRow>>(emptyList()) }
    var budget by remember { mutableStateOf<Budget?>(null) }
    var cost by remember { mutableStateOf<Int?>(null) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<RecordingSession>>(emptyList()) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var editing by remember { mutableStateOf<TaskRow?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // 할 일이 핵심이라 그것만 실패를 알리고, 나머지(출처·검토 대기·비용·옮길 곳)는 못 읽으면 비워 둔다
    suspend fun load() = coroutineScope {
        val t = async { runCatching { Server.todayTasks() } }
        val src = async { runCatching { Para.sources() }.getOrNull() }
        val pend = async { runCatching { Server.pendingProposals() }.getOrNull() }
        val b = async { runCatching { Server.budget() }.getOrNull() }
        val c = async { runCatching { Server.monthCostKrw() }.getOrNull() }
        val pl = async { runCatching { Para.places() }.getOrNull() }
        t.await().onSuccess { tasks = it }.onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
        src.await()?.let { sources = it }
        pend.await()?.let { pending = it }
        budget = b.await()
        cost = c.await()
        pl.await()?.let { places = it }
        sessions = RecordingStore.list(context, null)
    }
    LaunchedEffect(Unit) { load() }

    fun setDone(t: TaskRow, done: Boolean) = scope.launch {
        val ok = runCatching { Server.setTaskDone(t, done) }.getOrNull()
        message = when (ok) {
            true -> (if (done) "완료했어요 · 아래 ‘오늘 완료’에 남겨 둘게요" else "다시 열었어요 · ${t.title}") to Tone.Good
            false -> CONFLICT_TEXT to Tone.Warn
            null -> "저장하지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
        }
        load()
    }

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

    val today = LocalDate.now(SEOUL)
    val all = tasks.orEmpty()
    // 오늘 할 일 = 오늘 마감 + 지연(목업 dueToday). 기한 없는·모호한·다음 날 이후 할 일은 '모든 할 일'에서
    val due = all.filter { !it.done && dueDay(it)?.let { d -> !d.isAfter(today) } == true }
    val mine = due.filter { it.ownerKind != "other" }
    val late = sortTasks(mine.filter { isLate(it, today) })
    val todays = sortTasks(mine.filter { !isLate(it, today) })
    val follow = sortTasks(due.filter { it.ownerKind == "other" })
    val doneToday = all.filter { it.done }.sortedByDescending { t -> t.doneAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } }

    val pendingGroups = groupPending(pending.filter { !Snooze.isActive(it.snoozedUntil) })
    val processing = sessions.filter { it.recordId != null && it.serverState != "ready" }

    val todayCard: @Composable () -> Unit = {
        TodayCard(
            late, todays, follow, doneToday, loaded = tasks != null, sources,
            onToggle = { t -> setDone(t, !t.done) },
            onOpen = { t -> editError = null; editing = t },
            onPlace = { p -> nav.openProjects(if (p.type == PlaceType.PROJECT) "p:${p.id}" else "a:${p.id}") },
            onAll = { nav.openProjects("all") },
        )
    }
    val reviewCard: @Composable () -> Unit = { ReviewCard(pendingGroups, nav) }
    val processingCard: @Composable () -> Unit = {
        ProcessingCard(processing, uploads, onResume = { s -> UploadManager.resume(context, s) }, onOpenAll = nav.openRecords)
    }

    Column(
        Modifier.fillMaxSize().background(Wb.colors.surface).verticalScroll(rememberScrollState())
            .padding(horizontal = if (wide) 24.dp else 16.dp).padding(bottom = 24.dp),
    ) {
        PageHeader("오늘", "오늘 챙길 것만 모았어요") {
            TextButton(onClick = { scope.launch { message = null; load() } }) { Text("새로고침", color = Wb.colors.link) }
        }
        // 목업 .page-head .sub
        Text("${today.format(HEAD_DATE)} · 모든 프로젝트 · 확정 상태 기준", fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.padding(bottom = 12.dp))
        CostWarn(budget, cost, nav.openMore)
        message?.let { (text, tone) -> Banner(text, tone, Modifier.padding(bottom = 12.dp)) }

        if (wide) {
            // 목업 .dash: 왼쪽 1.25 : 오른쪽 1
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1.25f)) { todayCard() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { reviewCard(); processingCard() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { todayCard(); reviewCard(); processingCard() }
        }

        // 목업 .page-foot
        HorizontalDivider(color = Wb.colors.border, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Note("프로젝트별 상태·요약은 ")
            FootLink("프로젝트") { nav.openProjects(null) }
            Note(", 비용 상세는 ")
            FootLink("더보기") { nav.openMore() }
            Note("에서 봐요.")
        }
    }

    editing?.let { t ->
        TaskEditSheet(t, places, currentPlaceName = t.placeName, error = editError, saving = saving, onDismiss = { editing = null }) { e -> save(t, e) }
    }
}

/** 목업 정렬: 우선순위(높음 먼저), 그다음 기한 */
private fun sortTasks(list: List<TaskRow>) = list.sortedWith(compareBy<TaskRow>({ it.priority ?: 2 }, { dueDay(it) ?: LocalDate.MAX }, { it.title }))

private fun placeOf(t: TaskRow): Place? = when {
    t.projectId != null && t.project != null -> Place(PlaceType.PROJECT, t.projectId, t.project.name)
    t.areaId != null && t.area != null -> Place(PlaceType.AREA, t.areaId, t.area.name)
    else -> null
}

// ── 오늘 할 일 ──

/** 목업 .card-h: 제목 · 건수 · 오른쪽 링크 */
@Composable
private fun CardHead(title: String, count: String?, link: String? = null, onLink: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
        count?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted, modifier = Modifier.padding(start = 8.dp)) }
        Spacer(Modifier.weight(1f))
        link?.let {
            Text("$it →", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onLink).padding(4.dp))
        }
    }
}

/** 목업 .sub-h: 위 구분선 + 작은 굵은 묶음 이름 */
@Composable
private fun SubHead(text: String, color: Color = Wb.colors.muted) {
    HorizontalDivider(color = Wb.colors.border)
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
}

@Composable
private fun TodayCard(
    late: List<TaskRow>,
    todays: List<TaskRow>,
    follow: List<TaskRow>,
    doneToday: List<TaskRow>,
    loaded: Boolean,
    sources: Map<String, String>,
    onToggle: (TaskRow) -> Unit,
    onOpen: (TaskRow) -> Unit,
    onPlace: (Place) -> Unit,
    onAll: () -> Unit,
) {
    MockCard {
        BoxWithConstraints {
            // 목업은 좁아지면(700px) 우선순위 칸을 숨긴다
            val showPriority = maxWidth >= 480.dp
            Column {
                CardHead("오늘 할 일", "${late.size + todays.size}건", "다음 7일 · 전체", onAll)
                val rows: @Composable ColumnScope.(List<TaskRow>) -> Unit = { list ->
                    list.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = Wb.colors.border)
                        TaskLine(t, sources[t.id], showPriority, readOnly = false, onToggle = { onToggle(t) }, onOpen = { onOpen(t) }, place = placeOf(t), onPlace = onPlace)
                    }
                }
                if (late.isNotEmpty()) { SubHead("지연 ${late.size}", Wb.colors.crit); rows(late) }
                if (todays.isNotEmpty()) { SubHead("오늘 ${todays.size}"); rows(todays) }
                if (follow.isNotEmpty()) { SubHead("챙길 팔로업 ${follow.size} · 남이 맡은 일"); rows(follow) }
                if (loaded && late.isEmpty() && todays.isEmpty()) {
                    // 목업 .done-all
                    HorizontalDivider(color = Wb.colors.border)
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("오늘 할 일 끝!", fontFamily = HandAccent, fontSize = 26.sp, color = Wb.colors.good)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("다음 7일 일정은 ", fontSize = 14.sp, color = Wb.colors.muted)
                            Text("모든 할 일", fontSize = 14.sp, color = Wb.colors.link, modifier = Modifier.clickable(onClick = onAll))
                            Text("에서 봐요.", fontSize = 14.sp, color = Wb.colors.muted)
                        }
                    }
                }
                if (doneToday.isNotEmpty()) { SubHead("오늘 완료 ${doneToday.size}"); rows(doneToday) }
            }
        }
    }
}

// ── 검토 대기 ──

private fun changeKind(p: InboxRow): String {
    val keys = p.after.keys
    return when {
        "status" in keys -> "상태 변경"
        keys.any { it.startsWith("due_") } -> "기한 변경"
        keys.any { it == "owner_kind" || it == "assignee" } -> "담당 변경"
        else -> "내용 변경"
    }
}

private fun groupPending(rows: List<InboxRow>): List<Pending> = rows.groupBy { it.recordId ?: "" }.map { (id, list) ->
    val first = list.first()
    val day = first.recordAt?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).let { z -> "${z.monthValue}/${z.dayOfMonth}" } }.getOrNull() }
    val expired = list.any { p -> p.transcriptExpiresAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().isBefore(java.time.Instant.now()) }.getOrNull() } == true }
    Pending(
        recordId = id,
        title = first.recordTitle,
        day = day,
        newCount = list.count { it.targetId == null },
        changes = list.filter { it.targetId != null }.groupingBy(::changeKind).eachCount(),
        expired = expired,
    )
}.sortedByDescending { it.newCount + it.changes.values.sum() }

@Composable
private fun ReviewCard(groups: List<Pending>, nav: TodayNav) {
    MockCard {
        CardHead("검토 대기", "${groups.sumOf { it.newCount + it.changes.values.sum() }}건")
        if (groups.isEmpty()) {
            HorizontalDivider(color = Wb.colors.border)
            Note("검토할 것이 없어요. 녹음을 올리면 할 일 후보가 여기에 기록별로 모여요.", Modifier.padding(16.dp))
        }
        groups.forEach { g ->
            HorizontalDivider(color = Wb.colors.border)
            // 목업 .rv: 아이콘 · 제목·설명·칩 · 버튼
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                Icon(if (g.expired) WbIcons.Trash else WbIcons.Mic, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOfNotNull(if (g.expired) "근거가 지워진 제안" else g.title, g.day.takeIf { !g.expired }).joinToString(" · "),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                    val parts = buildList {
                        if (g.newCount > 0) add("할 일 후보 ${g.newCount}건")
                        g.changes.forEach { (k, n) -> add("$k $n") }
                    }
                    Text(
                        if (g.expired) "${g.title} — 근거 보관 기간이 지나 새 근거 없이는 승인할 수 없어요" else parts.joinToString(" · "),
                        fontSize = 12.sp, color = Wb.colors.muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (g.expired) WbChip("재검토", Tone.Mute)
                        else {
                            if (g.newCount > 0) WbChip("할 일 후보 ${g.newCount}", Tone.Warn)
                            val changed = g.changes.values.sum()
                            if (changed > 0) WbChip("변경 $changed", Tone.Mute)
                        }
                    }
                }
                // 새 할 일 후보는 기록 화면에서 고르고, 변경·재검토는 검토함에서 본다(목업, DEC-23)
                if (g.newCount > 0 && !g.expired) SmallButton("할 일 고르기", primary = true) { nav.openRecord(g.recordId, g.title) }
                else SmallButton("검토함") { nav.openReview() }
            }
        }
        // 목업 .card-f
        HorizontalDivider(color = Wb.colors.border)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Note("AI 제안은 승인해야 반영돼요. 승인 버튼은 기록 화면과 검토함에만 있어요.", Modifier.weight(1f).padding(end = 8.dp))
            SmallButton("검토함 열기", primary = true) { nav.openReview() }
        }
    }
}

// ── 처리 중인 기록 ──

@Composable
private fun ProcessingCard(
    rows: List<RecordingSession>,
    uploads: Map<String, UploadProgress>,
    onResume: (RecordingSession) -> Unit,
    onOpenAll: () -> Unit,
) {
    MockCard {
        CardHead("처리 중인 기록", "${rows.size}건", "기록 전체", onOpenAll)
        if (rows.isEmpty()) {
            HorizontalDivider(color = Wb.colors.border)
            Note("처리 중인 기록이 없어요.", Modifier.padding(16.dp))
        }
        rows.forEach { s ->
            HorizontalDivider(color = Wb.colors.border)
            val live = uploads[s.id]
            val failed = s.serverState == "failed"
            // 목업 .pr: 아이콘 · 제목/작은 설명/진행 막대 · 오른쪽 상태
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenAll).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (s.channel == "recording") WbIcons.Mic else WbIcons.Document, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                    Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                    Text(
                        listOf(sessionWhen(s), live?.message ?: if (failed) "처리하지 못했어요" else "백그라운드에서 처리 중").joinToString(" · "),
                        fontSize = 12.sp, color = if (failed || live?.isError == true) Wb.colors.crit else Wb.colors.muted,
                    )
                    if (!failed) {
                        // 단계별 퍼센트는 서버가 주지 않아 진행 중임만 보인다(목업 .bar)
                        LinearProgressIndicator(
                            Modifier.padding(top = 5.dp).widthIn(max = 220.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = Wb.colors.primary, trackColor = Wb.colors.surface,
                        )
                    }
                }
                when {
                    failed -> WbChip("처리 실패", Tone.Crit)
                    // 이 화면에서 돌고 있지 않으면(앱을 다시 연 뒤) 바로 다시 돌릴 수 있게 둔다 — 녹음 탭 '이어서 처리'와 같음
                    live == null -> SmallButton("이어서 처리") { onResume(s) }
                    else -> WbChip("진행 중", Tone.Info)
                }
            }
        }
    }
}

private fun sessionWhen(s: RecordingSession): String {
    val at = java.time.Instant.ofEpochMilli(s.startedAt).atZone(SEOUL)
    val min = (s.durationMs / 60_000).coerceAtLeast(1)
    return "${if (s.channel == "recording") "녹음" else "파일"} ${min}분 · ${at.monthValue}/${at.dayOfMonth} %02d:%02d".format(at.hour, at.minute)
}

// ── 비용 경고 · 바닥 줄 ──

/** 목업 .cost-warn: 이번 청구 주기 비용이 경고선을 넘었을 때만 */
@Composable
private fun CostWarn(budget: Budget?, cost: Int?, onOpen: () -> Unit) {
    if (budget == null || cost == null || cost < budget.warnKrw) return
    val won = NumberFormat.getNumberInstance(Locale.KOREA)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(8.dp))
            .background(Wb.colors.warn.copy(alpha = 0.10f)).border(1.dp, Wb.colors.warn.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WbIcons.Coin, contentDescription = null, tint = Wb.colors.warn, modifier = Modifier.size(18.dp))
        Text(
            buildAnnotatedString {
                append("이번 청구 주기 비용이 ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("₩${won.format(budget.warnKrw)} 경고선") }
                append("을 넘었어요(₩${won.format(cost)}). ₩${won.format(budget.waitKrw)}을 넘으면 새 전사·분석은 자동으로 기다려요.")
            },
            fontSize = 14.sp, color = Wb.colors.body, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text("더보기에서 보기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link, modifier = Modifier.clickable(onClick = onOpen))
    }
}

@Composable
private fun FootLink(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, color = Wb.colors.link, modifier = Modifier.clickable(onClick = onClick))
}
