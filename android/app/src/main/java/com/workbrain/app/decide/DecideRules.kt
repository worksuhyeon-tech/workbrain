package com.workbrain.app.decide

import com.workbrain.app.data.DecisionKind
import com.workbrain.app.data.SecretaryCard
import com.workbrain.app.data.TaskRow
import com.workbrain.app.tasks.dueDay
import com.workbrain.app.tasks.isLate
import com.workbrain.app.ui.SEOUL
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/*
 * 비서와 결정(DEC-111, Design §3.21)의 규칙 — 화면 없이 시험한다(DecideRulesTest).
 * 지연된 일에 새 날짜가 아니라 결정을 받는다: 지금 2분 시작 · 나누기 · 언제·무엇부터 다시 정하기 · 기다림 · 내려놓기 · 이미 끝남.
 */

/** 한 번에 보는 카드 수(결정하다 지치지 않게, DEC-111 ②) */
const val DECIDE_BATCH = 5
/** 집중을 길게 이어 갈 때(뽀모도로 기본, 메모 v1.2 §4) */
const val LONG_FOCUS_MIN = 25

private val DOW = arrayOf("월", "화", "수", "목", "금", "토", "일")

fun mdLabel(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}(${DOW[d.dayOfWeek.value - 1]})"

/**
 * 다시 정할 일 줄: 내 할 일 오래된 순 → 팔로업(남이 맡은 일). 기한이 지났는데 안 끝난 것.
 * 상위가 함께 밀린 하위는 상위 카드에서 다루므로 뺀다(하위만 밀렸으면 그 하위가 한 장)
 */
fun decideQueue(tasks: List<TaskRow>, today: LocalDate): List<TaskRow> {
    val late = tasks.filter { isLate(it, today) }
    val lateIds = late.map { it.id }.toSet()
    val cards = late.filter { it.parentId == null || it.parentId !in lateIds }
    val order = compareBy<TaskRow>({ dueDay(it) }, { it.createdAt.orEmpty() })
    return cards.filter { it.ownerKind != "other" }.sortedWith(order) + cards.filter { it.ownerKind == "other" }.sortedWith(order)
}

/** 기한이 지난 날수(기한 없으면 null) */
fun daysLate(t: TaskRow, today: LocalDate): Int? = dueDay(t)?.let { ChronoUnit.DAYS.between(it, today).toInt() }

/** 오늘 탭·카드의 차분한 칩: 'M/d(요일) · N일 지남', 팔로업은 'N일째 기다림'(빨간 '지연' 대신, DEC-111) */
fun calmLateLabel(t: TaskRow, today: LocalDate): String? {
    val day = dueDay(t) ?: return null
    val n = ChronoUnit.DAYS.between(day, today)
    if (n <= 0) return null
    return "${mdLabel(day)} · " + if (t.ownerKind == "other") "${n}일째 기다림" else "${n}일 지남"
}

/** 날짜를 다시 정할 때 보여 줄 다음 근무일 셋(토·일 건너뜀, 회사 일 기준) */
fun nextWorkdays(today: LocalDate, n: Int = 3): List<LocalDate> =
    generateSequence(today.plusDays(1)) { it.plusDays(1) }
        .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        .take(n).toList()

/** 팔로업 다시 챙길 날: 내일 · 모레 · 다음 주 월요일 */
fun followUpDays(today: LocalDate): List<Pair<String, LocalDate>> = listOf(
    "내일" to today.plusDays(1),
    "모레" to today.plusDays(2),
    "다음 주 월" to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
)

/** 그날 내 할 일 수(상위만, 안 끝난 것) — 계획 오류를 막으려고 날짜마다 보여 준다 */
fun loadOn(day: LocalDate, tasks: List<TaskRow>): Int =
    tasks.count { !it.done && !it.isSubtask && it.ownerKind != "other" && dueDay(it) == day }

/** 가장 여유 있는 날의 자리(할 일 수가 가장 적은 날, 같으면 이른 날) */
fun lightestIndex(loads: List<Int>): Int = loads.indices.minByOrNull { loads[it] } ?: 0

/** 비서가 먼저 권하는 결정: 캘린더 일정이 지났으면 '이미 끝냈어요', 두 번 넘게 미뤘으면 '너무 커요', 그 밖엔 바로 시작 */
fun recommend(card: SecretaryCard?, calendarPassed: Boolean): DecisionKind = when {
    calendarPassed -> DecisionKind.DONE
    (card?.defers ?: 0) >= 2 -> DecisionKind.SPLIT
    else -> DecisionKind.START
}

/** 캘린더에 넣은 할 일의 일정 시각이 지났는지 */
fun calendarPassed(t: TaskRow, linked: Boolean, now: Instant): Boolean =
    linked && t.dueTime?.let { runCatching { OffsetDateTime.parse(it).toInstant().isBefore(now) }.getOrNull() } == true

/**
 * 비서가 말할 사실(기록 그대로, AI 아님). card가 없으면(서버를 못 부름) 기기에 있는 것만.
 * 순서: 기다림 · 출처 · 캘린더 지남 · 하위 진행 · 미룬 횟수(없으면 손대지 않음)
 */
fun factsOf(t: TaskRow, card: SecretaryCard?, today: LocalDate, calendarPassed: Boolean, kids: List<TaskRow>): List<String> = buildList {
    if (t.ownerKind == "other") daysLate(t, today)?.takeIf { it > 0 }?.let { add("${t.assignee ?: "담당"} 회신을 ${it}일째 기다리고 있어요.") }
    card?.source?.let { s ->
        val day = s.at?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).toLocalDate() }.getOrNull() }
        add("${day?.let { "${it.monthValue}/${it.dayOfMonth} " }.orEmpty()}${s.title ?: "회의"}에서 맡은 일이에요.")
    }
    if (calendarPassed) {
        val at = t.dueTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL) }.getOrNull() }
        add("캘린더에 넣은 일정${at?.let { "(${it.monthValue}/${it.dayOfMonth} ${ampm(it.toLocalTime())})" }.orEmpty()}이 지났어요.")
    }
    val done = kids.count { it.done }
    if (kids.isNotEmpty() && done > 0) add("하위 ${kids.size}개 중 ${done}개는 끝냈어요.")
    when {
        (card?.defers ?: 0) >= 1 -> add("기한을 벌써 ${card!!.defers}번 옮겼어요.")
        card?.untouched == true -> add("만든 뒤로 아직 손대지 않았어요.")
    }
}

/** 추천에 맞춘 비서 한마디(없으면 null) */
fun sayFor(rec: DecisionKind, follow: Boolean): String? = when {
    rec == DecisionKind.DONE -> "혹시 그때 이미 했나요? 했다면 끝난 걸로 표시해 둘게요."
    rec == DecisionKind.SPLIT -> "두 번 넘게 미룬 일은 대개 너무 크거나 막연해서예요. 이번엔 날짜 말고 크기를 줄여 봐요."
    follow -> "기다리기만 하면 계속 밀려요. 2분이면 한 줄 물어볼 수 있어요."
    else -> null
}

fun ampm(t: LocalTime): String = (if (t.hour < 12) "오전 " else "오후 ") + "${(t.hour % 12).let { if (it == 0) 12 else it }}:%02d".format(t.minute)

/**
 * '그날 오전 9시에 알려 드릴게요'의 미리 알림 값(§3.19: 날짜만 있으면 그날 9시 − 값, 시각이 있으면 그 시각 − 값).
 * 날짜만 → 0(9시 정각), 시각이 9시 뒤 → 9시까지의 분, 9시 전 → 0(그 시각)
 */
fun remindAtNine(dueTime: LocalTime?): Int =
    if (dueTime == null) 0 else Duration.between(LocalTime.of(9, 0), dueTime).toMinutes().toInt().coerceAtLeast(0)

/** 날짜를 옮길 때 시각은 그대로(새 날의 같은 시각, 서울) */
fun movedDueTime(dueTime: String?, to: LocalDate): String? = dueTime?.let { iso ->
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(SEOUL).toLocalTime() }.getOrNull()
        ?.let { to.atTime(it).atZone(SEOUL).toOffsetDateTime().toString() }
}

fun localTimeOf(dueTime: String?): LocalTime? =
    dueTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).toLocalTime() }.getOrNull() }

/** 기다림 칩에 보일 이름: 지금 담당·받는 사람·자주 쓴 이름, 최대 4개 */
fun waitNames(t: TaskRow, used: List<String>): List<String> =
    (listOfNotNull(t.assignee?.takeIf { t.ownerKind == "other" }) + t.recipients + used)
        .map { it.trim() }.filter { it.isNotEmpty() && it != "나" }.distinct().take(4)

/** 새로 넣을 하위(첫 동작·조각): 빈 줄·이미 있는 안 끝난 하위와 같은 이름은 뺀다 */
fun newSubtaskTitles(lines: List<String>, existing: List<TaskRow>): List<String> {
    val have = existing.filter { !it.done }.map { it.title.trim() }.toSet()
    return lines.map { it.trim() }.filter { it.isNotEmpty() && it !in have }.distinct().take(5)
}

// ── 집중 타이머 시계(일시정지·25분 이어 가기를 넣어도 실제로 한 시간을 정확히) ──

/**
 * segMin: 지금 칸의 정한 분(2·5·25). segStartMs: 그 칸을 시작한 때. pausedMs: 그 칸에서 멈췄던 합. pausedAtMs: 지금 멈춘 때(없으면 도는 중).
 * beforeMs: 앞 칸들에서 실제로 한 시간(25분으로 이어 갈 때 넘겨받음)
 */
@Serializable
data class FocusClock(val segMin: Int, val segStartMs: Long, val pausedMs: Long = 0, val pausedAtMs: Long? = null, val beforeMs: Long = 0) {
    fun activeMs(now: Long): Long = (now - segStartMs - pausedMs - (pausedAtMs?.let { now - it } ?: 0)).coerceAtLeast(0)
    fun leftMs(now: Long): Long = segMin * 60_000L - activeMs(now)
    fun over(now: Long): Boolean = leftMs(now) <= 0
    fun overMs(now: Long): Long = (-leftMs(now)).coerceAtLeast(0)
    fun spentMs(now: Long): Long = beforeMs + activeMs(now)
    val paused: Boolean get() = pausedAtMs != null
    /** 이 칸이 끝나는 때(멈췄으면 null) */
    fun endAtMs(now: Long): Long? = if (paused) null else now + leftMs(now)
    fun pause(now: Long): FocusClock = if (paused) this else copy(pausedAtMs = now)
    fun resume(now: Long): FocusClock = pausedAtMs?.let { copy(pausedMs = pausedMs + (now - it), pausedAtMs = null) } ?: this
    fun switchTo(min: Int, now: Long): FocusClock = FocusClock(min, now, beforeMs = spentMs(now))

    companion object {
        fun start(min: Int, now: Long) = FocusClock(min, now)
    }
}

/** 남은·넘은 시간 글자 'M:SS' */
fun mmss(ms: Long): String {
    val s = ((ms.coerceAtLeast(0) + 999) / 1000)
    return "${s / 60}:%02d".format(s % 60)
}

/** 실제로 한 시간 글자 '4분 12초'·'48초' */
fun spentText(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val m = s / 60
    val r = s % 60
    return if (m == 0L) "${r}초" else "${m}분" + if (r > 0) " ${r}초" else ""
}
