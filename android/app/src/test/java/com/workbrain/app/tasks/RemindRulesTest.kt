package com.workbrain.app.tasks

import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.TaskRow
import com.workbrain.app.reminders.planReminders
import com.workbrain.app.ui.SEOUL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** 할 일 미리 알림 규칙(DEC-108, Design §3.19) — 지금 = 2026-11-15(일) 10:00 서울, 가짜 할 일 */
class RemindRulesTest {
    private val now = ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, SEOUL)
    private val today = LocalDate.of(2026, 11, 15)

    private fun row(id: String, due: String?, time: String? = null, remind: Int?, status: String = "open", vague: Boolean = false, project: String? = "p1") =
        TaskRow(id = id, title = "할 일 $id", status = status, version = 0, ownerKind = "me", dueDate = due, dueTime = time, dueAmbiguous = vague, projectId = project, remindBeforeMin = remind)

    @Test fun `울리는 시각 - 기한 시각에서 빼기, 날짜만이면 그날 오전 9시 기준`() {
        val d = LocalDate.of(2026, 11, 16)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 14, 0, 0, 0, SEOUL), remindAt(d, LocalTime.of(15, 0), 60))
        assertEquals(ZonedDateTime.of(2026, 11, 16, 15, 0, 0, 0, SEOUL), remindAt(d, LocalTime.of(15, 0), 0))
        assertEquals(ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, SEOUL), remindAt(d, null, 0))
        assertEquals(ZonedDateTime.of(2026, 11, 15, 9, 0, 0, 0, SEOUL), remindAt(d, null, 1440))
        // 자정 넘김: 0시 10분의 30분 전 = 전날 23:40
        assertEquals(ZonedDateTime.of(2026, 11, 15, 23, 40, 0, 0, SEOUL), remindAt(d, LocalTime.of(0, 10), 30))
        assertNull(remindAt(null, LocalTime.of(15, 0), 60))
        assertNull(remindAt(d, LocalTime.of(15, 0), null))
    }

    @Test fun `서버 할 일 - 끝냈거나 모호·기한 없음이면 안 울림, 시각은 서울로`() {
        assertEquals(ZonedDateTime.of(2026, 11, 16, 14, 0, 0, 0, SEOUL).toInstant(), remindAtOf(row("a", "2026-11-16", "2026-11-16T06:00:00+00:00", 60)))
        assertNull(remindAtOf(row("b", "2026-11-16", remind = 60, status = "done")))
        assertNull(remindAtOf(row("c", null, remind = 60)))
        assertNull(remindAtOf(row("d", "2026-11-16", remind = 60, vague = true)))
        assertNull(remindAtOf(row("e", "2026-11-16", remind = null)))
    }

    @Test fun `목록 글자 - 정각·분·시간·일`() {
        assertEquals("미리 알림 없음", remindLabel(null))
        assertEquals("정각", remindLabel(0))
        assertEquals("10분 전", remindLabel(10))
        assertEquals("1시간 전", remindLabel(60))
        assertEquals("90분 전", remindLabel(90))
        assertEquals("2일 전", remindLabel(2880))
        assertEquals(listOf(null, 0, 10, 30, 60, 1440), REMIND_PRESETS)
    }

    @Test fun `직접 설정 - 1분부터 30일까지`() {
        assertEquals(120, customRemind(2, RemindUnit.HOUR))
        assertEquals(43_200, customRemind(30, RemindUnit.DAY))
        assertNull(customRemind(31, RemindUnit.DAY))
        assertNull(customRemind(0, RemindUnit.MIN))
        assertNull(customRemind(null, RemindUnit.MIN))
    }

    @Test fun `둘째 줄 - 울리는 때, 지났으면 안내, 날짜만 기준`() {
        assertEquals("11월 16일 (월) 오후 2:00에 울려요", remindWhenText(ZonedDateTime.of(2026, 11, 16, 14, 0, 0, 0, SEOUL), dateOnly = false, now = now))
        assertEquals("이미 지난 시각이에요 · 울리지 않아요", remindWhenText(now.minusMinutes(1), dateOnly = false, now = now))
        assertEquals("날짜만 있으면 그날 오전 9시 기준", remindWhenText(null, dateOnly = true, now = now))
        assertNull(remindWhenText(null, dateOnly = false, now = now))
    }

    @Test fun `알림 내용 - 오늘 시각·오늘·내일·다른 날, 곳`() {
        assertEquals("오후 3:00 마감 · 사고접수", remindNotiText(today, LocalTime.of(15, 0), today, "사고접수"))
        assertEquals("오늘 마감", remindNotiText(today, null, today, null))
        assertEquals("내일 오전 9:30 마감", remindNotiText(today.plusDays(1), LocalTime.of(9, 30), today, null))
        assertEquals("11/20 마감 · 운영", remindNotiText(LocalDate.of(2026, 11, 20), null, today, "운영"))
    }

    @Test fun `예약할 것 - 지금 뒤 14일 안만, 누르면 갈 곳`() {
        val tasks = listOf(
            row("soon", "2026-11-16", "2026-11-16T06:00:00+00:00", 60),
            row("past", "2026-11-15", "2026-11-15T00:30:00+00:00", 0),
            row("far", "2026-12-20", remind = 0),
            row("area", "2026-11-17", remind = 1440, project = null).copy(areaId = "a9"),
            row("done", "2026-11-16", remind = 0, status = "done"),
        )
        val plan = planReminders(tasks, now.toInstant(), today)
        assertEquals(listOf("soon", "area"), plan.map { it.taskId })
        assertEquals("p:p1", plan[0].key)
        assertEquals("a:a9", plan[1].key)
        assertEquals("내일 오후 3:00 마감", plan[0].text)
        assertTrue(shouldSchedule(now.plusDays(13).toInstant(), now.toInstant()))
        assertFalse(shouldSchedule(now.plusDays(15).toInstant(), now.toInstant()))
    }

    @Test fun `기한이 없거나 모호하면 알림 값은 저장되지 않는다`() {
        val base = TaskEdit("미팅", null, "me", null, "2026-11-16", null, false, null, null, null, remindBeforeMin = 60)
        assertEquals(60, base.savedRemind)
        assertNull(base.copy(dueDate = null).savedRemind)
        assertNull(base.copy(dueAmbiguous = true).savedRemind)
    }
}
