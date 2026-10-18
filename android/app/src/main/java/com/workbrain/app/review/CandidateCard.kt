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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.workbrain.app.ui.Tone
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
) {
    val ambiguous = after["due_ambiguous"]?.jsonPrimitive?.booleanOrNull == true
    val due = dueLabel(after.str("due_date"), after.str("due_time"), ambiguous, after.str("due_text"))
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
                Text(after.str("title").orEmpty(), style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WbChip(ownerLabel(after.str("owner_kind"), after.str("assignee")), ownerTone(after.str("owner_kind")))
                    due?.let { WbChip(it, dueTone(ambiguous)) }
                    when (status) {
                        "pending" -> Unit
                        "approved" -> WbChip("추가됨", Tone.Good)
                        "rejected" -> WbChip("버림", Tone.Mute)
                        else -> WbChip("확인 필요", Tone.Crit)
                    }
                }
                Text(
                    "“$rationale”",
                    style = MaterialTheme.typography.bodySmall,
                    color = Wb.colors.muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                source?.let {
                    Text(
                        "📄 $it",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Wb.colors.link,
                        modifier = if (onOpenSource != null) Modifier.clickable(onClick = onOpenSource) else Modifier,
                    )
                }
            }
        }
    }
}
