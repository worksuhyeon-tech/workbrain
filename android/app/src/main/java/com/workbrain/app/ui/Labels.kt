package com.workbrain.app.ui

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val DAY = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREA)
private val DAY_TIME = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.KOREA)

/** 담당 표시: 나 / 팔로업(맡은 사람) / 담당 확인(말에 담당자가 없었음, DEC-23·27) */
fun ownerLabel(ownerKind: String?, assignee: String?): String = when (ownerKind) {
    "me" -> "내 할 일"
    "other" -> "팔로업 · ${assignee.orEmpty()}"
    else -> "담당 확인"
}

/** 기한 표시: 날짜만 / 날짜+시각(서울) / 모호(확인 필요) / 없음 — 모호한 기한을 날짜로 바꾸지 않는다 */
fun dueLabel(date: String?, time: String?, ambiguous: Boolean, text: String?): String = when {
    time != null -> runCatching { OffsetDateTime.parse(time).atZoneSameInstant(SEOUL).format(DAY_TIME) }.getOrDefault(time)
    date != null -> runCatching { LocalDate.parse(date).format(DAY) }.getOrDefault(date)
    ambiguous -> "${text ?: "모호"} · 확인 필요"
    else -> "기한 없음"
}
