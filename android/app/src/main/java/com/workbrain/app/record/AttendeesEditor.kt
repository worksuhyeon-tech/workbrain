package com.workbrain.app.record

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Wb

/** 한 기록의 참석자 상한(서버 제약과 같게: 30명, 이름 20자) */
const val MAX_ATTENDEES = 30
private const val MAX_NAME = 20

/**
 * 최근 참석자: 자주 함께 회의하는 사람을 한 번 누르면 넣게 기기에만 남긴다(최대 12명, 최근 순).
 * 업무 동료 이름이라 서버로 보내지 않는다. 기록에 넣은 명단만 그 기록과 함께 올라간다.
 */
object RecentAttendees {
    private const val PREFS = "attendees"
    private const val KEY = "recent"

    fun get(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun remember(context: Context, names: List<String>) {
        if (names.isEmpty()) return
        val merged = (names + get(context)).distinct().take(12)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, merged.joinToString("\n")).apply()
    }
}

/**
 * 참석자 입력(Design S04 Attendees, DEC-39): 넣은 사람은 칩(누르면 빼기), 아래에 이름 입력과 최근 참석자.
 * 참석자는 회의록의 참석자 칸과 이름 표기 교정에만 쓴다. 명단만으로 담당자를 정하지 않는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttendeesEditor(names: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val recent = remember { RecentAttendees.get(context) }.filter { it !in names }

    fun add(raw: String) {
        // 호칭은 떼고 이름만 남긴다("박지훈님" → "박지훈")
        val name = raw.trim().removeSuffix("님").trim().take(MAX_NAME)
        if (name.isEmpty() || name in names || names.size >= MAX_ATTENDEES) return
        onChange(names + name)
        input = ""
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (names.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEach { n ->
                    InputChip(
                        selected = true,
                        onClick = { onChange(names - n) },
                        label = { Text("$n  ×") },
                        shape = PillShape,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(MAX_NAME + 1) },
                placeholder = { Text("이름 (예: 박지훈)") },
                singleLine = true,
                shape = ControlShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(input) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            SecondaryButton("추가", enabled = input.isNotBlank() && names.size < MAX_ATTENDEES, onClick = { add(input) })
        }
        if (recent.isNotEmpty()) {
            Text("최근 참석자", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.forEach { n ->
                    AssistChip(
                        onClick = { add(n) },
                        label = { Text("+ $n") },
                        shape = PillShape,
                        colors = AssistChipDefaults.assistChipColors(labelColor = Wb.colors.link),
                    )
                }
            }
        }
    }
}
