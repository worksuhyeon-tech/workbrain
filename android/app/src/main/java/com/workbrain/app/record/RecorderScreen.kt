package com.workbrain.app.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.data.Para
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.Server
import com.workbrain.app.ui.AnnounceHost
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.ParaGlyph
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.TwoPane
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.dashedPill
import com.workbrain.app.ui.isWideWindow
import com.workbrain.app.ui.rememberAnnouncer

/**
 * S04 녹음 화면(목업 workbrain-capture-android): 프로젝트·참석자 · 녹음 카드(북마크), 펼치면 오른쪽에 참석자 입력·빠른 북마크.
 * DEC-89부터 탭이 아니다. 빠른 입력 마이크 길게·기록 탭 [녹음]·녹음 중 빨간 띠·녹음 알림으로 MainShell이 연다.
 * autoStart: 열자마자 녹음 시작(마이크 길게). 한 번 쓰면 onAutoStartUsed로 꺼서, 화면이 다시 그려져도 두 번 시작하지 않는다.
 * uploadKind: 정지 뒤 올리기 창의 종류 처음 값(null이면 지금 규칙). onUploaded: 정지 뒤 올리기를 맡겼을 때(MainShell이 기록 탭으로 옮김)
 */
@Composable
fun RecorderScreen(
    autoStart: Boolean,
    onAutoStartUsed: () -> Unit,
    uploadKind: String?,
    onBack: () -> Unit,
    onUploaded: () -> Unit,
) {
    // 뒤로 가기는 화면만 닫는다. 녹음은 서비스가 계속하고, 다른 화면 맨 위 띠로 돌아온다
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val rec by RecordingService.state.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    // 이 화면에서 정지를 누른 녹음. 저장이 끝나면 올리기 창을 바로 연다(제목·종류·참석자를 기억할 때 바로 넣도록)
    var stoppedId by remember { mutableStateOf<String?>(null) }
    var uploadTarget by remember { mutableStateOf<RecordingSession?>(null) }
    val announcer = rememberAnnouncer()
    val wide = isWideWindow()

    // 참석자(DEC-68, 목업 S04): 녹음 전에도 늘 보인다. 녹음 중이면 그 녹음의 명단, 아니면 다음 녹음 명단(시작하면 그 녹음으로 옮겨 붙음)
    val liveId = rec.sessionId.takeIf { rec.status != RecStatus.IDLE }
    var attendees by remember(liveId) {
        mutableStateOf(liveId?.let { RecordingStore.readAttendees(RecordingStore.dir(context, it)) } ?: NextAttendees.get(context))
    }
    fun changeAttendees(names: List<String>) {
        attendees = names
        if (liveId != null) RecordingStore.setAttendees(RecordingStore.dir(context, liveId), names) else NextAttendees.set(context, names)
    }
    // 접은 화면은 '+ 참석자'로 이름 넣는 창, 펼친 화면은 오른쪽 이름 칸으로 옮겨 간다(목업)
    var editingAttendees by remember { mutableStateOf(false) }
    val attendeeInput = remember { FocusRequester() }
    // 다음 녹음 명단의 첫 칸 = 더보기 '내 이름·호칭'의 첫 이름(사용자 선택 11/05). 못 읽으면 기기에 적어 둔 이름을 쓴다
    LaunchedEffect(Unit) {
        runCatching { Server.myNames() }.onSuccess { names ->
            NextAttendees.rememberMyName(context, names.firstOrNull())
            if (rec.status == RecStatus.IDLE) attendees = NextAttendees.get(context)
        }
    }

    // 프로젝트(DEC-69, 목업 S04 '📁 프로젝트는 나중에'): 참석자와 같이 녹음 중이면 그 녹음의, 아니면 다음 녹음의 것. 올릴 때 연결된다.
    // 프로젝트 상세에서 마이크를 길게 눌러 열면 MainShell이 먼저 다음 녹음 프로젝트로 적어 둔다(DEC-89)
    var chosenProject by remember(liveId) {
        mutableStateOf(liveId?.let { RecordingStore.readProject(RecordingStore.dir(context, it)) } ?: NextProject.get(context))
    }
    fun chooseProject(p: ChosenProject?) {
        chosenProject = p
        if (liveId != null) RecordingStore.setProject(RecordingStore.dir(context, liveId), p) else NextProject.set(context, p)
    }
    var projectChoices by remember { mutableStateOf<List<ChosenProject>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { Para.allProjects() }.onSuccess { all -> projectChoices = all.filterNot { it.archived }.map { ChosenProject(it.id, it.name) } }
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

    // 마이크 길게(DEC-89): 열자마자 시작. 이미 녹음 중이면 그 녹음 화면만 보여 준다
    LaunchedEffect(autoStart) {
        if (!autoStart) return@LaunchedEffect
        onAutoStartUsed()
        if (rec.status == RecStatus.IDLE) startRecording()
    }

    // 서비스가 마지막 조각을 닫고 IDLE로 돌아온 뒤에 올리기 창을 연다. 저장된 소리가 없으면(바로 정지 등) 올릴 것이 없어 열지 않는다
    LaunchedEffect(rec.status) {
        if (rec.status != RecStatus.IDLE) return@LaunchedEffect
        val id = stoppedId ?: return@LaunchedEffect
        stoppedId = null
        RecordingStore.list(context, null).firstOrNull { it.id == id && it.status == "stopped" && it.segments > 0 }?.let { uploadTarget = it }
    }

    // 녹음 카드: 한 칸이면 전부, 두 칸이면 왼쪽
    fun LazyListScope.recorderItems() {
        item {
            PageHeader("녹음", if (rec.status == RecStatus.IDLE) "말한 건 다 기억해 둘게요" else "잘 듣고 있어요")
            // 목업 S04 .rec-meta: 프로젝트 고르기 · 참석자
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProjectPicker(chosenProject, projectChoices, onPick = ::chooseProject)
                AttendeeStrip(
                    names = attendees,
                    onAdd = { if (wide) runCatching { attendeeInput.requestFocus() } else editingAttendees = true },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
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
                onBookmark = {
                    RecordingService.bookmark(context)
                    announcer.show("${formatClock(rec.elapsedMs)} 지점에 북마크를 남겼어요")
                },
            )
            (notice ?: rec.error)?.let { Banner(it, Tone.Crit, Modifier.padding(top = 8.dp)) }
        }
    }

    // 펼친 화면 오른쪽(목업 S04 펼침): 참석자 입력 · 빠른 북마크. 녹음 중에 넣은 참석자는 올리기 창에 그대로 채워진다
    fun LazyListScope.sidePanelItems() {
        item(key = "attendees") {
            Text("참석자 ${attendees.size}명", style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
            AttendeesEditor(attendees, onChange = ::changeAttendees, Modifier.padding(top = 8.dp), focusRequester = attendeeInput)
            Text(
                "참석자 이름은 회의록의 참석자 칸과 이름 표기에 쓰여요.",
                style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
        item(key = "bookmarks") {
            Text("빠른 북마크", style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink, modifier = Modifier.padding(top = 12.dp))
            SecondaryButton(
                "현재 지점 표시",
                enabled = rec.status != RecStatus.IDLE,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = {
                    RecordingService.bookmark(context)
                    announcer.show("${formatClock(rec.elapsedMs)} 지점에 북마크를 남겼어요")
                },
            )
            if (rec.status != RecStatus.IDLE && rec.bookmarks.isNotEmpty()) {
                rec.bookmarks.forEachIndexed { i, ms ->
                    Text("${formatClock(ms)}  북마크 ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.body, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                // 전사·회의록에서 그 지점으로 찾아가기는 서버 작업이 필요해 다음 단계(사용자 선택 11/05)
                Text(
                    "녹음 중에 중요한 순간을 표시해 두면 이 녹음에 남아요. 전사·회의록에서 그 지점으로 찾아가기는 다음 단계예요.",
                    style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    val listModifier = Modifier.fillMaxSize().background(Wb.colors.surface)
    val spacing = Arrangement.spacedBy(8.dp)
    Box(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        Column(Modifier.fillMaxSize()) {
            // 기록 상세처럼 맨 위 왼쪽 '뒤로'. 녹음 중이어도 닫을 수 있다(녹음은 계속)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 뒤로", color = Wb.colors.link) }
            }
            if (wide) {
                TwoPane(
                    leftWeight = 0.5f,
                    left = {
                        LazyColumn(listModifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = spacing) { recorderItems() }
                    },
                    right = {
                        LazyColumn(listModifier, contentPadding = PaddingValues(16.dp), verticalArrangement = spacing) { sidePanelItems() }
                    },
                )
            } else {
                LazyColumn(listModifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = spacing) {
                    recorderItems()
                }
            }
        }
        // 흐름을 막지 않게 화면 아래에 잠깐 떴다 사라진다
        AnnounceHost(announcer, Modifier.align(Alignment.BottomCenter))
    }

    if (editingAttendees) {
        AttendeesDialog(attendees, onDismiss = { editingAttendees = false }, title = "참석자") { names ->
            editingAttendees = false
            changeAttendees(names)
            RecentAttendees.remember(context, names)
        }
    }

    uploadTarget?.let { target ->
        UploadSheetHost(
            target = target,
            initialKind = uploadKind,
            // 창을 닫으면 이 화면에 남는다. 녹음은 기록 탭 목록에 '기기에만 있음'으로 있어 나중에 올릴 수 있다
            onDismiss = { uploadTarget = null },
            onStarted = { uploadTarget = null; onUploaded() },
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
    onBookmark: () -> Unit,
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
                // 왼쪽: 일시정지·재개, 오른쪽: 북마크(현재 지점 표시, DEC-68). 둘 다 녹음 중이 아니면 흐리게
                RoundButton(
                    glyph = if (rec.status == RecStatus.PAUSED) WbIcons.Play else WbIcons.Pause,
                    enabled = rec.status != RecStatus.IDLE,
                    description = if (rec.status == RecStatus.PAUSED) "다시 녹음" else "일시정지",
                    onClick = if (rec.status == RecStatus.PAUSED) onResume else onPause,
                )
                BigButton(recording = rec.status != RecStatus.IDLE, onClick = if (rec.status == RecStatus.IDLE) onStart else onStop)
                RoundButton(glyph = WbIcons.Bookmark, enabled = rec.status != RecStatus.IDLE, description = "현재 지점 북마크", onClick = onBookmark)
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
private fun RoundButton(glyph: ImageVector, enabled: Boolean, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Wb.colors.card,
        border = BorderStroke(1.dp, Wb.colors.borderStrong),
        modifier = Modifier.size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(glyph, contentDescription = description, tint = Wb.colors.ink.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(22.dp))
        }
    }
}

private val AvatarColors = listOf(GlassTint.Purple, GlassTint.Orange, GlassTint.Green, GlassTint.Blue).map { it.deep }

/**
 * 목업 S04 .rec-meta: 참석자 동그라미(이름 첫 글자) + '+ 참석자'(점선 알약). 녹음 전에도 늘 보인다(DEC-68).
 * 줄 어디를 눌러도 참석자를 넣는 곳으로 간다(접은 화면은 창, 펼친 화면은 오른쪽 이름 칸)
 */
@Composable
private fun AttendeeStrip(names: List<String>, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(ControlShape).clickable(onClickLabel = "참석자 넣기", onClick = onAdd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 동그라미는 조금씩 겹쳐 놓고(목업), 많으면 앞의 5명만 보이고 나머지는 숫자로
        val shown = names.take(5)
        if (shown.isNotEmpty()) {
            Box(Modifier.size(width = (30 + 22 * (shown.size - 1)).dp, height = 30.dp)) {
                shown.forEachIndexed { i, n ->
                    Box(
                        Modifier.offset(x = (22 * i).dp).size(30.dp)
                            .border(2.dp, Wb.colors.surface, CircleShape)
                            .background(AvatarColors[i % AvatarColors.size], CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(n.take(1), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        val overflow = names.size - shown.size
        if (overflow > 0) {
            Text("+$overflow", style = MaterialTheme.typography.labelMedium, color = Wb.colors.muted, modifier = Modifier.padding(start = 6.dp))
        }
        Text(
            "+ 참석자",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Wb.colors.body,
            modifier = Modifier
                .padding(start = if (shown.isEmpty()) 0.dp else 8.dp)
                .dashedPill(Wb.colors.borderStrong)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 목업 S04 .proj-sel: '📁 프로젝트는 나중에' / 고른 프로젝트 이름 ▾. 진행 중 프로젝트만 고를 수 있다.
 * 고른 프로젝트는 올릴 때 서버 기록에 연결된다(실패하면 기록 상세에서 다시 잇는다)
 */
@Composable
private fun ProjectPicker(chosen: ChosenProject?, choices: List<ChosenProject>, onPick: (ChosenProject?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(PillShape).background(Wb.colors.card, PillShape).border(1.dp, Wb.colors.border, PillShape)
                .clickable(onClickLabel = "프로젝트 고르기") { open = true }
                .heightIn(min = 36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(WbIcons.Folder, contentDescription = null, tint = if (chosen == null) Wb.colors.muted else ParaGlyph.tint(PlaceType.PROJECT), modifier = Modifier.size(15.dp))
            Text(
                chosen?.name ?: "프로젝트는 나중에",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (chosen == null) Wb.colors.muted else Wb.colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp),
            )
            Icon(WbIcons.ChevronDown, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.padding(start = 4.dp).size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 220.dp, max = 320.dp)) {
            DropdownMenuItem(text = { Text("프로젝트는 나중에") }, onClick = { open = false; onPick(null) })
            if (choices.isEmpty()) {
                Text(
                    "진행 중인 프로젝트가 없어요. 프로젝트 탭에서 만들 수 있어요.",
                    style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            choices.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(WbIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { open = false; onPick(p) },
                )
            }
        }
    }
}
