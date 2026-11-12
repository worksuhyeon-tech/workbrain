package com.workbrain.app.tasks

import com.workbrain.app.data.ParaData
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.TaskRow
import com.workbrain.app.data.WorkspaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 하위 할 일(FR-23a, DEC-101) 화면 밖 규칙. 예시는 모두 지어낸 것 */
class SubtaskTest {
    private val today = LocalDate.of(2026, 11, 12)

    private fun task(id: String, parent: String? = null, done: Boolean = false, created: String = "2026-11-0${id.last()}T00:00:00Z") =
        TaskRow(id, "할 일 $id", status = if (done) "done" else "open", version = 1, ownerKind = "me", projectId = "p1", parentId = parent, createdAt = created)

    private val parent = task("t1")
    private val kidA = task("t2", parent = "t1", done = true)
    private val kidB = task("t3", parent = "t1")
    private val other = task("t4")

    private val d = ParaData(
        workspace = WorkspaceRow("w1", "본업"),
        projects = listOf(ProjectRow("p1", "합성 프로젝트", version = 1)),
        areas = emptyList(), resources = emptyList(), links = emptyList(),
        tasks = listOf(parent, kidA, kidB, other),
        sources = emptyMap(),
    )

    @Test
    fun `목록·숫자는 상위만, 하위는 상위 아래에서만`() {
        assertEquals(listOf("t1", "t4"), d.tasksOf(PlaceType.PROJECT, "p1").map { it.id })
        assertEquals(listOf("t1", "t4"), d.liveTasks.map { it.id })
        assertEquals(2, d.openCount(PlaceType.PROJECT, "p1"))
        assertEquals(listOf("t2", "t3"), d.subtasksOf("t1").map { it.id })
    }

    @Test
    fun `지우는 중 - 하위 하나만 빼면 상위·다른 하위는 그대로, 상위를 빼면 그 하위도 빠짐`() {
        val oneSub = d.without(setOf("t3"))
        assertEquals(listOf("t2"), oneSub.subtasksOf("t1").map { it.id })
        assertEquals(listOf("t1", "t4"), oneSub.liveTasks.map { it.id })
        assertEquals(listOf("t4"), d.without(setOf("t1")).tasks.map { it.id })
        assertTrue(d.without(emptySet()) === d)
    }

    @Test
    fun `검색에서 찾아간 하위는 그 상위 줄에 든다`() {
        assertEquals("t1", d.topIdOf("t3"))
        assertEquals("t4", d.topIdOf("t4"))
        assertNull(d.topIdOf("없음"))
    }

    @Test
    fun `하위 개수 칩 - 끝난 수와 전체, 다 끝나면 allDone, 없으면 칩 없음`() {
        assertEquals(SubCount(done = 1, total = 2), subCountOf(d.subtasksOf("t1")))
        assertFalse(SubCount(1, 2).allDone)
        assertTrue(SubCount(2, 2).allDone)
        assertNull(subCountOf(emptyList()))
    }

    @Test
    fun `하위 줄 순서 - 안 끝난 것 먼저, 그 안에서 만든 순서`() {
        assertEquals(listOf("t3", "t2"), subtaskOrder(listOf(kidA, kidB)).map { it.id })
    }

    @Test
    fun `하위 한 줄 입력 - 기한과 @담당을 읽고, 곳·우선순위는 상위를 따라 비운다`() {
        val e = parseSubtask("내일 오류 문구 수정 @박지훈 !높음", today)!!
        assertEquals("오류 문구 수정", e.title)
        assertEquals("2026-11-13", e.dueDate)
        assertEquals("other", e.ownerKind)
        assertEquals("박지훈", e.assignee)
        assertNull(e.priority)
        assertNull(e.projectId)
        assertNull(e.areaId)
        assertNull(parseSubtask("   ", today))
    }

    @Test
    fun `상위를 지우면 알림에 하위 수`() {
        assertEquals("할 일 t1 삭제되었어요 · 하위 2개 포함", deletedText(listOf(parent), subtasks = 2))
        assertEquals("할 일 t4 삭제되었어요", deletedText(listOf(other)))
    }
}
