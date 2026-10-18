package com.workbrain.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 글래스 아이콘 색 묶음: 뒤판 그라데이션(밝은 쪽 → 진한 쪽)과 글리프 색. reference.png의 보라·주황·초록·분홍 + 앱 강조 파랑 */
enum class GlassTint(val light: Color, val deep: Color) {
    Purple(Color(0xFFB9A7FF), Color(0xFF6C5CF0)),
    Orange(Color(0xFFFFC27A), Color(0xFFFF7A2E)),
    Green(Color(0xFF78E7C3), Color(0xFF12B37F)),
    Pink(Color(0xFFFF9BB8), Color(0xFFFF3D7F)),
    Blue(Color(0xFF8CC8FF), Color(0xFF1E7FEA)),
    Red(Color(0xFFFFA49A), Color(0xFFE0453A)),
    Gray(Color(0xFFD7DEE6), Color(0xFF8A99A8)),
}

/**
 * 프로스티드 글래스 아이콘(docs/02-design/reference.png, DESIGN-SYSTEM-reference "비주얼 질감").
 * 오른쪽 아래로 비켜 놓은 색 뒤판 + 그 위의 반투명 유리 앞판 + 단순한 선 글리프. 이모지로 대신하지 않는다(DEC-32).
 * 진짜 배경 흐림은 Android 12 미만에서 못 쓰므로, 반투명 흰 앞판이 색 뒤판과 겹치는 부분으로 유리 느낌을 낸다.
 */
@Composable
fun GlassIcon(glyph: ImageVector, tint: GlassTint, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val plate = size * 0.80f
    val shift = size - plate
    val shape = RoundedCornerShape(size * 0.26f)
    val dark = Wb.colors.isDark
    Box(modifier.size(size)) {
        // 뒤판: 색 그라데이션
        Box(
            Modifier.offset(shift, shift).size(plate)
                .background(Brush.linearGradient(listOf(tint.light, tint.deep)), shape),
        )
        // 앞판: 반투명 유리 + 밝은 테두리 + 옅은 그림자
        Box(
            Modifier.size(plate)
                .shadow(if (dark) 0.dp else 2.dp, shape, ambientColor = tint.deep, spotColor = tint.deep)
                .background(
                    Brush.linearGradient(
                        if (dark) listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.08f))
                        else listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.55f)),
                    ),
                    shape,
                )
                .border(1.dp, Color.White.copy(alpha = if (dark) 0.30f else 0.85f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, contentDescription = null, tint = if (dark) Color.White else tint.deep, modifier = Modifier.size(plate * 0.56f))
        }
    }
}
