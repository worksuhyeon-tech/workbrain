package com.workbrain.app.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.WbIcons
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.dueLabel
import com.workbrain.app.ui.dueTone
import com.workbrain.app.ui.ownerLabel
import com.workbrain.app.ui.ownerTone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/**
 * 할 일 후보 카드(기록 상세·검토함 공용). 체크한 것만 추가된다.
 * onChecked가 null이면 이미 처리된 후보(추가됨·버림)라 상태 칩만 보인다.
 * after는 사용자가 고친 값이 있으면 그것을 덮은 값이다(edited=true면 '고침' 칩).
 * onEdit이 있으면 제목·담당 뱃지·기한 뱃지를 눌러 그 칸만 바로 고친다(Q3, 메모 v1.2 8번).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CandidateCard(
    after: JsonObject,
    rationale: String,
    status: String,
    checked: Boolean,
    onChecked: ((Boolean) -> Unit)?,
    source: String? = null,
    onOpenSource: (() -> Unit)? = null,
    edited: Boolean = false,
    onEdit: ((CandidateField) -> Unit)? = null,
    /** 기존 할 일 변경 제안이면 바뀌기 전 값(DEC-38). after에는 바뀌는 칸만 있다 */
    before: JsonObject? = null,
) {
    val ambiguous = after["due_ambiguous"]?.jsonPrimitive?.booleanOrNull == true
    val due = dueLabel(after.str("due_date"), after.str("due_time"), ambiguous, after.str("due_text"))
    val changes = before?.let { changeLines(it, after) }
    // 누를 수 있는 뱃지는 끝에 ▾를 붙여 고칠 수 있음을 보인다
    @Composable
    fun EditableChip(text: String, tone: Tone, field: CandidateField) {
        if (onEdit == null) WbChip(text, tone)
        else WbChip("$text ▾", tone, Modifier.clip(PillShape).clickable { onEdit(field) })
    }
    WbCard(padding = PaddingValues(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (onChecked != null) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onChecked,
                    colors = CheckboxDefaults.colors(checkedColor = Wb.colors.primary, uncheckedColor = Wb.colors.borderStrong),
                )
            } else {
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val title = (before ?: after).str("title").orEmpty()
                if (onEdit == null) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                } else {
                    Row(Modifier.clickable { onEdit(CandidateField.Title) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink, modifier = Modifier.weight(1f, fill = false))
                        Icon(WbIcons.Pencil, contentDescription = "제목 고치기", tint = Wb.colors.muted, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (changes != null) {
                        WbChip("기존 할 일 변경", Tone.Info)
                    } else {
                        EditableChip(ownerLabel(after.str("owner_kind"), after.str("assignee")), ownerTone(after.str("owner_kind")), CandidateField.Owner)
                        when {
                            due != null -> EditableChip(due, dueTone(ambiguous), CandidateField.Due)
                            // 기한이 없으면 고칠 수 있을 때만 '+ 기한'으로 넣을 자리를 보인다
                            onEdit != null -> WbChip("+ 기한", Tone.Mute, Modifier.clip(PillShape).clickable { onEdit(CandidateField.Due) })
                        }
                    }
                    if (edited) WbChip("고침", Tone.Info)
                    when (status) {
                        "pending" -> Unit
                        "approved" -> WbChip("추가됨", Tone.Good)
                        "rejected" -> WbChip("버림", Tone.Mute)
                        else -> WbChip("확인 필요", Tone.Crit)
                    }
                }
                changes?.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Wb.colors.ink) }
                Text(
                    "“$rationale”",
                    style = MaterialTheme.typography.bodySmall,
                    color = Wb.colors.muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                source?.let { LinkText(it, WbIcons.Document, onOpenSource) }
            }
        }
    }
}

/** 카드 아래 작은 파란 링크(아이콘 + 글자) */
@Composable
private fun LinkText(text: String, icon: ImageVector, onClick: (() -> Unit)?) {
    Row(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick).padding(vertical = 4.dp) else Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Wb.colors.link, modifier = Modifier.size(16.dp))
        Text(
            " $text",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Wb.colors.link,
        )
    }
}

/** 변경 제안의 전후 한 줄씩: "담당: 팔로업 · 박지연 → 팔로업 · 박지훈", "기한: 10/23(금) → 10/27(화)" */
private fun changeLines(before: JsonObject, after: JsonObject): List<String> = buildList {
    if ("owner_kind" in after) {
        add("담당: ${ownerLabel(before.str("owner_kind"), before.str("assignee"))} → ${ownerLabel(after.str("owner_kind"), after.str("assignee"))}")
    }
    if ("due_date" in after) {
        fun label(o: JsonObject) = dueLabel(o.str("due_date"), o.str("due_time"), o["due_ambiguous"]?.jsonPrimitive?.booleanOrNull == true, o.str("due_text")) ?: "없음"
        add("기한: ${label(before)} → ${label(after)}")
    }
}
