package com.workbrain.app.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import com.workbrain.app.ui.CardShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.data.Server
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.GlassIcon
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.WbIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SectionLabel
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.TwoPane
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.isWideWindow
import com.workbrain.app.ui.WbCard
import com.workbrain.app.ui.WbChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.workbrain.app.ui.TitleDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** S04 녹음(목업 workbrain-capture-android): 녹음 카드, 기기 안 녹음 목록, 올리기 → 회의록·할 일 보기 */
@Composable
fun HomeScreen(onOpenRecord: (recordId: String, title: String) -> Unit) {
    val context = LocalContext.current
    val rec by RecordingService.state.collectAsStateWithLifecycle()
    val uploads by UploadManager.progress.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf(RecordingStore.list(context, rec.sessionId)) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 올리기 확인 창: 대상 녹음과 동의 필요 여부(null이면 확인 중)
    var uploadTarget by remember { mutableStateOf<RecordingSession?>(null) }
    var needConsent by remember { mutableStateOf<Boolean?>(null) }
    // 지우기 확인 창 대상
    var deleteTarget by remember { mutableStateOf<RecordingSession?>(null) }
    // 제목 고치기 창 대상
    var renameTarget by remember { mutableStateOf<RecordingSession?>(null) }
    // 이 화면에서 정지를 누른 녹음. 저장이 끝나면 올리기 시트를 바로 연다(제목·종류·참석자를 기억할 때 바로 넣도록)
    var stoppedId by remember { mutableStateOf<String?>(null) }
    // 휴지통(DEC-51): 열려 있을 때만 목록을 읽는다
    var trashItems by remember { mutableStateOf<List<RecordingSession>?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val wide = isWideWindow()
    fun refresh() { sessions = RecordingStore.list(context, rec.sessionId) }

    // 휴지통은 접어도 펴도 화면 오른쪽 위(기록 상세의 ↪ 공유 자리)에 아이콘으로 둔다(사용자 요청 11/03).
    // 접은 화면은 '녹음' 제목 줄 끝, 펼친 화면은 오른쪽 칸(녹음 목록) 맨 위 줄 끝
    val trashButton: @Composable () -> Unit = {
        IconButton(onClick = { trashItems = RecordingStore.listTrash(context) }) {
            Icon(WbIcons.Trash, contentDescription = "휴지통", tint = Wb.colors.ink)
        }
    }

    // 지운 뒤 화면 아래에 잠깐 알린다. 되돌릴 수 있으면 '되돌리기'를 함께 보여 준다
    fun announce(message: String, undo: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            // 남은 시간을 게이지로 보여 주려면 사라지는 시점을 앱이 정해야 한다(기본 Short는 접근성 설정에 따라 늘어남)
            val result = withTimeoutOrNull(TOAST_MS) {
                snackbar.showSnackbar(message, actionLabel = if (undo != null) "되돌리기" else null, duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    // 파일 가져오기(DEC-40): 복사가 끝나면 바로 올리기 시트를 연다
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
        // 서비스가 마지막 조각을 닫고 IDLE로 돌아온 뒤에 연다. 저장된 소리가 없으면(바로 정지 등) 올릴 것이 없어 열지 않는다
        if (rec.status == RecStatus.IDLE) {
            stoppedId?.let { id ->
                stoppedId = null
                sessions.firstOrNull { it.id == id && it.status == "stopped" && it.segments > 0 }?.let { uploadTarget = it }
            }
        }
    }
    // 시트는 바로 열고 동의 여부는 시트 안에서 기다린다. 서버 요청 한도(150초)를 그대로 기다리면 창이 안 뜬 것처럼 보이므로
    // 5초 안에 답이 없거나 실패하면 '동의 필요'로 보고 동의 칸을 보여 준다
    LaunchedEffect(uploadTarget) {
        needConsent = null
        if (uploadTarget != null) {
            needConsent = runCatching { withTimeoutOrNull(5_000) { !Server.hasConsent() } }.getOrNull() ?: true
        }
    }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            notice = null
            RecordingService.start(context)
        } else {
            notice = "마이크 권한이 없어 녹음할 수 없어요. 설정 › 앱 › WorkBrain › 권한에서 마이크를 허용해 주세요."
        }
    }
    fun startRecording() {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasMic) RecordingService.start(context) else askPermissions.launch(permissions)
    }

    // 녹음 카드: 한 칸이면 맨 위, 두 칸이면 왼쪽
    fun LazyListScope.recorderItems() {
        item {
            PageHeader("녹음", if (rec.status == RecStatus.IDLE) "말한 건 다 기억해 둘게요" else "잘 듣고 있어요") {
                if (!wide) trashButton()
            }
            RecorderCard(
                rec = rec,
                onStart = ::startRecording,
                onPause = { RecordingService.pause(context) },
                onResume = { RecordingService.resume(context) },
                onStop = {
                    stoppedId = rec.sessionId
                    RecordingService.stop(context)
                },
            )
            (notice ?: rec.error)?.let { Banner(it, Tone.Crit, Modifier.padding(top = 8.dp)) }
        }
    }

    // 참석자·기기 녹음 목록: 한 칸이면 녹음 카드 아래, 두 칸이면 오른쪽(목업 S04 펼침)
    fun LazyListScope.sessionItems() {
        // 녹음 중에 참석자를 넣어 두면 올리기 시트에 그대로 채워진다(목업 S04)
        val liveId = rec.sessionId
        if (rec.status != RecStatus.IDLE && liveId != null) item(key = "attendees-$liveId") {
            var live by remember(liveId) { mutableStateOf(sessions.firstOrNull { it.id == liveId }?.attendees.orEmpty()) }
            WbCard {
                Text("참석자 ${live.size}명", style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                AttendeesEditor(live, onChange = {
                    live = it
                    RecordingStore.setAttendees(RecordingStore.dir(context, liveId), it)
                }, Modifier.padding(top = 8.dp))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("기기에 저장된 녹음 · 올리기", Modifier.weight(1f))
                // 음성 또는 클로바노트 등에서 받아쓴 txt(DEC-53)
                TextButton(onClick = { pickAudio.launch(arrayOf("audio/*", "text/plain")) }) { Text("파일 가져오기", color = Wb.colors.link) }
                if (wide) trashButton()
            }
            if (sessions.isEmpty()) {
                Text("아직 녹음이 없어요. 올리기를 누르기 전까지는 이 휴대폰 안에만 있어요.", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
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

    val listModifier = Modifier.fillMaxSize().background(Wb.colors.surface)
    val spacing = Arrangement.spacedBy(8.dp)
    Box(Modifier.fillMaxSize()) {
        if (wide) {
            TwoPane(
                leftWeight = 0.5f,
                left = {
                    LazyColumn(listModifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = spacing) { recorderItems() }
                },
                right = {
                    LazyColumn(listModifier, contentPadding = PaddingValues(16.dp), verticalArrangement = spacing) { sessionItems() }
                },
            )
        } else {
            LazyColumn(listModifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = spacing) {
                recorderItems()
                sessionItems()
            }
        }
        // 흐름을 막지 않게 화면 아래에 잠깐 떴다 사라진다
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { DoneSnackbar(it) }
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
        UploadSheet(
            title = target.title,
            needConsent = needConsent,
            initialAttendees = target.attendees,
            // 통화 녹음 공유나 '통화'가 들어간 파일 이름이면 처음부터 '통화'로 고른다
            initialKind = if (target.channel == "call-share" || "통화" in target.title) "call" else "meeting",
            onDismiss = { uploadTarget = null },
            onConfirm = { title, kind, permission, consentGiven, attendees ->
                uploadTarget = null
                // 올리는 작업이 기기 메타에서 제목을 읽어 서버 기록을 만들므로, 시작 전에 먼저 적어 둔다
                if (title != target.title) {
                    RecordingStore.setTitle(RecordingStore.dir(context, target.id), title)
                    refresh()
                }
                UploadManager.start(context, target, kind, permission, consentGiven, attendees)
            },
        )
    }
}

/** 목업 .rec-card: 상태 · 큰 타이머 · 확정 저장 표시 · 둥근 버튼(일시정지 / 녹음·정지) · 안내 칩 */
@Composable
private fun RecorderCard(
    rec: RecState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    WbCard(padding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val (label, color) = when (rec.status) {
                RecStatus.IDLE -> "대기 중" to Wb.colors.muted
                RecStatus.RECORDING -> "● 녹음 중" to Wb.colors.crit
                RecStatus.PAUSED -> "일시정지" to Wb.colors.warn
            }
            Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(formatClock(rec.elapsedMs), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, letterSpacing = (-1).sp)
            Text(
                if (rec.status == RecStatus.IDLE) " " else "${formatClock(rec.confirmedMs)}까지 확정 저장됨",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Wb.colors.good,
            )
            Spacer(Modifier.size(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                // 왼쪽: 일시정지·재개(녹음 중이 아니면 흐리게). 오른쪽은 북마크 자리(다음 버전) — 가운데 정렬을 위해 같은 크기로 비워 둔다
                RoundButton(
                    glyph = if (rec.status == RecStatus.PAUSED) WbIcons.Play else WbIcons.Pause,
                    enabled = rec.status != RecStatus.IDLE,
                    onClick = if (rec.status == RecStatus.PAUSED) onResume else onPause,
                )
                BigButton(recording = rec.status != RecStatus.IDLE, onClick = if (rec.status == RecStatus.IDLE) onStart else onStop)
                Spacer(Modifier.size(52.dp))
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WbChip("10초마다 확정 저장", Tone.Mute)
                WbChip("화면을 꺼도 계속 녹음", Tone.Mute)
            }
        }
    }
}

@Composable
private fun BigButton(recording: Boolean, onClick: () -> Unit) {
    // 대기: 빨간 원 안 흰 점(녹음 시작) · 녹음 중: 진한 원 안 흰 네모(정지·저장)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (recording) Wb.colors.ink else Wb.colors.crit,
        shadowElevation = 3.dp,
        modifier = Modifier.size(76.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(if (recording) 24.dp else 28.dp).background(
                    if (recording && Wb.colors.isDark) Wb.colors.surface else Color.White,
                    if (recording) RoundedCornerShape(4.dp) else CircleShape,
                ),
            )
        }
    }
}

@Composable
private fun RoundButton(glyph: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Wb.colors.card,
        border = BorderStroke(1.dp, Wb.colors.borderStrong),
        modifier = Modifier.size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(glyph, contentDescription = null, tint = Wb.colors.ink.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(22.dp))
        }
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
    Surface(shape = ControlShape, color = Wb.colors.card, border = BorderStroke(1.dp, Wb.colors.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(WbIcons.Mic, GlassTint.Red, size = 36.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    // 제목을 누르면 고친다(녹음 중·올리기 전후 모두. 올린 기록은 서버 제목도 함께 바뀜)
                    Row(Modifier.clickable(onClick = onRename), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(WbIcons.Pencil, contentDescription = "제목 고치기", tint = Wb.colors.muted, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                    }
                    Text(
                        SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(s.startedAt)) +
                            " · " + formatClock(s.durationMs) + " · " + deviceAudioLabel(s),
                        style = MaterialTheme.typography.bodySmall,
                        color = Wb.colors.muted,
                    )
                }
                WbChip(badge, tone)
            }
            // 기기 음성을 지운 뒤에도 서버에서 정리된 기록은 계속 열 수 있다
            if (!isActive && (s.segments > 0 || s.serverHasAudio || s.serverState == "failed")) SessionActions(s, progress, onUpload, onResume, onOpen)
        }
    }
}

/** 서버 진행: 올리기 → 진행 표시 → (멈췄으면) 이어서 처리 → 회의록·할 일 보기 */
@Composable
private fun SessionActions(
    s: RecordingSession,
    progress: UploadProgress?,
    onUpload: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
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
    if ((busy && progress != null) || s.serverState == "failed") return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            s.serverState == "ready" -> PrimaryButton("회의록·할 일 보기", onClick = onOpen)
            s.recordId != null -> SecondaryButton("이어서 처리", onClick = onResume)
            else -> SecondaryButton("올리기", onClick = onUpload)
        }
    }
}

/** 알림이 떠 있는 시간(DEC-51, 10/26 사용자 요청으로 4초 → 3초). 게이지도 같은 시간 동안 줄어든다 */
private const val TOAST_MS = 3_000L

/**
 * 지움·되살림 알림: 체크 표시 + 문구 + (있으면) 되돌리기 + 아래 남은 시간 게이지.
 * 바탕은 불투명(반투명은 글씨가 덜 보여 사용자가 되돌림, 10/26)
 */
@Composable
private fun DoneSnackbar(data: SnackbarData) {
    val remaining = remember(data) { Animatable(1f) }
    LaunchedEffect(data) { remaining.animateTo(0f, tween(TOAST_MS.toInt(), easing = LinearEasing)) }
    // 글자 길이만큼만 차지하고 가운데 둔다(화면 폭 전체는 너무 큼, 10/26 사용자 요청). 모서리는 카드와 같은 둥글기
    Surface(shape = CardShape, color = Wb.colors.ink, shadowElevation = 4.dp, modifier = Modifier.widthIn(max = 360.dp)) {
        // 게이지가 글줄과 같은 폭이 되도록 가장 넓은 줄에 맞춘다
        Column(Modifier.width(IntrinsicSize.Max)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(WbIcons.Check, contentDescription = null, tint = Wb.colors.surface, modifier = Modifier.size(18.dp))
                Text(
                    data.visuals.message,
                    color = Wb.colors.surface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                )
                data.visuals.actionLabel?.let { label ->
                    TextButton(onClick = data::performAction) { Text(label, color = Wb.colors.surface, fontWeight = FontWeight.Bold) }
                }
            }
            // 남은 시간: 다 줄어들면 알림이 사라지고 되돌리기도 끝난다
            Box(Modifier.fillMaxWidth().height(3.dp).background(Wb.colors.surface.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(remaining.value).fillMaxHeight().background(Wb.colors.surface.copy(alpha = 0.75f)))
            }
        }
    }
}

/** 왼쪽으로 살짝 밀면 빨간 휴지통이 보이고, 절반 넘게 밀면 지운다. onSwiped가 false면 제자리로 돌아온다 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onSwiped: () -> Boolean, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart && onSwiped() },
        positionalThreshold = { total -> total * 0.5f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // 밀고 있을 때만 빨간 바탕을 그린다(둥근 모서리 틈으로 비치지 않게)
            val dragging = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (dragging) Wb.colors.crit else Color.Transparent, ControlShape).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (dragging) Icon(WbIcons.Trash, contentDescription = "지우기", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        },
    ) { content() }
}

/** 기기에 남은 음성 크기와, 처리 완료 뒤 자동 삭제까지 남은 날 */
private fun deviceAudioLabel(s: RecordingSession): String {
    if (s.audioDeleted || s.segments == 0) return "기기 음성 지움"
    val size = "%.1fMB".format(s.bytes / 1_048_576.0)
    val readyAt = s.readyAt ?: return size
    val left = ((readyAt + RecordingStore.KEEP_AFTER_READY_MS - System.currentTimeMillis()) / 86_400_000L + 1).coerceAtLeast(1)
    return "$size · ${left}일 뒤 기기에서 자동 삭제"
}
