package com.workbrain.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workbrain.app.ui.EmptyState
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard

/** 프로젝트(PARA): 화면 자리만 둔다. 기능은 다음 단계에서 만든다(눌러도 동작하지 않는 버튼을 두지 않는다) */
@Composable
fun ProjectsScreen() {
    Column(Modifier.fillMaxSize().background(Wb.colors.surface).padding(horizontal = 16.dp)) {
        PageHeader("프로젝트", "하고 있는 일 한눈에")
        WbCard {
            EmptyState(
                "곧 만나요",
                "프로젝트·반복실행·관심영역 정리는 다음 단계에서 만들어요.\n지금은 녹음에서 나온 할 일이 오늘과 검토함에 모여요.",
            )
        }
    }
}
