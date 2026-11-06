package com.workbrain.app.tasks

import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * TC-26 빠른 입력: 해석 결과·못 알아본 말은 제목에 남김·모호 기한은 날짜로 바꾸지 않음·AI 없이 규칙만.
 * 오늘은 2026-11-06(금)으로 고정한다. 한 주는 월요일 시작(DEC-31)
 */
class QuickParseTest {
    private val friday = LocalDate.of(2026, 11, 6)
    private val claim = Place(PlaceType.PROJECT, "p1", "사고접수 개편")
    private val accident = Place(PlaceType.PROJECT, "p2", "사고 조사")
    private val erp = Place(PlaceType.PROJECT, "p3", "01. 클레임보상 ERP구축")
    private val review = Place(PlaceType.AREA, "a1", "심사")
    private val autoReview = Place(PlaceType.AREA, "a2", "자동심사")
    private val places = listOf(claim, accident, erp, review, autoReview)

    private fun parse(raw: String, today: LocalDate = friday, default: Place? = null, noDueMeansToday: Boolean = false) =
        QuickParse.parse(raw, today, places, default, noDueMeansToday)

    private fun day(m: Int, d: Int, y: Int = 2026) = LocalDate.of(y, m, d)

    @Test fun `목업 예시 한 줄을 모두 알아본다`() {
        val r = parse("내일 3시 판독 업체 미팅 !높음 #사고접수 @박지훈")
        assertEquals("판독 업체 미팅", r.title)
        assertEquals(day(11, 7), r.dueDate)
        assertEquals(LocalTime.of(15, 0), r.dueTime)
        assertEquals(1, r.priority)
        assertEquals(claim, r.place)
        assertEquals("박지훈", r.assignee)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun `오늘 내일 모레`() {
        assertEquals(friday, parse("오늘 보고").dueDate)
        assertEquals(day(11, 7), parse("내일 보고").dueDate)
        assertEquals(day(11, 8), parse("모레 보고").dueDate)
    }

    @Test fun `시각 여러 모양`() {
        assertEquals(LocalTime.of(15, 30), parse("오후 3시 반 회의").dueTime)
        assertEquals(LocalTime.of(15, 0), parse("15:00 회의").dueTime)
        assertEquals(LocalTime.of(15, 30), parse("3시 30분 회의").dueTime)
        assertEquals(LocalTime.of(8, 0), parse("8시 조찬").dueTime)
        assertEquals(LocalTime.of(12, 0), parse("12시 점심").dueTime)
        assertEquals(LocalTime.of(0, 0), parse("오전 12시 배치 확인").dueTime)
        assertEquals(LocalTime.of(3, 0), parse("03:00 배치 확인").dueTime)
    }

    @Test fun `시각만 적으면 오늘`() {
        val r = parse("4시 통화")
        assertEquals(friday, r.dueDate)
        assertEquals(LocalTime.of(16, 0), r.dueTime)
        assertEquals("통화", r.title)
    }

    @Test fun `3시간은 시각이 아니다`() {
        val r = parse("3시간 회의")
        assertEquals("3시간 회의", r.title)
        assertNull(r.dueDate)
        assertNull(r.dueTime)
    }

    @Test fun `없는 시각은 제목에 남기고 경고`() {
        val r = parse("25시 회의")
        assertEquals("25시 회의", r.title)
        assertNull(r.dueTime)
        assertEquals(1, r.warnings.size)
    }

    @Test fun `M_D는 올해, 이미 지났으면 다음 해(DEC-70)`() {
        assertEquals(day(12, 24), parse("12/24 송년회").dueDate)
        assertEquals(friday, parse("11/6 정리").dueDate)
        assertEquals(day(10, 24, 2027), parse("10/24 보고서").dueDate)
        assertEquals(day(1, 5, 2027), parse("1/5 보고서").dueDate)
    }

    @Test fun `없는 날짜는 제목에 남기고 경고`() {
        val r = parse("13/40 회의")
        assertEquals("13/40 회의", r.title)
        assertNull(r.dueDate)
        assertEquals(listOf("13/40는 없는 날짜예요"), r.warnings)
        // 2026·2027년 모두 2/29가 없다
        assertNull(parse("2/29 회의").dueDate)
        assertNull(parse("4/31 회의").dueDate)
    }

    @Test fun `요일은 오늘 포함 가장 가까운 날`() {
        assertEquals(friday, parse("금요일 정리").dueDate)
        assertEquals(day(11, 9), parse("월요일 정리").dueDate)
        assertEquals(day(11, 8), parse("일요일 정리").dueDate)
    }

    @Test fun `이번 주 다음 주 요일(월요일 시작)`() {
        assertEquals(day(11, 9), parse("다음 주 월요일 회의").dueDate)
        assertEquals(day(11, 13), parse("다음주 금요일 회의").dueDate)
        assertEquals(day(11, 4), parse("이번 주 수요일 회의").dueDate)
        // 일요일에 말한 '다음 주 화요일'은 바로 다음 날 시작하는 주의 화요일(DEC-31)
        assertEquals(day(11, 10), parse("다음 주 화요일 회의", today = day(11, 8)).dueDate)
    }

    @Test fun `모호한 기한은 날짜로 바꾸지 않는다`() {
        val r = parse("다음 주 보고")
        assertEquals("다음 주", r.vague)
        assertNull(r.dueDate)
        assertEquals("보고", r.title)
        assertEquals("월말", parse("월말까지 정산").vague)
        assertEquals("이번 달", parse("이번 달 정리").vague)
    }

    @Test fun `모호한 기한에 시각이 붙으면 원문으로 함께 둔다`() {
        val r = parse("다음 주 3시 미팅")
        assertEquals("다음 주 3시", r.vague)
        assertNull(r.dueDate)
        assertNull(r.dueTime)
        assertEquals("미팅", r.title)
    }

    @Test fun `조사 까지 에를 함께 뗀다`() {
        val r = parse("금요일까지 보고서 제출")
        assertEquals(friday, r.dueDate)
        assertEquals("보고서 제출", r.title)
        assertEquals(LocalTime.of(15, 0), parse("3시에 미팅").dueTime)
        assertEquals(day(12, 24), parse("12/24까지 정리").dueDate)
    }

    @Test fun `날짜·시각은 붙여 써도 된다(시간 띄고 본문)`() {
        val r = parse("내일3시 미팅")
        assertEquals("미팅", r.title)
        assertEquals(day(11, 7), r.dueDate)
        assertEquals(LocalTime.of(15, 0), r.dueTime)
        val next = parse("다음주월요일3시 미팅")
        assertEquals("미팅", next.title)
        assertEquals(day(11, 9), next.dueDate)
        assertEquals(LocalTime.of(15, 0), next.dueTime)
        assertEquals(LocalTime.of(15, 30), parse("내일오후3시반 회의").dueTime)
        assertEquals(LocalTime.of(15, 0), parse("내일오후 3시 회의").dueTime)
        assertEquals(LocalTime.of(10, 0), parse("11/7오전10시 회의").dueTime)
        assertEquals(LocalTime.of(15, 0), parse("내일3시까지 보고").dueTime)
        assertEquals("다음주 3시", parse("다음주3시 미팅").vague)
    }

    @Test fun `본문에 붙여 쓰면 알아보지 않는다`() {
        for (raw in listOf("내일3시미팅", "회의금요일3시 정리", "내일3시간 회의")) {
            val r = parse(raw)
            assertEquals(raw, r.title)
            assertNull(r.dueDate)
            assertNull(r.dueTime)
        }
    }

    @Test fun `자정 전후와 월말 연말`() {
        assertEquals(day(11, 7), parse("오늘 정리", today = day(11, 7)).dueDate)
        assertEquals(day(12, 1), parse("내일 정리", today = day(11, 30)).dueDate)
        assertEquals(day(1, 1, 2027), parse("내일 정리", today = day(12, 31)).dueDate)
        assertEquals(day(1, 1, 2027), parse("1/1 정리", today = day(12, 31)).dueDate)
    }

    @Test fun `우선순위`() {
        assertEquals(3, parse("!3 정리").priority)
        assertEquals(3, parse("정리 !낮음").priority)
        assertEquals(2, parse("정리").priority)
        assertEquals("정리 !4", parse("정리 !4").title)
    }

    @Test fun `#은 띄어쓰기·대소문자 무시하고 이름 일부로 찾는다`() {
        assertEquals(erp, parse("#erp 회의").place)
        assertEquals(claim, parse("#사고접수개편 회의").place)
        // 이름이 똑같은 곳이 먼저('심사'는 '자동심사'에도 들어 있다)
        assertEquals(review, parse("#심사 회의").place)
    }

    @Test fun `#이 여럿에 걸리거나 없으면 넣지 않고 경고`() {
        val many = parse("#사고 회의", default = review)
        assertEquals(review, many.place)
        assertEquals("#사고 회의", many.title)
        assertEquals(1, many.warnings.size)
        val none = parse("#없는곳 회의")
        assertNull(none.place)
        assertEquals("#없는곳 회의", none.title)
        assertEquals(listOf("#없는곳 프로젝트를 못 찾았어요"), none.warnings)
    }

    @Test fun `#을 두 번 쓰면 처음 것만`() {
        val r = parse("#사고접수 #erp 회의")
        assertEquals(claim, r.place)
        assertEquals("#erp 회의", r.title)
        assertEquals(1, r.warnings.size)
    }

    @Test fun `#이 없으면 기본 곳`() {
        assertEquals(accident, parse("회의", default = accident).place)
        assertNull(parse("회의").place)
    }

    @Test fun `낱말 중간의 #과 @는 건드리지 않는다`() {
        val r = parse("C# 공부 a@b.com 메일")
        assertEquals("C# 공부 a@b.com 메일", r.title)
        assertNull(r.place)
        assertNull(r.assignee)
    }

    @Test fun `오늘 탭은 기한이 없으면 오늘, 모호는 그대로`() {
        assertEquals(friday, parse("보고서", noDueMeansToday = true).dueDate)
        assertNull(parse("다음 주 보고서", noDueMeansToday = true).dueDate)
        assertEquals(day(11, 7), parse("내일 보고서", noDueMeansToday = true).dueDate)
        assertNull(parse("보고서").dueDate)
    }

    @Test fun `제목은 200자까지`() {
        val r = parse("가".repeat(250))
        assertEquals(200, r.title.length)
        assertEquals(1, r.warnings.size)
    }

    @Test fun `빈 입력은 빈 제목`() {
        assertEquals("", parse("   ").title)
        assertEquals("", parse("내일 3시").title)
    }
}
