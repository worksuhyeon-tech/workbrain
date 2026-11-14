package com.workbrain.app.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * 구글 캘린더 화면 규칙(FR-24a, Design §3.16) — 웹 rules/calendar.test.ts와 같은 경우.
 * 가짜 일정, 지금 = 2026-11-14(토) 10:20 서울
 */
class CalendarRulesTest {
    private val now = Instant.parse("2026-11-14T01:20:00Z")
    private val today = LocalDate.of(2026, 11, 14)

    private fun ev(id: String, start: String, end: String, allDay: Boolean = false) = CalEvent(id, id, start, end, allDay)
    private fun at(day: String, hm: String) = "${day}T$hm:00+09:00"

    private val todayEvents = listOf(
        ev("워크숍", "2026-11-14", "2026-11-15", allDay = true),
        ev("데일리", at("2026-11-14", "09:00"), at("2026-11-14", "09:30")),
        ev("리뷰", at("2026-11-14", "13:30"), at("2026-11-14", "14:30")),
        ev("점검", at("2026-11-14", "10:00"), at("2026-11-14", "11:00")),
        ev("통화", at("2026-11-14", "15:00"), at("2026-11-14", "15:30")),
        ev("정리", at("2026-11-14", "17:00"), at("2026-11-14", "17:30")),
    )

    @Test fun `오늘 카드 - 종일, 지난 일정 접기, 지금, 남은 일정, 처음엔 3개`() {
        val r = todayLines(todayEvents, now, moreOpen = false)
        assertEquals(listOf("워크숍"), r.allDay.map { it.e.id })
        assertEquals(listOf("데일리"), r.past.map { it.e.id })
        assertEquals(listOf("점검" to EventTone.NOW, "리뷰" to EventTone.NONE), r.rest.map { it.e.id to it.tone })
        assertEquals(2, r.hidden)
    }

    @Test fun `오늘 카드 - 펼치면 모두`() {
        val r = todayLines(todayEvents, now, moreOpen = true)
        assertEquals(listOf("점검", "리뷰", "통화", "정리"), r.rest.map { it.e.id })
        assertEquals(0, r.hidden)
    }

    @Test fun `끝나는 순간에는 지난 일정, 시작하는 순간에는 지금`() {
        val r = todayLines(
            listOf(ev("a", at("2026-11-14", "09:20"), at("2026-11-14", "10:20")), ev("b", at("2026-11-14", "10:20"), at("2026-11-14", "10:50"))),
            now, moreOpen = false,
        )
        assertEquals(listOf("a"), r.past.map { it.e.id })
        assertEquals(listOf("b" to EventTone.NOW), r.rest.map { it.e.id to it.tone })
    }

    @Test fun `종일이 많으면 종일만으로 3개를 채운다`() {
        val many = listOf("가", "나", "다", "라").map { ev(it, "2026-11-14", "2026-11-15", allDay = true) }
        val r = todayLines(many + ev("점검", at("2026-11-14", "10:00"), at("2026-11-14", "11:00")), now, moreOpen = false)
        assertEquals(3, r.allDay.size)
        assertEquals(0, r.rest.size)
        assertEquals(2, r.hidden)
    }

    @Test fun `시각은 서울로, 종일은 '종일'`() {
        assertEquals("10:00" to "11:30", eventTimes(ev("x", "2026-11-14T01:00:00Z", "2026-11-14T02:30:00Z")))
        assertEquals("종일" to null, eventTimes(ev("y", "2026-11-14", "2026-11-15", allDay = true)))
    }

    private val weekEvents = listOf(
        ev("주간회의", at("2026-11-16", "09:30"), at("2026-11-16", "10:30")),
        ev("데일리", at("2026-11-14", "09:00"), at("2026-11-14", "09:30")),
        ev("워크숍", "2026-11-14", "2026-11-15", allDay = true),
        ev("출장", "2026-11-13", "2026-11-15", allDay = true), // 어제 시작해 오늘까지 → 오늘에
        ev("점검", at("2026-11-14", "10:00"), at("2026-11-14", "11:00")),
        ev("인터뷰", at("2026-11-19", "11:00"), at("2026-11-19", "12:00")),
        ev("월간", at("2026-11-20", "15:00"), at("2026-11-20", "16:00")),
    )

    @Test fun `다음 7일 - 날짜별, 날마다 종일 먼저, 시각순, 처음엔 5개`() {
        val r = weekGroups(weekEvents, today, now, moreOpen = false)
        assertEquals(7, r.total)
        assertEquals(2, r.hidden)
        assertEquals(
            listOf(Triple("11월 14일 (토)", "오늘", listOf("출장", "워크숍", "데일리", "점검")), Triple("11월 16일 (월)", null, listOf("주간회의"))),
            r.groups.map { g -> Triple(g.label, g.rel, g.lines.map { it.e.id }) },
        )
    }

    @Test fun `다음 7일 - 오늘 끝난 일정은 흐리게, 지금인 것은 강조하지 않음`() {
        val first = weekGroups(weekEvents, today, now, moreOpen = true).groups.first()
        assertEquals(listOf(EventTone.NONE, EventTone.NONE, EventTone.PAST, EventTone.NONE), first.lines.map { it.tone })
    }

    @Test fun `다음 7일 - 펼치면 모두`() {
        val r = weekGroups(weekEvents, today, now, moreOpen = true)
        assertEquals(0, r.hidden)
        assertEquals(listOf(14, 16, 19, 20), r.groups.map { it.day.dayOfMonth })
    }

    @Test fun `날짜 머리 - 월일 요일, 오늘·내일만 옆에 작게`() {
        assertEquals("11월 30일 (월)", eventDayLabel(LocalDate.of(2026, 11, 30)))
        assertEquals("오늘", eventDayRel(today, today))
        assertEquals("내일", eventDayRel(LocalDate.of(2026, 11, 15), today))
        assertNull(eventDayRel(LocalDate.of(2026, 11, 30), today))
    }

    @Test fun `줄 모양(삼성 캘린더처럼) - 왼쪽 12시 시각, 아래 줄 오전·오후 범위·장소`() {
        assertEquals("1:30", weekStart(ev("a", at("2026-11-14", "13:30"), at("2026-11-14", "14:30"))))
        assertEquals("12:05", weekStart(ev("b", at("2026-11-14", "00:05"), at("2026-11-14", "12:00"))))
        assertNull(weekStart(ev("c", "2026-11-14", "2026-11-15", allDay = true)))
        assertEquals("오전 11:30 - 오후 12:30", weekSub(ev("d", at("2026-11-14", "11:30"), at("2026-11-14", "12:30"))))
        assertEquals("오전 8:00 - 오전 9:00 · 본사 6층 회의실", weekSub(ev("e", at("2026-11-14", "08:00"), at("2026-11-14", "09:00")).copy(location = "본사 6층 회의실")))
    }

    @Test fun `종일 - 하루면 하루 종일, 여러 날이면 첫날 - 끝날`() {
        assertEquals("하루 종일", weekSub(ev("a", "2026-11-14", "2026-11-15", allDay = true)))
        assertEquals("11월 13일 - 11월 15일", weekSub(ev("b", "2026-11-13", "2026-11-16", allDay = true)))
        assertEquals("11월 30일 - 12월 1일 · 제주", weekSub(ev("c", "2026-11-30", "2026-12-02", allDay = true).copy(location = "제주")))
    }

    @Test fun `서랍 - 저장할 때 할 일`() {
        assertEquals(CalendarPlan.ADD, calendarPlan(connected = true, linked = false, on = true, hasDate = true))
        assertEquals(CalendarPlan.NONE, calendarPlan(connected = true, linked = false, on = true, hasDate = false))
        assertEquals(CalendarPlan.NONE, calendarPlan(connected = false, linked = false, on = true, hasDate = true))
        assertEquals(CalendarPlan.KEEP, calendarPlan(connected = true, linked = true, on = true, hasDate = true))
        // 끄거나 기한을 지우면 묻는다(11/14 결정 ④)
        assertEquals(CalendarPlan.ASK, calendarPlan(connected = true, linked = true, on = false, hasDate = true))
        assertEquals(CalendarPlan.ASK, calendarPlan(connected = true, linked = true, on = true, hasDate = false))
        // 연결이 끊긴 동안은 건드리지 않는다
        assertEquals(CalendarPlan.KEEP, calendarPlan(connected = false, linked = true, on = false, hasDate = false))
    }

    @Test fun `서랍 - 안내 문구`() {
        assertTrue(calendarNote(connected = false, linked = false, on = false, hasDate = true, withTime = false).contains("더보기에서 구글 캘린더를 연결"))
        assertEquals("기한 날짜를 정하면 넣을 수 있어요.", calendarNote(connected = true, linked = false, on = false, hasDate = false, withTime = false))
        assertTrue(calendarNote(connected = true, linked = false, on = true, hasDate = true, withTime = true).startsWith("그 시각부터 1시간"))
        assertTrue(calendarNote(connected = true, linked = false, on = true, hasDate = true, withTime = false).startsWith("종일 일정"))
        // 이미 넣은 일정은 캘린더에서 길이를 바꿨을 수 있어 1시간이라고 말하지 않는다
        assertTrue(calendarNote(connected = true, linked = true, on = true, hasDate = true, withTime = true).startsWith("제목·기한을 고치면"))
        assertEquals("", calendarNote(connected = true, linked = false, on = false, hasDate = true, withTime = false))
    }

    @Test fun `묻는 글`() {
        assertTrue(askSaveText("업체 미팅", hasDate = false).contains("기한을 지웠어요"))
        assertTrue(askDeleteText(listOf("업체 미팅"), total = 1).startsWith("‘업체 미팅’ 일정이"))
        assertTrue(askDeleteText(listOf("업체 미팅"), total = 3).startsWith("지우는 할 일 3개 중 1개"))
    }

    @Test fun `장소 - 있으면 받고, 예전 서버처럼 없으면 null`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val base = """"id":"a","title":"주간 회의","start":"2026-11-14","end":"2026-11-15","allDay":true,"link":null"""
        assertEquals("본사 3층 대회의실", json.decodeFromString(CalEvent.serializer(), """{$base,"location":"본사 3층 대회의실"}""").location)
        assertNull(json.decodeFromString(CalEvent.serializer(), """{$base,"location":null}""").location)
        assertNull(json.decodeFromString(CalEvent.serializer(), "{$base}").location)
    }

    @Test fun `허락 결과 알림`() {
        assertEquals("구글 캘린더를 연결했어요", connectResultText("ok"))
        assertTrue(connectResultText("wrong_account")!!.contains("다른 Google 계정"))
        assertNull(connectResultText("evil"))
        assertNull(connectResultText(null))
    }
}
