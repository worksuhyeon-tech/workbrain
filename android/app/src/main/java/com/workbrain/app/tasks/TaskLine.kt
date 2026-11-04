package com.workbrain.app.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Place
import com.workbrain.app.data.TaskRow
import com.workbrain.app.ui.ParaGlyph
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * 할 일 한 줄(목업 .trow): 오늘(S02)·프로젝트(S03)·모든 할 일(S07)이 같이 쓴다.
 */

private fun priorityLabel(p: Int?) = when (p) { 1 -> "높음"; 3 -> "낮음"; else -> "보통" }

/** 기한 날짜(시각 포함 기한은 서울 날짜). 기한 없음·모호면 null */
fun dueDay(t: TaskRow): LocalDate? = t.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    ?: t.dueTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).toLocalDate() }.getOrNull() }

/** 지났는데 안 끝남 */
fun isLate(t: TaskRow, today: LocalDate = LocalDate.now(SEOUL)): Boolean = !t.done && dueDay(t)?.isBefore(today) == true

/** 목업 기한 칩 글자: 기한 없음 / '다음 주 · 확인 필요' / (지연 · )오늘·M/d( HH:mm) */
private fun rowDue(t: TaskRow, today: LocalDate): String {
    if (t.dueAmbiguous && t.dueDate == null && t.dueTime == null) return "${t.dueText ?: "모호"} · 확인 필요"
    val day = dueDay(t) ?: return "기한 없음"
    val label = if (day == today) "오늘" else "${day.monthValue}/${day.dayOfMonth}"
    val time = t.dueTime?.let { iso ->
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(SEOUL) }.getOrNull()?.let { " %02d:%02d".format(it.hour, it.minute) }
    }.orEmpty()
    return (if (isLate(t, today)) "지연 · " else "") + label + time
}

/**
 * 할 일 한 줄: 체크 · 제목(+팔로업/담당 확인) · (속한 곳) 출처 · 우선순위 · 기한.
 * 체크는 완료 ↔ 다시 열기. 줄을 누르면 수정 서랍. readOnly(아카이브)면 둘 다 막는다.
 * place: 여러 곳이 섞인 목록(오늘)에서만 넘긴다. onPlace가 있으면 눌러서 그 프로젝트로 간다(목업 .place).
 * showPriority: 좁은 칸에서는 목업처럼 우선순위 칸을 숨긴다(목업 max-width 700)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskLine(
    t: TaskRow,
    source: String?,
    showPriority: Boolean,
    readOnly: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    place: Place? = null,
    onPlace: ((Place) -> Unit)? = null,
) {
    val today = LocalDate.now(SEOUL)
    val late = isLate(t, today)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !readOnly, onClickLabel = "할 일 수정", onClick = onOpen)
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = t.done,
            onCheckedChange = { onToggle() },
            enabled = !readOnly,
            colors = CheckboxDefaults.colors(checkedColor = Wb.colors.primary, uncheckedColor = Wb.colors.borderStrong),
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    t.title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = if (t.done) Wb.colors.muted else Wb.colors.ink,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
                when (t.ownerKind) {
                    "other" -> OwnChip("${t.assignee.orEmpty()} · 팔로업", Wb.colors.info, WbIcons.Person)
                    "unknown" -> OwnChip("담당 확인", Wb.colors.warn, null)
                }
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                place?.let { p ->
                    Row(
                        Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = onPlace != null) { onPlace?.invoke(p) }.padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(ParaGlyph.place(p.type), contentDescription = null, tint = ParaGlyph.tint(p.type), modifier = Modifier.size(12.dp))
                        Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 3.dp))
                    }
                }
                Text(
                    source ?: "직접 추가", fontSize = 12.sp, color = Wb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
            }
        }
        if (showPriority) {
            Text(
                priorityLabel(t.priority), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                color = if (t.priority == 1) Wb.colors.crit else Wb.colors.muted,
                modifier = Modifier.width(44.dp),
            )
        }
        WbChip(
            rowDue(t, today),
            when { late -> Tone.Crit; t.dueAmbiguous && dueDay(t) == null -> Tone.Warn; else -> Tone.Mute },
            Modifier.padding(start = 6.dp),
        )
    }
}

/** 목업 .own: 제목 옆 작은 담당 표시 */
@Composable
private fun OwnChip(text: String, color: Color, icon: ImageVector?) {
    Row(
        Modifier.background(color.copy(alpha = 0.12f), PillShape).padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(11.dp).padding(end = 2.dp)) }
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}
