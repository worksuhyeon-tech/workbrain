package com.workbrain.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 주간·월간 업무계획(FR-33, DEC-81·87·88, Design §3.13). 표 글은 서버 공용 규칙(_shared/work-plan.ts)이 만들고
 * 전주 칸 □ 줄만 AI가 '이름 + 진행 상태' 한 줄로 다듬는다. 앱은 받은 글을 미리보기로 보여 주고, 같은 글을 서버에 돌려보내 Word로 받는다
 * (미리보기와 Word가 같고, AI를 두 번 부르지 않게). [업무계획 만들기]를 눌러야 만들고, 만든 결과는 서버(work_plan)에
 * 사람·종류·기간마다 하나로 남아 화면을 나갔다 와도·다른 기기에서도 그대로 보인다(DEC-95).
 */

/** 칸 안의 한 줄. head = □ 대주제 · item = - 소주제 · note = * 부연(작은 파란 글씨) */
@Serializable data class PlanLine(val level: String, val text: String, val placeId: String? = null)

/** label = 표 머리 '전주 (11.9 ~ 11.13)', start·end = 포함 기간(YYYY-MM-DD) */
@Serializable data class PlanCell(val label: String, val start: String, val end: String, val lines: List<PlanLine>)

@Serializable data class WorkPlan(
    val kind: String,
    val title: String,
    val ownerName: String,
    val previous: PlanCell,
    val next: PlanCell,
)

/**
 * ai: done(□ 줄 AI 다듬음) · none(넣을 곳 없음) · budget(예산 대기선) · no-key · failed — 마지막 셋은 규칙 줄 그대로.
 * madeAt: 만든 시각(ISO). 화면 '11/11 10:43 만듦'
 */
data class WorkPlanResult(val plan: WorkPlan, val ai: String, val madeAt: String)

/** 서버에 남은 업무계획 한 줄(work_plan, 0038). 앱은 읽기만 한다 */
@Serializable
data class SavedWorkPlanRow(
    val plan: WorkPlan,
    val ai: String,
    @SerialName("made_at") val madeAt: String,
) {
    fun toResult() = WorkPlanResult(plan, ai, madeAt)
}

enum class PlanKind(val code: String, val label: String) {
    WEEKLY("weekly", "주간"), MONTHLY("monthly", "월간");

    companion object {
        fun of(code: String?): PlanKind = entries.firstOrNull { it.code == code } ?: WEEKLY
    }
}

/**
 * 업무계획 날짜 규칙(앱 쪽): 화요일 카드와 미리보기 기간 넘기기, 저장본 찾기·기간 줄.
 * 칸 기간은 서버 규칙(_shared/work-plan.ts periods)과 같게 센다: 주간 = 기준일이 속한 주 월요일부터(표 머리는 월~금), 월간 = 그달과 다음 달
 */
object PlanDates {
    /** 저장본을 찾는 열쇠인 전주·전월 칸 시작일: 주간 = 그 주 월요일, 월간 = 그달 1일 */
    fun periodStart(kind: PlanKind, base: LocalDate): LocalDate =
        if (kind == PlanKind.WEEKLY) base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else base.withDayOfMonth(1)

    /** 기간 줄 '11.9 ~ 11.13 · 11.16 ~ 11.20'(월간 '11.1 ~ 11.30 · 12.1 ~ 12.31'). 아직 만들지 않은 기간에도 보인다 */
    fun periodText(kind: PlanKind, base: LocalDate): String {
        val start = periodStart(kind, base)
        fun dotted(d: LocalDate) = "${d.monthValue}.${d.dayOfMonth}"
        return if (kind == PlanKind.WEEKLY) {
            val next = start.plusWeeks(1)
            "${dotted(start)} ~ ${dotted(start.plusDays(4))} · ${dotted(next)} ~ ${dotted(next.plusDays(4))}"
        } else {
            val next = start.plusMonths(1)
            "${dotted(start)} ~ ${dotted(next.minusDays(1))} · ${dotted(next)} ~ ${dotted(next.plusMonths(1).minusDays(1))}"
        }
    }

    /** 매주 화요일이 주간 업무계획 내는 날(DEC-81) */
    fun isPlanDay(day: LocalDate): Boolean = day.dayOfWeek == DayOfWeek.TUESDAY

    /** 그달 마지막 화요일이면 월간도 내는 날 */
    fun isMonthlyDay(day: LocalDate): Boolean =
        isPlanDay(day) && day == day.with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY))

    /** ‹ › 한 번에 주간은 7일, 월간은 한 달(말일 넘침은 그달 말일로) */
    fun shift(kind: PlanKind, base: LocalDate, steps: Long): LocalDate =
        if (kind == PlanKind.WEEKLY) base.plusWeeks(steps) else base.plusMonths(steps)

    /** 공유 파일 이름(한글 파일 이름은 받는 쪽에서 실패한 적이 있어 ASCII만): weekly_plan_20261109.docx · monthly_plan_202611.docx */
    fun fileName(plan: WorkPlan): String {
        val digits = plan.previous.start.filter(Char::isDigit)
        return if (plan.kind == PlanKind.MONTHLY.code) "monthly_plan_${digits.take(6)}.docx" else "weekly_plan_${digits.take(8)}.docx"
    }
}
