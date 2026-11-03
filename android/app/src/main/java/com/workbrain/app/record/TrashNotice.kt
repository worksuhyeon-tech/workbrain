package com.workbrain.app.record

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 기록 상세 ⋮에서 휴지통으로 보낸 녹음을 녹음 목록에 넘겨준다(SharedImport와 같은 방식).
 * 녹음 목록이 받아서 밀어 지우기 때와 같은 '삭제되었어요 · 되돌리기'를 띄우고 비운다
 */
object TrashNotice {
    val pending = MutableStateFlow<RecordingSession?>(null)
}
