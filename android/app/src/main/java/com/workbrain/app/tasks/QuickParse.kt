package com.workbrain.app.tasks

import com.workbrain.app.data.Place
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * 한 줄 빠른 입력 해석 결과(FR-08·TC-26, Design §4.2 2단계 ②).
 * 기한은 셋 중 하나: 날짜(+시각) / 모호한 원문(vague, 날짜로 바꾸지 않음) / 없음.
 * place가 null이면 '정리 안 됨'. assignee가 있으면 팔로업(owner_kind = other)
 */
data class QuickTask(
    val title: String,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val vague: String? = null,
    val priority: Int = 2,
    val place: Place? = null,
    val assignee: String? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * 규칙 해석기(AI 호출 없음, DEC-23). 목업 parseQuick과 같은 규칙에 DEC-70(지난 M/D는 다음 해)과
 * 한국어 조사('까지'·'에')·'이번 주 X요일'을 더했다. 못 알아본 말은 제목에 그대로 남긴다.
 * 낱말은 앞뒤가 띄어쓰기여야 알아본다('3시간'·'C#'·메일 주소의 '@'를 잘못 떼지 않으려고).
 * 날짜·시각끼리는 붙여 써도 된다('내일3시', '다음주월요일3시', '11/7오후3시'). 본문과는 띄어 쓴다.
 */
object QuickParse {
    const val MAX_TITLE = 200

    // 낱말 끝에 붙어도 기한으로 보는 조사: '금요일까지', '3시에'
    private const val TAIL = "(?:까지|에)?(?=\\s)"
    private const val HEAD = "(?<=\\s)"

    private val PRIORITY = Regex("$HEAD!(높음|보통|낮음|[123])(?=\\s)")
    private val PLACE = Regex("$HEAD#(\\S+)")
    private val PERSON = Regex("$HEAD@(\\S+)")
    private val RELATIVE = Regex("$HEAD(오늘|내일|모레)$TAIL")
    private val MONTH_DAY = Regex("$HEAD(\\d{1,2})/(\\d{1,2})$TAIL")
    private val WEEKDAY = Regex("$HEAD(?:(이번|다음)\\s?주\\s?)?([월화수목금토일])요일$TAIL")
    private val TIME = Regex("$HEAD(?:(오전|오후)\\s?)?(\\d{1,2})(?::(\\d{2})|시(?:\\s?(\\d{1,2})분|\\s?(반))?)$TAIL")
    private val VAGUE = Regex("$HEAD(다음\\s?주|이번\\s?주|다음\\s?달|이번\\s?달|월말)$TAIL")

    /**
     * 날짜 바로 뒤에 붙여 쓴 시각을 떼어 놓는다: '내일3시 미팅' → '내일 3시 미팅'(사용자 규칙 '시간 (띄고) 본문', DEC-70).
     * 날짜는 앞이 띄어쓰기, 시각은 뒤가 띄어쓰기일 때만 — '내일3시미팅'·'회의금요일3시'·'내일3시간'은 그대로 둔다
     */
    private val GLUED = Regex(
        "$HEAD(오늘|내일|모레|\\d{1,2}/\\d{1,2}|다음\\s?주|이번\\s?주|다음\\s?달|이번\\s?달|월말|(?:(?:이번|다음)\\s?주\\s?)?[월화수목금토일]요일)" +
            "(?=(?:(?:오전|오후)\\s?)?\\d{1,2}(?::\\d{2}|시(?:\\d{1,2}분|반)?)(?:까지|에)?\\s)",
    )

    private const val DAYS = "월화수목금토일"

    /**
     * raw: 입력 한 줄, today: 서울 오늘, places: 보관 안 된 프로젝트·반복실행,
     * defaultPlace: '#'이 없을 때 넣을 곳(상세 화면이면 그곳, 오늘·모든 할 일은 마지막에 넣은 곳),
     * noDueMeansToday: 오늘 탭 — 기한을 안 적으면 오늘 마감(목업 S02, 안 그러면 넣자마자 화면에서 사라진다)
     */
    fun parse(raw: String, today: LocalDate, places: List<Place>, defaultPlace: Place?, noDueMeansToday: Boolean = false): QuickTask {
        var s = GLUED.replace(" ${raw.replace(Regex("\\s"), " ")} ", "$1 ")
        val warnings = mutableListOf<String>()

        /** 처음 맞는 곳 하나만 본다. accept가 false면 지우지 않고 제목에 남긴다 */
        fun takeFirst(re: Regex, accept: (MatchResult) -> Boolean) {
            val m = re.find(s) ?: return
            if (accept(m)) s = s.replaceRange(m.range, " ")
        }

        var priority = 2
        takeFirst(PRIORITY) { m ->
            priority = when (m.groupValues[1]) { "높음", "1" -> 1; "낮음", "3" -> 3; else -> 2 }
            true
        }

        // '#'은 여러 번 쓸 수 있어 하나씩 본다. 처음 찾은 곳만 쓰고, 못 찾거나 여럿 걸리면 제목에 남기고 경고
        var place: Place? = null
        var searchFrom = 0
        while (true) {
            val m = PLACE.find(s, searchFrom) ?: break
            val word = m.groupValues[1]
            val found = if (place == null) findPlace(word, places, warnings) else null
            if (found != null) {
                place = found
                s = s.replaceRange(m.range, " ")
                searchFrom = m.range.first
            } else {
                if (place != null) warnings += "#$word · 넣을 곳은 하나만 골라요"
                searchFrom = m.range.last + 1
            }
        }

        var assignee: String? = null
        takeFirst(PERSON) { m -> assignee = m.groupValues[1].take(40); true }

        var date: LocalDate? = null
        takeFirst(RELATIVE) { m ->
            date = today.plusDays(when (m.groupValues[1]) { "내일" -> 1L; "모레" -> 2L; else -> 0L })
            true
        }
        if (date == null) takeFirst(MONTH_DAY) { m ->
            val (mo, d) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            monthDay(mo, d, today).also { if (it == null) warnings += "$mo/${d}는 없는 날짜예요" }?.let { date = it; true } ?: false
        }
        if (date == null) takeFirst(WEEKDAY) { m ->
            date = weekday(m.groupValues[1], DAYS.indexOf(m.groupValues[2]) + 1, today)
            true
        }

        var time: LocalTime? = null
        var timeText: String? = null
        takeFirst(TIME) { m ->
            val parsed = time(m)
            if (parsed == null) warnings += "${m.value.trim()}: 없는 시각이에요"
            parsed?.let { time = it; timeText = m.value.trim(); true } ?: false
        }

        var vague: String? = null
        if (date == null) takeFirst(VAGUE) { m -> vague = m.groupValues[1]; true }
        when {
            // '다음 주 3시'는 날짜를 모르니 시각까지 원문으로 둔다(날짜를 지어내지 않음, NFR-02)
            vague != null && timeText != null -> { vague = "$vague $timeText"; time = null }
            date == null && time != null -> date = today
            date == null && vague == null && noDueMeansToday -> date = today
        }

        var title = s.replace(Regex("\\s+"), " ").trim()
        if (title.length > MAX_TITLE) {
            title = title.take(MAX_TITLE)
            warnings += "제목은 ${MAX_TITLE}자까지만 저장해요"
        }
        return QuickTask(
            title = title,
            dueDate = date,
            dueTime = if (date != null) time else null,
            vague = vague,
            priority = priority,
            place = place ?: defaultPlace,
            assignee = assignee,
            warnings = warnings,
        )
    }

    /**
     * '#이름' → 곳. 띄어쓰기·대소문자 무시. 이름이 똑같은 곳 → 이름에 들어 있는 곳이 하나뿐일 때 그곳.
     * 여럿이면 고르지 않는다(엉뚱한 곳에 몰래 넣지 않게)
     */
    private fun findPlace(word: String, places: List<Place>, warnings: MutableList<String>): Place? {
        fun norm(x: String) = x.replace(Regex("\\s"), "").lowercase()
        val w = norm(word)
        places.firstOrNull { norm(it.name) == w }?.let { return it }
        val hits = places.filter { norm(it.name).contains(w) }
        when {
            hits.size == 1 -> return hits.first()
            hits.isEmpty() -> warnings += "#$word 프로젝트를 못 찾았어요"
            else -> warnings += "#$word: 맞는 곳이 여럿이에요(${hits.take(2).joinToString(", ") { it.name }}${if (hits.size > 2) " …" else ""}) · 더 길게 적어 주세요"
        }
        return null
    }

    /** 올해 그 날. 이미 지났으면 다음 해(DEC-70). 없는 날짜면 null */
    private fun monthDay(month: Int, day: Int, today: LocalDate): LocalDate? {
        fun at(year: Int) = runCatching { LocalDate.of(year, month, day) }.getOrNull()
        val thisYear = at(today.year)
        return when {
            thisYear != null && !thisYear.isBefore(today) -> thisYear
            // 2/29처럼 올해엔 없지만 내년엔 있을 수 있는 날도 있다
            month in 1..12 -> at(today.year + 1)
            else -> null
        }
    }

    /** 한 주는 월요일 시작(DEC-31). 그냥 'X요일'은 오늘을 포함해 가장 가까운 X요일 */
    private fun weekday(which: String, dow: Int, today: LocalDate): LocalDate {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (which) {
            "이번" -> monday.plusDays(dow - 1L)
            "다음" -> monday.plusDays(7L + dow - 1L)
            else -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dow)))
        }
    }

    /** 오전·오후가 없는 1~7시는 업무 시간대로 보고 오후로 한다(목업). '15:00'처럼 쌍점으로 쓴 것은 그대로 */
    private fun time(m: MatchResult): LocalTime? {
        val (ampm, hText, colonMin, min, half) = m.destructured
        var hour = hText.toInt()
        val minute = when {
            colonMin.isNotEmpty() -> colonMin.toInt()
            min.isNotEmpty() -> min.toInt()
            half.isNotEmpty() -> 30
            else -> 0
        }
        if (hour > 23 || minute > 59) return null
        when {
            ampm == "오후" && hour < 12 -> hour += 12
            ampm == "오전" && hour == 12 -> hour = 0
            ampm.isEmpty() && colonMin.isEmpty() && hour in 1..7 -> hour += 12
        }
        return LocalTime.of(hour, minute)
    }
}
