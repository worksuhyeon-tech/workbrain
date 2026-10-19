package com.workbrain.app.record

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.workbrain.app.MainActivity
import com.workbrain.app.R
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
                permission = inputData.getString(PERMISSION) ?: "personal",
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
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "처리 결과", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val title = RecordingStore.title(RecordingStore.dir(ctx, sessionId))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("회의록·할 일 후보가 나왔어요")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(sessionId.hashCode(), n)
    }

    companion object {
        const val SESSION = "session"
        const val KIND = "kind"
        const val PERMISSION = "permission"
        const val CONSENT = "consent"
        private const val CHANNEL_ID = "processing"
    }
}
