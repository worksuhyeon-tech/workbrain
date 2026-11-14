package com.workbrain.app.tasks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskRow
import com.workbrain.app.ui.MockCard
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * 구글 캘린더 일정 카드(FR-24a, Design §3.16, 목업 workbrain-dashboard·tasks의 .ev — 웹 CalendarCards와 같은 규칙):
 * 오늘 탭 '오늘 일정'(맨 위, 11/14 결정 ①)과 모든 할 일 '다음 7일' 탭의 '다음 7일 일정'. 연결했을 때만 보인다.
 * 줄을 누르면 기기 캘린더 앱의 그 시각을 연다(Design §3.16: 앱은 기기 캘린더, 웹은 Google 캘린더 새 탭).
 * WB가 넣은 일정은 서버가 빼고 할 일 줄 캘린더 아이콘으로 보여 준다(결정 ②)
 */

private sealed interface EventsState {
    data object Loading : EventsState
    data class Ok(val events: List<CalEvent>) : EventsState
    data object Fail : EventsState
    data object Broken : EventsState
}

/**
 * 일정 읽기. refreshKey가 바뀌면(끌어 새로고침) 기억을 버리고 바로 다시 부른다. [다시 시도]도 같음.
 * 연결돼 있을 때만 부른다(끊김·연결 안 함이면 카드가 알아서 문구를 보인다)
 */
@Composable
private fun rememberEvents(from: LocalDate, days: Int, enabled: Boolean, refreshKey: Int): Pair<EventsState, () -> Unit> {
    var retries by remember { mutableIntStateOf(0) }
    // 처음 그릴 때는 5분 기억을 쓰고, 끌어 새로고침·다시 시도부터 바로 부른다
    val firstKey = remember { refreshKey }
    val state by produceState<EventsState>(EventsState.Loading, from, days, enabled, refreshKey, retries) {
        if (!enabled) return@produceState
        value = EventsState.Loading
        value = try {
            EventsState.Ok(CalendarStore.events(from, days, force = retries > 0 || refreshKey != firstKey))
        } catch (e: CalendarError) {
            if (e.reason == "broken") EventsState.Broken else EventsState.Fail
        }
    }
    return state to { retries++ }
}

/** '지금' 강조·지난 일정 접기가 시간 따라 바뀌게 1분마다 다시 그린다 */
@Composable
private fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }
    return now
}

/** 기기 캘린더 앱을 그 시각으로 연다. 캘린더 앱이 없으면 Google 캘린더 웹 */
private fun openCalendarAt(context: Context, at: Instant) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${at.toEpochMilli()}"))
    try {
        context.startActivity(app)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://calendar.google.com/calendar/r/day"))) }
    }
}

private fun eventStart(e: CalEvent): Instant =
    if (e.allDay) runCatching { LocalDate.parse(e.start).atStartOfDay(SEOUL).toInstant() }.getOrDefault(Instant.now())
    else runCatching { OffsetDateTime.parse(e.start).toInstant() }.getOrDefault(Instant.now())

/** 목업 .ev-row: 시각 칸(58) · 제목 · ● 지금 */
@Composable
private fun EventRow(line: EventLine) {
    val context = LocalContext.current
    val (start, end) = eventTimes(line.e)
    val past = line.tone == EventTone.PAST
    val now = line.tone == EventTone.NOW
    HorizontalDivider(color = Wb.colors.border)
    Row(
        Modifier.fillMaxWidth()
            // 줄 높이는 44dp 이상, 장소 줄로 높아지면 왼쪽 띠도 그만큼
            .heightIn(min = 44.dp)
            .height(IntrinsicSize.Min)
            .background(if (now) Wb.colors.primary.copy(alpha = 0.08f) else Wb.colors.card)
            .clickable(onClickLabel = "캘린더에서 열기") { openCalendarAt(context, eventStart(line.e)) }
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 지금 진행 중이면 왼쪽 굵은 띠(목업 inset 3px)
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (now) Wb.colors.primary else Wb.colors.card))
        Column(Modifier.width(58.dp).padding(start = 13.dp, top = 9.dp, bottom = 9.dp)) {
            Text(start, fontSize = 13.sp, fontWeight = if (past) FontWeight.Medium else FontWeight.Bold, color = if (past) Wb.colors.muted else Wb.colors.ink)
            end?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Wb.colors.muted) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                line.e.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (past) Wb.colors.muted else Wb.colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // 장소는 할 일 줄 두 번째 줄과 같은 모양(회색 작은 글씨)
            line.e.location?.let {
                Text(it, fontSize = 12.sp, color = Wb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (now) Text("● 지금", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.primary, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * 다음 7일 일정 줄 — 삼성 캘린더 일정 목록처럼(11/15 사용자, 목업 tasks .wk-ev·.wk-all):
 * 시각 일정 = 왼쪽 시작 시각 + 파란 막대 + 제목·'오전 8:00 - 오전 9:00 · 장소', 종일 = 연한 파란 칸 + '하루 종일'
 */
@Composable
private fun WeekRow(line: EventLine) {
    val context = LocalContext.current
    val e = line.e
    val past = line.tone == EventTone.PAST
    val open = { openCalendarAt(context, eventStart(e)) }
    val body: @Composable (FontWeight, Modifier) -> Unit = { weight, modifier ->
        Column(modifier) {
            Text(e.title, fontSize = 15.sp, fontWeight = weight, color = if (past) Wb.colors.muted else Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(weekSub(e), fontSize = 12.sp, color = Wb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
    if (e.allDay) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp)).background(Wb.colors.infoSoft)
                .clickable(onClickLabel = "캘린더에서 열기", onClick = open)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(WbIcons.Calendar, contentDescription = null, tint = Wb.colors.info, modifier = Modifier.size(18.dp))
            body(FontWeight.SemiBold, Modifier.weight(1f).padding(start = 10.dp))
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .clickable(onClickLabel = "캘린더에서 열기", onClick = open)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            weekStart(e) ?: "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (past) Wb.colors.muted else Wb.colors.ink,
            maxLines = 1, modifier = Modifier.width(44.dp),
        )
        Box(Modifier.padding(start = 12.dp).width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (past) Wb.colors.border else Wb.colors.info))
        body(FontWeight.Medium, Modifier.weight(1f).padding(start = 12.dp))
    }
}

/** 목업 .ev-fold: 접기·펼치기 줄 */
@Composable
private fun FoldRow(text: String, onClick: () -> Unit) {
    HorizontalDivider(color = Wb.colors.border)
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** 불러오는 중·실패·끊김·없음 한 줄(목업 .ev-msg) */
@Composable
private fun MsgRow(text: String, crit: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    HorizontalDivider(color = Wb.colors.border)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 14.sp, color = if (crit) Wb.colors.crit else Wb.colors.muted)
        action?.let {
            Text(
                it, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link,
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onAction).padding(2.dp),
            )
        }
    }
}

/** 카드 머리: 제목 · 수 · 오른쪽 '캘린더 ↗'(기기 캘린더 앱) */
@Composable
private fun EventsHead(title: String, count: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
        if (count.isNotEmpty()) Text(count, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        Text(
            "캘린더 ↗", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onOpen).padding(4.dp),
        )
    }
}

/** 불러오는 중·실패·끊김 한 줄(일정이 온 Ok는 카드가 그린다) */
@Composable
private fun StateMsg(state: EventsState, retry: () -> Unit, onReconnect: () -> Unit) {
    when (state) {
        EventsState.Loading -> MsgRow("일정을 불러오는 중…")
        EventsState.Fail -> MsgRow("일정을 불러오지 못했어요", action = "다시 시도", onAction = retry)
        EventsState.Broken -> MsgRow("캘린더 연결이 끊겼어요", crit = true, action = "다시 연결", onAction = onReconnect)
        is EventsState.Ok -> Unit
    }
}

/**
 * 오늘 탭 '오늘 일정': 종일 → 지난 일정(접힘) → ● 지금 → 남은 일정 3개 + '일정 N개 더'.
 * refreshKey: 끌어 새로고침할 때 바꿔 바로 다시 부른다. onReconnect: 끊겼을 때 [다시 연결](더보기로)
 */
@Composable
fun TodayEventsCard(refreshKey: Int, onReconnect: () -> Unit, modifier: Modifier = Modifier) {
    val cal by CalendarStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = LocalDate.now(SEOUL)
    val now = rememberNow()
    val (state, retry) = rememberEvents(today, 1, cal.status == CalendarStatus.ON, refreshKey)
    var pastOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    if (cal.status != CalendarStatus.ON && cal.status != CalendarStatus.BROKEN) return

    val events = (state as? EventsState.Ok)?.events.orEmpty()
    MockCard(modifier) {
        Column {
            EventsHead("오늘 일정", if (state is EventsState.Ok && events.isNotEmpty()) "${events.size}개" else "") { openCalendarAt(context, Instant.now()) }
            when {
                cal.status == CalendarStatus.BROKEN -> MsgRow("캘린더 연결이 끊겼어요", crit = true, action = "다시 연결", onAction = onReconnect)
                state !is EventsState.Ok -> StateMsg(state, retry, onReconnect)
                events.isEmpty() -> MsgRow("오늘 일정 없음")
                else -> {
                    val r = todayLines(events, now, moreOpen)
                    r.allDay.forEach { EventRow(it) }
                    if (r.past.isNotEmpty()) {
                        FoldRow("지난 일정 ${r.past.size} ${if (pastOpen) "⌃" else "⌄"}") { pastOpen = !pastOpen }
                        if (pastOpen) r.past.forEach { EventRow(it) }
                    }
                    r.rest.forEach { EventRow(it) }
                    if (r.hidden > 0 || moreOpen) FoldRow(if (moreOpen) "접기 ⌃" else "일정 ${r.hidden}개 더 ⌄") { moreOpen = !moreOpen }
                }
            }
        }
    }
}

/** 모든 할 일 '다음 7일' 탭 맨 위 '다음 7일 일정': 날짜별, 처음엔 5개 + '일정 N개 더'(11/14 사용자) */
@Composable
fun WeekEventsCard(refreshKey: Int, onReconnect: () -> Unit, modifier: Modifier = Modifier) {
    val cal by CalendarStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = LocalDate.now(SEOUL)
    val now = rememberNow()
    // 오늘 포함 8일(오늘 ~ 다음 7일)
    val (state, retry) = rememberEvents(today, 8, cal.status == CalendarStatus.ON, refreshKey)
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    if (cal.status != CalendarStatus.ON && cal.status != CalendarStatus.BROKEN) return

    val events = (state as? EventsState.Ok)?.events.orEmpty()
    MockCard(modifier) {
        Column {
            EventsHead("다음 7일 일정", if (state is EventsState.Ok && events.isNotEmpty()) "${events.size}" else "") { openCalendarAt(context, Instant.now()) }
            when {
                cal.status == CalendarStatus.BROKEN -> MsgRow("캘린더 연결이 끊겼어요", crit = true, action = "다시 연결", onAction = onReconnect)
                state !is EventsState.Ok -> StateMsg(state, retry, onReconnect)
                events.isEmpty() -> MsgRow("다음 7일 동안 일정 없음")
                else -> {
                    val w = weekGroups(events, today, now, moreOpen)
                    w.groups.forEach { g ->
                        HorizontalDivider(color = Wb.colors.border)
                        // 날짜 머리: '11월 15일 (일) 오늘' — 삼성 캘린더처럼 굵게(11/15 사용자)
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                            Text(g.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                            g.rel?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted, modifier = Modifier.padding(start = 6.dp, bottom = 1.dp)) }
                        }
                        g.lines.forEach { WeekRow(it) }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (w.hidden > 0 || moreOpen) FoldRow(if (moreOpen) "접기 ⌃" else "일정 ${w.hidden}개 더 ⌄") { moreOpen = !moreOpen }
                }
            }
        }
    }
}

/** 지울 때 '캘린더 일정도 지울까요?'를 묻는 상태(오늘 탭·프로젝트 탭 공용). 일정이 있는 할 일이 없으면 묻지 않고 바로 지운다 */
class CalendarDeleteAsk {
    var pending by mutableStateOf<Pair<String, (Boolean) -> Unit>?>(null)
        private set

    /** tasks = 지우는 할 일 전부(하위 포함). run(deleteEvents)은 답한 뒤에만 — 창을 닫으면 지우지 않는다 */
    fun confirm(tasks: List<TaskRow>, run: (deleteEvents: Boolean) -> Unit) {
        val links = CalendarStore.state.value.links
        val linked = tasks.filter { it.id in links }
        if (linked.isEmpty()) run(false) else pending = askDeleteText(linked.map { it.title }, tasks.size) to run
    }

    fun clear() {
        pending = null
    }
}

@Composable
fun rememberCalendarDeleteAsk(): CalendarDeleteAsk = remember { CalendarDeleteAsk() }

@Composable
fun CalendarDeleteAskHost(ask: CalendarDeleteAsk) {
    ask.pending?.let { (text, run) ->
        CalendarAskDialog(text, onAnswer = { deleteEvents -> ask.clear(); run(deleteEvents) }, onDismiss = ask::clear)
    }
}

/** 지우기 알림 끝에 붙이는 말 */
const val DELETE_EVENTS_TAIL = " · 캘린더 일정도 함께 지워요"

/**
 * 할 일을 지운다(3초 되돌리기 뒤 서버, DEC-90). deleteEvents면 서버에서 지우기 바로 전에 그 할 일·하위의 캘린더 일정을 먼저 지운다
 * — 할 일을 지우면 일정 연결이 함께 지워져(cascade) 그 뒤에는 어느 일정인지 알 수 없다. 되돌리면 일정도 그대로
 */
fun deleteTaskWithEvents(t: TaskRow, kids: List<TaskRow>, deleteEvents: Boolean) {
    val links = CalendarStore.state.value.links
    val ids = (listOf(t) + kids).map { it.id }.filter { it in links }
    TaskDeletes.delete(t, if (deleteEvents && ids.isNotEmpty()) suspend { Server.calendarRemove(ids, deleteEvent = true) } else null)
}

/**
 * '캘린더 일정도 지울까요?'(11/14 결정 ④·DEC-105): 일정이 있는 할 일의 [캘린더에도 넣기]를 끄거나 기한을 지우고 저장할 때,
 * 일정이 있는 할 일을 지울 때. [남기기] = 일정은 두고 연결만 끊기, [지우기] = 일정도 지우기. 바깥·뒤로 가기는 아무것도 하지 않고 닫는다
 */
@Composable
fun CalendarAskDialog(text: String, onAnswer: (deleteEvent: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("캘린더 일정도 지울까요?") },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.body) },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("남기기") } },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("지우기", color = Wb.colors.crit) } },
    )
}
