package com.workbrain.app.search

import com.workbrain.app.data.SearchRow
import com.workbrain.app.ui.Tone
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/*
 * 검색(FR-13, DEC-98) 화면 밖 규칙: 검색어 다듬기·강조 구간·필터 값·결과 나누기·누르면 갈 곳.
 * 화면(SearchScreen)과 PC 시험(SearchQueryTest)이 같이 쓴다. 서버 규칙(0039 wb_search)과 같게 맞춘다
 */

/** 서버와 같은 규칙: 두 글자 미만이면 찾지 않고, 100자까지 */
const val SEARCH_MIN = 2
const val SEARCH_MAX = 100

/** 앞뒤 공백·줄바꿈을 빼고 100자까지(잘린 끝이 이모지 반쪽이면 그 반쪽도 뺀다) */
fun cleanQuery(raw: String): String {
    val t = raw.trim().take(SEARCH_MAX)
    return if (t.isNotEmpty() && t.last().isHighSurrogate()) t.dropLast(1) else t
}

fun canSearch(raw: String): Boolean = cleanQuery(raw).length >= SEARCH_MIN

/** 글에서 검색어가 나온 곳(대소문자 무시, 겹치지 않게 앞에서부터). 강조 표시에 쓴다 */
fun matchRanges(text: String, raw: String): List<IntRange> {
    val needle = cleanQuery(raw)
    if (needle.length < SEARCH_MIN || text.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length - needle.length) {
        val i = text.indexOf(needle, from, ignoreCase = true)
        if (i < 0) break
        out += i until i + needle.length
        from = i + needle.length
    }
    return out
}

/** 기간 칩(처음 값 전체, DEC-98) */
enum class SearchPeriod(val code: String, val label: String, private val days: Long?) {
    ALL("all", "전체", null),
    MONTH("30", "최근 30일", 30),
    WEEK("7", "최근 7일", 7);

    fun since(now: Instant): Instant? = days?.let { now.minus(Duration.ofDays(it)) }

    companion object {
        fun of(code: String): SearchPeriod = entries.firstOrNull { it.code == code } ?: ALL
    }
}

/** 종류 칩. 할 일 후보(검토 대기)는 '할 일'에 함께 넣는다. serverKinds가 null이면 모든 종류 */
enum class SearchKind(val code: String, val label: String, val serverKinds: List<String>?) {
    ALL("all", "전체", null),
    TASK("task", "할 일", listOf(KIND_TASK, KIND_PROPOSAL)),
    MINUTES("record", "회의록", listOf(KIND_RECORD)),
    RESOURCE("resource", "자료", listOf(KIND_RESOURCE));

    companion object {
        fun of(code: String): SearchKind = entries.firstOrNull { it.code == code } ?: ALL
    }
}

const val KIND_TASK = "task"
const val KIND_RECORD = "record"
const val KIND_RESOURCE = "resource"
const val KIND_PROPOSAL = "proposal"

/**
 * 위에 보일 결과(최근 것부터, 종류를 섞어)와 아래에 접어 둘 검토 대기 할 일 후보.
 * 승인 전 회의록·AI가 뽑은 결정·미결로 찾은 기록은 위 결과에 '초안' 칩으로 함께 둔다(2026-11-11 사용자 결정: 회의록 대부분이 승인 전이라)
 */
class SearchResults(val shown: List<SearchRow>, val candidates: List<SearchRow>)

private fun instantOf(at: String?): Instant? = at?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/**
 * 서버 결과를 화면용으로 나눈다(할 일 후보만 아래로). 기록 탭 휴지통은 기기에만 있어서(DEC-51) 서버는 모르므로,
 * 이 기기 휴지통에 넣은 기록과 그 기록에서 나온 후보는 여기서 뺀다
 */
fun splitResults(rows: List<SearchRow>, trashedRecordIds: Set<String>): SearchResults {
    val shown = rows
        .filterNot { (it.kind == KIND_RECORD || it.kind == KIND_PROPOSAL) && it.recordId in trashedRecordIds }
        .sortedByDescending { instantOf(it.at) ?: Instant.MIN }
    val isCandidate = { r: SearchRow -> r.kind == KIND_PROPOSAL }
    return SearchResults(shown.filterNot(isCandidate), shown.filter(isCandidate))
}

/** 결과가 없을 때 넓혀 볼 버튼 */
enum class Relax(val label: String) {
    PERIOD("기간을 전체로"),
    PLACE("모든 프로젝트에서"),
    CANDIDATES("할 일 후보도 보기"),
}

/** 위 결과가 없을 때 보여 줄 넓히기 버튼: 걸린 필터만, 접어 둔 할 일 후보가 있으면 후보 보기 */
fun relaxOptions(period: SearchPeriod, placeFiltered: Boolean, candidatesOpen: Boolean, candidateCount: Int): List<Relax> = buildList {
    if (period != SearchPeriod.ALL) add(Relax.PERIOD)
    if (placeFiltered) add(Relax.PLACE)
    if (!candidatesOpen && candidateCount > 0) add(Relax.CANDIDATES)
}

private val SEOUL_ZONE = java.time.ZoneId.of("Asia/Seoul")

/** 카드 메타 줄의 날짜(서울): 회의록·후보는 회의 날 'M/D', 할 일·자료는 'M/D 만듦'. 시각이 없으면 null */
fun metaDate(row: SearchRow): String? {
    val day = instantOf(row.at)?.atZone(SEOUL_ZONE)?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: return null
    return if (row.kind == KIND_TASK || row.kind == KIND_RESOURCE) "$day 만듦" else day
}

/** 결과 카드 종류 칩 색(11/12 사용자): 할 일·후보 파랑, 회의록 보라, 자료 베이지(경고 톤 — 예전 '초안' 칩 색) */
fun kindTone(kind: String): Tone = when (kind) {
    KIND_RECORD -> Tone.Purple
    KIND_RESOURCE -> Tone.Warn
    else -> Tone.Info
}

/** 결과 카드 종류 칩 이름 */
fun kindLabel(kind: String): String = when (kind) {
    KIND_TASK -> "할 일"
    KIND_PROPOSAL -> "할 일 후보"
    KIND_RECORD -> "회의록"
    KIND_RESOURCE -> "자료"
    else -> "기타"
}

/** 누르면 갈 곳 */
sealed interface SearchTarget {
    /** 기록 상세(회의록·기록·후보) */
    data class Record(val recordId: String, val title: String) : SearchTarget
    /** 프로젝트 탭의 그 항목('p:ID'·'a:ID'·'r:ID', 정리 안 된 할 일은 'all'). focusTaskId: 그 상세에서 찾아 칠할 할 일 */
    data class Projects(val key: String, val focusTaskId: String? = null) : SearchTarget
}

/**
 * 할 일 → 속한 프로젝트·반복실행 상세(사용자 결정, 정리 안 됨이면 모든 할 일)로 가서 그 줄을 칠한다, 자료 → 그 자료,
 * 회의록·후보 → 기록 상세. 기록 ID가 없는 줄은 갈 곳이 없다(null)
 */
fun targetOf(row: SearchRow): SearchTarget? = when (row.kind) {
    KIND_TASK -> SearchTarget.Projects(row.projectId?.let { "p:$it" } ?: row.areaId?.let { "a:$it" } ?: "all", focusTaskId = row.id)
    KIND_RESOURCE -> SearchTarget.Projects("r:${row.id}")
    KIND_RECORD, KIND_PROPOSAL -> row.recordId?.let { SearchTarget.Record(it, recordTitle(row)) }
    else -> null
}

/** 기록 상세 머리에 쓸 기록 제목(후보 줄은 그 후보가 나온 기록). 없으면 검토함과 같은 이름 */
fun recordTitle(row: SearchRow): String = row.recordTitle ?: "제목 없는 기록"
