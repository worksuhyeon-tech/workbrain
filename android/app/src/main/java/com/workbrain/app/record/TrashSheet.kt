package com.workbrain.app.record

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Wb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 휴지통(DEC-51): 목록에서 민 '정리 완료' 녹음. 되살리면 녹음 목록으로 돌아오고 회의록을 다시 열 수 있다.
 * 영구 삭제·30일 자동 비움은 기기 목록 줄만 없앤다. 서버의 회의록·할 일 후보는 그대로다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashSheet(
    items: List<RecordingSession>,
    onRestore: (RecordingSession) -> Unit,
    onDeleteForever: (RecordingSession) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x7309141E),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("휴지통", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
            Text(
                "서버에 올라간 녹음만 여기서 되살릴 수 있어요. 30일이 지나면 자동으로 비워져요. 비워도 검토함의 할 일 후보는 남아요.",
                style = MaterialTheme.typography.bodySmall,
                color = Wb.colors.muted,
            )
            if (items.isEmpty()) {
                Text("비어 있어요.", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted, modifier = Modifier.padding(vertical = 16.dp))
            }
            items.forEach { s -> TrashRow(s, onRestore = { onRestore(s) }, onDeleteForever = { onDeleteForever(s) }) }
        }
    }
}

@Composable
private fun TrashRow(s: RecordingSession, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val trashedAt = s.trashedAt ?: return
    val daysLeft = ((trashedAt + RecordingStore.KEEP_IN_TRASH_MS - System.currentTimeMillis()) / 86_400_000L + 1).coerceAtLeast(1)
    Surface(shape = ControlShape, color = Wb.colors.surface, border = BorderStroke(1.dp, Wb.colors.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(s.startedAt)) + " 녹음 · ${daysLeft}일 뒤 자동으로 비워져요",
                style = MaterialTheme.typography.bodySmall,
                color = Wb.colors.muted,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SecondaryButton("되살리기", onClick = onRestore)
                TextButton(onClick = onDeleteForever) { Text("영구 삭제", color = Wb.colors.muted) }
            }
        }
    }
}
