package com.workbrain.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.workbrain.app.data.TaskRow
import com.workbrain.app.projects.areaKey
import com.workbrain.app.projects.projectKey
import com.workbrain.app.tasks.SNOOZE_MIN
import com.workbrain.app.tasks.remindAtOf
import com.workbrain.app.tasks.remindNotiText
import com.workbrain.app.tasks.shouldSchedule
import com.workbrain.app.ui.SEOUL
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * 할 일 미리 알림 예약(DEC-108, Design §3.19). 서버 값을 읽어 앞으로 14일 안 알림을 AlarmManager에 걸고,
 * 건 목록을 기기에 적어 둔다(다음 맞추기 때 사라진 것은 취소, 휴대폰을 다시 켜면 그대로 다시 건다).
 * [10분 뒤 다시]는 이 휴대폰에서만 따로 적어 두고, 서버 맞추기가 지우지 않는다.
 */

/** 휴대폰에 걸 알림 하나. key = 알림을 누르면 갈 프로젝트 탭 항목(p:ID·a:ID·all) */
data class PlannedReminder(val taskId: String, val atMillis: Long, val title: String, val text: String, val key: String)

/** 서버에서 읽은 할 일 → 걸 알림(앞으로 14일 안, 안 끝난 것만) */
fun planReminders(tasks: List<TaskRow>, now: Instant, today: LocalDate): List<PlannedReminder> = tasks.mapNotNull { t ->
    val at = remindAtOf(t) ?: return@mapNotNull null
    if (!shouldSchedule(at, now)) return@mapNotNull null
    val timed = t.dueTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL) }.getOrNull() }
    val day = timed?.toLocalDate() ?: LocalDate.parse(t.dueDate)
    PlannedReminder(
        taskId = t.id,
        atMillis = at.toEpochMilli(),
        title = t.title,
        text = remindNotiText(day, timed?.toLocalTime(), today, t.placeName),
        key = t.projectId?.let(::projectKey) ?: t.areaId?.let(::areaKey) ?: "all",
    )
}

object ReminderScheduler {
    private const val PREFS = "reminders"
    private const val SNOOZE = "snooze:"
    const val ACTION_FIRE = "com.workbrain.app.REMIND_FIRE"
    const val EXTRA_TASK = "task_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_KEY = "key"

    /** 서버에서 맞춘 목록으로 바꾼다: 없어지거나 시각이 바뀐 것은 취소하고, 새것·바뀐 것은 건다 */
    fun apply(context: Context, planned: List<PlannedReminder>) {
        val prefs = prefs(context)
        val stored = stored(context).filterKeys { !it.startsWith(SNOOZE) }
        val want = planned.associateBy { it.taskId }
        val edit = prefs.edit()
        stored.forEach { (id, old) ->
            if (want[id] != old) {
                cancelAlarm(context, id)
                edit.remove(id)
            }
        }
        want.values.forEach { p ->
            if (stored[p.taskId] != p) {
                schedule(context, p)
                edit.putString(p.taskId, encode(p))
            }
        }
        edit.apply()
    }

    /** 휴대폰을 다시 켰을 때·시각이 바뀌었을 때: 적어 둔 것 중 아직 안 지난 것을 다시 건다 */
    fun rearm(context: Context) {
        val now = System.currentTimeMillis()
        val edit = prefs(context).edit()
        stored(context).forEach { (k, p) -> if (p.atMillis > now) schedule(context, p, snooze = k.startsWith(SNOOZE)) else edit.remove(k) }
        edit.apply()
    }

    /** [10분 뒤 다시]: 이 휴대폰에서만 */
    fun snooze(context: Context, p: PlannedReminder) {
        val later = p.copy(atMillis = System.currentTimeMillis() + SNOOZE_MIN * 60_000)
        schedule(context, later, snooze = true)
        prefs(context).edit().putString(SNOOZE + p.taskId, encode(later)).apply()
    }

    /** 울렸거나 완료한 알림은 목록에서 뺀다(다음 맞추기 때 다시 걸지 않게) */
    fun fired(context: Context, taskId: String, snooze: Boolean) {
        prefs(context).edit().remove(if (snooze) SNOOZE + taskId else taskId).apply()
    }

    /** 정확한 시각에 울릴 수 있는지(Android 12+ '알람 및 리마인더' 허용) */
    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun schedule(context: Context, p: PlannedReminder, snooze: Boolean = false) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, p, snooze)
        // 허용이 없으면 몇 분 늦을 수 있는 예약으로 대신한다(Design §3.19)
        if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.atMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.atMillis, pi)
    }

    private fun cancelAlarm(context: Context, taskId: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(context, PlannedReminder(taskId, 0, "", "", ""), snooze = false))
    }

    private fun alarmIntent(context: Context, p: PlannedReminder, snooze: Boolean): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE)
            // 할 일마다 다른 주소라 서로 덮어쓰지 않는다. 다시 울리기는 따로
            .setData(Uri.parse("workbrain-remind://${if (snooze) "snooze/" else ""}${p.taskId}"))
            .putExtra(EXTRA_TASK, p.taskId).putExtra(EXTRA_TITLE, p.title).putExtra(EXTRA_TEXT, p.text).putExtra(EXTRA_KEY, p.key)
            .putExtra(ReminderReceiver.EXTRA_SNOOZE, snooze)
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stored(context: Context): Map<String, PlannedReminder> =
        prefs(context).all.mapNotNull { (k, v) -> (v as? String)?.let(::decode)?.let { k to it } }.toMap()

    private fun encode(p: PlannedReminder): String =
        JSONObject().put("id", p.taskId).put("at", p.atMillis).put("title", p.title).put("text", p.text).put("key", p.key).toString()

    private fun decode(s: String): PlannedReminder? = runCatching {
        val o = JSONObject(s)
        PlannedReminder(o.getString("id"), o.getLong("at"), o.getString("title"), o.getString("text"), o.getString("key"))
    }.getOrNull()
}
