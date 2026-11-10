package com.workbrain.app.record

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.data.Server
import com.workbrain.app.ui.AnnounceHost
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.GlassIcon
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SectionLabel
import com.workbrain.app.ui.SwipeToDelete
import com.workbrain.app.ui.TitleDialog
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.rememberAnnouncer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기록 탭(DEC-89, 옛 녹음 탭의 목록 부분): 이 휴대폰에서 녹음·가져온 기록 목록 · [녹음] · [파일 가져오기] · 🗑 휴지통.
 * 다른 앱의 '공유 → WorkBrain'도 여기로 와서 가져오기를 이어 간다. 올리기 → 정리 끝난 카드를 눌러 회의록·할 일 보기.
 * 검색(FR-13)은 만들 때 이 화면 맨 위에 둔다
 */
@Composable
fun RecordsScreen(onOpenRecord: (recordId: String, title: String) -> Unit) {
    val context = LocalContext.current
    val rec by RecordingService.state.collectAsStateWithLifecycle()
    val uploads by UploadManager.progress.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf(RecordingStore.list(context, rec.sessionId)) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 올리기 확인 창 대상(가져온 파일·[올리기])
    var uploadTarget by remember { mutableStateOf<RecordingSession?>(null) }
    // 지우기 확인 창 대상
    var deleteTarget by remember { mutableStateOf<RecordingSession?>(null) }
    // 제목 고치기 창 대상
    var renameTarget by remember { mutableStateOf<RecordingSession?>(null) }
    // 휴지통(DEC-51): 열려 있을 때만 목록을 읽는다
    var trashItems by remember { mutableStateOf<List<RecordingSession>?>(null) }
    val scope = rememberCoroutineScope()
    val announcer = rememberAnnouncer()
    fun refresh() { sessions = RecordingStore.list(context, rec.sessionId) }

    // 지운 뒤 화면 아래에 잠깐 알린다. 되돌릴 수 있으면 '되돌리기'를 함께 보여 준다
    fun announce(message: String, undo: (() -> Unit)? = null) = announcer.show(message, undo)

    // 파일 가져오기(DEC-40): 복사가 끝나면 바로 올리기 창을 연다
    fun runImport(work: () -> FileImporter.Result) {
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { work() }) {
                is FileImporter.Result.Ok -> { notice = null; sessions = RecordingStore.list(context, rec.sessionId); uploadTarget = r.session }
                is FileImporter.Result.Rejected -> notice = r.reason
            }
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport { FileImporter.import(context, uri, "upload") }
    }
    val shared by SharedImport.pending.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        // 지침 zip은 더보기 화면이 받아야 하므로 여기서 비우지 않는다
        val item = shared?.takeUnless { it is SharedImport.Item.SkillZip } ?: return@LaunchedEffect
        SharedImport.pending.value = null
        when (item) {
            is SharedImport.Item.File -> runImport { FileImporter.import(context, item.uri, "call-share", item.mime) }
            is SharedImport.Item.Text -> runImport { FileImporter.importSharedText(context, item.text, item.subject) }
            is SharedImport.Item.SkillZip -> Unit
        }
    }

    // 기록 상세 ⋮에서 지운 녹음: 밀어 지우기와 같은 알림·되돌리기를 여기서 띄운다
    val trashedFromDetail by TrashNotice.pending.collectAsStateWithLifecycle()
    LaunchedEffect(trashedFromDetail) {
        val s = trashedFromDetail ?: return@LaunchedEffect
        TrashNotice.pending.value = null
        refresh()
        val dir = RecordingStore.dir(context, s.id)
        announce("${s.title} 삭제되었어요") { RecordingStore.restore(dir); refresh() }
    }

    // 처리 완료 후 7일 지난 기기 음성 정리(앱을 열 때)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RecordingStore.purgeExpired(context) }
        sessions = RecordingStore.list(context, rec.sessionId)
    }

    // 녹음이 시작·정지되거나 조각이 확정되거나 올리기 단계가 바뀔 때 목록을 다시 읽는다
    LaunchedEffect(rec.status, rec.sessionId, rec.confirmedMs, uploads) {
        sessions = RecordingStore.list(context, rec.sessionId)
    }

    // 머리 줄: 제목 + 🗑 휴지통(DEC-62: 화면 오른쪽 위, 기록 상세의 ↪ 공유 자리)
    fun LazyListScope.headerItems() {
        item {
            PageHeader("기록", "말한 건 다 기억해 둘게요") {
                IconButton(onClick = { trashItems = RecordingStore.listTrash(context) }) {
                    Icon(WbIcons.Trash, contentDescription = "휴지통", tint = Wb.colors.ink)
                }
            }
            notice?.let { Banner(it, Tone.Crit, Modifier.padding(bottom = 8.dp)) }
        }
    }

    // 이 휴대폰의 기록 목록(목업 S04 '올리는 중 · 최근')
    fun LazyListScope.sessionItems() {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("이 휴대폰의 기록", Modifier.weight(1f))
                // 녹음 화면은 탭이 아니어서, 마이크 길게 누르기를 잊어도 여기서 열 수 있게 둔다(DEC-89)
                TextButton(onClick = { RecorderLaunch.open() }) { Text("녹음", color = Wb.colors.link) }
                // 음성 또는 클로바노트 등에서 받아쓴 txt(DEC-53)
                TextButton(onClick = { pickAudio.launch(arrayOf("audio/*", "text/plain")) }) { Text("파일 가져오기", color = Wb.colors.link) }
            }
            if (sessions.isEmpty()) {
                Text(
                    "아직 기록이 없어요. 클로바노트·녹음 앱에서 '공유 → WorkBrain'을 하거나 [파일 가져오기]를 눌러 주세요. " +
                        "빠른 입력의 마이크를 길게 누르면 녹음 메모를 남겨요. 올리기를 누르기 전까지는 이 휴대폰 안에만 있어요.",
                    style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted,
                )
            }
        }
        items(sessions, key = { it.id }) { s ->
            val isActive = s.id == rec.sessionId
            val row = @Composable {
                SessionRow(
                    s = s,
                    progress = uploads[s.id],
                    isActive = isActive,
                    onUpload = { uploadTarget = s },
                    onResume = { UploadManager.resume(context, s) },
                    onOpen = { s.recordId?.let { onOpenRecord(it, s.title) } },
                    onOpenRecorder = { RecorderLaunch.open() },
                    onRename = { renameTarget = s },
                )
            }
            // 왼쪽으로 밀어 목록에서 지운다. 올리기·처리 중인 녹음은 지우면 처리가 멈추므로 밀 수 없다.
            // 서버가 실패로 멈춘 녹음은 더 처리되지 않으므로 정리 완료처럼 휴지통으로 보낸다
            val processed = s.serverState == "ready" || s.serverState == "failed"
            val localOnly = s.recordId == null && s.status != "recording"
            if (!isActive && (processed || localOnly)) {
                SwipeToDelete(onSwiped = {
                    if (processed) {
                        // 처리된 녹음: 휴지통으로(기기 음성은 지우고 서버 기록 연결만 30일 둔다)
                        val dir = RecordingStore.dir(context, s.id)
                        RecordingStore.trash(dir)
                        refresh()
                        announce("${s.title} 삭제되었어요") { RecordingStore.restore(dir); refresh() }
                        true
                    } else {
                        // 올리지 않은 녹음은 되돌릴 수 없어 한 번 더 묻는다
                        deleteTarget = s
                        false
                    }
                }) { row() }
            } else {
                row()
            }
        }
    }

    // 펼친 화면도 한 칸 목록이다(DEC-89). 너무 넓게 퍼지지 않게 가운데 최대 폭으로 둔다
    Box(Modifier.fillMaxSize().background(Wb.colors.surface), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            headerItems()
            sessionItems()
        }
        // 흐름을 막지 않게 화면 아래에 잠깐 떴다 사라진다
        AnnounceHost(announcer, Modifier.align(Alignment.BottomCenter))
    }

    trashItems?.let { items ->
        TrashSheet(
            items = items,
            onRestore = { s ->
                RecordingStore.restore(RecordingStore.dir(context, s.id))
                trashItems = RecordingStore.listTrash(context)
                refresh()
                announce("${s.title} 되살렸어요")
            },
            onDeleteForever = { s ->
                RecordingStore.deleteSession(RecordingStore.dir(context, s.id))
                trashItems = RecordingStore.listTrash(context)
            },
            onDismiss = { trashItems = null },
        )
    }

    renameTarget?.let { r ->
        TitleDialog(r.title, onDismiss = { renameTarget = null }) { newTitle ->
            renameTarget = null
            RecordingStore.setTitle(RecordingStore.dir(context, r.id), newTitle)
            sessions = RecordingStore.list(context, rec.sessionId)
            // 이미 올린 기록이면 서버 제목도 바꾼다. 실패하면 알리고 기기 제목은 그대로 둔다(다음에 다시 고치면 됨)
            r.recordId?.let { id ->
                scope.launch {
                    runCatching { Server.renameRecord(id, newTitle) }
                        .onFailure { notice = "기기 제목은 바꿨지만 서버에는 반영하지 못했어요. 인터넷 연결 뒤 다시 고쳐 주세요." }
                }
            }
        }
    }

    deleteTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("녹음 버리기") },
            text = { Text("아직 올리지 않은 녹음이라 지우면 완전히 사라져요. 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    RecordingStore.deleteSession(RecordingStore.dir(context, d.id))
                    deleteTarget = null
                    refresh()
                    announce("${d.title} 삭제되었어요")
                }) { Text("지우기", color = Wb.colors.crit) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }

    uploadTarget?.let { target ->
        UploadSheetHost(
            target = target,
            initialKind = null,
            onDismiss = { uploadTarget = null },
            onStarted = { uploadTarget = null; refresh() },
        )
    }
}

/** 목업 .up-row: 녹음 한 건의 상태와 다음 행동 */
@Composable
private fun SessionRow(
    s: RecordingSession,
    progress: UploadProgress?,
    isActive: Boolean,
    onUpload: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onOpenRecorder: () -> Unit,
    onRename: () -> Unit,
) {
    val (badge, tone) = when {
        s.status == "recording" -> "녹음 중" to Tone.Crit
        s.status == "interrupted" -> "중단됨 · 확정분만 남음" to Tone.Warn
        s.status == "failed" -> "시작 실패" to Tone.Crit
        s.serverState == "ready" -> "정리 완료" to Tone.Good
        s.serverState == "failed" -> "처리 실패" to Tone.Crit
        s.recordId != null -> "올림" to Tone.Info
        s.status == "imported" -> "가져온 파일" to Tone.Mute
        else -> "기기에만 있음" to Tone.Mute
    }
    // 정리가 끝난 녹음은 카드 어디를 눌러도 회의록·할 일로 들어간다(사용자 요청 11/05: 왼손·오른손 엄지 어느 쪽으로도). ✏️만 제목 고치기.
    // 지금 녹음 중인 줄은 녹음 화면으로 간다(DEC-89)
    val openable = s.serverState == "ready" && s.recordId != null && !isActive
    Surface(
        shape = ControlShape,
        color = Wb.colors.card,
        border = BorderStroke(1.dp, Wb.colors.border),
        modifier = Modifier.fillMaxWidth().clip(ControlShape).clickable(
            enabled = openable || isActive,
            onClickLabel = if (isActive) "녹음 화면 열기" else "회의록·할 일 보기",
            onClick = if (isActive) onOpenRecorder else onOpen,
        ),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 12.dp, end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(WbIcons.Mic, GlassTint.Red, size = 36.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    // 제목 고치기는 ✏️ 아이콘만(녹음 중·올리기 전후 모두. 올린 기록은 서버 제목도 함께 바뀜)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        IconButton(onClick = onRename, modifier = Modifier.size(40.dp)) {
                            Icon(WbIcons.Pencil, contentDescription = "제목 고치기", tint = Wb.colors.muted, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(
                        listOfNotNull(
                            SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(s.startedAt)),
                            formatClock(s.durationMs),
                            deviceAudioSize(s),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Wb.colors.muted,
                    )
                    // 기기 음성 상태는 다음 줄로(사용자 요청 11/05: 크기 뒤에 붙으면 줄이 꺾여 읽기 어려움)
                    val note = listOfNotNull(s.bookmarks.size.takeIf { it > 0 }?.let { "북마크 $it" }, deviceAudioNote(s)).joinToString(" · ")
                    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
                }
                WbChip(badge, tone)
            }
            // 기기 음성을 지운 뒤에도 서버에서 정리된 기록은 계속 열 수 있다
            if (!isActive && (s.segments > 0 || s.serverHasAudio || s.serverState == "failed")) SessionActions(s, progress, onUpload, onResume)
        }
    }
}

/** 서버 진행: 올리기 → 진행 표시 → (멈췄으면) 이어서 처리. 정리가 끝나면 버튼 없이 카드를 눌러 연다 */
@Composable
private fun SessionActions(
    s: RecordingSession,
    progress: UploadProgress?,
    onUpload: () -> Unit,
    onResume: () -> Unit,
) {
    // 앱을 다시 열었는데 진행 표시가 없으면, 백그라운드 작업이 이어서 하는 중이다
    val shown = progress ?: when {
        s.serverState == "failed" -> UploadProgress("처리하지 못했어요 · 왼쪽으로 밀어 지우고 다시 올려 주세요", busy = false, isError = true)
        s.recordId != null && s.serverState != "ready" -> UploadProgress("백그라운드에서 처리 중 · 결과가 나오면 알려 드릴게요", busy = true)
        else -> null
    }
    val busy = shown?.busy == true
    shown?.let {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Wb.colors.primary)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                it.message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (it.isError) Wb.colors.crit else Wb.colors.primary,
            )
        }
    }
    // 지금 이 화면에서 돌고 있는 작업이면 버튼을 숨기고, 백그라운드 추정 표시일 때는 '이어서 처리'로 바로 다시 돌릴 수 있게 둔다
    // 실패로 멈춘 녹음은 다시 돌려도 같으니 버튼 없이 안내만 둔다
    if ((busy && progress != null) || s.serverState == "failed" || s.serverState == "ready") return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (s.recordId != null) SecondaryButton("이어서 처리", onClick = onResume)
        else SecondaryButton("올리기", onClick = onUpload)
    }
}

private fun hasDeviceAudio(s: RecordingSession) = !s.audioDeleted && s.segments > 0

/** 기기에 남은 음성 크기(지웠으면 없음) */
private fun deviceAudioSize(s: RecordingSession): String? =
    if (hasDeviceAudio(s)) "%.1fMB".format(s.bytes / 1_048_576.0) else null

/** 기기 음성 상태: 지움 / 처리 완료 뒤 자동 삭제까지 남은 날 / (올리기 전이면) 없음 */
private fun deviceAudioNote(s: RecordingSession): String? {
    if (!hasDeviceAudio(s)) return "기기 음성 지움"
    val readyAt = s.readyAt ?: return null
    val left = ((readyAt + RecordingStore.KEEP_AFTER_READY_MS - System.currentTimeMillis()) / 86_400_000L + 1).coerceAtLeast(1)
    return "${left}일 뒤 기기에서 자동 삭제"
}
