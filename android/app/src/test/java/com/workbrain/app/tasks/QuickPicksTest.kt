package com.workbrain.app.tasks

import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 빠른 입력 아이콘 값 합치기(DEC-107, Design §3.18) — 오늘 = 2026-11-15(일), 가짜 곳 */
class QuickPicksTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val claim = Place(PlaceType.PROJECT, "p1", "사고접수 개편")
    private val ops = Place(PlaceType.AREA, "a1", "보상 운영")
    private val places = listOf(claim, ops)

    private fun parse(text: String, noDueMeansToday: Boolean = false) = QuickParse.parse(text, today, places, null, noDueMeansToday)

    @Test fun `아이콘으로 고른 기한이 단축어보다 먼저`() {
        val raw = parse("업체 미팅 내일3시")
        val picks = QuickPicks(due = Picked(Due(today.plusDays(3), LocalTime.of(10, 0)), dueBase(raw)))
        val r = applyPicks(raw, picks)
        assertEquals(today.plusDays(3), r.dueDate)
        assertEquals(LocalTime.of(10, 0), r.dueTime)
        assertEquals("업체 미팅", r.title)
    }

    @Test fun `고른 뒤 글을 고쳐 단축어가 바뀌면 단축어를 따른다(나중에 한 것)`() {
        val raw = parse("업체 미팅 내일3시")
        val picks = QuickPicks(due = Picked(Due(today.plusDays(3), null), dueBase(raw)))
        val later = parse("업체 미팅 금요일")
        assertNull(picks.liveFor(later).due)
        assertEquals(LocalDate.of(2026, 11, 20), applyPicks(later, picks).dueDate)
        // 기한과 상관없는 글만 고치면 고른 값은 그대로
        val retitled = parse("판독 업체 미팅 내일3시")
        assertEquals(today.plusDays(3), applyPicks(retitled, picks).dueDate)
    }

    @Test fun `오늘 탭에서 기한 없음을 고르면 오늘 마감이 아니다`() {
        val raw = parse("주간 보고", noDueMeansToday = true)
        assertEquals(today, raw.dueDate)
        val r = applyPicks(raw, QuickPicks(due = Picked(null, dueBase(raw))))
        assertNull(r.dueDate)
        assertNull(r.dueTime)
    }

    @Test fun `모호한 기한도 아이콘 날짜로 바꾸면 모호가 풀린다`() {
        val raw = parse("자료 정리 다음주")
        val r = applyPicks(raw, QuickPicks(due = Picked(Due(today.plusDays(1), null), dueBase(raw))))
        assertNull(r.vague)
        assertEquals(today.plusDays(1), r.dueDate)
    }

    @Test fun `곳을 고르면 '#' 경고와 후보 칩을 뺀다`() {
        val raw = parse("자료 정리 #없는곳")
        assertTrue(raw.warnings.any { it.startsWith("#") })
        val r = applyPicks(raw, QuickPicks(place = Picked(ops, placeBase(raw))))
        assertEquals(ops, r.place)
        assertTrue(r.warnings.none { it.startsWith("#") })
        assertTrue(r.placeChoices.isEmpty())
    }

    @Test fun `정리 안 됨을 고를 수 있다`() {
        val raw = parse("자료 정리 #사고접수")
        assertEquals(claim, raw.place)
        assertNull(applyPicks(raw, QuickPicks(place = Picked(null, placeBase(raw)))).place)
    }

    @Test fun `우선순위 - 고른 값, 단축어가 바뀌면 단축어`() {
        val raw = parse("자료 정리")
        val picks = QuickPicks(priority = Picked(1, raw.priority))
        assertEquals(1, applyPicks(raw, picks).priority)
        assertEquals(3, applyPicks(parse("자료 정리 !낮음"), picks).priority)
    }

    @Test fun `1시간 후는 5분 단위로 올리고 자정을 넘으면 다음 날`() {
        assertEquals(Due(today, LocalTime.of(3, 0)), hourLater(LocalDateTime.of(2026, 11, 15, 1, 57)))
        assertEquals(Due(today, LocalTime.of(15, 10)), hourLater(LocalDateTime.of(2026, 11, 15, 14, 10, 30)))
        assertEquals(Due(today.plusDays(1), LocalTime.of(0, 5)), hourLater(LocalDateTime.of(2026, 11, 15, 23, 2)))
    }

    @Test fun `바로 가기 날짜 - 1개월 후는 같은 날, 말일은 그달 마지막 날`() {
        val month = QUICK_DAYS.first { it.first == "1개월 후" }.second
        assertEquals(LocalDate.of(2026, 12, 15), month(today))
        assertEquals(LocalDate.of(2027, 2, 28), month(LocalDate.of(2027, 1, 31)))
        assertEquals(listOf("오늘", "내일", "3일 후", "1주 후", "1개월 후"), QUICK_DAYS.map { it.first })
    }

    @Test fun `휠 - 오전 오후 12시, 5분 단위 칸`() {
        assertEquals(LocalTime.of(0, 30), wheelTime(pm = false, hour12 = 12, minute = 30))
        assertEquals(LocalTime.of(12, 0), wheelTime(pm = true, hour12 = 12, minute = 0))
        assertEquals(LocalTime.of(15, 5), wheelTime(pm = true, hour12 = 3, minute = 5))
        assertEquals(1, wheelMinuteIndex(LocalTime.of(15, 7)))
        assertEquals("오후 3:00", ampmLabel(LocalTime.of(15, 0)))
        assertEquals("오전 12:05", ampmLabel(LocalTime.of(0, 5)))
    }

    @Test fun `하위 할 일 줄 정리와 알림 끝말`() {
        val lines = listOf("  자료 모으기 ", "", "   ") + List(25) { "줄 $it" } + listOf("가".repeat(150))
        val clean = cleanSubtasks(lines)
        assertEquals(MAX_SUBTASKS, clean.size)
        assertEquals("자료 모으기", clean.first())
        assertEquals(MAX_SUBTASK_TITLE, cleanSubtasks(listOf("가".repeat(150))).single().length)
        assertEquals("", subtaskTail(0, 0))
        assertEquals(" · 하위 2개", subtaskTail(2, 0))
        assertEquals(" · 하위 3개 중 1개를 못 넣었어요", subtaskTail(3, 1))
    }
}
