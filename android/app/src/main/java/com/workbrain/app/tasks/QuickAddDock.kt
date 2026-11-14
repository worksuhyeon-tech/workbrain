package com.workbrain.app.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Place
import com.workbrain.app.data.TaskEdit
import com.workbrain.app.ui.CardShape
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons

/*
 * 아래 떠 있는 빠른 입력 띠(DEC-73, 갤럭시 캘린더 참고): '오늘'·'모든 할 일' 화면 아래 한 줄 알약.
 * 스크롤해도 그 자리이고, 입력할 때만 키보드 위에 미리보기·단축어 설명·'자세히 ›' 카드가 뜬다.
 * 해석·저장은 카드 안 입력 줄과 같은 rememberQuickAdd. 화면은 이 띠를 Box 아래 가운데에 두고, 목록 끝에 여백을 둔다
 */

/** 띠가 차지하는 높이(알약 52 + 아래 여백 12). 화면 목록·알림이 이만큼 비켜 선다 */
val QUICK_DOCK_SPACE = 64.dp

/**
 * places: 보관 안 된 프로젝트·반복실행. draftKey: 입력 줄 이름('today'·'all', 적던 글·말로 넣기 결과를 화면 밖에 두는 열쇠).
 * noDueMeansToday: 오늘 탭(기한을 안 적으면 오늘 마감). onAdded: 넣은 뒤(바로 넣기·새 할 일 서랍 둘 다) — 화면은 다시 읽고 알림을 띄운다
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddDock(
    places: List<Place>,
    draftKey: String,
    modifier: Modifier = Modifier,
    noDueMeansToday: Boolean = false,
    onAdded: (QuickTask, String) -> Unit,
) {
    val quick = rememberQuickAdd(places, fixedPlace = null, noDueMeansToday, draftKey, onAdded)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    // 키보드를 내리면(뒤로 가기) 초점이 남아 있어도 카드를 접는다. 적던 글은 띠에 남는다
    val typing = focused && WindowInsets.isImeVisible
    var detail by remember { mutableStateOf<TaskEdit?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }

    val openInput: () -> Unit = {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val openDetail: () -> Unit = {
        detailError = null
        detail = quick.parsed.toEdit()
        focusManager.clearFocus()
    }

    Column(
        // 키보드가 뜨면 그 바로 위로 올라간다(아래 메뉴만큼은 MainShell이 빼 준다)
        modifier.imePadding().padding(horizontal = 16.dp).padding(bottom = 12.dp).widthIn(max = 640.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 말로 넣을 때(듣는 중·다 들은 뒤·안내)는 키보드 없이 카드만 띄운다(DEC-89, 사용자 선택 11/11)
        val voiceCard = quick.listening || quick.voiceMessage != null || quick.fromVoice
        if (typing || quick.error != null || voiceCard) {
            TypingCard(quick, showHelp = typing, showDetail = typing || quick.fromVoice, onDetail = openDetail)
        }
        Surface(
            onClick = openInput,
            shape = PillShape,
            color = Wb.colors.card,
            border = BorderStroke(1.dp, Wb.colors.border),
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Row(Modifier.padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = quick.text,
                    onValueChange = quick.onText,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = Wb.colors.ink),
                    cursorBrush = SolidColor(Wb.colors.primary),
                    // 넣은 뒤에도 키보드를 닫지 않아 이어서 적을 수 있다
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { quick.add() }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (quick.text.isEmpty()) {
                                Text(QUICK_PLACEHOLDER, fontSize = 15.sp, color = Wb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    },
                )
                MicButton(quick, recordProject = null)
                if (quick.text.isBlank()) PlusButton(openInput) else AddButton(quick.saving, quick.add)
            }
        }
    }

    detail?.let { e ->
        // 새 할 일 서랍에도 '캘린더' 줄(FR-24a). 빠른 입력에서 [캘린더에도]를 켰으면 켜진 채로 연다
        val calendarState by CalendarStore.state.collectAsStateWithLifecycle()
        TaskEditSheet(
            e, places, currentPlaceName = null, error = detailError, saving = quick.saving,
            onDismiss = { detail = null }, isNew = true,
            calendar = calendarFieldOf(calendarState, null),
            calendarStartOn = quick.calendarShown && quick.calendarOn,
        ) { edited, cal -> quick.addEdited(edited, cal == CalendarIntent.ADD, { detail = null }, { detailError = it }) }
    }
}

/**
 * 입력 중 키보드 위 카드: 미리보기 칩 · 말로 넣기 상태 · 단축어 설명 · 저장 실패 · '자세히 ›'.
 * showHelp: 단축어 설명(키보드로 적을 때만). showDetail: '자세히 ›'(적을 때와 말로 넣은 뒤). 둘 다 false면 실패 띠만(키보드를 내린 뒤)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypingCard(quick: QuickAdd, showHelp: Boolean, showDetail: Boolean, onDetail: () -> Unit) {
    Surface(
        shape = CardShape,
        color = Wb.colors.card,
        border = BorderStroke(1.dp, Wb.colors.border),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
            if (quick.text.isNotBlank()) {
                // 적는 동안 해석 결과(화면 읽기 프로그램도 바뀔 때마다 읽는다)
                FlowRow(
                    Modifier.fillMaxWidth().padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { QuickPreview(quick.parsed, quick.shownDay, quick.pickPlace); CalendarChip(quick) }
            }
            VoiceStatus(quick, Modifier.padding(top = if (quick.text.isNotBlank()) 6.dp else 0.dp, end = 8.dp))
            if (showHelp) Box(Modifier.padding(end = 8.dp)) { QuickHint() }
            quick.error?.let { Text(it, fontSize = 12.sp, color = Wb.colors.crit, modifier = Modifier.padding(top = 6.dp, end = 8.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (showDetail) {
                    // 해석한 값으로 채운 새 할 일 서랍(담당·기한·우선순위를 직접 고를 때)
                    Box(
                        Modifier.clip(PillShape).clickable(onClickLabel = "새 할 일 서랍 열기", onClick = onDetail)
                            .heightIn(min = 40.dp).padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("자세히 ›", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.link) }
                } else {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** 비었을 때 오른쪽 둥근 (+): 입력 칸에 초점(갤럭시 캘린더 모양) */
@Composable
private fun PlusButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Wb.colors.primary).clickable(onClickLabel = "빠르게 추가 입력", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(WbIcons.Plus, contentDescription = "빠르게 추가", tint = Wb.colors.onPrimary, modifier = Modifier.size(22.dp)) }
}

/** 글자가 있을 때 [추가]: 해석한 그대로 바로 넣는다(DEC-73 ③) */
@Composable
private fun AddButton(saving: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(PillShape).background(if (saving) Wb.colors.borderStrong else Wb.colors.primary)
            .clickable(enabled = !saving, onClickLabel = "할 일 추가", onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(if (saving) "추가 중…" else "추가", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.onPrimary) }
}
