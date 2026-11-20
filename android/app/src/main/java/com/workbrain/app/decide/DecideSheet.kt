package com.workbrain.app.decide

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.data.Decide
import com.workbrain.app.data.DecisionKind
import com.workbrain.app.data.SecretaryCard
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskRow
import com.workbrain.app.data.toEdit
import com.workbrain.app.tasks.CalendarDeleteAsk
import com.workbrain.app.tasks.CalendarStore
import com.workbrain.app.tasks.DueTabsDialog
import com.workbrain.app.tasks.TaskDeletes
import com.workbrain.app.tasks.repeatDoneNote
import com.workbrain.app.ui.AnnounceHost
import com.workbrain.app.ui.CONFLICT_TEXT
import com.workbrain.app.ui.HandAccent
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.rememberAnnouncer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/*
 * 비서와 결정 서랍(DEC-111, Design §3.21, 목업 workbrain-late-decide-android.html ②⑤~⑪).
 * 다시 정할 일을 한 번에 5장. 카드마다 비서가 사실을 말하고 첫 2분을 제안 → [N분 시작] = 바로 집중 타이머(FocusHost).
 * 손이 안 가면 다른 방법: 이미 끝냈어요 · 너무 커요 · 남을 기다려요 · 오늘은 못 해요 · 이제 필요 없어요.
 * 결정마다 바로 저장하고 다음 장으로. 아래 알림 [되돌리기]는 방금 한 것만.
 */

private enum class Panel { SPLIT, LATER, WAIT, DROP }

private const val NOTIFY_PERMISSION = Manifest.permission.POST_NOTIFICATIONS

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DecideSheet(
    queue: List<TaskRow>,
    allTasks: List<TaskRow>,
    childrenOf: (String) -> List<TaskRow>,
    placeOf: (TaskRow) -> String?,
    parentTitleOf: (String) -> String,
    usedNames: List<String>,
    calendarAsk: CalendarDeleteAsk,
    remaining: Int,
    onMore: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val announcer = rememberAnnouncer()
    val today = remember { LocalDate.now(SEOUL) }
    val links = CalendarStore.state.collectAsStateWithLifecycle().value.links

    var i by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var len by remember { mutableIntStateOf(2) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cards = remember { mutableStateMapOf<String, SecretaryCard>() }
    var ai by remember { mutableStateOf<String?>(null) }
    val tally = remember { mutableStateMapOf<DecisionKind, Int>() }
    var finished by remember { mutableIntStateOf(0) }
    var focusMs by remember { mutableLongStateOf(0L) }
    // 나누고 날짜로 넘어간 할 일(그 결정은 '나눔'으로 남긴다)
    val laterFromSplit = remember { mutableStateMapOf<String, Boolean>() }

    // 비서 사실·제안을 한 번에(못 받으면 기기에 있는 사실만으로)
    LaunchedEffect(queue.map { it.id }) {
        runCatching { Decide.secretary(queue.map { it.id }) }
            .onSuccess { a -> a.cards.forEach { cards[it.taskId] = it }; ai = a.ai }
            .onFailure { ai = "offline" }
    }

    fun advance(kind: DecisionKind, doneTask: Boolean = false) {
        tally[kind] = (tally[kind] ?: 0) + 1
        if (doneTask) finished++
        i++
        panel = null
        error = null
    }

    /** 되돌리기: 앞 장으로 돌아가 셈을 뺀다 */
    fun back(kind: DecisionKind, doneTask: Boolean = false) {
        tally[kind] = ((tally[kind] ?: 1) - 1).coerceAtLeast(0)
        if (doneTask) finished = (finished - 1).coerceAtLeast(0)
        i = (i - 1).coerceAtLeast(0)
        panel = null
    }

    // 타이머가 끝나면(끝났어요·여기까지) 다음 장
    LaunchedEffect(Unit) {
        FocusController.finished.collect { r ->
            if (queue.getOrNull(i)?.id == r.taskId) {
                advance(r.kind, r.doneTask)
                focusMs += r.spentMs
                announcer.show(r.message)
            }
        }
    }

    val notifyAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        FocusNotify.refresh(context, FocusController.state.value)
    }

    fun startFocus(t: TaskRow, step: String, kind: DecisionKind, piece: String? = null, stepTaskId: String? = null, nextHint: String? = null) {
        val now = System.currentTimeMillis()
        FocusController.start(
            context,
            FocusSession(
                taskId = t.id, title = t.title, place = placeOf(t), piece = piece,
                step = step.trim().ifEmpty { "‘${t.title}’ 열어 보기" }, stepTaskId = stepTaskId, nextHint = nextHint,
                isSubtask = t.isSubtask, kind = kind.server, plannedMin = len, startedAtMs = now, clock = FocusClock.start(len, now),
            ),
        )
        // 잠금 화면 시계·끝 진동 알림에 필요(Android 13+). 거절해도 앱 안 타이머는 돈다
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, NOTIFY_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) notifyAsk.launch(NOTIFY_PERMISSION)
    }

    /** 결정 한 번: 저장 실패면 그 장에 남겨 다시 누를 수 있게 */
    fun save(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = "저장하지 못했어요. 인터넷 연결을 확인해 주세요" }
            busy = false
            onChanged()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x7309141E),
    ) {
        Box {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
                val t = queue.getOrNull(i)
                if (t == null) {
                    DoneView(queue.size, tally, finished, focusMs, remaining, onMore, onDismiss)
                    return@Column
                }
                val kids = if (t.isSubtask) emptyList() else childrenOf(t.id)
                val card = cards[t.id]
                val calPassed = calendarPassed(t, t.id in links, Instant.now())
                val rec = recommend(card, calPassed)
                val follow = t.ownerKind == "other"
                // 첫 동작: 고친 글 > 서버 제안(남은 하위 또는 AI) > 기기의 남은 하위
                val localKid = kids.firstOrNull { !it.done }
                var step by remember(t.id, card) { mutableStateOf(card?.step ?: localKid?.title ?: "") }
                val stepIsAi = card?.step != null && card.stepTaskId == null && step == card.step
                val stepTaskId = when {
                    card?.stepTaskId != null && step == card.step -> card.stepTaskId
                    localKid != null && step == localKid.title -> localKid.id
                    else -> null
                }
                val pieces = card?.pieces?.takeIf { it.isNotEmpty() } ?: kids.filter { !it.done }.map { it.title }
                val nextHint = pieces.firstOrNull { it != step }

                // 머리 · 진행
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("비서와 정리", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                    Text("${i + 1} / ${queue.size}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Text("오늘은 여기까지", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss).padding(6.dp))
                }
                LinearProgressIndicator(
                    progress = { (i + 1f) / queue.size },
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Wb.colors.primary, trackColor = Wb.colors.surface, drawStopIndicator = {},
                )
                TaskBox(t, placeOf(t), t.parentId?.let(parentTitleOf), today)

                when (panel) {
                    null -> {
                        SecretaryBubble(Modifier.padding(top = 12.dp)) {
                            val facts = factsOf(t, card, today, calPassed, kids)
                            if (facts.isNotEmpty()) BubbleText(facts.joinToString(" "))
                            card?.quote?.let { q -> QuoteBlock(q, card.source?.title) }
                            sayFor(rec, follow)?.let { BubbleText(it, strong = true) }
                            aiNote(ai, card)?.let { BubbleText(it, muted = true) }
                            BubbleText("첫 2분은 이것부터 해 보면 어때요?", strong = true)
                            LineInput(step, { step = it }, if (ai == null) "제안을 만드는 중…" else "첫 2분에 할 동작을 적어 보세요", dashed = true,
                                trailing = if (stepIsAi) ({ AiTag() }) else null)
                        }
                        // 2분 | 5분 + 시작
                        Row(
                            Modifier.padding(top = 12.dp).align(Alignment.CenterHorizontally).clip(PillShape).background(Wb.colors.surface)
                                .border(1.dp, Wb.colors.border, PillShape).padding(4.dp),
                        ) {
                            listOf(2, 5).forEach { m ->
                                Text("${m}분", fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                    color = if (len == m) Wb.colors.ink else Wb.colors.muted,
                                    modifier = Modifier.width(80.dp).clip(PillShape).background(if (len == m) Wb.colors.card else Color.Transparent)
                                        .clickable { len = m }.padding(vertical = 6.dp))
                            }
                        }
                        PillButton("${len}분 시작", {
                            startFocus(t, step, DecisionKind.START, stepTaskId = stepTaskId, nextHint = nextHint)
                        }, Modifier.fillMaxWidth().padding(top = 8.dp), icon = WbIcons.Play, height = 58.dp, fontSize = 18)
                        Text(if (follow) "끝나면 바로 다음 장으로 넘어가요" else "${len}분 지나면 그만둬도 괜찮아요",
                            fontSize = 12.sp, color = Wb.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text("아직 손이 안 가요? 다른 방법", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted,
                            modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val chips = buildList {
                                add(DecisionKind.DONE to if (follow) "받았어요" else "이미 끝냈어요")
                                if (!follow && !t.isSubtask) add(DecisionKind.SPLIT to "너무 커요")
                                add(DecisionKind.WAIT to if (follow) "다시 챙길 날" else "남을 기다려요")
                                if (!follow) add(DecisionKind.LATER to "오늘은 못 해요")
                                add(DecisionKind.DROP to "이제 필요 없어요")
                            }
                            chips.forEach { (k, label) ->
                                ChoiceChip(label, recommended = k == rec && k != DecisionKind.START) {
                                    when (k) {
                                        DecisionKind.DONE -> save {
                                            val fresh = Server.task(t.id) ?: run { advance(k); return@save }
                                            val dec = Decide.decide(t.id, k)
                                            val note = repeatDoneNote(fresh, today)
                                            if (!Server.setTaskDone(fresh, true)) { Decide.undo(dec); error = CONFLICT_TEXT; return@save }
                                            advance(k, doneTask = true)
                                            announcer.show((if (follow) "받은 걸로" else "끝난 걸로") + " 표시했어요" + (note?.let { " · $it" } ?: "")) {
                                                scope.launch {
                                                    Server.task(t.id)?.let { Server.setTaskDone(it, false) }
                                                    Decide.undo(dec)
                                                    back(k, doneTask = true)
                                                    onChanged()
                                                }
                                            }
                                        }
                                        DecisionKind.SPLIT -> panel = Panel.SPLIT
                                        DecisionKind.WAIT -> panel = Panel.WAIT
                                        DecisionKind.LATER -> panel = Panel.LATER
                                        DecisionKind.DROP -> panel = Panel.DROP
                                        DecisionKind.START -> Unit
                                    }
                                }
                            }
                        }
                    }
                    Panel.SPLIT -> SplitPanel(
                        t, pieces, len, busy,
                        onBack = { panel = null },
                        onStart = { list ->
                            save {
                                val made = makeSubtasks(t, list)
                                val first = list.first()
                                startFocus(t, first, DecisionKind.SPLIT, piece = first, stepTaskId = made[first], nextHint = list.getOrNull(1))
                            }
                        },
                        onDate = { list ->
                            save {
                                makeSubtasks(t, list)
                                step = list.first()
                                panel = Panel.LATER
                                laterFromSplit[t.id] = true
                            }
                        },
                    )
                    Panel.LATER -> LaterPanel(
                        t, card, step, allTasks, today, busy, fromSplit = laterFromSplit[t.id] == true,
                        onBack = { panel = null },
                        onSplit = { panel = Panel.SPLIT },
                        onSave = { day, time, what, remind ->
                            save {
                                val fresh = Server.task(t.id) ?: run { advance(DecisionKind.LATER); return@save }
                                val kind = if (laterFromSplit[t.id] == true) DecisionKind.SPLIT else DecisionKind.LATER
                                val dec = Decide.decide(t.id, kind)
                                val newTime = if (time != null) day.atTime(time).atZone(SEOUL).toOffsetDateTime().toString() else movedDueTime(fresh.dueTime, day)
                                val edit = fresh.toEdit().copy(
                                    dueDate = day.toString(), dueTime = newTime, dueAmbiguous = false, dueText = null,
                                    remindBeforeMin = if (remind) remindAtNine(localTimeOf(newTime)) else fresh.remindBeforeMin,
                                )
                                if (Server.updateTask(fresh, edit) == null) { Decide.undo(dec); error = CONFLICT_TEXT; return@save }
                                val subId = if (t.isSubtask) null else newSubtaskTitles(listOf(what), Decide.children(t.id)).firstOrNull()
                                    ?.let { Server.createSubtask(t.id, plainEdit(it)) }
                                advance(kind)
                                val tail = if (remind) " · 그날 오전 9시에" + (what.takeIf { it.isNotBlank() }?.let { " ‘${short(it)}’부터" } ?: "") + " 알려 드릴게요" else ""
                                announcer.show("${mdLabel(day)}에 하기로 했어요$tail") {
                                    scope.launch {
                                        Server.task(t.id)?.let { cur -> Server.updateTask(cur, fresh.toEdit()) }
                                        subId?.let { id -> Server.task(id)?.let { Server.deleteTask(it) } }
                                        Decide.undo(dec)
                                        back(kind)
                                        onChanged()
                                    }
                                }
                            }
                        },
                    )
                    Panel.WAIT -> WaitPanel(
                        t, waitNames(t, usedNames), today, busy,
                        onBack = { panel = null },
                        onSave = { who, day ->
                            save {
                                val fresh = Server.task(t.id) ?: run { advance(DecisionKind.WAIT); return@save }
                                val dec = Decide.decide(t.id, DecisionKind.WAIT)
                                val edit = fresh.toEdit().copy(
                                    ownerKind = "other", assignee = who, dueDate = day.toString(), dueTime = null, dueAmbiguous = false, dueText = null, remindBeforeMin = 0,
                                )
                                if (Server.updateTask(fresh, edit) == null) { Decide.undo(dec); error = CONFLICT_TEXT; return@save }
                                advance(DecisionKind.WAIT)
                                announcer.show((if (follow) "" else "팔로업으로 옮겼어요 · ") + "${mdLabel(day)} 오전 9시에 $who 챙기라고 알려 드릴게요") {
                                    scope.launch {
                                        Server.task(t.id)?.let { cur -> Server.updateTask(cur, fresh.toEdit()) }
                                        Decide.undo(dec)
                                        back(DecisionKind.WAIT)
                                        onChanged()
                                    }
                                }
                            }
                        },
                    )
                    Panel.DROP -> DropPanel(
                        fromMeeting = card?.source != null,
                        onBack = { panel = null },
                        onDrop = {
                            val all = listOf(t) + kids
                            calendarAsk.confirm(all) { deleteEvents ->
                                val ids = all.map { it.id }.filter { it in links }
                                TaskDeletes.delete(
                                    t,
                                    beforeCommit = if (deleteEvents && ids.isNotEmpty()) suspend { Server.calendarRemove(ids, deleteEvent = true) } else null,
                                    verb = "내려놓",
                                    commit = { Decide.drop(Server.task(it.id) ?: it) },
                                )
                                advance(DecisionKind.DROP)
                                announcer.show("내려놓았어요 · ‘모든 할 일 › 내려놓음’에서 되살릴 수 있어요") {
                                    TaskDeletes.undo(t.id)
                                    back(DecisionKind.DROP)
                                }
                            }
                        },
                    )
                }
                error?.let { Text(it, fontSize = 13.sp, color = Wb.colors.crit, modifier = Modifier.padding(top = 8.dp)) }
            }
            AnnounceHost(announcer, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** 조각을 하위 할 일로(이미 있는 안 끝난 하위와 같은 이름은 그대로 씀) → 이름 → ID */
private suspend fun makeSubtasks(t: TaskRow, titles: List<String>): Map<String, String> {
    val existing = Decide.children(t.id)
    val have = existing.filter { !it.done }.associate { it.title.trim() to it.id }
    val made = newSubtaskTitles(titles, existing).associateWith { Server.createSubtask(t.id, plainEdit(it)) }
    return have + made
}

private fun short(s: String, n: Int = 12) = if (s.length > n) s.take(n) + "…" else s

/** AI 제안을 못 만든 까닭(직접 적으면 된다) */
private fun aiNote(ai: String?, card: SecretaryCard?): String? {
    if (card?.step != null) return null
    return when (ai) {
        "key" -> "AI 키 문제로 제안을 못 만들었어요. 직접 적어 주세요."
        "budget" -> "이번 달 AI 예산을 넘어 제안은 쉬어요. 직접 적어 주세요."
        "offline" -> "인터넷이 안 돼 제안을 못 받았어요. 직접 적어 주세요."
        "failed", "no-key" -> "제안을 만들지 못했어요. 직접 적어 주세요."
        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskBox(t: TaskRow, place: String?, parentTitle: String?, today: LocalDate) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Wb.colors.card2).border(1.dp, Wb.colors.border, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        place?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.body) }
        Text((parentTitle?.let { "$it › " } ?: "") + t.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, lineHeight = 26.sp,
            modifier = Modifier.padding(top = 3.dp, bottom = 7.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            calmLateLabel(t, today)?.let { Tag(it) }
            if (t.priority == 1) Text("· 높음", fontSize = 13.sp, color = Wb.colors.muted)
            if (t.ownerKind == "other") Text("팔로업 · ${t.assignee.orEmpty()}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Wb.colors.info,
                modifier = Modifier.background(Wb.colors.info.copy(alpha = 0.12f), PillShape).padding(horizontal = 8.dp, vertical = 1.dp))
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.body,
        modifier = Modifier.background(Wb.colors.surface, PillShape).border(1.dp, Wb.colors.border, PillShape).padding(horizontal = 8.dp, vertical = 1.dp))
}

@Composable
private fun QuoteBlock(quote: String, from: String?) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.width(3.dp).heightIn(min = 32.dp).background(Wb.colors.borderStrong))
        Column(Modifier.padding(start = 9.dp)) {
            Text("회의에서 들은 말" + (from?.let { " · $it" } ?: ""), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted)
            Text("“$quote”", fontSize = 13.sp, color = Wb.colors.muted, lineHeight = 19.sp)
        }
    }
}

/** 다른 방법 칩(목업 .chip), 비서가 권하면 파란 테두리 + '추천' */
@Composable
private fun ChoiceChip(label: String, recommended: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp).clip(PillShape)
            .background(if (recommended) Wb.colors.primary.copy(alpha = 0.08f) else Wb.colors.card)
            .border(1.dp, if (recommended) Wb.colors.primary else Wb.colors.borderStrong, PillShape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = if (recommended) FontWeight.Bold else FontWeight.SemiBold,
            color = if (recommended) Wb.colors.primary else Wb.colors.ink)
        if (recommended) Text("추천", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Wb.colors.onPrimary,
            modifier = Modifier.background(Wb.colors.primary, PillShape).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
private fun SubLabel(text: String, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
        extra?.invoke()
    }
}

@Composable
private fun TwoButtons(left: String, right: String, enabled: Boolean, onLeft: () -> Unit, onRight: () -> Unit) {
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(left, onLeft, Modifier.weight(1f), PillKind.Ghost)
        PillButton(right, onRight, Modifier.weight(1.6f), enabled = enabled)
    }
}

// ⑤ 너무 커요: 비서가 나눈 조각(고칠 수 있음) → 첫 조각 바로 시작 또는 날짜 정하기
@Composable
private fun ColumnScope.SplitPanel(
    t: TaskRow, suggested: List<String>, len: Int, busy: Boolean,
    onBack: () -> Unit, onStart: (List<String>) -> Unit, onDate: (List<String>) -> Unit,
) {
    val rows = remember(t.id) { mutableStateListOf(*(suggested.take(5).ifEmpty { listOf("", "", "") }).toTypedArray()) }
    SecretaryBubble(Modifier.padding(top = 12.dp)) {
        BubbleText(if (suggested.isNotEmpty()) "${suggested.size}조각으로 나눠 봤어요. 고칠 건 고치고, 첫 조각부터 ${len}분 해 볼까요?" else "몇 조각으로 나눠 볼까요? 첫 조각만 적어도 돼요.")
    }
    SubLabel("조각 · 하위 할 일로 저장돼요") { if (suggested.isNotEmpty()) AiTag() }
    rows.forEachIndexed { k, v ->
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (k == 0) Wb.colors.primary else Wb.colors.surface)
                    .border(1.dp, if (k == 0) Wb.colors.primary else Wb.colors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("${k + 1}", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = if (k == 0) Wb.colors.onPrimary else Wb.colors.body) }
            LineInput(v, { rows[k] = it }, if (k == 0) "첫 조각" else "다음 조각", Modifier.padding(start = 8.dp))
        }
    }
    if (rows.size < 5) Text("+ 조각 더하기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { rows.add("") }.padding(4.dp))
    Text("작게 나누면 \"이 정도는 할 수 있겠다\"는 느낌이 생겨서 시작하기 쉬워져요.", fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.padding(top = 8.dp))
    val list = rows.map { it.trim() }.filter { it.isNotEmpty() }
    TwoButtons("나누고 날짜 정하기", "첫 조각 ${len}분 시작", enabled = list.isNotEmpty() && !busy, onLeft = { if (list.isNotEmpty() && !busy) onDate(list) }, onRight = { onStart(list) })
    BackLink(onBack)
}

// ⑥ 오늘은 못 해요: 다음 근무일 셋(그날 할 일 수·여유) 또는 달력 + 그날 무엇부터 + 그날 아침 9시 알림
@Composable
private fun ColumnScope.LaterPanel(
    t: TaskRow, card: SecretaryCard?, step: String, allTasks: List<TaskRow>, today: LocalDate, busy: Boolean, fromSplit: Boolean,
    onBack: () -> Unit, onSplit: () -> Unit, onSave: (LocalDate, LocalTime?, String, Boolean) -> Unit,
) {
    val days = remember(today) { nextWorkdays(today) }
    val loads = days.map { loadOn(it, allTasks) }
    val light = lightestIndex(loads)
    var sel by remember(t.id) { mutableIntStateOf(light) }
    var custom by remember(t.id) { mutableStateOf<Pair<LocalDate, LocalTime?>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var what by remember(t.id, step) { mutableStateOf(step) }
    var remind by remember(t.id) { mutableStateOf(true) }
    val day = custom?.first ?: days[sel]
    SecretaryBubble(Modifier.padding(top = 12.dp)) {
        BubbleText("${mdLabel(days[light])}이 가장 여유 있어요." + if (t.isSubtask) "" else " 그날 무엇부터 할지 적어 두면 그날 아침에 알려 드릴게요.")
    }
    val defers = card?.defers ?: 0
    if (defers >= 2 && !fromSplit && !t.isSubtask) {
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Wb.colors.warn.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text("이번이 ${defers + 1}번째 미룸이에요. 날짜보다 크기를 줄여 보면 어때요?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.warn, modifier = Modifier.weight(1f))
            Text("나눠 보기 ›", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Wb.colors.link, modifier = Modifier.clickable(onClick = onSplit).padding(start = 6.dp))
        }
    }
    SubLabel("언제")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEachIndexed { k, d ->
            val on = custom == null && k == sel
            Box(Modifier.weight(1f)) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (on) Wb.colors.primary.copy(alpha = 0.08f) else Wb.colors.card)
                        .border(if (on) 2.dp else 1.dp, if (on) Wb.colors.primary else Wb.colors.borderStrong, RoundedCornerShape(18.dp))
                        .clickable { sel = k; custom = null }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(mdLabel(d), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                    Text("할 일 ${loads[k]}개", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted)
                }
                if (k == light) Text("여유", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Wb.colors.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp).background(Wb.colors.primary, PillShape).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
    }
    Text(
        custom?.let { (d, tm) -> "다른 날: ${mdLabel(d)}${tm?.let { " ${ampm(it)}" }.orEmpty()} · 할 일 ${loadOn(d, allTasks)}개" } ?: "다른 날 고르기 (달력)",
        fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        color = if (custom != null) Wb.colors.primary else Wb.colors.body,
        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (custom != null) Wb.colors.primary else Wb.colors.borderStrong, RoundedCornerShape(14.dp))
            .clickable { picking = true }.padding(vertical = 10.dp),
    )
    if (!t.isSubtask) {
        SubLabel("그날 무엇부터")
        LineInput(what, { what = it }, "예: 화면 캡처 열기", trailing = if (what.isNotBlank() && what == card?.step && card.stepTaskId == null) ({ AiTag() }) else null)
    }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("그날 오전 9시에 알려 드릴게요", fontSize = 14.sp, color = Wb.colors.body, modifier = Modifier.weight(1f))
        Switch(remind, { remind = it }, colors = SwitchDefaults.colors(checkedTrackColor = Wb.colors.primary))
    }
    TwoButtons("‹ 돌아가기", "${mdLabel(day)}에 하기", enabled = !busy, onLeft = onBack, onRight = { onSave(day, custom?.second, what.trim(), remind) })
    if (picking) {
        DueTabsDialog(
            initialDate = custom?.first ?: days[sel], initialTime = custom?.second ?: localTimeOf(t.dueTime), startOnTime = false,
            onDismiss = { picking = false },
            onDone = { d, tm -> picking = false; if (d.isAfter(today) || d == today) custom = d to tm },
        )
    }
}

// ⑦ 남을 기다려요 / 팔로업 다시 챙길 날
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.WaitPanel(t: TaskRow, names: List<String>, today: LocalDate, busy: Boolean, onBack: () -> Unit, onSave: (String, LocalDate) -> Unit) {
    val follow = t.ownerKind == "other"
    var who by remember(t.id) { mutableStateOf(if (follow) t.assignee.orEmpty() else names.firstOrNull().orEmpty()) }
    var typing by remember(t.id) { mutableStateOf(!follow && names.isEmpty()) }
    val days = remember(today) { followUpDays(today) }
    var dayIdx by remember(t.id) { mutableIntStateOf(0) }
    SecretaryBubble(Modifier.padding(top = 12.dp)) {
        BubbleText(
            if (follow) "${t.assignee.orEmpty()}에게 언제 다시 물어볼까요? 그날 오전에 알려 드릴게요."
            else "누구 회신을 기다려요? 팔로업으로 옮겨 두고, 다시 챙길 날에 알려 드릴게요. 기다리는 동안은 내 할 일에서 빠져요.",
        )
    }
    if (!follow) {
        SubLabel("누구를")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            names.forEach { n -> PickChip(n, !typing && who == n) { who = n; typing = false } }
            PickChip("직접 입력", typing) { typing = true; who = "" }
        }
        if (typing) LineInput(who, { who = it.take(40) }, "이름이나 역할", Modifier.padding(top = 8.dp))
    }
    SubLabel("언제 다시 챙길까요")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEachIndexed { k, (label, d) -> PickChip("$label · ${mdLabel(d)}", dayIdx == k) { dayIdx = k } }
    }
    TwoButtons("‹ 돌아가기", if (follow) "이날 다시 챙기기" else "팔로업으로 옮기기", enabled = !busy && who.isNotBlank(),
        onLeft = onBack, onRight = { if (who.isNotBlank()) onSave(who.trim(), days[dayIdx].second) })
}

@Composable
private fun PickChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
        color = if (on) Wb.colors.primary else Wb.colors.ink,
        modifier = Modifier.height(38.dp).clip(PillShape)
            .background(if (on) Wb.colors.primary.copy(alpha = 0.08f) else Wb.colors.card)
            .border(if (on) 2.dp else 1.dp, if (on) Wb.colors.primary else Wb.colors.borderStrong, PillShape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

// ⑨ 이제 필요 없어요
@Composable
private fun ColumnScope.DropPanel(fromMeeting: Boolean, onBack: () -> Unit, onDrop: () -> Unit) {
    SecretaryBubble(Modifier.padding(top = 12.dp)) {
        BubbleText("내려놓을게요. 안 하기로 정한 것도 결정이에요.")
        if (fromMeeting) BubbleText("회의록에는 그대로 남아요.", strong = true)
        BubbleText("내려놓은 일은 ‘모든 할 일 › 내려놓음’에 남아서 나중에 되살릴 수 있어요.", muted = true)
    }
    TwoButtons("‹ 돌아가기", "내려놓기", enabled = true, onLeft = onBack, onRight = onDrop)
}

@Composable
private fun BackLink(onBack: () -> Unit) {
    Text("‹ 돌아가기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(4.dp))
}

// ⑪ 결정 끝: 미룬 개수가 아니라 결정한 개수
@Composable
private fun DoneView(n: Int, tally: Map<DecisionKind, Int>, finished: Int, focusMs: Long, remaining: Int, onMore: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${n}개를 결정했어요!", fontFamily = HandAccent, fontSize = 34.sp, color = Wb.colors.good)
        Text("집중한 시간 ${focusMs / 60_000}분 · 끝낸 일 ${finished}개", fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        val cells = listOf(
            "시작함" to DecisionKind.START, "나눔" to DecisionKind.SPLIT, "날짜 다시" to DecisionKind.LATER,
            "기다림" to DecisionKind.WAIT, "내려놓음" to DecisionKind.DROP, "끝남" to DecisionKind.DONE,
        )
        cells.chunked(3).forEach { row ->
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, k) ->
                    val v = tally[k] ?: 0
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).border(1.dp, Wb.colors.border, RoundedCornerShape(14.dp)).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("$v", fontSize = 20.sp, fontWeight = if (v > 0) FontWeight.Bold else FontWeight.Medium, color = if (v > 0) Wb.colors.ink else Wb.colors.muted)
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted)
                    }
                }
            }
        }
        SecretaryBubble(Modifier.padding(top = 6.dp)) {
            BubbleText(if (remaining > 0) "남은 ${remaining}개는 다음에 오늘 탭에서 다시 같이 봐요. 오늘은 여기까지 해도 충분해요." else "다시 정할 일이 하나도 없어요. 이제 오늘 할 일에만 집중하면 돼요.")
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (remaining > 0) PillButton("${minOf(DECIDE_BATCH, remaining)}개 더 보기", onMore, Modifier.weight(1f), PillKind.Ghost)
            PillButton("닫기", onClose, Modifier.weight(1.6f))
        }
    }
}

/** 오늘 탭 맨 위 비서 줄(목업 ① .secb): 다시 정할 일이 있을 때만 */
@Composable
fun SecretaryBanner(count: Int, modifier: Modifier = Modifier, onGo: () -> Unit) {
    val take = minOf(DECIDE_BATCH, count)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Wb.colors.primary.copy(alpha = 0.07f))
            .border(1.dp, Wb.colors.primary.copy(alpha = 0.22f), RoundedCornerShape(20.dp)).padding(start = 12.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SecretaryAvatar()
        Column(Modifier.weight(1f)) {
            Text("비서", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Wb.colors.primary)
            Text(
                "다시 정할 일이 ${count}개 있어요. " + (if (count > take) "오래된 ${take}개만" else "${take}개") + " 같이 볼까요? 한 장에 1분이면 돼요.",
                fontSize = 14.sp, color = Wb.colors.ink, lineHeight = 21.sp, modifier = Modifier.padding(top = 2.dp),
            )
            PillButton("같이 보기 ›", onGo, Modifier.padding(top = 10.dp), height = 38.dp, fontSize = 14)
        }
    }
}
