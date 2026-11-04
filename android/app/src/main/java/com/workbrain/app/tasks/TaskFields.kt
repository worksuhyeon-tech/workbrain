package com.workbrain.app.tasks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.SegmentPills
import com.workbrain.app.ui.Wb
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 할 일 후보 고치기(CandidateEditSheet)와 할 일 고치기(TaskEditSheet)가 같이 쓰는 칸: 담당·기한.
 * 모호한 기한('다음 주')은 날짜로 바꾸지 않고 원문대로 둘 수 있다(NFR-02). 원래 모호했던 것만 '원문대로'를 보여 준다.
 */

private val OWNERS = listOf("me" to "나", "other" to "다른 사람", "unknown" to "확인 필요")
private val DUE_KINDS = listOf("none" to "없음", "date" to "날짜", "vague" to "원문대로")
private val DAY = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)

/** 기한 종류: 없음(none) / 날짜(date) / 모호한 원문 그대로(vague) */
fun dueKindOf(dueDate: String?, dueAmbiguous: Boolean): String = when {
    dueDate != null -> "date"
    dueAmbiguous -> "vague"
    else -> "none"
}

/** 다른 사람이면 이름, 날짜면 날짜가 있어야 저장할 수 있다 */
fun taskFieldsValid(title: String, owner: String, assignee: String, dueKind: String, date: LocalDate?): Boolean =
    title.isNotBlank() && (owner != "other" || assignee.isNotBlank()) && (dueKind != "date" || date != null)

/** 담당: 나 / 다른 사람(이름·팀) / 확인 필요(DEC-23) */
@Composable
fun OwnerField(owner: String, assignee: String, onOwner: (String) -> Unit, onAssignee: (String) -> Unit) {
    SegmentPills(OWNERS, owner, onChange = onOwner)
    if (owner == "other") {
        OutlinedTextField(
            value = assignee, onValueChange = { onAssignee(it.take(40)) },
            placeholder = { Text("이름이나 팀") }, singleLine = true,
            shape = ControlShape, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 기한: 없음 / 날짜(달력) / 원문대로(원래 모호했을 때만). '날짜'를 처음 고르면 달력을 바로 띄운다.
 * openPicker: 처음부터 달력을 띄울지('+ 기한'으로 들어온 경우)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueField(
    kind: String,
    date: LocalDate?,
    vagueText: String?,
    allowVague: Boolean,
    onKind: (String) -> Unit,
    onDate: (LocalDate) -> Unit,
    openPicker: Boolean = false,
) {
    var picking by remember { mutableStateOf(openPicker) }
    SegmentPills(if (allowVague) DUE_KINDS else DUE_KINDS.dropLast(1), kind) { onKind(it); if (it == "date" && date == null) picking = true }
    when (kind) {
        "date" -> TextButton(onClick = { picking = true }) {
            Text(date?.format(DAY) ?: "날짜 고르기", color = Wb.colors.link)
        }
        "vague" -> Text("'${vagueText ?: "모호"}' · 확인 필요로 둬요", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
    }

    if (picking) DatePickDialog(date, onDismiss = { picking = false }) { onDate(it); onKind("date"); picking = false }
}

/** 날짜 고르기 창(달력). 처음 위치는 지금 값, 없으면 오늘 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) { DatePicker(state) }
}

/** 시각 고르기 창(서울 시각으로 보여 준다, 목업 '날짜+시각') */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(initial: LocalTime?, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val start = initial ?: LocalTime.of(10, 0)
    val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("시각 고르기") },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 서랍 안 칸 이름 */
@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Wb.colors.muted, modifier = Modifier.padding(top = 8.dp))
}
