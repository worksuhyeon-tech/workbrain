package com.workbrain.app.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.TaskRow
import com.workbrain.app.ui.CardShape
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * 목업 S03(workbrain-project.html)의 모양을 Compose로 옮긴 부품. 이름은 목업 CSS 클래스를 따른다(DEC-66).
 * 목업의 이모지 자리(📁 🔁 🔗 📝 ✅ 🎯 🧭 🗄️)는 선 글리프로 바꿨다(Design §6.5 이모지 금지).
 */

/** 트리·머리에 쓰는 종류별 글리프와 색(목업 이모지 색과 비슷하게) */
object ParaGlyph {
    val allTasks = WbIcons.Review
    fun project() = WbIcons.Folder
    fun area() = WbIcons.Repeat
    fun resource(kind: String) = if (kind == "link") WbIcons.Link else WbIcons.Document
    fun place(type: PlaceType?) = when (type) { PlaceType.PROJECT -> project(); PlaceType.AREA -> area(); null -> allTasks }

    @Composable fun tint(type: PlaceType?): Color = when (type) {
        PlaceType.PROJECT -> GlassTint.Orange.deep
        PlaceType.AREA -> GlassTint.Blue.deep
        null -> GlassTint.Green.deep
    }
    @Composable fun resourceTint(): Color = Wb.colors.muted
}

/** 목업 .card: 흰 바탕 + 옅은 테두리 + 14dp 모서리. 안쪽 여백은 쓰는 곳이 정한다 */
@Composable
fun MockCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = Wb.colors.card,
        border = BorderStroke(1.dp, Wb.colors.border),
        shadowElevation = 1.dp,
    ) { Column(content = content) }
}

/** 목업 .crumb: 상세 위 경로(본업 › 1. 프로젝트) */
@Composable
fun Crumb(text: String) {
    Text(text, fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.padding(bottom = 4.dp))
}

/** 목업 .sec-h: 작은 회색 굵은 칸 이름 + 오른쪽 끝 무엇(개수·링크) */
@Composable
fun SecH(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = Wb.colors.muted)
        trailing()
    }
}

/** 목업 .note: 작은 회색 안내 */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = Wb.colors.muted, modifier = modifier)
}

/** 목업 .empty: 굵은 한 줄 + 설명(카드 안 빈 상태) */
@Composable
fun EmptyNote(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, textAlign = TextAlign.Center)
        Text(body, fontSize = 14.sp, color = Wb.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 목업 .link-chip: 다른 항목으로 가는 둥근 단추(아이콘 + 이름) */
@Composable
fun LinkChip(icon: ImageVector, text: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(PillShape).background(Wb.colors.surface).border(1.dp, Wb.colors.border, PillShape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 목업 .seg 한 칸. count가 있으면 이름 뒤 숫자, laterTag가 있으면 흐린 칸(P4 자리, 누르면 안내만) */
data class SegItem(val key: String, val label: String, val count: Int? = null, val alert: Boolean = false, val laterTag: String? = null)

/** 목업 .seg: 옅은 바탕 안에서 고른 칸만 진한 알약. 칸은 글자 길이만큼, 넘치면 옆으로 밀어 본다 */
@Composable
fun DarkSeg(items: List<SegItem>, value: String, modifier: Modifier = Modifier, onLater: (String) -> Unit = {}, onChange: (String) -> Unit) {
    Row(
        modifier.clip(PillShape).background(Wb.colors.surface).border(1.dp, Wb.colors.border, PillShape)
            .horizontalScroll(rememberScrollState()).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEach { item ->
            val on = item.key == value
            val later = item.laterTag != null
            Row(
                Modifier.height(32.dp).clip(PillShape).background(if (on) Wb.colors.pillActive else Color.Transparent)
                    .clickable { if (later) onLater(item.key) else onChange(item.key) }
                    .padding(horizontal = 12.dp).alpha(if (later) 0.75f else 1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Wb.colors.onPillActive else Wb.colors.muted, maxLines = 1)
                item.count?.let { c ->
                    Text(
                        "$c", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = when { on -> Wb.colors.onPillActive; item.alert && c > 0 -> Wb.colors.crit; else -> Wb.colors.muted },
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                item.laterTag?.let { tag -> Text(tag, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted, modifier = Modifier.padding(start = 4.dp)) }
            }
        }
    }
}

/** 목업 .btn(흰 바탕 테두리) — 상세 머리의 [수정][아카이브로] */
@Composable
fun SmallButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (primary) Wb.colors.onPrimary else Wb.colors.body,
        modifier = Modifier.heightIn(min = 36.dp).clip(shape)
            .background(if (primary) Wb.colors.primary else Wb.colors.card)
            .border(1.dp, if (primary) Wb.colors.primary else Wb.colors.borderStrong, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ── 할 일 한 줄(목업 .trow) ──

private fun priorityLabel(p: Int?) = when (p) { 1 -> "높음"; 3 -> "낮음"; else -> "보통" }

/** 목업 기한 표시: 오늘 / M/d / M/d HH:mm / '다음 주 · 확인 필요' */
private fun rowDue(t: TaskRow, today: LocalDate): String? {
    t.dueTime?.let { iso ->
        val z = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(SEOUL) }.getOrNull() ?: return null
        val d = z.toLocalDate()
        return (if (d == today) "오늘" else "${d.monthValue}/${d.dayOfMonth}") + " %02d:%02d".format(z.hour, z.minute)
    }
    t.dueDate?.let { s ->
        val d = runCatching { LocalDate.parse(s) }.getOrNull() ?: return s
        return if (d == today) "오늘" else "${d.monthValue}/${d.dayOfMonth}"
    }
    return if (t.dueAmbiguous) "${t.dueText ?: "모호"} · 확인 필요" else null
}

/** 기한 날짜(시각 포함 기한은 서울 날짜) */
fun dueDay(t: TaskRow): LocalDate? = t.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    ?: t.dueTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).toLocalDate() }.getOrNull() }

/**
 * 할 일 한 줄: 체크 · 제목(+팔로업/담당 확인) · 출처 · 우선순위 · 기한.
 * 체크는 완료 ↔ 다시 열기. onOpen이 있으면 줄을 눌러 수정 서랍. readOnly(아카이브)면 둘 다 막는다.
 * showPriority: 좁은 화면에서는 목업처럼 우선순위 칸을 숨긴다(목업 max-width 700)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskLine(t: TaskRow, source: String?, showPriority: Boolean, readOnly: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val today = LocalDate.now(SEOUL)
    val late = !t.done && dueDay(t)?.isBefore(today) == true
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
            Text(
                source ?: "직접 추가", fontSize = 12.sp, color = Wb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (showPriority) {
            Text(
                priorityLabel(t.priority), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                color = if (t.priority == 1) Wb.colors.crit else Wb.colors.muted,
                modifier = Modifier.width(44.dp),
            )
        }
        rowDue(t, today)?.let {
            WbChip(it, when { late -> Tone.Crit; t.dueAmbiguous && t.dueDate == null -> Tone.Warn; else -> Tone.Mute }, Modifier.padding(start = 6.dp))
        }
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
