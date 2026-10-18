package com.workbrain.app.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.graphics.vector.ImageVector
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SectionLabel
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import com.workbrain.app.ui.WbChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()

    // 처리 완료 후 7일 지난 기기 음성 정리(앱을 열 때)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RecordingStore.purgeExpired(context) }
        sessions = RecordingStore.list(context, rec.sessionId)
    }

    // 녹음이 시작·정지되거나 조각이 확정되거나 올리기 단계가 바뀔 때 목록을 다시 읽는다
    LaunchedEffect(rec.status, rec.sessionId, rec.confirmedMs, uploads) {
        sessions = RecordingStore.list(context, rec.sessionId)
    }
    LaunchedEffect(uploadTarget) {
        needConsent = null
        if (uploadTarget != null) needConsent = runCatching { !Server.hasConsent() }.getOrDefault(true)
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

    LazyColumn(
        Modifier.fillMaxSize().background(Wb.colors.surface),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PageHeader("녹음", if (rec.status == RecStatus.IDLE) "말한 건 다 기억해 둘게요" else "잘 듣고 있어요")
            RecorderCard(
                rec = rec,
                onStart = ::startRecording,
                onPause = { RecordingService.pause(context) },
                onResume = { RecordingService.resume(context) },
                onStop = { RecordingService.stop(context) },
            )
            (notice ?: rec.error)?.let { Banner(it, Tone.Crit, Modifier.padding(top = 8.dp)) }
            // 녹음 중에 참석자를 넣어 두면 올리기 시트에 그대로 채워진다(목업 S04)
            val liveId = rec.sessionId
            if (rec.status != RecStatus.IDLE && liveId != null) {
                var live by remember(liveId) { mutableStateOf(sessions.firstOrNull { it.id == liveId }?.attendees.orEmpty()) }
                WbCard(Modifier.padding(top = 8.dp)) {
                    Text("참석자 ${live.size}명", style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                    AttendeesEditor(live, onChange = {
                        live = it
                        RecordingStore.setAttendees(RecordingStore.dir(context, liveId), it)
                    }, Modifier.padding(top = 8.dp))
                }
            }
            SectionLabel("기기에 저장된 녹음 · 올리기")
            if (sessions.isEmpty()) {
                Text("아직 녹음이 없어요. 올리기를 누르기 전까지는 이 휴대폰 안에만 있어요.", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
            }
        }
        items(sessions, key = { it.id }) { s ->
            SessionRow(
                s = s,
                progress = uploads[s.id],
                isActive = s.id == rec.sessionId,
                onUpload = { uploadTarget = s },
                onResume = { UploadManager.resume(context, s) },
                onOpen = { s.recordId?.let { onOpenRecord(it, s.title) } },
                onDelete = { deleteTarget = s },
                onRename = { renameTarget = s },
            )
        }
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
            title = { Text(if (d.serverHasAudio) "기기에서 음성 지우기" else "녹음 버리기") },
            text = {
                Text(
                    if (d.serverHasAudio) "이 휴대폰에 있는 음성만 지워요. 서버에 올린 회의록·할 일은 그대로 볼 수 있어요."
                    else "아직 올리지 않은 녹음이라 지우면 완전히 사라져요. 되돌릴 수 없어요.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val dir = RecordingStore.dir(context, d.id)
                    if (d.serverHasAudio) RecordingStore.deleteAudio(dir) else RecordingStore.deleteSession(dir)
                    deleteTarget = null
                    sessions = RecordingStore.list(context, rec.sessionId)
                }) { Text("지우기", color = Wb.colors.crit) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }

    val target = uploadTarget
    val consentNeeded = needConsent
    if (target != null && consentNeeded != null) {
        UploadSheet(
            title = target.title,
            needConsent = consentNeeded,
            initialAttendees = target.attendees,
            onDismiss = { uploadTarget = null },
            onConfirm = { kind, permission, consentGiven, attendees ->
                uploadTarget = null
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
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val (badge, tone) = when {
        s.status == "recording" -> "녹음 중" to Tone.Crit
        s.status == "interrupted" -> "중단됨 · 확정분만 남음" to Tone.Warn
        s.status == "failed" -> "시작 실패" to Tone.Crit
        s.serverState == "ready" -> "정리 완료" to Tone.Good
        s.recordId != null -> "올림" to Tone.Info
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
            if (!isActive && (s.segments > 0 || s.serverHasAudio)) SessionActions(s, progress, onUpload, onResume, onOpen, onDelete)
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
    onDelete: () -> Unit,
) {
    // 앱을 다시 열었는데 진행 표시가 없으면, 백그라운드 작업이 이어서 하는 중이다
    val shown = progress ?: if (s.recordId != null && s.serverState != "ready") {
        UploadProgress("백그라운드에서 처리 중 · 결과가 나오면 알려 드릴게요", busy = true)
    } else null
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
    if (busy && progress != null) return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            s.serverState == "ready" -> PrimaryButton("회의록·할 일 보기", onClick = onOpen)
            s.recordId != null -> SecondaryButton("이어서 처리", onClick = onResume)
            else -> SecondaryButton("올리기", onClick = onUpload)
        }
        Spacer(Modifier.weight(1f))
        if (s.segments > 0) TextButton(onClick = onDelete) { Text("지우기", color = Wb.colors.muted) }
    }
}

/** 기기에 남은 음성 크기와, 처리 완료 뒤 자동 삭제까지 남은 날 */
private fun deviceAudioLabel(s: RecordingSession): String {
    if (s.audioDeleted || s.segments == 0) return "기기 음성 지움"
    val size = "%.1fMB".format(s.bytes / 1_048_576.0)
    val readyAt = s.readyAt ?: return size
    val left = ((readyAt + RecordingStore.KEEP_AFTER_READY_MS - System.currentTimeMillis()) / 86_400_000L + 1).coerceAtLeast(1)
    return "$size · ${left}일 뒤 기기에서 자동 삭제"
}
