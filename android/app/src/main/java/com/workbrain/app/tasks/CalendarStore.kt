package com.workbrain.app.tasks

import android.util.Log
import com.workbrain.app.data.Server
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * 구글 캘린더 상태(FR-24a, Design §3.16): 연결 상태와 '캘린더에 넣은 할 일' 목록을 화면들이 같이 본다(웹 calendarStore와 같은 방식).
 * 할 일 목록을 다시 읽을 때 refresh()를 함께 부르고, 고쳤는데 캘린더에 아직 반영 안 된 할 일(dirty)이 있으면 그때 sync를 한 번 부른다
 * (서버 크론 없음 — 저장·승인 뒤에 목록을 다시 읽으므로 곧바로 반영되고, 실패하면 다음 읽기 때 다시)
 */
object CalendarStore {
    /** status null = 아직 못 읽음(카드·칩을 숨긴다). links = 캘린더에 넣은 할 일 ID → dirty */
    data class State(val status: CalendarStatus? = null, val connectedAt: String? = null, val links: Map<String, Boolean> = emptyMap())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 허락하고 돌아온 결과(workbrain://calendar-callback?calendar=). 더보기가 알리고 consumeConnectResult로 비운다 */
    private val _connectResult = MutableStateFlow<String?>(null)
    val connectResult: StateFlow<String?> = _connectResult

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val syncLock = Mutex()

    /** 연결 상태·일정 연결을 다시 읽는다. 보조 정보라 못 읽으면 조용히 그대로 둔다 */
    suspend fun refresh() {
        runCatching {
            val (status, connectedAt) = Server.calendarStatus()
            val links = Server.calendarLinks()
            _state.value = State(status, connectedAt, links)
            if (status == CalendarStatus.ON && links.values.any { it }) syncDirty()
        }.onFailure { Log.w(TAG, "calendar state load failed", it) }
    }

    /** 화면 밖에서도 끝나게(저장 뒤 서랍이 닫혀도) */
    fun refreshLater() {
        scope.launch { refresh() }
    }

    /** 함수가 '연결이 끊겼어요'(Google이 열쇠를 거절)를 돌려줬을 때 */
    fun markBroken() {
        _state.update { if (it.status == CalendarStatus.ON) it.copy(status = CalendarStatus.BROKEN) else it }
    }

    private suspend fun syncDirty() {
        if (syncLock.isLocked) return
        syncLock.withLock {
            runCatching {
                Server.calendarSync()
                val links = Server.calendarLinks()
                _state.update { it.copy(links = links) }
            }.onFailure { e ->
                if (e is CalendarError && e.reason == "broken") markBroken() else Log.w(TAG, "calendar sync failed", e)
            }
        }
    }

    fun onConnectResult(result: String) {
        _connectResult.value = result
        events.clear()
        refreshLater()
    }

    fun consumeConnectResult() {
        _connectResult.value = null
    }

    // ── 일정 읽기(오늘 카드·다음 7일 카드). 같은 기간은 5분 안에 다시 부르지 않는다 ──

    private val events = mutableMapOf<String, Pair<Long, List<CalEvent>>>()

    /** force(끌어 새로고침·[다시 시도])면 기억을 버리고 바로 부른다. 실패는 CalendarError로 */
    suspend fun events(from: LocalDate, days: Int, force: Boolean): List<CalEvent> {
        val key = "$from/$days"
        val hit = events[key]
        if (!force && hit != null && System.currentTimeMillis() - hit.first < EVENTS_TTL_MS) return hit.second
        try {
            val list = Server.calendarEvents(from, days)
            events[key] = System.currentTimeMillis() to list
            return list
        } catch (e: CalendarError) {
            if (e.reason == "broken") markBroken()
            // 다른 기기에서 끊었으면 상태를 다시 읽어 카드를 숨긴다
            if (e.reason == "not-connected") refreshLater()
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CalendarError("failed", e)
        }
    }

    // ── 저장·추가 뒤 캘린더 처리 ──

    /**
     * 할 일을 저장(또는 새로 추가)한 뒤 캘린더에 할 일. 알림 끝에 붙일 말을 돌려준다(없으면 빈 글자).
     * ADD = 넣기, UNLINK·DELETE = 연결 끊기(DELETE는 일정도 지움). KEEP·NONE은 할 일 없음
     */
    suspend fun apply(taskId: String, intent: CalendarIntent): String {
        if (intent == CalendarIntent.NONE || intent == CalendarIntent.KEEP) return ""
        val r = runCatching {
            when (intent) {
                CalendarIntent.ADD -> Server.calendarInsert(taskId)
                else -> Server.calendarRemove(listOf(taskId), deleteEvent = intent == CalendarIntent.DELETE)
            }
        }
        refresh()
        return r.fold(
            onSuccess = {
                when (intent) {
                    CalendarIntent.ADD -> " · 캘린더에도 넣었어요"
                    CalendarIntent.DELETE -> " · 캘린더 일정도 지웠어요"
                    else -> " · 캘린더 일정은 남겼어요"
                }
            },
            onFailure = { e ->
                val reason = (e as? CalendarError)?.reason ?: "failed"
                if (reason == "broken") markBroken()
                " · ${calendarFailText(reason)}"
            },
        )
    }

    private const val TAG = "WbCalendar"
}
