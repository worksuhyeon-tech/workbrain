package com.workbrain.app.decide

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.workbrain.app.MainActivity
import com.workbrain.app.R
import com.workbrain.app.data.Decide
import com.workbrain.app.data.DecisionKind
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.reminders.ReminderReceiver
import com.workbrain.app.reminders.ReminderScheduler
import com.workbrain.app.tasks.dueDay
import com.workbrain.app.tasks.repeatDoneNote
import com.workbrain.app.data.toEdit
import com.workbrain.app.ui.SEOUL
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/*
 * 집중 타이머(DEC-111, Design §3.21): [2분 시작]을 누르면 바로 켜진다. 정한 시간이 지나도 멈추지 않고 '+M:SS 더 하는 중'으로 잰다
 * (11/19 사용자: 2분은 걸릴 시간 예상이 아니라 시작 약속, 실제 걸린 시간을 남김). 끝나면 [끝났어요]·[여기까지]·[25분 타이머로].
 * 기기에 끝나는 시각을 적어 두어 앱이 꺼졌다 켜져도 이어서 센다. 잠금 화면에는 줄어드는(넘으면 늘어나는) 시계 알림,
 * 정한 시간이 되면 진동 알림 한 번(정확한 알람, 미리 알림과 같은 허용).
 */

/** 집중 한 번. kind: start(바로 시작) · split(나눈 첫 조각) */
@Serializable
data class FocusSession(
    val taskId: String,
    val title: String,
    val place: String? = null,
    /** 나눈 조각에서 시작했으면 그 조각('상위 › 조각'으로 보임) */
    val piece: String? = null,
    /** 이번에 할 동작 */
    val step: String,
    /** 동작이 하위 할 일이면 그 ID(끝냈다고 하면 체크) */
    val stepTaskId: String? = null,
    /** '다음엔 무엇부터'의 처음 값(비서가 나눈 다음 조각) */
    val nextHint: String? = null,
    /** 할 일 자체가 하위면 다음 동작을 하위로 넣을 수 없다(한 단계) */
    val isSubtask: Boolean = false,
    val kind: String = DecisionKind.START.server,
    /** 처음 정한 분(2·5). 25분으로 이어 가도 그대로 남긴다 */
    val plannedMin: Int,
    val startedAtMs: Long,
    val clock: FocusClock,
    /** [여기까지]·[그만]을 눌러 '다음엔 무엇부터'를 받는 중(시간은 멈춤) */
    val stopped: Boolean = false,
    /** 정한 시간이 지나 진동 알림을 보냈음 */
    val alerted: Boolean = false,
)

/** 집중이 끝난 뒤 화면이 받는 것(비서 카드는 다음 장으로, 오늘 탭은 다시 읽기) */
data class FocusResult(val taskId: String, val kind: DecisionKind, val doneTask: Boolean, val spentMs: Long, val message: String, val ok: Boolean)

object FocusController {
    private const val PREFS = "focus"
    private const val KEY = "session"
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<FocusSession?>(null)
    val state: StateFlow<FocusSession?> = _state

    private val _finished = MutableSharedFlow<FocusResult>(extraBufferCapacity = 4)
    val finished: SharedFlow<FocusResult> = _finished

    private var loaded = false

    /** 앱을 열 때 한 번: 적어 둔 집중이 있으면 이어서 */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        _state.value = read(context)
        FocusNotify.refresh(context, _state.value)
    }

    fun start(context: Context, s: FocusSession) {
        FocusNotify.cancelDone(context)
        set(context, s)
    }

    fun togglePause(context: Context) = update(context) { s ->
        val now = System.currentTimeMillis()
        s.copy(clock = if (s.clock.paused) s.clock.resume(now) else s.clock.pause(now))
    }

    /** [25분 타이머로 바꾸기]: 지금까지 한 시간은 그대로 두고 새 25분 */
    fun extend(context: Context) = update(context) { s ->
        FocusNotify.cancelDone(context)
        s.copy(clock = s.clock.switchTo(LONG_FOCUS_MIN, System.currentTimeMillis()), alerted = false)
    }

    /** [여기까지]·[그만]: 시간을 멈추고 '다음엔 무엇부터'로 */
    fun stop(context: Context) = update(context) { s -> s.copy(stopped = true, clock = s.clock.pause(System.currentTimeMillis())) }

    /** 정한 시간이 됐을 때(알람·화면 둘 중 먼저 본 쪽) 진동 알림을 한 번만 */
    fun onOver(context: Context) {
        val s = _state.value ?: read(context) ?: return
        if (s.alerted || s.stopped || !s.clock.over(System.currentTimeMillis())) return
        set(context, s.copy(alerted = true))
        FocusNotify.showDone(context, s)
    }

    /** [끝났어요]: 할 일을 완료(반복이면 다음 회차는 서버가). 실패하면 화면에 남겨 다시 누를 수 있게 */
    suspend fun finishDone(context: Context): FocusResult? {
        val s = _state.value ?: return null
        val spent = s.clock.spentMs(System.currentTimeMillis())
        val kind = kindOf(s)
        val today = LocalDate.now(SEOUL)
        val result = runCatching {
            val t = Server.task(s.taskId)
            if (t != null && !t.done) {
                Decide.decide(t.id, kind)
                val note = repeatDoneNote(t, today)
                if (!Server.setTaskDone(t, true)) return@runCatching "다른 기기에서 먼저 바뀌어 완료하지 못했어요. 새로 불러왔어요" to false
                "끝냈어요 · 실제 ${spentText(spent)}" + (note?.let { " · $it" } ?: "") to true
            } else "끝냈어요 · 실제 ${spentText(spent)}" to true
        }.getOrElse { Log.w("WbFocus", "finish done failed", it); return FocusResult(s.taskId, kind, false, spent, "저장하지 못했어요. 인터넷 연결을 확인해 주세요", false) }
        Decide.logFocus(s.taskId, s.step, s.plannedMin, (spent / 1000).toInt(), Instant.ofEpochMilli(s.startedAtMs), "done", true)
        return finish(context, FocusResult(s.taskId, kind, result.second, spent, result.first, true))
    }

    /** [저장하고 다음 장]: 동작 체크·오늘 할 일에 남기기·다음 동작을 하위로 */
    suspend fun finishHere(context: Context, stepDone: Boolean, next: String?): FocusResult? {
        val s = _state.value ?: return null
        val spent = s.clock.spentMs(System.currentTimeMillis())
        val kind = kindOf(s)
        val today = LocalDate.now(SEOUL)
        val text = runCatching {
            val t = Server.task(s.taskId) ?: return@runCatching "이 할 일은 그새 지워졌어요"
            Decide.decide(t.id, kind)
            if (stepDone) s.stepTaskId?.let { id -> Server.task(id)?.takeIf { !it.done }?.let { Server.setTaskDone(it, true) } }
            // 기한이 지났거나 없으면 오늘로(시각 없이). 오늘·앞날이면 그대로
            val fresh = Server.task(s.taskId) ?: t
            val due = dueDay(fresh)
            if (due == null || due.isBefore(today)) {
                Server.updateTask(fresh, fresh.toEdit().copy(dueDate = today.toString(), dueTime = null, dueAmbiguous = false, dueText = null))
            }
            if (!s.isSubtask) {
                val kids = Decide.children(t.id)
                newSubtaskTitles(listOfNotNull(next), kids).firstOrNull()?.let { Server.createSubtask(t.id, plainEdit(it)) }
            }
            "오늘 할 일에 두고, 다음 동작을 기억해 둘게요 · 실제 ${spentText(spent)}"
        }.getOrElse { Log.w("WbFocus", "finish here failed", it); return FocusResult(s.taskId, kind, false, spent, "저장하지 못했어요. 인터넷 연결을 확인해 주세요", false) }
        Decide.logFocus(s.taskId, s.step, s.plannedMin, (spent / 1000).toInt(), Instant.ofEpochMilli(s.startedAtMs), "here", stepDone)
        return finish(context, FocusResult(s.taskId, kind, false, spent, text, true))
    }

    private fun kindOf(s: FocusSession) = if (s.kind == DecisionKind.SPLIT.server) DecisionKind.SPLIT else DecisionKind.START

    private fun finish(context: Context, r: FocusResult): FocusResult {
        set(context, null)
        FocusNotify.cancelDone(context)
        _finished.tryEmit(r)
        return r
    }

    private fun update(context: Context, f: (FocusSession) -> FocusSession) {
        val s = _state.value ?: return
        set(context, f(s))
    }

    private fun set(context: Context, s: FocusSession?) {
        _state.value = s
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (s == null) prefs.remove(KEY) else prefs.putString(KEY, json.encodeToString(FocusSession.serializer(), s))
        prefs.apply()
        FocusNotify.refresh(context, s)
    }

    internal fun read(context: Context): FocusSession? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(FocusSession.serializer(), it) }.getOrNull() }
}

/** 제목만 있는 하위 할 일(첫 동작·조각) */
fun plainEdit(title: String) = TaskEdit(title, null, "me", null, null, null, false, null, null, null)

/** 잠금 화면 시계 알림 + 정한 시간이 되면 진동 알림 */
object FocusNotify {
    private const val CHANNEL = "focus_timer"
    private const val ONGOING_ID = 0x5300_0001
    private const val DONE_ID = 0x5300_0002
    const val ACTION_OVER = "com.workbrain.app.FOCUS_OVER"

    private fun canNotify(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "집중 타이머", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "집중하는 동안 남은 시간을 잠금 화면에 보여 줘요(소리 없음)"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0x53,
        Intent(context, MainActivity::class.java).setData(Uri.parse("workbrain-focus://open")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0x53, Intent(context, FocusAlarmReceiver::class.java).setAction(ACTION_OVER),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 지금 상태에 맞게 시계 알림·알람을 다시 건다(없거나 멈춘 화면이면 모두 거둠) */
    fun refresh(context: Context, s: FocusSession?) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(context))
        val nm = NotificationManagerCompat.from(context)
        if (s == null || s.stopped) { nm.cancel(ONGOING_ID); return }
        val now = System.currentTimeMillis()
        val c = s.clock
        // 정한 시간이 되는 때 진동 알림(멈췄거나 이미 넘었으면 걸지 않음)
        c.endAtMs(now)?.takeIf { !c.over(now) }?.let { at ->
            if (ReminderScheduler.canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        }
        if (!canNotify(context)) return
        ensureChannel(context)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText(s.piece?.let { "${s.title} › $it" } ?: s.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(openApp(context))
        when {
            c.paused -> b.setContentTitle("잠깐 멈춤 · ${mmss(c.leftMs(now))} 남음").setShowWhen(false)
            c.over(now) -> b.setContentTitle("${c.segMin}분 지나서 계속하는 중").setUsesChronometer(true).setWhen(now - c.overMs(now))
            else -> b.setContentTitle("집중 · ${c.segMin}분").setUsesChronometer(true).setChronometerCountDown(true).setWhen(now + c.leftMs(now))
        }
        nm.notify(ONGOING_ID, b.build())
    }

    /** 정한 시간이 됐을 때 한 번: 진동이 켜진 '할 일 알림' 채널로 */
    fun showDone(context: Context, s: FocusSession) {
        if (!canNotify(context)) return
        ReminderReceiver.ensureChannel(context)
        val b = NotificationCompat.Builder(context, ReminderReceiver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${s.clock.segMin}분 해냈어요!")
            .setContentText("하던 거 마저 해도 돼요 · 시간은 계속 재요")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        NotificationManagerCompat.from(context).notify(DONE_ID, b.build())
    }

    fun cancelDone(context: Context) = NotificationManagerCompat.from(context).cancel(DONE_ID)
}

/** 정한 시간이 된 알람(앱 안에서만 받음): 진동 알림을 띄우고 시계 알림을 '넘어서 세기'로 */
class FocusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FocusNotify.ACTION_OVER) return
        FocusController.onOver(context)
        FocusNotify.refresh(context, FocusController.state.value ?: FocusController.read(context))
    }
}
