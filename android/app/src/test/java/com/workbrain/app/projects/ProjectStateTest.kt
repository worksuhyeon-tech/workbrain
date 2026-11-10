package com.workbrain.app.projects

import com.workbrain.app.data.LinkedRecord
import com.workbrain.app.data.RecordBrief
import com.workbrain.app.data.StateProposalRow
import com.workbrain.app.data.StateQuote
import com.workbrain.app.data.stateLabel
import com.workbrain.app.ui.Tone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-09 현재 상태(DEC-86): 정리할 회의 고르기·제안 표시 값. 화면·서버 없이 규칙만 본다 */
class ProjectStateTest {

    private fun linked(id: String, at: String?) = LinkedRecord("p1", RecordBrief(id, "회의 $id", at, "recording", null))
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test fun `연결된 기록으로 정리하기는 최근 회의 3개를 오래된 것부터`() {
        val records = listOf(
            linked("a", "2026-10-01T01:00:00+00:00"),
            linked("b", "2026-11-05T01:00:00+00:00"),
            linked("c", null),
            linked("d", "2026-11-09T01:00:00+00:00"),
            linked("e", "2026-10-20T01:00:00+00:00"),
        )
        assertEquals(listOf("e", "b", "d"), StateWorker.initRecords(records))
        assertEquals(listOf("c"), StateWorker.initRecords(listOf(linked("c", null))))
        assertEquals(emptyList<String>(), StateWorker.initRecords(emptyList()))
    }

    @Test fun `제안 값 읽기 - 과거 회의·회의 수·처음 상태`() {
        val p = StateProposalRow(
            id = "x", status = "pending", revision = 2, projectId = "p1", baseVersion = 3,
            before = obj("""{"summary":"8건 이관 끝.","status":"active"}"""),
            after = obj("""{"summary":"10건 이관 끝.","status":"paused","past":true,"records":["r1","r2"],"record_title":"합성 회의","record_at":"2026-11-08T01:00:00Z"}"""),
            rationale = "2건 더 끝남",
            evidence = listOf(StateQuote("앞 근거"), StateQuote(null), StateQuote("뒤 근거"), StateQuote(" ")),
        )
        assertEquals("10건 이관 끝.", p.summary)
        assertEquals("paused", p.stateStatus)
        assertEquals("8건 이관 끝.", p.beforeSummary)
        assertTrue(p.past)
        assertEquals(2, p.recordCount)
        assertEquals("뒤 근거", p.quote)
        val first = p.copy(before = null, after = obj("""{"summary":"처음","status":"active"}"""), evidence = emptyList())
        assertNull(first.beforeSummary)
        assertFalse(first.past)
        assertEquals(0, first.recordCount)
        assertNull(first.quote)
    }

    @Test fun `기준 버전이 지금과 다르면 낡은 제안`() {
        val p = StateProposalRow("x", "pending", 0, "p1", 3, null, obj("""{"summary":"a","status":"active"}"""), "r")
        assertFalse(p.staleAgainst(3))
        assertTrue(p.staleAgainst(4))
        assertFalse(p.copy(baseVersion = null).staleAgainst(4))
    }

    @Test fun `상태 이름과 색`() {
        assertEquals("진행 중", stateLabel("active"))
        assertEquals("멈춤", stateLabel("paused"))
        assertEquals("완료", stateLabel("done"))
        assertEquals("진행 중", stateLabel(null))
        assertEquals(Tone.Good, stateTone("active"))
        assertEquals(Tone.Warn, stateTone("paused"))
        assertEquals(Tone.Mute, stateTone("done"))
    }

    @Test fun `날짜는 서울 기준 월·일`() {
        assertEquals("11/10", monthDay("2026-11-09T15:30:00+00:00"))
        assertNull(monthDay("어제"))
        assertNull(monthDay(null))
    }
}
