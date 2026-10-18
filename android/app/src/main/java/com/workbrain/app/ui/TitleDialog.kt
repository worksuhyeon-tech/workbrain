package com.workbrain.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/** 녹음·기록 제목 길이 상한(목록 한 줄에 읽히는 정도) */
const val MAX_TITLE = 80

/** 제목 고치기 창(녹음 목록·녹음 중·기록 상세 공용). 앞뒤 공백을 떼고 비어 있으면 저장하지 않는다 */
@Composable
fun TitleDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val trimmed = text.trim()
    val save = { if (trimmed.isNotEmpty()) onSave(trimmed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("제목 고치기") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_TITLE) },
                singleLine = true,
                placeholder = { Text("예: 사고접수 개편 2차 범위 회의") },
                shape = ControlShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = trimmed.isNotEmpty() && trimmed != current, onClick = save) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
