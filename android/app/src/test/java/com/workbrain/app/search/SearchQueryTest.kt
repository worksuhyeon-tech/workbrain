package com.workbrain.app.search

import com.workbrain.app.data.SearchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** 검색(FR-13, DEC-98) 화면 밖 규칙. 예시 이름은 모두 지어낸 것 */
class SearchQueryTest {
    private fun row(
        kind: String, id: String, at: String?, draft: Boolean = false, recordId: String? = null,
        projectId: String? = null, areaId: String? = null, recordTitle: String? = null,
    ) = SearchRow(kind = kind, id = id, title = "제목 $id", recordId = recordId, recordTitle = recordTitle, projectId = projectId, areaId = areaId, at = at, draft = draft)

    @Test
    fun `검색어는 앞뒤 공백을 빼고 두 글자부터, 100자까지`() {
        assertEquals("개발 공수", cleanQuery("  개발 공수 \n"))
        assertFalse(canSearch(" 청 "))
        assertTrue(canSearch("청약"))
        assertEquals(SEARCH_MAX, cleanQuery("가".repeat(150)).length)
        // 100번째 글자가 이모지 앞 반쪽이면 그 반쪽은 뺀다(서버로 깨진 글자를 보내지 않음)
        val cut = cleanQuery("가".repeat(99) + "😀")
        assertEquals(99, cut.length)
    }

    @Test
    fun `강조 구간은 대소문자를 무시하고 겹치지 않게 모두`() {
        assertEquals(listOf(0..2, 9..11), matchRanges("CRM 이관 · crm 정리", "crm"))
        assertEquals(listOf(0..1, 2..3), matchRanges("가가가가", "가가"))
        assertTrue(matchRanges("청약 화면", "청").isEmpty())
        assertTrue(matchRanges("", "청약").isEmpty())
    }

    @Test
    fun `기간 칩은 지금부터 30일·7일 전, 전체는 거르지 않음`() {
        val now = Instant.parse("2026-11-12T00:00:00Z")
        assertNull(SearchPeriod.ALL.since(now))
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), SearchPeriod.MONTH.since(now))
        assertEquals(Instant.parse("2026-11-05T00:00:00Z"), SearchPeriod.WEEK.since(now))
        assertEquals(SearchPeriod.ALL, SearchPeriod.of("모름"))
    }

    @Test
    fun `종류 칩 할 일에는 검토 대기 후보도 함께`() {
        assertNull(SearchKind.ALL.serverKinds)
        assertEquals(listOf("task", "proposal"), SearchKind.TASK.serverKinds)
        assertEquals(listOf("record"), SearchKind.MINUTES.serverKinds)
        assertEquals(listOf("resource"), SearchKind.RESOURCE.serverKinds)
    }

    @Test
    fun `결과는 최근 것부터, 초안 회의록은 위에 함께 두고 할 일 후보만 아래로, 이 기기 휴지통 기록과 그 후보는 뺀다`() {
        val rows = listOf(
            row(KIND_TASK, "t1", "2026-11-01T01:00:00+00:00"),
            row(KIND_RECORD, "r1", "2026-11-10T01:00:00+00:00", recordId = "r1"),
            row(KIND_RECORD, "r2", "2026-11-11T01:00:00+00:00", draft = true, recordId = "r2"),
            row(KIND_PROPOSAL, "c1", "2026-11-09T01:00:00+00:00", draft = true, recordId = "trash"),
            row(KIND_PROPOSAL, "c2", "2026-11-08T01:00:00+00:00", draft = true, recordId = "r1"),
            row(KIND_RECORD, "trash", "2026-11-12T01:00:00+00:00", recordId = "trash"),
            row(KIND_RESOURCE, "s1", null),
        )
        val split = splitResults(rows, trashedRecordIds = setOf("trash"))
        assertEquals(listOf("r2", "r1", "t1", "s1"), split.shown.map { it.id })
        assertEquals(listOf("c2"), split.candidates.map { it.id })
    }

    @Test
    fun `결과가 없을 때 넓히기 버튼은 걸린 필터와 접어 둔 할 일 후보만`() {
        assertEquals(emptyList<Relax>(), relaxOptions(SearchPeriod.ALL, placeFiltered = false, candidatesOpen = false, candidateCount = 0))
        assertEquals(
            listOf(Relax.PERIOD, Relax.PLACE, Relax.CANDIDATES),
            relaxOptions(SearchPeriod.WEEK, placeFiltered = true, candidatesOpen = false, candidateCount = 2),
        )
        assertEquals(listOf(Relax.PERIOD), relaxOptions(SearchPeriod.MONTH, placeFiltered = false, candidatesOpen = true, candidateCount = 2))
    }

    @Test
    fun `누르면 갈 곳 - 할 일은 속한 곳 상세, 자료는 그 자료, 회의록과 후보는 기록 상세`() {
        assertEquals(SearchTarget.Projects("p:P1", focusTaskId = "t"), targetOf(row(KIND_TASK, "t", null, projectId = "P1")))
        assertEquals(SearchTarget.Projects("a:A1", focusTaskId = "t"), targetOf(row(KIND_TASK, "t", null, areaId = "A1")))
        assertEquals(SearchTarget.Projects("all", focusTaskId = "t"), targetOf(row(KIND_TASK, "t", null)))
        assertEquals(SearchTarget.Projects("r:S1"), targetOf(row(KIND_RESOURCE, "S1", null, projectId = "P1")))
        assertEquals(SearchTarget.Record("R1", "주간 합성 회의"), targetOf(row(KIND_PROPOSAL, "c", null, recordId = "R1", recordTitle = "주간 합성 회의")))
        assertEquals(SearchTarget.Record("R2", "제목 없는 기록"), targetOf(row(KIND_RECORD, "R2", null, recordId = "R2")))
        assertNull(targetOf(row(KIND_RECORD, "R3", null)))
    }

    @Test
    fun `메타 날짜는 서울 날짜, 할 일과 자료는 만든 날`() {
        // UTC 11/10 16시 = 서울 11/11 01시
        assertEquals("11/11", metaDate(row(KIND_RECORD, "r", "2026-11-10T16:00:00+00:00")))
        assertEquals("11/11 만듦", metaDate(row(KIND_TASK, "t", "2026-11-10T16:00:00+00:00")))
        assertNull(metaDate(row(KIND_RESOURCE, "s", null)))
    }
}
