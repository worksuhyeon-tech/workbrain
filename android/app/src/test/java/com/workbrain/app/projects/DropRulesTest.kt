package com.workbrain.app.projects

import com.workbrain.app.data.AreaRow
import com.workbrain.app.data.LinkedRecord
import com.workbrain.app.data.ParaData
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.RecordBrief
import com.workbrain.app.data.ResourceLinkRow
import com.workbrain.app.data.ResourceRow
import com.workbrain.app.data.TaskRow
import com.workbrain.app.data.WorkspaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 끌어서 옮기기 규칙(DEC-24 4종, DEC-72): 놓을 수 있는 곳만 동작이 나오고, 서버가 거부할 곳(보관된 곳·이미 연결된 곳)은 null.
 * 예시는 보험 보상 가상 데이터
 */
class DropRulesTest {
    private val claim = ProjectRow("p1", "사고접수 개편", version = 3)
    private val erp = ProjectRow("p2", "클레임보상 ERP구축", version = 1)
    private val done = ProjectRow("p3", "지급 일정 정비", version = 2, archivedAt = "2026-10-30T00:00:00Z")
    private val review = AreaRow("a1", "지급심사")
    private val oldArea = AreaRow("a2", "옛 심사", archivedAt = "2026-10-01T00:00:00Z")
    private val guide = ResourceRow("r1", "link", "보상 매뉴얼", version = 1)
    private val oldRes = ResourceRow("r2", "note", "옛 메모", version = 1, archivedAt = "2026-10-01T00:00:00Z")

    private fun task(id: String, projectId: String? = null, areaId: String? = null) =
        TaskRow(id, "할 일 $id", status = "open", version = 1, ownerKind = "me", projectId = projectId, areaId = areaId)

    private val inClaim = task("t1", projectId = claim.id)
    private val inArea = task("t2", areaId = review.id)
    private val unsorted = task("t3")
    private val inArchived = task("t4", projectId = done.id)

    private fun record(id: String, projectId: String) = LinkedRecord(projectId, RecordBrief(id, "회의 $id", "2026-11-05T01:00:00Z", "app-recording", 600))

    private val d = ParaData(
        workspace = WorkspaceRow("w1", "본업"),
        projects = listOf(claim, erp, done),
        areas = listOf(review, oldArea),
        resources = listOf(guide, oldRes),
        links = listOf(ResourceLinkRow(guide.id, "project", claim.id)),
        tasks = listOf(inClaim, inArea, unsorted, inArchived),
        sources = emptyMap(),
        // rec1은 사고접수에만, rec2는 사고접수·ERP 둘 다에 연결
        recordLinks = listOf(record("rec1", claim.id), record("rec2", claim.id), record("rec2", erp.id)),
    )

    private val toArchive = DropKey.Section(ParaSection.ARCHIVE)
    private val toProjects = DropKey.Section(ParaSection.PROJECTS)
    private fun toProject(p: ProjectRow) = DropKey.Node(PlaceType.PROJECT, p.id)
    private fun toArea(a: AreaRow) = DropKey.Node(PlaceType.AREA, a.id)

    private fun drop(item: DragItem, key: DropKey) = dropAction(d, item, key)

    // ── 프로젝트: 보관·복원 ──

    @Test fun `진행 중 프로젝트를 4 아카이브 칸에 놓으면 보관`() {
        val a = drop(DragItem.Project(claim.id, claim.name), toArchive)
        assertEquals(DropAction.Archive(claim), a)
    }

    @Test fun `보관된 프로젝트를 1 프로젝트 칸에 놓으면 복원`() {
        assertEquals(DropAction.Restore(done), drop(DragItem.Project(done.id, done.name), toProjects))
    }

    @Test fun `이미 그 칸에 있으면 아무 일도 없다`() {
        assertNull(drop(DragItem.Project(claim.id, claim.name), toProjects))
        assertNull(drop(DragItem.Project(done.id, done.name), toArchive))
    }

    @Test fun `프로젝트를 다른 줄이나 2 3칸 머리에 놓을 수 없다`() {
        val item = DragItem.Project(claim.id, claim.name)
        assertNull(drop(item, toProject(erp)))
        assertNull(drop(item, toArea(review)))
        assertNull(drop(item, DropKey.Section(ParaSection.AREAS)))
        assertNull(drop(item, DropKey.Section(ParaSection.RESOURCES)))
    }

    // ── 할 일: 소속 변경 ──

    @Test fun `할 일을 다른 프로젝트나 반복실행에 놓으면 옮긴다`() {
        val toErp = drop(DragItem.Task(inClaim.id, inClaim.title), toProject(erp)) as DropAction.MoveTask
        assertEquals("클레임보상 ERP구축", toErp.to.name)
        assertEquals(inClaim, toErp.task)
        val toReview = drop(DragItem.Task(inClaim.id, inClaim.title), toArea(review)) as DropAction.MoveTask
        assertEquals(PlaceType.AREA, toReview.to.type)
        val fromArea = drop(DragItem.Task(inArea.id, inArea.title), toProject(claim))
        assertTrue(fromArea is DropAction.MoveTask)
    }

    @Test fun `정리 안 됨 할 일도 프로젝트로 옮길 수 있다`() {
        assertTrue(drop(DragItem.Task(unsorted.id, unsorted.title), toProject(claim)) is DropAction.MoveTask)
    }

    @Test fun `지금 있는 곳 보관된 곳 칸 머리에는 할 일을 놓을 수 없다`() {
        val item = DragItem.Task(inClaim.id, inClaim.title)
        assertNull(drop(item, toProject(claim)))
        assertNull(drop(item, toProject(done)))
        assertNull(drop(item, toArea(oldArea)))
        assertNull(drop(item, toArchive))
        assertNull(drop(DragItem.Task(inArea.id, inArea.title), toArea(review)))
    }

    @Test fun `보관된 곳의 할 일은 읽기 전용이라 옮기지 않는다`() {
        assertNull(drop(DragItem.Task(inArchived.id, inArchived.title), toProject(claim)))
    }

    @Test fun `다른 기기에서 지운 할 일은 놓아도 아무 일 없다`() {
        assertNull(drop(DragItem.Task("gone", "지운 할 일"), toProject(erp)))
    }

    @Test fun `옮기기는 최신 행을 쓴다(버전 확인용)`() {
        val a = drop(DragItem.Task(inClaim.id, "끌 때 제목"), toProject(erp)) as DropAction.MoveTask
        assertEquals(inClaim.version, a.task.version)
    }

    // ── 기록: 다른 프로젝트로 재배정(프로젝트만, DEC-69) ──

    @Test fun `기록을 다른 프로젝트에 놓으면 연결을 옮긴다`() {
        val a = drop(DragItem.Record("rec1", claim.id, "회의 rec1"), toProject(erp))
        assertEquals(DropAction.MoveRecord("rec1", "회의 rec1", claim.id, d.places.first { it.id == erp.id }), a)
    }

    @Test fun `기록은 반복실행 같은 프로젝트 보관된 프로젝트에 놓을 수 없다`() {
        val item = DragItem.Record("rec1", claim.id, "회의 rec1")
        assertNull(drop(item, toArea(review)))
        assertNull(drop(item, toProject(claim)))
        assertNull(drop(item, toProject(done)))
        assertNull(drop(item, toArchive))
    }

    @Test fun `이미 연결된 프로젝트에 놓으면 옛 연결만 풀리므로 막는다`() {
        assertNull(drop(DragItem.Record("rec2", claim.id, "회의 rec2"), toProject(erp)))
    }

    @Test fun `그 사이 연결이 풀린 기록은 놓아도 아무 일 없다`() {
        assertNull(drop(DragItem.Record("rec1", erp.id, "회의 rec1"), toProject(claim)))
    }

    // ── 자료: 연결 추가 ──

    @Test fun `자료를 아직 안 이은 프로젝트나 반복실행에 놓으면 연결한다`() {
        val toErp = drop(DragItem.Resource(guide.id, guide.title), toProject(erp)) as DropAction.LinkResource
        assertEquals(guide, toErp.resource)
        assertEquals("클레임보상 ERP구축", toErp.to.name)
        assertTrue(drop(DragItem.Resource(guide.id, guide.title), toArea(review)) is DropAction.LinkResource)
    }

    // ── 놓을 수 없을 때 알릴 이유(DEC-72 ③, 휴대폰엔 '놓기 불가' 커서가 없음) ──

    @Test fun `이미 연결된 곳에 자료를 놓으면 그 이유를 알린다(11월 8일 사용자 제보)`() {
        assertEquals("이미 ‘사고접수 개편’에 연결된 자료예요", blockReason(d, DragItem.Resource(guide.id, guide.title), toProject(claim)))
    }

    @Test fun `놓을 수 있는 곳이면 이유가 없다`() {
        assertNull(blockReason(d, DragItem.Resource(guide.id, guide.title), toProject(erp)))
        assertNull(blockReason(d, DragItem.Project(claim.id, claim.name), toArchive))
    }

    @Test fun `할 일 기록의 놓기 불가 이유`() {
        assertEquals("이미 ‘사고접수 개편’에 있는 할 일이에요", blockReason(d, DragItem.Task(inClaim.id, inClaim.title), toProject(claim)))
        assertEquals("할 일은 프로젝트·반복실행 이름 위에 놓아요", blockReason(d, DragItem.Task(inClaim.id, inClaim.title), toArchive))
        assertEquals("기록은 프로젝트에만 연결해요", blockReason(d, DragItem.Record("rec1", claim.id, "회의 rec1"), toArea(review)))
        assertEquals("이미 ‘클레임보상 ERP구축’에 연결된 기록이에요", blockReason(d, DragItem.Record("rec2", claim.id, "회의 rec2"), toProject(erp)))
    }

    @Test fun `프로젝트의 놓기 불가 이유와 자기 줄 위에서 뗀 경우`() {
        assertEquals("이미 1. 프로젝트에 있어요 · 보관하려면 4. 아카이브에 놓아요", blockReason(d, DragItem.Project(claim.id, claim.name), toProjects))
        assertEquals("이미 아카이브에 있어요 · 복원하려면 1. 프로젝트에 놓아요", blockReason(d, DragItem.Project(done.id, done.name), toArchive))
        assertTrue(blockReason(d, DragItem.Project(claim.id, claim.name), toProject(erp))!!.startsWith("프로젝트 안에는"))
        assertNull(blockReason(d, DragItem.Project(claim.id, claim.name), toProject(claim)))
    }

    @Test fun `이미 이은 곳 보관된 곳 보관된 자료는 연결하지 않는다`() {
        assertNull(drop(DragItem.Resource(guide.id, guide.title), toProject(claim)))
        assertNull(drop(DragItem.Resource(guide.id, guide.title), toProject(done)))
        assertNull(drop(DragItem.Resource(oldRes.id, oldRes.title), toProject(erp)))
        assertNull(drop(DragItem.Resource(guide.id, guide.title), toArchive))
    }
}
