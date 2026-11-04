package com.workbrain.app.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.TaskRow
import com.workbrain.app.ui.CardShape
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.dueLabel
import com.workbrain.app.ui.dueTone
import com.workbrain.app.ui.ownerLabel
import com.workbrain.app.ui.ownerTone

/**
 * 할 일 카드(오늘·모든 할 일·프로젝트 상세 공용). 완료한 일은 체크된 채 제목에 취소선을 긋고 카드 전체를 흐리게 한다(DEC-64).
 * place: 속한 프로젝트·반복실행 이름(오늘처럼 여러 곳이 섞인 목록에서만 넘긴다, S02 '프로젝트 표시').
 * onToggle이 null이면 체크를 못 누르고(아카이브 읽기 전용), onOpen이 있으면 카드를 눌러 고치기 서랍을 연다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskCard(t: TaskRow, overdue: Boolean, onToggle: (() -> Unit)?, onOpen: (() -> Unit)? = null, place: String? = null) {
    val modifier = if (onOpen != null) Modifier.clip(CardShape).clickable(onClickLabel = "할 일 고치기", onClick = onOpen) else Modifier
    WbCard(modifier, padding = PaddingValues(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = t.done,
                onCheckedChange = { onToggle?.invoke() },
                enabled = onToggle != null,
                colors = CheckboxDefaults.colors(checkedColor = Wb.colors.muted, uncheckedColor = Wb.colors.borderStrong),
            )
            Column(
                Modifier.weight(1f).padding(top = 10.dp).alpha(if (t.done) 0.55f else 1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    t.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (t.done) Wb.colors.muted else Wb.colors.ink,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WbChip(ownerLabel(t.ownerKind, t.assignee), if (t.done) Tone.Mute else ownerTone(t.ownerKind))
                    dueLabel(t.dueDate, t.dueTime, t.dueAmbiguous, t.dueText)?.let {
                        WbChip(it, if (t.done) Tone.Mute else if (overdue) Tone.Crit else dueTone(t.dueAmbiguous))
                    }
                    place?.let { WbChip(it, Tone.Mute) }
                }
            }
        }
    }
}
