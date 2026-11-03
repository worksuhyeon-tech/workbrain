package com.workbrain.app.review

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.workbrain.app.ui.Wb

/**
 * 회의록 승인 확인 창(FR-07, DEC-61). 승인은 되돌릴 수 없어 한 번 묻는다.
 * 승인해도 할 일·프로젝트는 바뀌지 않는다는 것을 함께 알린다(Design §3.9 '회의록 승인 ≠ 운영 반영')
 */
@Composable
fun ApproveMinutesDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이 회의록 승인") },
        text = {
            Text(
                "승인하면 Word의 '초안' 표시가 빠지고 3년 보관돼요. 할 일은 바뀌지 않아요.\n\n" +
                    "승인은 되돌릴 수 없어요. 고칠 게 생기면 다시 만들어 새로 승인하면 돼요.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("승인", color = Wb.colors.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
