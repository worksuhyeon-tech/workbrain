package com.workbrain.app.decide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.ui.GlassIcon
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.dashedPill

/*
 * 비서와 결정(목업 workbrain-late-decide-android.html)의 공용 조각: 비서 얼굴·말풍선·알약 버튼·한 줄 입력·AI 제안 표시.
 */

/** 비서 얼굴: 보라 글래스 원 안 체크 목록(목업 .av) */
@Composable
fun SecretaryAvatar(size: Dp = 32.dp) {
    GlassIcon(WbIcons.Checklist, GlassTint.Purple, size = size)
}

/** 비서 말풍선(목업 .bub): 왼쪽 얼굴 + 옅은 파랑 상자, 맨 위 '비서' */
@Composable
fun SecretaryBubble(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        SecretaryAvatar()
        Column(
            Modifier.weight(1f)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                .background(Wb.colors.primary.copy(alpha = 0.06f))
                .border(1.dp, Wb.colors.primary.copy(alpha = 0.16f), RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("비서", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Wb.colors.primary)
            content()
        }
    }
}

@Composable
fun BubbleText(text: String, strong: Boolean = false, muted: Boolean = false) {
    Text(
        text, fontSize = if (muted) 13.sp else 14.sp, lineHeight = 21.sp,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        color = if (muted) Wb.colors.muted else Wb.colors.ink,
    )
}

/** 'AI 제안' 작은 표시(AI가 만든 글은 확인된 사실과 구분, 메모 v1.2 §5) */
@Composable
fun AiTag() {
    Text(
        "AI 제안", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Wb.colors.info,
        modifier = Modifier.background(Wb.colors.info.copy(alpha = 0.12f), PillShape).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

enum class PillKind { Primary, Ghost, Good, Outline }

/** 목업의 둥근 버튼들: 진한(주), 회색(보조), 초록(끝났어요), 테두리(칩) */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PillKind = PillKind.Primary,
    icon: ImageVector? = null,
    height: Dp = 50.dp,
    enabled: Boolean = true,
    fontSize: Int = 15,
) {
    val (bg, fg, line) = when (kind) {
        PillKind.Primary -> Triple(Wb.colors.pillActive, Wb.colors.onPillActive, Color.Transparent)
        PillKind.Ghost -> Triple(Wb.colors.surface, Wb.colors.ink, Wb.colors.border)
        PillKind.Good -> Triple(Wb.colors.good.copy(alpha = 0.10f), Wb.colors.good, Wb.colors.good.copy(alpha = 0.4f))
        PillKind.Outline -> Triple(Wb.colors.card, Wb.colors.ink, Wb.colors.borderStrong)
    }
    Row(
        modifier.height(height).clip(PillShape).background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .border(1.dp, line, PillShape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp)) }
        Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, color = if (enabled) fg else fg.copy(alpha = 0.6f), maxLines = 1)
    }
}

/** 한 줄 입력(목업 .inpl): 테두리 상자 + 오른쪽 덧붙임(AI 제안 표시 등) */
@Composable
fun LineInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp)).background(Wb.colors.card)
            .then(if (dashed) Modifier.dashedPill(Wb.colors.primary.copy(alpha = 0.5f)) else Modifier.border(1.dp, Wb.colors.borderStrong, RoundedCornerShape(14.dp)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = Wb.colors.muted)
            BasicTextField(
                value, { onChange(it.take(200)) },
                textStyle = TextStyle(fontSize = 15.sp, fontWeight = if (dashed) FontWeight.Bold else FontWeight.Normal, color = Wb.colors.ink),
                cursorBrush = SolidColor(Wb.colors.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}
