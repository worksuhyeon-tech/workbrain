package com.workbrain.app.projects

import com.workbrain.app.data.ParaData
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.ResourceRow
import com.workbrain.app.data.TaskRow

/*
 * 끌어서 옮기기 규칙(DEC-24 4종, DEC-72): 무엇을 어디에 놓으면 무슨 일이 생기는지.
 * 화면과 떼어 놓아 PC 시험(DropRulesTest)으로 확인한다. 서버 규칙(0030·0032)과 같은 것만 허용해,
 * 놓을 수 있다고 강조했는데 서버가 거부하는 일이 없게 한다.
 */

/** 끄는 것. 끄는 동안 데이터가 다시 읽혀도 최신 행을 쓰도록 ID만 들고 있다(label은 따라오는 이름 칩용) */
sealed interface DragItem {
    /** 끄는 줄을 흐리게 할 때 줄과 맞춰 보는 열쇠 */
    val key: String
    val label: String

    data class Project(val id: String, override val label: String) : DragItem {
        override val key get() = projectKey(id)
    }

    /** 프로젝트 아래 기록 줄. 같은 기록이 여러 프로젝트 아래 있을 수 있어 어느 줄에서 끌었는지도 든다 */
    data class Record(val recordId: String, val fromProjectId: String, override val label: String) : DragItem {
        override val key get() = "rec:$recordId@$fromProjectId"
    }

    data class Resource(val id: String, override val label: String) : DragItem {
        override val key get() = resourceKey(id)
    }

    data class Task(val taskId: String, override val label: String) : DragItem {
        override val key get() = "t:$taskId"
    }
}

/** 놓는 곳: 칸 머리(1. 프로젝트 = 복원, 4. 아카이브 = 보관) 또는 프로젝트·반복실행 줄(트리 줄·접은 화면 '옮길 곳' 칩) */
sealed interface DropKey {
    data class Section(val section: ParaSection) : DropKey
    data class Node(val type: PlaceType, val id: String) : DropKey
}

/** 놓았을 때 할 일. 메시지·되돌리기에 쓸 이름까지 담는다 */
sealed interface DropAction {
    data class Archive(val project: ProjectRow) : DropAction
    data class Restore(val project: ProjectRow) : DropAction
    data class MoveTask(val task: TaskRow, val to: Place) : DropAction
    data class MoveRecord(val recordId: String, val title: String, val fromProjectId: String, val to: Place) : DropAction
    data class LinkResource(val resource: ResourceRow, val to: Place) : DropAction
}

/** 놓을 수 없으면 null(강조하지 않고, 놓아도 아무 일 없음 — 목업 dropAction) */
fun dropAction(d: ParaData, item: DragItem, key: DropKey): DropAction? = when (key) {
    is DropKey.Section -> sectionDrop(d, item, key.section)
    // 놓을 수 있는 줄은 보관 안 된 프로젝트·반복실행뿐이다(보관된 곳에 넣기·잇기는 서버가 WB409로 막음)
    is DropKey.Node -> d.places.firstOrNull { it.type == key.type && it.id == key.id }?.let { to -> nodeDrop(d, item, to) }
}

/** 칸 머리에는 프로젝트만: 진행 중 → 4. 아카이브(보관), 보관된 것 → 1. 프로젝트(복원) */
private fun sectionDrop(d: ParaData, item: DragItem, section: ParaSection): DropAction? {
    val p = (item as? DragItem.Project)?.let { d.project(it.id) } ?: return null
    return when {
        section == ParaSection.ARCHIVE && !p.archived -> DropAction.Archive(p)
        section == ParaSection.PROJECTS && p.archived -> DropAction.Restore(p)
        else -> null
    }
}

private fun nodeDrop(d: ParaData, item: DragItem, to: Place): DropAction? = when (item) {
    is DragItem.Task -> d.tasks.firstOrNull { it.id == item.taskId }
        // 보관된 곳의 할 일은 읽기 전용, 지금 있는 곳에 다시 놓는 건 옮기는 게 아니다
        ?.takeIf { !d.inArchived(it) && !it.isIn(to) }
        ?.let { DropAction.MoveTask(it, to) }
    // 기록은 프로젝트에만 잇는다(DEC-69). 이미 이은 프로젝트에 놓으면 서버가 옛 연결만 풀어 버리므로 막는다
    is DragItem.Record -> if (
        to.type == PlaceType.PROJECT && to.id != item.fromProjectId &&
        d.recordsOf(item.fromProjectId).any { it.recordId == item.recordId } &&
        d.recordsOf(to.id).none { it.recordId == item.recordId }
    ) DropAction.MoveRecord(item.recordId, item.label, item.fromProjectId, to) else null
    is DragItem.Resource -> d.resource(item.id)
        ?.takeIf { r -> !r.archived && d.placesOf(r.id).none { it.type == to.type && it.id == to.id } }
        ?.let { DropAction.LinkResource(it, to) }
    // 프로젝트를 다른 프로젝트·반복실행 위에 놓는 동작은 없다(트리 깊이 고정, DEC-24)
    is DragItem.Project -> null
}

private fun TaskRow.isIn(p: Place): Boolean = if (p.type == PlaceType.PROJECT) projectId == p.id else areaId == p.id

/**
 * 놓을 수 없는 곳에서 손을 뗐을 때 화면 아래에 알릴 이유(DEC-72 ③, 11/08 사용자).
 * 휴대폰에는 PC의 '놓기 불가' 커서가 없어, 조용히 넘어가면 고장처럼 보였다(이미 연결된 자료를 같은 프로젝트에 놓음).
 * 놓을 수 있거나, 자기 줄 위에서 뗀 것처럼 알릴 까닭이 없으면 null
 */
fun blockReason(d: ParaData, item: DragItem, key: DropKey): String? {
    if (dropAction(d, item, key) != null) return null
    return when (key) {
        is DropKey.Section -> sectionBlock(d, item, key.section)
        is DropKey.Node -> d.places.firstOrNull { it.type == key.type && it.id == key.id }?.let { nodeBlock(d, item, it) }
    }
}

private fun sectionBlock(d: ParaData, item: DragItem, section: ParaSection): String? = when (item) {
    is DragItem.Project -> when {
        d.project(item.id) == null -> null
        section == ParaSection.PROJECTS -> "이미 1. 프로젝트에 있어요 · 보관하려면 4. 아카이브에 놓아요"
        section == ParaSection.ARCHIVE -> "이미 아카이브에 있어요 · 복원하려면 1. 프로젝트에 놓아요"
        else -> "프로젝트는 1. 프로젝트(복원)나 4. 아카이브(보관)에 놓아요"
    }
    is DragItem.Task -> "할 일은 프로젝트·반복실행 이름 위에 놓아요"
    is DragItem.Record -> "기록은 다른 프로젝트 이름 위에 놓아요"
    is DragItem.Resource -> "자료는 프로젝트·반복실행 이름 위에 놓아 연결해요"
}

private fun nodeBlock(d: ParaData, item: DragItem, to: Place): String? = when (item) {
    is DragItem.Project -> if (item.id == to.id) null else "프로젝트 안에는 프로젝트를 넣을 수 없어요 · 트리는 칸 → 항목까지예요"
    is DragItem.Task -> d.tasks.firstOrNull { it.id == item.taskId }?.takeIf { it.isIn(to) }?.let { "이미 ‘${to.name}’에 있는 할 일이에요" }
    is DragItem.Record -> when {
        to.type == PlaceType.AREA -> "기록은 프로젝트에만 연결해요"
        to.id == item.fromProjectId || d.recordsOf(to.id).any { it.recordId == item.recordId } -> "이미 ‘${to.name}’에 연결된 기록이에요"
        else -> null
    }
    is DragItem.Resource -> if (d.placesOf(item.id).any { it.type == to.type && it.id == to.id }) "이미 ‘${to.name}’에 연결된 자료예요" else null
}
