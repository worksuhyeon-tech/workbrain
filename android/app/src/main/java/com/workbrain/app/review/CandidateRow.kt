package com.workbrain.app.review

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.dueLabel
import com.workbrain.app.ui.dueTone
import com.workbrain.app.ui.ownerLabel
import com.workbrain.app.ui.ownerTone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 기록 상세의 할 일 후보 한 줄(목업 S05 할 일 후보 표, DEC-68): 체크 · 제목(그 자리에서 고침) · 담당 ▾ · 기한 ▾ · 근거 한 줄.
 * 체크하면서 바로 고칠 수 있게 카드 대신 표 줄로 둔다(사용자 요청 11/05). 넓은 칸(600dp 이상)은 목업처럼 담당·기한이 제목 오른쪽 칸,
 * 좁으면 제목 아래 줄. 근거는 회색 한 줄이고 누르면 펼친다(사용자 선택).
 * onChecked가 null이면 이미 처리된 후보(추가됨·버림)라 고칠 수 없고 상태 칩만 보인다.
 * 고친 값은 승인 전까지 화면에만 있고, 승인 때 AI 값 위에 덮는다(approve_proposal p_edits, FR-10).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CandidateRow(
    after: JsonObject,
    rationale: String,
    status: String,
    checked: Boolean,
    onChecked: ((Boolean) -> Unit)?,
    /** 담당 고르기에 바로 보일 사람(기록 참석자 등, 내 이름 제외) */
    followNames: List<String>,
    onTitle: (String) -> Unit,
    onOwner: (kind: String, assignee: String?) -> Unit,
    /** 목록에 없는 사람을 적을 때: 담당 고치기 창 */
    onOwnerOther: () -> Unit,
    onDue: () -> Unit,
    badges: List<Pair<String, Tone>> = emptyList(),
) {
    val editable = onChecked != null
    val title = after.str("title").orEmpty()
    val ownerKind = after.str("owner_kind")
    val assignee = after.str("assignee")
    val ambiguous = after["due_ambiguous"]?.jsonPrimitive?.booleanOrNull == true
    val due = dueLabel(after.str("due_date"), after.str("due_time"), ambiguous, after.str("due_text"))

    val ownerChip: @Composable () -> Unit = {
        OwnerPicker(ownerLabel(ownerKind, assignee), ownerTone(ownerKind), editable, followNames, onOwner, onOwnerOther)
    }
    val dueChip: @Composable () -> Unit = {
        when {
            !editable -> due?.let { WbChip(it, dueTone(ambiguous)) }
            due != null -> WbChip("$due ▾", dueTone(ambiguous), Modifier.clip(PillShape).clickable(onClickLabel = "기한 고르기", onClick = onDue))
            else -> WbChip("+ 기한", Tone.Mute, Modifier.clip(PillShape).clickable(onClickLabel = "기한 넣기", onClick = onDue))
        }
    }
    val statusChips: @Composable () -> Unit = {
        when (status) {
            "pending" -> Unit
            "approved" -> WbChip("추가됨", Tone.Good)
            "rejected" -> WbChip("버림", Tone.Mute)
            else -> WbChip("확인 필요", Tone.Crit)
        }
        badges.forEach { (text, tone) -> WbChip(text, tone) }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Column {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                if (editable) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = onChecked,
                        colors = CheckboxDefaults.colors(checkedColor = Wb.colors.primary, uncheckedColor = Wb.colors.borderStrong),
                    )
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TitleCell(title, editable, onTitle, Modifier.weight(1f))
                            Box(Modifier.width(160.dp).padding(start = 10.dp)) { ownerChip() }
                            Box(Modifier.width(120.dp).padding(start = 6.dp)) { dueChip() }
                        }
                        if (status != "pending" || badges.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { statusChips() }
                        }
                    } else {
                        TitleCell(title, editable, onTitle, Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ownerChip()
                            dueChip()
                            statusChips()
                        }
                    }
                    if (rationale.isNotBlank()) RationaleLine(rationale)
                }
            }
            HorizontalDivider(color = Wb.colors.border)
        }
    }
}

/** 제목: 고칠 수 있으면 테두리 있는 입력 칸(목업 .xi), 처리된 후보는 글자만 */
@Composable
private fun TitleCell(title: String, editable: Boolean, onTitle: (String) -> Unit, modifier: Modifier) {
    if (!editable) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Wb.colors.muted, modifier = modifier.padding(vertical = 8.dp))
        return
    }
    BasicTextField(
        value = title,
        onValueChange = { onTitle(it.take(200)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Wb.colors.ink),
        cursorBrush = SolidColor(Wb.colors.primary),
        maxLines = 3,
        modifier = modifier
            .heightIn(min = 40.dp)
            .border(1.dp, if (title.isBlank()) Wb.colors.crit else Wb.colors.border, ControlShape)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}

/**
 * 담당 ▾: 나 · 확인 필요 · 팔로업(참석자) 중에서 바로 고른다(목업 select).
 * 목록에 없는 사람은 '다른 사람…'으로 이름을 적는다
 */
@Composable
private fun OwnerPicker(
    label: String,
    tone: Tone,
    editable: Boolean,
    followNames: List<String>,
    onOwner: (kind: String, assignee: String?) -> Unit,
    onOther: () -> Unit,
) {
    if (!editable) {
        WbChip(label, tone)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        WbChip("$label ▾", tone, Modifier.clip(PillShape).clickable(onClickLabel = "담당 고르기") { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("나") }, onClick = { open = false; onOwner("me", null) })
            DropdownMenuItem(text = { Text("확인 필요") }, onClick = { open = false; onOwner("unknown", null) })
            followNames.forEach { name ->
                DropdownMenuItem(text = { Text("팔로업 · $name") }, onClick = { open = false; onOwner("other", name) })
            }
            DropdownMenuItem(text = { Text("다른 사람…", color = Wb.colors.link) }, onClick = { open = false; onOther() })
        }
    }
}

/** 근거 문장 회색 한 줄, 누르면 전체(사용자 선택 11/05) */
@Composable
private fun RationaleLine(rationale: String) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(ControlShape).clickable(onClickLabel = if (open) "근거 접기" else "근거 펼치기") { open = !open }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "“$rationale”",
            style = MaterialTheme.typography.bodySmall,
            color = Wb.colors.muted,
            maxLines = if (open) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!open) Icon(WbIcons.ChevronDown, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.padding(start = 4.dp).size(14.dp))
    }
}
