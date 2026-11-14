package com.workbrain.app.data

import com.workbrain.app.Supa
import com.workbrain.app.record.TranscriptText
import com.workbrain.app.tasks.CalEvent
import com.workbrain.app.tasks.CalendarError
import com.workbrain.app.tasks.CalendarStatus
import kotlinx.serialization.builtins.ListSerializer
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
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
    /** 근거 구간(전사 문장 번호). 결정과 겹치는 후보를 결정 카드 밑에 접을 때 쓴다(DEC-48) */
    @SerialName("evidence_link") val evidence: List<EvidenceRef> = emptyList(),
    /** 검토 미루기(FR-28): 이 시각이 되면 검토함 '오늘'에 다시 나온다. 미루지 않았으면 null */
    @SerialName("snoozed_until") val snoozedUntil: String? = null,
    /** task(할 일 후보·변경) · project_state(프로젝트 현재 상태 제안, FR-09) */
    @SerialName("target_type") val targetType: String = "task",
) {
    val isUpdate get() = targetId != null
    val segments: Set<Int> get() = evidence.flatMap { it.segments }.toSet()
}
@Serializable data class EvidenceRef(@SerialName("segment_ref") val ref: JsonObject? = null) {
    val segments: List<Int> get() = (ref?.get("idx") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
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
    @SerialName("snoozed_until") val snoozedUntil: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("target_type") val targetType: String = "task",
) {
    /** 프로젝트 현재 상태 제안(FR-09): 할 일 후보 목록·묶음 고르기에 섞지 않고 따로 보여 준다 */
    val isState: Boolean get() = targetType == "project_state"
    fun proposal() = ProposalRow(id, status, revision, after, rationale, targetId, before, snoozedUntil = snoozedUntil, targetType = targetType)
    val recordId: String? get() = run?.transcript?.record?.id
    val recordTitle: String get() = run?.transcript?.record?.title ?: "제목 없는 기록"
    val recordAt: String? get() = run?.transcript?.record?.let { it.occurredAt ?: it.ingestedAt }
    /** 근거 전사 보관 기한(미뤄도 늘지 않음, DEC-15). 지나면 승인할 수 없다 */
    val transcriptExpiresAt: String? get() = run?.transcript?.expiresAt
    /** 원본 음성 보관 기한과 이미 지웠는지. 음성이 없는 기록(가져온 글)이면 null */
    val audio: InboxAudio? get() = run?.transcript?.record?.audio?.let { el ->
        // 기록당 음성은 하나라 PostgREST가 객체나 한 칸짜리 배열로 준다
        val obj = (el as? JsonObject) ?: (el as? JsonArray)?.firstOrNull() as? JsonObject ?: return@let null
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        InboxAudio(text("retention_expires_at"), text("purged_at"))
    }
}
@Serializable data class InboxRun(@SerialName("transcript_version") val transcript: InboxTranscript? = null)
@Serializable data class InboxTranscript(
    @SerialName("retention_expires_at") val expiresAt: String? = null,
    @SerialName("source_record") val record: InboxRecord? = null,
)
@Serializable data class InboxRecord(
    val id: String,
    val title: String? = null,
    /** 회의 시각(모르면 올린 시각). 오늘 화면 '검토 대기'의 '주간회의 · 10/16' */
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("ingested_at") val ingestedAt: String? = null,
    @SerialName("audio_asset") val audio: JsonElement? = null,
)
data class InboxAudio(val expiresAt: String?, val purgedAt: String?)

// 검토함 후보 + 나온 기록 + 근거 전사·음성 보관 기한(후보 → 분석 → 전사 → 기록 → 음성을 한 번에 따라간다)
private const val INBOX_COLUMNS = "id,status,revision,after_json,rationale,target_id,before_json,snoozed_until,created_at,target_type," +
    "analysis_run(transcript_version(retention_expires_at,source_record(id,title,occurred_at,ingested_at,audio_asset(retention_expires_at,purged_at))))"

/** 처리됨 탭(FR-28): 이미 추가·버림·충돌로 끝난 후보와 처리한 시각(승인 기록이 없으면 후보가 만들어진 시각) */
data class ProcessedRow(val row: InboxRow, val processedAt: String?)

@Serializable private data class ApprovalTimeRow(
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("created_at") val createdAt: String,
)

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

/** 가져온 지침의 참고 파일 하나(references 폴더의 .md) */
@Serializable data class GuidelineRef(val name: String, val content: String)

/** 설정에 저장된 회의록 지침. guideline이 null이면 앱 기본 지침(rev 0)을 쓰는 중 */
@Serializable data class GuidelineRow(
    @SerialName("minutes_guideline") val guideline: String? = null,
    @SerialName("minutes_guideline_refs") val refs: List<GuidelineRef>? = null,
    @SerialName("guideline_rev") val rev: Int = 0,
    @SerialName("guideline_imported_at") val importedAt: String? = null,
    @SerialName("guideline_source_name") val sourceName: String? = null,
)

/** 서버가 지침을 거부했을 때(WB400): 사용자에게 그대로 보여 줄 이유 */
class GuidelineRejected(reason: String) : Exception(reason)

@Serializable data class MeetingRow(
    val id: String,
    val fields: JsonObject,
    // 사람이 읽는 회의록(FR-30). '회의록 만들기'를 누르기 전에는 없다
    val document: MinutesDocument? = null,
    // 문서를 만들 때 쓴 지침 번호(0 = 기본 지침)와 가져온 시각
    @SerialName("guideline_rev") val guidelineRev: Int? = null,
    @SerialName("guideline_imported_at") val guidelineImportedAt: String? = null,
    // 회의록 승인(FR-07, DEC-61): draft | user-edited | approved
    val state: String = "draft",
    @SerialName("approved_at") val approvedAt: String? = null,
) {
    val approved: Boolean get() = state == "approved"
}

@Serializable data class ApprovedAtRow(@SerialName("approved_at") val approvedAt: String? = null)

/**
 * 검색 결과 한 줄(FR-13, 서버 0039 wb_search). kind: task · record · resource · proposal(검토 대기 할 일 후보).
 * snippet은 제목이 아닌 곳에서 맞았을 때 그 앞뒤 조각, at은 기록·후보 = 회의 시각, 할 일·자료 = 만든 시각
 */
@Serializable data class SearchRow(
    val kind: String,
    val id: String,
    val title: String? = null,
    val snippet: String? = null,
    @SerialName("record_id") val recordId: String? = null,
    /** 그 기록 제목(후보 줄의 출처·기록 상세 머리). 제목 없는 기록이면 null */
    @SerialName("record_title") val recordTitle: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("area_id") val areaId: String? = null,
    @SerialName("place_name") val placeName: String? = null,
    val at: String? = null,
    /** 검토 전 초안: 승인 전 회의록 글에서만 맞은 기록 · 검토 대기 할 일 후보 */
    val draft: Boolean = false,
    val done: Boolean = false,
    /** 보관한 곳의 할 일 · 보관한 자료 */
    val archived: Boolean = false,
    @SerialName("audio_expired") val audioExpired: Boolean = false,
)

/** 회의록 문서: 섹션마다 글줄과 (있으면) 표. 서버 document.ts의 MinutesDocument와 같은 모양 */
@Serializable data class MinutesDocument(val title: String = "", val sections: List<MinutesSection> = emptyList())
@Serializable data class MinutesSection(val heading: String, val lines: List<String> = emptyList(), val table: MinutesTable? = null)
@Serializable data class MinutesTable(val columns: List<String>, val rows: List<List<String>> = emptyList())
@Serializable data class MyNamesRow(@SerialName("my_names") val myNames: List<String>)
/** 기록 상세 머리(목업 S05 '녹음 · 42분 · 10/16 15:00')와 참석자 명단 */
@Serializable data class RecordInfoRow(
    val attendees: List<String>? = null,
    /** AI가 붙이고 사용자가 고치는 분류: work · personal · uncertain(FR-06). 처리 전이면 null */
    val classification: String? = null,
    val channel: String? = null,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("ingested_at") val ingestedAt: String? = null,
    @SerialName("audio_asset") val audio: JsonElement? = null,
) {
    /** 음성 길이(초). 받아쓴 글만 가져온 기록은 음성이 없어 null */
    val durationSec: Int? get() = audio?.let { el ->
        // 기록당 음성은 하나라 PostgREST가 객체나 한 칸짜리 배열로 준다
        val obj = (el as? JsonObject) ?: (el as? JsonArray)?.firstOrNull() as? JsonObject ?: return@let null
        (obj["duration_sec"] as? JsonPrimitive)?.intOrNull
    }
}

/** 서버가 만든 회의록 Word. meetingDate = 회의 날짜(서울, YYYY-MM-DD), draft = 승인 전 문서 */
class MinutesWord(val bytes: ByteArray, val meetingDate: String, val draft: Boolean)

/** 회의록 만들기 응답: 다 됨 · 같은 요청을 아직 만드는 중(앱 작업이 다시 돌았을 때) · 실패(보여 줄 문구) */
sealed interface MinutesAnswer {
    data object Done : MinutesAnswer
    data object Running : MinutesAnswer
    data class Failed(val message: String) : MinutesAnswer
}

@Serializable data class TranscriptRow(val id: String, val kind: String = "", val content: String? = null, val segments: List<TranscriptSegment>? = null)

/** 전사 문장 구간. speaker·offsetMs는 가져온 txt의 화자·시각으로 화면 표시 전용(DEC-54) */
@Serializable data class TranscriptSegment(val idx: Int, val text: String, val speaker: String? = null, val offsetMs: Long? = null)

/** 한 전사 버전: 본문과 문장 구간 */
data class Transcript(val content: String, val segments: List<TranscriptSegment>)

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
    /** 완료한 시각(서버 트리거가 채움, DEC-64). 완료 전·완료 취소면 null */
    @SerialName("done_at") val doneAt: String? = null,
    /** 1 높음 · 2 보통 · 3 낮음. 비어 있으면 보통으로 보여 준다(목업 기본값) */
    val priority: Int? = null,
    /** 속한 곳(FR-02). 둘 중 하나만 있거나 둘 다 없음('정리 안 됨') */
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("area_id") val areaId: String? = null,
    /** 오늘 화면용으로 함께 읽은 속한 곳 이름·보관 여부(TASK_WITH_PLACE). 다른 조회에서는 null */
    val project: PlaceRef? = null,
    val area: PlaceRef? = null,
    /** 받는 사람(DEC-77, 0035): 메일·공유·전달·알려 줄 상대. 없으면 빈 목록(칩이 안 보임) */
    val recipients: List<String> = emptyList(),
    /** 하위 할 일이면 상위 할 일 ID(FR-23a, 0040). 곳은 늘 상위와 같다(서버 트리거) */
    @SerialName("parent_id") val parentId: String? = null,
    /** 만든 시각(하위 할 일을 만든 순서로 보인다) */
    @SerialName("created_at") val createdAt: String? = null,
) {
    val done: Boolean get() = status == "done"
    val isSubtask: Boolean get() = parentId != null
    val placeName: String? get() = project?.name ?: area?.name
}

@Serializable data class Budget(@SerialName("budget_warn_krw") val warnKrw: Int, @SerialName("budget_wait_krw") val waitKrw: Int)

@Serializable data class PlaceRef(val name: String, @SerialName("archived_at") val archivedAt: String? = null)

/** 할 일 고치기 서랍에서 저장하는 값 전부(FR-02 어디에 + 제목·담당·기한·우선순위) */
data class TaskEdit(
    val title: String,
    val priority: Int?,
    val ownerKind: String,
    val assignee: String?,
    val dueDate: String?,
    val dueTime: String?,
    val dueAmbiguous: Boolean,
    val dueText: String?,
    val projectId: String?,
    val areaId: String?,
    val recipients: List<String> = emptyList(),
)

fun TaskRow.toEdit() = TaskEdit(title, priority, ownerKind, assignee, dueDate, dueTime, dueAmbiguous, dueText, projectId, areaId, recipients)

/**
 * 사용자가 직접 만드는 새 할 일(한 줄 빠른 입력·새 할 일 서랍, DEC-70·73)의 task 행.
 * AI 승인 경로가 아니다. 소유자는 DB 기본값(auth.uid), 아카이브된 곳은 서버가 WB409로 막는다
 */
fun TaskEdit.toNewTaskFields(): JsonObject = buildJsonObject {
    put("title", title)
    priority?.let { put("priority", it) }
    put("owner_kind", ownerKind)
    assignee?.let { put("assignee", it) }
    dueDate?.let { put("due_date", it) }
    dueTime?.let { put("due_time", it) }
    if (dueAmbiguous) {
        put("due_ambiguous", true)
        dueText?.let { put("due_text", it) }
    }
    projectId?.let { put("project_id", it) }
    areaId?.let { put("area_id", it) }
    if (recipients.isNotEmpty()) put("recipients", JsonArray(recipients.map(::JsonPrimitive)))
}

private const val TASK_WITH_PLACE = "*,project(name,archived_at),area(name,archived_at)"

/** 처리 함수 응답: stage(STT·EXTRACT·REVIEWABLE), state(PENDING·RUNNING·DONE·FAILED·BUDGET_WAIT·WAITING_KEY) */
data class ProcessStatus(val stage: String, val state: String, val error: String?)

const val CONSENT_POLICY_VERSION = "2026-10-18"
private const val REFS_CONSENT_PURPOSE = "minutes-guideline-refs"

/** 저장된 로그인도 없거나 서버가 거부해, 사용자가 다시 로그인해야 하는 상태 */
class LoginRequired : Exception("WB401")

/** WorkBrain 서버 호출을 한곳에 모은다. 모든 데이터 접근은 로그인한 본인 것으로 서버(RLS)가 제한한다. */
object Server {
    private val db get() = Supa.client
    /** 업무계획 글을 주고받을 때: 서버가 칸을 더해도 앱이 깨지지 않게 모르는 칸은 넘긴다 */
    private val planJson = Json { ignoreUnknownKeys = true }

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

    /** 참고 파일(인물 사전 등)을 회의록 만들 때 OpenAI로 함께 보내도 되는지(DEC-21 Q10). 음성·텍스트 동의와 별도 */
    suspend fun refsConsent(): Boolean =
        db.from("consent_grant").select {
            filter {
                eq("purpose", REFS_CONSENT_PURPOSE)
                exact("revoked_at", null)
            }
        }.decodeList<JsonObject>().isNotEmpty()

    /** 켤 때는 새 동의 행을, 끌 때는 살아 있는 동의 행의 철회 시각을 채운다(행을 지우지 않아 이력이 남는다) */
    suspend fun setRefsConsent(on: Boolean) {
        if (on) {
            if (!refsConsent()) db.from("consent_grant").insert(ConsentInsert(REFS_CONSENT_PURPOSE, provider = "openai", policyVersion = CONSENT_POLICY_VERSION))
        } else {
            db.from("consent_grant").update({ set("revoked_at", java.time.Instant.now().toString()) }) {
                filter {
                    eq("purpose", REFS_CONSENT_PURPOSE)
                    exact("revoked_at", null)
                }
            }
        }
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
     * 받아쓴 글 가져오기(DEC-53): 원문을 저장하고 받아쓰기 없이 교정 단계부터 처리를 건다(서버 0022).
     * turns: 전문 보기용 화자·시각(DEC-54, 서버 0023). 서버는 AI 입력에 넣지 않는다
     */
    suspend fun recordTextReady(recordId: String, text: String, turns: List<TranscriptText.Turn>) {
        db.postgrest.rpc("wb_record_text_ready", buildJsonObject {
            put("p_record_id", recordId)
            put("p_text", text)
            if (turns.isNotEmpty()) put("p_turns", Json.encodeToJsonElement(turns))
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

    /** 서버 함수 호출. 세션이 만료돼 401이면 다시 로그인한 뒤 한 번 더 부른다 */
    private suspend fun invokeFunction(name: String, body: JsonObject): HttpResponse {
        ensureSession()
        return try {
            db.functions.invoke(name, body = body)
        } catch (e: RestException) {
            if (e.statusCode != 401) throw e
            ensureSession(force = true)
            db.functions.invoke(name, body = body)
        }
    }

    /**
     * 회의록 만들기(FR-30, DEC-56): 처리 함수가 이 호출 안에서 문서를 만든다(짧은 회의도 1~2분).
     * requestKey는 누를 때 한 번 만든 열쇠: 앱 작업이 멈췄다 다시 돌아도 같은 열쇠라 서버가 두 번 만들지 않는다(DEC-79)
     */
    suspend fun makeMinutes(recordId: String, requestKey: String): MinutesAnswer {
        val res = invokeFunction("process-record", buildJsonObject {
            put("record_id", recordId); put("action", "document"); put("request_key", requestKey)
        })
        val json = Json.parseToJsonElement(res.bodyAsText()) as JsonObject
        return when (json["document"]?.jsonPrimitive?.content) {
            "DONE" -> MinutesAnswer.Done
            "RUNNING" -> MinutesAnswer.Running
            else -> MinutesAnswer.Failed(json["error"]?.jsonPrimitive?.content ?: "회의록을 만들지 못했어요")
        }
    }

    /** 응답을 못 받은 회의록 요청이 서버에서 어떻게 됐는지: DONE·FAILED·RUNNING·BUSY·NONE, 내 기록이 아니면 null(DEC-79) */
    suspend fun minutesStatus(recordId: String, requestKey: String): String? {
        ensureSession()
        return db.postgrest.rpc("wb_document_status", buildJsonObject {
            put("p_record_id", recordId); put("p_key", requestKey)
        }).decodeAs<JsonElement>().jsonPrimitive.contentOrNull
    }

    /**
     * 회의록 Word(FR-29, DEC-60): 화면에 보이는 회의록 버전을 서버가 .docx로 만들어 준다(M14).
     * 회의 날짜(서울)는 파일 이름·메일 제목에 쓴다. 문서가 없거나 남의 것이면 서버가 404로 거부한다
     */
    suspend fun minutesWord(meetingVersionId: String): MinutesWord {
        val res = invokeFunction("minutes-word", buildJsonObject { put("meeting_version_id", meetingVersionId) })
        return MinutesWord(res.bodyAsBytes(), res.headers["x-wb-meeting-date"].orEmpty(), res.headers["x-wb-draft"] != "false")
    }

    /**
     * 업무계획(FR-33, DEC-87·88): 처리 함수가 기준일이 속한 주(달)와 다음 주(달)의 표 글을 규칙으로 만들고,
     * 전주 칸 □ 줄만 AI가 '이름 + 진행 상태' 한 줄로 다듬는다(한 번에 약 1원). 자료를 못 읽으면 예외, AI만 못 쓰면 ai 값으로 알린다.
     * 서버가 결과를 work_plan에 남기고 돌려준다(DEC-95). 같은 기간을 다시 만들면 고쳐 쓴다
     */
    suspend fun workPlan(kind: PlanKind, baseDate: LocalDate): WorkPlanResult {
        val res = invokeFunction("process-record", buildJsonObject {
            put("action", "work_plan"); put("kind", kind.code); put("base_date", baseDate.toString())
        })
        val json = Json.parseToJsonElement(res.bodyAsText()) as JsonObject
        val fail = json["error"]?.jsonPrimitive?.contentOrNull ?: "업무계획을 만들지 못했어요"
        val plan = json["plan"] ?: error(fail)
        val madeAt = json["made_at"]?.jsonPrimitive?.contentOrNull ?: error(fail)
        return WorkPlanResult(planJson.decodeFromJsonElement(WorkPlan.serializer(), plan), json["ai"]?.jsonPrimitive?.contentOrNull ?: "none", madeAt)
    }

    /** 이 기간에 만들어 둔 업무계획(DEC-95, 다른 기기에서 만든 것 포함). 아직 없으면 null */
    suspend fun savedWorkPlan(kind: PlanKind, baseDate: LocalDate): WorkPlanResult? =
        db.from("work_plan").select(Columns.raw("plan,ai,made_at")) {
            filter { eq("kind", kind.code); eq("period_start", PlanDates.periodStart(kind, baseDate).toString()) }
        }.decodeList<SavedWorkPlanRow>().firstOrNull()?.toResult()

    /** 업무계획 Word: 미리보기로 받은 글을 그대로 보내 같은 표를 .docx로 받는다(AI를 다시 부르지 않음, 서버는 모양·크기만 검사) */
    suspend fun planWord(plan: WorkPlan): ByteArray =
        invokeFunction("minutes-word", buildJsonObject { put("plan", planJson.encodeToJsonElement(WorkPlan.serializer(), plan)) }).bodyAsBytes()

    /** 처리 함수를 한 번 불러 다음 단계를 진행한다 */
    suspend fun process(recordId: String): ProcessStatus {
        val res = invokeFunction("process-record", buildJsonObject { put("record_id", recordId) })
        val json = Json.parseToJsonElement(res.bodyAsText()) as JsonObject
        val failMessage = json["error"]?.jsonPrimitive?.content
        // 단계가 없는 응답은 거부(로그인 만료·기록 없음 등)다. 계속 다시 부르지 않고 바로 알린다
        val stage = json["stage"]?.jsonPrimitive?.content ?: error(failMessage ?: "처리 상태를 알 수 없어요")
        return ProcessStatus(stage = stage, state = json["state"]?.jsonPrimitive?.content ?: "", error = failMessage)
    }

    /**
     * 밀린 프로젝트 연결 제안 만들기(FR-06, DEC-75): 처리 함수가 한 번에 몇 건씩 만들고 남은 수를 알려 준다.
     * 남은 게 없거나, 이번에 하나도 못 만들었거나(실패·예산 대기선), 15번을 부르면 멈춘다. 만든 제안 수를 돌려준다
     */
    suspend fun suggestLinks(): Int {
        var made = 0
        repeat(15) {
            val res = invokeFunction("process-record", buildJsonObject { put("action", "suggest_links") })
            val json = Json.parseToJsonElement(res.bodyAsText()) as JsonObject
            made += json["made"]?.jsonPrimitive?.intOrNull ?: 0
            val done = json["done"]?.jsonPrimitive?.intOrNull ?: 0
            val remaining = json["remaining"]?.jsonPrimitive?.intOrNull ?: 0
            if (remaining == 0 || done == 0) return made
        }
        return made
    }

    /**
     * 프로젝트 현재 상태 제안(FR-09, DEC-86): 연결을 확정한 기록 하나로 그 프로젝트의 제안을 만들거나 고쳐 쓴다.
     * 결과: PROPOSED · NO_CHANGE · ALREADY(이미 반영) · NOT_LINKED · NO_TRANSCRIPT(아직 정리 전) · BUDGET_WAIT · FAILED 등
     */
    suspend fun projectState(recordId: String, projectId: String): String {
        val res = invokeFunction("process-record", buildJsonObject {
            put("action", "project_state"); put("record_id", recordId); put("project_id", projectId)
        })
        val json = Json.parseToJsonElement(res.bodyAsText()) as JsonObject
        return json["state"]?.jsonPrimitive?.content ?: "FAILED"
    }

    private suspend fun transcriptIds(recordId: String): List<String> =
        db.from("transcript_version").select {
            filter { eq("source_record_id", recordId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<IdRow>().map { it.id }

    /** 종류별 최신 전사: stt-original(받아쓴 원문) · term-corrected(용어 교정본, DEC-36) */
    suspend fun transcripts(recordId: String): Map<String, Transcript> =
        db.from("transcript_version").select(Columns.list("id", "kind", "content", "segments")) {
            filter { eq("source_record_id", recordId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<TranscriptRow>()
            .filter { it.content != null }
            .distinctBy { it.kind }
            .associate { it.kind to Transcript(it.content!!, it.segments.orEmpty()) }

    suspend fun meeting(recordId: String): MeetingRow? {
        val tv = transcriptIds(recordId).ifEmpty { return null }
        return db.from("meeting_version").select {
            filter { isIn("transcript_version_id", tv) }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeList<MeetingRow>().firstOrNull()
    }

    /**
     * 이 기록의 지금 승인본(대체되지 않은 것)이 승인된 시각. 없으면 null.
     * 승인 뒤 다시 만들면 새 초안이 화면에 나오므로, 승인본이 따로 있다는 것을 알리는 데 쓴다(DEC-61)
     */
    suspend fun approvedMeetingAt(recordId: String): String? {
        val tv = transcriptIds(recordId).ifEmpty { return null }
        return db.from("meeting_version").select(Columns.list("approved_at")) {
            filter {
                isIn("transcript_version_id", tv)
                eq("state", "approved")
                exact("superseded_at", null)
            }
            limit(1)
        }.decodeList<ApprovedAtRow>().firstOrNull()?.approvedAt
    }

    /**
     * 회의록 버전 승인(FR-07). 결과: approved | already-approved | stale-version | no-document | evidence-missing.
     * 화면에 보이는 버전 ID를 보내 다른 기기에서 새로 만든 버전을 모르고 승인하지 않게 한다(M14)
     */
    suspend fun approveMeeting(meetingVersionId: String): String =
        db.postgrest.rpc("approve_meeting", buildJsonObject {
            put("p_meeting_version_id", meetingVersionId)
        }).decodeAs<JsonObject>()["result"]?.jsonPrimitive?.content ?: ""

    /**
     * 검색(FR-13, DEC-98): 서버가 내 것(RLS)에서 글자로 찾아 종류마다 최신 30건씩 준다. AI를 쓰지 않아 비용 0원.
     * q는 두 글자 이상(미만이면 서버가 빈 결과), 곳은 프로젝트·반복실행 중 하나만, kinds가 null이면 모든 종류
     */
    suspend fun search(q: String, place: Place?, since: java.time.Instant?, kinds: List<String>?): List<SearchRow> =
        db.postgrest.rpc("wb_search", buildJsonObject {
            put("p_q", q)
            put("p_project", place?.takeIf { it.type == PlaceType.PROJECT }?.let { JsonPrimitive(it.id) } ?: JsonNull)
            put("p_area", place?.takeIf { it.type == PlaceType.AREA }?.let { JsonPrimitive(it.id) } ?: JsonNull)
            put("p_since", since?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("p_kinds", kinds?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
        }).decodeList<SearchRow>()

    suspend fun proposals(recordId: String): List<ProposalRow> {
        val tv = transcriptIds(recordId).ifEmpty { return emptyList() }
        val runs = db.from("analysis_run").select { filter { isIn("input_transcript_version_id", tv) } }
            .decodeList<IdRow>().map { it.id }.ifEmpty { return emptyList() }
        // 기록 상세는 할 일 후보·변경만 다룬다. 이 기록으로 만든 현재 상태 제안(FR-09)은 프로젝트 상세·검토함에서 본다
        return db.from("change_proposal").select(Columns.raw("*,evidence_link(segment_ref)")) {
            filter { isIn("analysis_run_id", runs); eq("target_type", "task") }
            order("created_at", Order.ASCENDING)
        }.decodeList<ProposalRow>()
    }

    /**
     * 사용자가 직접 만드는 할 일(결정 카드 '할 일로 만들기' DEC-48, 한 줄 빠른 입력 DEC-70). AI 후보 승인 경로가 아니다.
     * fields는 task 표의 사용자 입력 칸(title·priority·owner_kind·assignee·due_*·project_id/area_id)만 담는다. 소유자는 DB 기본값(auth.uid)
     */
    suspend fun createTask(fields: JsonObject): String =
        // 새 할 일 ID: [캘린더에도]를 켰으면 바로 일정으로 넣는다(FR-24a)
        db.from("task").insert(fields) { select(Columns.list("id")) }.decodeSingle<IdRow>().id

    /**
     * 모든 기록의 승인 대기 후보(검토함). 오래된 것부터. 미룬 후보도 함께 오며 '오늘'·'미룬 항목'은 화면이 나눈다(FR-28).
     * 미룬 항목에 근거 전사·음성 보관 기한을 따로 보여 주려고 함께 읽는다(Design §3.7)
     */
    suspend fun pendingProposals(): List<InboxRow> =
        db.from("change_proposal").select(Columns.raw(INBOX_COLUMNS)) {
            filter { eq("status", "pending") }
            order("created_at", Order.ASCENDING)
        }.decodeList<InboxRow>()

    /** 처리됨 탭: 최근에 처리한 후보 100건, 처리한 시각 최신순(DEC-63) */
    suspend fun processedProposals(): List<ProcessedRow> {
        val rows = db.from("change_proposal").select(Columns.raw(INBOX_COLUMNS)) {
            filter { neq("status", "pending") }
            order("created_at", Order.DESCENDING)
            limit(100)
        }.decodeList<InboxRow>()
        if (rows.isEmpty()) return emptyList()
        val decidedAt = db.from("approval_event").select(Columns.list("proposal_id", "created_at")) {
            filter { isIn("proposal_id", rows.map { it.id }) }
        }.decodeList<ApprovalTimeRow>().associate { it.proposalId to it.createdAt }
        return rows.map { ProcessedRow(it, decidedAt[it.id] ?: it.createdAt) }
            .sortedByDescending { r -> r.processedAt?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() } }
    }

    /**
     * 검토 미루기(FR-28, 서버 0028). until = 다시 볼 날(그날 0시에 '오늘'로), null이면 지금 검토(미룸 해제).
     * 대기 중인 내 후보만 바뀌고, 바뀐 개수를 돌려준다
     */
    suspend fun snoozeProposals(ids: List<String>, until: java.time.LocalDate?): Int =
        db.postgrest.rpc("wb_snooze_proposals", buildJsonObject {
            put("p_ids", JsonArray(ids.map { JsonPrimitive(it) }))
            put("p_until", until?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        }).decodeAs<JsonObject>()["updated"]?.jsonPrimitive?.intOrNull ?: 0

    /** 비용 경고선·대기선(원). 경고선을 넘으면 오늘 화면에 띠, 대기선을 넘으면 새 전사·분석이 기다린다(C7) */
    suspend fun budget(): Budget =
        db.from("settings").select(Columns.list("budget_warn_krw", "budget_wait_krw")).decodeSingle()

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

    /**
     * 기록 상세 머리 정보(종류·길이·회의 시각)와 참석자 명단(DEC-39).
     * 명단은 처리가 끝난 뒤에도 기록 상세에서 고칠 수 있다(DEC-59)
     */
    suspend fun recordInfo(recordId: String): RecordInfoRow =
        db.from("source_record").select(Columns.raw("attendees,classification,channel,occurred_at,ingested_at,audio_asset(duration_sec)")) {
            filter { eq("id", recordId) }
        }.decodeSingle<RecordInfoRow>()

    /** 분류 고치기(FR-06, DEC-75). 개인으로 바꾸면 그 기록에는 새 연결 제안을 만들지 않는다 */
    suspend fun setClassification(recordId: String, value: String) {
        db.from("source_record").update({ set("classification", value) }) { filter { eq("id", recordId) } }
    }

    /** 고친 명단은 다음 회의록 만들기부터 쓰인다. 이미 뽑은 할 일 후보·교정본은 다시 만들지 않는다 */
    suspend fun saveAttendees(recordId: String, names: List<String>) {
        db.from("source_record").update({ set("attendees", names) }) { filter { eq("id", recordId) } }
    }

    /** 내 이름·호칭(DEC-49): 회의에서 이 이름으로 맡긴 일은 처리 함수가 담당을 '나'로 바꾼다 */
    suspend fun myNames(): List<String> =
        db.from("settings").select(Columns.list("my_names")).decodeSingle<MyNamesRow>().myNames

    /** 설정 행은 가입 때 만들어지고 RLS가 본인 행만 바꾼다. 상한(5개)은 DB 제약이 지킨다 */
    suspend fun saveMyNames(names: List<String>) {
        db.from("settings").update({ set("my_names", names) }) { filter { eq("user_id", uid()) } }
    }

    suspend fun guideline(): GuidelineRow =
        db.from("settings").select(
            Columns.list("minutes_guideline", "minutes_guideline_refs", "guideline_rev", "guideline_imported_at", "guideline_source_name"),
        ).decodeSingle<GuidelineRow>()

    /**
     * 지침을 통째로 바꾼다(검사 후 교체, 번호 +1). 새 번호를 돌려준다.
     * 서버가 상한 등으로 거부(WB400)하면 이유를 GuidelineRejected로 던지고 기존 지침은 그대로다
     */
    suspend fun setGuideline(skill: String, refs: List<GuidelineRef>, sourceName: String): Int {
        val result = try {
            db.postgrest.rpc("wb_guideline_set", buildJsonObject {
                put("p_guideline", skill)
                put("p_refs", Json.encodeToJsonElement(refs))
                put("p_source_name", sourceName)
            }).decodeAs<JsonObject>()
        } catch (e: RestException) {
            val reason = Regex("WB400: ([^\"\\n}]+)").find(e.message.orEmpty())?.groupValues?.get(1)?.trim()
            throw if (reason != null) GuidelineRejected(reason) else e
        }
        return result["guideline_rev"]?.jsonPrimitive?.intOrNull ?: error("지침 번호를 받지 못했어요")
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

    /**
     * 오늘 탭: 아직 안 끝난 할 일 + 오늘(서울) 완료한 할 일(DEC-64). 오늘 완료한 것은 취소선으로 남겨 '오늘 뭘 했지?'를 보이게 한다.
     * 완료 시각은 서버 트리거가 채우므로 이 기능 전에 완료한 할 일은 나오지 않는다
     */
    suspend fun todayTasks(): List<TaskRow> {
        val todayStart = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
            .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant().toString()
        return db.from("task").select(Columns.raw(TASK_WITH_PLACE)) {
            filter {
                or {
                    neq("status", "done")
                    gte("done_at", todayStart)
                }
            }
            order("created_at", Order.DESCENDING)
        }.decodeList<TaskRow>()
            // 보관된 프로젝트·반복실행의 할 일은 오늘에서 뺀다(목업 S02, 복원하면 다시 보임)
            .filter { it.project?.archivedAt == null && it.area?.archivedAt == null }
    }

    /** 받는 사람 고르기 칩(DEC-77): 그 기록의 참석자 + 이름 붙은 화자(서버가 번호 화자·'(나)'를 뺌) */
    suspend fun recordPeople(recordId: String): List<String> =
        db.postgrest.rpc("wb_record_people", buildJsonObject { put("p_record_id", recordId) }).decodeAs<List<String>>()

    /**
     * 하위 할 일 넣기(FR-23a, DEC-101): 곳은 서버가 상위 것을 넣고, 끝난 상위면 서버가 다시 연다.
     * 보관된 곳의 할 일·하위 아래에는 서버가 거부한다(WB409·WB422)
     */
    suspend fun createSubtask(parent: TaskRow, e: TaskEdit) {
        val fields = e.copy(projectId = null, areaId = null, priority = null).toNewTaskFields()
        db.from("task").insert(JsonObject(fields + ("parent_id" to JsonPrimitive(parent.id))))
    }

    suspend fun task(id: String): TaskRow? =
        db.from("task").select { filter { eq("id", id) } }.decodeList<TaskRow>().firstOrNull()

    /**
     * 할 일 고치기(제목·우선순위·담당·기한·어디에). 버전이 같을 때만 바꾸고 바뀐 행을 돌려준다(다른 기기에서 먼저 바꿨으면 null, M18).
     * 수정 전후는 서버 트리거가 task_revision에 쌓는다. 보관된 곳으로 옮기면 서버가 WB409로 막는다
     */
    suspend fun updateTask(t: TaskRow, e: TaskEdit): TaskRow? {
        fun json(v: String?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
        return db.from("task").update(buildJsonObject {
            put("title", e.title)
            put("priority", e.priority?.let { JsonPrimitive(it) } ?: JsonNull)
            put("owner_kind", e.ownerKind)
            put("assignee", json(e.assignee))
            put("due_date", json(e.dueDate))
            put("due_time", json(e.dueTime))
            put("due_ambiguous", e.dueAmbiguous)
            put("due_text", json(e.dueText))
            put("project_id", json(e.projectId))
            put("area_id", json(e.areaId))
            put("recipients", JsonArray(e.recipients.map(::JsonPrimitive)))
            put("version", t.version + 1)
        }) {
            select()
            filter {
                eq("id", t.id)
                eq("version", t.version)
            }
        }.decodeList<TaskRow>().firstOrNull()
    }

    /**
     * 완료·완료 취소(DEC-64: 체크를 다시 누르면 할 일로 되돌림). 버전이 같을 때만 바꾼다(다른 기기에서 먼저 바꿨으면 0건, M18).
     * 완료 시각은 서버 트리거가 상태를 보고 채우거나 비운다
     */
    suspend fun setTaskDone(t: TaskRow, done: Boolean): Boolean =
        db.from("task").update({
            set("status", if (done) "done" else "open")
            set("version", t.version + 1)
        }) {
            select()
            filter {
                eq("id", t.id)
                eq("version", t.version)
            }
        }.decodeList<IdRow>().isNotEmpty()

    /**
     * 할 일 지우기(DEC-90: 밀어 지우기). 버전이 같을 때만 지운다(다른 기기에서 먼저 고쳤으면 0건 → false, M18).
     * RLS가 본인 행만 지우고, 변경 이력(task_revision)은 함께 지워진다(ON DELETE CASCADE)
     */
    suspend fun deleteTask(t: TaskRow): Boolean =
        db.from("task").delete {
            select()
            filter {
                eq("id", t.id)
                eq("version", t.version)
            }
        }.decodeList<IdRow>().isNotEmpty()

    // ── 구글 캘린더(FR-24a, DEC-105, Design §3.16): 처리 함수 `calendar`와 연결 상태 표(웹과 같은 함수·표) ──
    // 연결 열쇠는 서버(Vault)에만 있고, 앱은 연결 상태(calendar_link)와 할 일 ↔ 일정 연결(task_calendar)만 읽는다

    /** 내 연결 상태와 연결한 때. 줄이 없거나 끊었으면 OFF, Google이 열쇠를 거절했으면 BROKEN */
    suspend fun calendarStatus(): Pair<CalendarStatus, String?> {
        val row = db.from("calendar_link").select(Columns.list("connected_at", "broken_at")).decodeList<CalendarLinkRow>().firstOrNull()
        if (row?.connectedAt == null) return CalendarStatus.OFF to null
        return (if (row.brokenAt != null) CalendarStatus.BROKEN else CalendarStatus.ON) to row.connectedAt
    }

    /** 캘린더에 넣은 내 할 일 → '고쳤는데 아직 반영 안 됨'(dirty) */
    suspend fun calendarLinks(): Map<String, Boolean> =
        db.from("task_calendar").select(Columns.list("task_id", "dirty")).decodeList<TaskCalendarRow>().associate { it.taskId to it.dirty }

    /** 함수가 기대한 실패를 200 + reason으로 돌려주면 CalendarError로 바꾼다 */
    private suspend fun calendarCall(body: JsonObject): JsonObject {
        val res = try {
            invokeFunction("calendar", body)
        } catch (e: RestException) {
            throw CalendarError("failed", e)
        }
        val json = runCatching { Json.parseToJsonElement(res.bodyAsText()) as JsonObject }.getOrElse { throw CalendarError("failed", it) }
        json["reason"]?.jsonPrimitive?.contentOrNull?.let { throw CalendarError(it) }
        return json
    }

    /** Google 허락 화면 주소(1회용 확인값 포함, 10분). 허락 뒤 서버가 workbrain://calendar-callback 으로 돌려보낸다 */
    suspend fun calendarConnectUrl(): String =
        calendarCall(buildJsonObject { put("action", "connect"); put("return_to", "app") })["url"]?.jsonPrimitive?.contentOrNull
            ?: throw CalendarError("failed")

    /** 연결 끊기: Google에 열쇠 취소를 요청하고 서버 열쇠를 지운다. 넣은 일정·일정 연결은 남는다 */
    suspend fun calendarDisconnect() {
        calendarCall(buildJsonObject { put("action", "disconnect") })
    }

    /** 서울 날짜 from부터 days일(1~8)의 보여 줄 일정(취소·거절·WB가 넣은 일정은 서버가 뺌) */
    suspend fun calendarEvents(from: LocalDate, days: Int): List<CalEvent> {
        val json = calendarCall(buildJsonObject { put("action", "events"); put("from", from.toString()); put("days", days) })
        return planJson.decodeFromJsonElement(ListSerializer(CalEvent.serializer()), json["events"] ?: JsonArray(emptyList()))
    }

    /** 할 일을 일정으로 넣기(이미 넣었으면 고치기). 기한 날짜가 없으면 no-due */
    suspend fun calendarInsert(taskId: String) {
        calendarCall(buildJsonObject { put("action", "insert"); put("task_id", taskId) })
    }

    /** 제목·기한을 고친 할 일(dirty)을 캘린더에 반영 */
    suspend fun calendarSync() {
        calendarCall(buildJsonObject { put("action", "sync") })
    }

    /** 일정 연결 끊기. deleteEvent면 WB가 넣은 일정도 지운다(다른 일정은 서버가 지키며 건너뜀) */
    suspend fun calendarRemove(taskIds: List<String>, deleteEvent: Boolean) {
        calendarCall(buildJsonObject {
            put("action", "remove")
            put("task_ids", JsonArray(taskIds.map(::JsonPrimitive)))
            put("delete_event", deleteEvent)
        })
    }
}

@Serializable private data class CalendarLinkRow(
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("broken_at") val brokenAt: String? = null,
)

@Serializable private data class TaskCalendarRow(@SerialName("task_id") val taskId: String, val dirty: Boolean = false)
