package com.workbrain.app.record

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.workbrain.app.data.Server
import com.workbrain.app.data.SourceRecordInsert
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 기록 하나의 올리기·처리 진행 표시 */
data class UploadProgress(val message: String, val busy: Boolean, val isError: Boolean = false)

/** 한 번 돌린 결과: 끝남 · 나중에 다시(처리가 길어짐) · 멈춤(사용자 조치 필요) */
enum class RunOutcome { DONE, LATER, STOPPED }

/**
 * 녹음 → 하나로 합치기 → 올리기 → 처리(받아쓰기 → 용어 교정 → 회의록·할 일 후보)까지 진행한다(FR-03~07).
 * 실제 일은 WorkManager(ProcessWorker)가 한다(DEC-37): 앱을 닫거나 화면을 꺼도 계속되고,
 * 끊기면 연결될 때 다시 이어 간다. 단계마다 기기 메타에 기록해 같은 기록이 두 번 만들어지지 않는다.
 */
object UploadManager {
    private val _progress = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, UploadProgress>> = _progress

    internal fun set(id: String, p: UploadProgress) = _progress.update { it + (id to p) }

    /** kind: meeting·call·memo, permission: personal·sample·company-approved(금지·모름이면 부르지 않는다, M11) */
    fun start(context: Context, session: RecordingSession, kind: String, permission: String, consentGiven: Boolean, attendees: List<String>) {
        // 참석자는 기기 메타에 먼저 적어 둔다(작업이 다시 돌아도 같은 명단으로 올림)
        RecordingStore.setAttendees(RecordingStore.dir(context, session.id), attendees)
        RecentAttendees.remember(context, attendees)
        set(session.id, UploadProgress("준비 중", busy = true))
        enqueue(context, session.id, kind, permission, consentGiven, ExistingWorkPolicy.KEEP)
    }

    /** '이어서 처리': 기다리는 중이던 작업도 지금 바로 다시 돌린다(종류·허용은 이미 서버 기록에 있다) */
    fun resume(context: Context, session: RecordingSession) {
        set(session.id, UploadProgress("다시 이어서 하는 중", busy = true))
        enqueue(context, session.id, "meeting", "personal", false, ExistingWorkPolicy.REPLACE)
    }

    /**
     * 앱을 열 때 부른다: 올리기는 시작했는데 아직 결과가 없는 녹음을 백그라운드 작업으로 다시 건다.
     * 설정에서 '강제 중지'하면 예약된 작업도 지워지므로, 다시 열었을 때 여기서 이어 간다.
     */
    fun ensureRunning(context: Context) {
        RecordingStore.list(context, activeId = null)
            .filter { it.recordId != null && it.serverState != "ready" }
            .forEach { enqueue(context, it.id, "meeting", "personal", false, ExistingWorkPolicy.KEEP) }
    }

    private fun enqueue(context: Context, sessionId: String, kind: String, permission: String, consentGiven: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ProcessWorker>()
            .setInputData(workDataOf(
                ProcessWorker.SESSION to sessionId, ProcessWorker.KIND to kind,
                ProcessWorker.PERMISSION to permission, ProcessWorker.CONSENT to consentGiven,
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("process-$sessionId", policy, request)
    }

    /** ProcessWorker가 부른다. 세션 상태는 매번 기기 메타에서 다시 읽는다(앱이 꺼졌다 켜져도 같은 지점부터) */
    internal suspend fun run(app: Context, sessionId: String, kind: String, permission: String, consentGiven: Boolean): RunOutcome {
        val dir = RecordingStore.dir(app, sessionId)
        val session = RecordingStore.list(app, activeId = null).firstOrNull { it.id == sessionId } ?: return RunOutcome.STOPPED
        if (session.serverState == "ready") return RunOutcome.DONE
        if (consentGiven && !Server.hasConsent()) Server.grantConsent()
        if (!Server.hasConsent()) error("WB403")

        var recordId = session.recordId
        if (session.serverState == null) {
            set(sessionId, UploadProgress("녹음 조각 합치는 중", busy = true))
            val merged = File(dir, "upload.m4a")
            val seconds = AudioMerger.merge(RecordingStore.segmentFiles(dir), merged)
            val bytes = merged.readBytes()
            if (recordId == null) {
                recordId = UUID.randomUUID().toString()
                Server.createRecord(SourceRecordInsert(
                    id = recordId,
                    workspaceId = Server.workspaceId(),
                    channel = "recording",
                    title = session.title,
                    occurredAt = Instant.ofEpochMilli(session.startedAt).toString(),
                    storagePermission = permission,
                    recordKind = kind,
                    attendees = session.attendees,
                ))
                RecordingStore.setServer(dir, recordId, null)
            }
            set(sessionId, UploadProgress("올리는 중 · %.1fMB".format(bytes.size / 1_048_576.0), busy = true))
            Server.uploadAudio(recordId, bytes)
            Server.recordReady(recordId, bytes.size.toLong(), seconds, sha256(bytes))
            RecordingStore.setServer(dir, recordId, "uploaded")
            merged.delete()
        }
        return processLoop(sessionId, dir, recordId ?: error("기록 ID가 없어요"))
    }

    // 한 번의 작업 안에서는 4분까지 기다린다(WorkManager 한 번 실행 제한 10분). 그 뒤는 작업을 다시 예약해 이어 간다
    private suspend fun processLoop(id: String, dir: File, recordId: String): RunOutcome {
        repeat(80) {
            val s = Server.process(recordId)
            when {
                s.stage == "REVIEWABLE" -> {
                    RecordingStore.setServer(dir, recordId, "ready")
                    set(id, UploadProgress("회의록·할 일 후보가 나왔어요", busy = false))
                    return RunOutcome.DONE
                }
                s.state == "WAITING_KEY" -> {
                    set(id, UploadProgress("올리기는 끝났어요. 서버 AI 설정이 끝나면 '이어서 처리'를 눌러 주세요", busy = false, isError = true))
                    return RunOutcome.STOPPED
                }
                s.state == "BUDGET_WAIT" -> {
                    set(id, UploadProgress("이번 달 AI 예산 대기선을 넘어 기다리는 중이에요(설정에서 확인)", busy = false, isError = true))
                    return RunOutcome.STOPPED
                }
                s.state == "FAILED" && (s.error?.contains("세 번") == true || s.error?.contains("말소리") == true) -> {
                    set(id, UploadProgress(s.error ?: "처리에 실패했어요", busy = false, isError = true))
                    return RunOutcome.STOPPED
                }
                else -> {
                    val label = when (s.stage) {
                        "STT" -> "받아쓰는 중"
                        "CORRECT" -> "업무 용어 맞추는 중"
                        else -> "회의록·할 일 정리 중"
                    }
                    set(id, UploadProgress(if (s.state == "FAILED") "$label · 다시 시도 중" else label, busy = true))
                    delay(3000)
                }
            }
        }
        set(id, UploadProgress("아직 처리 중이에요. 자동으로 이어서 할게요", busy = true))
        return RunOutcome.LATER
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 인터넷 문제는 다시 시도할 일, 나머지는 사용자에게 알릴 일 */
    internal fun isTransient(e: Throwable): Boolean {
        val m = e.message.orEmpty()
        return e is java.io.IOException || "Unable to resolve host" in m || "timeout" in m.lowercase()
    }

    internal fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "WB403" in m -> "외부 전사·분석 동의가 필요해요"
            "합칠 수 있는" in m -> "올릴 수 있는 녹음 조각이 없어요"
            isTransient(e) -> "인터넷 연결을 기다리는 중이에요. 연결되면 자동으로 이어서 할게요"
            else -> "처리 중 문제가 생겼어요: " + m.take(80)
        }
    }
}
