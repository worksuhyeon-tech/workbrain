package com.workbrain.app.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.Para
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.SearchRow
import com.workbrain.app.data.Server
import com.workbrain.app.record.RecordingStore
import com.workbrain.app.tasks.placeIcon
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.DarkSeg
import com.workbrain.app.ui.MockCard
import com.workbrain.app.ui.Note
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.PanePlaceholder
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.SegItem
import com.workbrain.app.ui.SmallButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.TwoPane
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant

/*
 * 검색 화면(FR-13, DEC-98, 목업 S08 workbrain-search.html, Design §3.14).
 * 오늘·프로젝트·기록 탭 오른쪽 위 🔍로 MainShell이 탭 위에 연다. 두 글자 이상 적고 0.4초 멈추면 저절로 찾는다.
 * 결과는 종류를 섞어 최근 것부터, 승인 전 회의록은 '초안' 칩. 검토 대기 할 일 후보만 아래에 접어 둔다. 펼친 화면은 두 칸(왼쪽 결과, 오른쪽 누른 기록 상세)
 */

/** 기다렸다 찾기(DEC-98: 입력하면 바로). 적는 동안 요청을 줄이려고 멈춘 뒤에만 부른다 */
private const val TYPE_PAUSE_MS = 400L

/**
 * 검색 화면 상태. MainShell이 들고 있어서 결과에서 기록 상세를 열었다 돌아와도 검색어·필터·결과가 그대로다.
 * 검색어·필터는 접기·펼치기·회전에도 남고(NFR-05), 결과는 화면에 다시 들어올 때 새로 읽는다
 */
@Stable
class SearchState(query: String, period: SearchPeriod, kind: SearchKind, place: Place?, draftsOpen: Boolean, focusPending: Boolean) {
    var query by mutableStateOf(query)
    var period by mutableStateOf(period)
    var kind by mutableStateOf(kind)
    /** 프로젝트·반복실행으로 거르기. null = 모든 프로젝트 */
    var place by mutableStateOf(place)
    var draftsOpen by mutableStateOf(draftsOpen)
    /** 처음 열 때만 키보드를 띄운다(기록 상세에서 돌아올 때는 안 띄움) */
    var focusPending by mutableStateOf(focusPending)
    /** 마지막으로 받은 결과(돌아왔을 때 다시 읽는 동안 비지 않게). 저장하지 않는다 */
    var rows by mutableStateOf<List<SearchRow>?>(null)

    /** 새로 열기: 프로젝트·반복실행 상세에서 열었으면 그곳으로 미리 거른다(Design §3.14) */
    fun reset(scope: Place?) {
        query = ""; period = SearchPeriod.ALL; kind = SearchKind.ALL; place = scope; draftsOpen = false; focusPending = true; rows = null
    }

    companion object {
        fun empty() = SearchState("", SearchPeriod.ALL, SearchKind.ALL, null, draftsOpen = false, focusPending = true)

        val Saver = listSaver<SearchState, String>(
            save = {
                listOf(it.query, it.period.code, it.kind.code, it.place?.type?.name.orEmpty(), it.place?.id.orEmpty(), it.place?.name.orEmpty(),
                    if (it.draftsOpen) "1" else "0", if (it.focusPending) "1" else "0")
            },
            restore = { v ->
                val place = v[3].takeIf { it.isNotEmpty() }?.let { Place(PlaceType.valueOf(it), v[4], v[5]) }
                SearchState(v[0], SearchPeriod.of(v[1]), SearchKind.of(v[2]), place, v[6] == "1", v[7] == "1")
            },
        )
    }
}

/** 탭 제목 줄 오른쪽 위 🔍(⋮ 옆, 기록 탭은 휴지통 옆) */
@Composable
fun SearchButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(WbIcons.Search, contentDescription = "검색", tint = Wb.colors.ink)
    }
}

/**
 * backLabel: 열었던 탭 이름('← 오늘'). onOpen: 결과를 눌렀을 때(기록 상세 또는 프로젝트 탭 항목).
 * selectedRecordId: 두 칸에서 오른쪽에 열린 기록(그 결과 줄을 표시). detailPane이 있으면 두 칸
 */
@Composable
fun SearchScreen(
    state: SearchState,
    backLabel: String,
    onClose: () -> Unit,
    onOpen: (SearchTarget) -> Unit,
    selectedRecordId: String? = null,
    detailPane: (@Composable () -> Unit)? = null,
) {
    BackHandler(onBack = onClose)
    val list: @Composable () -> Unit = { SearchList(state, backLabel, onClose, onOpen, selectedRecordId, wide = detailPane != null) }
    if (detailPane != null) TwoPane(leftWeight = 0.45f, left = list, right = detailPane) else list()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchList(
    state: SearchState,
    backLabel: String,
    onClose: () -> Unit,
    onOpen: (SearchTarget) -> Unit,
    selectedRecordId: String?,
    wide: Boolean,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // 키보드 [검색]·[다시 시도]를 누르면 기다리지 않고 바로 다시 찾는다(handledRun과 다르면 누른 것)
    var runNow by remember { mutableIntStateOf(0) }
    var handledRun by remember { mutableIntStateOf(0) }
    var trashed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var picking by remember { mutableStateOf(false) }
    val q = cleanQuery(state.query)

    LaunchedEffect(Unit) {
        if (state.focusPending) {
            state.focusPending = false
            focus.requestFocus()
        }
    }
    // 검색어·필터가 바뀌면 0.4초 기다렸다 찾는다. 그사이 또 바뀌면 이 작업이 취소되고 새로 기다린다(앞 요청 취소)
    LaunchedEffect(q, state.period, state.kind, state.place, runNow) {
        failed = false
        if (q.length < SEARCH_MIN) { state.rows = null; loading = false; return@LaunchedEffect }
        val pressed = runNow != handledRun
        handledRun = runNow
        if (!pressed) delay(TYPE_PAUSE_MS)
        loading = true
        trashed = RecordingStore.listTrash(context).mapNotNull { it.recordId }.toSet()
        try {
            state.rows = Server.search(q, state.place, state.period.since(Instant.now()), state.kind.serverKinds)
        } catch (e: CancellationException) {
            // 검색어가 또 바뀌어 취소된 것: 실패가 아니다(다음 작업이 이어 찾는다)
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WorkBrainSearch", "search failed: ${e.javaClass.simpleName}")
            failed = true
        }
        loading = false
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Wb.colors.surface),
        contentPadding = PaddingValues(start = if (wide) 24.dp else 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        item {
            TextButton(onClick = onClose, modifier = Modifier.padding(top = 4.dp)) { Text("← $backLabel", color = Wb.colors.link) }
            PageHeader("검색", "그때 뭐라고 했더라", Modifier.padding(start = 4.dp))
            SearchBox(state, focus, onSearch = { keyboard?.hide(); runNow++ })
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaceChip(state.place, onClick = { picking = true })
                DarkSeg(SearchPeriod.entries.map { SegItem(it.code, it.label) }, state.period.code) { state.period = SearchPeriod.of(it) }
                DarkSeg(SearchKind.entries.map { SegItem(it.code, it.label) }, state.kind.code) { state.kind = SearchKind.of(it) }
            }
        }
        val rows = state.rows
        when {
            q.isEmpty() -> item { Note("할 일·회의록·자료에서 글자 그대로 찾아요. 전사(받아쓴 원문)는 찾지 않아요.") }
            q.length < SEARCH_MIN -> item { Note("두 글자 이상 적어 주세요") }
            failed -> item {
                Banner("검색하지 못했어요. 인터넷 연결을 확인해 주세요.", Tone.Crit)
                TextButton(onClick = { runNow++ }) { Text("다시 시도", color = Wb.colors.link) }
            }
            rows == null -> item { Note("찾는 중…") }
            else -> {
                val results = splitResults(rows, trashed)
                val relax = relaxOptions(state.period, state.place != null, state.draftsOpen, results.candidates.size)
                if (results.shown.isEmpty() && !(state.draftsOpen && results.candidates.isNotEmpty())) {
                    item { NoResults(relax, results.candidates.size) { r -> applyRelax(state, r) } }
                } else {
                    item {
                        ResultsHead(
                            buildString {
                                append("찾은 결과 ${results.shown.size}건")
                                val drafts = results.shown.count { it.draft }
                                if (drafts > 0) append(" · 그중 초안 ${drafts}건")
                                if (loading) append(" · 다시 찾는 중…")
                            },
                        )
                        ResultCard(results.shown, q, selectedRecordId, emptyText = "할 일 후보 말고는 없어요", onOpen = onOpen)
                    }
                }
                if (results.candidates.isNotEmpty()) item {
                    CandidateSection(results.candidates, q, state.draftsOpen, selectedRecordId, onToggle = { state.draftsOpen = !state.draftsOpen }, onOpen = onOpen)
                }
            }
        }
        item {
            Note(
                "지금은 키워드 검색이에요. 뜻으로 찾기(의미 검색)는 다음 단계(P2)예요. 휴지통에 넣은 기록과 다른 사람의 자료는 결과에 나오지 않아요.",
                Modifier.padding(top = 24.dp),
            )
        }
    }

    if (picking) {
        SearchPlaceSheet(
            onDismiss = { picking = false },
            onPick = { p -> picking = false; state.place = p },
        )
    }
}

private fun applyRelax(state: SearchState, r: Relax) {
    when (r) {
        Relax.PERIOD -> state.period = SearchPeriod.ALL
        Relax.PLACE -> state.place = null
        Relax.CANDIDATES -> state.draftsOpen = true
    }
}

/** 목업 .s-box: 큰 검색 칸 + 지우기 */
@Composable
private fun SearchBox(state: SearchState, focus: FocusRequester, onSearch: () -> Unit) {
    BasicTextField(
        value = state.query,
        onValueChange = { state.query = it.take(SEARCH_MAX + 20) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, color = Wb.colors.ink),
        cursorBrush = SolidColor(Wb.colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth().height(46.dp).focusRequester(focus),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().background(Wb.colors.card, ControlShape)
                    .border(1.dp, Wb.colors.borderStrong, ControlShape).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(WbIcons.Search, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.query.isEmpty()) {
                        Text("키워드로 찾기 (예: 개발 공수)", fontSize = 16.sp, color = Wb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { state.query = "" }) {
                        Icon(WbIcons.Close, contentDescription = "검색어 지우기", tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

/** 프로젝트 거르기 칩(목업 select '모든 프로젝트') */
@Composable
private fun PlaceChip(place: Place?, onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp).background(Wb.colors.card, PillShape).border(1.dp, Wb.colors.borderStrong, PillShape)
            .clickable(onClickLabel = "찾을 곳 고르기", onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(placeIcon(place?.type ?: PlaceType.PROJECT), contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(16.dp))
        Text(
            place?.name ?: "모든 프로젝트", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.body,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
        )
        Icon(WbIcons.ChevronDown, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.padding(start = 4.dp).size(14.dp))
    }
}

/** 목업 .res-head: '확정된 자료 N건' */
@Composable
private fun ResultsHead(text: String) {
    Text(text, fontSize = 13.sp, color = Wb.colors.muted, modifier = Modifier.padding(bottom = 8.dp))
}

/** 결과 묶음 카드(목업 .card 안 .res 줄들) */
@Composable
private fun ResultCard(rows: List<SearchRow>, q: String, selectedRecordId: String?, emptyText: String, onOpen: (SearchTarget) -> Unit) {
    MockCard {
        if (rows.isEmpty()) {
            Text(emptyText, fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = Wb.colors.border)
            ResultRow(row, q, selected = row.recordId != null && row.recordId == selectedRecordId && row.kind != KIND_RESOURCE, onOpen = onOpen)
        }
    }
}

/**
 * 목업 .res: 종류 칩 · 제목(강조) · 완료·보관됨 칩 / 조각(강조) / 📁 곳 · 날짜 · 초안 · 원본 음성 만료.
 * 제목은 칩 바로 뒤에서 남은 폭 안에 줄바꿈한다(11/12 사용자: 긴 할 일 제목이 칩 다음 줄로 떨어져 칩 혼자 한 줄을 차지했음).
 * '초안'은 눈에 띄는 칩 대신 날짜 뒤 회색 작은 글씨(같은 날 사용자 요청)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultRow(row: SearchRow, q: String, selected: Boolean, onOpen: (SearchTarget) -> Unit) {
    val target = targetOf(row)
    val highlight = Wb.colors.highlightText
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) Wb.colors.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = target != null) { target?.let(onOpen) }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            WbChip(kindLabel(row.kind), kindTone(row.kind), Modifier.padding(top = 1.dp))
            Text(
                highlighted(row.title ?: if (row.kind == KIND_RECORD) "제목 없는 기록" else "제목 없음", q, highlight),
                fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = if (row.done) Wb.colors.muted else Wb.colors.ink,
                textDecoration = if (row.done) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
            )
            if (row.done) WbChip("완료", Tone.Mute, Modifier.padding(start = 8.dp, top = 1.dp))
            if (row.archived) WbChip("보관됨", Tone.Mute, Modifier.padding(start = 8.dp, top = 1.dp))
        }
        row.snippet?.let { s ->
            Text(highlighted(s, q, highlight), fontSize = 14.sp, lineHeight = 22.sp, color = Wb.colors.body, modifier = Modifier.padding(top = 4.dp))
        }
        FlowRow(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val place = row.placeName ?: if (row.kind == KIND_TASK) "정리 안 됨" else null
            if (place != null) {
                Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                    Icon(WbIcons.Folder, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(13.dp))
                    Text(place, fontSize = 12.sp, color = Wb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
                }
            }
            val from = if (row.kind == KIND_PROPOSAL) row.recordTitle?.let { "회의 ‘$it’" } else null
            listOfNotNull(metaDate(row), "초안".takeIf { row.draft }, from).forEach { part ->
                Text("· $part", fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.align(Alignment.CenterVertically))
            }
            if (row.audioExpired) WbChip("원본 음성 만료", Tone.Mute, Modifier.align(Alignment.CenterVertically))
        }
    }
}

private fun highlighted(text: String, q: String, background: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    matchRanges(text, q).forEach { r ->
        append(text.substring(at, r.first))
        pushStyle(SpanStyle(background = background))
        append(text.substring(r.first, r.last + 1))
        pop()
        at = r.last + 1
    }
    append(text.substring(at))
}

/** 맨 아래 '검토 대기 할 일 후보 N건'(처음엔 접힘, DEC-98). 펼치면 '아직 확정 사실이 아니에요' 묶음 */
@Composable
private fun CandidateSection(rows: List<SearchRow>, q: String, open: Boolean, selectedRecordId: String?, onToggle: () -> Unit, onOpen: (SearchTarget) -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "후보 접기" else "후보 보기", onClick = onToggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (open) "검토 대기 할 일 후보 ${rows.size}건 · 아직 확정 사실이 아니에요" else "검토 대기 할 일 후보 ${rows.size}건 · 보기",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (open) Wb.colors.muted else Wb.colors.link,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) WbIcons.ChevronDown else WbIcons.ChevronRight, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(16.dp))
        }
        if (open) ResultCard(rows, q, selectedRecordId, emptyText = "", onOpen = onOpen)
    }
}

/** 목업 .empty: '찾는 결과가 없어요' + 걸린 필터를 넓히는 버튼 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoResults(relax: List<Relax>, candidateCount: Int, onRelax: (Relax) -> Unit) {
    MockCard {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("찾는 결과가 없어요", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
            if (relax.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    relax.forEach { r ->
                        SmallButton(if (r == Relax.CANDIDATES) "할 일 후보 ${candidateCount}건도 보기" else r.label) { onRelax(r) }
                    }
                }
            }
        }
    }
}

/** 찾을 곳 고르기: 모든 프로젝트 / 프로젝트 / 반복실행(보관 안 된 곳) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPlaceSheet(onDismiss: () -> Unit, onPick: (Place?) -> Unit) {
    var places by remember { mutableStateOf<List<Place>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { Para.places() }.onSuccess { places = it }.onFailure { failed = true }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x6609141E),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text("어디에서 찾을까요?", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            PlaceRow(null, "모든 프로젝트") { onPick(null) }
            val list = places
            when {
                failed -> Text("목록을 불러오지 못했어요", fontSize = 14.sp, color = Wb.colors.crit, modifier = Modifier.padding(20.dp))
                list == null -> Text("불러오는 중…", fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.padding(20.dp))
                else -> listOf(PlaceType.PROJECT to "1. 프로젝트", PlaceType.AREA to "2. 반복실행").forEach { (type, heading) ->
                    val group = list.filter { it.type == type }
                    if (group.isNotEmpty()) {
                        HorizontalDivider(color = Wb.colors.border, modifier = Modifier.padding(top = 4.dp))
                        Text(heading, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        group.forEach { p -> PlaceRow(p.type, p.name) { onPick(p) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(type: PlaceType?, name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (type == null) WbIcons.Search else placeIcon(type), contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
        Text(name, fontSize = 15.sp, color = Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}

/** 펼친 화면 오른쪽 칸이 비었을 때 */
@Composable
fun SearchPanePlaceholder() {
    PanePlaceholder("결과를 누르면 여기에 열려요")
}
