package com.workbrain.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 목업 공통 모양(radius·간격)을 한곳에 둔다
val CardShape = RoundedCornerShape(14.dp)      // --r-xl
val ControlShape = RoundedCornerShape(8.dp)    // --r-lg (선택지·업로드 줄)
val PillShape = RoundedCornerShape(200.dp)     // --r-pill

/** 화면 머리: 제목 + 손글씨 한마디(목업 .page-head .hand) */
@Composable
fun PageHeader(title: String, hand: String?, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Wb.colors.ink)
        // 남는 폭은 손글씨 한마디(또는 빈 칸)만 차지한다. 오른쪽 버튼까지 폭을 나눠 가지면 버튼이 화면 가운데에 뜬다(목업은 오른쪽 끝)
        if (hand != null) {
            Text(
                hand,
                fontFamily = HandAccent,
                fontSize = 21.sp,
                color = Wb.colors.muted,
                modifier = Modifier.padding(start = 10.dp, top = 4.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** 카드: 흰 바탕 + 옅은 테두리 + 14dp 모서리(목업 .card) */
@Composable
fun WbCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = Wb.colors.card,
        border = BorderStroke(1.dp, Wb.colors.border),
        shadowElevation = 1.dp,
    ) { Column(Modifier.padding(padding), content = content) }
}

/** 구역 이름: 작은 회색 굵은 글씨(목업 .sec-h) */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = Wb.colors.muted,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

enum class Tone { Good, Warn, Crit, Info, Mute }

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Good -> Wb.colors.good
    Tone.Warn -> Wb.colors.warn
    Tone.Crit -> Wb.colors.crit
    Tone.Info -> Wb.colors.info
    Tone.Mute -> Wb.colors.muted
}

/** 상태 칩: 글자색 + 같은 색 12% 바탕(목업 .chip) */
@Composable
fun WbChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    val base = modifier.background(if (tone == Tone.Mute) Wb.colors.surface else color.copy(alpha = 0.12f), PillShape)
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = (if (tone == Tone.Mute) base.border(1.dp, Wb.colors.border, PillShape) else base)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** 안내 띠: 경고·실패·정보(목업 .banner) */
@Composable
fun Banner(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), ControlShape)
            .border(1.dp, color.copy(alpha = 0.28f), ControlShape)
            .padding(12.dp),
    )
}

/** 비어 있을 때: 손글씨 한마디 + 설명(목업 .empty) */
@Composable
fun EmptyState(hand: String, body: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: GlassTint = GlassTint.Blue) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon?.let { GlassIcon(it, tint, size = 56.dp, modifier = Modifier.padding(bottom = 12.dp)) }
        Text(hand, fontFamily = HandAccent, fontSize = 26.sp, color = Wb.colors.ink, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 주 버튼(파란 바탕) */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        colors = ButtonDefaults.buttonColors(containerColor = Wb.colors.primary, contentColor = Wb.colors.onPrimary),
        modifier = modifier.height(44.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** 보조 버튼(흰 바탕 + 테두리, 목업 .btn) */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        border = BorderStroke(1.dp, Wb.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Wb.colors.card, contentColor = Wb.colors.body),
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = modifier.height(38.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** 목업 .tabs2: 둥근 바탕 안에서 고른 것만 흰 알약. options = (값, 표시 이름) */
@Composable
fun SegmentPills(options: List<Pair<String, String>>, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Row(
        modifier.fillMaxWidth().background(Wb.colors.surface, PillShape).border(1.dp, Wb.colors.border, PillShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (v, label) ->
            val on = v == value
            Surface(
                onClick = { onChange(v) },
                shape = PillShape,
                color = if (on) Wb.colors.card else Color.Transparent,
                shadowElevation = if (on) 1.dp else 0.dp,
                modifier = Modifier.weight(1f).height(36.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (on) Wb.colors.ink else Wb.colors.muted)
                }
            }
        }
    }
}
