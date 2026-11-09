package com.workbrain.app.review

import org.junit.Assert.assertEquals
import org.junit.Test

/** DEC-79 회의록 만들기 한 번만: 응답을 못 받았을 때 서버 확인 결과로 정하는 다음 걸음 */
class MinutesVerdictTest {
    @Test fun `서버가 다 만들었으면 실패가 아니라 만들어짐`() {
        assertEquals(MinutesVerdict.Made, minutesVerdict("DONE", lost = true))
        assertEquals(MinutesVerdict.Made, minutesVerdict("DONE", lost = false))
    }

    @Test fun `만드는 중이거나 확인을 못 하면 더 기다린다`() {
        assertEquals(MinutesVerdict.Wait, minutesVerdict("RUNNING", lost = true))
        assertEquals(MinutesVerdict.Wait, minutesVerdict(null, lost = true))
    }

    @Test fun `요청이 서버에 안 닿았으면 연결 문구, 서버가 실패했으면 다시 시도 문구`() {
        assertEquals(MinutesVerdict.Failed(NETWORK_ERROR), minutesVerdict("NONE", lost = true))
        assertEquals(MinutesVerdict.Failed(RETRY_ERROR), minutesVerdict("NONE", lost = false))
        assertEquals(MinutesVerdict.Failed(RETRY_ERROR), minutesVerdict("FAILED", lost = true))
    }

    @Test fun `다른 요청이 만드는 중이면 기다리지 않고 알린다`() {
        assertEquals(
            MinutesVerdict.Failed("이미 회의록을 만드는 중이에요. 다 되면 화면에 보여요"),
            minutesVerdict("BUSY", lost = true),
        )
    }
}
