package com.workbrain.app.decide

import com.workbrain.app.data.DecisionKind
import com.workbrain.app.data.SecretaryCard
import com.workbrain.app.data.SecretarySource
import com.workbrain.app.data.TaskRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** 비서와 결정 규칙(DEC-111, Design §3.21) — 오늘 = 2026-11-18(수), 가짜 할 일 */
class DecideRulesTest {
    private val today = LocalDate.of(2026, 11, 18)
    private fun task(
        id: String, due: String?, owner: String = "me", parent: String? = null, status: String = "open",
        created: String = "2026-11-01T00:00:00Z", assignee: String? = null, dueTime: String? = null,
    ) = TaskRow(id = id, title = "할 일 $id", status = status, version = 0, ownerKind = owner, assignee = assignee,
        dueDate = due, dueTime = dueTime, parentId = parent, createdAt = created)

    @Test fun `다시 정할 일 줄 - 내 할 일 오래된 순, 팔로업은 뒤, 상위가 함께 밀린 하위는 빼고 끝낸 것·오늘 기한은 빼기`() {
        val tasks = listOf(
            task("a", "2026-11-12"), task("b", "2026-11-09"), task("f", "2026-11-10", owner = "other", assignee = "개발 담당"),
            task("c", "2026-11-16"), task("c1", "2026-11-15", parent = "c"), task("d1", "2026-11-11", parent = "d"),
            task("d", "2026-11-25"), task("x", "2026-11-10", status = "done"), task("t", "2026-11-18"), task("n", null),
        )
        assertEquals(listOf("b", "d1", "a", "c", "f"), decideQueue(tasks, today).map { it.id })
    }

    @Test fun `차분한 칩 - N일 지남, 팔로업은 N일째 기다림, 안 밀렸으면 없음`() {
        assertEquals("11/9(월) · 9일 지남", calmLateLabel(task("a", "2026-11-09"), today))
        assertEquals("11/10(화) · 8일째 기다림", calmLateLabel(task("f", "2026-11-10", owner = "other"), today))
        assertNull(calmLateLabel(task("t", "2026-11-18"), today))
        assertNull(calmLateLabel(task("n", null), today))
    }

    @Test fun `다음 근무일 셋 - 토·일 건너뜀, 가장 여유 있는 날은 같으면 이른 날`() {
        assertEquals(listOf("2026-11-19", "2026-11-20", "2026-11-23"), nextWorkdays(today).map { it.toString() })
        assertEquals(listOf("2026-11-23", "2026-11-24", "2026-11-25"), nextWorkdays(LocalDate.of(2026, 11, 20)).map { it.toString() })
        assertEquals(1, lightestIndex(listOf(3, 1, 2)))
        assertEquals(0, lightestIndex(listOf(2, 2, 2)))
        assertEquals(listOf("2026-11-19", "2026-11-20", "2026-11-23"), followUpDays(today).map { it.second.toString() })
    }

    @Test fun `그날 할 일 수 - 내 상위 할 일 중 안 끝난 것만`() {
        val day = LocalDate.of(2026, 11, 20)
        val tasks = listOf(task("a", "2026-11-20"), task("b", "2026-11-20", status = "done"), task("c", "2026-11-20", owner = "other", assignee = "x"),
            task("d", "2026-11-20", parent = "a"), task("e", "2026-11-21"))
        assertEquals(1, loadOn(day, tasks))
    }

    @Test fun `추천 - 캘린더 일정 지남이면 이미 끝냄, 두 번 넘게 미뤘으면 나누기, 그 밖은 바로 시작`() {
        val card = SecretaryCard("a", defers = 2)
        assertEquals(DecisionKind.DONE, recommend(card, calendarPassed = true))
        assertEquals(DecisionKind.SPLIT, recommend(card, calendarPassed = false))
        assertEquals(DecisionKind.START, recommend(SecretaryCard("a", defers = 1), false))
        assertEquals(DecisionKind.START, recommend(null, false))
        val timed = task("a", "2026-11-13", dueTime = "2026-11-13T06:00:00Z")
        assertTrue(calendarPassed(timed, linked = true, now = Instant.parse("2026-11-18T00:00:00Z")))
        assertFalse(calendarPassed(timed, linked = false, now = Instant.parse("2026-11-18T00:00:00Z")))
    }

    @Test fun `비서 사실 - 출처·하위 진행·미룬 횟수(없으면 손대지 않음), 팔로업은 기다린 날`() {
        val card = SecretaryCard("a", source = SecretarySource("주간 회의", "2026-11-06T01:00:00Z"), defers = 0, untouched = true)
        assertEquals(listOf("11/6 주간 회의에서 맡은 일이에요.", "만든 뒤로 아직 손대지 않았어요."),
            factsOf(task("a", "2026-11-09"), card, today, calendarPassed = false, kids = emptyList()))
        val kids = listOf(task("k1", null, parent = "a", status = "done"), task("k2", null, parent = "a"))
        assertEquals(listOf("하위 2개 중 1개는 끝냈어요.", "기한을 벌써 2번 옮겼어요."),
            factsOf(task("a", "2026-11-09"), SecretaryCard("a", defers = 2), today, false, kids))
        assertEquals(listOf("개발 담당 회신을 8일째 기다리고 있어요."),
            factsOf(task("f", "2026-11-10", owner = "other", assignee = "개발 담당"), null, today, false, emptyList()))
        assertEquals(listOf("캘린더에 넣은 일정(11/13 오후 3:00)이 지났어요."),
            factsOf(task("c", "2026-11-13", dueTime = "2026-11-13T06:00:00Z"), null, today, true, emptyList()))
    }

    @Test fun `그날 오전 9시 알림 값 - 날짜만 0, 시각이 9시 뒤면 9시까지의 분, 9시 전이면 0`() {
        assertEquals(0, remindAtNine(null))
        assertEquals(360, remindAtNine(LocalTime.of(15, 0)))
        assertEquals(0, remindAtNine(LocalTime.of(8, 30)))
    }

    @Test fun `날짜를 옮겨도 시각은 그대로(서울)`() {
        assertEquals("2026-11-20T15:00+09:00", movedDueTime("2026-11-13T06:00:00Z", LocalDate.of(2026, 11, 20)))
        assertNull(movedDueTime(null, LocalDate.of(2026, 11, 20)))
    }

    @Test fun `기다림 이름과 새 하위 - 겹침·빈 줄 빼기`() {
        val t = task("a", "2026-11-09").copy(recipients = listOf("디자인 담당"))
        assertEquals(listOf("디자인 담당", "개발 담당", "정산 담당"), waitNames(t, listOf("개발 담당", "디자인 담당", "나", "정산 담당")))
        val existing = listOf(task("k1", null, parent = "a").copy(title = "메일 모으기"), task("k2", null, parent = "a", status = "done").copy(title = "표 만들기"))
        assertEquals(listOf("표 만들기", "칸 채우기"), newSubtaskTitles(listOf(" 메일 모으기 ", "표 만들기", "", "칸 채우기", "칸 채우기"), existing))
    }

    @Test fun `집중 시계 - 멈춤은 빼고, 정한 시간이 지나도 이어서 재고, 25분으로 이어 가도 한 시간은 그대로`() {
        var c = FocusClock.start(2, 0)
        assertEquals(120_000, c.leftMs(0))
        c = c.pause(30_000)
        assertTrue(c.paused)
        assertEquals(90_000, c.leftMs(50_000))
        assertNull(c.endAtMs(50_000))
        c = c.resume(50_000)
        assertEquals(30_000, c.activeMs(50_000))
        assertEquals(140_000L, c.endAtMs(50_000))
        assertFalse(c.over(139_000))
        assertTrue(c.over(140_000))
        assertEquals(42_000, c.overMs(182_000))
        assertEquals(162_000, c.spentMs(182_000))
        val long = c.switchTo(LONG_FOCUS_MIN, 182_000)
        assertEquals(25 * 60_000L, long.leftMs(182_000))
        assertEquals(162_000 + 60_000, long.spentMs(242_000))
    }

    @Test fun `시간 글자`() {
        assertEquals("2:00", mmss(120_000))
        assertEquals("1:21", mmss(80_100))
        assertEquals("0:00", mmss(-5))
        assertEquals("4분 12초", spentText(252_000))
        assertEquals("48초", spentText(48_500))
        assertEquals("2분", spentText(120_000))
        assertEquals("11/23(월)", mdLabel(LocalDate.of(2026, 11, 23)))
    }
}
