package com.workbrain.app.tasks

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** 설명 줄: 단축어 → 뜻 */
private val HINTS = listOf("#프로젝트" to "넣을 곳", "!높음·!낮음" to "우선순위", "@이름" to "팔로업", "내일3시·금요일·10/24" to "기한")

/** 이 기기에서 빠른 입력으로 마지막에 넣은 곳(DEC-70). 'project:ID'·'area:ID', 정리 안 됨이면 빈 값 */
private object QuickAddMemory {
    private const val PREFS = "quick_add"
    private const val KEY = "last_place"

    /** 기억한 곳이 지금도 보관 안 된 곳이면 그곳, 아니면 '정리 안 됨'(null) */
    fun last(context: Context, places: List<Place>): Place? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return places.firstOrNull { key(it) == saved }
    }

    fun save(context: Context, place: Place?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, place?.let(::key).orEmpty()).apply()
    }

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
)

/**
 * places: 보관 안 된 프로젝트·반복실행(# 찾기·마지막에 넣은 곳 확인).
 * fixedPlace: 상세 화면이면 그곳('#'이 없을 때). null이면 이 기기에서 마지막에 넣은 곳(DEC-70).
 * noDueMeansToday: 오늘 탭(기한을 안 적으면 오늘). onAdded: 넣은 뒤 — 화면은 다시 읽고 알림을 띄운다.
 * 저장이 안 되면 입력을 그대로 둔다(목업 '미저장 표시')
 */
@Composable
fun rememberQuickAdd(places: List<Place>, fixedPlace: Place?, noDueMeansToday: Boolean, onAdded: (QuickTask) -> Unit): QuickAdd {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var shownDay by remember { mutableStateOf(LocalDate.now(SEOUL)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var remembered by remember(places) { mutableStateOf(QuickAddMemory.last(context, places)) }
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
                    remembered = r.place
                    text = ""
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

    return QuickAdd(text, { text = it.take(MAX_INPUT); error = null }, parsed, shownDay, saving, error, ::add, ::addEdited)
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
    val quick = rememberQuickAdd(places, fixedPlace, noDueMeansToday = false, onAdded)
    Column(modifier) {
        // 목업 .qa: 입력 칸이 남은 폭을 다 쓰고 오른쪽에 [추가]
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
            PrimaryButton(if (quick.saving) "추가 중…" else "추가", onClick = quick.add, enabled = !quick.saving)
        }
        QuickHint()
        // 목업 .qa-prev: 적는 동안 해석 결과를 칩으로(화면 읽기 프로그램도 바뀔 때마다 읽는다)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = if (quick.text.isNotBlank()) 8.dp else 0.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (quick.text.isNotBlank()) QuickPreview(quick.parsed, quick.shownDay)
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

/** 목업 .qa-prev 칩들: 제목 · 기한 · 모호 · 우선순위 · 들어갈 곳 · 팔로업 · 경고 */
@Composable
fun QuickPreview(r: QuickTask, today: LocalDate) {
    QuickChip(WbIcons.Pencil, r.title.ifEmpty { "제목 없음" }, italic = r.title.isEmpty())
    quickDueLabel(r, today)?.let { QuickChip(WbIcons.Calendar, it) }
    r.vague?.let { QuickChip(WbIcons.Calendar, "$it · 확인 필요", warn = true) }
    if (r.priority != 2) QuickChip(WbIcons.Flag, if (r.priority == 1) "높음" else "낮음")
    QuickChip(placeIcon(r.place?.type), r.place?.name ?: UNSORTED)
    r.assignee?.let { QuickChip(WbIcons.Person, "$it · 팔로업") }
    r.warnings.forEach { QuickChip(WbIcons.Alert, it, warn = true) }
}

/** 목업 .qc: 작은 테두리 알약(경고는 주황 글씨·테두리) */
@Composable
private fun QuickChip(icon: ImageVector, text: String, warn: Boolean = false, italic: Boolean = false) {
    val color = if (warn) Wb.colors.warn else Wb.colors.body
    Row(
        Modifier.background(Wb.colors.surface, PillShape)
            .border(1.dp, if (warn) Wb.colors.warn.copy(alpha = 0.35f) else Wb.colors.border, PillShape)
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
            fontStyle = if (italic) FontStyle.Italic else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp),
        )
    }
}
