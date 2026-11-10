package com.workbrain.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 왼쪽으로 살짝 밀면 빨간 휴지통이 보이고, 절반 넘게 밀면 지운다. onSwiped가 false면 제자리로 돌아온다.
 * 기록 탭 목록(DEC-51)과 할 일 줄(DEC-90)이 같은 손짓을 쓴다. 내용은 바탕이 칠해져 있어야 밀 때 빨간 바탕이 비치지 않는다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(onSwiped: () -> Boolean, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart && onSwiped() },
        positionalThreshold = { total -> total * 0.5f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // 밀고 있을 때만 빨간 바탕을 그린다(둥근 모서리 틈으로 비치지 않게)
            val dragging = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (dragging) Wb.colors.crit else Color.Transparent, ControlShape).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (dragging) Icon(WbIcons.Trash, contentDescription = "지우기", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        },
    ) { content() }
}
