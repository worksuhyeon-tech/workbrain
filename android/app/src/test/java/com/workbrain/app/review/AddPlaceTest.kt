package com.workbrain.app.review

import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 결정 → 할 일(DEC-48)의 task 행: 고치기 창 값 중 task 표 칸만 + 들어갈 곳 키.
 * 11/13 사용자 제보 '만들지 못했어요' — 후보 전용 owner_basis가 task 표에 들어가 서버가 거부했다. 이름은 가상
 */
class AddPlaceTest {
    private val edited = JsonObject(
        mapOf(
            "title" to JsonPrimitive("흐름도 확정본 공유"),
            "owner_kind" to JsonPrimitive("other"),
            "assignee" to JsonPrimitive("홍가상"),
            "due_date" to JsonPrimitive("2026-11-17"),
            "due_time" to JsonNull,
            "due_ambiguous" to JsonPrimitive(false),
            "due_text" to JsonNull,
            "owner_basis" to JsonNull,
        ),
    )

    @Test fun `후보 전용 칸은 빼고 task 칸과 곳 키만`() {
        val out = decisionTaskFields(edited, Place(PlaceType.PROJECT, "p1", "가상 ERP"))
        assertEquals(setOf("title", "owner_kind", "assignee", "due_date", "due_time", "due_ambiguous", "due_text", "project_id"), out.keys)
        assertEquals(JsonPrimitive("p1"), out["project_id"])
    }

    @Test fun `반복실행이면 area_id, 정리 안 됨이면 곳 키 없음`() {
        assertEquals(JsonPrimitive("a1"), decisionTaskFields(edited, Place(PlaceType.AREA, "a1", "팀 운영"))["area_id"])
        val none = decisionTaskFields(edited, null)
        assertEquals(false, "project_id" in none.keys || "area_id" in none.keys || "owner_basis" in none.keys)
    }
}
