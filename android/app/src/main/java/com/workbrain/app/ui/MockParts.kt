package com.workbrain.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.PlaceType

/*
 * 웹 목업(S02 dashboard·S03 project)의 모양을 Compose로 옮긴 공용 부품. 이름은 목업 CSS 클래스를 따른다(DEC-66·67).
 * 목업의 이모지 자리(📁 🔁 🔗 📝 ✅ 🎯 🧭 🗄️ 🎙️)는 선 글리프로 바꿨다(Design §6.5 이모지 금지).
 */

/** 종류별 글리프와 색: 프로젝트 베이지 폴더(DEC-103, 전에는 주황) · 반복실행 파랑 · 모든 할 일 초록 */
object ParaGlyph {
    val allTasks = WbIcons.Review
    fun project() = WbIcons.Folder
    fun area() = WbIcons.Repeat
    /** 링크 · 메모 · 기록 참조(목업 🎙️, DEC-71) */
    fun resource(kind: String) = when (kind) { "link" -> WbIcons.Link; "record-ref" -> WbIcons.Mic; else -> WbIcons.Document }
    fun place(type: PlaceType?) = when (type) { PlaceType.PROJECT -> project(); PlaceType.AREA -> area(); null -> allTasks }

    @Composable fun tint(type: PlaceType?): Color = when (type) {
        PlaceType.PROJECT -> Wb.colors.folder
        PlaceType.AREA -> GlassTint.Blue.deep
        null -> GlassTint.Green.deep
    }
    @Composable fun resourceTint(): Color = Wb.colors.muted
}

/** 점선 테두리 알약(목업 .att-add 참석자 추가, AI 연결 제안 칩 FR-06): 아직 확정되지 않은 것 */
fun Modifier.dashedPill(color: Color): Modifier = drawBehind {
    val stroke = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    val r = size.height / 2
    drawRoundRect(color = color, cornerRadius = CornerRadius(r, r), style = stroke)
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

/**
 * 목업 .seg: 옅은 바탕 안에서 고른 칸만 진한 알약. 칸은 글자 길이만큼, 넘치면 옆으로 밀어 본다.
 * compact는 좁은 칸에서 두 묶음을 한 줄에 나란히 둘 때 칸 좌우 여백을 줄인다(숫자가 두 자리여도 안 넘치게)
 */
@Composable
fun DarkSeg(
    items: List<SegItem>,
    value: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onLater: (String) -> Unit = {},
    onChange: (String) -> Unit,
) {
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
                    .padding(horizontal = if (compact) 8.dp else 12.dp).alpha(if (later) 0.75f else 1f),
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

/** 목업 .btn(흰 바탕 테두리) / .btn.primary(파랑) — 상세 머리·카드 안의 작은 버튼. enabled = false면 흐리게 하고 누르지 못하게 */
@Composable
fun SmallButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (primary) Wb.colors.onPrimary else Wb.colors.body,
        modifier = Modifier.heightIn(min = 36.dp).alpha(if (enabled) 1f else 0.5f).clip(shape)
            .background(if (primary) Wb.colors.primary else Wb.colors.card)
            .border(1.dp, if (primary) Wb.colors.primary else Wb.colors.borderStrong, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
