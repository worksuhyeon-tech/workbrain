package com.workbrain.app.tasks

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.record.ChosenProject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.Server
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.toNewTaskFields
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.saveFailText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/*
 * 한 줄 빠른 입력(목업 QUICK_BAR, FR-08·TC-26, Design §4.2 2단계 ②): 입력 + [추가] + 미리보기 칩 + 단축어 설명.
 * 상태·저장은 rememberQuickAdd 하나로, 모양은 카드 안 입력 줄(QuickAddBar, 프로젝트·반복실행 상세)과
 * 아래 떠 있는 띠(QuickAddDock, '오늘'·'모든 할 일', DEC-73) 두 가지다. 해석은 QuickParse(AI 없음).
 */

// 목업 예시는 접은 화면에서 '…'로 잘려서 짧게 두고, 단축어는 설명 줄에 쓴다(11/06 사용자 요청)
const val QUICK_PLACEHOLDER = "+ 빠르게 추가 · 예: 내일3시 업체 미팅"
private const val MAX_INPUT = 300

/** 설명 줄: 단축어 → 뜻. 마이크는 짧게 누르면 말로 넣기, 길게 누르면 녹음 메모(DEC-89) */
private val HINTS = listOf(
    "#프로젝트" to "넣을 곳", "!높음·!낮음" to "우선순위", "@이름" to "팔로업", "내일3시·금요일·10/24" to "기한", "마이크 길게" to "녹음 메모",
)

/**
 * 빠른 입력 줄마다(today·all·place:…) 적던 글을 화면 밖에 둔다(DEC-89).
 * 접기·펼치기로 입력 줄 화면이 다시 그려져도 적던 글·받아쓴 글이 지워지지 않게 한다(NFR-05). 앱을 닫으면 비워진다
 */
private object QuickDrafts {
    private val texts = mutableStateMapOf<String, String>()
    fun get(key: String): String = texts[key].orEmpty()
    fun set(key: String, value: String) { if (value.isEmpty()) texts.remove(key) else texts[key] = value }
}

/**
 * 이 기기에서 마지막에 할 일을 넣거나 옮긴 곳(DEC-70, DEC-91). 'project:ID'·'area:ID', 정리 안 됨이면 빈 값.
 * 빠른 입력으로 넣을 때뿐 아니라 고치기 서랍 '어디에'·끌어 놓기로 옮길 때도 기억한다.
 * 넣고 나서 옮겨도 다음 입력이 옛 곳으로 들어가던 문제(11/11 사용자 제보: 다른 프로젝트로 옮긴 뒤에도 늘 처음 곳)를 막는다.
 * 화면마다 따로 읽어 두면 다른 화면에서 바꾼 것을 모르므로, 바꾼 값을 한곳(changed)에 두고 모든 입력 줄이 같이 본다
 */
object QuickAddMemory {
    private const val PREFS = "quick_add"
    private const val KEY = "last_place"

    // 이 실행에서 바꾼 값. 없으면 기기에 저장된 값을 읽는다. 읽는 화면이 다시 그려지도록 Compose 상태로 둔다
    private var changed by mutableStateOf<String?>(null)

    /** 기억한 곳이 지금도 보관 안 된 곳이면 그곳, 아니면 '정리 안 됨'(null) */
    fun last(context: Context, places: List<Place>): Place? {
        val saved = changed ?: prefs(context).getString(KEY, null)
        if (saved.isNullOrEmpty()) return null
        return places.firstOrNull { key(it) == saved }
    }

    /** place가 null이면 '정리 안 됨'으로 넣거나 옮긴 것 */
    fun save(context: Context, place: Place?) {
        val value = place?.let(::key).orEmpty()
        changed = value
        prefs(context).edit().putString(KEY, value).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(p: Place) = "${p.type.table}:${p.id}"
}

/** 해석 결과 → 저장·서랍에 쓰는 값. 시각이 있는 기한은 서울 시각으로(날짜만 있으면 날짜만) */
fun QuickTask.toEdit(): TaskEdit = TaskEdit(
    title = title,
    priority = priority,
    ownerKind = if (assignee != null) "other" else "me",
    assignee = assignee,
    dueDate = dueDate?.toString(),
    dueTime = dueDate?.let { d -> dueTime?.let { t -> ZonedDateTime.of(d, t, SEOUL).toOffsetDateTime().toString() } },
    dueAmbiguous = vague != null,
    dueText = vague,
    projectId = place?.id?.takeIf { place.type == PlaceType.PROJECT },
    areaId = place?.id?.takeIf { place.type == PlaceType.AREA },
)

/** 새 할 일 서랍에서 고친 값 → 넣은 뒤 알림을 만드는 화면이 읽는 해석 결과 모양 */
private fun TaskEdit.toQuickTask(places: List<Place>): QuickTask {
    val timed = dueTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL) }.getOrNull() }
    return QuickTask(
        title = title,
        dueDate = timed?.toLocalDate() ?: dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        dueTime = timed?.toLocalTime(),
        vague = dueText.takeIf { dueAmbiguous },
        priority = priority ?: 2,
        place = places.firstOrNull { (it.type == PlaceType.PROJECT && it.id == projectId) || (it.type == PlaceType.AREA && it.id == areaId) },
        assignee = assignee.takeIf { ownerKind == "other" },
    )
}

/** 미리보기·알림의 기한 글자: 오늘 / M/D / 다른 해면 YYYY. M/D, 시각이 있으면 뒤에 HH:mm */
fun quickDueLabel(r: QuickTask, today: LocalDate): String? {
    val d = r.dueDate ?: return null
    val day = when {
        d == today -> "오늘"
        d.year != today.year -> "${d.year}. ${d.monthValue}/${d.dayOfMonth}"
        else -> "${d.monthValue}/${d.dayOfMonth}"
    }
    return day + (r.dueTime?.let { " %02d:%02d".format(it.hour, it.minute) } ?: "")
}

/** 넣은 뒤 알림의 기본 글자(목업): '추가했어요 · 곳( · 팔로업)' */
fun quickAddedText(r: QuickTask): String =
    "추가했어요 · ${r.place?.name ?: UNSORTED}" + if (r.assignee != null) " · 팔로업" else ""

/**
 * 빠른 입력 한 벌의 지금 값과 동작. 카드 안 입력 줄(QuickAddBar)과 아래 떠 있는 띠(QuickAddDock, DEC-73)가 같이 쓴다.
 * shownDay: 미리보기를 계산한 날. 추가를 누를 때 날짜가 바뀌었으면(자정) 미리보기와 다르게 저장되지 않게 다시 보여 준다(TC-26)
 */
class QuickAdd(
    val text: String,
    val onText: (String) -> Unit,
    val parsed: QuickTask,
    val shownDay: LocalDate,
    val saving: Boolean,
    val error: String?,
    /** 해석한 그대로 바로 넣기 */
    val add: () -> Unit,
    /** 새 할 일 서랍에서 고친 값으로 넣기(DEC-73 '자세히'). 실패 이유는 서랍 안에 보여 준다 */
    val addEdited: (edit: TaskEdit, onSaved: () -> Unit, onFailed: (String) -> Unit) -> Unit,
    /** '#'이 여러 곳에 걸릴 때 후보 칩을 누르면 입력의 '#…'을 그 이름으로 바꾼다(DEC-80) */
    val pickPlace: (Place) -> Unit,
    /** 이 입력 줄의 이름(today·all·place:…). 말로 넣기 결과가 이 줄로 온다(DEC-89) */
    val draftKey: String,
    /** 말로 넣기: 듣는 중 · 중간 글 · 안내(권한 거부·못 들음·기본 인식 안내) */
    val listening: Boolean,
    val partial: String,
    val voiceMessage: VoiceInput.Message?,
    /** 지금 글이 말로 넣은 것이라 키보드 없이 미리보기 카드를 띄워 둘 때(사용자 선택 11/11) */
    val fromVoice: Boolean,
)

/**
 * places: 보관 안 된 프로젝트·반복실행(# 찾기·마지막에 넣은 곳 확인).
 * fixedPlace: 상세 화면이면 그곳('#'이 없을 때). null이면 이 기기에서 마지막에 넣거나 옮긴 곳(DEC-70·91).
 * noDueMeansToday: 오늘 탭(기한을 안 적으면 오늘). draftKey: 입력 줄 이름(적던 글·말로 넣기 결과를 화면 밖에 두는 열쇠).
 * onAdded: 넣은 뒤 — 화면은 다시 읽고 알림을 띄운다. 저장이 안 되면 입력을 그대로 둔다(목업 '미저장 표시')
 */
@Composable
fun rememberQuickAdd(places: List<Place>, fixedPlace: Place?, noDueMeansToday: Boolean, draftKey: String, onAdded: (QuickTask) -> Unit): QuickAdd {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val text = QuickDrafts.get(draftKey)
    fun setText(value: String) = QuickDrafts.set(draftKey, value.take(MAX_INPUT))
    var fromVoice by remember { mutableStateOf(false) }
    val voice by VoiceInput.state.collectAsStateWithLifecycle()
    val mine = voice.key == draftKey
    // 다 들은 글을 이 입력 줄 뒤에 이어 붙인다. 다른 화면에 가 있는 동안 나온 글은 돌아왔을 때 붙는다
    val heard by VoiceInput.heard.collectAsStateWithLifecycle()
    LaunchedEffect(heard) {
        val h = heard?.takeIf { it.key == draftKey } ?: return@LaunchedEffect
        VoiceInput.consume(h)
        setText(VoiceText.append(QuickDrafts.get(draftKey), h.text))
        fromVoice = true
    }
    var shownDay by remember { mutableStateOf(LocalDate.now(SEOUL)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val remembered = QuickAddMemory.last(context, places)
    val parsed = remember(text, places, fixedPlace, remembered, shownDay, noDueMeansToday) {
        QuickParse.parse(text, shownDay, places, fixedPlace ?: remembered, noDueMeansToday)
    }

    /** 서버에 넣고, 넣은 곳을 기억하고, 입력을 비운다. 다른 기기에서 그곳을 막 보관했으면 서버가 WB409로 막는다 → 그 이유를 쓴다 */
    fun insert(edit: TaskEdit, onSaved: () -> Unit, onFailed: (String) -> Unit) {
        saving = true
        scope.launch {
            runCatching { Server.createTask(edit.toNewTaskFields()) }
                .onSuccess {
                    val r = edit.toQuickTask(places)
                    QuickAddMemory.save(context, r.place)
                    setText("")
                    fromVoice = false
                    onSaved()
                    onAdded(r)
                }
                .onFailure { onFailed(if (it.message.orEmpty().contains("아카이브")) saveFailText(it) else UNSAVED_TEXT) }
            saving = false
        }
    }

    fun add() {
        if (saving) return
        val now = LocalDate.now(SEOUL)
        if (now != shownDay) {
            shownDay = now
            error = "날짜가 바뀌었어요. 미리보기를 확인하고 다시 눌러 주세요"
            return
        }
        if (parsed.title.isBlank()) { error = "할 일 제목을 입력해 주세요"; return }
        error = null
        insert(parsed.toEdit(), onSaved = {}, onFailed = { error = it })
    }

    fun addEdited(edit: TaskEdit, onSaved: () -> Unit, onFailed: (String) -> Unit) {
        if (!saving) insert(edit, onSaved, onFailed)
    }

    fun pickPlace(place: Place) {
        val asked = parsed.placeAsked ?: return
        setText(QuickParse.choosePlace(text, asked, place))
        error = null
    }

    fun onText(value: String) {
        setText(value)
        error = null
        VoiceInput.clearMessage(draftKey)
        if (value.isEmpty()) fromVoice = false
    }

    return QuickAdd(
        text, ::onText, parsed, shownDay, saving, error, ::add, ::addEdited, ::pickPlace,
        draftKey = draftKey,
        listening = mine && voice.listening,
        partial = if (mine) voice.partial else "",
        voiceMessage = voice.message.takeIf { mine },
        fromVoice = fromVoice && text.isNotBlank(),
    )
}

/**
 * 카드 안 한 줄 입력(목업 .qa): 프로젝트·반복실행 상세 할 일 카드(S03 .card-qa).
 * '오늘'·'모든 할 일'은 아래 떠 있는 띠(QuickAddDock, DEC-73)를 쓴다
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddBar(
    places: List<Place>,
    modifier: Modifier = Modifier,
    fixedPlace: Place? = null,
    onAdded: (QuickTask) -> Unit,
) {
    val quick = rememberQuickAdd(places, fixedPlace, noDueMeansToday = false, draftKey = "place:${fixedPlace?.type?.table}:${fixedPlace?.id}", onAdded)
    // 마이크를 길게 눌러 녹음하면 이 프로젝트를 녹음 프로젝트로 미리 고른다. 반복실행은 녹음 화면에서 고를 수 없어 '나중에'(11/11 사용자 선택)
    val recordProject = fixedPlace?.takeIf { it.type == PlaceType.PROJECT }?.let { ChosenProject(it.id, it.name) }
    Column(modifier) {
        // 목업 .qa: 입력 칸이 남은 폭을 다 쓰고 오른쪽에 마이크·[추가]
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = quick.text,
                onValueChange = quick.onText,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Wb.colors.ink),
                cursorBrush = SolidColor(Wb.colors.primary),
                // 넣은 뒤에도 키보드를 닫지 않아 이어서 적을 수 있다(목업: 추가 뒤 입력 칸에 다시 초점)
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { quick.add() }),
                modifier = Modifier.weight(1f).height(44.dp),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxSize().background(Wb.colors.card, ControlShape)
                            .border(1.dp, Wb.colors.borderStrong, ControlShape).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (quick.text.isEmpty()) Text(QUICK_PLACEHOLDER, fontSize = 14.sp, color = Wb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            MicButton(quick, recordProject)
            PrimaryButton(if (quick.saving) "추가 중…" else "추가", onClick = quick.add, enabled = !quick.saving)
        }
        VoiceStatus(quick, Modifier.padding(top = 6.dp))
        QuickHint()
        // 목업 .qa-prev: 적는 동안 해석 결과를 칩으로(화면 읽기 프로그램도 바뀔 때마다 읽는다)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = if (quick.text.isNotBlank()) 8.dp else 0.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (quick.text.isNotBlank()) QuickPreview(quick.parsed, quick.shownDay, quick.pickPlace)
        }
        quick.error?.let { Text(it, fontSize = 12.sp, color = Wb.colors.crit, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(10.dp))
    }
}

/** 입력 줄 아래 단축어 설명. 묶음 사이에서만 줄을 바꾼다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickHint() {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HINTS.forEach { (token, meaning) ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Wb.colors.body)) { append(token) }
                    append(" $meaning")
                },
                fontSize = 12.sp, color = Wb.colors.muted, maxLines = 1,
            )
        }
        Text("본문과는 한 칸 띄어 쓰기", fontSize = 12.sp, color = Wb.colors.muted, maxLines = 1)
    }
}

/** 목업 .qa-prev 칩들: 제목 · 기한 · 모호 · 우선순위 · 들어갈 곳 · 팔로업 · 경고 · ('#'이 여럿에 걸리면) 고를 곳 */
@Composable
fun QuickPreview(r: QuickTask, today: LocalDate, onPickPlace: (Place) -> Unit) {
    QuickChip(WbIcons.Pencil, r.title.ifEmpty { "제목 없음" }, italic = r.title.isEmpty())
    quickDueLabel(r, today)?.let { QuickChip(WbIcons.Calendar, it) }
    r.vague?.let { QuickChip(WbIcons.Calendar, "$it · 확인 필요", warn = true) }
    // 줄과 같은 기호(DEC-103): 높음 주황 별 · 낮음 회색 아래 화살표
    when (r.priority) {
        1 -> QuickChip(WbIcons.Star, "높음", iconTint = Wb.colors.star)
        3 -> QuickChip(WbIcons.PriorityLow, "낮음", iconTint = Wb.colors.muted)
    }
    QuickChip(placeIcon(r.place?.type), r.place?.name ?: UNSORTED)
    r.assignee?.let { QuickChip(WbIcons.Person, "$it · 팔로업") }
    r.warnings.forEach { QuickChip(WbIcons.Alert, it, warn = true) }
    r.placeChoices.forEach { PlaceChoiceChip(it) { onPickPlace(it) } }
}

/** '#' 후보 칩(DEC-80): 누를 수 있게 미리보기 칩보다 크게, 파란 테두리 */
@Composable
private fun PlaceChoiceChip(place: Place, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 36.dp).clip(PillShape)
            .clickable(onClickLabel = "${place.name}에 넣기", onClick = onClick)
            .background(Wb.colors.card, PillShape)
            .border(1.dp, Wb.colors.primary, PillShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(placeIcon(place.type), contentDescription = null, tint = Wb.colors.primary, modifier = Modifier.size(14.dp))
        Text(
            place.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp),
        )
    }
}

/** 목업 .qc: 작은 테두리 알약(경고는 주황 글씨·테두리) */
@Composable
private fun QuickChip(icon: ImageVector, text: String, warn: Boolean = false, italic: Boolean = false, iconTint: Color? = null) {
    val color = if (warn) Wb.colors.warn else Wb.colors.body
    Row(
        Modifier.background(Wb.colors.surface, PillShape)
            .border(1.dp, if (warn) Wb.colors.warn.copy(alpha = 0.35f) else Wb.colors.border, PillShape)
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint ?: color, modifier = Modifier.size(12.dp))
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
            fontStyle = if (italic) FontStyle.Italic else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp),
        )
    }
}
