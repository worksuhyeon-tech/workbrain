package com.workbrain.app.reminders

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.workbrain.app.Supa
import com.workbrain.app.data.Server
import com.workbrain.app.ui.SEOUL
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 서버 값과 휴대폰 예약 맞추기(DEC-108): 앱을 열 때·할 일 목록을 다시 읽을 때(refreshLater),
 * 1시간마다 백그라운드(PC·다른 휴대폰에서 바꾼 알림도 늦어도 1시간 안에), 휴대폰을 다시 켠 뒤(syncSoon)
 */
object ReminderSync {
    private const val PERIODIC = "reminder-sync"
    private const val ONCE = "reminder-sync-once"
    /** 목록을 자주 다시 읽어도 서버를 너무 자주 부르지 않게 */
    private const val MIN_GAP_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var lastAt = 0L

    /** 서버에서 읽어 맞춘다. 실패(오프라인·로그인 전)면 false — 예약은 그대로 둔다 */
    suspend fun sync(context: Context): Boolean = lock.withLock {
        try {
            val today = LocalDate.now(SEOUL)
            val tasks = Server.remindTasks(today)
            ReminderScheduler.apply(context, planReminders(tasks, Instant.now(), today))
            lastAt = System.currentTimeMillis()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WorkBrain", "reminder sync failed", e)
            false
        }
    }

    /** 화면이 목록을 다시 읽을 때 뒤에서 한 번(20초 안에 또 부르면 건너뜀) */
    fun refreshLater(context: Context) {
        if (System.currentTimeMillis() - lastAt < MIN_GAP_MS) return
        val app = context.applicationContext
        scope.launch { sync(app) }
    }

    /** 앱을 처음 열 때: 1시간마다 맞추기를 걸어 둔다(이미 있으면 그대로) */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** 휴대폰을 다시 켠 뒤 등: 인터넷이 되면 한 번 */
    fun syncSoon(context: Context) {
        val req = OneTimeWorkRequestBuilder<ReminderSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }
}

class ReminderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // 로그인 열쇠를 기기에서 읽은 뒤에 서버를 부른다(다른 작업과 같음)
        Supa.client.auth.awaitInitialization()
        if (Supa.client.auth.currentSessionOrNull() == null) return Result.success()
        return if (ReminderSync.sync(applicationContext)) Result.success() else Result.retry()
    }
}
