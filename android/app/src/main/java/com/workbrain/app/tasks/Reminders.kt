package com.workbrain.app.tasks

import com.workbrain.app.data.TaskRow
import com.workbrain.app.ui.SEOUL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/*
 * 할 일 미리 알림 규칙(DEC-108, Design §3.19) — 화면·휴대폰 없이 시험하는 계산.
 * 울리는 시각 = 기한 시각(날짜만이면 그날 오전 9시, 서울) − 몇 분 전. 값은 서버 task.remind_before_min.
 */

/** 날짜만 있는 할 일의 기준 시각(11/15 사용자: 그날 오전 9시) */
val DATE_ONLY_REMIND_TIME: LocalTime = LocalTime.of(9, 0)

/** 30일(서버 CHECK와 같음) */
const val REMIND_MAX_MIN = 43_200

/** 고르는 목록(삼성 리마인더 + 정각, 11/15 사용자). null = 미리 알림 없음. 직접은 RemindUnit으로 */
val REMIND_PRESETS: List<Int?> = listOf(null, 0, 10, 30, 60, 1440)

/** 직접 설정 단위 */
enum class RemindUnit(val label: String, val minutes: Int) { MIN("분", 1), HOUR("시간", 60), DAY("일", 1440) }

/** '정각' · 'N분 전' · 'N시간 전' · 'N일 전'(나누어떨어지는 큰 단위로) */
fun remindLabel(min: Int?): String = when {
    min == null -> "미리 알림 없음"
    min == 0 -> "정각"
    min % 1440 == 0 -> "${min / 1440}일 전"
    min % 60 == 0 -> "${min / 60}시간 전"
    else -> "${min}분 전"
}

/** 직접 설정: 숫자 × 단위가 1분~30일이면 그 분, 아니면 null(저장 못 함) */
fun customRemind(amount: Int?, unit: RemindUnit): Int? {
    val n = amount ?: return null
    if (n <= 0) return null
    val total = n.toLong() * unit.minutes
    return total.takeIf { it <= REMIND_MAX_MIN }?.toInt()
}

/** 울리는 시각(서울). 기한 날짜나 알림이 없으면 null */
fun remindAt(dueDate: LocalDate?, dueTime: LocalTime?, before: Int?): ZonedDateTime? {
    val d = dueDate ?: return null
    val b = before ?: return null
    return ZonedDateTime.of(d, dueTime ?: DATE_ONLY_REMIND_TIME, SEOUL).minusMinutes(b.toLong())
}

/** 서버 할 일 한 줄의 울리는 시각. 끝냈거나 모호·기한 없음이면 null */
fun remindAtOf(t: TaskRow): Instant? {
    if (t.done || t.dueAmbiguous) return null
    val timed = t.dueTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL) }.getOrNull() }
    val day = timed?.toLocalDate() ?: t.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return remindAt(day, timed?.toLocalTime(), t.remindBeforeMin)?.toInstant()
}

/** 예약할 것: 지금 뒤 ~ 14일 안에 울릴 알림만(그 뒤는 다음 맞추기 때) */
const val REMIND_WINDOW_DAYS = 14L

fun shouldSchedule(at: Instant, now: Instant): Boolean = at.isAfter(now) && at.isBefore(now.plusSeconds(REMIND_WINDOW_DAYS * 86_400))

private const val DOW = "일월화수목금토"

/** 칸 둘째 줄: '11월 16일 (월) 오후 2:00에 울려요' · 이미 지났으면 '이미 지난 시각이에요' · 날짜만이면 기준 안내 */
fun remindWhenText(at: ZonedDateTime?, dateOnly: Boolean, now: ZonedDateTime): String? = when {
    at == null -> if (dateOnly) "날짜만 있으면 그날 오전 9시 기준" else null
    !at.isAfter(now) -> "이미 지난 시각이에요 · 울리지 않아요"
    else -> "${at.monthValue}월 ${at.dayOfMonth}일 (${DOW[at.dayOfWeek.value % 7]}) ${ampmLabel(at.toLocalTime())}에 울려요"
}

/** 알림 내용: '오후 3:00 마감 · 곳' / '오늘 마감' / '내일 마감' / '11/20 마감'(곳이 없으면 곳 빼고) */
fun remindNotiText(dueDate: LocalDate, dueTime: LocalTime?, today: LocalDate, place: String?): String {
    val day = when (dueDate) {
        today -> if (dueTime != null) "" else "오늘"
        today.plusDays(1) -> "내일"
        else -> "${dueDate.monthValue}/${dueDate.dayOfMonth}"
    }
    val whenText = listOfNotNull(day.ifEmpty { null }, dueTime?.let(::ampmLabel)).joinToString(" ")
    return "$whenText 마감" + (place?.let { " · $it" } ?: "")
}

/** 알림 [10분 뒤 다시] */
const val SNOOZE_MIN = 10L
