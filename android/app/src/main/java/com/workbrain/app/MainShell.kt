package com.workbrain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.workbrain.app.record.SharedAudio
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Server
import com.workbrain.app.more.MoreScreen
import com.workbrain.app.projects.ProjectsScreen
import com.workbrain.app.record.HomeScreen
import com.workbrain.app.review.RecordDetailScreen
import com.workbrain.app.review.ReviewScreen
import com.workbrain.app.today.TodayScreen
import com.workbrain.app.ui.GlassIcon
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PanePlaceholder
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.isWideWindow
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.Wb

// 목업 하단 메뉴와 같은 순서·이름. 아이콘은 글래스(고른 메뉴만 색, 나머지는 회색 유리, DEC-32)
private enum class Tab(val label: String, val glyph: ImageVector, val tint: GlassTint) {
    TODAY("오늘", WbIcons.Home, GlassTint.Blue),
    PROJECTS("프로젝트", WbIcons.Folder, GlassTint.Purple),
    RECORD("녹음", WbIcons.Mic, GlassTint.Red),
    REVIEW("검토함", WbIcons.Review, GlassTint.Green),
    MORE("더보기", WbIcons.Menu, GlassTint.Orange),
}

/** 로그인 뒤 화면 틀: 메뉴 5개(접힘 = 아래, 펼침 = 왼쪽 세로)와, 녹음·검토함에서 연 기록 상세. 상세는 뒤로 가기로 닫는다 */
@Composable
fun MainShell() {
    // 휴대폰 앱의 첫 화면은 녹음(바로 기록하는 곳)
    var tab by rememberSaveable { mutableStateOf(Tab.RECORD) }
    // 열린 기록: 서버 기록 ID와 제목. null이면 메뉴 화면
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailTitle by rememberSaveable { mutableStateOf("") }
    var reviewCount by rememberSaveable { mutableIntStateOf(0) }

    // 검토함 배지: 메뉴를 바꾸거나 상세를 닫을 때 다시 센다
    LaunchedEffect(tab, detailId) {
        runCatching { Server.pendingProposals().size }.onSuccess { reviewCount = it }
    }
    val open: (String, String) -> Unit = { id, title -> detailTitle = title; detailId = id }
    // 앱을 열 때: 결과가 아직 없는 녹음은 백그라운드 처리를 다시 건다(강제 중지 뒤에도 이어 가기, DEC-37)
    val context = LocalContext.current
    LaunchedEffect(Unit) { UploadManager.ensureRunning(context) }
    // 다른 앱에서 음성을 공유하면 녹음 화면으로 와서 가져오기를 이어 간다(DEC-40)
    val shared by SharedAudio.pending.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared != null) { tab = Tab.RECORD; detailId = null } }

    // 펼친 화면(두 칸, DEC-47): 메뉴는 왼쪽 세로, 검토함에서 연 기록은 전체 화면 대신 오른쪽 칸에 연다.
    // 열린 기록은 detailId 하나로 들고 있어서, 접으면 같은 기록이 한 칸 전체 화면으로 이어진다
    val wide = isWideWindow()
    val detailInPane = wide && tab == Tab.REVIEW
    // 두 칸에서 한쪽이 추가·버리기를 하면 다른 쪽을 다시 읽게 하는 번호
    var inboxVersion by remember { mutableIntStateOf(0) }
    var detailVersion by remember { mutableIntStateOf(0) }

    val body: @Composable () -> Unit = {
        val openId = detailId
        when {
            openId != null && !detailInPane -> RecordDetailScreen(openId, detailTitle, onBack = { detailId = null })
            else -> when (tab) {
                Tab.TODAY -> TodayScreen()
                Tab.PROJECTS -> ProjectsScreen()
                Tab.RECORD -> HomeScreen(onOpenRecord = open)
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
                                RecordDetailScreen(openId, detailTitle, onBack = null, onChanged = { inboxVersion++ })
                            }
                        }
                    },
                )
                Tab.MORE -> MoreScreen()
            }
        }
    }
    val select: (Tab) -> Unit = { tab = it; detailId = null }
    // 오른쪽 칸에 기록이 열려 있어도 검토함 메뉴는 고른 상태로 보인다
    val isOn: (Tab) -> Boolean = { t -> tab == t && (detailId == null || detailInPane) }

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
            Box(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().navigationBarsPadding()) { body() }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) { body() }
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
