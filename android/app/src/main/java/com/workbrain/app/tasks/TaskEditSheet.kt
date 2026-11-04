package com.workbrain.app.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.data.TaskRow
import com.workbrain.app.projects.DarkSeg
import com.workbrain.app.projects.Note
import com.workbrain.app.projects.SegItem
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** '어디에'가 비어 있을 때 이름. 모든 할 일 맨 위 '정리 안 됨' 묶음(DEC-65) */
const val UNSORTED = "정리 안 됨"

/** 저장이 안 됐을 때 서랍 안 띠(목업 .unsaved): 입력은 그대로 둔다 */
const val UNSAVED_TEXT = "저장하지 못했어요. 입력한 내용은 그대로 있어요 — 연결을 확인하고 다시 저장해 주세요."

fun placeIcon(type: PlaceType?): ImageVector = when (type) {
    PlaceType.PROJECT -> WbIcons.Folder
    PlaceType.AREA -> WbIcons.Repeat
    null -> WbIcons.Review
}

/** 옮긴 뒤 알림 문구 */
fun movedText(place: Place?): String = if (place == null) "‘$UNSORTED’으로 옮겼어요" else "‘${place.name}’에 넣었어요"

private val DAY = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)
private val OWNERS = listOf(SegItem("me", "나"), SegItem("other", "다른 사람 (팔로업)"), SegItem("unknown", "확인 필요"))
private val DUES = listOf(SegItem("none", "없음"), SegItem("date", "날짜"), SegItem("time", "날짜+시각"), SegItem("vague", "모호"))
private val PRIOS = listOf(SegItem("1", "높음"), SegItem("2", "보통"), SegItem("3", "낮음"))

/**
 * 할 일 수정 서랍(목업 S07 편집 서랍, DEC-66): 제목 · 어디에 · 담당 · 기한(없음/날짜/날짜+시각/모호) · 우선순위.
 * 오늘 탭과 프로젝트 탭이 같은 서랍을 쓴다. Android라서 오른쪽 서랍 대신 아래에서 올라온다(앱의 다른 서랍과 같음).
 * 저장이 안 되면(error) 서랍을 닫지 않고 입력을 그대로 둔 채 띠를 보여 준다(목업 '미저장 표시').
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditSheet(
    task: TaskRow,
    places: List<Place>,
    currentPlaceName: String?,
    error: String?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (TaskEdit) -> Unit,
) {
    val timed = task.dueTime?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL) }.getOrNull() }
    var title by remember { mutableStateOf(task.title) }
    var projectId by remember { mutableStateOf(task.projectId) }
    var areaId by remember { mutableStateOf(task.areaId) }
    var owner by remember { mutableStateOf(task.ownerKind) }
    var assignee by remember { mutableStateOf(task.assignee.orEmpty()) }
    var dueKind by remember {
        mutableStateOf(when { timed != null -> "time"; task.dueDate != null -> "date"; task.dueAmbiguous -> "vague"; else -> "none" })
    }
    var date by remember { mutableStateOf(timed?.toLocalDate() ?: task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }) }
    var time by remember { mutableStateOf(timed?.toLocalTime()) }
    var vague by remember { mutableStateOf(task.dueText.orEmpty()) }
    var priority by remember { mutableStateOf((task.priority ?: 2).toString()) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    val valid = title.isNotBlank() && (owner != "other" || assignee.isNotBlank()) &&
        (dueKind != "date" || date != null) && (dueKind != "time" || (date != null && time != null))
    val chosen = places.firstOrNull { (it.type == PlaceType.PROJECT && it.id == projectId) || (it.type == PlaceType.AREA && it.id == areaId) }
    val placeLabel = when {
        chosen != null -> chosen.name
        projectId == null && areaId == null -> UNSORTED
        else -> currentPlaceName ?: "보관된 곳"
    }
    val placeType = chosen?.type ?: when { projectId != null -> PlaceType.PROJECT; areaId != null -> PlaceType.AREA; else -> null }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x6609141E),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("할 일 수정", style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink, modifier = Modifier.padding(bottom = 8.dp))
            error?.let { Banner(it, Tone.Crit, Modifier.padding(bottom = 4.dp)) }

            Label("제목")
            OutlinedTextField(value = title, onValueChange = { title = it.take(200) }, shape = ControlShape, modifier = Modifier.fillMaxWidth())

            Label("어디에")
            PlaceField(placeLabel, placeType, places) { p ->
                projectId = p?.id?.takeIf { p.type == PlaceType.PROJECT }
                areaId = p?.id?.takeIf { p.type == PlaceType.AREA }
            }
            if (places.isEmpty()) Note("아직 프로젝트·반복실행이 없어요. 프로젝트 탭에서 칸 이름 옆 (+)로 만들 수 있어요.")

            Label("담당")
            DarkSeg(OWNERS, owner) { owner = it }
            if (owner == "other") {
                OutlinedTextField(
                    value = assignee, onValueChange = { assignee = it.take(40) },
                    placeholder = { Text("맡은 사람 이름 (예: 박지훈)") }, singleLine = true,
                    shape = ControlShape, modifier = Modifier.fillMaxWidth(),
                )
            }
            Note("팔로업은 남이 맡은 일이에요. 내가 챙길 수 있게 모아 보여 줘요.")

            Label("기한")
            DarkSeg(DUES, dueKind) { k ->
                dueKind = k
                if ((k == "date" || k == "time") && date == null) pickingDate = true
            }
            when (dueKind) {
                "date" -> TextButton(onClick = { pickingDate = true }) { Text(date?.format(DAY) ?: "날짜 고르기", color = Wb.colors.link) }
                "time" -> Row {
                    TextButton(onClick = { pickingDate = true }) { Text(date?.format(DAY) ?: "날짜 고르기", color = Wb.colors.link) }
                    TextButton(onClick = { pickingTime = true }) { Text(time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "시각 고르기", color = Wb.colors.link) }
                }
                "vague" -> OutlinedTextField(
                    value = vague, onValueChange = { vague = it.take(40) },
                    placeholder = { Text("예: 다음 주, 월말") }, singleLine = true,
                    shape = ControlShape, modifier = Modifier.fillMaxWidth(),
                )
            }
            when (dueKind) {
                "date" -> Note("날짜만 저장해요(시각·시간대 없음).")
                "time" -> Note("시각은 서울 시간으로 표시해요.")
                "vague" -> Note("모호한 기한은 날짜로 바꾸지 않고 '확인 필요'로 둬요.")
            }

            Label("우선순위")
            DarkSeg(PRIOS, priority) { priority = it }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("취소", onClick = onDismiss, modifier = Modifier.height(44.dp))
                PrimaryButton(if (saving) "저장 중…" else "저장", enabled = valid && !saving, modifier = Modifier.weight(1f), onClick = {
                    val d = date
                    val t = time
                    onSave(
                        TaskEdit(
                            title = title.trim(),
                            priority = priority.toInt(),
                            ownerKind = owner,
                            assignee = if (owner == "other") assignee.trim() else null,
                            dueDate = if ((dueKind == "date" || dueKind == "time") && d != null) d.toString() else null,
                            dueTime = if (dueKind == "time" && d != null && t != null) ZonedDateTime.of(d, t, SEOUL).toOffsetDateTime().toString() else null,
                            dueAmbiguous = dueKind == "vague",
                            dueText = if (dueKind == "vague") vague.trim().ifEmpty { "미정" } else null,
                            projectId = projectId,
                            areaId = areaId,
                        ),
                    )
                })
            }
        }
    }

    if (pickingDate) DatePickDialog(date, onDismiss = { pickingDate = false }) { date = it; pickingDate = false }
    if (pickingTime) TimePickDialog(time, onDismiss = { pickingTime = false }) { time = it; pickingTime = false }
}

/** 목업 .field label: 굵은 진한 글씨 */
@Composable
private fun Label(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(top = 10.dp))
}

/** 어디에: 정리 안 됨 + 1. 프로젝트 / 2. 반복실행 묶음(목업 optgroup) */
@Composable
private fun PlaceField(label: String, type: PlaceType?, places: List<Place>, onPick: (Place?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = ControlShape,
            color = Wb.colors.card,
            border = BorderStroke(1.dp, Wb.colors.borderStrong),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(placeIcon(type), contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
                Text(
                    label, style = MaterialTheme.typography.bodyLarge, color = Wb.colors.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Icon(WbIcons.ChevronDown, contentDescription = "어디에 고르기", tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 260.dp)) {
            DropdownMenuItem(
                text = { Text(UNSORTED) },
                leadingIcon = { Icon(placeIcon(null), contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { open = false; onPick(null) },
            )
            listOf(PlaceType.PROJECT to "1. 프로젝트", PlaceType.AREA to "2. 반복실행").forEach { (kind, heading) ->
                val group = places.filter { it.type == kind }
                if (group.isNotEmpty()) {
                    HorizontalDivider(color = Wb.colors.border)
                    Text(heading, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    group.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(placeIcon(p.type), contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { open = false; onPick(p) },
                        )
                    }
                }
            }
        }
    }
}
