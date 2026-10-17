package com.workbrain.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.workbrain.app.record.HomeScreen
import com.workbrain.app.review.RecordDetailScreen
import com.workbrain.app.tasks.TasksScreen

private enum class Tab(val label: String, val glyph: String) { RECORD("녹음", "●"), TASKS("할 일", "✓") }

/** 로그인 뒤 화면 틀: 아래 탭(녹음 / 할 일)과, 녹음에서 연 기록 상세. 상세는 뒤로 가기로 닫는다 */
@Composable
fun MainShell() {
    var tab by rememberSaveable { mutableStateOf(Tab.RECORD) }
    // 열린 기록: 서버 기록 ID와 제목. null이면 탭 화면
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailTitle by rememberSaveable { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && detailId == null,
                        onClick = { tab = t; detailId = null },
                        icon = { Text(t.glyph) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            val openId = detailId
            when {
                openId != null -> RecordDetailScreen(openId, detailTitle, onBack = { detailId = null })
                tab == Tab.RECORD -> HomeScreen(onOpenRecord = { id, title -> detailTitle = title; detailId = id })
                else -> TasksScreen()
            }
        }
    }
}
