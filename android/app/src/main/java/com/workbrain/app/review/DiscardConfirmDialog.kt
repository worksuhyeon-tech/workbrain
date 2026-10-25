package com.workbrain.app.review

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.workbrain.app.ui.Wb

/** 체크한 후보 여러 개를 한 번에 버리기 전 확인. 전체 선택 상태에서 잘못 눌러 모두 버리는 것을 막는다 */
@Composable
fun DiscardConfirmDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("후보 ${count}개 버리기") },
        text = { Text("체크한 할 일 후보 ${count}개를 버려요. 버린 후보는 검토함에서 사라져요.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("버리기", color = Wb.colors.crit) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
