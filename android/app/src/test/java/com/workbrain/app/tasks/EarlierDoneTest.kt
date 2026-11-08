package com.workbrain.app.tasks

import com.workbrain.app.data.TaskRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * DEC-74 상세 할 일 카드: 오늘(서울) 끝낸 것은 목록에 남기고, 어제 이전 완료·완료 시각 없는 옛 완료는 '이전 완료'로 접는다.
 * 오늘은 2026-11-08로 고정한다. 서버 완료 시각은 UTC(+00:00)로 온다
 */
class EarlierDoneTest {
    private val today = LocalDate.of(2026, 11, 8)

    private fun task(status: String, doneAt: String? = null) =
        TaskRow("t1", "보험금 지급 심사표 정리", status = status, version = 1, ownerKind = "me", doneAt = doneAt)

    @Test fun `안 끝난 할 일은 접지 않는다`() {
        assertFalse(isEarlierDone(task("open"), today))
    }

    @Test fun `오늘 끝낸 할 일은 목록에 남긴다`() {
        assertFalse(isEarlierDone(task("done", "2026-11-08T03:00:00+00:00"), today))
    }

    @Test fun `어제 끝낸 할 일은 접는다`() {
        assertTrue(isEarlierDone(task("done", "2026-11-07T03:00:00+00:00"), today))
    }

    @Test fun `서울 자정 직후(UTC로는 전날 15시)에 끝낸 것은 오늘 완료`() {
        assertFalse(isEarlierDone(task("done", "2026-11-07T15:00:00+00:00"), today))
    }

    @Test fun `서울 자정 직전(UTC로는 전날 14시 59분)에 끝낸 것은 이전 완료`() {
        assertTrue(isEarlierDone(task("done", "2026-11-07T14:59:59.5+00:00"), today))
    }

    @Test fun `완료 시각이 없는 옛 완료는 접는다`() {
        assertTrue(isEarlierDone(task("done", null), today))
    }
}
