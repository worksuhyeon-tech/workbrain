package com.workbrain.app.data

import com.workbrain.app.Supa
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Collator
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/*
 * PARA 트리(FR-02, DEC-22·24): 업무 공간 → 1. 프로젝트 / 2. 반복실행 / 3. 관심영역(자료) / 4. 아카이브.
 * 아카이브는 따로 있는 표가 아니라 각 항목의 archived_at이다(보관해도 ID·이력 유지, Design §4.2).
 * 보관된 항목은 서버가 읽기 전용으로 막는다(복원 후 고치기, 마이그레이션 0030·0031).
 */

@Serializable data class WorkspaceRow(val id: String, val name: String, val description: String? = null)

@Serializable data class ProjectRow(
    val id: String,
    val name: String,
    val purpose: String? = null,
    @SerialName("area_id") val areaId: String? = null,
    val version: Int,
    @SerialName("archived_at") val archivedAt: String? = null,
) {
    val archived: Boolean get() = archivedAt != null
}

/** 반복실행. description = 목업 '무엇을 계속 맡는 일인지' 한 줄(0031) */
@Serializable data class AreaRow(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
) {
    val archived: Boolean get() = archivedAt != null
}

/**
 * 자료: 링크·짧은 메모·기존 기록 참조(M15). 링크·메모는 이름·종류만으로 먼저 만들고 내용은 '수정'에서 채운다(목업, 0031).
 * 기록 참조(record-ref)는 만들 때 기록이 필수이고 나중에 바꾸지 못한다(DEC-71). 메모(note)는 '어느 부분을 왜 참고하는지'
 */
@Serializable data class ResourceRow(
    val id: String,
    val kind: String,
    val title: String,
    val url: String? = null,
    val note: String? = null,
    val version: Int,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("source_record_id") val sourceRecordId: String? = null,
    /** 트리를 읽을 때만 함께 읽는 담은 기록(못 읽으면 null) */
    @SerialName("source_record") val sourceRecord: RecordBriefRow? = null,
) {
    val archived: Boolean get() = archivedAt != null
    val isRecordRef: Boolean get() = kind == RECORD_REF
    val kindLabel: String get() = when (kind) { "link" -> "링크"; "note" -> "메모"; else -> "기록 참조" }
    val body: String? get() = (if (kind == "link") url else note)?.takeIf { it.isNotBlank() }
    val record: RecordBrief? get() = sourceRecord?.toBrief()

    companion object {
        const val RECORD_REF = "record-ref"
    }
}

@Serializable data class ResourceLinkRow(
    @SerialName("resource_id") val resourceId: String,
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String,
)

/** 할 일이 속하는 곳. 서버의 task.project_id / task.area_id 중 하나(둘 다 없으면 '정리 안 됨') */
enum class PlaceType(val table: String) { PROJECT("project"), AREA("area") }

data class Place(val type: PlaceType, val id: String, val name: String)

/**
 * 기록 한 건 요약: 트리 하위 기록·'관련 기록' 카드·기록 참조 자료·기록 고르기가 같이 쓴다.
 * at = 회의 시각(모르면 올린 시각), durationSec = 음성 길이(받아쓴 글은 없음)
 */
data class RecordBrief(val id: String, val title: String, val at: String?, val channel: String?, val durationSec: Int?)

/** 프로젝트에 연결된 기록 하나(DEC-69, 0032 기록 전체 연결) */
data class LinkedRecord(val projectId: String, val record: RecordBrief) {
    val recordId: String get() = record.id
    val title: String get() = record.title
    val at: String? get() = record.at
    val channel: String? get() = record.channel
}

/** PostgREST가 돌려주는 기록 요약 행(source_record + 음성 길이) */
@Serializable data class RecordBriefRow(
    val id: String,
    val title: String? = null,
    val channel: String? = null,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("ingested_at") val ingestedAt: String? = null,
    @SerialName("audio_asset") val audio: JsonElement? = null,
) {
    fun toBrief() = RecordBrief(id, title ?: "제목 없는 기록", occurredAt ?: ingestedAt, channel, durationOf(audio))
}

private const val RECORD_BRIEF_COLUMNS = "id,title,channel,occurred_at,ingested_at,audio_asset(duration_sec)"

/** 트리·상세를 그리는 데 필요한 것 전부. 할 일은 완료한 것까지(목업 '완료 포함'·프로젝트 할 일 카드) */
data class ParaData(
    val workspace: WorkspaceRow,
    val projects: List<ProjectRow>,
    val areas: List<AreaRow>,
    val resources: List<ResourceRow>,
    val links: List<ResourceLinkRow>,
    val tasks: List<TaskRow>,
    /** 할 일 → 출처 한 줄('AI 제안 승인 · 회의 이름 10/14'). 없으면 직접 추가 */
    val sources: Map<String, String>,
    /** 프로젝트에 연결된 기록(확정된 기록 전체 연결만) */
    val recordLinks: List<LinkedRecord> = emptyList(),
) {
    /** 이 프로젝트의 기록, 최근 회의부터 */
    fun recordsOf(projectId: String): List<LinkedRecord> =
        recordLinks.filter { it.projectId == projectId }.sortedByDescending { it.at.orEmpty() }

    val activeProjects get() = projects.filterNot { it.archived }
    val activeAreas get() = areas.filterNot { it.archived }
    val activeResources get() = resources.filterNot { it.archived }

    fun project(id: String?) = projects.firstOrNull { it.id == id }
    fun area(id: String?) = areas.firstOrNull { it.id == id }
    fun resource(id: String?) = resources.firstOrNull { it.id == id }

    /** 할 일을 옮길 수 있는 곳: 보관되지 않은 프로젝트, 그다음 반복실행 */
    val places: List<Place> get() =
        activeProjects.map { Place(PlaceType.PROJECT, it.id, it.name) } + activeAreas.map { Place(PlaceType.AREA, it.id, it.name) }

    /** 보관된 프로젝트·반복실행의 할 일은 '모든 할 일'과 '오늘'에서 빠진다(목업 S07·S02, 복원하면 다시 보임, DEC-65) */
    fun inArchived(t: TaskRow): Boolean = project(t.projectId)?.archived == true || area(t.areaId)?.archived == true

    /** '모든 할 일'에 올라가는 할 일(완료 포함). 보관된 곳의 것은 뺀다 */
    val liveTasks: List<TaskRow> get() = tasks.filterNot(::inArchived)

    fun openCount(type: PlaceType, id: String): Int = tasksOf(type, id).count { !it.done }

    fun tasksOf(type: PlaceType, id: String): List<TaskRow> =
        tasks.filter { if (type == PlaceType.PROJECT) it.projectId == id else it.areaId == id }

    /** 속한 곳을 못 찾는 할 일(어디에도 없음·다른 기기에서 막 지운 곳)은 '정리 안 됨' */
    fun unsorted(t: TaskRow): Boolean = project(t.projectId) == null && area(t.areaId) == null

    fun resourcesOf(type: PlaceType, id: String): List<ResourceRow> {
        val ids = links.filter { it.targetType == type.table && it.targetId == id }.map { it.resourceId }.toSet()
        return resources.filter { it.id in ids }
    }

    /** 자료가 연결된 곳(이름은 지금 목록에서 찾는다) */
    fun placesOf(resourceId: String): List<Place> = links.filter { it.resourceId == resourceId }.mapNotNull { l ->
        when (l.targetType) {
            PlaceType.PROJECT.table -> project(l.targetId)?.let { Place(PlaceType.PROJECT, it.id, it.name) }
            PlaceType.AREA.table -> area(l.targetId)?.let { Place(PlaceType.AREA, it.id, it.name) }
            else -> null
        }
    }
}

// 할 일 출처: 승인 이력(task_revision, actor=ai-approved) → 승인 → 후보 → 분석 → 전사 → 기록
@Serializable private data class SrcRecord(val title: String? = null, @SerialName("occurred_at") val occurredAt: String? = null, @SerialName("ingested_at") val ingestedAt: String? = null)
@Serializable private data class SrcTranscript(@SerialName("source_record") val record: SrcRecord? = null)
@Serializable private data class SrcRun(@SerialName("transcript_version") val transcript: SrcTranscript? = null)
@Serializable private data class SrcProposal(@SerialName("analysis_run") val run: SrcRun? = null)
@Serializable private data class SrcEvent(@SerialName("change_proposal") val proposal: SrcProposal? = null)
@Serializable private data class SrcRevision(@SerialName("task_id") val taskId: String, @SerialName("approval_event") val event: SrcEvent? = null)

private const val SOURCE_COLUMNS =
    "task_id,approval_event(change_proposal(analysis_run(transcript_version(source_record(title,occurred_at,ingested_at)))))"

// 기록↔프로젝트 연결(0032): 확정된 것만 읽고, 기록 전체 연결(item_ref.scope = record)만 쓴다(항목 단위 AI 연결은 FR-06 때)
@Serializable private data class RecordLinkRow(
    @SerialName("project_id") val projectId: String,
    @SerialName("item_ref") val itemRef: JsonObject,
    @SerialName("source_record") val record: RecordBriefRow? = null,
)
private const val RECORD_LINK_COLUMNS = "project_id,item_ref,source_record($RECORD_BRIEF_COLUMNS)"

private val RecordLinkRow.isRecordScope: Boolean
    get() = (itemRef["scope"] as? JsonPrimitive)?.content == "record"

// 기록당 음성은 하나라 PostgREST가 객체나 한 칸짜리 배열로 준다
private fun durationOf(audio: JsonElement?): Int? {
    val obj = (audio as? JsonObject) ?: ((audio as? JsonArray)?.firstOrNull() as? JsonObject) ?: return null
    return (obj["duration_sec"] as? JsonPrimitive)?.content?.toIntOrNull()
}

object Para {
    private val db get() = Supa.client
    private val seoul = ZoneId.of("Asia/Seoul")

    // 같은 칸 안은 이름순(사용자가 '01. …'처럼 번호를 붙여 순서를 정할 수 있게, DEC-65). 한글 사전 순서
    private val byName = Collator.getInstance(Locale.KOREAN)

    suspend fun load(): ParaData = coroutineScope {
        val workspace = async {
            db.from("workspace").select { order("created_at", Order.ASCENDING); limit(1) }
                .decodeList<WorkspaceRow>().firstOrNull() ?: error("기본 공간이 없어요. 다시 로그인해 주세요")
        }
        val projects = async { db.from("project").select().decodeList<ProjectRow>().sortedWith(compareBy(byName) { it.name }) }
        val areas = async { db.from("area").select().decodeList<AreaRow>().sortedWith(compareBy(byName) { it.name }) }
        val resources = async {
            db.from("resource").select(Columns.raw("*,source_record($RECORD_BRIEF_COLUMNS)")).decodeList<ResourceRow>().sortedWith(compareBy(byName) { it.title })
        }
        val links = async { db.from("resource_link").select().decodeList<ResourceLinkRow>() }
        val tasks = async { db.from("task").select { order("created_at", Order.DESCENDING) }.decodeList<TaskRow>() }
        // 출처·연결된 기록은 보조 정보라 못 읽어도 화면은 그린다
        val sources = async { runCatching { sources() }.getOrDefault(emptyMap()) }
        val recordLinks = async { runCatching { recordLinks() }.getOrDefault(emptyList()) }
        ParaData(
            workspace.await(), projects.await(), areas.await(), resources.await(), links.await(), tasks.await(), sources.await(),
            recordLinks.await(),
        )
    }

    /** 프로젝트에 확정 연결된 기록 전부(트리 하위 기록·관련 기록 카드) */
    suspend fun recordLinks(): List<LinkedRecord> =
        db.from("source_project_link").select(Columns.raw(RECORD_LINK_COLUMNS)) { filter { eq("status", "confirmed") } }
            .decodeList<RecordLinkRow>()
            .filter { it.isRecordScope }
            .mapNotNull { l -> l.record?.let { LinkedRecord(l.projectId, it.toBrief()) } }

    /** 기록 고르기(기록 참조 자료): 보관 안 된 내 기록, 최근 회의부터 */
    suspend fun records(): List<RecordBrief> =
        db.from("source_record").select(Columns.raw(RECORD_BRIEF_COLUMNS)) { filter { exact("archived_at", null) } }
            .decodeList<RecordBriefRow>()
            .map { it.toBrief() }
            .sortedByDescending { it.at.orEmpty() }

    /** 프로젝트 전부(보관된 것 포함, 이름순). 기록 상세가 연결된 프로젝트 이름과 연결할 후보를 고를 때 */
    suspend fun allProjects(): List<ProjectRow> =
        db.from("project").select().decodeList<ProjectRow>().sortedWith(compareBy(byName) { it.name })

    /** 이 기록이 연결된 프로젝트 ID(기록 상세 머리 칩) */
    suspend fun projectsOfRecord(recordId: String): List<String> =
        db.from("source_project_link").select(Columns.raw("project_id,item_ref")) {
            filter { eq("source_record_id", recordId); eq("status", "confirmed") }
        }.decodeList<RecordLinkRow>().filter { it.isRecordScope }.map { it.projectId }

    /**
     * 기록 전체를 프로젝트에 연결(linked = true) 또는 해제. 결과: linked · unlinked · unchanged.
     * 아카이브된 프로젝트·남의 것·다른 공간은 서버가 거부한다(WB409·WB404·WB422)
     */
    suspend fun linkRecord(recordId: String, projectId: String, linked: Boolean): String =
        db.postgrest.rpc("wb_link_record_project", buildJsonObject {
            put("p_record_id", recordId); put("p_project_id", projectId); put("p_linked", linked)
        }).decodeAs<JsonPrimitive>().content

    /**
     * 트리에서 기록을 다른 프로젝트로 끌어 놓기(DEC-24·72): 새 곳 연결과 옛 곳 해제를 서버가 한 번에 한다(0032).
     * 새 곳이 아카이브됐거나 남의 것이면 거부되고 옛 연결은 그대로 남는다. 결과: moved · unchanged
     */
    suspend fun moveRecord(recordId: String, fromProjectId: String, toProjectId: String): String =
        db.postgrest.rpc("wb_move_record_project", buildJsonObject {
            put("p_record_id", recordId); put("p_from_project_id", fromProjectId); put("p_to_project_id", toProjectId)
        }).decodeAs<JsonPrimitive>().content

    /** 할 일 → 출처 한 줄(오늘·프로젝트 화면 공용). AI 승인으로 생긴 할 일만 있고, 나머지는 직접 추가 */
    suspend fun sources(): Map<String, String> =
        db.from("task_revision").select(Columns.raw(SOURCE_COLUMNS)) { filter { eq("actor", "ai-approved") } }
            .decodeList<SrcRevision>()
            .mapNotNull { r ->
                val rec = r.event?.proposal?.run?.transcript?.record ?: return@mapNotNull null
                val day = (rec.occurredAt ?: rec.ingestedAt)?.let {
                    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(seoul).let { z -> "${z.monthValue}/${z.dayOfMonth}" } }.getOrNull()
                }
                // 목업 표기: 'AI 제안 승인 · IT 개발팀 통화 10/14'
                r.taskId to "AI 제안 승인 · ${rec.title ?: "기록"}${day?.let { " $it" } ?: ""}"
            }
            .toMap()

    /** '오늘'의 할 일 고치기 서랍에서 고를 곳(보관되지 않은 프로젝트·반복실행) */
    suspend fun places(): List<Place> = coroutineScope {
        val projects = async { db.from("project").select { filter { exact("archived_at", null) } }.decodeList<ProjectRow>() }
        val areas = async { db.from("area").select { filter { exact("archived_at", null) } }.decodeList<AreaRow>() }
        projects.await().sortedWith(compareBy(byName) { it.name }).map { Place(PlaceType.PROJECT, it.id, it.name) } +
            areas.await().sortedWith(compareBy(byName) { it.name }).map { Place(PlaceType.AREA, it.id, it.name) }
    }

    // ── 만들기(소유자는 DB 기본값 auth.uid). 트리 칸 안의 입력 줄에서 이름(자료는 종류도)만 받는다(목업) ──

    suspend fun createProject(workspaceId: String, name: String): ProjectRow =
        db.from("project").insert(buildJsonObject { put("workspace_id", workspaceId); put("name", name) }) { select() }.decodeSingle()

    suspend fun createArea(workspaceId: String, name: String): AreaRow =
        db.from("area").insert(buildJsonObject { put("workspace_id", workspaceId); put("name", name) }) { select() }.decodeSingle()

    /** recordId는 기록 참조일 때만(서버가 내 기록인지 확인하고, 기록 없는 기록 참조는 거부한다) */
    suspend fun createResource(workspaceId: String, kind: String, title: String, recordId: String? = null): ResourceRow =
        db.from("resource").insert(buildJsonObject {
            put("workspace_id", workspaceId); put("kind", kind); put("title", title)
            recordId?.let { put("source_record_id", it) }
        }) { select() }.decodeSingle()

    // ── 다시 읽기(되돌리기는 지금 버전을 읽어 그 위에 고친다) ──

    suspend fun project(id: String): ProjectRow? = db.from("project").select { filter { eq("id", id) } }.decodeList<ProjectRow>().firstOrNull()
    suspend fun area(id: String): AreaRow? = db.from("area").select { filter { eq("id", id) } }.decodeList<AreaRow>().firstOrNull()
    suspend fun resource(id: String): ResourceRow? = db.from("resource").select { filter { eq("id", id) } }.decodeList<ResourceRow>().firstOrNull()

    // ── 고치기: 프로젝트·자료는 버전이 같을 때만(다른 기기에서 먼저 고쳤으면 null, M18) ──

    /** 목업 [수정]: 이름·반복실행·목적을 한 번에 */
    suspend fun saveProject(p: ProjectRow, name: String, areaId: String?, purpose: String?) = patchProject(p, buildJsonObject {
        put("name", name); put("area_id", json(areaId)); put("purpose", json(purpose))
    })
    /** 목업 '목적' 점선 상자에서 바로 저장 */
    suspend fun setPurpose(p: ProjectRow, purpose: String?) = patchProject(p, buildJsonObject { put("purpose", json(purpose)) })
    suspend fun setProjectArchived(p: ProjectRow, archived: Boolean) = patchProject(p, archivedPatch(archived))

    private suspend fun patchProject(p: ProjectRow, fields: JsonObject): ProjectRow? =
        db.from("project").update(JsonObject(fields + ("version" to JsonPrimitive(p.version + 1)))) {
            select()
            filter { eq("id", p.id); eq("version", p.version) }
        }.decodeList<ProjectRow>().firstOrNull()

    /** 반복실행은 버전이 없다(이름·설명·보관만 바뀌어 겹쳐 고쳐도 잃을 내용이 적다) */
    suspend fun saveArea(a: AreaRow, name: String, description: String?) = patchArea(a, buildJsonObject {
        put("name", name); put("description", json(description))
    })
    suspend fun setAreaArchived(a: AreaRow, archived: Boolean) = patchArea(a, archivedPatch(archived))

    private suspend fun patchArea(a: AreaRow, fields: JsonObject): AreaRow? =
        db.from("area").update(fields) { select(); filter { eq("id", a.id) } }.decodeList<AreaRow>().firstOrNull()

    suspend fun editResource(r: ResourceRow, title: String, url: String?, note: String?) = patchResource(r, buildJsonObject {
        put("title", title); put("url", json(url)); put("note", json(note))
    })
    suspend fun setResourceArchived(r: ResourceRow, archived: Boolean) = patchResource(r, archivedPatch(archived))

    private suspend fun patchResource(r: ResourceRow, fields: JsonObject): ResourceRow? =
        db.from("resource").update(JsonObject(fields + ("version" to JsonPrimitive(r.version + 1)))) {
            select()
            filter { eq("id", r.id); eq("version", r.version) }
        }.decodeList<ResourceRow>().firstOrNull()

    private fun json(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    /** 보관 시각은 서버가 서버 시각으로 바꿔 넣는다. 여기 값은 '보관함' 표시일 뿐 */
    private fun archivedPatch(archived: Boolean) = buildJsonObject {
        put("archived_at", if (archived) JsonPrimitive(Instant.now().toString()) else JsonNull)
    }

    // ── 자료 연결(목업 '연결 편집': 고른 곳만 남긴다 / 트리에서 자료를 끌어 놓으면 하나 더하기, DEC-72) ──

    /** 지금 연결과 고른 곳을 비교해 더하고 뺀다. 바뀐 게 없으면 아무것도 안 한다 */
    suspend fun setLinks(resourceId: String, current: List<Place>, wanted: List<Place>) {
        val now = current.map { it.type to it.id }.toSet()
        val next = wanted.map { it.type to it.id }.toSet()
        (next - now).forEach { (type, id) -> link(resourceId, type, id) }
        (now - next).forEach { (type, id) -> unlink(resourceId, type, id) }
    }

    /** 아카이브된 곳·보관된 자료에 잇기는 서버가 WB409로 막는다(0030) */
    suspend fun link(resourceId: String, type: PlaceType, targetId: String) {
        db.from("resource_link").insert(buildJsonObject {
            put("resource_id", resourceId); put("target_type", type.table); put("target_id", targetId)
        })
    }

    suspend fun unlink(resourceId: String, type: PlaceType, targetId: String) {
        db.from("resource_link").delete {
            filter { eq("resource_id", resourceId); eq("target_type", type.table); eq("target_id", targetId) }
        }
    }
}
