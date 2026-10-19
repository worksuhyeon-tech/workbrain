package com.workbrain.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 펼친 Fold처럼 창 폭이 600dp 이상이면 왼쪽 세로 메뉴 + 두 칸으로 바꾼다(Design §6.3, DEC-47).
 * 펼친 Fold8은 약 932dp라 Android 기준 expanded지만, 휴대폰은 두 칸까지만 쓴다. 3칸(근거 패널)은 PC 웹만.
 * 접기·펼치기는 configChanges로 받으므로 화면이 다시 만들어지지 않고 이 값만 바뀐다(녹음·입력 상태 유지, NFR-05).
 */
@Composable
fun isWideWindow(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** 두 칸: 왼쪽은 목록·조작, 오른쪽은 고른 것의 상세. leftWeight는 전체 폭 중 왼쪽 비율 */
@Composable
fun TwoPane(leftWeight: Float, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(leftWeight).fillMaxHeight()) { left() }
        VerticalDivider(color = Wb.colors.border)
        Box(Modifier.weight(1f - leftWeight).fillMaxHeight()) { right() }
    }
}

/** 오른쪽 칸에 아직 고른 것이 없을 때 안내 */
@Composable
fun PanePlaceholder(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted, textAlign = TextAlign.Center)
    }
}
