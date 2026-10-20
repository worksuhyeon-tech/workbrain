package com.workbrain.app.data

import com.workbrain.app.Supa
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ── 서버 행 모양(필요한 칸만). 이름은 DB 열 이름과 같다 ──
// 보낼 행에는 기본값을 두지 않는다: 직렬화가 기본값과 같은 칸을 빼고 보내 DB에서 NULL이 된다(2단계 첫 시험에서 발견)
@Serializable data class IdRow(val id: String)

@Serializable data class SourceRecordInsert(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val channel: String,
    val title: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("storage_permission") val storagePermission: String,
    @SerialName("record_kind") val recordKind: String,
    // 기본값을 두지 않는다: 직렬화가 기본값과 같은 칸을 빼 버려 빈 목록이 서버에 안 간다
    val attendees: List<String>,
)

@Serializable data class ConsentInsert(
    val purpose: String,
    val provider: String,
    @SerialName("policy_version") val policyVersion: String,
)

@Serializable data class ProposalRow(
    val id: String,
    val status: String,
    val revision: Int,
    @SerialName("after_json") val after: JsonObject,
    val rationale: String,
    /** 기존 할 일 변경 제안이면 대상 할 일 ID와 바뀌기 전 값(제목 포함, DEC-38). 새 할 일 후보면 null */
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("before_json") val before: JsonObject? = null,
) {
    val isUpdate get() = targetId != null
}

// 검토함: 후보와 그 후보가 나온 기록(후보 → 분석 → 전사 → 기록을 한 번에 따라간다)
@Serializable data class InboxRow(
    val id: String,
    val status: String,
    val revision: Int,
    @SerialName("after_json") val after: JsonObject,
    val rationale: String,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("before_json") val before: JsonObject? = null,
    @SerialName("analysis_run") val run: InboxRun? = null,
) {
    fun proposal() = ProposalRow(id, status, revision, after, rationale, targetId, before)
    val recordId: String? get() = run?.transcript?.record?.id
    val recordTitle: String get() = run?.transcript?.record?.title ?: "제목 없는 기록"
}
@Serializable data class InboxRun(@SerialName("transcript_version") val transcript: InboxTranscript? = null)
@Serializable data class InboxTranscript(@SerialName("source_record") val record: InboxRecord? = null)
@Serializable data class InboxRecord(val id: String, val title: String? = null)

@Serializable data class VocabRow(val id: String, val term: String)

/** wb_vocab_add 결과: 넣은 개수와 건너뛴 이유별 개수, 넣은 뒤 전체 개수 */
@Serializable data class VocabAddResult(
    val added: Int,
    val duplicate: Int,
    val invalid: Int,
    @SerialName("over_limit") val overLimit: Int,
    val total: Int,
)

@Serializable data class CostRow(@SerialName("confirmed_krw") val krw: Int = 0)

@Serializable data class MeetingRow(val id: String, val fields: JsonObject)
@Serializable data class MyNamesRow(@SerialName("my_names") val myNames: List<String>)

@Serializable data class TranscriptRow(val id: String, val kind: String = "", val content: String? = null)

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

/** 저장된 로그인도 없거나 서버가 거부해, 사용자가 다시 로그인해야 하는 상태 */
class LoginRequired : Exception("WB401")

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
        db.from("consent_grant").insert(listOf("voice-stt", "text-ai").map { ConsentInsert(it, provider = "openai", policyVersion = CONSENT_POLICY_VERSION) })
    }

    suspend fun createRecord(row: SourceRecordInsert) {
        db.from("source_record").insert(row)
    }

    /** 받아쓰기가 읽는 형식과 저장소 형식(서버 0019와 같은 목록, DEC-40) */
    val AUDIO_TYPES = mapOf(
        "m4a" to "audio/mp4", "mp4" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/wav",
        "ogg" to "audio/ogg", "webm" to "audio/webm", "flac" to "audio/flac",
    )

    fun audioPath(recordId: String, ext: String) = "${uid()}/$recordId/audio.$ext"

    suspend fun uploadAudio(recordId: String, bytes: ByteArray, ext: String = "m4a") {
        db.storage.from("audio").upload(audioPath(recordId, ext), bytes) {
            upsert = true          // 같은 기록을 다시 올리면 덮어쓴다(중간에 끊긴 업로드 재시도)
            contentType = ContentType.parse(AUDIO_TYPES.getValue(ext))
        }
    }

    suspend fun recordReady(recordId: String, bytes: Long, durationSec: Int, sha256: String, ext: String = "m4a") {
        db.postgrest.rpc("wb_record_ready", buildJsonObject {
            put("p_record_id", recordId)
            put("p_bytes", bytes)
            put("p_duration_sec", durationSec)
            put("p_hash", sha256)
            put("p_ext", ext)
        })
    }

    /**
     * 서버를 부르기 전에 로그인 토큰을 확보한다.
     * 자동 갱신이 잠깐 실패하면(인터넷 끊김 등) 라이브러리는 세션을 저장소에 둔 채 상태만 RefreshFailure로 바꾸고,
     * 그동안 currentSessionOrNull()이 null이라 요청에 공개 키가 붙어 401이 난다(2026-10-19 bad_jwt).
     * 그래서 메모리 세션이 없으면 저장된 세션의 refresh token으로 직접 갱신한다.
     * force: 세션은 있지만 서버가 401을 준 경우(기기가 잠든 사이 토큰 만료).
     */
    suspend fun ensureSession(force: Boolean = false) {
        val auth = db.auth
        val current = auth.currentSessionOrNull()
        if (current != null && !force) return
        val refreshToken = current?.refreshToken ?: auth.sessionManager.loadSession()?.refreshToken
            ?: throw LoginRequired()
        val renewed = try {
            auth.refreshSession(refreshToken)
        } catch (e: RestException) {
            // 4xx는 refresh token 자체가 거부된 것(로그아웃·만료). 5xx는 서버 쪽 일시 문제라 나중에 다시 한다
            if (e.statusCode in 400..499) throw LoginRequired()
            throw java.io.IOException("auth refresh failed: ${e.statusCode}", e)
        }
        auth.importSession(renewed)
    }

    /** 처리 함수를 한 번 불러 다음 단계를 진행한다 */
    suspend fun process(recordId: String): ProcessStatus {
        val body = buildJsonObject { put("record_id", recordId) }
        ensureSession()
        val res = try {
            db.functions.invoke("process-record", body = body)
        } catch (e: RestException) {
            if (e.statusCode != 401) throw e
            ensureSession(force = true)
            db.functions.invoke("process-record", body = body)
        }
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

    /** 종류별 최신 전사: stt-original(받아쓴 원문) · term-corrected(용어 교정본, DEC-36) */
    suspend fun transcripts(recordId: String): Map<String, String> =
        db.from("transcript_version").select(Columns.list("id", "kind", "content")) {
            filter { eq("source_record_id", recordId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<TranscriptRow>()
            .filter { it.content != null }
            .distinctBy { it.kind }
            .associate { it.kind to it.content!! }

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

    /** 모든 기록의 승인 대기 후보(검토함). 오래된 것부터 */
    suspend fun pendingProposals(): List<InboxRow> =
        db.from("change_proposal").select(
            Columns.raw("id,status,revision,after_json,rationale,target_id,before_json,analysis_run(transcript_version(source_record(id,title)))"),
        ) {
            filter { eq("status", "pending") }
            order("created_at", Order.ASCENDING)
        }.decodeList<InboxRow>()

    /** 이번 달 AI 처리 비용(원). 달 구분은 처리 함수와 같은 UTC 기준 yyyy-MM */
    suspend fun monthCostKrw(): Int {
        val cycle = java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString()
        return db.from("cost_ledger").select(Columns.list("confirmed_krw")) {
            filter { eq("billing_cycle", cycle) }
        }.decodeList<CostRow>().sumOf { it.krw }
    }

    /**
     * context: record(기록 상세, 새 할 일만 가능 DEC-23) · inbox(검토함).
     * 결과: approved·already-approved·stale-revision 등
     */
    suspend fun approve(p: ProposalRow, context: String, edits: JsonObject? = null): String =
        db.postgrest.rpc("approve_proposal", buildJsonObject {
            put("p_proposal_id", p.id)
            put("p_seen_revision", p.revision)
            put("p_context", context)
            // 사용자가 고친 제목·담당·기한(FR-10). AI 후보 원본 위에 덮어 할 일을 만든다
            edits?.let { put("p_edits", it) }
        }).decodeAs<JsonObject>()["result"]?.jsonPrimitive?.content ?: ""

    suspend fun reject(p: ProposalRow, context: String): String =
        db.postgrest.rpc("reject_proposal", buildJsonObject {
            put("p_proposal_id", p.id)
            put("p_seen_revision", p.revision)
            put("p_context", context)
        }).decodeAs<JsonObject>()["result"]?.jsonPrimitive?.content ?: ""

    /** 기록 제목 바꾸기. 회의록·검토함은 기록 제목을 따라 보여 준다 */
    suspend fun renameRecord(recordId: String, title: String) {
        db.from("source_record").update({ set("title", title) }) { filter { eq("id", recordId) } }
    }

    /** 내 이름·호칭(DEC-49): 회의에서 이 이름으로 맡긴 일은 처리 함수가 담당을 '나'로 바꾼다 */
    suspend fun myNames(): List<String> =
        db.from("settings").select(Columns.list("my_names")).decodeSingle<MyNamesRow>().myNames

    /** 설정 행은 가입 때 만들어지고 RLS가 본인 행만 바꾼다. 상한(5개)은 DB 제약이 지킨다 */
    suspend fun saveMyNames(names: List<String>) {
        db.from("settings").update({ set("my_names", names) }) { filter { eq("user_id", uid()) } }
    }

    /** 내 용어집(DEC-33), 클로바노트처럼 최신순 */
    suspend fun vocab(): List<VocabRow> =
        db.from("vocab_term").select(Columns.list("id", "term")) {
            order("created_at", Order.DESCENDING)
        }.decodeList<VocabRow>()

    /** 형식·중복·200개 상한 검사는 서버 함수가 한다. 한 단어 등록과 파일 가져오기 모두 이 길로 */
    suspend fun addVocab(terms: List<String>): VocabAddResult =
        db.postgrest.rpc("wb_vocab_add", buildJsonObject {
            put("p_terms", JsonArray(terms.map { JsonPrimitive(it) }))
        }).decodeAs<VocabAddResult>()

    /** 한 건·선택 여러 건 모두 같은 길. RLS가 본인 행만 지운다 */
    suspend fun deleteVocab(ids: List<String>) {
        if (ids.isEmpty()) return
        db.from("vocab_term").delete { filter { isIn("id", ids) } }
    }

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
