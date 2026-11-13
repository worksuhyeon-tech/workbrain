package com.workbrain.app.record

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.workbrain.app.Supa
import kotlinx.coroutines.CancellationException
import io.github.jan.supabase.auth.auth

/**
 * 올리기·처리 백그라운드 작업(DEC-37). 앱을 닫아도 WorkManager가 이어서 돌리고,
 * 인터넷이 끊기면 연결될 때, 처리가 길면 잠시 뒤 다시 돌린다. 결과가 나오면 알림으로 알린다.
 */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(SESSION) ?: return Result.failure()
        // 앱 화면 없이 깨어났을 수 있으므로 저장된 로그인 정보를 먼저 불러온다(토큰 확보는 UploadManager.run에서)
        Supa.client.auth.awaitInitialization()
        return runCatching {
            UploadManager.run(
                applicationContext, sessionId,
                kind = inputData.getString(KIND) ?: "meeting",
                consentGiven = inputData.getBoolean(CONSENT, false),
            )
        }.onFailure {
            // 시스템이 작업을 잠시 멈춘 것(앱 종료·제약 변화)은 오류가 아니다. WorkManager가 다시 돌린다
            if (it is CancellationException) {
                UploadManager.set(sessionId, UploadProgress("잠시 멈췄어요. 자동으로 이어서 할게요", busy = true))
                throw it
            }
        }.fold(
            { outcome ->
                when (outcome) {
                    RunOutcome.DONE -> { notifyReady(sessionId); Result.success() }
                    RunOutcome.LATER -> Result.retry()
                    RunOutcome.STOPPED -> Result.failure()
                }
            },
            { e ->
                UploadManager.set(sessionId, UploadProgress(UploadManager.friendly(e), busy = UploadManager.isTransient(e), isError = !UploadManager.isTransient(e)))
                if (UploadManager.isTransient(e)) Result.retry() else Result.failure()
            },
        )
    }

    private fun notifyReady(sessionId: String) {
        val title = RecordingStore.title(RecordingStore.dir(applicationContext, sessionId))
        ResultNotifier.notify(applicationContext, sessionId.hashCode(), "할 일 후보가 나왔어요", title)
    }

    companion object {
        const val SESSION = "session"
        const val KIND = "kind"
        const val CONSENT = "consent"
    }
}
