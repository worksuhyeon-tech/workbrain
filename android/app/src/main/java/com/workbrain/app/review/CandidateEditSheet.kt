package com.workbrain.app.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SegmentPills
import com.workbrain.app.ui.Wb
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val OWNERS = listOf("me" to "나", "other" to "다른 사람", "unknown" to "확인 필요")
private val DUE_KINDS = listOf("none" to "없음", "date" to "날짜", "vague" to "원문대로")
private val DAY = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)

/**
 * 할 일 후보 고치기(FR-10): 제목·담당·기한을 고친 값은 승인 때 AI 값 위에 덮어 할 일로 만든다(approve_proposal p_edits).
 * 후보 자체(AI 제안 원본)는 바꾸지 않는다. 고친 값은 승인 전까지 이 화면에만 있다.
 * 결과는 승인 함수가 읽는 키 전체(title·owner_kind·assignee·due_*)로 돌려준다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CandidateEditSheet(current: JsonObject, onDismiss: () -> Unit, onApply: (JsonObject) -> Unit) {
    val wasVague = current["due_ambiguous"]?.jsonPrimitive?.booleanOrNull == true
    var title by remember { mutableStateOf(current.str("title").orEmpty()) }
    var owner by remember { mutableStateOf(current.str("owner_kind") ?: "unknown") }
    var assignee by remember { mutableStateOf(current.str("assignee").orEmpty()) }
    var dueKind by remember {
        mutableStateOf(when { current.str("due_date") != null -> "date"; wasVague -> "vague"; else -> "none" })
    }
    var date by remember { mutableStateOf(current.str("due_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }) }
    var picking by remember { mutableStateOf(false) }

    val valid = title.isNotBlank() && (owner != "other" || assignee.isNotBlank()) && (dueKind != "date" || date != null)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x7309141E),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("할 일 고치기", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
            Text("고친 값으로 할 일이 만들어져요. AI가 낸 후보 원본은 그대로 남아요.", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)

            Label("제목")
            OutlinedTextField(
                value = title, onValueChange = { title = it.take(200) },
                shape = ControlShape, modifier = Modifier.fillMaxWidth(),
            )

            Label("담당")
            SegmentPills(OWNERS, owner) { owner = it }
            if (owner == "other") {
                OutlinedTextField(
                    value = assignee, onValueChange = { assignee = it.take(40) },
                    placeholder = { Text("이름이나 팀") }, singleLine = true,
                    shape = ControlShape, modifier = Modifier.fillMaxWidth(),
                )
            }

            Label("기한")
            SegmentPills(if (wasVague) DUE_KINDS else DUE_KINDS.dropLast(1), dueKind) { dueKind = it; if (it == "date" && date == null) picking = true }
            when (dueKind) {
                "date" -> TextButton(onClick = { picking = true }) {
                    Text(date?.format(DAY) ?: "날짜 고르기", color = Wb.colors.link)
                }
                "vague" -> Text("'${current.str("due_text") ?: "모호"}' · 확인 필요로 둬요", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("취소", onClick = onDismiss, modifier = Modifier.height(44.dp))
                PrimaryButton("적용", enabled = valid, modifier = Modifier.weight(1f), onClick = {
                    onApply(edits(current, title.trim(), owner, assignee.trim(), dueKind, date))
                })
            }
        }
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("취소") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Wb.colors.muted, modifier = Modifier.padding(top = 8.dp))
}

/** 승인 함수가 읽는 키를 모두 채운다. 날짜를 바꾸면 시각은 버린다(날짜만 기한, 시각 지정은 다음 버전) */
private fun edits(current: JsonObject, title: String, owner: String, assignee: String, dueKind: String, date: LocalDate?): JsonObject {
    val sameDate = date?.toString() == current.str("due_date")
    return JsonObject(mapOf(
        "title" to JsonPrimitive(title),
        "owner_kind" to JsonPrimitive(owner),
        "assignee" to if (owner == "other") JsonPrimitive(assignee) else JsonNull,
        "due_date" to if (dueKind == "date" && date != null) JsonPrimitive(date.toString()) else JsonNull,
        "due_time" to if (dueKind == "date" && sameDate) (current["due_time"] ?: JsonNull) else JsonNull,
        "due_ambiguous" to JsonPrimitive(dueKind == "vague"),
        "due_text" to if (dueKind == "vague") (current["due_text"] ?: JsonNull) else JsonNull,
    ))
}
