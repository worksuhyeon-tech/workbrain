package com.workbrain.app.data

import android.util.Log
import com.workbrain.app.Supa
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/*
 * 비서와 결정(DEC-111, Design §3.21, 서버 0045·함수 secretary): 다시 정할 일에 내린 결정과 집중 기록.
 * 결정·집중 기록은 주간 리뷰 '무엇이 왜 밀리나'용이라 남기지 못해도 사용자가 한 일(기한 바꾸기·완료)은 그대로 둔다.
 */

/** 결정 종류(서버 task_decision.kind) */
enum class DecisionKind(val server: String) { START("start"), SPLIT("split"), LATER("later"), WAIT("wait"), DROP("drop"), DONE("done") }

@Serializable data class SecretarySource(val title: String? = null, val at: String? = null)

/** 비서가 카드 한 장에 말할 것: 사실(출처·근거 문장·미룬 횟수·손댐)과 제안(첫 동작·조각) */
@Serializable data class SecretaryCard(
    @SerialName("task_id") val taskId: String,
    val source: SecretarySource? = null,
    val quote: String? = null,
    val defers: Int = 0,
    val untouched: Boolean = false,
    val step: String? = null,
    /** 첫 동작이 남은 하위 할 일이면 그 ID */
    @SerialName("step_task_id") val stepTaskId: String? = null,
    val pieces: List<String> = emptyList(),
)

/** ai: done · none(물을 것 없음) · no-key · budget · key(키·크레딧 문제) · failed */
@Serializable data class SecretaryAnswer(val cards: List<SecretaryCard> = emptyList(), val ai: String = "none")

/** 내려놓은 할 일(모든 할 일 › 내려놓음): 사본에서 제목·곳·하위 수를 읽는다 */
@Serializable data class DroppedRow(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val snapshot: JsonObject,
) {
    private fun text(k: String) = (snapshot[k] as? JsonPrimitive)?.contentOrNull
    val title: String get() = text("title") ?: "할 일"
    val projectId: String? get() = text("project_id")
    val areaId: String? get() = text("area_id")
    val subtaskCount: Int get() = (snapshot["subtasks"] as? JsonArray)?.size ?: 0
}

object Decide {
    private val db get() = Supa.client
    private val json = Json { ignoreUnknownKeys = true }
    private const val TAG = "WbDecide"

    /** 카드 사실·제안(최대 10개). 못 받으면 예외 — 화면은 기기에 있는 사실만으로 그린다 */
    suspend fun secretary(taskIds: List<String>): SecretaryAnswer {
        val res = Server.invokeFunction("secretary", buildJsonObject { put("task_ids", JsonArray(taskIds.map(::JsonPrimitive))) })
        return json.decodeFromString(SecretaryAnswer.serializer(), res.bodyAsText())
    }

    /** 결정 남기기(내려놓음 빼고). 할 일을 바꾸기 전에 부른다. 못 남기면 null(사용자 일은 막지 않음) */
    suspend fun decide(taskId: String, kind: DecisionKind): String? = runCatching {
        require(kind != DecisionKind.DROP)
        db.postgrest.rpc("wb_task_decide", buildJsonObject { put("p_task_id", taskId); put("p_kind", kind.server) })
            .decodeAs<JsonPrimitive>().content
    }.onFailure { Log.w(TAG, "decide failed", it) }.getOrNull()

    /** 알림의 [되돌리기]: 방금 남긴 결정을 지운다(10분 안, 내려놓음 제외) */
    suspend fun undo(decisionId: String?) {
        decisionId ?: return
        runCatching { db.postgrest.rpc("wb_task_decide_undo", buildJsonObject { put("p_decision_id", decisionId) }) }
            .onFailure { Log.w(TAG, "decide undo failed", it) }
    }

    /** 내려놓기: 버전이 같을 때만 사본을 남기고 지운다. 다른 기기에서 먼저 바꿨으면 false */
    suspend fun drop(t: TaskRow): Boolean {
        val r = db.postgrest.rpc("wb_task_drop", buildJsonObject { put("p_task_id", t.id); put("p_version", t.version) })
            .decodeAs<JsonElement>()
        return r !is JsonNull && (r as? JsonPrimitive)?.contentOrNull != null
    }

    /** 내려놓은 할 일 되살리기 → 새 할 일 ID */
    suspend fun restore(decisionId: String): String =
        db.postgrest.rpc("wb_task_restore", buildJsonObject { put("p_decision_id", decisionId) }).decodeAs<JsonPrimitive>().content

    /** 아직 되살리지 않은 내려놓음, 최근 것부터 100개 */
    suspend fun dropped(): List<DroppedRow> =
        db.from("task_decision").select(Columns.list("id", "created_at", "snapshot")) {
            filter { eq("kind", "drop"); exact("restored_at", null) }
            order("created_at", Order.DESCENDING)
            limit(100)
        }.decodeList<DroppedRow>()

    /** 하위 할 일(끝낸 것 포함). 다음 동작을 넣기 전에 같은 이름이 있는지 본다 */
    suspend fun children(parentId: String): List<TaskRow> =
        db.from("task").select { filter { eq("parent_id", parentId) } }.decodeList<TaskRow>()

    /** 집중 한 번 남기기(endedHow: done · here) */
    suspend fun logFocus(taskId: String?, step: String?, plannedMin: Int, actualSec: Int, startedAt: Instant, endedHow: String, stepDone: Boolean?) {
        runCatching {
            db.postgrest.rpc("wb_focus_log", buildJsonObject {
                put("p_task_id", taskId?.let(::JsonPrimitive) ?: JsonNull)
                put("p_step", step?.let(::JsonPrimitive) ?: JsonNull)
                put("p_planned_min", plannedMin)
                put("p_actual_sec", actualSec)
                put("p_started_at", startedAt.toString())
                put("p_ended_how", endedHow)
                put("p_step_done", stepDone?.let(::JsonPrimitive) ?: JsonNull)
            })
        }.onFailure { Log.w(TAG, "focus log failed", it) }
    }
}
