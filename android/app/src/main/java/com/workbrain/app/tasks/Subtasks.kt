package com.workbrain.app.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.TaskRow
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import java.time.LocalDate

/*
 * 하위 할 일(FR-23a, DEC-101, Design §3.15): 할 일 아래 한 단계. 상위 줄의 '⤷ 1/3' 칩을 누르면 바로 아래에 펼쳐지고,
 * 펼친 끝에서 한 줄로 더 넣는다. 곳(프로젝트·반복실행)과 우선순위는 상위를 따르고(서버 트리거), 하위는 제목·완료·기한·담당만.
 * 프로젝트 상세·모든 할 일·오늘 탭·할 일 수정 서랍이 같이 쓴다
 */

/** 하위 할 일 개수: '⤷ done/total', 다 끝나면 '하위 다 끝남'(상위는 사용자가 직접 끝낸다, ④) */
data class SubCount(val done: Int, val total: Int) {
    val allDone: Boolean get() = total > 0 && done == total
}

fun subCountOf(children: List<TaskRow>): SubCount? =
    children.takeIf { it.isNotEmpty() }?.let { SubCount(it.count(TaskRow::done), it.size) }

/** 하위 줄 순서: 안 끝난 것 먼저(만든 순서), 끝난 것은 뒤 */
fun subtaskOrder(children: List<TaskRow>): List<TaskRow> = children.sortedWith(compareBy<TaskRow> { it.done }.thenBy { it.createdAt.orEmpty() })

/**
 * 하위 할 일 한 줄 입력 해석: 빠른 입력과 같은 규칙으로 기한·@담당만 읽는다.
 * 곳('#')·우선순위('!')는 상위를 따르므로 쓰지 않는다. 제목이 비면 null
 */
fun parseSubtask(text: String, today: LocalDate): TaskEdit? {
    val q = QuickParse.parse(text, today, places = emptyList(), defaultPlace = null)
    if (q.title.isBlank()) return null
    return q.toEdit().copy(priority = null, projectId = null, areaId = null)
}

/** 펼친 상위 할 일 ID. 화면에 있는 동안·접기·펼치기·회전에도 남는다 */
@Stable
class OpenSubtasks(ids: Set<String>) {
    var ids by mutableStateOf(ids)
        private set

    operator fun contains(id: String): Boolean = id in ids
    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun open(id: String) { if (id !in ids) ids = ids + id }

    companion object {
        val Saver = listSaver<OpenSubtasks, String>(save = { it.ids.toList() }, restore = { OpenSubtasks(it.toSet()) })
    }
}

@Composable
fun rememberOpenSubtasks(): OpenSubtasks = rememberSaveable(saver = OpenSubtasks.Saver) { OpenSubtasks(emptySet()) }

/** 상위 줄 제목 옆 '⤷ 1/3'(다 끝나면 초록 '하위 다 끝남'). 누르면 펼침·접힘 */
@Composable
fun SubtaskChip(count: SubCount, open: Boolean, onClick: (() -> Unit)?) {
    val color = if (count.allDone) Wb.colors.good else Wb.colors.muted
    Row(
        Modifier.clip(PillShape).background(color.copy(alpha = 0.12f), PillShape)
            .clickable(enabled = onClick != null, onClickLabel = if (open) "하위 할 일 접기" else "하위 할 일 펼치기") { onClick?.invoke() }
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count.allDone) "하위 다 끝남 ${count.total}" else "⤷ ${count.done}/${count.total}",
            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1,
        )
        Icon(
            if (open) WbIcons.ChevronDown else WbIcons.ChevronRight, contentDescription = null, tint = color,
            modifier = Modifier.padding(start = 2.dp).size(12.dp),
        )
    }
}

/**
 * 상위 줄 아래 펼친 하위 줄들 + '+ 하위 할 일' 한 줄 입력(onAdd가 null이면 입력 없음 — 보관된 곳·고르는 중).
 * 하위 줄을 누르면 그 하위의 수정 서랍, 체크는 완료↔다시 열기. focusId: 검색에서 찾아간 하위면 깜빡인다
 */
@Composable
fun SubtaskRows(
    children: List<TaskRow>,
    readOnly: Boolean,
    onToggle: (TaskRow) -> Unit,
    onOpen: (TaskRow) -> Unit,
    onAdd: ((String) -> Unit)?,
    focusId: String? = null,
) {
    val flash = Wb.colors.highlight
    Column(Modifier.fillMaxWidth().background(Wb.colors.surface.copy(alpha = 0.6f))) {
        subtaskOrder(children).forEach { c ->
            HorizontalDivider(color = Wb.colors.border.copy(alpha = 0.6f), modifier = Modifier.padding(start = 44.dp))
            TaskLine(
                c, source = null, readOnly = readOnly,
                onToggle = { onToggle(c) }, onOpen = { onOpen(c) }, compact = true,
                modifier = Modifier.focusFlash(c.id, c.id == focusId, flash),
            )
        }
        if (onAdd != null) {
            HorizontalDivider(color = Wb.colors.border.copy(alpha = 0.6f), modifier = Modifier.padding(start = 44.dp))
            SubtaskInput(onAdd, Modifier.padding(start = 44.dp, end = 12.dp, top = 6.dp, bottom = 8.dp))
        }
    }
}

/** '+ 하위 할 일' 한 줄 입력: 적고 [추가](또는 키보드 완료). 빠른 입력처럼 '내일'·'@이름'을 알아본다 */
@Composable
fun SubtaskInput(onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val add = { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(200) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = Wb.colors.ink),
            cursorBrush = SolidColor(Wb.colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).height(38.dp),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxSize().background(Wb.colors.card, ControlShape).border(1.dp, Wb.colors.border, ControlShape).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text("+ 하위 할 일 (예: 내일 오류 문구 수정)", fontSize = 14.sp, color = Wb.colors.muted, maxLines = 1)
                    inner()
                }
            },
        )
        TextButton(onClick = add, enabled = text.isNotBlank()) { Text("추가", color = if (text.isNotBlank()) Wb.colors.link else Wb.colors.muted) }
    }
}
