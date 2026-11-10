package com.workbrain.app.record

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 녹음 화면을 열어 달라는 요청 하나(DEC-89). 녹음은 탭이 아니어서 빠른 입력 마이크(길게)·기록 탭 [녹음]·녹음 알림이
 * 이 신호로 MainShell에 부탁한다(SharedImport·TrashNotice와 같은 방식). 여러 화면으로 콜백을 내려보내지 않으려고 화면 밖에 둔다
 */
object RecorderLaunch {
    /** 녹음 알림을 눌러 앱을 열 때 Intent에 붙이는 표시 */
    const val EXTRA_OPEN = "com.workbrain.app.OPEN_RECORDER"

    /**
     * start: 열자마자 녹음을 시작(마이크 길게). kind: 정지 뒤 올리기 창 종류 처음 값(null이면 지금 규칙).
     * project: 녹음 전일 때만 미리 고를 프로젝트(프로젝트 상세에서 길게 누른 경우)
     */
    data class Request(val start: Boolean, val kind: String? = null, val project: ChosenProject? = null)

    val pending = MutableStateFlow<Request?>(null)

    fun open(start: Boolean = false, kind: String? = null, project: ChosenProject? = null) {
        pending.value = Request(start, kind, project)
    }
}
