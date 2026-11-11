package com.workbrain.app.tasks

import com.workbrain.app.data.Place
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * 한 줄 빠른 입력 해석 결과(FR-08·TC-26, Design §4.2 2단계 ②).
 * 기한은 셋 중 하나: 날짜(+시각) / 모호한 원문(vague, 날짜로 바꾸지 않음) / 없음.
 * place가 null이면 '정리 안 됨'. assignee가 있으면 팔로업(owner_kind = other).
 * '#'이 여러 곳에 걸리면 placeChoices(최대 5곳)를 칩으로 보여 고르게 하고, 고르면 입력의 placeAsked를 그 이름으로 바꾼다(DEC-80)
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
    val placeChoices: List<Place> = emptyList(),
    val placeAsked: String? = null,
)

/**
 * 규칙 해석기(AI 호출 없음, DEC-23). 목업 parseQuick과 같은 규칙에 DEC-70(지난 M/D는 다음 해)과
 * 한국어 조사('까지'·'에')·'이번 주 X요일'을 더했다. 못 알아본 말은 제목에 그대로 남긴다.
 * 낱말은 앞뒤가 띄어쓰기여야 알아본다('3시간'·'C#'·메일 주소의 '@'를 잘못 떼지 않으려고).
 * 날짜·시각끼리는 붙여 써도 된다('내일3시', '다음주월요일3시', '11/7오후3시', '내일아침9시'). 본문과는 띄어 쓴다.
 * 시간대 낱말(DEC-97): 아침·새벽 = 오전, 저녁·밤 = 오후, 점심은 그냥 시각과 같다('아침9시' = 9:00, '저녁7시' = 19:00).
 * 숫자 없이 쓴 아침·점심·오후·퇴근전은 날짜 바로 뒤에서만 정해 둔 시각으로 읽는다('내일 아침' = 9:00) —
 * 혼자 쓴 '점심'은 '팀 점심 메뉴'처럼 제목의 말일 때가 많아서 그대로 둔다.
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
    // 시각 앞에 붙는 낱말: 오전·오후 + 시간대 낱말(DEC-97)
    private const val DAYPART = "오전|오후|아침|새벽|점심|저녁|밤"
    private val TIME = Regex("$HEAD(?:($DAYPART)\\s?)?(\\d{1,2})(?::(\\d{2})|시(?:\\s?(\\d{1,2})분|\\s?(반))?)$TAIL")
    private val VAGUE = Regex("$HEAD(다음\\s?주|이번\\s?주|다음\\s?달|이번\\s?달|월말)$TAIL")

    // 숫자 없이 날짜 바로 뒤에 쓰면 정해 둔 시각으로 읽는 낱말(DEC-97, 사용자 업무 흐름). 저녁·밤·새벽·오전은 숫자가 있어야 읽는다
    private const val SLOT_WORDS = "아침|점심|오후|퇴근\\s?전"
    private val SLOT = Regex("$HEAD($SLOT_WORDS)$TAIL")
    private val SLOT_TIME = mapOf(
        "아침" to LocalTime.of(9, 0),
        "점심" to LocalTime.of(13, 0),
        "오후" to LocalTime.of(16, 0),
        "퇴근전" to LocalTime.of(18, 0),
    )
    // '밤 12시'는 그날 밤 자정 = 그날 마감 끝으로 본다(다음 날 0시로 넘기지 않음)
    private val END_OF_DAY = LocalTime.of(23, 59)

    /**
     * 날짜 바로 뒤에 붙여 쓴 시각·시간대 낱말을 떼어 놓는다: '내일3시 미팅' → '내일 3시 미팅'(사용자 규칙 '시간 (띄고) 본문', DEC-70),
     * '내일아침9시' → '내일 아침9시', '내일아침' → '내일 아침'(DEC-97).
     * 날짜는 앞이 띄어쓰기, 시각은 뒤가 띄어쓰기일 때만 — '내일3시미팅'·'회의금요일3시'·'내일3시간'은 그대로 둔다
     */
    private val GLUED = Regex(
        "$HEAD(오늘|내일|모레|\\d{1,2}/\\d{1,2}|다음\\s?주|이번\\s?주|다음\\s?달|이번\\s?달|월말|(?:(?:이번|다음)\\s?주\\s?)?[월화수목금토일]요일)" +
            "(?=(?:(?:$DAYPART)\\s?)?\\d{1,2}(?::\\d{2}|시(?:\\d{1,2}분|반)?)(?:까지|에)?\\s|(?:$SLOT_WORDS)(?:까지|에)?\\s)",
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

        // '#'은 여러 번 쓸 수 있어 하나씩 본다. 처음 찾은 곳만 쓰고, 못 찾거나 여럿 걸리면 제목에 남기고 경고(여럿이면 고를 칩)
        var place: Place? = null
        var placeChoices = emptyList<Place>()
        var placeAsked: String? = null
        var searchFrom = 0
        while (true) {
            val m = PLACE.find(s, searchFrom) ?: break
            val word = m.groupValues[1]
            if (place != null) {
                warnings += "#$word · 넣을 곳은 하나만 골라요"
                searchFrom = m.range.last + 1
                continue
            }
            val next = followingWords(s, m.range.last + 1)
            val found = matchPlace(word, next.map { it.first }, places)
            if (found.place != null) {
                place = found.place
                // 좁히는 데 쓴 다음 낱말까지 제목에서 뺀다
                val end = if (found.extra > 0) next[found.extra - 1].second else m.range.last + 1
                s = s.replaceRange(m.range.first, end, " ")
                searchFrom = m.range.first
                placeChoices = emptyList()
                placeAsked = null
                continue
            }
            val asked = "#" + (listOf(word) + next.take(found.extra).map { it.first }).joinToString(" ")
            if (found.hits.isEmpty()) {
                warnings += "$asked 프로젝트를 못 찾았어요"
            } else {
                val more = if (found.hits.size > MAX_CHOICES) "고르거나 더 적어 주세요" else "골라 주세요"
                warnings += "$asked: 맞는 곳이 ${found.hits.size}곳이에요 · 아래에서 $more"
                if (placeChoices.isEmpty()) {
                    placeChoices = found.hits.take(MAX_CHOICES)
                    placeAsked = asked
                }
            }
            searchFrom = m.range.last + 1
        }

        var assignee: String? = null
        takeFirst(PERSON) { m ->
            val who = m.groupValues[1].take(40)
            assignee = who
            // '@'는 담당자, '#'은 넣을 곳. 프로젝트 이름과 맞으면 헷갈렸을 수 있어 알려만 준다(저장은 막지 않음, DEC-97)
            if (who.length >= 2 && placesFor(who, places).isNotEmpty()) warnings += "@$who${topic(who)} 담당자로 들어가요 · 프로젝트면 #$who"
            true
        }

        var date: LocalDate? = null
        // 날짜 낱말이 있던 자리: 바로 뒤에 숫자 없이 쓴 '아침'·'퇴근전'을 찾는 데 쓴다
        var dateAt = -1
        takeFirst(RELATIVE) { m ->
            date = today.plusDays(when (m.groupValues[1]) { "내일" -> 1L; "모레" -> 2L; else -> 0L })
            dateAt = m.range.first
            true
        }
        if (date == null) takeFirst(MONTH_DAY) { m ->
            val (mo, d) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            monthDay(mo, d, today).also { if (it == null) warnings += "$mo/${d}는 없는 날짜예요" }
                ?.let { date = it; dateAt = m.range.first; true } ?: false
        }
        if (date == null) takeFirst(WEEKDAY) { m ->
            date = weekday(m.groupValues[1], DAYS.indexOf(m.groupValues[2]) + 1, today)
            dateAt = m.range.first
            true
        }

        var time: LocalTime? = null
        var timeText: String? = null
        var timeRejected = false
        takeFirst(TIME) { m ->
            val parsed = time(m)
            if (parsed == null) { warnings += timeWarning(m); timeRejected = true }
            parsed?.let { time = it; timeText = m.value.trim(); true } ?: false
        }
        // 시각을 안 적었을 때만: 날짜 바로 뒤(사이엔 띄어쓰기만)의 시간대 낱말. TIME이 아무것도 안 지웠으니 dateAt은 그대로 맞다.
        // '내일 오후 25시'처럼 적은 시각이 틀렸으면 정해 둔 시각으로 덮지 않는다(경고만)
        if (date != null && time == null && !timeRejected) {
            SLOT.find(s, dateAt)?.takeIf { s.substring(dateAt, it.range.first).isBlank() }?.let { m ->
                time = SLOT_TIME.getValue(m.groupValues[1].replace(" ", ""))
                s = s.replaceRange(m.range, " ")
            }
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
            placeChoices = placeChoices,
            placeAsked = placeAsked,
        )
    }

    /**
     * 후보 칩을 눌렀을 때: 입력에서 asked('#CRM 12'처럼 좁히는 데 쓴 낱말까지)를 '#이름'(띄어쓰기 뺀 정확한 이름)으로 바꾼다.
     * 그 사이에 다른 단축어가 끼어 통째로 못 찾으면 '#' 낱말 하나만 바꾼다
     */
    fun choosePlace(raw: String, asked: String, place: Place): String {
        val words = asked.removePrefix("#").split(" ")
        val replacement = "#" + place.name.replace(WHITESPACE, "")
        for (ws in listOf(words, words.take(1))) {
            val m = Regex("(?<=^|\\s)#" + ws.joinToString("\\s+") { Regex.escape(it) } + "(?=\\s|$)").find(raw) ?: continue
            return raw.replaceRange(m.range, replacement)
        }
        return raw
    }

    private const val MAX_CHOICES = 5
    private const val MAX_EXTRA_WORDS = 3
    private val WHITESPACE = Regex("\\s")
    private val NEXT_WORD = Regex("\\s+(\\S+)")
    private val NOT_WORD_CHAR = Regex("[^\\p{L}\\p{N}]+")

    /** '#낱말' 뒤에 띄어 쓴 낱말들(최대 3개)과 각 끝 위치. 다른 단축어(#·@·!)가 나오면 멈춘다 */
    private fun followingWords(s: String, from: Int): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        var at = from
        while (out.size < MAX_EXTRA_WORDS) {
            val m = NEXT_WORD.find(s, at)?.takeIf { it.range.first == at } ?: break
            val word = m.groupValues[1]
            if (word.first() in "#@!") break
            out += word to m.range.last + 1
            at = m.range.last + 1
        }
        return out
    }

    /** 찾은 곳(하나일 때만) · 좁히는 데 쓴 다음 낱말 수 · 맞는 곳들 */
    private class PlaceMatch(val place: Place?, val extra: Int, val hits: List<Place>)

    /**
     * '#낱말'로 찾고, 여럿이면 띄어 쓴 다음 낱말을 하나씩 붙여 좁힌다(DEC-80). 붙여서 하나도 안 맞으면 그 낱말은 본문이라 멈춘다.
     * 하나로 정해진 뒤에도 다음 낱말이 그 이름에 이어 들어 있으면 이름의 일부로 보고 함께 뺀다
     * ('#CRM 이관 12건 보고서' → 'CRM 이관'에서 정해져도 '12건'까지 이름, DEC-82). 다른 곳으로 바뀌면 멈춘다.
     * 하나로 좁혀질 때만 고른다(엉뚱한 곳에 몰래 넣지 않게)
     */
    private fun matchPlace(word: String, next: List<String>, places: List<Place>): PlaceMatch {
        var hits = placesFor(word, places)
        var extra = 0
        while (hits.isNotEmpty() && extra < next.size) {
            val narrowed = placesFor((listOf(word) + next.take(extra + 1)).joinToString(" "), places)
            if (narrowed.isEmpty() || (hits.size == 1 && narrowed != hits)) break
            hits = narrowed
            extra++
        }
        return PlaceMatch(hits.singleOrNull(), extra, hits)
    }

    /**
     * 맞는 곳을 세 단계로 찾고, 앞 단계에서 하나라도 맞으면 거기서 멈춘다:
     * ① 이름이 똑같음 ② 이름에 그대로 들어 있음(띄어쓰기·대소문자 무시)
     * ③ 조각 맞추기 — 영문·숫자·한글 덩어리가 이름에 순서대로 다 있음(문장부호 무시): 'CRM 12건' → 'CRM) 이관 12건'
     */
    private fun placesFor(query: String, places: List<Place>): List<Place> {
        val k = key(query)
        places.filter { key(it.name) == k }.ifEmpty { null }?.let { return it }
        places.filter { key(it.name).contains(k) }.ifEmpty { null }?.let { return it }
        val pieces = pieces(query)
        if (pieces.isEmpty()) return emptyList()
        return places.filter { inOrder(loose(it.name), pieces) }
    }

    private fun key(x: String) = x.replace(WHITESPACE, "").lowercase()

    /** 글자·숫자만(문장부호·띄어쓰기 뺌) */
    private fun loose(x: String) = x.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 띄어쓰기·문장부호에서 자르고, 영문·숫자·그 밖의 글자(한글 등)가 바뀌는 곳에서 한 번 더 자른 덩어리:
     * 'CRM12건' → crm · 12 · 건, '사고 개편' → 사고 · 개편
     */
    private fun pieces(query: String): List<String> {
        fun kind(c: Char) = when {
            c in 'a'..'z' -> 0
            c.isDigit() -> 1
            else -> 2
        }
        val out = mutableListOf<String>()
        for (word in query.split(NOT_WORD_CHAR)) {
            var last = -1
            val run = StringBuilder()
            for (c in loose(word)) {
                val k = kind(c)
                if (k != last && run.isNotEmpty()) { out += run.toString(); run.clear() }
                run.append(c)
                last = k
            }
            if (run.isNotEmpty()) out += run.toString()
        }
        return out
    }

    private fun inOrder(name: String, pieces: List<String>): Boolean {
        var from = 0
        for (p in pieces) {
            val at = name.indexOf(p, from)
            if (at < 0) return false
            from = at + p.length
        }
        return true
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

    /**
     * 오전·오후가 없는 1~7시는 업무 시간대로 보고 오후로 한다(목업). '15:00'처럼 쌍점으로 쓴 것은 그대로.
     * 아침·새벽은 오전, 저녁·밤은 오후, 점심은 앞 낱말이 없는 것과 같다(DEC-97).
     * 저녁·밤 12시는 그날 23:59, 밤 1~5시는 날짜가 넘어가 헷갈리므로 읽지 않는다(새벽으로 적게 안내)
     */
    private fun time(m: MatchResult): LocalTime? {
        val (part, hText, colonMin, min, half) = m.destructured
        var hour = hText.toInt()
        val minute = when {
            colonMin.isNotEmpty() -> colonMin.toInt()
            min.isNotEmpty() -> min.toInt()
            half.isNotEmpty() -> 30
            else -> 0
        }
        if (hour > 23 || minute > 59) return null
        val morning = part == "오전" || part == "아침" || part == "새벽"
        val evening = part == "저녁" || part == "밤"
        if (part == "밤" && hour in 1..5) return null
        if (evening && hour == 12) return END_OF_DAY
        when {
            (part == "오후" || evening) && hour < 12 -> hour += 12
            morning && hour == 12 -> hour = 0
            (part.isEmpty() || part == "점심") && colonMin.isEmpty() && hour in 1..7 -> hour += 12
        }
        return LocalTime.of(hour, minute)
    }

    private fun timeWarning(m: MatchResult): String {
        val text = m.value.trim()
        val hour = m.groupValues[2].toIntOrNull()
        return if (m.groupValues[1] == "밤" && hour in 1..5) "$text: '새벽 ${hour}시'로 적어 주세요" else "$text: 없는 시각이에요"
    }

    /** 이름 뒤 조사 '은/는'(받침 있으면 은). 한글이 아니면 '은(는)' */
    private fun topic(word: String): String {
        val c = word.last()
        return when {
            c !in '가'..'힣' -> "은(는)"
            (c - '가') % 28 != 0 -> "은"
            else -> "는"
        }
    }
}
