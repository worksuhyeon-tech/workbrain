package com.workbrain.app.tasks

import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskRow
import com.workbrain.app.ui.TOAST_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 할 일 지우기(DEC-90, ⋮ › 선택 › 삭제 DEC-92). 기록 탭 밀어 지우기처럼 줄은 바로 사라지고 '삭제되었어요 · 되돌리기'가 3초 뜬다.
 * 서버에서는 그 3초가 지난 뒤에 지운다. 되돌리기를 누르면 서버에는 아무 일도 없었던 것이 된다(이력·출처·버전 그대로).
 * 기다리는 동안 다른 탭으로 가도 지우기가 끝나도록 화면이 아니라 이 object가 기다린다.
 * 앱이 그 3초 안에 꺼지면 지우지 않은 채 남는다(잃는 쪽보다 남는 쪽이 안전)
 */
object TaskDeletes {
    // 알림의 되돌리기가 끝난 뒤에 지운다. 알림이 늦게 뜨는 만큼 조금 더 기다린다
    private const val COMMIT_MS = TOAST_MS + 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val waiting = mutableMapOf<String, Job>()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /** 목록에서 숨길 할 일(지우는 중이거나 지운 것). 다시 읽은 목록에 아직 남아 있어도 깜빡이며 다시 보이지 않게 한다 */
    val hidden: StateFlow<Set<String>> = _hidden

    private val _failed = MutableStateFlow<String?>(null)

    /** 지우지 못했을 때 알릴 문구. 화면이 받아 띄우고 consumeFailure로 비운다. 그 할 일은 목록에 다시 보인다 */
    val failed: StateFlow<String?> = _failed

    fun delete(t: TaskRow) {
        if (t.id in waiting) return
        _hidden.update { it + t.id }
        waiting[t.id] = scope.launch {
            delay(COMMIT_MS)
            val result = runCatching { Server.deleteTask(t) }
            waiting.remove(t.id)
            result
                .onSuccess { deleted -> if (!deleted) fail(t, "‘${t.title}’은(는) 다른 기기에서 먼저 바뀌어 지우지 않았어요. 새로 불러왔어요") }
                .onFailure { fail(t, "‘${t.title}’을(를) 지우지 못했어요. 인터넷 연결을 확인해 주세요") }
        }
    }

    /** 알림의 되돌리기. 아직 서버에 보내기 전이라 숨긴 것만 푼다 */
    fun undo(id: String) {
        waiting.remove(id)?.cancel()
        _hidden.update { it - id }
    }

    fun consumeFailure() {
        _failed.value = null
    }

    private fun fail(t: TaskRow, text: String) {
        _hidden.update { it - t.id }
        _failed.value = text
    }
}
