package com.workbrain.app.tasks

import com.workbrain.app.data.TaskRow
import java.time.DayOfWeek
import java.time.LocalDate

/*
 * 할 일 반복 규칙(DEC-109, Design §3.20) — 화면 없이 시험하는 계산.
 * 저장은 서버 task.repeat_rule(RFC 5545 RRULE 일부). 다음 회차는 완료할 때 서버 트리거(0044 wb_repeat_next)가 만들고,
 * 앱은 같은 규칙으로 '다음 회차 ○월 ○일'을 미리 보여 주기만 한다(두 계산이 어긋나지 않게 시험을 같이 둔다).
 */

enum class RepeatFreq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** 매일·매주(days 비면 기한의 요일)·매월(기한의 날짜)·매년. interval 1~99 */
data class RepeatRule(val freq: RepeatFreq, val interval: Int = 1, val days: Set<DayOfWeek> = emptySet()) {
    /** 서버에 넣는 글자: 'FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE'(1이면 INTERVAL 생략, 요일은 월→일 순) */
    fun text(): String = buildString {
        append("FREQ=").append(freq.name)
        if (interval > 1) append(";INTERVAL=").append(interval)
        if (days.isNotEmpty()) append(";BYDAY=").append(days.sorted().joinToString(",") { it.name.take(2) })
    }

    companion object {
        // 서버 CHECK task_repeat_rule_shape와 같은 모양
        private val SHAPE = Regex("""^FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;INTERVAL=([1-9][0-9]?))?(;BYDAY=((MO|TU|WE|TH|FR|SA|SU)(,(MO|TU|WE|TH|FR|SA|SU)){0,6}))?$""")

        /** 서버 글자 → 규칙. 모양이 다르면 null(반복 없음으로 본다) */
        fun parse(s: String?): RepeatRule? {
            val m = SHAPE.matchEntire(s ?: return null) ?: return null
            val freq = RepeatFreq.valueOf(m.groupValues[1])
            val days = m.groupValues[5].takeIf { it.isNotEmpty() }?.split(',')?.map { code -> DayOfWeek.entries.first { it.name.startsWith(code) } }?.toSet().orEmpty()
            if (days.isNotEmpty() && freq != RepeatFreq.WEEKLY) return null
            return RepeatRule(freq, m.groupValues[3].toIntOrNull() ?: 1, days)
        }
    }
}

const val REPEAT_MAX_INTERVAL = 99

val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.take(5).toSet()

/** 고르는 목록(업무용 + 삼성 리마인더, 11/16 사용자). null = 반복 안 함. 직접은 customRepeat으로 */
val REPEAT_PRESETS: List<RepeatRule?> = listOf(
    null,
    RepeatRule(RepeatFreq.DAILY),
    RepeatRule(RepeatFreq.WEEKLY, days = WEEKDAYS),
    RepeatRule(RepeatFreq.WEEKLY),
    RepeatRule(RepeatFreq.MONTHLY),
    RepeatRule(RepeatFreq.YEARLY),
)

/** 직접 설정 단위(목업: 일·주·개월) */
enum class RepeatUnit(val label: String, val freq: RepeatFreq) { DAY("일", RepeatFreq.DAILY), WEEK("주", RepeatFreq.WEEKLY), MONTH("개월", RepeatFreq.MONTHLY) }

/** 직접 설정: N(1~99) + 단위 + (주면) 요일 하나 이상. 맞지 않으면 null(저장 못 함) */
fun customRepeat(amount: Int?, unit: RepeatUnit, days: Set<DayOfWeek>): RepeatRule? {
    val n = amount ?: return null
    if (n !in 1..REPEAT_MAX_INTERVAL) return null
    if (unit == RepeatUnit.WEEK && days.isEmpty()) return null
    return RepeatRule(unit.freq, n, if (unit == RepeatUnit.WEEK) days else emptySet())
}

private const val DOW = "일월화수목금토"

internal fun dowName(d: DayOfWeek) = DOW[d.value % 7].toString()

/**
 * '반복 안 함' · '매일' · '평일' · '매주 월요일' · '2주마다 월·수' · '매월 16일' · '매년 11월 16일' · '3일마다'.
 * 매주·매월·매년은 기한 날짜를 따르므로 due로 글자를 만든다. menu = 목록 글자('평일 (월~금)')
 */
fun repeatLabel(rule: RepeatRule?, due: LocalDate?, menu: Boolean = false): String {
    val r = rule ?: return "반복 안 함"
    val n = r.interval
    return when (r.freq) {
        RepeatFreq.DAILY -> if (n == 1) "매일" else "${n}일마다"
        RepeatFreq.WEEKLY -> {
            if (n == 1 && r.days == WEEKDAYS) return if (menu) "평일 (월~금)" else "평일"
            val days = when {
                r.days.isEmpty() -> due?.let { "${dowName(it.dayOfWeek)}요일" }
                r.days.size == 1 -> "${dowName(r.days.first())}요일"
                else -> r.days.sorted().joinToString("·", transform = ::dowName)
            }
            listOfNotNull(if (n == 1) "매주" else "${n}주마다", days).joinToString(" ")
        }
        RepeatFreq.MONTHLY -> listOfNotNull(if (n == 1) "매월" else "${n}개월마다", due?.let { "${it.dayOfMonth}일" }).joinToString(" ")
        RepeatFreq.YEARLY -> listOfNotNull(if (n == 1) "매년" else "${n}년마다", due?.let { "${it.monthValue}월 ${it.dayOfMonth}일" }).joinToString(" ")
    }
}

/** 목록에 있는 것이면 true(아니면 '직접 설정'에 ✓) */
fun isPresetRepeat(rule: RepeatRule?): Boolean = rule in REPEAT_PRESETS

/**
 * 다음 회차 날짜(기한 기준, DEC-109 ③) — 서버 wb_repeat_next와 같은 계산.
 * 기한 다음 첫 회차, 그날이 오늘보다 앞이면(늦게 끝냄) 오늘 이후 첫 회차까지 건너뛴다(오늘 자체는 됨).
 * 주 단위는 월요일 시작 주(DEC-31)에서 interval 주마다 고른 요일. 월·년은 기한에서 n개월·n년(없는 날은 그달 말일)
 */
fun nextRepeat(rule: RepeatRule, due: LocalDate, today: LocalDate): LocalDate {
    val n = rule.interval.toLong()
    val baseWeek = due.minusDays(due.dayOfWeek.value - 1L)
    var next = due
    var step = 0L
    repeat(20_000) {
        next = when (rule.freq) {
            RepeatFreq.DAILY -> next.plusDays(n)
            RepeatFreq.WEEKLY -> if (rule.days.isEmpty()) next.plusWeeks(n) else {
                var d = next.plusDays(1)
                while (d.dayOfWeek !in rule.days || (d.minusDays(d.dayOfWeek.value - 1L).toEpochDay() - baseWeek.toEpochDay()) / 7 % n != 0L) d = d.plusDays(1)
                d
            }
            RepeatFreq.MONTHLY -> { step += n; due.plusMonths(step) }
            RepeatFreq.YEARLY -> { step += n; due.plusYears(step) }
        }
        if (!next.isBefore(today)) return next
    }
    return next
}

/** 칸 둘째 줄: '완료하면 다음 회차 11월 23일 (월)을 만들어요' */
fun repeatNextText(rule: RepeatRule?, due: LocalDate?, today: LocalDate): String? {
    val r = rule ?: return null
    val d = due ?: return null
    val next = nextRepeat(r, d, today)
    return "완료하면 다음 회차 ${next.monthValue}월 ${next.dayOfMonth}일 (${dowName(next.dayOfWeek)})을 만들어요"
}

/** 완료 알림 꼬리: 반복하는 상위 할 일을 끝낼 때 '다음 회차 11/23 (월)'. 아니면 null(서버도 만들지 않음) */
fun repeatDoneNote(t: TaskRow, today: LocalDate): String? {
    if (t.done || t.isSubtask) return null
    val rule = RepeatRule.parse(t.repeatRule) ?: return null
    val due = t.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val next = nextRepeat(rule, due, today)
    return "다음 회차 ${next.monthValue}/${next.dayOfMonth} (${dowName(next.dayOfWeek)})"
}
