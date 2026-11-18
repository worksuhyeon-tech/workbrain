package com.workbrain.app.tasks

import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.TaskRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 할 일 반복 규칙(DEC-109, Design §3.20) — 오늘 = 2026-11-16(월), 가짜 할 일.
 * 다음 날짜 경우는 서버 DB 시험 tests/p1/dec109_repeat.sql과 같다(두 계산이 어긋나지 않게)
 */
class RepeatRulesTest {
    private val today = LocalDate.of(2026, 11, 16)
    private fun d(s: String) = LocalDate.parse(s)
    private fun next(rule: String, due: String, today: String = "2026-11-16") = nextRepeat(RepeatRule.parse(rule)!!, d(due), d(today))

    @Test fun `서버 글자 - 읽고 다시 쓰면 같고, 모양이 다르면 반복 없음`() {
        listOf("FREQ=DAILY", "FREQ=WEEKLY", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", "FREQ=MONTHLY;INTERVAL=3", "FREQ=YEARLY")
            .forEach { assertEquals(it, RepeatRule.parse(it)!!.text()) }
        // 요일은 월→일 순으로 쓴다
        assertEquals("FREQ=WEEKLY;BYDAY=MO,SU", RepeatRule(RepeatFreq.WEEKLY, days = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY)).text())
        listOf(null, "", "FREQ=HOURLY", "FREQ=DAILY;BYDAY=MO", "FREQ=DAILY;INTERVAL=0", "FREQ=DAILY;INTERVAL=100", "freq=daily").forEach { assertNull(it, RepeatRule.parse(it)) }
    }

    @Test fun `다음 날짜 - 매일·평일·매주·2주 월수`() {
        assertEquals(d("2026-11-17"), next("FREQ=DAILY", "2026-11-16"))
        assertEquals(d("2026-11-23"), next("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", "2026-11-20"))
        assertEquals(d("2026-11-23"), next("FREQ=WEEKLY", "2026-11-16"))
        assertEquals(d("2026-11-18"), next("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", "2026-11-16"))
        assertEquals(d("2026-11-30"), next("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", "2026-11-18"))
    }

    @Test fun `다음 날짜 - 월말·윤년은 그달 마지막 날`() {
        assertEquals(d("2026-02-28"), next("FREQ=MONTHLY", "2026-01-31", "2026-01-01"))
        // 기한(31일)에서 세어 2월 말 다음은 3월 31일(2월 28일에서 세지 않음)
        assertEquals(d("2026-03-31"), next("FREQ=MONTHLY", "2026-01-31", "2026-03-01"))
        assertEquals(d("2029-02-28"), next("FREQ=YEARLY", "2028-02-29", "2028-01-01"))
    }

    @Test fun `늦게 끝내면 지난 회차는 건너뛰고 오늘 이후 첫 회차(오늘 자체는 됨)`() {
        assertEquals(d("2026-11-16"), next("FREQ=DAILY", "2026-11-10"))
        assertEquals(d("2026-11-23"), next("FREQ=WEEKLY", "2026-11-02", "2026-11-18"))
        assertEquals(d("2026-12-15"), next("FREQ=MONTHLY", "2026-09-15"))
        // 일찍 끝내도 기한 기준: 매주 월(11/23) 할 일을 11/16에 끝내면 11/30
        assertEquals(d("2026-11-30"), next("FREQ=WEEKLY", "2026-11-23"))
    }

    @Test fun `글자 - 목록·칩·직접`() {
        val due = d("2026-11-16")
        assertEquals("반복 안 함", repeatLabel(null, due))
        assertEquals("매일", repeatLabel(RepeatRule(RepeatFreq.DAILY), due))
        assertEquals("평일", repeatLabel(RepeatRule(RepeatFreq.WEEKLY, days = WEEKDAYS), due))
        assertEquals("평일 (월~금)", repeatLabel(RepeatRule(RepeatFreq.WEEKLY, days = WEEKDAYS), due, menu = true))
        assertEquals("매주 월요일", repeatLabel(RepeatRule(RepeatFreq.WEEKLY), due))
        assertEquals("매월 16일", repeatLabel(RepeatRule(RepeatFreq.MONTHLY), due))
        assertEquals("매년 11월 16일", repeatLabel(RepeatRule(RepeatFreq.YEARLY), due))
        assertEquals("2주마다 월요일", repeatLabel(RepeatRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO"), due))
        assertEquals("2주마다 월·수", repeatLabel(RepeatRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE,MO"), due))
        assertEquals("3일마다", repeatLabel(RepeatRule.parse("FREQ=DAILY;INTERVAL=3"), due))
        assertEquals("2개월마다 16일", repeatLabel(RepeatRule.parse("FREQ=MONTHLY;INTERVAL=2"), due))
        // 기한이 없으면 요일·날짜 없이
        assertEquals("매주", repeatLabel(RepeatRule(RepeatFreq.WEEKLY), null))
        assertTrue(isPresetRepeat(RepeatRule(RepeatFreq.MONTHLY)))
        assertFalse(isPresetRepeat(RepeatRule.parse("FREQ=WEEKLY;BYDAY=MO")))
        assertEquals("완료하면 다음 회차 11월 23일 (월)을 만들어요", repeatNextText(RepeatRule(RepeatFreq.WEEKLY), due, today))
        assertNull(repeatNextText(RepeatRule(RepeatFreq.WEEKLY), null, today))
    }

    @Test fun `직접 설정 - 1~99, 주면 요일 하나 이상`() {
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", customRepeat(2, RepeatUnit.WEEK, setOf(DayOfWeek.MONDAY))!!.text())
        assertEquals("FREQ=DAILY;INTERVAL=3", customRepeat(3, RepeatUnit.DAY, setOf(DayOfWeek.MONDAY))!!.text())
        assertEquals("FREQ=MONTHLY", customRepeat(1, RepeatUnit.MONTH, emptySet())!!.text())
        assertNull(customRepeat(0, RepeatUnit.DAY, emptySet()))
        assertNull(customRepeat(100, RepeatUnit.DAY, emptySet()))
        assertNull(customRepeat(null, RepeatUnit.DAY, emptySet()))
        assertNull(customRepeat(2, RepeatUnit.WEEK, emptySet()))
    }

    @Test fun `완료 알림 꼬리 - 반복하는 상위 할 일만`() {
        fun row(rule: String?, due: String? = "2026-11-16", parent: String? = null, status: String = "open") =
            TaskRow(id = "t", title = "주간 보고", status = status, version = 0, ownerKind = "me", dueDate = due, parentId = parent, repeatRule = rule)
        assertEquals("다음 회차 11/23 (월)", repeatDoneNote(row("FREQ=WEEKLY"), today))
        assertEquals("다음 회차 11/17 (화)", repeatDoneNote(row("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"), today))
        assertNull(repeatDoneNote(row(null), today))
        assertNull(repeatDoneNote(row("FREQ=DAILY", due = null), today))
        assertNull(repeatDoneNote(row("FREQ=DAILY", parent = "p"), today))
        assertNull(repeatDoneNote(row("FREQ=DAILY", status = "done"), today))
    }

    @Test fun `저장 - 기한 날짜가 없으면 반복도 빠진다`() {
        val e = TaskEdit("주간 보고", 2, "me", null, "2026-11-16", null, false, null, null, null, repeatRule = "FREQ=WEEKLY")
        assertEquals("FREQ=WEEKLY", e.savedRepeat)
        assertNull(e.copy(dueDate = null).savedRepeat)
        assertNull(e.copy(dueDate = null, dueAmbiguous = true, dueText = "다음 주 중").savedRepeat)
    }
}
