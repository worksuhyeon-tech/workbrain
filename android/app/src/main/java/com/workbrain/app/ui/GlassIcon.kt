package com.workbrain.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
    Gray(Color(0xFFE2E8EE), Color(0xFF8A99A8)),
}

/**
 * 프로스티드 글래스 아이콘(docs/02-design/reference.png, DESIGN-SYSTEM-reference "비주얼 질감").
 * 색 뒤판을 오른쪽 아래로 조금만 비켜(크기의 14%) 앞판과 대부분 겹치게 두고, 앞판은 반투명이라 겹친 자리에 뒤판 색이 비친다.
 * 진짜 배경 흐림은 Android 12 미만에서 못 쓰므로 이 비침 + 밝은 사선 테두리로 유리 느낌을 낸다. 이모지로 대신하지 않는다(DEC-32).
 */
@Composable
fun GlassIcon(glyph: ImageVector, tint: GlassTint, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val plate = size * 0.86f
    val shift = size - plate
    val shape = RoundedCornerShape(size * 0.26f)
    val dark = Wb.colors.isDark
    Box(modifier.size(size)) {
        // 뒤판: 색 그라데이션 + 같은 색의 옅은 그림자(불투명이라 그림자가 앞으로 비치지 않는다)
        Box(
            Modifier.offset(shift, shift).size(plate)
                .shadow(if (dark) 0.dp else 3.dp, shape, ambientColor = tint.deep, spotColor = tint.deep)
                .background(Brush.linearGradient(listOf(tint.light, tint.deep)), shape),
        )
        // 앞판: 반투명 유리. 그림자를 주면 반투명 면 아래로 회색이 비쳐 탁해지므로 쓰지 않는다
        Box(
            Modifier.size(plate)
                .background(
                    Brush.linearGradient(
                        if (dark) listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.10f))
                        else listOf(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.34f)),
                    ),
                    shape,
                )
                // 바깥 실선: 흰 화면 위에서 유리판 윤곽이 묻히지 않게 아주 옅은 색 테두리를 두른다
                .border(1.dp, tint.deep.copy(alpha = if (dark) 0.35f else 0.22f), shape)
                .padding(1.dp)
                // 안쪽 사선 하이라이트: 유리 모서리의 반사광
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = if (dark) 0.55f else 0.95f), Color.White.copy(alpha = if (dark) 0.12f else 0.40f)),
                        ),
                    ),
                    RoundedCornerShape(size * 0.26f - 1.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, contentDescription = null, tint = if (dark) Color.White else tint.deep, modifier = Modifier.size(plate * 0.54f))
        }
    }
}
