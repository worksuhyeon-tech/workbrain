package com.workbrain.app.review

import com.workbrain.app.data.LinkSuggestion
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.RecordClass
import com.workbrain.app.data.suggestionNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DEC-75 AI 프로젝트 연결 제안: 확인 필요 문구(확률 숫자 없이 이유만), 화면에 올릴 제안(아직 안 이은 진행 중 프로젝트만), 분류 값.
 * 데이터는 보험 보상 업무를 흉내 낸 가상 이름이다
 */
class LinkSuggestTest {
    private val erp = ProjectRow("p-erp", "클레임 보상 ERP", version = 1)
    private val bot = ProjectRow("p-bot", "보상 상담 챗봇", version = 1)
    private val old = ProjectRow("p-old", "지난 심사 개편", version = 2, archivedAt = "2026-11-01T00:00:00Z")
    private val projects = listOf(erp, bot, old)

    private fun s(projectId: String, record: String = "r1") = LinkSuggestion(record, projectId, "medium", emptyList(), null)

    @Test fun `확신이 높으면 확인 필요 문구가 없다`() {
        assertNull(suggestionNote("high", listOf("brief_mention")))
    }

    @Test fun `이유를 사람 말로, 모르는 코드는 건너뛴다`() {
        assertEquals(
            "확인 필요 · 프로젝트 이름이 직접 나오지 않았어요 · 짧게만 다뤘어요",
            suggestionNote("medium", listOf("name_not_mentioned", "made_up", "brief_mention")),
        )
    }

    @Test fun `이유가 없으면 등급으로만`() {
        assertEquals("확인 필요 · 관련 있어 보이지만 확실하지 않아요", suggestionNote("low", emptyList()))
        assertEquals("확인 필요 · AI가 확신 정도를 매기지 않았어요", suggestionNote("unrated", emptyList()))
        assertNull(suggestionNote("medium", emptyList()))
    }

    @Test fun `이미 연결한 곳과 보관된 프로젝트의 제안은 숨긴다`() {
        val shown = visibleSuggestions(listOf(s("p-erp"), s("p-bot"), s("p-old"), s("p-gone")), projects, linkedIds = listOf("p-bot"))
        assertEquals(listOf("클레임 보상 ERP"), shown.map { it.project.name })
    }

    @Test fun `분류 값은 업무·개인·불확실만 알아본다`() {
        assertEquals(RecordClass.PERSONAL, RecordClass.of("personal"))
        assertNull(RecordClass.of(null))
        assertNull(RecordClass.of("family"))
    }
}
