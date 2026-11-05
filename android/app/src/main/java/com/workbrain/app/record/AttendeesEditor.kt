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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
 * 다음 녹음의 참석자(DEC-68): 목업 S04처럼 녹음 전에도 참석자 칸이 늘 보이므로, 녹음을 시작하기 전에 넣은 명단을 여기 둔다.
 * 녹음을 시작하면 그 녹음에 옮겨 붙이고 비운다. 비어 있으면(처음·지난 녹음 뒤) 더보기 '내 이름·호칭'의 첫 이름으로 채운다
 * (목업의 '나' 자리. 회의록 참석자 칸에 '나' 대신 실명이 찍히게). 사용자가 직접 다 뺀 빈 명단은 그대로 둔다. 기기에만 둔다.
 */
object NextAttendees {
    private const val PREFS = "attendees"
    private const val KEY_NEXT = "next"
    private const val KEY_MY_NAME = "my_name"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): List<String> {
        val p = prefs(context)
        if (p.contains(KEY_NEXT)) return p.getString(KEY_NEXT, "").orEmpty().split('\n').filter { it.isNotBlank() }
        return listOfNotNull(p.getString(KEY_MY_NAME, null)?.takeIf { it.isNotBlank() })
    }

    fun set(context: Context, names: List<String>) {
        prefs(context).edit().putString(KEY_NEXT, names.joinToString("\n")).apply()
    }

    /** 녹음을 시작할 때: 지금 명단을 넘겨주고 비운다(다음 녹음은 내 이름부터 다시) */
    fun take(context: Context): List<String> {
        val names = get(context)
        prefs(context).edit().remove(KEY_NEXT).apply()
        return names
    }

    /** 서버의 '내 이름·호칭' 첫 이름을 기기에 적어 둔다(인터넷이 없을 때도 채우려고) */
    fun rememberMyName(context: Context, name: String?) {
        prefs(context).edit().putString(KEY_MY_NAME, name?.trim().orEmpty()).apply()
    }
}

/**
 * 참석자 입력(Design S04 Attendees, DEC-39): 넣은 사람은 칩(누르면 빼기), 아래에 이름 입력과 최근 참석자.
 * 참석자는 회의록의 참석자 칸과 이름 표기 교정에만 쓴다. 명단만으로 담당자를 정하지 않는다.
 * 더보기 '내 이름·호칭'(DEC-49)도 같은 입력을 쓴다(상한 5개, 최근 참석자 없음).
 * focusRequester: 펼친 녹음 화면에서 왼쪽 '+ 참석자'를 누르면 오른쪽 이름 칸으로 바로 옮겨 가게 한다
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttendeesEditor(
    names: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = MAX_ATTENDEES,
    placeholder: String = "이름 (예: 박지훈)",
    showRecent: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val recent = if (showRecent) remember { RecentAttendees.get(context) }.filter { it !in names } else emptyList()

    fun add(raw: String) {
        // 호칭은 떼고 이름만 남긴다("박지훈님" → "박지훈")
        val name = raw.trim().removeSuffix("님").trim().take(MAX_NAME)
        if (name.isEmpty() || name in names || names.size >= max) return
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
                placeholder = { Text(placeholder) },
                singleLine = true,
                shape = ControlShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(input) }),
                modifier = Modifier.weight(1f).then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            )
            Spacer(Modifier.width(8.dp))
            SecondaryButton("추가", enabled = input.isNotBlank() && names.size < max, onClick = { add(input) })
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

/**
 * 참석자 고치기 창. 기록 상세(DEC-59)와 접은 녹음 화면의 '+ 참석자'(DEC-68)가 같이 쓴다.
 * 회의록은 이 명단의 사람만 참석자로 쓰고, 받아쓰기 앱이 한 사람을 목소리 높낮이로 둘로 나눈 '참석자 4' 같은 화자는 따로 세지 않는다
 */
@Composable
fun AttendeesDialog(current: List<String>, onDismiss: () -> Unit, title: String = "참석자 고치기", onSave: (List<String>) -> Unit) {
    var names by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "실제로 참석한 사람만 넣어 주세요. 회의록은 이 명단의 사람만 참석자로 써요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Wb.colors.muted,
                )
                AttendeesEditor(names, onChange = { names = it })
            }
        },
        confirmButton = { TextButton(enabled = names != current, onClick = { onSave(names) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
