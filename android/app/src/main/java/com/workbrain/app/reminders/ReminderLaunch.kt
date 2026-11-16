package com.workbrain.app.reminders

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 미리 알림을 눌러 앱을 열었을 때 갈 곳(DEC-108): 프로젝트 탭의 그 할 일 줄로 찾아가 칠하고 서랍을 연다.
 * 화면마다 인자로 내려보내지 않으려고 화면 밖 신호로 둔다(RecorderLaunch와 같은 방식). MainShell이 한 번 쓰고 비운다
 */
object ReminderLaunch {
    const val EXTRA_OPEN_TASK = "remind_open_task"
    const val EXTRA_OPEN_KEY = "remind_open_key"

    /** (할 일 ID, 프로젝트 탭 항목 p:ID·a:ID·all) */
    val pending = MutableStateFlow<Pair<String, String>?>(null)
}
