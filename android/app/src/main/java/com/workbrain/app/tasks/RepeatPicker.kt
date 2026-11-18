package com.workbrain.app.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import java.time.DayOfWeek
import java.time.LocalDate

/*
 * 반복 고르기(DEC-109, Design §3.20, 목업 quickadd-android 기한 칸 '반복' 줄·목록·직접 설정 창):
 * 빠른 입력 기한 칸과 할 일 서랍이 미리 알림 줄 아래에 같이 쓴다. 다음 회차는 완료할 때 서버가 만든다.
 */

/** 🔄 줄: 지금 값(또는 '반복 안 함')과 둘째 줄('완료하면 다음 회차 …을 만들어요'). 기한이 없으면 흐리게 안내만 */
@Composable
fun RepeatRow(value: RepeatRule?, due: LocalDate?, today: LocalDate, onPick: (RepeatRule?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val enabled = due != null
    Box(modifier.fillMaxWidth()) {
        PickerRow(
            WbIcons.Recur, if (enabled) repeatLabel(value, due) else "기한을 정하면 반복할 수 있어요", repeatNextText(value, due, today),
            active = value != null, enabled = enabled, clickLabel = "반복 고르기",
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp), containerColor = Wb.colors.card) {
            REPEAT_PRESETS.forEach { r -> PickMenuItem(repeatLabel(r, due, menu = true), on = r == value) { open = false; onPick(r) } }
            val customOn = value != null && !isPresetRepeat(value)
            PickMenuItem(if (customOn) "직접 설정 · ${repeatLabel(value, due)}" else "직접 설정…", on = customOn) { open = false; custom = true }
        }
    }
    if (custom) CustomRepeatDialog(value, due ?: today, onDismiss = { custom = false }) { r -> custom = false; onPick(r) }
}

/** 직접 설정: N(1~99) + [일·주·개월] + 주면 요일 하나 이상. 처음 값은 지금 규칙, 없으면 '2주마다 기한 요일'(목업) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomRepeatDialog(initial: RepeatRule?, due: LocalDate, onDismiss: () -> Unit, onPick: (RepeatRule) -> Unit) {
    // 매년은 직접 설정 단위에 없어 처음 값으로 쓰지 않는다
    val start = initial?.takeIf { it.freq != RepeatFreq.YEARLY }
    var amount by remember { mutableStateOf((start?.interval ?: 2).toString()) }
    var unit by remember { mutableStateOf(start?.let { s -> RepeatUnit.entries.first { it.freq == s.freq } } ?: RepeatUnit.WEEK) }
    var days by remember { mutableStateOf(start?.days?.takeIf { it.isNotEmpty() } ?: setOf(due.dayOfWeek)) }
    val rule = customRepeat(amount.toIntOrNull(), unit, days)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(26.dp), color = Wb.colors.card, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("반복 직접 설정", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amount, onValueChange = { v -> amount = v.filter(Char::isDigit).take(2) },
                        singleLine = true, shape = ControlShape,
                        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(76.dp),
                    )
                    Text("${unit.label}마다", fontSize = 15.sp, color = Wb.colors.ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepeatUnit.entries.forEach { u -> ChoicePill(u.label, on = u == unit) { unit = u } }
                }
                if (unit == RepeatUnit.WEEK) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { d ->
                            // 요일은 하나 이상 남긴다(마지막 하나는 빼지 않음)
                            ChoicePill(dowName(d), on = d in days) { days = if (d in days) (days - d).ifEmpty { days } else days + d }
                        }
                    }
                }
                if (amount.isNotEmpty() && amount.toIntOrNull() !in 1..REPEAT_MAX_INTERVAL) {
                    Text("1부터 ${REPEAT_MAX_INTERVAL}까지 정할 수 있어요", fontSize = 12.sp, color = Wb.colors.crit)
                }
                PanelFoot(onCancel = onDismiss, onDone = { rule?.let(onPick) }, doneLabel = "확인", doneEnabled = rule != null)
            }
        }
    }
}
