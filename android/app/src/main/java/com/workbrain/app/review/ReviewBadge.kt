package com.workbrain.app.review

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 검토함 배지를 다시 세라는 신호. 배지는 메뉴를 바꾸거나 기록 상세를 닫을 때 센다(MainShell).
 * 그런데 프로젝트 상세에서 현재 상태 제안을 반영·거절하거나, 백그라운드에서 새 제안이 생기면(FR-09) 메뉴가 그대로라 숫자가 남는다.
 * 그런 곳에서 changed()를 부르면 MainShell이 다시 센다. 백그라운드 작업에서도 부르므로 update로 올린다
 */
object ReviewBadge {
    private val counter = MutableStateFlow(0)
    val version: StateFlow<Int> = counter

    fun changed() = counter.update { it + 1 }
}
