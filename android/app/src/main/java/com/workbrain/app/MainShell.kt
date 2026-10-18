package com.workbrain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.workbrain.app.ui.PillShape
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

/** 로그인 뒤 화면 틀: 아래 메뉴 5개와, 녹음·검토함에서 연 기록 상세. 상세는 뒤로 가기로 닫는다 */
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

    Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
            val openId = detailId
            when {
                openId != null -> RecordDetailScreen(openId, detailTitle, onBack = { detailId = null })
                else -> when (tab) {
                    Tab.TODAY -> TodayScreen()
                    Tab.PROJECTS -> ProjectsScreen()
                    Tab.RECORD -> HomeScreen(onOpenRecord = open)
                    Tab.REVIEW -> ReviewScreen(onOpenRecord = open, onCountChanged = { reviewCount = it })
                    Tab.MORE -> MoreScreen()
                }
            }
        }
        HorizontalDivider(color = Wb.colors.border)
        Row(
            Modifier.fillMaxWidth().background(Wb.colors.card).navigationBarsPadding().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val on = tab == t && detailId == null
                Column(
                    Modifier.weight(1f).clickable { tab = t; detailId = null }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
        }
    }
}
