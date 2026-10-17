package com.workbrain.app.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.workbrain.app.MainActivity
import com.workbrain.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class RecStatus { IDLE, RECORDING, PAUSED }

data class RecState(
    val status: RecStatus = RecStatus.IDLE,
    val sessionId: String? = null,
    val title: String = "",
    val elapsedMs: Long = 0,
    /** 이 시간까지는 강제 종료돼도 남는다 */
    val confirmedMs: Long = 0,
    val error: String? = null,
)

/**
 * 녹음은 화면·앱과 떨어진 포그라운드 서비스에서 돈다. 화면을 끄거나 다른 앱을 써도, 접거나 펼쳐도 계속된다(FR-03, NFR-05).
 * 시스템이 서비스를 죽인 뒤 자동으로 다시 녹음하지는 않는다(START_NOT_STICKY, Design §6.3). 남은 조각은 목록에 '중단됨'으로 보인다.
 */
class RecordingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: SegmentRecorder? = null
    private var dir: File? = null
    private var accumulatedMs = 0L      // 일시정지 전까지 쌓인 시간
    private var runningSince = 0L       // 지금 녹음 구간이 시작된 시각(일시정지 중엔 0)

    private val tick = object : Runnable {
        override fun run() {
            _state.update { it.copy(elapsedMs = elapsed()) }
            notifyProgress()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> end()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (_state.value.status != RecStatus.IDLE) return
        // 마이크를 쓰는 포그라운드 서비스는 시작 직후 바로 알림을 띄워야 한다(Android 14+)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("녹음 준비 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val d = RecordingStore.create(this)
        val rec = SegmentRecorder(this, d) { onConfirmed() }
        try {
            rec.start()
        } catch (e: Exception) {
            rec.release()
            RecordingStore.finish(d, 0, status = "failed")
            _state.value = RecState(error = "녹음을 시작하지 못했어요. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요.")
            shutDown()
            return
        }
        dir = d
        recorder = rec
        accumulatedMs = 0
        runningSince = SystemClock.elapsedRealtime()
        _state.value = RecState(RecStatus.RECORDING, d.name, RecordingStore.title(d))
        handler.post(tick)
    }

    private fun elapsed(): Long =
        accumulatedMs + if (runningSince > 0) SystemClock.elapsedRealtime() - runningSince else 0

    private fun onConfirmed() {
        val ms = elapsed()
        _state.update { it.copy(confirmedMs = ms) }
        dir?.let { RecordingStore.updateProgress(it, ms) }
    }

    private fun pause() {
        if (_state.value.status != RecStatus.RECORDING) return
        recorder?.pause()
        accumulatedMs = elapsed()
        runningSince = 0
        _state.update { it.copy(status = RecStatus.PAUSED, elapsedMs = accumulatedMs) }
        notifyProgress()
    }

    private fun resume() {
        if (_state.value.status != RecStatus.PAUSED) return
        recorder?.resume()
        runningSince = SystemClock.elapsedRealtime()
        _state.update { it.copy(status = RecStatus.RECORDING) }
    }

    private fun end(status: String = "stopped") {
        val rec = recorder ?: return shutDown()
        val total = elapsed()
        rec.stop()
        dir?.let { RecordingStore.finish(it, total, status) }
        recorder = null
        dir = null
        _state.value = RecState()
        shutDown()
    }

    private fun shutDown() {
        handler.removeCallbacks(tick)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // 시스템이 서비스를 정리하는 경우에도 마지막 조각까지 닫아 둔다
    override fun onDestroy() {
        if (recorder != null) end()
        super.onDestroy()
    }

    private fun notifyProgress() {
        val s = _state.value
        val text = when (s.status) {
            RecStatus.PAUSED -> "일시정지 ${formatClock(s.elapsedMs)}"
            else -> "● 녹음 중 ${formatClock(s.elapsedMs)} · ${formatClock(s.confirmedMs)}까지 저장됨"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "녹음", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("WorkBrain 녹음")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "정지·저장", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_STOP = "stop"

        private val _state = MutableStateFlow(RecState())
        val state: StateFlow<RecState> = _state

        fun start(context: Context) {
            _state.update { it.copy(error = null) }
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }
        fun pause(context: Context) { context.startService(intent(context, ACTION_PAUSE)) }
        fun resume(context: Context) { context.startService(intent(context, ACTION_RESUME)) }
        fun stop(context: Context) { context.startService(intent(context, ACTION_STOP)) }

        private fun intent(context: Context, action: String) =
            Intent(context, RecordingService::class.java).setAction(action)
    }
}

/** 12:34 또는 1:02:03 */
fun formatClock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
