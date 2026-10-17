package com.workbrain.app.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val KINDS = listOf("meeting" to "회의", "call" to "통화", "memo" to "혼자 메모")

// 앱이 내용을 보고 자동으로 판단하지 않고 사용자가 고른다(M11). 금지·모름이면 올리지 않는다
private val PERMISSIONS = listOf(
    "personal" to "개인 자료 (내 메모·개인 통화)",
    "sample" to "샘플·시험용 녹음",
    "company-approved" to "회사 자료 (외부 저장 허용을 확인함)",
    "deny" to "허용 안 됨 · 잘 모르겠음 → 올리지 않아요",
)

/**
 * 올리기 전 확인: 기록 종류, 클라우드 저장 허용(M11), 처음 한 번 외부 전사·분석 동의(FR-17).
 * onConfirm(kind, permission, consentGiven)
 */
@Composable
fun UploadDialog(
    needConsent: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (kind: String, permission: String, consentGiven: Boolean) -> Unit,
) {
    var kind by remember { mutableStateOf("meeting") }
    var permission by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf(false) }
    val canSend = permission != null && permission != "deny" && (!needConsent || consent)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("녹음 올리기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("어떤 녹음인가요?", fontWeight = FontWeight.Bold)
                Text(
                    "메모는 '제가 할게요'를 내 할 일로, 회의·통화는 '확인 필요'로 정리해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KINDS.forEach { (value, label) ->
                        FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("이 자료를 클라우드에 올려도 되나요?", fontWeight = FontWeight.Bold)
                PERMISSIONS.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = permission == value, onClick = { permission = value }).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = permission == value, onClick = { permission = value })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (permission == "deny") {
                    Text(
                        "올리지 않아요. 녹음은 이 휴대폰에만 남아요.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (needConsent) {
                    Spacer(Modifier.height(12.dp))
                    Text("외부 AI 처리 동의 (처음 한 번)", fontWeight = FontWeight.Bold)
                    Text(
                        "음성은 OpenAI 전사(gpt-transcribe)로, 받아쓴 글은 OpenAI 분석(gpt-6-luna)으로 보내 회의록·할 일 후보를 만들어요. " +
                            "결과는 내가 승인하기 전까지 아무것도 바꾸지 않아요. 음성은 30일 뒤 자동으로 지워요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consent, onCheckedChange = { consent = it })
                        Text("동의해요", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(kind, permission!!, consent) }, enabled = canSend) { Text("올리기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
