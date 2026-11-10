package com.workbrain.app.tasks

import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.TaskRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⋮ 메뉴 정렬 방식·선택 모드(DEC-92). 예시 이름은 모두 지어낸 것 */
class TaskSelectTest {
    private fun task(id: String, priority: Int? = null, date: String? = null, time: String? = null) =
        TaskRow(id = id, title = "할 일 $id", status = "open", version = 0, ownerKind = "me", priority = priority, dueDate = date, dueTime = time)

    private val urgentLate = task("a", priority = 1, date = "2026-11-20")
    private val normalSoon = task("b", priority = 2, date = "2026-11-12")
    private val lowTimed = task("c", priority = 3, time = "2026-11-12T09:00:00+09:00")
    private val noDue = task("d", priority = 1)

    @Test
    fun `우선순위순은 높음 먼저, 같은 우선순위 안에서 기한 순`() {
        val sorted = listOf(lowTimed, normalSoon, noDue, urgentLate).sortedWith(taskComparator(TaskOrder.PRIORITY))
        assertEquals(listOf("a", "d", "b", "c"), sorted.map { it.id })
    }

    @Test
    fun `기한순은 이른 날 먼저, 같은 날은 시각 있는 일이 먼저, 기한 없음은 맨 뒤`() {
        val sorted = listOf(noDue, urgentLate, normalSoon, lowTimed).sortedWith(taskComparator(TaskOrder.DUE))
        assertEquals(listOf("c", "b", "a", "d"), sorted.map { it.id })
    }

    @Test
    fun `선택 - 하나씩 고르고 전체 선택은 보이는 것만, 끝내면 비운다`() {
        val sel = TaskSelection(active = false, ids = emptySet())
        sel.start()
        sel.visible = listOf("a", "b", "c")
        sel.toggle("b")
        assertEquals(setOf("b"), sel.ids)
        assertFalse(sel.allSelected)
        sel.toggleAll()
        assertEquals(setOf("a", "b", "c"), sel.ids)
        assertTrue(sel.allSelected)
        sel.toggleAll()
        assertTrue(sel.ids.isEmpty())
        sel.toggle("a")
        sel.stop()
        assertFalse(sel.active)
        assertTrue(sel.ids.isEmpty())
    }

    @Test
    fun `옮긴 뒤·지운 뒤 알림 문구`() {
        val to = Place(PlaceType.PROJECT, "p1", "가상 프로젝트")
        assertEquals("할 일 2개를 ‘가상 프로젝트’(으)로 옮겼어요", movedManyText(MoveResult(listOf(urgentLate, normalSoon), 0, 0), to))
        assertEquals(
            "할 일 1개를 ‘정리 안 됨’(으)로 옮겼어요 · 1개는 다른 기기에서 먼저 바뀌어 건너뜀",
            movedManyText(MoveResult(listOf(urgentLate), 1, 0), null),
        )
        assertEquals("할 일 a 삭제되었어요", deletedText(listOf(urgentLate)))
        assertEquals("할 일 2개 삭제되었어요", deletedText(listOf(urgentLate, normalSoon)))
    }
}
