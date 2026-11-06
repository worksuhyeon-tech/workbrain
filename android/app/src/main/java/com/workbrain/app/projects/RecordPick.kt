package com.workbrain.app.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Para
import com.workbrain.app.data.RecordBrief
import com.workbrain.app.record.RecordingStore
import com.workbrain.app.ui.Note
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons

/*
 * 기록 한 줄(트리 하위 기록·'관련 기록' 카드·기록 참조 자료)과 기록 고르기 서랍(DEC-71).
 */

/** 받아쓴 글은 문서, 나머지(녹음·파일·통화)는 마이크 */
fun recordIcon(channel: String?): ImageVector = if (channel == "text-import") WbIcons.Document else WbIcons.Mic

/** 목업 '10/16 · 녹음 42분'. 받아쓴 글은 길이 없이 */
fun recordMeta(r: RecordBrief): String = listOfNotNull(
    shortDay(r.at),
    when (r.channel) { "recording" -> "녹음"; "upload" -> "파일"; "call-share" -> "통화"; "text-import" -> "받아쓴 글"; else -> null }
        ?.let { kind -> r.durationSec?.let { "$kind ${maxOf(1, it / 60)}분" } ?: kind },
).joinToString(" · ")

/** 기록 한 줄: 아이콘 · 이름 · 오른쪽 날짜·길이. 누르면 onClick */
@Composable
fun RecordLine(r: RecordBrief, onClickLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .heightIn(min = 44.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(recordIcon(r.channel), contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(16.dp))
        Text(
            r.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Text(recordMeta(r), fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * 기록 고르기(기록 참조 자료 만들기, DEC-71): 보관 안 된 내 기록, 최근 회의부터.
 * 기록 삭제는 기기에서만 하므로(휴지통, DEC-51) 이 기기 휴지통에 넣은 녹음은 목록에서 뺀다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordPickSheet(onDismiss: () -> Unit, onPick: (RecordBrief) -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<RecordBrief>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        val trashed = RecordingStore.listTrash(context).mapNotNull { it.recordId }.toSet()
        runCatching { Para.records() }
            .onSuccess { list -> records = list.filterNot { it.id in trashed } }
            .onFailure { failed = true }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x6609141E),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("기록 고르기", style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink)
            Note("고른 기록의 제목이 이름 칸에 들어가요. 담은 뒤에는 다른 기록으로 바꿀 수 없어요.")
            val list = records
            when {
                failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("불러오지 못했어요. 인터넷 연결을 확인해 주세요.", fontSize = 14.sp, color = Wb.colors.crit, modifier = Modifier.weight(1f))
                    TextButton(onClick = { attempt++ }) { Text("다시 시도", color = Wb.colors.link) }
                }
                list == null -> Text("불러오는 중…", fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.padding(vertical = 12.dp))
                list.isEmpty() -> Text("아직 올린 기록이 없어요. 녹음을 올리면 여기서 고를 수 있어요.", fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.padding(vertical = 12.dp))
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(list, key = { _, r -> r.id }) { i, r ->
                        if (i > 0) HorizontalDivider(color = Wb.colors.border)
                        RecordLine(r, onClickLabel = "이 기록 고르기") { onPick(r) }
                    }
                }
            }
        }
    }
}
