package com.workbrain.app.review

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.LinkSuggestion
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.RecordClass
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.ParaGlyph
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.SegmentPills
import com.workbrain.app.ui.SmallButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.dashedPill

/** AI 연결 제안 하나와 그 프로젝트(보관되지 않은 것만 화면에 올린다) */
data class ProjectSuggestion(val suggestion: LinkSuggestion, val project: ProjectRow)

/** 제안 중 화면에 보일 것: 아직 잇지 않은 진행 중 프로젝트에 대한 제안만(보관된 곳은 연결할 수 없음) */
fun visibleSuggestions(suggestions: List<LinkSuggestion>, projects: List<ProjectRow>, linkedIds: Collection<String>): List<ProjectSuggestion> =
    suggestions.filter { it.projectId !in linkedIds }.mapNotNull { s ->
        projects.firstOrNull { it.id == s.projectId && !it.archived }?.let { ProjectSuggestion(s, it) }
    }

/**
 * 기록 상세 머리의 프로젝트 칩 줄(목업 S05 머리 '📁 자동차 사고접수 개편', DEC-69).
 * 분류가 개인·불확실이면 맨 앞 칩, 연결된 프로젝트마다 칩, AI 제안은 점선 칩(DEC-75), 끝에 '+ 프로젝트'.
 * 어느 칩을 눌러도 '관련 프로젝트' 창이 열린다(사용자 선택: 탭 대신 창)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordProjectChips(
    linked: List<ProjectRow>,
    suggested: List<ProjectSuggestion>,
    recordClass: RecordClass?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (recordClass == RecordClass.PERSONAL || recordClass == RecordClass.UNCERTAIN) {
            WbChip(
                recordClass.label,
                if (recordClass == RecordClass.UNCERTAIN) Tone.Warn else Tone.Mute,
                Modifier.clip(PillShape).clickable(onClickLabel = "분류 고치기", onClick = onOpen),
            )
        }
        linked.forEach { p ->
            Row(
                Modifier.clip(PillShape).border(1.dp, Wb.colors.border, PillShape)
                    .clickable(onClickLabel = "연결된 프로젝트 보기", onClick = onOpen)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ParaGlyph.project(), contentDescription = null, tint = if (p.archived) Wb.colors.muted else ParaGlyph.tint(PlaceType.PROJECT), modifier = Modifier.size(14.dp))
                Text(
                    p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (p.archived) Wb.colors.muted else Wb.colors.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).widthIn(max = 220.dp),
                )
            }
        }
        suggested.forEach { SuggestionChip(it.project.name, onOpen) }
        Text(
            if (linked.isEmpty()) "+ 프로젝트 연결" else "+ 프로젝트",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.link,
            modifier = Modifier.clip(PillShape).clickable(onClickLabel = "프로젝트 연결", onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

/** AI 연결 제안 칩: 점선 알약 '📁 이름 · 제안'(확정 전이라 연결된 칩과 모양을 다르게, DEC-75). 기록 상세 머리·검토함 묶음 줄 공용 */
@Composable
fun SuggestionChip(name: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(PillShape).dashedPill(Wb.colors.link)
            .clickable(onClickLabel = "AI 연결 제안 보기", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ParaGlyph.project(), contentDescription = null, tint = ParaGlyph.tint(PlaceType.PROJECT), modifier = Modifier.size(14.dp))
        Text(
            name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp).widthIn(max = 200.dp),
        )
        Text(" · 제안", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.link)
    }
}

/**
 * '관련 프로젝트' 창(목업 S05 '관련 프로젝트' 탭 내용): 분류 [업무|개인|불확실], AI 제안([연결]·[아니요]),
 * 연결된 프로젝트마다 [연결 해제], 아래 [+ 다른 프로젝트에 연결].
 * 하나의 기록을 여러 프로젝트에 나눠 연결할 수 있다(목업 안내). 아카이브된 프로젝트에는 새로 잇지 않는다(풀기는 됨).
 * busy 동안 버튼을 막아 두 번 눌러 같은 요청이 겹치지 않게 한다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordProjectsSheet(
    recordClass: RecordClass?,
    suggestions: List<ProjectSuggestion>,
    linked: List<ProjectRow>,
    candidates: List<ProjectRow>,
    busy: Boolean,
    onClassify: (RecordClass) -> Unit,
    onLink: (ProjectRow) -> Unit,
    onUnlink: (ProjectRow) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss) {
        Text("관련 프로젝트", style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink)
        SectionLabel("분류")
        SegmentPills(
            RecordClass.entries.map { it.value to it.label },
            value = recordClass?.value.orEmpty(),
            compact = true,
        ) { v -> if (!busy) RecordClass.of(v)?.let(onClassify) }
        if (recordClass == RecordClass.PERSONAL) {
            Text("개인 기록에는 AI가 프로젝트 연결을 제안하지 않아요.", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
        }
        if (suggestions.isNotEmpty()) {
            SectionLabel("AI 제안")
            SuggestionList(suggestions, busy, onAccept = onLink, onReject = onUnlink)
        }
        SectionLabel("연결된 프로젝트")
        if (linked.isEmpty()) {
            Text("아직 연결된 프로젝트가 없어요.", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted)
        } else {
            Column(Modifier.fillMaxWidth().border(1.dp, Wb.colors.border, ControlShape)) {
                linked.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = Wb.colors.border)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ParaGlyph.project(), contentDescription = null, tint = ParaGlyph.tint(PlaceType.PROJECT), modifier = Modifier.size(16.dp))
                        Text(
                            p.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        if (p.archived) WbChip("아카이브", Tone.Mute) else WbChip("사용자 확인됨", Tone.Good)
                        TextButton(onClick = { onUnlink(p) }, enabled = !busy) { Text("연결 해제", color = Wb.colors.body) }
                    }
                }
            }
        }
        AddProjectButton(candidates, busy, onLink)
        Text(
            "하나의 기록을 여러 프로젝트에 나눠 연결할 수 있어요. 연결한 기록은 프로젝트 화면의 트리와 '관련 기록'에 보여요. " +
                "AI 제안은 [연결]을 눌러야 연결돼요.",
            style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted,
        )
    }
}

/** 검토함 묶음 줄의 제안 칩을 누르면: 그 기록의 AI 제안만 담은 창(DEC-75). 연결하면 '추가할 곳' 처음 값이 따라간다 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionSheet(
    recordTitle: String,
    suggestions: List<ProjectSuggestion>,
    busy: Boolean,
    onAccept: (ProjectRow) -> Unit,
    onReject: (ProjectRow) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss) {
        Text("프로젝트 연결 제안", style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink)
        Text(recordTitle, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (suggestions.isEmpty()) {
            Text("남은 제안이 없어요.", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted)
        } else {
            SuggestionList(suggestions, busy, onAccept, onReject)
        }
        Text(
            "연결하면 이 기록이 프로젝트 화면의 트리와 '관련 기록'에 들어가고, 할 일 후보의 '추가할 곳'도 그 프로젝트가 돼요.",
            style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x6609141E),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** 목업 h2: 작은 회색 굵은 칸 이름 */
@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = Wb.colors.muted, modifier = Modifier.padding(top = 8.dp))
}

/**
 * AI 제안 줄들: 프로젝트 · 'AI 제안' 칩, 왜 이 프로젝트인지 한 줄(본문 색), 확신이 높지 않으면 '확인 필요 · 이유',
 * 가장 분명한 근거 문장 하나(작게), [아니요] [연결]. 11/09 이전 제안은 이유가 없어 근거 문장만 보인다
 */
@Composable
private fun SuggestionList(suggestions: List<ProjectSuggestion>, busy: Boolean, onAccept: (ProjectRow) -> Unit, onReject: (ProjectRow) -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, Wb.colors.border, ControlShape)) {
        suggestions.forEachIndexed { i, (s, p) ->
            if (i > 0) HorizontalDivider(color = Wb.colors.border)
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ParaGlyph.project(), contentDescription = null, tint = ParaGlyph.tint(PlaceType.PROJECT), modifier = Modifier.size(16.dp))
                    Text(
                        p.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    WbChip("AI 제안", Tone.Info)
                }
                s.reason?.let { Text(it, fontSize = 14.sp, color = Wb.colors.body) }
                s.note?.let { Text(it, fontSize = 12.sp, color = Wb.colors.warn) }
                s.quote?.let { Text("근거 “$it”", fontSize = 12.sp, color = Wb.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    SmallButton("아니요", enabled = !busy) { onReject(p) }
                    SmallButton("연결", primary = true, enabled = !busy) { onAccept(p) }
                }
            }
        }
    }
}

/** [+ 다른 프로젝트에 연결]: 아직 잇지 않은 진행 중 프로젝트를 고른다(이름순) */
@Composable
private fun AddProjectButton(candidates: List<ProjectRow>, busy: Boolean, onLink: (ProjectRow) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton("+ 다른 프로젝트에 연결", enabled = !busy, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)) {
            if (candidates.isEmpty()) {
                Text(
                    "연결할 프로젝트가 없어요. 프로젝트 탭의 '1. 프로젝트' (+)로 만들 수 있어요.",
                    style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            candidates.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(ParaGlyph.project(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { open = false; onLink(p) },
                )
            }
        }
    }
}
