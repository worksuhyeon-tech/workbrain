package com.workbrain.app.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 업무계획 날짜 규칙(FR-33): 화요일 카드·마지막 화요일 월간·기간 넘기기·공유 파일 이름 */
class PlanDatesTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun tuesdayIsPlanDay() {
        assertTrue(PlanDates.isPlanDay(d("2026-11-10")))
        assertFalse(PlanDates.isPlanDay(d("2026-11-09")))
        assertFalse(PlanDates.isPlanDay(d("2026-11-11")))
    }

    @Test fun lastTuesdayOfMonthIsMonthlyDay() {
        assertTrue(PlanDates.isMonthlyDay(d("2026-11-24")))   // 11월 마지막 화요일
        assertFalse(PlanDates.isMonthlyDay(d("2026-11-17")))
        assertTrue(PlanDates.isMonthlyDay(d("2026-12-29")))   // 해 넘기기 직전
        assertFalse(PlanDates.isMonthlyDay(d("2026-11-30")))  // 말일이지만 월요일
    }

    @Test fun shiftByWeekOrMonth() {
        assertEquals(d("2026-11-17"), PlanDates.shift(PlanKind.WEEKLY, d("2026-11-10"), 1))
        assertEquals(d("2026-11-03"), PlanDates.shift(PlanKind.WEEKLY, d("2026-11-10"), -1))
        assertEquals(d("2026-12-10"), PlanDates.shift(PlanKind.MONTHLY, d("2026-11-10"), 1))
        // 31일에서 한 달 앞이면 그달 말일
        assertEquals(d("2026-11-30"), PlanDates.shift(PlanKind.MONTHLY, d("2026-12-31"), -1))
    }

    @Test fun asciiFileNames() {
        val cell = PlanCell("전주 (11.9 ~ 11.13)", "2026-11-09", "2026-11-15", emptyList())
        val weekly = WorkPlan("weekly", "주간 업무계획 (11.9 ~ 11.20)", "김하늘", cell, cell)
        assertEquals("weekly_plan_20261109.docx", PlanDates.fileName(weekly))
        assertEquals("monthly_plan_202611.docx", PlanDates.fileName(weekly.copy(kind = "monthly")))
    }

    @Test fun unknownKindFallsBackToWeekly() {
        assertEquals(PlanKind.MONTHLY, PlanKind.of("monthly"))
        assertEquals(PlanKind.WEEKLY, PlanKind.of("daily"))
        assertEquals(PlanKind.WEEKLY, PlanKind.of(null))
    }
}
