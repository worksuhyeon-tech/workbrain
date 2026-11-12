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
    private val erp = Place(PlaceType.PROJECT, "p3", "01. 가상 ERP구축")
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
        assertEquals(listOf(claim, accident), many.placeChoices)
        assertEquals("#사고", many.placeAsked)
        val none = parse("#없는곳 회의")
        assertNull(none.place)
        assertEquals("#없는곳 회의", none.title)
        assertEquals(listOf("#없는곳 프로젝트를 못 찾았어요"), none.warnings)
    }

    // DEC-80: 이름이 비슷한 프로젝트가 많을 때(합성 이름)
    private val crm12 = Place(PlaceType.PROJECT, "c1", "CRM) 이관 12건")
    private val crm31 = Place(PlaceType.PROJECT, "c2", "CRM)3-1.협력사 정산")
    private val crm52 = Place(PlaceType.PROJECT, "c3", "CRM)5-2.고객 알림")
    private val crmPay = Place(PlaceType.PROJECT, "c4", "CRM) 고객 정산")
    private val crm = listOf(crm12, crm31, crm52, crmPay)
    private fun parseCrm(raw: String) = QuickParse.parse(raw, friday, crm, null)

    @Test fun `#조각 맞추기 - 영문·숫자·한글 덩어리가 이름에 순서대로 있으면 찾는다`() {
        assertEquals(crm12, parseCrm("#CRM12건 보고서").place)
        assertEquals(crm31, parseCrm("#crm3 일정").place)
        assertEquals("일정", parseCrm("#crm3 일정").title)
        assertEquals(crm12, parseCrm("#이관 보고서").place)
    }

    @Test fun `#여럿이면 띄어 쓴 다음 낱말까지 붙여 좁히고 그 낱말은 제목에서 뺀다`() {
        val r = parseCrm("#CRM 12건 보고서 수정")
        assertEquals(crm12, r.place)
        assertEquals("보고서 수정", r.title)
        assertTrue(r.warnings.isEmpty())
        assertTrue(r.placeChoices.isEmpty())
        // 하나로 정해진 뒤에는 그 이름에 이어 들어 있는 낱말만 더 뺀다
        assertEquals("회의", parse("#erp 회의").title)
    }

    @Test fun `#하나로 정해진 뒤에도 이름에 이어 들어 있는 낱말은 이름으로 본다(DEC-82)`() {
        // 'CRM 이관'에서 이미 하나지만 '12건'도 그 이름에 이어 있다
        val r = parseCrm("#CRM 이관 12건 자료 정리")
        assertEquals(crm12, r.place)
        assertEquals("자료 정리", r.title)
        // 처음부터 하나여도 같다
        assertEquals("검토", parseCrm("#이관 12건 검토").title)
        // 이름에 없는 낱말에서 멈춘다(이름에 있는 글자로 시작해도 순서대로 이어지지 않으면 본문)
        assertEquals("12건 개선안", parseCrm("#CRM 이관 12건 12건 개선안").title)
        assertEquals("개선안 정리", parseCrm("#CRM 이관 개선안 정리").title)
        assertEquals(crm12, parseCrm("#CRM 이관 개선안 정리").place)
    }

    @Test fun `#붙여서 안 맞는 낱말은 본문이라 멈추고 후보를 보여 준다`() {
        val r = parseCrm("#CRM 내일 3시 회의")
        assertNull(r.place)
        assertEquals("#CRM 회의", r.title)
        assertEquals(day(11, 7), r.dueDate)
        assertEquals(crm, r.placeChoices)
        assertEquals("#CRM", r.placeAsked)
        assertEquals(listOf("#CRM: 맞는 곳이 4곳이에요 · 아래에서 골라 주세요"), r.warnings)
    }

    @Test fun `#후보를 고르면 입력이 띄어쓰기 뺀 정확한 이름으로 바뀌어 그곳에 들어간다`() {
        val raw = "#CRM 내일 3시 회의"
        val picked = QuickParse.choosePlace(raw, "#CRM", crm31)
        assertEquals("#CRM)3-1.협력사정산 내일 3시 회의", picked)
        val r = parseCrm(picked)
        assertEquals(crm31, r.place)
        assertEquals("회의", r.title)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun `#좁히다 여럿이 남으면 좁힌 낱말까지 바꾼다`() {
        val r = parseCrm("#CRM 고객 회의")
        assertEquals(listOf(crm52, crmPay), r.placeChoices)
        assertEquals("#CRM 고객", r.placeAsked)
        assertEquals("#CRM)고객정산 회의", QuickParse.choosePlace("#CRM  고객 회의", "#CRM 고객", crmPay))
    }

    @Test fun `#후보는 5곳까지, 더 많으면 더 적어 달라고 한다`() {
        val many = (1..7).map { Place(PlaceType.PROJECT, "m$it", "팀 과제 $it") }
        val r = QuickParse.parse("#팀 회의", friday, many, null)
        assertEquals(many.take(5), r.placeChoices)
        assertEquals(listOf("#팀: 맞는 곳이 7곳이에요 · 아래에서 고르거나 더 적어 주세요"), r.warnings)
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

    // ── DEC-97 시간대 낱말·@ 안내(11/11 사용자 입력 '…요청 내일아침9시'를 못 읽음) ──

    @Test fun `사용자 줄 모양 - 내일아침9시를 읽고 @가 프로젝트 이름이면 안내`() {
        val r = parse("@사고접수 박지훈님한테 알림톡 생성 요청 내일아침9시", noDueMeansToday = true)
        assertEquals("박지훈님한테 알림톡 생성 요청", r.title)
        assertEquals(day(11, 7), r.dueDate)
        assertEquals(LocalTime.of(9, 0), r.dueTime)
        assertEquals("사고접수", r.assignee)
        assertEquals(listOf("@사고접수는 담당자로 들어가요 · 프로젝트면 #사고접수"), r.warnings)
    }

    @Test fun `시간대 낱말 + 시각 - 아침 새벽은 오전, 저녁 밤은 오후, 점심은 그냥 시각`() {
        fun at(raw: String) = parse(raw).let { it.dueTime to it.title }
        assertEquals(LocalTime.of(9, 0) to "보고", at("아침 9시 보고"))
        assertEquals(LocalTime.of(9, 0) to "보고", at("아침9시 보고"))
        // 예전에는 '7시'만 읽어 업무 시간대 규칙으로 19시가 됐다
        assertEquals(LocalTime.of(7, 0) to "운동", at("아침 7시 운동"))
        assertEquals(LocalTime.of(2, 0) to "배치 확인", at("새벽 2시 배치 확인"))
        assertEquals(LocalTime.of(19, 0) to "회식", at("저녁 7시 회식"))
        assertEquals(LocalTime.of(19, 30) to "회식", at("저녁 7:30 회식"))
        assertEquals(LocalTime.of(22, 0) to "배포", at("밤 10시 배포"))
        assertEquals(LocalTime.of(13, 0) to "약속", at("점심 1시 약속"))
        assertEquals(LocalTime.of(12, 0) to "약속", at("점심 12시 약속"))
        assertEquals(LocalTime.of(12, 0) to "점심", at("12시 점심"))
    }

    @Test fun `날짜와 시간대 낱말은 붙여 써도 띄어 써도 된다`() {
        for (raw in listOf("내일아침9시 보고", "내일 아침 9시 보고", "내일 아침9시 보고", "내일아침 9시 보고")) {
            val r = parse(raw)
            assertEquals(raw, day(11, 7), r.dueDate)
            assertEquals(raw, LocalTime.of(9, 0), r.dueTime)
            assertEquals(raw, "보고", r.title)
        }
        val r = parse("금요일저녁7시 회식")
        assertEquals(friday to LocalTime.of(19, 0), r.dueDate to r.dueTime)
    }

    @Test fun `숫자 없이 날짜 바로 뒤 - 아침 9시 점심 13시 오후 16시 퇴근전 18시`() {
        fun at(raw: String) = parse(raw).let { Triple(it.dueDate, it.dueTime, it.title) }
        assertEquals(Triple(day(11, 7), LocalTime.of(9, 0), "보고"), at("내일 아침 보고"))
        assertEquals(Triple(day(11, 7), LocalTime.of(9, 0), "보고"), at("내일아침 보고"))
        assertEquals(Triple(day(11, 7), LocalTime.of(13, 0), "약속"), at("내일 점심 약속"))
        assertEquals(Triple(friday, LocalTime.of(16, 0), "보고"), at("금요일 오후 보고"))
        assertEquals(Triple(friday, LocalTime.of(16, 0), "보고"), at("금요일오후 보고"))
        assertEquals(Triple(day(11, 12), LocalTime.of(18, 0), "정리"), at("11/12 퇴근전까지 정리"))
        assertEquals(Triple(day(11, 7), LocalTime.of(18, 0), "정리"), at("내일 퇴근 전 정리"))
    }

    @Test fun `숫자 없는 시간대 낱말이 날짜 뒤가 아니면 제목의 말`() {
        val lunch = parse("팀 점심 메뉴 정하기")
        assertEquals("팀 점심 메뉴 정하기", lunch.title)
        assertNull(lunch.dueDate)
        assertNull(lunch.dueTime)
        assertEquals("아침 보고", parse("아침 보고").title)
        // 정해 둔 시각이 없는 저녁·밤은 날짜만 읽고 낱말은 제목에 둔다
        val dinner = parse("내일 저녁 회식")
        assertEquals(day(11, 7), dinner.dueDate)
        assertNull(dinner.dueTime)
        assertEquals("저녁 회식", dinner.title)
    }

    @Test fun `밤 12시는 그날 23시59분, 밤 1~5시는 새벽으로 적게 안내`() {
        assertEquals(LocalTime.of(23, 59), parse("밤 12시 마감").dueTime)
        val r = parse("내일 밤 1시 배포")
        assertNull(r.dueTime)
        assertEquals("밤 1시 배포", r.title)
        assertEquals(listOf("밤 1시: '새벽 1시'로 적어 주세요"), r.warnings)
    }

    @Test fun `틀린 시각을 적었으면 정해 둔 시각으로 덮지 않는다`() {
        val r = parse("내일 오후 25시 회의")
        assertNull(r.dueTime)
        assertEquals(listOf("오후 25시: 없는 시각이에요"), r.warnings)
    }

    @Test fun `@가 사람 이름이면 안내 없음, 프로젝트·반복실행 이름이면 안내`() {
        assertTrue(parse("@박지훈 자료 요청").warnings.isEmpty())
        assertEquals(listOf("@심사는 담당자로 들어가요 · 프로젝트면 #심사"), parse("@심사 자료 요청").warnings)
        // 한글이 아닌 이름은 받침을 몰라 '은(는)'
        assertEquals(listOf("@ERP은(는) 담당자로 들어가요 · 프로젝트면 #ERP"), parse("@ERP 자료 요청").warnings)
    }
}
