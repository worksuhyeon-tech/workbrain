package com.workbrain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.workbrain.app.record.UploadManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.record.SharedImport
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.PlanKind
import com.workbrain.app.data.Server
import com.workbrain.app.more.MoreScreen
import com.workbrain.app.projects.ProjectsScreen
import com.workbrain.app.record.NextProject
import com.workbrain.app.record.RecState
import com.workbrain.app.record.RecStatus
import com.workbrain.app.record.RecorderLaunch
import com.workbrain.app.record.RecorderScreen
import com.workbrain.app.record.RecordingService
import com.workbrain.app.record.RecordsScreen
import com.workbrain.app.record.formatClock
import com.workbrain.app.review.RecordDetailScreen
import com.workbrain.app.review.ReviewBadge
import com.workbrain.app.review.ReviewScreen
import com.workbrain.app.review.Snooze
import com.workbrain.app.today.TodayNav
import com.workbrain.app.today.TodayScreen
import com.workbrain.app.ui.GlassIcon
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PanePlaceholder
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.isWideWindow
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.Wb

// 아래 메뉴(DEC-89): 오늘 / 프로젝트 / 기록 / 검토함 / 더보기. 녹음은 탭이 아니라 기록 탭·빠른 입력 마이크에서 연다.
// 아이콘은 글래스(고른 메뉴만 색, 나머지는 회색 유리, DEC-32)
private enum class Tab(val label: String, val glyph: ImageVector, val tint: GlassTint) {
    TODAY("오늘", WbIcons.Home, GlassTint.Blue),
    PROJECTS("프로젝트", WbIcons.Folder, GlassTint.Purple),
    RECORDS("기록", WbIcons.Document, GlassTint.Red),
    REVIEW("검토함", WbIcons.Review, GlassTint.Green),
    MORE("더보기", WbIcons.Menu, GlassTint.Orange),
}

/**
 * 로그인 뒤 화면 틀: 메뉴 5개(접힘 = 아래, 펼침 = 왼쪽 세로)와, 그 위에 여는 기록 상세·녹음 화면. 둘 다 뒤로 가기로 닫는다.
 * 녹음 중이고 녹음 화면이 닫혀 있으면 화면 맨 위에 빨간 띠를 띄워 돌아갈 길을 둔다(DEC-89)
 */
@Composable
fun MainShell() {
    // 앱을 열면 오늘(DEC-89). 회의 녹음은 다른 앱에서 공유로 들어오므로 바로 녹음할 곳을 첫 화면에 둘 까닭이 없어졌다
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    // 열린 기록: 서버 기록 ID와 제목. null이면 메뉴 화면
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailTitle by rememberSaveable { mutableStateOf("") }
    var reviewCount by rememberSaveable { mutableIntStateOf(0) }
    // 녹음 화면(DEC-89): 접기·펼치기에도 열린 채 남도록 여기에 둔다. autoStart는 한 번 쓰면 끈다(두 번 시작 방지),
    // kind는 정지 뒤 올리기 창 종류 처음 값(마이크 길게 = '혼자 메모')이라 녹음이 끝나 올릴 때까지 들고 있는다
    var recorderOpen by rememberSaveable { mutableStateOf(false) }
    var recorderAutoStart by rememberSaveable { mutableStateOf(false) }
    var recorderKind by rememberSaveable { mutableStateOf<String?>(null) }
    val rec by RecordingService.state.collectAsStateWithLifecycle()

    // 검토함 배지: 메뉴를 바꾸거나 상세를 닫을 때, 다른 화면이 제안을 바꿨다고 알릴 때(ReviewBadge) 다시 센다.
    // 미룬 후보는 그날까지 재촉하지 않도록 빼고 센다(DEC-63)
    val badgeVersion by ReviewBadge.version.collectAsStateWithLifecycle()
    LaunchedEffect(tab, detailId, badgeVersion) {
        runCatching { Server.pendingProposals().count { !Snooze.isActive(it.snoozedUntil) } }.onSuccess { reviewCount = it }
    }
    val open: (String, String) -> Unit = { id, title -> detailTitle = title; detailId = id }
    // 앱을 열 때: 결과가 아직 없는 녹음은 백그라운드 처리를 다시 건다(강제 중지 뒤에도 이어 가기, DEC-37)
    val context = LocalContext.current
    LaunchedEffect(Unit) { UploadManager.ensureRunning(context) }
    // 밀린 프로젝트 연결 제안(FR-06, DEC-75): 지난 기록·실패한 기록의 제안을 몇 건씩 채운다. 보조 기능이라 화면에는 알리지 않고,
    // 앱을 닫으면 멈췄다가 다음에 열 때 이어진다(실패만 개발 기록 logcat 'WorkBrainLink')
    LaunchedEffect(Unit) {
        runCatching { Server.suggestLinks() }.onFailure { android.util.Log.w("WorkBrainLink", "link sweep stopped", it) }
    }
    // 다른 앱에서 음성·글을 공유하면 기록 탭으로 와서 가져오기를 이어 간다(DEC-40·53·89). 지침 zip은 더보기로(FR-30)
    val shared by SharedImport.pending.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        val item = shared ?: return@LaunchedEffect
        tab = if (item is SharedImport.Item.SkillZip) Tab.MORE else Tab.RECORDS
        detailId = null
        recorderOpen = false
    }
    // 녹음 화면 열기 요청(빠른 입력 마이크 길게·기록 탭 [녹음]·녹음 알림, DEC-89).
    // 녹음 전일 때만 새 녹음 설정(바로 시작·종류·프로젝트)을 받고, 녹음 중이면 그 녹음 화면만 연다
    val recorderRequest by RecorderLaunch.pending.collectAsStateWithLifecycle()
    LaunchedEffect(recorderRequest) {
        val req = recorderRequest ?: return@LaunchedEffect
        RecorderLaunch.pending.value = null
        if (RecordingService.state.value.status == RecStatus.IDLE) {
            recorderAutoStart = req.start
            recorderKind = req.kind
            req.project?.let { NextProject.set(context, it) }
        }
        detailId = null
        recorderOpen = true
    }

    // 펼친 화면(두 칸, DEC-47): 메뉴는 왼쪽 세로, 검토함에서 연 기록은 전체 화면 대신 오른쪽 칸에 연다.
    // 열린 기록은 detailId 하나로 들고 있어서, 접으면 같은 기록이 한 칸 전체 화면으로 이어진다
    val wide = isWideWindow()
    val detailInPane = wide && tab == Tab.REVIEW
    // 두 칸에서 한쪽이 추가·버리기를 하면 다른 쪽을 다시 읽게 하는 번호
    var inboxVersion by remember { mutableIntStateOf(0) }
    var detailVersion by remember { mutableIntStateOf(0) }

    // 기록 상세 ⋮에서 녹음을 지우면 기록 탭으로 가서 '삭제되었어요 · 되돌리기'를 보여 준다(검토함에서 열었어도 같음)
    val afterTrash: () -> Unit = { detailId = null; tab = Tab.RECORDS }
    // 다른 탭에서 프로젝트 탭의 특정 항목('all' = 모든 할 일, 'p:ID')을 열 때 한 번 넘기는 열쇠
    var projectsStart by remember { mutableStateOf<String?>(null) }
    // 오늘 탭 화요일 카드에서 더보기의 업무계획(주간·월간)을 바로 열 때 한 번 넘기는 열쇠(FR-33)
    var planStart by remember { mutableStateOf<PlanKind?>(null) }
    val todayNav = TodayNav(
        openRecord = open,
        openReview = { tab = Tab.REVIEW },
        openRecords = { tab = Tab.RECORDS },
        openProjects = { key -> projectsStart = key; tab = Tab.PROJECTS },
        openMore = { tab = Tab.MORE },
        openWorkPlan = { kind -> planStart = kind; tab = Tab.MORE },
    )

    val body: @Composable () -> Unit = {
        val openId = detailId
        when {
            recorderOpen -> RecorderScreen(
                autoStart = recorderAutoStart,
                onAutoStartUsed = { recorderAutoStart = false },
                uploadKind = recorderKind,
                onBack = { recorderOpen = false },
                // 정지 뒤 올리기를 맡기면 진행이 보이는 기록 탭으로(11/11 사용자 선택)
                onUploaded = { recorderOpen = false; recorderKind = null; tab = Tab.RECORDS },
            )
            openId != null && !detailInPane -> RecordDetailScreen(openId, detailTitle, onBack = { detailId = null }, onTrashed = afterTrash)
            else -> when (tab) {
                Tab.TODAY -> TodayScreen(todayNav)
                Tab.PROJECTS -> ProjectsScreen(
                    startKey = projectsStart,
                    onStartUsed = { projectsStart = null },
                    // 프로젝트에서 연 기록을 닫으면 보던 항목으로 돌아오게 그 열쇠를 맡겨 둔다
                    onOpenRecord = { id, title, returnKey -> projectsStart = returnKey; open(id, title) },
                )
                Tab.RECORDS -> RecordsScreen(onOpenRecord = open)
                Tab.REVIEW -> ReviewScreen(
                    onOpenRecord = open,
                    onCountChanged = { reviewCount = it },
                    refreshKey = inboxVersion,
                    onChanged = { detailVersion++ },
                    detailPane = if (!wide) null else {
                        {
                            if (openId == null) {
                                PanePlaceholder("할 일 후보의 ‘회의록 보기’를 누르면\n여기에 기록이 열려요")
                            } else key(openId, detailVersion) {
                                RecordDetailScreen(openId, detailTitle, onBack = null, onChanged = { inboxVersion++ }, onTrashed = afterTrash)
                            }
                        }
                    },
                )
                Tab.MORE -> MoreScreen(startPlan = planStart, onStartUsed = { planStart = null })
            }
        }
    }
    // 메뉴를 누르면 녹음 화면도 닫는다. 녹음은 서비스가 계속하고 맨 위 띠로 돌아갈 수 있다
    val select: (Tab) -> Unit = { tab = it; detailId = null; recorderOpen = false }
    // 오른쪽 칸에 기록이 열려 있어도 검토함 메뉴는 고른 상태로 보인다
    val isOn: (Tab) -> Boolean = { t -> tab == t && !recorderOpen && (detailId == null || detailInPane) }
    // 녹음 중 빨간 띠: 몸통 위에 둔다. 띠가 생기고 없어져도 몸통 화면 상태는 그대로다(같은 Column 안 뒤쪽 자리)
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            if (rec.status != RecStatus.IDLE && !recorderOpen) RecordingBand(rec, onClick = { RecorderLaunch.open() })
            Box(Modifier.weight(1f).fillMaxWidth()) { body() }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().background(Wb.colors.surface)) {
            Column(
                Modifier.fillMaxHeight().width(80.dp).background(Wb.colors.card).statusBarsPadding().navigationBarsPadding().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Tab.entries.forEach { t ->
                    NavItem(t, isOn(t), reviewCount, Modifier.fillMaxWidth().clickable { select(t) }.padding(vertical = 10.dp))
                }
            }
            VerticalDivider(color = Wb.colors.border)
            Box(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().navigationBarsPadding()) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
            // 화면 안에서 키보드만큼 올리는 것(빠른 입력 띠 imePadding, DEC-73)은 아래 메뉴(64 + 구분선 1)와 시스템 막대 높이를 빼고 올린다
            Box(
                Modifier.weight(1f).fillMaxWidth().statusBarsPadding()
                    .consumeWindowInsets(WindowInsets.navigationBars.add(WindowInsets(bottom = 65.dp))),
            ) { content() }
            HorizontalDivider(color = Wb.colors.border)
            Row(
                Modifier.fillMaxWidth().background(Wb.colors.card).navigationBarsPadding().height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { t ->
                    NavItem(t, isOn(t), reviewCount, Modifier.weight(1f).clickable { select(t) }.padding(vertical = 6.dp))
                }
            }
        }
    }
}

/**
 * 녹음 중 빨간 띠(DEC-89, 통화 중 띠처럼): '● 녹음 중 12:34'·'일시정지 12:34' + '녹음 화면 ›'. 누르면 녹음 화면.
 * 마이크가 켜져 있다는 것을 어느 화면에서든 보이게 해서, 녹음을 켜 둔 채 잊지 않게 한다
 */
@Composable
private fun RecordingBand(rec: RecState, onClick: () -> Unit) {
    val paused = rec.status == RecStatus.PAUSED
    Row(
        Modifier.fillMaxWidth().background(if (paused) Wb.colors.warn else Wb.colors.crit)
            .clickable(onClickLabel = "녹음 화면 열기", onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (paused) "일시정지 " else "● 녹음 중 ") + formatClock(rec.elapsedMs),
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
        )
        Text("녹음 화면 ›", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 메뉴 한 칸: 글래스 아이콘(검토함은 대기 건수 배지) + 이름. 아래 메뉴와 왼쪽 세로 메뉴가 같이 쓴다 */
@Composable
private fun NavItem(t: Tab, on: Boolean, reviewCount: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            GlassIcon(t.glyph, if (on) t.tint else GlassTint.Gray, size = 30.dp)
            if (t == Tab.REVIEW && reviewCount > 0) {
                Text(
                    if (reviewCount > 99) "99+" else reviewCount.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp)
                        .background(Wb.colors.crit, PillShape).padding(horizontal = 5.dp),
                )
            }
        }
        Text(
            t.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (on) Wb.colors.primary else Wb.colors.muted,
        )
    }
}
