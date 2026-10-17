package com.workbrain.app.record

import android.content.Context
import com.workbrain.app.data.Server
import com.workbrain.app.data.SourceRecordInsert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** 기록 하나의 올리기·처리 진행 표시 */
data class UploadProgress(val message: String, val busy: Boolean, val isError: Boolean = false)

/**
 * 녹음 → 하나로 합치기 → 올리기 → 처리(받아쓰기 → 회의록·할 일 후보)까지 진행한다(FR-03~07).
 * 단계마다 기기 메타에 기록해 두어, 중간에 앱을 닫아도 '이어서 처리'로 같은 기록을 이어 간다(같은 기록이 두 번 만들어지지 않음).
 * 이번 버전은 앱이 열려 있는 동안 진행한다. 앱을 닫아도 계속 올리는 것은 다음 버전(WorkManager).
 */
object UploadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, UploadProgress>> = _progress

    private fun set(id: String, p: UploadProgress) = _progress.update { it + (id to p) }

    /** kind: meeting·call·memo, permission: personal·sample·company-approved(금지·모름이면 부르지 않는다, M11) */
    fun start(context: Context, session: RecordingSession, kind: String, permission: String, consentGiven: Boolean) {
        if (_progress.value[session.id]?.busy == true) return
        val app = context.applicationContext
        set(session.id, UploadProgress("준비 중", busy = true))
        scope.launch {
            runCatching { run(app, session, kind, permission, consentGiven) }
                .onFailure { set(session.id, UploadProgress(friendly(it), busy = false, isError = true)) }
        }
    }

    /** 앞서 멈춘 기록을 이어서 처리한다(종류·허용은 이미 서버 기록에 있다) */
    fun resume(context: Context, session: RecordingSession) = start(context, session, kind = "meeting", permission = "personal", consentGiven = false)

    private suspend fun run(app: Context, session: RecordingSession, kind: String, permission: String, consentGiven: Boolean) {
        val dir = RecordingStore.dir(app, session.id)
        if (consentGiven && !Server.hasConsent()) Server.grantConsent()
        if (!Server.hasConsent()) error("WB403")

        var recordId = session.recordId
        if (session.serverState == null) {
            set(session.id, UploadProgress("녹음 조각 합치는 중", busy = true))
            val merged = File(dir, "upload.m4a")
            val seconds = AudioMerger.merge(RecordingStore.segmentFiles(dir), merged)
            val bytes = merged.readBytes()
            if (recordId == null) {
                recordId = UUID.randomUUID().toString()
                Server.createRecord(SourceRecordInsert(
                    id = recordId,
                    workspaceId = Server.workspaceId(),
                    title = session.title,
                    occurredAt = Instant.ofEpochMilli(session.startedAt).toString(),
                    storagePermission = permission,
                    recordKind = kind,
                ))
                RecordingStore.setServer(dir, recordId, null)
            }
            set(session.id, UploadProgress("올리는 중 · %.1fMB".format(bytes.size / 1_048_576.0), busy = true))
            Server.uploadAudio(recordId, bytes)
            Server.recordReady(recordId, bytes.size.toLong(), seconds, sha256(bytes))
            RecordingStore.setServer(dir, recordId, "uploaded")
            merged.delete()
        }
        processLoop(session.id, dir, recordId ?: error("기록 ID가 없어요"))
    }

    private suspend fun processLoop(id: String, dir: File, recordId: String) {
        repeat(60) {
            val s = Server.process(recordId)
            when {
                s.stage == "REVIEWABLE" -> {
                    RecordingStore.setServer(dir, recordId, "ready")
                    set(id, UploadProgress("회의록·할 일 후보가 나왔어요", busy = false))
                    return
                }
                s.state == "WAITING_KEY" -> {
                    set(id, UploadProgress("올리기는 끝났어요. 서버 AI 설정이 끝나면 '이어서 처리'를 눌러 주세요", busy = false, isError = true))
                    return
                }
                s.state == "BUDGET_WAIT" -> {
                    set(id, UploadProgress("이번 달 AI 예산 대기선을 넘어 기다리는 중이에요(설정에서 확인)", busy = false, isError = true))
                    return
                }
                s.state == "FAILED" && (s.error?.contains("세 번") == true || s.error?.contains("말소리") == true) -> {
                    set(id, UploadProgress(s.error ?: "처리에 실패했어요", busy = false, isError = true))
                    return
                }
                else -> {
                    val label = if (s.stage == "STT") "받아쓰는 중" else "회의록·할 일 정리 중"
                    set(id, UploadProgress(if (s.state == "FAILED") "$label · 다시 시도 중" else label, busy = true))
                    delay(3000)
                }
            }
        }
        set(id, UploadProgress("아직 처리 중이에요. 잠시 뒤 '이어서 처리'를 눌러 주세요", busy = false))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "WB403" in m -> "외부 전사·분석 동의가 필요해요"
            "합칠 수 있는" in m -> "올릴 수 있는 녹음 조각이 없어요"
            e is java.io.IOException || "Unable to resolve host" in m || "timeout" in m.lowercase() ->
                "인터넷 연결이 끊겼어요. 연결되면 '이어서 처리'를 눌러 주세요"
            else -> "처리 중 문제가 생겼어요: " + m.take(80)
        }
    }
}
