package com.workbrain.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.workbrain.app.R

/**
 * 목업(docs/02-design/mockups) CSS 변수와 같은 이름·값의 색 토큰.
 * Material 색 체계만으로는 good/warn/crit·muted·border 구분이 모자라 별도로 둔다.
 */
@Immutable
data class WbColors(
    val surface: Color, val card: Color, val card2: Color,
    val border: Color, val borderStrong: Color,
    val ink: Color, val body: Color, val muted: Color,
    val primary: Color, val onPrimary: Color, val link: Color,
    val darkAction: Color, val pillActive: Color, val onPillActive: Color,
    val good: Color, val warn: Color, val crit: Color, val info: Color,
    val isDark: Boolean,
)

private val LightWb = WbColors(
    surface = Color(0xFFF5F8FB), card = Color(0xFFFFFFFF), card2 = Color(0xFFFBFDFE),
    border = Color(0xFFE3E8EE), borderStrong = Color(0xFFCFD8E2),
    ink = Color(0xFF0E1726), body = Color(0xFF17253D), muted = Color(0xFF5C6B7C),
    primary = Color(0xFF0078D4), onPrimary = Color(0xFFFFFFFF), link = Color(0xFF0067B8),
    darkAction = Color(0xFF091F2C), pillActive = Color(0xFF06161F), onPillActive = Color(0xFFFFFFFF),
    good = Color(0xFF0F7A43), warn = Color(0xFF935600), crit = Color(0xFFB02A1F), info = Color(0xFF0067B8),
    isDark = false,
)

private val DarkWb = WbColors(
    surface = Color(0xFF0F1620), card = Color(0xFF141D27), card2 = Color(0xFF17212C),
    border = Color(0xFF243240), borderStrong = Color(0xFF31424F),
    ink = Color(0xFFEEF4FA), body = Color(0xFFC3D0DC), muted = Color(0xFF8EA0B2),
    primary = Color(0xFF3AA0F0), onPrimary = Color(0xFF06131D), link = Color(0xFF6BB8F6),
    darkAction = Color(0xFF0A1A24), pillActive = Color(0xFFEAF2F9), onPillActive = Color(0xFF06131D),
    good = Color(0xFF55D393), warn = Color(0xFFE6A84A), crit = Color(0xFFF08478), info = Color(0xFF6BB8F6),
    isDark = true,
)

private val LocalWbColors = staticCompositionLocalOf { LightWb }

/** 화면에서 토큰을 꺼내 쓰는 곳: `Wb.colors.muted` */
object Wb {
    val colors: WbColors
        @Composable @ReadOnlyComposable get() = LocalWbColors.current
}

// Pretendard 가변 글꼴 하나로 굵기 4단계를 쓴다(앱 크기 절약). 손글씨는 화면 머리의 짧은 한마디에만 쓴다(목업 .hand)
@OptIn(ExperimentalTextApi::class)
private fun pretendard(weight: Int) = Font(
    R.font.pretendard_variable,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Pretendard = FontFamily(pretendard(400), pretendard(500), pretendard(600), pretendard(700))
val HandAccent = FontFamily(Font(R.font.hand_accent))

private fun style(size: Int, weight: Int, line: Int, tracking: Double = -0.2) = TextStyle(
    fontFamily = Pretendard,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

// 목업 글자 크기: 화면 제목 22~30, 카드 제목 16~17, 본문 14~15, 보조 12~13
private val WbTypography = Typography(
    headlineMedium = style(26, 700, 34, -0.6),
    titleLarge = style(20, 700, 28, -0.4),
    titleMedium = style(17, 700, 24),
    titleSmall = style(15, 700, 22),
    bodyLarge = style(16, 400, 24),
    bodyMedium = style(15, 400, 22),
    bodySmall = style(13, 400, 19, -0.1),
    labelLarge = style(14, 600, 20),
    labelMedium = style(12, 700, 16, 0.0),
    labelSmall = style(11, 600, 14, 0.0),
)

private fun colorScheme(c: WbColors) = (if (c.isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = c.primary, onPrimary = c.onPrimary,
    primaryContainer = c.primary.copy(alpha = 0.12f), onPrimaryContainer = c.ink,
    secondary = c.primary, onSecondary = c.onPrimary,
    secondaryContainer = c.primary.copy(alpha = 0.12f), onSecondaryContainer = c.ink,
    background = c.surface, onBackground = c.body,
    surface = c.card, onSurface = c.ink, onSurfaceVariant = c.muted,
    surfaceVariant = c.surface,
    // Material 기본의 보라빛 표면색이 섞이지 않게 모든 표면 단계를 카드색으로 맞춘다
    surfaceContainerLowest = c.card, surfaceContainerLow = c.card, surfaceContainer = c.card,
    surfaceContainerHigh = c.card, surfaceContainerHighest = c.card2, surfaceTint = Color.Transparent,
    outline = c.borderStrong, outlineVariant = c.border,
    error = c.crit, onError = Color.White,
)

@Composable
fun WorkBrainTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkWb else LightWb
    CompositionLocalProvider(LocalWbColors provides c) {
        MaterialTheme(colorScheme = colorScheme(c), typography = WbTypography, content = content)
    }
}
