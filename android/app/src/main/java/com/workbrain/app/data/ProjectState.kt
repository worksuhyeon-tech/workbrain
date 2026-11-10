package com.workbrain.app.data

import com.workbrain.app.Supa
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * 프로젝트 현재 상태(FR-09, DEC-86, 서버 0037): 프로젝트 전체가 지금 어디쯤인지 적은 짧은 메모.
 * 요약 2~3줄 + 상태(진행 중 active · 멈춤 paused · 완료 done). 버전으로 쌓이고, 바꾸는 길은 둘뿐이다:
 * AI 제안을 승인(approve_proposal) · 직접 쓰기(wb_set_project_state). 둘 다 사용자가 본 버전을 함께 보내 충돌을 막는다
 */

/** 현재 상태 버전 한 줄(변경 이력에도 쓴다). appliedBy: user(직접 수정) · ai-approved(AI 제안 승인) */
@Serializable data class StateVersionRow(
    val version: Int,
    val summary: String? = null,
    val status: String,
    @SerialName("applied_by") val appliedBy: String,
    @SerialName("change_reason") val changeReason: String? = null,
    @SerialName("basis_at") val basisAt: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable data class StateQuote(val quote: String? = null)

/**
 * 승인 안 한(또는 처리한) 현재 상태 제안. 프로젝트마다 대기 제안은 하나다(새 기록을 연결하면 서버가 고쳐 쓰고 revision을 올림).
 * after: summary·status·past(과거 회의 기준)·record_title·record_at, before: 제안 때의 지금 상태(없으면 null)
 */
@Serializable data class StateProposalRow(
    val id: String,
    val status: String,
    val revision: Int,
    @SerialName("target_id") val projectId: String,
    @SerialName("base_version") val baseVersion: Int? = null,
    @SerialName("before_json") val before: JsonObject? = null,
    @SerialName("after_json") val after: JsonObject,
    val rationale: String,
    @SerialName("evidence_link") val evidence: List<StateQuote> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
) {
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    val summary: String get() = after.text("summary").orEmpty()
    val stateStatus: String get() = after.text("status") ?: "active"
    val beforeSummary: String? get() = before?.text("summary")
    val beforeStatus: String? get() = before?.text("status")
    /** 지금 상태의 근거보다 예전 회의로 만든 제안(DEC-86 ⑥) */
    val past: Boolean get() = after["past"]?.jsonPrimitive?.booleanOrNull == true
    val recordTitle: String? get() = after.text("record_title")
    val recordAt: String? get() = after.text("record_at")
    /** 반영할 기록 수(대기 제안이 여러 회의를 합쳤는지) */
    val recordCount: Int get() = (after["records"] as? JsonArray)?.size ?: 0
    /** 가장 최근에 더한 근거 문장 하나(화면에 작게) */
    val quote: String? get() = evidence.lastOrNull { !it.quote.isNullOrBlank() }?.quote
    /** 제안을 만든 뒤 사용자가 직접 고쳤거나 다른 제안을 승인해 기준 버전이 달라졌는지(승인하면 충돌로 돌아감) */
    fun staleAgainst(currentVersion: Int): Boolean = baseVersion != null && baseVersion != currentVersion
}

/** 상태 값 → 화면 말(목업 칩) */
fun stateLabel(status: String?): String = when (status) {
    "paused" -> "멈춤"
    "done" -> "완료"
    else -> "진행 중"
}

object ProjectState {
    private val db get() = Supa.client
    private const val PROPOSAL_COLUMNS =
        "id,status,revision,target_id,base_version,before_json,after_json,rationale,created_at,evidence_link(quote)"

    /** 지금 상태(가장 높은 버전). 아직 없으면 null */
    suspend fun current(projectId: String): StateVersionRow? =
        db.from("project_state_version").select {
            filter { eq("project_id", projectId) }
            order("version", Order.DESCENDING)
            limit(1)
        }.decodeList<StateVersionRow>().firstOrNull()

    /** 변경 이력: 최근 것부터 30개 */
    suspend fun history(projectId: String): List<StateVersionRow> =
        db.from("project_state_version").select {
            filter { eq("project_id", projectId) }
            order("version", Order.DESCENDING)
            limit(30)
        }.decodeList<StateVersionRow>()

    /** 이 프로젝트의 승인 안 한 현재 상태 제안(없으면 null) */
    suspend fun pending(projectId: String): StateProposalRow? =
        db.from("change_proposal").select(Columns.raw(PROPOSAL_COLUMNS)) {
            filter { eq("target_type", "project_state"); eq("target_id", projectId); eq("status", "pending") }
            limit(1)
        }.decodeList<StateProposalRow>().firstOrNull()

    /** 검토함: 모든 프로젝트의 대기 제안(오래된 것부터) */
    suspend fun pendingAll(): List<StateProposalRow> =
        db.from("change_proposal").select(Columns.raw(PROPOSAL_COLUMNS)) {
            filter { eq("target_type", "project_state"); eq("status", "pending") }
            order("created_at", Order.ASCENDING)
        }.decodeList<StateProposalRow>()

    /**
     * 직접 쓰기·고치기(DEC-86 ⑧). baseVersion = 화면이 본 버전(없으면 0).
     * 결과: saved · conflict(다른 기기에서 먼저 바뀜). 상태·요약이 틀리면 서버가 WB400으로 거부한다
     */
    suspend fun save(projectId: String, summary: String, status: String, baseVersion: Int): String =
        db.postgrest.rpc("wb_set_project_state", buildJsonObject {
            put("p_project_id", projectId); put("p_summary", summary); put("p_status", status); put("p_base_version", baseVersion)
        }).decodeAs<JsonObject>()["result"]?.jsonPrimitive?.content ?: ""

    /** 제안 반영·거절. 상세 카드에서 눌러도 검토함과 같은 전후 비교라 위치는 inbox로 남긴다. 결과: approved·conflict·stale-revision 등 */
    suspend fun approve(p: StateProposalRow): String =
        db.postgrest.rpc("approve_proposal", buildJsonObject {
            put("p_proposal_id", p.id); put("p_seen_revision", p.revision); put("p_context", "inbox")
        }).decodeAs<JsonObject>()["result"]?.jsonPrimitive?.content ?: ""

    suspend fun reject(p: StateProposalRow): String =
        db.postgrest.rpc("reject_proposal", buildJsonObject {
            put("p_proposal_id", p.id); put("p_seen_revision", p.revision); put("p_context", "inbox")
        }).decodeAs<JsonObject>()["result"]?.jsonPrimitive?.content ?: ""
}
