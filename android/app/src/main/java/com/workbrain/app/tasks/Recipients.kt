package com.workbrain.app.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Server
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Wb

/*
 * 받는 사람(DEC-77): 메일·자료를 보낼 상대, 공유·전달할 상대, 확인해서 알려 줄 상대.
 * 할 일 줄 제목 옆 '→ 박서연 외 1' 작은 칩(없으면 안 보임). 누르면 작은 서랍에서 고른다(11/09 사용자 선택).
 */

/** 서버 제약과 같다(0035: 최대 5명, 이름 40자) */
const val MAX_RECIPIENTS = 5
private const val NAME_MAX = 40

/** 칩 글자: '→ 박서연' · '→ 박서연 외 1'. 없으면 null */
fun recipientsLabel(names: List<String>): String? = when (names.size) {
    0 -> null
    1 -> "→ ${names[0]}"
    else -> "→ ${names[0]} 외 ${names.size - 1}"
}

private fun normName(s: String) = s.replace(Regex("\\s+"), "").removeSuffix("님").removeSuffix("씨").lowercase()

/**
 * 고르기 칩 후보: 지금 받는 사람 → 나 → 그 기록의 참석자·화자, 같은 이름은 한 번.
 * 이 공간에서 쓴 이름(팔로업 담당·받는 사람)은 기록 사람이 없을 때만 보탠다(직접 넣은 할 일).
 * 기록 사람이 있는데도 섞으면 다른 회의 사람까지 칩이 많아져 고르기 어려웠다(11/09 화면 확인)
 */
fun recipientChoices(current: List<String>, recordPeople: List<String>, usedNames: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    return (current + "나" + recordPeople.ifEmpty { usedNames })
        .map { it.trim() }
        .filter { it.isNotEmpty() && seen.add(normName(it)) }
}

/** 그 기록의 참석자·화자(서버). 내 이름은 '나' 칩이 따로 있어 뺀다. 못 읽으면 빈 목록(직접 적거나 쓴 이름에서 고름) */
suspend fun loadRecordPeople(recordId: String?): List<String> {
    if (recordId == null) return emptyList()
    val people = runCatching { Server.recordPeople(recordId) }.getOrDefault(emptyList())
    val mine = runCatching { Server.myNames() }.getOrDefault(emptyList()).map(::normName).toSet()
    return people.filterNot { normName(it) in mine }
}

/** 할 일 줄 제목 옆 작은 칩. onClick이 없으면(보관된 곳) 누를 수 없다 */
@Composable
fun RecipientsChip(names: List<String>, onClick: (() -> Unit)?) {
    val label = recipientsLabel(names) ?: return
    Text(
        label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Wb.colors.body, maxLines = 1,
        modifier = Modifier.clip(PillShape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "받는 사람 고치기", onClick = onClick) else Modifier)
            .border(1.dp, Wb.colors.border, PillShape)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/**
 * 받는 사람 고르기 칸: 후보 칩을 눌러 넣고 빼며, 목록에 없으면 이름을 적어 더한다.
 * 할 일 수정 서랍과 받는 사람 서랍이 같이 쓴다
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipientsField(selected: List<String>, choices: List<String>, onChange: (List<String>) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val full = selected.size >= MAX_RECIPIENTS
    fun has(name: String) = selected.any { normName(it) == normName(name) }
    fun add(name: String) {
        val n = name.trim().removeSuffix("님").trim().take(NAME_MAX)
        if (n.isEmpty() || has(n) || full) return
        onChange(selected + n)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 누른 칩이 자리를 옮기지 않게 후보 순서를 그대로 두고, 직접 적은 이름만 뒤에 붙인다(11/10 화면 확인)
            val typedOnly = selected.filterNot { s -> choices.any { normName(it) == normName(s) } }
            (choices + typedOnly).forEach { name ->
                val on = has(name)
                Text(
                    if (on) "✓ $name" else name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (on) Wb.colors.primary else Wb.colors.body,
                    modifier = Modifier.clip(PillShape)
                        .clickable(enabled = on || !full, onClickLabel = if (on) "$name 빼기" else "$name 넣기") {
                            if (on) onChange(selected.filterNot { normName(it) == normName(name) }) else add(name)
                        }
                        .background(if (on) Wb.colors.primary.copy(alpha = 0.10f) else Wb.colors.surface, PillShape)
                        .border(1.dp, if (on) Wb.colors.primary else Wb.colors.border, PillShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed, onValueChange = { typed = it.take(NAME_MAX) },
                placeholder = { Text(if (full) "최대 ${MAX_RECIPIENTS}명까지예요" else "목록에 없으면 이름 적기 (예: IT 개발팀)") },
                singleLine = true, enabled = !full, shape = ControlShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(typed); typed = "" }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { add(typed); typed = "" }, enabled = typed.isNotBlank() && !full) { Text("추가") }
        }
    }
}

/**
 * 받는 사람 서랍(할 일 줄 칩·후보 칩에서 연다). recordId가 있으면 그 기록의 참석자·화자를 불러 칩으로 보탠다.
 * 저장은 부른 쪽이 한다(할 일은 서버에 저장, 후보는 승인 때 덮을 값으로 화면에만)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipientsSheet(
    current: List<String>,
    recordId: String?,
    usedNames: List<String>,
    saving: Boolean = false,
    error: String? = null,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    var recordPeople by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(recordId) { recordPeople = loadRecordPeople(recordId) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x6609141E),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("받는 사람", style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink)
            Text(
                "메일·자료를 보낼 상대, 공유·전달하거나 알려 줄 상대예요. 최대 ${MAX_RECIPIENTS}명.",
                style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted,
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Wb.colors.crit) }
            RecipientsField(selected, recipientChoices(current, recordPeople, usedNames)) { selected = it }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("취소", onClick = onDismiss, modifier = Modifier.height(44.dp))
                PrimaryButton(if (saving) "저장 중…" else "저장", enabled = !saving, modifier = Modifier.weight(1f), onClick = { onSave(selected) })
            }
        }
    }
}
