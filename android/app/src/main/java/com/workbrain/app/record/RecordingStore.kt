package com.workbrain.app.record

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 기기 안 녹음 보관: files/recordings/<세션ID>/seg-*.m4a + session.json (앱 전용 저장소, 다른 앱이 못 읽음) */
data class RecordingSession(
    val id: String,
    val title: String,
    val startedAt: Long,
    /** recording · stopped · interrupted(앱이 강제 종료돼 정지 버튼 없이 끝남) · failed */
    val status: String,
    val durationMs: Long,
    val segments: Int,
    val bytes: Long,
    /** 서버에 만든 기록 ID(올리기 시작 뒤) */
    val recordId: String? = null,
    /** 서버 진행: uploaded(올림 완료) · ready(회의록·할 일 후보 나옴) · 그 밖은 null */
    val serverState: String? = null,
    /** 처리 완료 시각. 이때부터 7일 뒤 기기 음성을 자동으로 지운다 */
    val readyAt: Long? = null,
    /** 기기 음성을 지웠는지(목록 줄과 서버 기록 연결은 남긴다) */
    val audioDeleted: Boolean = false,
    /** 참석자(DEC-39). 올릴 때 기록과 함께 올라간다 */
    val attendees: List<String> = emptyList(),
) {
    /** 서버에 음성이 다 올라가 있어 기기 사본이 없어도 되는 상태 */
    val serverHasAudio get() = serverState == "uploaded" || serverState == "ready"
}

object RecordingStore {
    private const val META = "session.json"
    /** 처리 완료 후 기기 음성 보관 기간(사용자 결정 2026-10-18: 7일 뒤 자동 삭제 + 바로 지우기) */
    const val KEEP_AFTER_READY_MS = 7L * 24 * 60 * 60 * 1000

    private fun root(context: Context) = File(context.filesDir, "recordings").apply { mkdirs() }

    fun create(context: Context): File {
        val now = Date()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val dir = File(root(context), id).apply { mkdirs() }
        write(dir, JSONObject()
            .put("id", id)
            .put("title", "녹음 " + SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(now))
            .put("startedAt", now.time)
            .put("status", "recording")
            .put("durationMs", 0L)
            .put("confirmedMs", 0L))
        return dir
    }

    fun title(dir: File): String = read(dir)?.optString("title").orEmpty()

    fun setTitle(dir: File, title: String) {
        val m = read(dir) ?: return
        write(dir, m.put("title", title))
    }

    fun setAttendees(dir: File, names: List<String>) {
        val m = read(dir) ?: return
        write(dir, m.put("attendees", org.json.JSONArray(names)))
    }

    private fun attendees(m: JSONObject): List<String> =
        m.optJSONArray("attendees")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()

    /** 조각이 확정될 때마다 기록해 두면, 강제 종료 뒤에도 어디까지 남았는지 안다 */
    fun updateProgress(dir: File, confirmedMs: Long) {
        val m = read(dir) ?: return
        write(dir, m.put("confirmedMs", confirmedMs))
    }

    fun finish(dir: File, durationMs: Long, status: String = "stopped") {
        val m = read(dir) ?: return
        write(dir, m.put("status", status).put("durationMs", durationMs))
    }

    fun dir(context: Context, id: String) = File(root(context), id)

    fun segmentFiles(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith("seg-") && f.name.endsWith(".m4a") }.orEmpty().sortedBy { it.name }

    /** 올리기 진행 상태를 기록해 두어 앱을 다시 열어도 이어서 처리할 수 있게 한다 */
    fun setServer(dir: File, recordId: String, serverState: String?) {
        val m = read(dir) ?: return
        m.put("recordId", recordId).put("serverState", serverState ?: "")
        if (serverState == "ready" && !m.has("readyAt")) m.put("readyAt", System.currentTimeMillis())
        write(dir, m)
    }

    /** 기기 음성 조각만 지운다. 서버 기록 연결(회의록 보기)은 남긴다 */
    fun deleteAudio(dir: File) {
        segmentFiles(dir).forEach { it.delete() }
        File(dir, "upload.m4a").delete()
        val m = read(dir) ?: return
        write(dir, m.put("audioDeletedAt", System.currentTimeMillis()))
    }

    /** 올리지 않은 녹음을 버릴 때: 세션 통째로 지운다 */
    fun deleteSession(dir: File) {
        dir.deleteRecursively()
    }

    /**
     * 처리 완료 후 7일 지난 기기 음성을 지운다. 앱을 열 때마다 부른다.
     * 이 기능 전에 처리된 녹음은 완료 시각이 없으므로 지금부터 7일을 센다(바로 지우지 않음).
     */
    fun purgeExpired(context: Context, now: Long = System.currentTimeMillis()): Int {
        var purged = 0
        root(context).listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val m = read(dir) ?: return@forEach
            if (m.optString("serverState") != "ready") return@forEach
            if (!m.has("readyAt")) { write(dir, m.put("readyAt", now)); return@forEach }
            if (now - m.optLong("readyAt") >= KEEP_AFTER_READY_MS && segmentFiles(dir).isNotEmpty()) {
                deleteAudio(dir)
                purged++
            }
        }
        return purged
    }

    fun list(context: Context, activeId: String?): List<RecordingSession> =
        root(context).listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val m = read(dir) ?: return@mapNotNull null
            val segs = dir.listFiles { f -> f.name.startsWith("seg-") }.orEmpty()
            var status = m.optString("status")
            var duration = m.optLong("durationMs")
            // '녹음 중'으로 남았는데 지금 녹음하고 있지 않으면, 앱이 강제 종료된 것이다. 확정 저장된 시간까지만 남는다
            if (status == "recording" && dir.name != activeId) {
                status = "interrupted"
                duration = m.optLong("confirmedMs")
            }
            RecordingSession(
                id = dir.name,
                title = m.optString("title"),
                startedAt = m.optLong("startedAt"),
                status = status,
                durationMs = duration,
                segments = segs.size,
                bytes = segs.sumOf { it.length() },
                recordId = m.optString("recordId").ifEmpty { null },
                serverState = m.optString("serverState").ifEmpty { null },
                readyAt = m.optLong("readyAt").takeIf { m.has("readyAt") },
                audioDeleted = m.has("audioDeletedAt"),
                attendees = attendees(m),
            )
        }.sortedByDescending { it.startedAt }

    private fun read(dir: File): JSONObject? =
        runCatching { JSONObject(File(dir, META).readText()) }.getOrNull()

    // 쓰는 도중 종료돼도 메타 파일이 깨지지 않게 임시 파일에 쓰고 바꿔 끼운다
    private fun write(dir: File, json: JSONObject) {
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(dir, META))
    }
}
