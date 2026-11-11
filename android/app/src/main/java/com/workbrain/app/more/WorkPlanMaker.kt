package com.workbrain.app.more

import com.workbrain.app.data.PlanDates
import com.workbrain.app.data.PlanKind
import com.workbrain.app.data.Server
import com.workbrain.app.data.WorkPlanResult
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 업무계획 만들기(DEC-95): [업무계획 만들기]·[다시 만들기]를 누르면 앱이 켜져 있는 동안 화면 밖에서 끝까지 만든다.
 * 10~15초 걸려서, 기다리다 화면을 나가도 요청이 끊기지 않게 화면이 아니라 여기서 부른다(회의록 1~2분은 WorkManager, MinutesWorker).
 * 결과는 서버가 저장하므로, 앱이 그사이 꺼져도 다시 들어오면 화면이 저장본을 읽는다. 같은 기간은 한 번에 하나만 만든다(두 번 눌러도 비용 한 번)
 */
object WorkPlanMaker {
    sealed interface State {
        data object Making : State
        data class Made(val result: WorkPlanResult) : State
        data object Failed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    /** 기간 열쇠(keyOf) → 이번에 앱을 켠 뒤 만든 상태. 없으면 이 앱에서 만든 적 없음(저장본은 화면이 읽는다) */
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun keyOf(kind: PlanKind, base: LocalDate): String = "${kind.code}:${PlanDates.periodStart(kind, base)}"

    fun make(kind: PlanKind, base: LocalDate) {
        val key = keyOf(kind, base)
        if (_states.value[key] == State.Making) return
        set(key, State.Making)
        scope.launch {
            val next = runCatching { Server.workPlan(kind, base) }.fold(onSuccess = { State.Made(it) }, onFailure = { State.Failed })
            set(key, next)
        }
    }

    /** 실패 알림은 그 화면에 있는 동안만 보인다(나갔다 오면 '만들기' 버튼만) */
    fun forgetFailure(key: String) {
        _states.update { if (it[key] == State.Failed) it - key else it }
    }

    private fun set(key: String, state: State) = _states.update { it + (key to state) }
}
