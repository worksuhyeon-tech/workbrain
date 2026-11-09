package com.workbrain.app.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.collectAsState
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
import com.workbrain.app.data.Transcript
import com.workbrain.app.more.guidelineStamp
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.HandAccent
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SectionLabel
import com.workbrain.app.ui.SegmentPills
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import com.workbrain.app.ui.TitleDialog
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.record.AttendeesDialog
import com.workbrain.app.record.RecentAttendees
import com.workbrain.app.record.RecordingSession
import com.workbrain.app.record.RecordingStore
import com.workbrain.app.record.TrashNotice
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import com.workbrain.app.data.LinkSuggest
import com.workbrain.app.data.LinkSuggestion
import com.workbrain.app.data.Para
import com.workbrain.app.data.Place
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.RecordClass
import com.workbrain.app.data.RecordInfoRow
import com.workbrain.app.tasks.PlaceField
import com.workbrain.app.tasks.RecipientsSheet
import com.workbrain.app.tasks.UNSORTED
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.SEOUL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.workbrain.app.ui.WbChip

private fun JsonObject.texts(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.str("text")?.takeIf(String::isNotBlank) }

/** 결정 후보 한 줄과 그 근거 구간(전사 문장 번호) */
private data class Decision(val text: String, val segments: Set<Int>)

private fun JsonObject.decisions(): List<Decision> =
    (this["decisions"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el.jsonObject
        val text = o.str("text")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        Decision(text, (o["segments"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }.toSet())
    }

/** 근거 구간이 결정과 겹치는 첫 결정의 번호(DEC-48: 그 결정 카드 밑에 접는다). 없으면 null */
private fun ProposalRow.overlappingDecision(decisions: List<Decision>): Int? =
    decisions.indexOfFirst { d -> d.segments.any { it in segments } }.takeIf { it >= 0 }

/**
 * S05 기록 상세: 회의 정리(결정 후보·미결정)와 할 일 후보.
 * 회의록을 본 자리에서 할 일을 골라 바로 추가한다. 승인 기록은 검토함과 같다(DEC-23). AI가 직접 할 일을 만들지 않는다.
 * onBack이 null이면 펼친 화면의 오른쪽 칸에 들어간 것이라 뒤로 가기가 없다. onChanged: 추가·버리기 뒤 옆 칸 목록을 다시 읽게 알린다.
 * onTrashed: ⋮에서 이 녹음을 휴지통으로 보낸 뒤 화면을 닫고 녹음 목록으로 가게 한다
 */
@Composable
fun RecordDetailScreen(recordId: String, title: String, onBack: (() -> Unit)?, onChanged: () -> Unit = {}, onTrashed: () -> Unit = {}) {
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val scope = rememberCoroutineScope()
    var firstLoad by remember { mutableStateOf(true) }
    var meeting by remember { mutableStateOf<MeetingRow?>(null) }
    // 화면의 회의록이 초안인데 이 기록에 승인본이 따로 있으면 그 승인 시각(승인 뒤 다시 만든 경우, DEC-61)
    var approvedEarlierAt by remember { mutableStateOf<String?>(null) }
    // 지금 설정의 지침 번호: 문서를 만들 때 쓴 번호와 다르면 '새 지침으로 다시 만들기'를 보여 준다
    var currentGuidelineRev by remember { mutableStateOf<Int?>(null) }
    var proposals by remember { mutableStateOf<List<ProposalRow>>(emptyList()) }
    var transcripts by remember { mutableStateOf<Map<String, Transcript>>(emptyMap()) }
    var showTranscript by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    // 고친 값(승인 전까지 이 화면에만): 후보 ID → 승인 함수에 넘길 p_edits
    val edits = remember { mutableStateMapOf<String, JsonObject>() }
    var editing by remember { mutableStateOf<Pair<ProposalRow, CandidateField>?>(null) }
    var recipientsFor by remember { mutableStateOf<ProposalRow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var shownTitle by remember { mutableStateOf(title) }
    var renaming by remember { mutableStateOf(false) }
    // 기록 머리 정보(종류·길이·회의 시각)와 참석자 명단(null = 아직 못 읽음). 회의록 문서는 이 명단 기준으로 참석자를 쓴다(DEC-59)
    var info by remember { mutableStateOf<RecordInfoRow?>(null) }
    var attendees by remember { mutableStateOf<List<String>?>(null) }
    var editingAttendees by remember { mutableStateOf(false) }
    // 할 일 후보 표(DEC-68): 담당 고르기에 내 이름은 '나'로 따로 있으니 팔로업 목록에서 뺀다. '추가할 곳'(null = 정리 안 됨)
    var myNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var place by remember { mutableStateOf<Place?>(null) }
    // 추가한 뒤 체크하지 않고 남은 후보: 버릴지 묻는다(사용자 요청 11/05)
    var leftover by remember { mutableStateOf<List<ProposalRow>?>(null) }
    // 연결된 프로젝트(DEC-69): 머리 칩·'연결된 프로젝트' 창. 프로젝트 이름은 보관된 것까지 모두 읽어 둔다
    var allProjects by remember { mutableStateOf<List<ProjectRow>>(emptyList()) }
    var linkedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    // AI 연결 제안(FR-06, DEC-75): 아직 확인하지 않은 것. [연결]하면 linkedIds로 옮겨 간다
    var suggestions by remember { mutableStateOf<List<LinkSuggestion>>(emptyList()) }
    var projectsOpen by remember { mutableStateOf(false) }
    var linkBusy by remember { mutableStateOf(false) }
    // '추가할 곳'을 사용자가 직접 골랐으면 연결된 프로젝트로 처음 값을 다시 채우지 않는다
    var placeTouched by remember { mutableStateOf(false) }
    // 결정 카드 '할 일로 만들기'(DEC-48): 만들 결정 문장, 이미 있는 할 일 제목(같은 결정을 두 번 만들지 않게)
    var creatingFrom by remember { mutableStateOf<String?>(null) }
    var openTaskTitles by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 결정 밑에 접은 후보를 펼친 결정 번호
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    // 목업 S05: 추출 항목(할 일·결정 후보) ↔ 회의록 문서(FR-30)
    var view by remember { mutableStateOf("fields") }
    val context = LocalContext.current

    // 연결된 프로젝트와 AI 제안은 보조 정보라 못 읽어도 화면은 그린다
    suspend fun loadLinks() {
        runCatching { Para.projectsOfRecord(recordId) }.onSuccess { linkedIds = it }
        runCatching { LinkSuggest.pending(recordId) }.onSuccess { suggestions = it }
    }

    suspend fun load() {
        runCatching {
            meeting = Server.meeting(recordId)
            approvedEarlierAt = if (meeting?.approved == true) null else Server.approvedMeetingAt(recordId)
            proposals = Server.proposals(recordId)
            transcripts = Server.transcripts(recordId)
            openTaskTitles = Server.openTasks().map { it.title.trim() }.toSet()
            currentGuidelineRev = Server.guideline().rev
            info = Server.recordInfo(recordId).also { attendees = it.attendees.orEmpty() }
        }.onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
        loadLinks()
        // 결정과 겹치는 후보는 결정과 중복일 가능성이 커서 처음에는 체크하지 않는다(DEC-48).
        // 검토함에서 미룬 후보도 모르고 추가하지 않게 처음에는 체크하지 않는다(DEC-63)
        val decisions = meeting?.fields?.decisions().orEmpty()
        proposals.filter { it.status == "pending" && !it.isUpdate }
            .forEach { checked.putIfAbsent(it.id, it.overlappingDecision(decisions) == null && !Snooze.isActive(it.snoozedUntil)) }
        firstLoad = false
    }
    LaunchedEffect(recordId) { load() }
    // 추가할 곳·내 이름은 보조 정보라 못 읽어도 화면은 그린다(정리 안 됨으로 추가, 팔로업 목록에 내 이름이 섞일 뿐)
    LaunchedEffect(recordId) {
        runCatching { Para.places() }.onSuccess { places = it }
        runCatching { Server.myNames() }.onSuccess { myNames = it }
        runCatching { Para.allProjects() }.onSuccess { allProjects = it }
    }
    val linkedProjects = linkedIds.mapNotNull { id -> allProjects.firstOrNull { it.id == id } }
    val shownSuggestions = visibleSuggestions(suggestions, allProjects, linkedIds)
    val recordClass = RecordClass.of(info?.classification)
    // 프로젝트 하나에 연결된 기록이면 '추가할 곳' 처음 값을 그 프로젝트로(DEC-69). 여럿이면 사용자가 고른다
    LaunchedEffect(linkedIds, places) {
        if (!placeTouched) {
            place = projectPlace(linkedIds.singleOrNull(), places)
        }
    }

    // 연결·해제·AI 제안 확인: 끝나면 연결 목록과 제안을 다시 읽는다. 아카이브·남의 것은 서버가 거부한다(0032).
    // 제안 [아니요]도 같은 함수(해제)라, 연결돼 있지 않던 곳이면 '제안을 넘겼다'고 알린다
    fun setLinked(p: ProjectRow, linked: Boolean) {
        val wasLinked = p.id in linkedIds
        linkBusy = true
        scope.launch {
            message = runCatching { Para.linkRecord(recordId, p.id, linked) }.fold(
                {
                    when {
                        linked -> "‘${p.name}’에 연결했어요"
                        wasLinked -> "‘${p.name}’ 연결을 풀었어요"
                        else -> "‘${p.name}’ 연결 제안을 넘겼어요 · 다시 제안하지 않아요"
                    } to Tone.Good
                },
                { e -> (if (e.message.orEmpty().contains("WB409")) "아카이브된 프로젝트에는 연결할 수 없어요" else "바꾸지 못했어요. 인터넷 연결을 확인해 주세요") to Tone.Crit },
            )
            loadLinks()
            linkBusy = false
            onChanged()
        }
    }

    // 분류(업무·개인·불확실, DEC-75): 바로 저장. 개인으로 바꾸면 이 기록에는 새 연결 제안을 만들지 않는다
    fun classify(c: RecordClass) {
        // 누른 순간의 값을 읽는다(그릴 때 계산한 값을 잡아 두면 바뀐 뒤에도 옛 값을 본다, 11/08 트리 화살표 결함과 같은 이유)
        if (c == RecordClass.of(info?.classification)) return
        linkBusy = true
        scope.launch {
            message = runCatching { Server.setClassification(recordId, c.value) }.fold(
                { info = info?.copy(classification = c.value); "분류를 바꿨어요 · ${c.label}" to Tone.Good },
                { "바꾸지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit },
            )
            linkBusy = false
        }
    }

    // 기존 할 일 변경 제안은 검토함에서 전후를 보고 승인한다(DEC-23·38). 여기서는 보여 주기만 한다
    val decisions = meeting?.fields?.decisions().orEmpty()
    val pending = proposals.filter { it.status == "pending" && !it.isUpdate }
    val folded = pending.groupBy { it.overlappingDecision(decisions) }
    val loose = folded[null].orEmpty()
    val updates = proposals.filter { it.status == "pending" && it.isUpdate }
    val done = proposals.filter { it.status != "pending" }
    val selected = pending.filter { checked[it.id] == true }
    var confirmDiscard by remember { mutableStateOf(false) }

    // [버리기]는 체크한 후보에만(검토함과 같은 규칙), 추가 뒤 확인 창은 체크하지 않고 남은 후보에
    fun discard(drop: List<ProposalRow>) {
        busy = true
        scope.launch {
            message = runCatching { drop.forEach { Server.reject(it, "record") } }
                .fold({ "후보 ${drop.size}개를 버렸어요" to Tone.Mute }, { "버리지 못했어요. 다시 시도해 주세요" to Tone.Crit })
            load(); busy = false; onChanged()
        }
    }

    fun current(p: ProposalRow) = JsonObject(p.after + edits[p.id].orEmpty())

    // 표에서 고친 칸은 고친 값에 덮고 체크한다(고친 것은 추가하려는 것, 검토함 고치기와 같음)
    fun edit(p: ProposalRow, vararg fields: Pair<String, JsonElement>) {
        edits[p.id] = JsonObject(edits[p.id].orEmpty() + fields)
        checked[p.id] = true
    }

    fun addSelected() {
        val pick = selected
        if (pick.any { current(it).str("title").isNullOrBlank() }) {
            message = "제목이 빈 할 일이 있어요. 제목을 넣어 주세요" to Tone.Warn
            return
        }
        val target = place
        busy = true
        scope.launch {
            // 다른 기기에서 먼저 승인했으면 already-approved로 돌아와 중복 없이 넘어간다(TC-08)
            val results = runCatching { pick.map { Server.approve(it, "record", withPlace(edits[it.id], target)) } }.getOrNull()
            val allAdded = results != null && results.all { it == "approved" || it == "already-approved" }
            message = when {
                results == null -> "추가하지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit
                allAdded -> "할 일 ${pick.size}개를 ‘${target?.name ?: UNSORTED}’에 추가했어요" to Tone.Good
                else -> "일부는 추가하지 못했어요(${results.filterNot { it == "approved" }.joinToString()})" to Tone.Warn
            }
            load(); busy = false; onChanged()
            // 체크하지 않고 남은 후보는 버릴지 묻는다(사용자 요청 11/05). 미룬 후보는 일부러 미뤄 둔 것이라 묻지 않는다
            if (allAdded) {
                leftover = proposals.filter {
                    it.status == "pending" && !it.isUpdate && checked[it.id] != true && !Snooze.isActive(it.snoozedUntil)
                }.ifEmpty { null }
            }
        }
    }

    // 회의록은 버튼을 눌렀을 때만 백그라운드 작업으로 만든다(DEC-56). 화면을 나갔다 와도 '만드는 중'이 이어지고 끝나면 알림이 온다
    val minutes by remember(recordId) { MinutesWorker.state(context, recordId) }.collectAsState(MinutesWorker.State.Idle)
    LaunchedEffect(minutes) {
        when (val m = minutes) {
            MinutesWorker.State.Done -> load()
            is MinutesWorker.State.Failed -> if (meeting?.document == null) message = m.message to Tone.Crit
            else -> Unit
        }
    }
    fun remakeMinutes() {
        message = null
        MinutesWorker.start(context, recordId, shownTitle)
    }

    // ↪ 공유: 화면에 보이는 회의록 버전으로 Word를 만들어 공유 창을 띄운다(FR-29, DEC-60)
    var sharing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // ⋮ 이 녹음 삭제(사용자 요청 11/03, 삼성 음성 녹음 메뉴 참고): 녹음 목록에서 밀어 지우기와 같다.
    // 휴지통에는 이 휴대폰의 녹음만 들어가므로(DEC-51), 이 기록을 이 휴대폰에서 올렸고 처리가 끝났을 때만 보인다.
    // 서버의 회의록·할 일은 그대로 둔다
    val localSession = remember(recordId) {
        RecordingStore.list(context, activeId = null).firstOrNull { it.recordId == recordId }
            ?.takeIf { it.serverState == "ready" || it.serverState == "failed" }
    }
    // 받아쓴 글(txt)은 서버에 음성 길이가 없어서, 이 휴대폰에서 올린 기록이면 기기에 적어 둔 길이를 머리에 쓴다
    val localDurationSec = remember(recordId) {
        RecordingStore.list(context, activeId = null).firstOrNull { it.recordId == recordId }
            ?.durationMs?.div(1000)?.toInt()?.takeIf { it > 0 }
    }
    fun trashRecording(s: RecordingSession) {
        RecordingStore.trash(RecordingStore.dir(context, s.id))
        TrashNotice.pending.value = s
        onTrashed()
    }
    fun shareWord(meetingVersionId: String) {
        sharing = true
        message = null
        scope.launch {
            runCatching { Server.minutesWord(meetingVersionId) }
                .onSuccess { MinutesShare.share(context, it, shownTitle) }
                .onFailure { message = "Word를 만들지 못했어요. 인터넷 연결을 확인하고 다시 눌러 주세요" to Tone.Crit }
            sharing = false
        }
    }

    // 회의록 승인(FR-07, DEC-61): 확인 창을 거쳐 화면에 보이는 버전을 승인한다. 할 일은 바뀌지 않는다
    var confirmApprove by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf(false) }
    fun approveMinutes(meetingVersionId: String) {
        approving = true
        message = null
        scope.launch {
            message = when (runCatching { Server.approveMeeting(meetingVersionId) }.getOrNull()) {
                "approved" -> "회의록을 승인했어요 · 이제 Word에 '초안' 표시가 없어요" to Tone.Good
                "already-approved" -> "이미 승인된 회의록이에요" to Tone.Mute
                // 다른 기기에서 다시 만들어 새 버전이 생긴 경우: 새 내용을 보고 다시 누르게 한다(M14)
                "stale-version" -> "회의록이 새로 만들어졌어요. 새 내용을 확인하고 승인해 주세요" to Tone.Warn
                "no-document" -> "회의록 문서를 먼저 만들어 주세요" to Tone.Warn
                "evidence-missing" -> "전사 보관 기간이 지나 승인할 수 없어요" to Tone.Crit
                else -> "승인하지 못했어요. 인터넷 연결을 확인하고 다시 눌러 주세요" to Tone.Crit
            }
            load(); approving = false; onChanged()
        }
    }

    // 담당 고르기의 팔로업 목록: 기록 참석자 + AI가 짚은 사람, 내 이름은 '나'가 따로 있으니 뺀다
    val followNames = (attendees.orEmpty() + proposals.mapNotNull { it.after.str("assignee") })
        .map { it.trim() }.filter { it.isNotEmpty() && it !in myNames }.distinct()

    @Composable
    fun candidate(p: ProposalRow) = CandidateRow(
        after = current(p),
        rationale = p.rationale,
        status = p.status,
        checked = checked[p.id] == true,
        onChecked = if (p.status == "pending") ({ checked[p.id] = it }) else null,
        followNames = followNames,
        onTitle = { edit(p, "title" to JsonPrimitive(it)) },
        // 담당을 고르면 '말한 사람 기준' 표시는 더 맞지 않아 지운다(DEC-76)
        onOwner = { kind, who -> edit(p, "owner_kind" to JsonPrimitive(kind), "assignee" to (who?.let(::JsonPrimitive) ?: JsonNull), "owner_basis" to JsonNull) },
        onRecipients = { recipientsFor = p },
        onOwnerOther = { editing = p to CandidateField.OwnerOther },
        onDue = { editing = p to CandidateField.Due },
        // 미룸은 승인과 무관해 여기서도 추가할 수 있지만, 미뤄 둔 것임을 보여 준다(FR-28)
        badges = listOfNotNull(Snooze.label(p.snoozedUntil)?.takeIf { Snooze.isActive(p.snoozedUntil) }?.let { "미룸 $it" to Tone.Info }),
    )

    Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        // 맨 위 줄(클로바 노트처럼): 왼쪽 뒤로 가기, 오른쪽 ↪ 공유(Word)·⋮ 메뉴.
        // ↪와 '다시 만들기'는 회의록 문서가 있을 때만(DEC-60), ⋮는 메뉴에 넣을 것이 하나라도 있으면 보인다
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // 녹음·오늘·검토함 어디서 열었든 온 곳으로 돌아가므로 탭 이름 대신 '뒤로'(사용자 요청 11/05)
            if (onBack != null) TextButton(onClick = onBack) { Text("← 뒤로", color = Wb.colors.link) }
            Spacer(Modifier.weight(1f))
            val version = meeting?.takeIf { it.document != null }
            if (version != null) {
                if (sharing) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Wb.colors.primary)
                    }
                } else {
                    IconButton(onClick = { shareWord(version.id) }) {
                        Icon(WbIcons.Share, contentDescription = "회의록 Word 공유", tint = Wb.colors.ink)
                    }
                }
            }
            if (version != null || localSession != null) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(WbIcons.More, contentDescription = "더보기", tint = Wb.colors.ink)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.widthIn(min = 200.dp)) {
                    if (version != null) {
                        DropdownMenuItem(
                            text = { Text("다시 만들기") },
                            enabled = minutes != MinutesWorker.State.Making,
                            onClick = { menuOpen = false; remakeMinutes() },
                        )
                    }
                    localSession?.let { s ->
                        if (version != null) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Wb.colors.border)
                        // 삼성 음성 녹음처럼 메뉴 맨 아래 아이콘 줄(사용자 선택 11/03). 즐겨찾기 등은 나중에 같은 줄에 더한다
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            IconButton(onClick = { menuOpen = false; trashRecording(s) }) {
                                Icon(WbIcons.Trash, contentDescription = "이 녹음 삭제", tint = Wb.colors.ink)
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.clickable { renaming = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(shownTitle, style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink, modifier = Modifier.weight(1f, fill = false))
                Icon(WbIcons.Pencil, contentDescription = "제목 고치기", tint = Wb.colors.muted, modifier = Modifier.padding(start = 6.dp).size(18.dp))
            }
            // 목업 S05 머리: '녹음 · 42분 · 10/16 15:00' 알약(사용자 요청 11/05) + 손글씨 한마디
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                info?.let { RecordMetaPill(it, localDurationSec, Modifier.padding(end = 10.dp)) }
                Text(
                    if (!firstLoad && pending.isEmpty() && updates.isEmpty()) "정리 끝!" else "거의 다 왔어요",
                    fontFamily = HandAccent,
                    fontSize = 20.sp,
                    color = Wb.colors.muted,
                )
            }
            // 목업 S05 머리 프로젝트 칩(+ 분류·AI 제안 칩). 누르면 '관련 프로젝트' 창(DEC-69, 사용자 선택: 탭 대신 창)
            RecordProjectChips(linkedProjects, shownSuggestions, recordClass, onOpen = { projectsOpen = true }, modifier = Modifier.padding(top = 6.dp))
            if (meeting != null) {
                SegmentPills(
                    listOf("fields" to "추출 항목", "doc" to "회의록 문서"),
                    value = view,
                    modifier = Modifier.padding(top = 8.dp),
                ) { view = it }
            }
        }
        if (firstLoad) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Wb.colors.primary) }
        } else LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            if (view == "doc") {
                item {
                    // 문서의 지침 번호 0 = 앱 기본 지침. 번호가 있으면 가져온 지침으로 만든 것
                    val madeRev = meeting?.guidelineRev ?: 0
                    val madeWith = if (madeRev > 0) {
                        listOfNotNull(guidelineStamp(meeting?.guidelineImportedAt, "MM/dd"), "가져온 지침 적용").joinToString(" ")
                    } else "기본 지침으로 만듦"
                    val status = when {
                        meeting?.approved == true -> listOfNotNull("승인", guidelineStamp(meeting?.approvedAt, "MM/dd")).joinToString(" ")
                        approvedEarlierAt != null -> "초안 · 승인본 ${guidelineStamp(approvedEarlierAt, "MM/dd").orEmpty()} 있음"
                        else -> "초안"
                    }
                    MinutesPane(
                        meeting?.document,
                        making = minutes == MinutesWorker.State.Making,
                        status = status,
                        madeWith = madeWith,
                        outdated = currentGuidelineRev?.let { it != madeRev } == true,
                        onMake = ::remakeMinutes,
                    )
                }
            } else {
                val fields = meeting?.fields
                val questions = fields?.texts("open_questions").orEmpty()
                // 이미 있는 할 일이라 새로 만들지 않은 것(DEC-38)
                val tracked = (fields?.get("already_tracked") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("title") }
                // 참석자는 처리 때 찍어 둔 값이 아니라 기록의 지금 명단을 보여 주고 여기서 고친다(DEC-59)
                attendees?.let { names ->
                    item {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { editingAttendees = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (names.isEmpty()) "참석자 · 아직 없어요" else "참석자 · ${names.joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (names.isEmpty()) Wb.colors.muted else Wb.colors.body,
                                modifier = Modifier.weight(1f),
                            )
                            Text(if (names.isEmpty()) "넣기" else "고치기", style = MaterialTheme.typography.labelMedium, color = Wb.colors.link)
                        }
                    }
                }
                if (decisions.isNotEmpty() || questions.isNotEmpty()) item {
                    WbCard {
                        if (decisions.isNotEmpty()) {
                            Text("결정 후보", style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                            Text("회의에서 정한 것처럼 들린 말이에요. 확정은 아니에요. 챙길 일이면 할 일로 옮겨요.", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
                        }
                        decisions.forEachIndexed { i, d ->
                            val overlaps = folded[i].orEmpty()
                            DecisionRow(
                                text = d.text,
                                alreadyTask = d.text.trim() in openTaskTitles,
                                overlapCount = overlaps.size,
                                expanded = expanded[i] == true,
                                onMakeTask = { creatingFrom = d.text },
                                onToggle = { expanded[i] = expanded[i] != true },
                            )
                            if (expanded[i] == true) overlaps.forEach { p -> Box(Modifier.padding(top = 6.dp)) { candidate(p) } }
                        }
                        if (decisions.isNotEmpty() && questions.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Wb.colors.border)
                        MeetingSection("정하지 못한 것", null, questions)
                    }
                }
                item {
                    SectionLabel("할 일 후보 ${loose.size}")
                    if (pending.isEmpty() && done.isEmpty() && updates.isEmpty() && tracked.isEmpty()) {
                        Text("이 녹음에서는 할 일을 찾지 못했어요.", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted)
                    } else if (pending.isNotEmpty()) {
                        Text(
                            "체크한 것만 할 일로 추가돼요. 제목은 그 자리에서 고치고, 담당·기한은 눌러서 골라요." +
                                if (loose.size < pending.size) " 결정과 겹치는 후보는 결정 밑에 접어 뒀어요." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Wb.colors.muted,
                        )
                    }
                }
                // 목업 표처럼 줄들을 한 카드 안에 붙여 둔다(줄 사이 구분선)
                if (loose.isNotEmpty()) item(key = "candidates") { CandidateTable(loose) { candidate(it) } }
                if (updates.isNotEmpty()) item {
                    SectionLabel("기존 할 일 변경 ${updates.size}")
                    Text("이미 있는 할 일의 담당·기한이 이번 녹음에서 달라졌어요. 검토함에서 전후를 보고 반영해요.", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
                }
                items(updates, key = { it.id }) { p -> CandidateCard(p.after, p.rationale, p.status, checked = false, onChecked = null, before = p.before) }
                if (tracked.isNotEmpty()) item {
                    SectionLabel("이미 있는 할 일 ${tracked.size}")
                    WbCard {
                        Text("이번 녹음에도 나왔지만 이미 할 일에 있어서 새로 만들지 않았어요.", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
                        tracked.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.ink, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                // 처리된 후보(추가됨·버림)도 같은 표 줄로, 고칠 수 없게. 처리된 변경 제안은 전후가 보이는 카드로
                val (doneUpdates, doneTasks) = done.partition { it.isUpdate }
                if (doneTasks.isNotEmpty()) item(key = "done-candidates") { CandidateTable(doneTasks) { candidate(it) } }
                items(doneUpdates, key = { it.id }) { p -> CandidateCard(p.after, p.rationale, p.status, checked = false, onChecked = null, before = p.before) }
                item {
                    // 교정본이 있으면 그것을 먼저 보여 주고, 받아쓴 원문은 따로 볼 수 있게 둔다(원문은 고치지 않음, DEC-36)
                    val corrected = transcripts["term-corrected"]
                    val original = transcripts["stt-original"]
                    val shown = if (corrected != null && !showOriginal) corrected else original
                    SectionLabel(if (corrected != null && !showOriginal) "전사 · 업무 용어 교정본" else "받아쓴 원문")
                    WbCard {
                        if (showTranscript) {
                            if (shown == null) Text("원문이 없어요", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.body)
                            else TranscriptBody(shown)
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
        }
        message?.let { (text, tone) -> Banner(text, tone, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        // 회의록 승인은 문서를 다 읽고 누르도록 '회의록 문서'를 볼 때 맨 아래 고정 줄에 둔다. 승인할 초안이 있을 때만(DEC-61)
        val approvable = meeting?.takeIf { it.document != null && !it.approved }
        if (!firstLoad && view == "doc" && approvable != null) {
            HorizontalDivider(color = Wb.colors.border)
            Row(Modifier.fillMaxWidth().background(Wb.colors.card).padding(horizontal = 16.dp, vertical = 10.dp)) {
                PrimaryButton(
                    if (approving) "승인하는 중…" else "이 회의록 승인",
                    // 다시 만드는 중에는 곧 바뀔 문서라 승인하지 않는다
                    enabled = !approving && minutes != MinutesWorker.State.Making,
                    modifier = Modifier.weight(1f),
                    onClick = { confirmApprove = true },
                )
            }
        }
        // 버리기·추가는 할 일 후보에 쓰는 것이라 추출 항목을 볼 때만 둔다. 목업 S05처럼 '추가할 곳'을 버튼 바로 위에(DEC-68)
        if (!firstLoad && pending.isNotEmpty() && view == "fields") {
            HorizontalDivider(color = Wb.colors.border)
            Column(
                Modifier.fillMaxWidth().background(Wb.colors.card).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AddPlaceRow(place, places) { place = it; placeTouched = true }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(
                        "버리기",
                        enabled = !busy && selected.isNotEmpty(),
                        onClick = { if (selected.size > 1) confirmDiscard = true else discard(selected) },
                    )
                    PrimaryButton(
                        "선택한 할 일 추가 (${selected.size})",
                        enabled = !busy && selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = ::addSelected,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        DiscardConfirmDialog(selected.size, onDismiss = { confirmDiscard = false }) { confirmDiscard = false; discard(selected) }
    }

    if (projectsOpen) {
        RecordProjectsSheet(
            recordClass = recordClass,
            suggestions = shownSuggestions,
            linked = linkedProjects,
            candidates = allProjects.filter { !it.archived && it.id !in linkedIds },
            busy = linkBusy,
            onClassify = { classify(it) },
            onLink = { setLinked(it, true) },
            onUnlink = { setLinked(it, false) },
            onDismiss = { projectsOpen = false },
        )
    }

    leftover?.let { rows ->
        AlertDialog(
            onDismissRequest = { leftover = null },
            title = { Text("선택하지 않은 후보 ${rows.size}개") },
            text = { Text("할 일로 추가하지 않은 후보예요. 버릴까요? 버린 후보는 검토함 ‘처리됨’에서 볼 수 있어요.") },
            confirmButton = { TextButton(onClick = { leftover = null; discard(rows) }) { Text("버리기", color = Wb.colors.crit) } },
            dismissButton = { TextButton(onClick = { leftover = null }) { Text("그대로 두기") } },
        )
    }

    if (confirmApprove) {
        ApproveMinutesDialog(onDismiss = { confirmApprove = false }) {
            confirmApprove = false
            meeting?.let { approveMinutes(it.id) }
        }
    }

    if (editingAttendees) {
        AttendeesDialog(attendees.orEmpty(), onDismiss = { editingAttendees = false }) { names ->
            editingAttendees = false
            scope.launch {
                runCatching { Server.saveAttendees(recordId, names) }
                    .onSuccess {
                        attendees = names
                        RecentAttendees.remember(context, names)
                        // 이미 만든 회의록은 자동으로 바뀌지 않는다(다시 만들기를 눌러야 새 명단이 반영됨)
                        message = (if (meeting?.document != null) "참석자를 바꿨어요 · 회의록 문서에서 '다시 만들기'를 누르면 반영돼요" else "참석자를 바꿨어요") to Tone.Good
                    }
                    .onFailure { message = "참석자를 바꾸지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit }
            }
        }
    }

    if (renaming) {
        TitleDialog(shownTitle, onDismiss = { renaming = false }) { newTitle ->
            renaming = false
            scope.launch {
                runCatching { Server.renameRecord(recordId, newTitle) }
                    .onSuccess {
                        shownTitle = newTitle
                        // 이 기록을 올린 기기 녹음이 있으면 녹음 목록 제목도 맞춘다
                        RecordingStore.list(context, activeId = null).firstOrNull { it.recordId == recordId }
                            ?.let { RecordingStore.setTitle(RecordingStore.dir(context, it.id), newTitle) }
                        message = "제목을 바꿨어요" to Tone.Good
                    }
                    .onFailure { message = "제목을 바꾸지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit }
            }
        }
    }

    editing?.let { (p, field) ->
        CandidateEditSheet(
            current = JsonObject(p.after + edits[p.id].orEmpty()),
            field = field,
            onDismiss = { editing = null },
            // 앞서 고친 다른 칸(받는 사람 등)을 지우지 않게 합친다
            onApply = { edits[p.id] = JsonObject(edits[p.id].orEmpty() + it); checked[p.id] = true; editing = null },
        )
    }
    recipientsFor?.let { p ->
        RecipientsSheet(current(p).recipients(), recordId, followNames, onDismiss = { recipientsFor = null }) { names ->
            edit(p, "recipients" to JsonArray(names.map(::JsonPrimitive)))
            recipientsFor = null
        }
    }

    // 결정 → 할 일: 사용자가 직접 만드는 할 일이라 AI 후보 승인을 거치지 않는다. 담당은 '나'로 시작하고 시트에서 바꿀 수 있다.
    // '어디에' 처음 값은 '추가할 곳'과 같다(연결된 프로젝트가 하나면 그 프로젝트, DEC-69). 남은 후보가 없으면 '추가할 곳' 줄이 안 보여서 여기서 고른다
    creatingFrom?.let { text ->
        var target by remember(text) { mutableStateOf(place) }
        CandidateEditSheet(
            current = JsonObject(mapOf("title" to JsonPrimitive(text.take(200)), "owner_kind" to JsonPrimitive("me"))),
            field = null,
            onDismiss = { creatingFrom = null },
            whereField = { PlaceField(label = target?.name ?: UNSORTED, type = target?.type, places = places) { target = it } },
            onApply = { fields ->
                creatingFrom = null
                val chosen = target
                scope.launch {
                    // 오늘 탭은 오늘 마감·지연만 보여서(DEC-67) 들어간 곳을 알려 준다
                    message = runCatching { Server.createTask(JsonObject(fields + placeKeys(chosen))) }
                        .fold({ "할 일로 만들었어요 · ‘${chosen?.name ?: UNSORTED}’에 넣었어요" to Tone.Good }, { "만들지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit })
                    load(); onChanged()
                }
            },
        )
    }
}

/** 할 일 후보 줄들을 한 카드에(목업 S05 표). 줄마다 아래 구분선이 있어 카드 위아래 여백만 둔다 */
@Composable
private fun CandidateTable(rows: List<ProposalRow>, row: @Composable (ProposalRow) -> Unit) {
    WbCard(padding = PaddingValues(start = 0.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)) {
        rows.forEach { row(it) }
    }
}

/** 목업 S05 머리 알약: '녹음 · 42분 · 10/16 15:00'. 길이는 서버 음성 길이, 없으면 기기에 적어 둔 길이, 그것도 없으면 빼고 */
@Composable
private fun RecordMetaPill(info: RecordInfoRow, localDurationSec: Int?, modifier: Modifier = Modifier) {
    val parts = listOfNotNull(
        when (info.channel) {
            "recording" -> "녹음"
            "upload" -> "가져온 파일"
            "call-share" -> "통화"
            "text-import" -> "받아쓴 글"
            else -> null
        },
        (info.durationSec ?: localDurationSec)?.let(::durationText),
        meetingTimeText(info.occurredAt ?: info.ingestedAt),
    )
    if (parts.isEmpty()) return
    Row(
        modifier.border(1.dp, Wb.colors.border, PillShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (info.channel == "text-import") WbIcons.Document else WbIcons.Mic, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(14.dp))
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Wb.colors.body, modifier = Modifier.padding(start = 4.dp))
    }
}

// 42분, 1시간 47분. 1분이 안 되면 '1분 미만'
private fun durationText(sec: Int): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    return when {
        h > 0 -> if (m > 0) "${h}시간 ${m}분" else "${h}시간"
        m > 0 -> "${m}분"
        else -> "1분 미만"
    }
}

// 서버 시각(UTC) → 서울 '10/16 15:00'
private fun meetingTimeText(iso: String?): String? = iso?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).format(DateTimeFormatter.ofPattern("M/d HH:mm")) }.getOrNull()
}

/** 결정 후보 한 줄: 문장 + '할 일로 만들기'(이미 같은 제목의 할 일이 있으면 '할 일 있음') + 겹치는 후보 접기 */
@Composable
private fun DecisionRow(text: String, alreadyTask: Boolean, overlapCount: Int, expanded: Boolean, onMakeTask: () -> Unit, onToggle: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("· $text", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.body)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (alreadyTask) {
                WbChip("할 일 있음", Tone.Good, Modifier.padding(start = 12.dp, end = 4.dp))
            } else {
                TextButton(onClick = onMakeTask) { Text("할 일로 만들기", color = Wb.colors.link) }
            }
            if (overlapCount > 0) {
                TextButton(onClick = onToggle) {
                    Text("겹치는 할 일 후보 ${overlapCount}개 ${if (expanded) "▴" else "▾"}", color = Wb.colors.muted)
                }
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

/**
 * 전사 본문. 가져온 txt처럼 화자가 있으면 말한 사람 차례마다 '화자 · 시각' 줄 + 문장으로(DEC-54, 사용자 선택 1안),
 * 없으면 본문 그대로 보여 준다.
 */
@Composable
private fun TranscriptBody(transcript: Transcript) {
    val turns = speakerTurns(transcript)
    if (turns.isEmpty()) {
        Text(transcript.content, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.body)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        turns.forEach { turn ->
            Column {
                Text(
                    listOfNotNull(turn.speaker, turn.offsetMs?.let(::clockText)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = Wb.colors.muted,
                )
                Text(turn.text, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.body, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private data class SpeakerTurn(val speaker: String?, val offsetMs: Long?, val text: String)

/** 같은 화자·시각이 이어지는 문장을 한 차례로 묶는다. 화자가 하나도 없으면 빈 목록 */
private fun speakerTurns(transcript: Transcript): List<SpeakerTurn> {
    if (transcript.segments.none { it.speaker != null }) return emptyList()
    val turns = mutableListOf<SpeakerTurn>()
    for (s in transcript.segments) {
        val last = turns.lastOrNull()
        if (last != null && last.speaker == s.speaker && last.offsetMs == s.offsetMs) {
            turns[turns.lastIndex] = last.copy(text = last.text + " " + s.text)
        } else {
            turns += SpeakerTurn(s.speaker, s.offsetMs, s.text)
        }
    }
    return turns
}

// 00:03, 1:02:03
private fun clockText(ms: Long): String {
    val total = ms / 1000
    val (h, m, sec) = Triple(total / 3600, total % 3600 / 60, total % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
