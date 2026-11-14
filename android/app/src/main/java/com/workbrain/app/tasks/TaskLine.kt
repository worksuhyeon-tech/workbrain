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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
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

/** 기한 날짜(시각 포함 기한은 서울 날짜). 기한 없음·모호면 null */
fun dueDay(t: TaskRow): LocalDate? = t.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    ?: t.dueTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).toLocalDate() }.getOrNull() }

/** 지났는데 안 끝남 */
fun isLate(t: TaskRow, today: LocalDate = LocalDate.now(SEOUL)): Boolean = !t.done && dueDay(t)?.isBefore(today) == true

/** 완료한 순간. 안 끝났거나 완료 시각이 없으면(0029 이전에 끝낸 것) null */
fun doneInstant(t: TaskRow): OffsetDateTime? = t.doneAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

/**
 * 어제 이전에 끝낸 할 일: 상세 할 일 카드에서 '이전 완료'로 접는다(DEC-74). 오늘(서울) 끝낸 것은 '오늘' 탭처럼 목록에 남긴다.
 * 완료 시각이 없는 옛 완료도 언제 끝냈는지 모르니 이전 완료로 본다
 */
fun isEarlierDone(t: TaskRow, today: LocalDate): Boolean {
    if (!t.done) return false
    val doneDay = doneInstant(t)?.atZoneSameInstant(SEOUL)?.toLocalDate() ?: return true
    return doneDay.isBefore(today)
}

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
 * 할 일 한 줄: 체크 · (우선순위 표시) 제목(+팔로업/담당 확인) · (속한 곳) 출처 · 기한.
 * 우선순위(DEC-103): 높음은 제목 앞 주황 별, 보통은 표시 없음, 낮음은 회색 아래 화살표 — 목업의 오른쪽 글자 칸 대신. 하위는 상위를 따라 표시 없음
 * 체크는 완료 ↔ 다시 열기. 줄을 누르면 수정 서랍. readOnly(아카이브)면 둘 다 막는다.
 * place: 여러 곳이 섞인 목록(오늘)에서만 넘긴다. onPlace가 있으면 눌러서 그 프로젝트로 간다(목업 .place).
 * showUnsorted: place가 없을 때 '정리 안 됨'이라고 적는다(모든 할 일의 기한 없음 목록, DEC-78). '오늘'은 지금처럼 비워 둔다
 * onRecipients: 제목 옆 '→ 받는 사람' 칩을 눌렀을 때(DEC-77). 없으면 줄을 누른 것처럼 수정 서랍을 연다
 * selected: ⋮ › 선택 모드(DEC-92)일 때만 값이 있다. 그때는 앞 체크가 '고르기'가 되고 줄 어디를 눌러도 onSelect(완료·수정·칩은 막음)
 * modifier: 프로젝트 화면에서 길게 눌러 끌기를 붙일 때(DEC-72)
 * 하위 할 일(FR-23a): subtasks가 있으면 제목 옆 '⤷ 1/3' 칩(onSubtasks로 펼침·접힘). parentTitle은 오늘 탭의 '상위 › 하위' 줄,
 * compact는 상위 아래 펼친 하위 줄(들여 쓰고 출처·우선순위 칸 없이)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskLine(
    t: TaskRow,
    source: String?,
    readOnly: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    place: Place? = null,
    onPlace: ((Place) -> Unit)? = null,
    showUnsorted: Boolean = false,
    onRecipients: (() -> Unit)? = null,
    selected: Boolean? = null,
    onSelect: () -> Unit = {},
    parentTitle: String? = null,
    subtasks: SubCount? = null,
    subtasksOpen: Boolean = false,
    onSubtasks: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now(SEOUL)
    val late = isLate(t, today)
    val selecting = selected != null
    val mark = priorityMarkOf(t)
    val calendarLinks = CalendarStore.state.collectAsStateWithLifecycle().value.links
    Row(
        modifier.fillMaxWidth()
            .background(if (selected == true) Wb.colors.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(
                enabled = selecting || !readOnly,
                onClickLabel = if (selecting) "선택" else "할 일 수정",
                onClick = if (selecting) onSelect else onOpen,
            )
            .padding(start = if (compact) 32.dp else 4.dp, end = 16.dp, top = if (compact) 0.dp else 4.dp, bottom = if (compact) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected ?: t.done,
            onCheckedChange = { if (selecting) onSelect() else onToggle() },
            enabled = selecting || !readOnly,
            colors = CheckboxDefaults.colors(checkedColor = Wb.colors.primary, uncheckedColor = Wb.colors.borderStrong),
        )
        Column(Modifier.weight(1f).padding(vertical = if (compact) 4.dp else 6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    // 오늘 탭의 하위 할 일은 '상위 › 하위'(상위는 옅게, DEC-101 ⑤)
                    buildAnnotatedString {
                        mark?.let { appendInlineContent(PRIORITY_MARK, it.second) }
                        parentTitle?.let { withStyle(SpanStyle(color = Wb.colors.muted, fontWeight = FontWeight.Normal)) { append("$it › ") } }
                        append(t.title)
                    },
                    inlineContent = mark?.let { (icon, label) ->
                        mapOf(PRIORITY_MARK to priorityMark(icon, label, if (t.priority == 1) Wb.colors.star else Wb.colors.muted))
                    }.orEmpty(),
                    fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Medium,
                    color = if (t.done) Wb.colors.muted else Wb.colors.ink,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
                subtasks?.let { SubtaskChip(it, subtasksOpen, onSubtasks?.takeIf { !selecting }) }
            }
            // 담당 칩(팔로업·담당 확인)과 받는 사람 칩은 늘 제목 다음 줄에 — 제목이 짧으면 옆에 붙고 길면 내려가 들쭉날쭉하던 것(11/13 사용자)
            if (t.ownerKind == "other" || t.ownerKind == "unknown" || t.recipients.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when (t.ownerKind) {
                        "other" -> OwnChip("${t.assignee.orEmpty()} · 팔로업", Wb.colors.info, WbIcons.Person)
                        "unknown" -> OwnChip("담당 확인", Wb.colors.warn, null)
                    }
                    RecipientsChip(t.recipients, if (selecting) onSelect else if (readOnly) null else onRecipients ?: onOpen)
                }
            }
            // 펼친 하위 줄은 출처 줄 없이(상위에 이미 보임)
            if (!compact) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (place != null || showUnsorted) {
                    Row(
                        Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = !selecting && place != null && onPlace != null) { place?.let { onPlace?.invoke(it) } }.padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 트리와 같은 색(11/13 사용자: 할 일 줄 폴더도 프로젝트 화면과 같은 주황으로, DEC-103 베이지를 바꿈)
                        Icon(
                            ParaGlyph.place(place?.type), contentDescription = null,
                            tint = ParaGlyph.tint(place?.type),
                            modifier = Modifier.size(12.dp),
                        )
                        Text(place?.name ?: UNSORTED, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 3.dp))
                    }
                }
                Text(
                    source ?: "직접 추가", fontSize = 12.sp, color = Wb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
            }
        }
        // 구글 캘린더에 넣은 할 일: 기한 칩 앞 작은 캘린더 아이콘(오늘 일정 카드에서는 빼는 대신, 11/14 결정 ②)
        if (t.id in calendarLinks) {
            Icon(WbIcons.Calendar, contentDescription = "캘린더에 넣은 할 일", tint = Wb.colors.info, modifier = Modifier.padding(start = 6.dp).size(14.dp))
        }
        WbChip(
            rowDue(t, today),
            when { late -> Tone.Crit; t.dueAmbiguous && dueDay(t) == null -> Tone.Warn; else -> Tone.Mute },
            Modifier.padding(start = 6.dp),
        )
    }
}

private const val PRIORITY_MARK = "priority"

/** 제목 앞 우선순위 기호와 읽어 줄 말. 보통·하위(상위를 따름)는 null */
private fun priorityMarkOf(t: TaskRow): Pair<ImageVector, String>? = when {
    t.isSubtask -> null
    t.priority == 1 -> WbIcons.Star to "우선순위 높음"
    t.priority == 3 -> WbIcons.PriorityLow to "우선순위 낮음"
    else -> null
}

/** 글자 줄 안에 넣는 기호(제목 줄 가운데에 맞춤) */
private fun priorityMark(icon: ImageVector, label: String, tint: Color) =
    InlineTextContent(Placeholder(17.sp, 15.sp, PlaceholderVerticalAlign.TextCenter)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.padding(end = 2.dp).size(15.dp))
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
