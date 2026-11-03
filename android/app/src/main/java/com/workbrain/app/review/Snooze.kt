package com.workbrain.app.review

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 검토 미루기(FR-28, DEC-63) 날짜 계산. 서버가 미룬 날 0시(서울)를 snoozed_until로 저장하므로
 * 화면도 서울 날짜로 계산·표시한다.
 */
object Snooze {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")

    fun today(): LocalDate = LocalDate.now(zone)

    private fun instant(iso: String?): Instant? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /** 아직 미룬 날이 오지 않았는지. 그날 0시가 지나면 '오늘' 목록으로 돌아온다 */
    fun isActive(snoozedUntil: String?): Boolean = instant(snoozedUntil)?.isAfter(Instant.now()) == true

    /** 보관 기한 같은 시각이 이미 지났는지 */
    fun isPast(iso: String?): Boolean = instant(iso)?.isBefore(Instant.now()) == true

    /** 서버 시각을 서울 날짜로 */
    fun date(iso: String?): LocalDate? = instant(iso)?.atZone(zone)?.toLocalDate()

    /** 11/05(목) */
    fun label(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("M/d")) + "(" + date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ")"

    fun label(iso: String?): String? = date(iso)?.let(::label)

    /** 한 주는 월요일 시작(DEC-31): 오늘이 월요일이어도 다음 주 월요일 */
    fun nextMonday(from: LocalDate = today()): LocalDate = from.with(TemporalAdjusters.next(DayOfWeek.MONDAY))

    /**
     * 고를 수 있는 마지막 날: 1년 뒤와, 고른 후보들의 근거 전사 보관이 끝나는 날의 전날 중 이른 날.
     * 보관이 끝나면 승인할 수 없으므로(DEC-15·21) 그 뒤로 미루지 않고, 다시 나온 뒤 하루는 검토할 수 있게 한다
     */
    fun lastAllowed(transcriptExpiries: List<String?>): LocalDate {
        val yearLater = today().plusYears(1)
        val evidenceLast = transcriptExpiries.mapNotNull(::date).minOrNull()?.minusDays(1) ?: return yearLater
        return minOf(yearLater, evidenceLast)
    }
}
