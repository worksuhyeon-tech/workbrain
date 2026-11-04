package com.workbrain.app.ui

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val DAY = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREA)
private val DAY_TIME = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.KOREA)

/** 담당 표시: 나 / 팔로업(맡은 사람) / 담당 확인(말에 담당자가 없었음, DEC-23·27) */
fun ownerLabel(ownerKind: String?, assignee: String?): String = when (ownerKind) {
    "me" -> "내 할 일"
    "other" -> "팔로업 · ${assignee.orEmpty()}"
    else -> "담당 확인"
}

fun ownerTone(ownerKind: String?): Tone = when (ownerKind) {
    "me" -> Tone.Info
    "other" -> Tone.Mute
    else -> Tone.Warn
}

/** 기한 표시: 날짜만 / 날짜+시각(서울) / 모호(확인 필요) / 없음(null) — 모호한 기한을 날짜로 바꾸지 않는다 */
fun dueLabel(date: String?, time: String?, ambiguous: Boolean, text: String?): String? = when {
    time != null -> runCatching { OffsetDateTime.parse(time).atZoneSameInstant(SEOUL).format(DAY_TIME) }.getOrDefault(time)
    date != null -> runCatching { LocalDate.parse(date).format(DAY) }.getOrDefault(date)
    ambiguous -> "${text ?: "모호"} · 확인 필요"
    else -> null
}

fun dueTone(ambiguous: Boolean): Tone = if (ambiguous) Tone.Warn else Tone.Mute

/** 오늘 화면의 묶음. 한 주는 월요일에 시작한다(DEC-31) */
enum class DueGroup(val label: String) {
    OVERDUE("기한 지남"), TODAY("오늘"), THIS_WEEK("이번 주"), LATER("다음에"), UNSET("기한 없음·확인 필요")
}

fun dueGroup(date: String?, today: LocalDate = LocalDate.now(SEOUL)): DueGroup {
    val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return DueGroup.UNSET
    val sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    return when {
        d.isBefore(today) -> DueGroup.OVERDUE
        d == today -> DueGroup.TODAY
        !d.isAfter(sunday) -> DueGroup.THIS_WEEK
        else -> DueGroup.LATER
    }
}

/** 버전이 달라 저장하지 않았을 때(다른 기기에서 먼저 고침, M18) */
const val CONFLICT_TEXT = "다른 기기에서 먼저 바뀌었어요. 새로 불러왔어요"

/** 저장 실패 문구: 아카이브 읽기 전용(WB409)은 따로 알린다. 그 밖은 연결 문제로 본다 */
fun saveFailText(e: Throwable): String = when {
    e.message.orEmpty().contains("아카이브") -> "아카이브된 곳이라 바꿀 수 없어요. 먼저 복원해 주세요"
    else -> "저장하지 못했어요. 인터넷 연결을 확인해 주세요"
}
