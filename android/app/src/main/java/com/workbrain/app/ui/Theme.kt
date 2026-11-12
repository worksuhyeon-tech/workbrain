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
    /**
     * 상태 칩·안내 띠 바탕(DEC-104): 안내만 파스텔 하늘, 지연·완료·경고는 예전처럼 글자색 12%
     * (11/13 사용자: 지연·'추가됨'·'담당 확인' 뱃지는 예전이 낫다). 글자와 대비 4.5:1 넘음
     */
    val goodSoft: Color, val warnSoft: Color, val critSoft: Color, val infoSoft: Color,
    /** 보라 칩 글자(검색 결과 '회의록', 11/12 사용자). 목업 --g-purple 계열을 칩 바탕(12%) 위에서 글자 대비 4.5:1 넘게 진하게 */
    val purple: Color,
    /** 줄 강조: 검색으로 찾아간 할 일 줄 깜빡임. 분홍 #FFAFD7의 20%(2026-11-12 사용자 지정) */
    val highlight: Color,
    /** 글자 강조(형광펜): 검색에서 맞은 글자 바탕. 같은 분홍 50%(11/12 사용자: 20%는 너무 옅고 70%는 너무 진함). 다크는 밝은 글자가 묻히지 않게 40% */
    val highlightText: Color,
    /** 프로젝트 폴더 아이콘(DEC-103, 11/12 사용자 선택 베이지 — 흰 바탕에서도 보이게 조금 짙게). PC 웹 --folder와 같은 값 */
    val folder: Color,
    /** 우선순위 높음 별(DEC-103, OneNote '중요' 태그처럼 주황). PC 웹 --star와 같은 값 */
    val star: Color,
    val isDark: Boolean,
)

private val LightWb = WbColors(
    surface = Color(0xFFFAFAFA), card = Color(0xFFFFFFFF), card2 = Color(0xFFFCFCFC),
    border = Color(0xFFECECED), borderStrong = Color(0xFFCFCFD2),
    ink = Color(0xFF262626), body = Color(0xFF3A3A3C), muted = Color(0xFF757577),
    primary = Color(0xFF008AE8), onPrimary = Color(0xFFFFFFFF), link = Color(0xFF0077C8),
    darkAction = Color(0xFF262626), pillActive = Color(0xFF262626), onPillActive = Color(0xFFFFFFFF),
    good = Color(0xFF0F7A43), warn = Color(0xFF935600), crit = Color(0xFFB02A1F), info = Color(0xFF0069B4),
    goodSoft = Color(0x1F0F7A43), warnSoft = Color(0x1F935600), critSoft = Color(0x1FB02A1F), infoSoft = Color(0xFFE0F2FE),
    purple = Color(0xFF5E46C8),
    highlight = Color(0x33FFAFD7),
    highlightText = Color(0x80FFAFD7),
    folder = Color(0xFFB88A5E),
    star = Color(0xFFF08A24),
    isDark = false,
)

private val DarkWb = WbColors(
    surface = Color(0xFF121214), card = Color(0xFF1C1C1F), card2 = Color(0xFF212124),
    border = Color(0xFF2C2C30), borderStrong = Color(0xFF45454B),
    ink = Color(0xFFF1F1F2), body = Color(0xFFD6D6D8), muted = Color(0xFF9B9BA0),
    primary = Color(0xFF3AA6F5), onPrimary = Color(0xFF04131F), link = Color(0xFF6BBCF7),
    darkAction = Color(0xFF2A2A2E), pillActive = Color(0xFFF1F1F2), onPillActive = Color(0xFF141416),
    good = Color(0xFF55D393), warn = Color(0xFFE6A84A), crit = Color(0xFFF08478), info = Color(0xFF8FCFFF),
    goodSoft = Color(0x1F55D393), warnSoft = Color(0x1FE6A84A), critSoft = Color(0x1FF08478), infoSoft = Color(0x1FAFE4FF),
    purple = Color(0xFFB9A7FF),
    highlight = Color(0x33FFAFD7),
    highlightText = Color(0x66FFAFD7),
    folder = Color(0xFFD9B48C),
    star = Color(0xFFF5A04A),
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

private fun style(size: Int, weight: Int, line: Int, tracking: Double = -0.08) = TextStyle(
    fontFamily = Pretendard,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

// 목업 글자 크기: 화면 제목 22~30, 카드 제목 16~17, 본문 14~15, 보조 12~13. 자간은 DEC-104(Aboard 비율: 24px -0.14, 20px -0.1)
private val WbTypography = Typography(
    headlineMedium = style(26, 700, 34, -0.16),
    titleLarge = style(20, 700, 28, -0.1),
    titleMedium = style(17, 700, 24, -0.09),
    titleSmall = style(15, 700, 22),
    bodyLarge = style(16, 400, 24),
    bodyMedium = style(15, 400, 22),
    bodySmall = style(13, 400, 19, -0.04),
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
