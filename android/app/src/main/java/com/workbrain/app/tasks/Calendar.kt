package com.workbrain.app.tasks

import com.workbrain.app.ui.SEOUL
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * 구글 캘린더 보기·넣기 화면 규칙(FR-24a, DEC-105, Design §3.16). 웹 rules/calendar.ts와 같게 — 고치면 두 곳을 같이.
 * 일정은 서버에 저장하지 않고 함수 `calendar`가 부를 때마다 Google에서 읽어 준다
 */

/** 함수 calendar가 돌려주는 일정 한 줄(서버 _shared/calendar.ts CalEvent와 같은 칸) */
@Serializable
data class CalEvent(
    val id: String,
    val title: String,
    /** 종일 = 'YYYY-MM-DD', 시각 있음 = ISO(오프셋 포함) */
    val start: String,
    /** 종일이면 다음 날(끝 날 미포함) */
    val end: String,
    val allDay: Boolean,
    val link: String? = null,
    /** 장소(없으면 null) — 제목 아래 회색 작은 줄(11/15 사용자) */
    val location: String? = null,
)

enum class CalendarStatus { OFF, ON, BROKEN }

/**
 * 함수가 200으로 돌려주는 기대한 실패(not-connected·broken·no-due·not-found)와 주고받기 실패(failed).
 * reason으로 문구를 고른다(calendarFailText)
 */
class CalendarError(val reason: String, cause: Throwable? = null) : Exception("calendar $reason", cause)

/** 오늘 카드에서 처음 보이는 수(종일·지금·남은 일정) */
const val TODAY_EVENTS_SHOWN = 3

/** 다음 7일 카드에서 처음 보이는 수 — 할 일 목록이 밀리지 않게(11/14 사용자) */
const val WEEK_EVENTS_SHOWN = 5

/** 같은 기간 일정을 다시 부르지 않는 시간(끌어 새로고침·[다시 시도]는 바로) */
const val EVENTS_TTL_MS = 5 * 60_000L

/** 줄 색: PAST = 끝남(흐리게), NOW = 진행 중(강조) */
enum class EventTone { NONE, PAST, NOW }

data class EventLine(val e: CalEvent, val tone: EventTone)

private fun instantOf(iso: String): Instant? = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

/** 정렬용 시작 시각(종일은 그날 0시). 읽을 수 없으면 맨 뒤 */
private fun startMs(e: CalEvent): Long =
    instantOf(e.start)?.toEpochMilli()
        ?: runCatching { LocalDate.parse(e.start).atStartOfDay(SEOUL).toInstant().toEpochMilli() }.getOrNull()
        ?: Long.MAX_VALUE

private fun hhmm(iso: String): String? = runCatching {
    val t = OffsetDateTime.parse(iso).atZoneSameInstant(SEOUL)
    "%02d:%02d".format(t.hour, t.minute)
}.getOrNull()

/** 줄 왼쪽 시각: 종일은 '종일', 시각 있음은 서울 시작·끝 */
fun eventTimes(e: CalEvent): Pair<String, String?> =
    if (e.allDay) "종일" to null else (hhmm(e.start) ?: "") to hhmm(e.end)

private fun toneOf(e: CalEvent, now: Instant): EventTone {
    if (e.allDay) return EventTone.NONE
    val end = instantOf(e.end) ?: return EventTone.NONE
    if (!end.isAfter(now)) return EventTone.PAST
    val start = instantOf(e.start) ?: return EventTone.NONE
    return if (!start.isAfter(now)) EventTone.NOW else EventTone.NONE
}

data class TodayLines(val allDay: List<EventLine>, val past: List<EventLine>, val rest: List<EventLine>, val hidden: Int)

/**
 * 오늘 카드: 종일 → '지난 일정 N'(접힘) → 지금 → 남은 일정. 처음에는 종일·지금·남은 일정 가운데 TODAY_EVENTS_SHOWN개만,
 * 나머지는 '일정 N개 더'. moreOpen이면 모두
 */
fun todayLines(events: List<CalEvent>, now: Instant, moreOpen: Boolean): TodayLines {
    val timed = events.filter { !it.allDay }.sortedBy(::startMs)
    val lines = (events.filter { it.allDay } + timed.filter { toneOf(it, now) != EventTone.PAST }).map { EventLine(it, toneOf(it, now)) }
    val shown = if (moreOpen) lines else lines.take(TODAY_EVENTS_SHOWN)
    val nAllDay = shown.count { it.e.allDay }
    return TodayLines(
        allDay = shown.take(nAllDay),
        past = timed.filter { toneOf(it, now) == EventTone.PAST }.map { EventLine(it, EventTone.PAST) },
        rest = shown.drop(nAllDay),
        hidden = lines.size - shown.size,
    )
}

private const val DOW = "월화수목금토일"

// ── 다음 7일 카드 줄 모양: 삼성 캘린더 일정 목록처럼(11/15 사용자) ──

private fun monthDay(day: LocalDate) = "${day.monthValue}월 ${day.dayOfMonth}일"

/** 날짜 머리: '11월 14일 (금)' */
fun eventDayLabel(day: LocalDate): String = "${monthDay(day)} (${DOW[day.dayOfWeek.value - 1]})"

/** 날짜 머리 옆 작은 글씨: 오늘·내일만 */
fun eventDayRel(day: LocalDate, today: LocalDate): String? = when (day) {
    today -> "오늘"
    today.plusDays(1) -> "내일"
    else -> null
}

/** '13:30' → '1:30'(왼쪽 칸, 삼성처럼 오전·오후 없이 — 아래 줄에 있음) */
private fun h12(hm: String): String = "${(hm.take(2).toInt() % 12).let { if (it == 0) 12 else it }}:${hm.substring(3, 5)}"

/** '13:30' → '오후 1:30' */
private fun ampm(hm: String): String = "${if (hm.take(2).toInt() < 12) "오전" else "오후"} ${h12(hm)}"

/** 왼쪽 칸 시작 시각('1:30'). 종일이면 null(종일은 파란 칸으로 따로 그림) */
fun weekStart(e: CalEvent): String? = if (e.allDay) null else hhmm(e.start)?.let(::h12)

/**
 * 제목 아래 줄: 시각 일정 '오후 1:30 - 오후 2:30', 종일 '하루 종일', 여러 날 종일 '11월 13일 - 11월 15일'(끝 날은 Google이 다음 날로 줌).
 * 장소가 있으면 ' · 장소'
 */
fun weekSub(e: CalEvent): String {
    val whenText = if (e.allDay) {
        val first = runCatching { LocalDate.parse(e.start) }.getOrNull()
        val last = runCatching { LocalDate.parse(e.end).minusDays(1) }.getOrNull()
        if (first != null && last != null && last.isAfter(first)) "${monthDay(first)} - ${monthDay(last)}" else "하루 종일"
    } else {
        val s = hhmm(e.start)
        val t = hhmm(e.end)
        if (s != null && t != null) "${ampm(s)} - ${ampm(t)}" else ""
    }
    return e.location?.let { "$whenText · $it" } ?: whenText
}

/** 일정이 놓이는 날: 시작한 날(서울). 이미 시작해 오늘까지 이어지는 일정은 오늘에 */
private fun dayOf(e: CalEvent, today: LocalDate): LocalDate {
    val d = if (e.allDay) runCatching { LocalDate.parse(e.start) }.getOrNull() else instantOf(e.start)?.atZone(SEOUL)?.toLocalDate()
    return if (d == null || d.isBefore(today)) today else d
}

data class DayGroup(val day: LocalDate, val label: String, val rel: String?, val lines: List<EventLine>)
data class WeekGroups(val groups: List<DayGroup>, val total: Int, val hidden: Int)

/**
 * 다음 7일 카드: 날짜별(일정 없는 날은 건너뜀), 날마다 종일 먼저·시각순. 처음에는 WEEK_EVENTS_SHOWN개만(moreOpen이면 모두).
 * 오늘 이미 끝난 일정은 흐리게 둔다(지운 것처럼 빼지 않음 — 이번 주 계획을 보는 곳)
 */
fun weekGroups(events: List<CalEvent>, today: LocalDate, now: Instant, moreOpen: Boolean): WeekGroups {
    val sorted = events.map { it to dayOf(it, today) }
        .sortedWith(compareBy<Pair<CalEvent, LocalDate>> { it.second }.thenBy { !it.first.allDay }.thenBy { startMs(it.first) })
    val shown = if (moreOpen) sorted else sorted.take(WEEK_EVENTS_SHOWN)
    val groups = shown.groupBy({ it.second }, { it.first }).map { (day, list) ->
        DayGroup(day, eventDayLabel(day), eventDayRel(day, today), list.map { EventLine(it, if (toneOf(it, now) == EventTone.PAST) EventTone.PAST else EventTone.NONE) })
    }
    return WeekGroups(groups, total = sorted.size, hidden = sorted.size - shown.size)
}

// ── 연결 ──

/** 허락하고 돌아온 결과(workbrain://calendar-callback?calendar=) 알림. 모르는 값이면 null */
fun connectResultText(result: String?): String? = when (result) {
    "ok" -> "구글 캘린더를 연결했어요"
    "denied" -> "캘린더 허락을 하지 않아 연결하지 않았어요"
    "wrong_account" -> "로그인한 계정과 다른 Google 계정이에요 · 같은 계정으로 다시 연결해 주세요"
    "error" -> "연결하지 못했어요. 잠시 뒤 다시 해 주세요"
    else -> null
}

fun calendarFailText(reason: String): String = when (reason) {
    "broken" -> "캘린더 연결이 끊겼어요 · 더보기에서 다시 연결해 주세요"
    "not-connected" -> "구글 캘린더가 연결돼 있지 않아요 · 더보기에서 연결해 주세요"
    "no-due" -> "기한 날짜가 없어 캘린더에 넣지 못했어요"
    "not-found" -> "할 일을 찾지 못했어요. 새로 불러와 주세요"
    else -> "캘린더와 주고받지 못했어요. 잠시 뒤 다시 해 주세요"
}

// ── 할 일 서랍 '캘린더' 줄 ──

/**
 * 저장할 때 캘린더에 할 일:
 * ADD = 새로 넣기 · KEEP = 그대로(제목·기한을 고쳤으면 서버 표시를 보고 목록을 다시 읽을 때 반영) · ASK = 끄거나 기한을 지워 '일정도 지울까요?'
 */
enum class CalendarPlan { NONE, ADD, KEEP, ASK }

/** 묻고 난 뒤까지 포함한 최종 처리: UNLINK = 일정은 남기고 연결만 끊기, DELETE = 일정도 지우기 */
enum class CalendarIntent { NONE, ADD, KEEP, UNLINK, DELETE }

fun calendarPlan(connected: Boolean, linked: Boolean, on: Boolean, hasDate: Boolean): CalendarPlan = when {
    !linked -> if (connected && on && hasDate) CalendarPlan.ADD else CalendarPlan.NONE
    // 연결이 끊긴 동안에는 일정을 건드릴 수 없어 그대로 둔다(다시 연결하면 이어짐)
    !connected -> CalendarPlan.KEEP
    on && hasDate -> CalendarPlan.KEEP
    else -> CalendarPlan.ASK
}

/** 스위치 아래 안내 한 줄 */
fun calendarNote(connected: Boolean, linked: Boolean, on: Boolean, hasDate: Boolean, withTime: Boolean): String = when {
    !connected -> if (linked) "캘린더 연결이 끊겨 있어요. 더보기에서 다시 연결하면 이어서 반영해요." else "더보기에서 구글 캘린더를 연결하면 넣을 수 있어요."
    !hasDate -> if (linked) "기한 날짜를 지우고 저장하면 캘린더 일정을 어떻게 할지 물어봐요." else "기한 날짜를 정하면 넣을 수 있어요."
    !on -> if (linked) "끄고 저장하면 캘린더 일정을 어떻게 할지 물어봐요." else ""
    else -> {
        val how = when { linked -> ""; withTime -> "그 시각부터 1시간 일정으로 넣어요. "; else -> "종일 일정으로 넣어요. " }
        "${how}제목·기한을 고치면 캘린더도 따라 바뀌고, 완료해도 일정은 남아요."
    }
}

/** 저장 전 '캘린더 일정도 지울까요?' 설명 */
fun askSaveText(title: String, hasDate: Boolean): String =
    "‘$title’ 일정이 Google 캘린더에 있어요. ${if (hasDate) "[캘린더에도 넣기]를 껐어요." else "기한을 지웠어요."} [남기기]를 누르면 일정은 그대로 두고 연결만 끊어요."

/** 지우기 전 '캘린더 일정도 지울까요?' 설명. titles = 지우는 것 가운데 일정이 있는 할 일, total = 지우는 할 일 수(하위 포함) */
fun askDeleteText(titles: List<String>, total: Int): String {
    val first = titles.firstOrNull().orEmpty()
    if (total == 1) return "‘$first’ 일정이 Google 캘린더에 있어요. [남기기]를 누르면 할 일만 지우고 일정은 그대로 둬요."
    return "지우는 할 일 ${total}개 중 ${titles.size}개(‘$first’${if (titles.size > 1) " 등" else ""})가 Google 캘린더에 있어요. [남기기]를 누르면 할 일만 지우고 일정은 그대로 둬요."
}
