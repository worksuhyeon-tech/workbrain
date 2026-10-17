package com.workbrain.app.data

import com.workbrain.app.Supa
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ── 서버 행 모양(필요한 칸만). 이름은 DB 열 이름과 같다 ──
@Serializable data class IdRow(val id: String)

@Serializable data class SourceRecordInsert(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val channel: String = "recording",
    val title: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("storage_permission") val storagePermission: String,
    @SerialName("record_kind") val recordKind: String,
)

@Serializable data class ConsentInsert(
    val purpose: String,
    val provider: String = "openai",
    @SerialName("policy_version") val policyVersion: String = CONSENT_POLICY_VERSION,
)

@Serializable data class ProposalRow(
    val id: String,
    val status: String,
    val revision: Int,
    @SerialName("after_json") val after: JsonObject,
    val rationale: String,
)

@Serializable data class MeetingRow(val id: String, val fields: JsonObject)

@Serializable data class TranscriptRow(val id: String, val content: String? = null)

@Serializable data class TaskRow(
    val id: String,
    val title: String,
    val status: String,
    val version: Int,
    @SerialName("owner_kind") val ownerKind: String,
    val assignee: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_time") val dueTime: String? = null,
    @SerialName("due_ambiguous") val dueAmbiguous: Boolean = false,
    @SerialName("due_text") val dueText: String? = null,
)

/** 처리 함수 응답: stage(STT·EXTRACT·REVIEWABLE), state(PENDING·RUNNING·DONE·FAILED·BUDGET_WAIT·WAITING_KEY) */
data class ProcessStatus(val stage: String, val state: String, val error: String?)

const val CONSENT_POLICY_VERSION = "2026-10-18"

/** WorkBrain 서버 호출을 한곳에 모은다. 모든 데이터 접근은 로그인한 본인 것으로 서버(RLS)가 제한한다. */
object Server {
    private val db get() = Supa.client

    fun uid(): String = db.auth.currentUserOrNull()?.id ?: error("로그인이 필요해요")

    suspend fun workspaceId(): String =
        db.from("workspace").select { order("created_at", Order.ASCENDING); limit(1) }
            .decodeList<IdRow>().firstOrNull()?.id ?: error("기본 공간이 없어요. 다시 로그인해 주세요")

    /** 음성 외부 전사·텍스트 AI 분석 동의(FR-17). 둘 다 있어야 처리할 수 있다 */
    suspend fun hasConsent(): Boolean {
        val rows = db.from("consent_grant").select {
            filter {
                isIn("purpose", listOf("voice-stt", "text-ai"))
                exact("revoked_at", null)
            }
        }.decodeList<JsonObject>()
        return rows.map { it["purpose"]?.jsonPrimitive?.content }.toSet().size == 2
    }

    suspend fun grantConsent() {
        db.from("consent_grant").insert(listOf(ConsentInsert("voice-stt"), ConsentInsert("text-ai")))
    }

    suspend fun createRecord(row: SourceRecordInsert) {
        db.from("source_record").insert(row)
    }

    fun audioPath(recordId: String) = "${uid()}/$recordId/audio.m4a"

    suspend fun uploadAudio(recordId: String, bytes: ByteArray) {
        db.storage.from("audio").upload(audioPath(recordId), bytes) {
            upsert = true          // 같은 기록을 다시 올리면 덮어쓴다(중간에 끊긴 업로드 재시도)
            contentType = ContentType.Audio.MP4
        }
    }

    suspend fun recordReady(recordId: String, bytes: Long, durationSec: Int, sha256: String) {
        db.postgrest.rpc("wb_record_ready", buildJsonObject {
            put("p_record_id", recordId)
            put("p_bytes", bytes)
            put("p_duration_sec", durationSec)
            put("p_hash", sha256)
        })
    }

    /** 처리 함수를 한 번 불러 다음 단계를 진행한다 */
    suspend fun process(recordId: String): ProcessStatus {
        val res = db.functions.invoke("process-record", body = buildJsonObject { put("record_id", recordId) })
        val json = Json.parseToJsonElement(res.bodyAsText()) as JsonObject
        val failMessage = json["error"]?.jsonPrimitive?.content
        // 단계가 없는 응답은 거부(로그인 만료·기록 없음 등)다. 계속 다시 부르지 않고 바로 알린다
        val stage = json["stage"]?.jsonPrimitive?.content ?: error(failMessage ?: "처리 상태를 알 수 없어요")
        return ProcessStatus(stage = stage, state = json["state"]?.jsonPrimitive?.content ?: "", error = failMessage)
    }

    private suspend fun transcriptIds(recordId: String): List<String> =
        db.from("transcript_version").select {
            filter { eq("source_record_id", recordId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<IdRow>().map { it.id }

    suspend fun transcript(recordId: String): String? =
        db.from("transcript_version").select {
            filter { eq("source_record_id", recordId) }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeList<TranscriptRow>().firstOrNull()?.content

    suspend fun meeting(recordId: String): MeetingRow? {
        val tv = transcriptIds(recordId).ifEmpty { return null }
        return db.from("meeting_version").select {
            filter { isIn("transcript_version_id", tv) }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeList<MeetingRow>().firstOrNull()
    }

    suspend fun proposals(recordId: String): List<ProposalRow> {
        val tv = transcriptIds(recordId).ifEmpty { return emptyList() }
        val runs = db.from("analysis_run").select { filter { isIn("input_transcript_version_id", tv) } }
            .decodeList<IdRow>().map { it.id }.ifEmpty { return emptyList() }
        return db.from("change_proposal").select {
            filter { isIn("analysis_run_id", runs) }
            order("created_at", Order.ASCENDING)
        }.decodeList<ProposalRow>()
    }

    /** 기록 화면에서는 새 할 일만 승인할 수 있다(DEC-23). 결과: approved·already-approved·stale-revision 등 */
    suspend fun approve(p: ProposalRow): String =
        db.postgrest.rpc("approve_proposal", buildJsonObject {
            put("p_proposal_id", p.id)
            put("p_seen_revision", p.revision)
            put("p_context", "record")
        }).decodeAs<JsonObject>()["result"]?.jsonPrimitive?.content ?: ""

    suspend fun reject(p: ProposalRow): String =
        db.postgrest.rpc("reject_proposal", buildJsonObject {
            put("p_proposal_id", p.id)
            put("p_seen_revision", p.revision)
            put("p_context", "record")
        }).decodeAs<JsonObject>()["result"]?.jsonPrimitive?.content ?: ""

    suspend fun openTasks(): List<TaskRow> =
        db.from("task").select {
            filter { neq("status", "done") }
            order("created_at", Order.DESCENDING)
        }.decodeList<TaskRow>()

    /** 버전이 같을 때만 완료로 바꾼다(다른 기기에서 먼저 바꿨으면 0건, M18) */
    suspend fun completeTask(t: TaskRow): Boolean =
        db.from("task").update({
            set("status", "done")
            set("version", t.version + 1)
        }) {
            select()
            filter {
                eq("id", t.id)
                eq("version", t.version)
            }
        }.decodeList<IdRow>().isNotEmpty()
}
