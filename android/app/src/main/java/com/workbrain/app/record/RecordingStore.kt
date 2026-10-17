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
)

object RecordingStore {
    private const val META = "session.json"

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
        write(dir, m.put("recordId", recordId).put("serverState", serverState ?: ""))
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
