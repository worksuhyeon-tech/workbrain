package com.workbrain.app.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.Supa
import com.workbrain.app.data.Server
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** S04 녹음: 녹음 시작·일시정지·정지, 기기 안 녹음 목록, 올리기 → 회의록·할 일 후보 보기 */
@Composable
fun HomeScreen(onOpenRecord: (recordId: String, title: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by RecordingService.state.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf(RecordingStore.list(context, rec.sessionId)) }
    val uploads by UploadManager.progress.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    // 올리기 확인 창: 대상 녹음과 동의 필요 여부(null이면 확인 중)
    var uploadTarget by remember { mutableStateOf<RecordingSession?>(null) }
    var needConsent by remember { mutableStateOf<Boolean?>(null) }

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

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WorkBrain", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    Supa.client.auth.currentUserOrNull()?.email.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { scope.launch { runCatching { Supa.client.auth.signOut() } } },
                enabled = rec.status == RecStatus.IDLE,
            ) { Text("로그아웃") }
        }

        RecorderCard(
            rec = rec,
            onStart = ::startRecording,
            onPause = { RecordingService.pause(context) },
            onResume = { RecordingService.resume(context) },
            onStop = { RecordingService.stop(context) },
        )
        (notice ?: rec.error)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(20.dp))
        Text("기기에 저장된 녹음", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "올리기를 누르기 전까지는 이 휴대폰 안에만 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Text("아직 녹음이 없어요.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s ->
                SessionRow(
                    s = s,
                    progress = uploads[s.id],
                    isActive = s.id == rec.sessionId,
                    onUpload = { uploadTarget = s },
                    onResume = { UploadManager.resume(context, s) },
                    onOpen = { s.recordId?.let { onOpenRecord(it, s.title) } },
                )
            }
        }
    }

    val target = uploadTarget
    val consentNeeded = needConsent
    if (target != null && consentNeeded != null) {
        UploadDialog(
            needConsent = consentNeeded,
            onDismiss = { uploadTarget = null },
            onConfirm = { kind, permission, consentGiven ->
                uploadTarget = null
                UploadManager.start(context, target, kind, permission, consentGiven)
            },
        )
    }
}

@Composable
private fun RecorderCard(
    rec: RecState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val (label, color) = when (rec.status) {
                RecStatus.IDLE -> "대기 중" to MaterialTheme.colorScheme.onSurfaceVariant
                RecStatus.RECORDING -> "● 녹음 중" to Color(0xFFD93A2B)
                RecStatus.PAUSED -> "일시정지" to Color(0xFFB7791F)
            }
            Text(label, color = color, fontWeight = FontWeight.Bold)
            Text(formatClock(rec.elapsedMs), fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Text(
                if (rec.status == RecStatus.IDLE) "약 10초마다 확정 저장 · 화면을 꺼도 계속 녹음돼요"
                else "${formatClock(rec.confirmedMs)}까지 확정 저장됨 · 강제 종료돼도 여기까지는 남아요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (rec.status) {
                    RecStatus.IDLE -> Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93A2B)),
                        modifier = Modifier.height(52.dp).width(200.dp),
                    ) { Text("녹음 시작") }
                    RecStatus.RECORDING -> {
                        OutlinedButton(onClick = onPause, modifier = Modifier.height(52.dp)) { Text("일시정지") }
                        Button(onClick = onStop, modifier = Modifier.height(52.dp)) { Text("정지·저장") }
                    }
                    RecStatus.PAUSED -> {
                        OutlinedButton(onClick = onResume, modifier = Modifier.height(52.dp)) { Text("재개") }
                        Button(onClick = onStop, modifier = Modifier.height(52.dp)) { Text("정지·저장") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    s: RecordingSession,
    progress: UploadProgress?,
    isActive: Boolean,
    onUpload: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
) {
    val (badge, badgeColor) = when (s.status) {
        "recording" -> "녹음 중" to Color(0xFFD93A2B)
        "interrupted" -> "중단됨 · 확정분만 남음" to Color(0xFFB7791F)
        "failed" -> "시작 실패" to MaterialTheme.colorScheme.error
        else -> "저장됨" to Color(0xFF1E8E5A)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(badge, color = badgeColor, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(s.startedAt)) +
                    " · " + formatClock(s.durationMs) +
                    " · 조각 ${s.segments}개 · " + "%.1fMB".format(s.bytes / 1_048_576.0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isActive && s.segments > 0) SessionActions(s, progress, onUpload, onResume, onOpen)
        }
    }
}

/** 녹음 한 건의 서버 진행: 올리기 → 진행 표시 → (멈췄으면) 이어서 처리 → 회의록·할 일 보기 */
@Composable
private fun SessionActions(
    s: RecordingSession,
    progress: UploadProgress?,
    onUpload: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
) {
    val busy = progress?.busy == true
    progress?.let {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(if (busy) 6.dp else 0.dp))
            Text(
                it.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (busy) return
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            s.serverState == "ready" -> Button(onClick = onOpen) { Text("회의록·할 일 보기") }
            s.recordId != null -> OutlinedButton(onClick = onResume) { Text("이어서 처리") }
            else -> OutlinedButton(onClick = onUpload) { Text("올리기") }
        }
    }
}
