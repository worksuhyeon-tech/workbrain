package com.workbrain.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.workbrain.app.MainActivity
import com.workbrain.app.R
import com.workbrain.app.Supa
import com.workbrain.app.data.Server
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 미리 알림 받기(DEC-108): 시각이 되면 알림을 띄우고, 알림의 [완료]·[10분 뒤 다시]를 받는다(앱 안 PendingIntent만 — 내보내지 않음).
 * 알림을 누르면 앱을 열어 그 할 일 서랍으로(ReminderLaunch)
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_FIRE -> {
                val p = planned(intent) ?: return
                ReminderScheduler.fired(context, p.taskId, intent.getBooleanExtra(EXTRA_SNOOZE, false))
                show(context, p, failed = false)
            }
            ACTION_DONE -> {
                val p = planned(intent) ?: return
                // 서버에 완료(버전이 같을 때만). 앱이 꺼져 있어도 로그인 열쇠를 읽고 나서 보낸다
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    val ok = runCatching {
                        Supa.client.auth.awaitInitialization()
                        val t = Server.task(p.taskId)
                        t == null || t.done || Server.setTaskDone(t, true)
                    }.getOrDefault(false)
                    if (ok) NotificationManagerCompat.from(context).cancel(notificationId(p.taskId)) else show(context, p, failed = true)
                    pending.finish()
                }
            }
            ACTION_SNOOZE -> {
                val p = planned(intent) ?: return
                NotificationManagerCompat.from(context).cancel(notificationId(p.taskId))
                ReminderScheduler.snooze(context, p)
            }
        }
    }

    private fun planned(i: Intent): PlannedReminder? {
        val id = i.getStringExtra(ReminderScheduler.EXTRA_TASK) ?: return null
        return PlannedReminder(
            id, 0,
            i.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty(),
            i.getStringExtra(ReminderScheduler.EXTRA_TEXT).orEmpty(),
            i.getStringExtra(ReminderScheduler.EXTRA_KEY) ?: "all",
        )
    }

    companion object {
        /** 11/15 처음 채널(진동 꺼짐)은 진동 모드에서 소리·진동이 없어 팝업 없이 목록에만 들어갔다 → 진동을 켠 새 채널 */
        const val CHANNEL_ID = "task_reminder_alert"
        private const val OLD_CHANNEL_ID = "task_reminder"
        const val EXTRA_SNOOZE = "snooze"
        private const val ACTION_DONE = "com.workbrain.app.REMIND_DONE"
        private const val ACTION_SNOOZE = "com.workbrain.app.REMIND_SNOOZE"

        fun notificationId(taskId: String) = 0x5200_0000 or (taskId.hashCode() and 0x00FF_FFFF)

        /** 채널 '할 일 알림': 높음(소리·진동·화면 위에 팝업). 진동 모드에서도 울리게 진동을 켠다 */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(OLD_CHANNEL_ID) != null) nm.deleteNotificationChannel(OLD_CHANNEL_ID)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "할 일 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "미리 알림을 정한 할 일이 그 시각에 울려요"
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 300, 150, 300)
                    },
                )
            }
        }

        private fun show(context: Context, p: PlannedReminder, failed: Boolean) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).setData(Uri.parse("workbrain-remind://open/${p.taskId}"))
                    .putExtra(ReminderLaunch.EXTRA_OPEN_TASK, p.taskId).putExtra(ReminderLaunch.EXTRA_OPEN_KEY, p.key)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(p.title)
                .setContentText(if (failed) "완료하지 못했어요 · 앱에서 다시 해 주세요" else p.text)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
            if (!failed) {
                b.addAction(0, "완료", action(context, ACTION_DONE, p))
                b.addAction(0, "10분 뒤 다시", action(context, ACTION_SNOOZE, p))
            }
            NotificationManagerCompat.from(context).notify(notificationId(p.taskId), b.build())
        }

        private fun action(context: Context, action: String, p: PlannedReminder): PendingIntent {
            val i = Intent(context, ReminderReceiver::class.java).setAction(action)
                .setData(Uri.parse("workbrain-remind://$action/${p.taskId}"))
                .putExtra(ReminderScheduler.EXTRA_TASK, p.taskId).putExtra(ReminderScheduler.EXTRA_TITLE, p.title)
                .putExtra(ReminderScheduler.EXTRA_TEXT, p.text).putExtra(ReminderScheduler.EXTRA_KEY, p.key)
            return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/**
 * 휴대폰을 다시 켜면·앱을 새로 깔면·시각이 바뀌면 알람이 지워지거나 틀어지므로 적어 둔 것을 다시 걸고 서버와 한 번 맞춘다.
 * 시스템만 보내는 방송이라 따로 둔다(완료·다시 울리기 받는 쪽은 밖에서 못 부르게)
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                ReminderScheduler.rearm(context)
                ReminderSync.syncSoon(context)
            }
        }
    }
}
