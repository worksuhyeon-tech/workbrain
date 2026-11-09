package com.workbrain.app.data

import com.workbrain.app.Supa
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * AI 프로젝트 연결 제안(FR-06, DEC-75, Design §3.11).
 * 처리 함수가 기록 단위 제안을 source_project_link에 'proposed'로 남기고, 사용자가 [연결]하면 같은 행이 확정된다
 * (확인·거절은 Para.linkRecord = wb_link_record_project 그대로). 확정 전에는 트리·관련 기록·'추가할 곳'에 쓰지 않는다.
 */

/**
 * 기록 하나에 대한 연결 제안. reason = 왜 그 프로젝트인지 AI가 쓴 한 줄(link-v2, 11/09 이전 제안은 null),
 * quote = 가장 분명한 근거 문장 하나(없으면 null)
 */
data class LinkSuggestion(
    val recordId: String,
    val projectId: String,
    val confidence: String,
    val reasons: List<String>,
    val quote: String?,
    val reason: String? = null,
) {
    /** 확인이 필요한 이유 한 줄(확률 숫자는 보여 주지 않는다, §3.10). 확신이 높으면 null */
    val note: String? get() = suggestionNote(confidence, reasons)
}

/** 업무 · 개인 · 불확실(source_record.classification) */
enum class RecordClass(val value: String, val label: String) {
    WORK("work", "업무"), PERSONAL("personal", "개인"), UNCERTAIN("uncertain", "불확실");

    companion object {
        fun of(value: String?): RecordClass? = entries.firstOrNull { it.value == value }
    }
}

private val REASON_TEXT = mapOf(
    "name_not_mentioned" to "프로젝트 이름이 직접 나오지 않았어요",
    "brief_mention" to "짧게만 다뤘어요",
    "project_ambiguous" to "비슷한 프로젝트가 여럿이에요",
    "transcript_low_quality" to "받아쓰기가 불분명해요",
    "conflicting_evidence" to "다른 이야기와 섞여 있어요",
    "no_evidence" to "근거 문장을 찾지 못했어요",
)

/**
 * '확인 필요 · 이유' 한 줄. high면 null, 이유가 없으면 등급만으로(낮음·미평가) 짧게.
 * 모르는 이유 코드는 건너뛴다(서버가 나중에 코드를 늘려도 화면이 깨지지 않게)
 */
fun suggestionNote(confidence: String, reasons: List<String>): String? {
    if (confidence == "high") return null
    val texts = reasons.mapNotNull { REASON_TEXT[it] }.distinct()
    return when {
        texts.isNotEmpty() -> "확인 필요 · " + texts.joinToString(" · ")
        confidence == "low" -> "확인 필요 · 관련 있어 보이지만 확실하지 않아요"
        confidence == "unrated" -> "확인 필요 · AI가 확신 정도를 매기지 않았어요"
        else -> null
    }
}

@Serializable private data class QuoteRow(val quote: String? = null)

@Serializable private data class SuggestionRow(
    @SerialName("source_record_id") val recordId: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("item_ref") val itemRef: JsonObject,
    val confidence: String = "unrated",
    @SerialName("uncertainty_reasons") val reasons: List<String> = emptyList(),
    val rationale: String? = null,
    @SerialName("evidence_link") val evidence: List<QuoteRow> = emptyList(),
)

object LinkSuggest {
    private val db get() = Supa.client

    /** 아직 확인하지 않은 AI 제안(recordId를 주면 그 기록만), 먼저 만든 것부터 */
    suspend fun pending(recordId: String? = null): List<LinkSuggestion> =
        db.from("source_project_link")
            .select(Columns.raw("source_record_id,project_id,item_ref,confidence,uncertainty_reasons,rationale,evidence_link(quote)")) {
                filter {
                    eq("status", "proposed")
                    eq("created_by", "ai")
                    recordId?.let { eq("source_record_id", it) }
                }
                order("created_at", Order.ASCENDING)
            }
            .decodeList<SuggestionRow>()
            .filter { (it.itemRef["scope"] as? JsonPrimitive)?.content == "record" }
            .map {
                LinkSuggestion(
                    it.recordId, it.projectId, it.confidence, it.reasons,
                    quote = it.evidence.firstNotNullOfOrNull { e -> e.quote },
                    reason = it.rationale?.takeIf { r -> r.isNotBlank() },
                )
            }
}
