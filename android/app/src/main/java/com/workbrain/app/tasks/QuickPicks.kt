package com.workbrain.app.tasks

import com.workbrain.app.data.Place
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * 빠른 입력 아이콘으로 고른 값(DEC-107, Design §3.18) — 화면 없이 시험하는 규칙.
 * 단축어로 읽은 값(raw)과 아이콘으로 고른 값이 다르면 '나중에 한 것'이 들어간다:
 * 고를 때 그 칸의 단축어 해석(base)을 같이 적어 두고, 글을 고쳐 그 해석이 달라지면 고른 값을 버린다.
 */

/** 아이콘 줄의 칸. 담당은 아이콘 없이 @이름·'자세히 ›'(11/15 사용자 — 2단계 장소·사진 자리) */
enum class QuickTool(val label: String) { SUB("하위 할 일"), DUE("기한"), PLACE("어디에"), PRIO("우선순위") }

/** 고른 기한. 시각이 없으면 날짜만 */
data class Due(val date: LocalDate, val time: LocalTime?)

/** value = 고른 값(기한·곳은 null = '없음'을 고름), base = 고를 때 그 칸의 단축어 해석 */
data class Picked<T>(val value: T, val base: Any?)

data class QuickPicks(
    val due: Picked<Due?>? = null,
    val place: Picked<Place?>? = null,
    val priority: Picked<Int>? = null,
) {
    /** 글을 고쳐 그 칸의 단축어 해석이 고를 때와 달라졌으면 그 칸은 단축어를 따른다 */
    fun liveFor(raw: QuickTask): QuickPicks = QuickPicks(
        due = due?.takeIf { it.base == dueBase(raw) },
        place = place?.takeIf { it.base == placeBase(raw) },
        priority = priority?.takeIf { it.base == raw.priority },
    )
}

fun dueBase(raw: QuickTask): Any = Triple(raw.dueDate, raw.dueTime, raw.vague)
fun placeBase(raw: QuickTask): Any = listOf(raw.place?.type, raw.place?.id, raw.placeAsked)

/** 단축어 해석에 살아 있는 아이콘 값을 덮는다. 곳을 고르면 '#'이 걸린 경고·후보 칩은 뺀다 */
fun applyPicks(raw: QuickTask, picks: QuickPicks): QuickTask {
    val live = picks.liveFor(raw)
    var r = raw
    live.due?.let { r = r.copy(dueDate = it.value?.date, dueTime = it.value?.time, vague = null) }
    live.place?.let { p ->
        r = r.copy(place = p.value, placeChoices = emptyList(), placeAsked = null, warnings = r.warnings.filterNot { it.startsWith("#") })
    }
    live.priority?.let { r = r.copy(priority = it.value) }
    return r
}

/** 기한 칸 바로 가기: 오늘·내일·3일 후·1주 후·1개월 후(말일은 Java 규칙대로 그달 마지막 날) */
val QUICK_DAYS: List<Pair<String, (LocalDate) -> LocalDate>> = listOf(
    "오늘" to { d -> d },
    "내일" to { d -> d.plusDays(1) },
    "3일 후" to { d -> d.plusDays(3) },
    "1주 후" to { d -> d.plusWeeks(1) },
    "1개월 후" to { d -> d.plusMonths(1) },
)

/** 시간 칸 바로 가기(삼성 리마인더 참고, 업무 시간에 맞춤). '1시간 후'는 hourLater로 */
val QUICK_TIMES: List<Pair<String, LocalTime>> = listOf(
    "오전 9:00" to LocalTime.of(9, 0),
    "오후 2:00" to LocalTime.of(14, 0),
    "오후 6:00" to LocalTime.of(18, 0),
)

/** '1시간 후': 지금부터 1시간 뒤를 5분 단위로 올린 날짜·시각(자정을 넘으면 다음 날) */
fun hourLater(now: LocalDateTime): Due {
    val t = now.plusHours(1).withSecond(0).withNano(0)
    val up = (5 - t.minute % 5) % 5
    val r = t.plusMinutes(up.toLong())
    return Due(r.toLocalDate(), r.toLocalTime())
}

/** 휠 분 칸은 5분 단위: 적은 시각이 3:07이면 3:05에 놓는다(돌리기 전까지 값은 그대로) */
fun wheelMinuteIndex(t: LocalTime): Int = t.minute / 5

/** '오후 3:00' */
fun ampmLabel(t: LocalTime): String = "${if (t.hour < 12) "오전" else "오후"} ${(t.hour % 12).let { if (it == 0) 12 else it }}:%02d".format(t.minute)

/** 휠 [오전/오후, 시(1~12), 분(5분)] → 시각 */
fun wheelTime(pm: Boolean, hour12: Int, minute: Int): LocalTime = LocalTime.of(hour12 % 12 + if (pm) 12 else 0, minute)

const val MAX_SUBTASKS = 20
const val MAX_SUBTASK_TITLE = 100

/** 하위 할 일 줄 정리: 앞뒤 공백 떼고 빈 줄 빼고 20줄·100자까지 */
fun cleanSubtasks(lines: List<String>): List<String> =
    lines.map { it.trim().take(MAX_SUBTASK_TITLE) }.filter { it.isNotEmpty() }.take(MAX_SUBTASKS)

/** 넣은 뒤 알림 끝말: ' · 하위 2개' 또는 ' · 하위 3개 중 1개를 못 넣었어요' */
fun subtaskTail(total: Int, failed: Int): String = when {
    total == 0 -> ""
    failed == 0 -> " · 하위 ${total}개"
    else -> " · 하위 ${total}개 중 ${failed}개를 못 넣었어요"
}
