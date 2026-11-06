package com.workbrain.app.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.AreaRow
import com.workbrain.app.data.LinkedRecord
import com.workbrain.app.data.ParaData
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.ResourceRow
import com.workbrain.app.data.TaskRow
import com.workbrain.app.tasks.UNSORTED
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PanePlaceholder
import com.workbrain.app.ui.SEOUL
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import java.time.LocalDate
import com.workbrain.app.ui.ParaGlyph
import com.workbrain.app.ui.MockCard
import com.workbrain.app.ui.Crumb
import com.workbrain.app.ui.SecH
import com.workbrain.app.ui.Note
import com.workbrain.app.ui.EmptyNote
import com.workbrain.app.ui.LinkChip
import com.workbrain.app.ui.SegItem
import com.workbrain.app.ui.DarkSeg
import com.workbrain.app.ui.SmallButton
import com.workbrain.app.tasks.QuickAddBar
import com.workbrain.app.tasks.TaskLine
import com.workbrain.app.tasks.dueDay
import com.workbrain.app.tasks.quickAddedText

/**
 * 오른쪽 칸(접힌 화면은 전체 화면): 트리에서 고른 것의 상세(목업 S03 #detail, DEC-66).
 * onBack이 null이면 펼친 화면의 오른쪽 칸이라 뒤로 가기가 없다. 보관된 항목은 읽기 전용(복원 버튼만, 서버도 막는다)
 */
@Composable
fun ParaDetail(d: ParaData, key: String, onBack: (() -> Unit)?, onEvent: (ParaEvent) -> Unit) {
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val id = key.substringAfter(':')
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 목업은 좁아지면 머리 버튼이 다음 줄로, 우선순위 칸은 숨는다. 같은 폭에서 '모든 할 일' 위 두 묶음은 칸 여백을 줄인다
        val wide = maxWidth >= 560.dp
        val layout = PaneLayout(wideHead = wide, compactSeg = !wide, showPriority = maxWidth >= 480.dp)
        when {
            key == ALL_TASKS -> AllTasksPane(d, layout, onBack, onEvent)
            key.startsWith("p:") -> d.project(id)?.let { ProjectPane(d, it, layout, onBack, onEvent) } ?: Missing(onBack)
            key.startsWith("a:") -> d.area(id)?.let { AreaPane(d, it, layout, onBack, onEvent) } ?: Missing(onBack)
            key.startsWith("r:") -> d.resource(id)?.let { ResourcePane(d, it, layout, onBack, onEvent) } ?: Missing(onBack)
            else -> Missing(onBack)
        }
    }
}

private data class PaneLayout(val wideHead: Boolean, val compactSeg: Boolean, val showPriority: Boolean)

@Composable
private fun Missing(onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxSize()) {
        onBack?.let { BackLink(it) }
        PanePlaceholder("이 항목을 찾지 못했어요.\n다른 기기에서 바뀌었을 수 있어요.")
    }
}

@Composable
private fun BackLink(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Text("← 프로젝트", color = Wb.colors.link) }
}

/** 상세 공통 틀: 뒤로 가기(접힌 화면) + 세로 목록 */
@Composable
private fun PaneFrame(onBack: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        onBack?.let { BackLink(it) }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (onBack == null) 4.dp else 0.dp, bottom = 96.dp),
            content = content,
        )
    }
}

/** 목업 .d-head: 경로 · (아이콘 이름 칩들) · 오른쪽 버튼. 좁으면 버튼은 다음 줄 오른쪽 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Head(
    crumb: String,
    icon: ImageVector,
    tint: Color,
    title: String,
    wide: Boolean,
    chips: @Composable () -> Unit = {},
    buttons: @Composable () -> Unit = {},
) {
    Column {
        Crumb(crumb)
        val titleRow: @Composable (Modifier) -> Unit = { m ->
            FlowRow(m, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(start = 8.dp))
                }
                Row(Modifier.align(Alignment.CenterVertically)) { chips() }
            }
        }
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                titleRow(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
        } else {
            titleRow(Modifier)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { buttons() }
        }
    }
}

/** 수정·아카이브로 / 복원 버튼(목업: 보관되면 [복원]만, 파란 버튼) */
@Composable
private fun HeadButtons(key: String, archived: Boolean, onEvent: (ParaEvent) -> Unit) {
    if (archived) {
        SmallButton("복원", primary = true) { onEvent(ParaEvent.Archive(key, false)) }
    } else {
        SmallButton("수정") { onEvent(ParaEvent.Edit(key)) }
        SmallButton("아카이브로") { onEvent(ParaEvent.Archive(key, true)) }
    }
}

/** 목업 .arch-banner */
@Composable
private fun ArchBanner(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Wb.colors.surface)
            .border(1.dp, Wb.colors.border, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WbIcons.Archive, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.padding(start = 10.dp))
    }
}

/** 목업 .purpose: 아이콘 + 한 줄(🎯 목적, 🧭 반복실행 설명). muted면 아직 비어 있다는 안내 */
@Composable
private fun PurposeLine(icon: ImageVector, text: String, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = if (muted) Wb.colors.muted else GlassTint.Red.deep, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(text, fontSize = 15.sp, color = if (muted) Wb.colors.muted else Wb.colors.body, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 점선 테두리(목업 .purpose-empty) */
private fun Modifier.dashedBorder(color: Color) = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        cornerRadius = CornerRadius(8.dp.toPx()),
    )
}

/**
 * 목적이 비어 있을 때: 점선 상자 안에서 바로 적고 저장(목업 .purpose-empty).
 * 목업 문구는 'AI가 관련 기록을 더 잘 연결해요'지만, 기록 연결(FR-06)이 아직 없어 약속하지 않는 말로 바꿨다
 */
@Composable
private fun PurposeBox(onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val save = { if (text.isNotBlank()) onSave(text.trim()) }
    Column(Modifier.fillMaxWidth().dashedBorder(Wb.colors.borderStrong).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(WbIcons.Target, contentDescription = null, tint = GlassTint.Red.deep, modifier = Modifier.size(18.dp))
            Text("목적을 적어 두면 관련 기록을 이어 볼 때 기준이 돼요.", fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(500) }, singleLine = true,
                placeholder = { Text("예: 보상 진행 문의를 챗봇으로 응대해 콜 인입을 줄인다", fontSize = 13.sp) },
                textStyle = TextStyle(fontSize = 14.sp, color = Wb.colors.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                shape = ControlShape, modifier = Modifier.weight(1f),
            )
            SmallButton("저장", onClick = save)
        }
    }
}

/** 카드 안 여백 */
private val CardPad = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)

/** '현재 상태 · 확정' 카드. 상태 요약은 기록 연결·제안 승인(FR-09)이 생기면 쌓인다 — 지금은 목업의 빈 상태 */
@Composable
private fun StateCard() {
    MockCard {
        Column(CardPad) {
            SecH("현재 상태") { WbChip("확정", Tone.Mute) }
            EmptyNote("아직 확정된 상태가 없어요", "기록을 연결하고 제안을 승인하면 여기에 쌓여요.")
        }
    }
}

/**
 * '관련 기록' 카드(목업 S03): 이 프로젝트에 연결된 기록, 최근 회의부터. 줄을 누르면 기록 상세(DEC-69).
 * records가 null이면 반복실행이라 연결하지 않는다(사용자 선택: 프로젝트만)
 */
@Composable
private fun RecordsCard(records: List<LinkedRecord>?, onEvent: (ParaEvent) -> Unit) {
    MockCard {
        Column(CardPad) {
            SecH("관련 기록")
            when {
                records == null -> Note("기록은 프로젝트에만 연결해요.", Modifier.padding(top = 10.dp))
                records.isEmpty() -> Note("연결된 기록이 없어요. 기록 화면 머리의 ‘+ 프로젝트 연결’로 이어요.", Modifier.padding(top = 10.dp))
                else -> Column(Modifier.padding(top = 6.dp)) {
                    records.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = Wb.colors.border)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                .clickable(onClickLabel = "기록 열기") { onEvent(ParaEvent.OpenRecord(r.recordId, r.title)) }
                                .heightIn(min = 44.dp).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (r.channel == "text-import") WbIcons.Document else WbIcons.Mic, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(16.dp))
                            Text(
                                r.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            Text(recordMeta(r), fontSize = 12.sp, color = Wb.colors.muted, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 목업 '10/16 · 녹음 42분'. 받아쓴 글은 길이 없이 */
private fun recordMeta(r: LinkedRecord): String = listOfNotNull(
    shortDay(r.at),
    when (r.channel) { "recording" -> "녹음"; "upload" -> "파일"; "call-share" -> "통화"; "text-import" -> "받아쓴 글"; else -> null }
        ?.let { kind -> r.durationSec?.let { "$kind ${maxOf(1, it / 60)}분" } ?: kind },
).joinToString(" · ")

/** 목업 정렬: 안 끝난 것 먼저, 그다음 우선순위(높음 먼저), 기한 이른 순 */
private fun sorted(tasks: List<TaskRow>) = tasks.sortedWith(
    compareBy<TaskRow>({ it.done }, { it.priority ?: 2 }, { dueDay(it) ?: LocalDate.MAX }, { it.title }),
)

/**
 * 할 일 카드(목업 tasksCard): 머리 '할 일 N'(안 끝난 수) + 한 줄 빠른 입력(이곳에 넣음, 보관된 곳은 없음) + 줄 목록.
 * 빠른 입력에서 '#'으로 다른 곳을 고르면 그곳에 들어가고 알림에 이름이 나온다
 */
@Composable
private fun TasksCard(title: String, d: ParaData, place: Place, tasks: List<TaskRow>, readOnly: Boolean, layout: PaneLayout, onEvent: (ParaEvent) -> Unit) {
    MockCard {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            SecH(title) { Text("${tasks.count { !it.done }}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink) }
        }
        if (!readOnly) {
            QuickAddBar(d.places, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), fixedPlace = place) { r ->
                onEvent(ParaEvent.QuickAdded(quickAddedText(r)))
            }
        }
        if (tasks.isEmpty()) {
            EmptyNote(
                "할 일이 없어요",
                if (readOnly) "회의를 올리면 기록 화면에서 할 일 후보를 골라 추가할 수 있어요."
                else "위에 한 줄로 적거나, 회의를 올리면 기록 화면에서 할 일 후보를 골라 추가할 수 있어요.",
            )
        } else {
            TaskLines(sorted(tasks), d, readOnly, layout, onEvent)
        }
    }
}

@Composable
private fun TaskLines(tasks: List<TaskRow>, d: ParaData, readOnly: Boolean, layout: PaneLayout, onEvent: (ParaEvent) -> Unit) {
    tasks.forEachIndexed { i, t ->
        if (i > 0) HorizontalDivider(color = Wb.colors.border)
        TaskLine(
            t, d.sources[t.id], layout.showPriority, readOnly,
            onToggle = { onEvent(ParaEvent.ToggleTask(t)) },
            onOpen = { onEvent(ParaEvent.OpenTask(t)) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

private fun crumbOf(d: ParaData, archived: Boolean, section: ParaSection) =
    "${d.workspace.name} › ${if (archived) ParaSection.ARCHIVE.label else section.label}"

// ── 프로젝트 ──

@Composable
private fun ProjectPane(d: ParaData, p: ProjectRow, layout: PaneLayout, onBack: (() -> Unit)?, onEvent: (ParaEvent) -> Unit) {
    val area = d.area(p.areaId)
    val tasks = d.tasksOf(PlaceType.PROJECT, p.id)
    PaneFrame(onBack) {
        item(key = "head") {
            Head(
                crumbOf(d, p.archived, ParaSection.PROJECTS),
                ParaGlyph.project(), if (p.archived) Wb.colors.muted else ParaGlyph.tint(PlaceType.PROJECT), p.name, layout.wideHead,
                chips = {
                    if (area != null) LinkChip(ParaGlyph.area(), if (area.archived) "${area.name} (아카이브)" else area.name, ParaGlyph.tint(PlaceType.AREA)) {
                        onEvent(ParaEvent.Select(areaKey(area.id)))
                    } else WbChip("반복실행 연결 없음", Tone.Mute)
                },
                buttons = { HeadButtons(projectKey(p.id), p.archived, onEvent) },
            )
        }
        item(key = "purpose") {
            when {
                !p.purpose.isNullOrBlank() -> PurposeLine(WbIcons.Target, p.purpose)
                !p.archived -> PurposeBox { onEvent(ParaEvent.SavePurpose(p, it)) }
            }
        }
        if (p.archived) item(key = "arch") {
            val open = tasks.count { !it.done }
            ArchBanner(
                "끝나서 아카이브한 프로젝트예요. 읽기만 할 수 있고, 복원하면 1. 프로젝트로 돌아가요. ID·이력은 그대로예요." +
                    if (open > 0) " 안 끝난 할 일 ${open}개도 복원하면 ‘오늘’·‘모든 할 일’에 다시 보여요." else "",
            )
        }
        item(key = "state") { StateCard() }
        item(key = "records") { RecordsCard(d.recordsOf(p.id), onEvent) }
        item(key = "tasks") { TasksCard("할 일", d, Place(PlaceType.PROJECT, p.id, p.name), tasks, p.archived, layout, onEvent) }
    }
}

// ── 반복실행 ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AreaPane(d: ParaData, a: AreaRow, layout: PaneLayout, onBack: (() -> Unit)?, onEvent: (ParaEvent) -> Unit) {
    val projects = d.projects.filter { it.areaId == a.id }
    val resources = d.resourcesOf(PlaceType.AREA, a.id)
    PaneFrame(onBack) {
        item(key = "head") {
            Head(
                crumbOf(d, a.archived, ParaSection.AREAS),
                ParaGlyph.area(), if (a.archived) Wb.colors.muted else ParaGlyph.tint(PlaceType.AREA), a.name, layout.wideHead,
                chips = { WbChip("계속 맡는 일", Tone.Mute) },
                buttons = { HeadButtons(areaKey(a.id), a.archived, onEvent) },
            )
        }
        item(key = "desc") {
            if (!a.description.isNullOrBlank()) PurposeLine(WbIcons.Compass, a.description)
            else PurposeLine(WbIcons.Compass, "무엇을 계속 맡는 일인지 적어 두면 관련 기록을 이어 볼 때 기준이 돼요. ([수정]에서 입력)", muted = true)
        }
        if (a.archived) item(key = "arch") { ArchBanner("아카이브한 반복실행이에요. 읽기만 할 수 있고, 복원하면 2. 반복실행으로 돌아가요. ID·이력은 그대로예요.") }
        item(key = "links") {
            MockCard {
                Column(CardPad) {
                    SecH("이 영역의 프로젝트")
                    ChipFlow(Modifier.padding(top = 10.dp)) {
                        if (projects.isEmpty()) Note("연결된 프로젝트가 없어요")
                        projects.forEach { p -> LinkChip(ParaGlyph.project(), p.name, ParaGlyph.tint(PlaceType.PROJECT)) { onEvent(ParaEvent.Select(projectKey(p.id))) } }
                    }
                    SecH("참고 자료", Modifier.padding(top = 16.dp))
                    ChipFlow(Modifier.padding(top = 10.dp)) {
                        if (resources.isEmpty()) Note("연결된 자료가 없어요")
                        resources.forEach { r -> LinkChip(ParaGlyph.resource(r.kind), r.title, ParaGlyph.resourceTint()) { onEvent(ParaEvent.Select(resourceKey(r.id))) } }
                    }
                }
            }
        }
        item(key = "records") { RecordsCard(null, onEvent) }
        item(key = "tasks") { TasksCard("반복 할 일", d, Place(PlaceType.AREA, a.id, a.name), d.tasksOf(PlaceType.AREA, a.id), a.archived, layout, onEvent) }
    }
}

// ── 자료(3. 관심영역) ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResourcePane(d: ParaData, r: ResourceRow, layout: PaneLayout, onBack: (() -> Unit)?, onEvent: (ParaEvent) -> Unit) {
    val uri = LocalUriHandler.current
    val places = d.placesOf(r.id)
    PaneFrame(onBack) {
        item(key = "head") {
            Head(
                crumbOf(d, r.archived, ParaSection.RESOURCES),
                ParaGlyph.resource(r.kind), ParaGlyph.resourceTint(), r.title, layout.wideHead,
                chips = { WbChip(r.kindLabel, Tone.Info) },
                buttons = { HeadButtons(resourceKey(r.id), r.archived, onEvent) },
            )
        }
        if (r.archived) item(key = "arch") { ArchBanner("아카이브한 자료예요. 읽기만 할 수 있고, 복원하면 3. 관심영역으로 돌아가요.") }
        item(key = "body") {
            MockCard {
                Column(CardPad) {
                    SecH("내용")
                    val body = r.body
                    Text(
                        body ?: "아직 내용이 없어요 · [수정]에서 ${if (r.kind == "link") "주소" else "메모"}를 적어요",
                        fontSize = 15.sp, lineHeight = 24.sp,
                        color = when { body == null -> Wb.colors.muted; r.kind == "link" -> Wb.colors.link; else -> Wb.colors.ink },
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Wb.colors.surface)
                            // 서버가 http(s) 주소만 받는다(다른 앱을 여는 주소 차단). 브라우저가 없으면 조용히 넘어간다
                            .clickable(enabled = r.kind == "link" && body != null) { body?.let { runCatching { uri.openUri(it) } } }
                            .padding(12.dp),
                    )
                    SecH("연결된 곳", Modifier.padding(top = 16.dp)) {
                        Spacer(Modifier.weight(1f))
                        if (!r.archived) Text(
                            "연결 편집", fontSize = 13.sp, color = Wb.colors.link,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onEvent(ParaEvent.EditLinks(r.id)) }.padding(4.dp),
                        )
                    }
                    ChipFlow(Modifier.padding(top = 10.dp)) {
                        if (places.isEmpty()) Note("연결된 곳이 없어요")
                        places.forEach { pl -> LinkChip(ParaGlyph.place(pl.type), pl.name, ParaGlyph.tint(pl.type)) { onEvent(ParaEvent.Select(placeKey(pl.type, pl.id))) } }
                    }
                }
            }
        }
        item(key = "note") {
            Note("자료는 여러 프로젝트·반복실행에 함께 연결할 수 있어요. 지금은 링크·메모를 담아요(기존 기록 참조는 다음 단계, 새 문서 편집기는 없어요).")
        }
    }
}

// ── 모든 할 일(S07, DEC-22) ──

private val RANGES = listOf("next7" to "다음 7일", "late" to "지연", "all" to "전체")
private val OWNERS = listOf("all" to "전체", "mine" to "내 할 일", "follow" to "팔로업")

/** 기한 날짜가 기간 탭 안인지. 할 일 줄과 빠른 입력 알림('지금 고른 탭 밖')이 같이 쓴다 */
private fun dayInRange(day: LocalDate?, range: String, today: LocalDate): Boolean = when (range) {
    "late" -> day?.isBefore(today) == true
    "next7" -> day != null && !day.isBefore(today) && !day.isAfter(today.plusDays(6))
    else -> true
}

private fun inRange(t: TaskRow, range: String, today: LocalDate): Boolean =
    (range != "late" || !t.done) && dayInRange(dueDay(t), range, today)

/** follow = 남이 맡은 일(팔로업) */
private fun ownerIn(follow: Boolean, owner: String): Boolean = when (owner) {
    "mine" -> !follow
    "follow" -> follow
    else -> true
}

private fun inOwner(t: TaskRow, owner: String): Boolean = ownerIn(t.ownerKind == "other", owner)

private data class Group(val type: PlaceType?, val name: String, val key: String?, val rows: List<TaskRow>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllTasksPane(d: ParaData, layout: PaneLayout, onBack: (() -> Unit)?, onEvent: (ParaEvent) -> Unit) {
    // 목업 기본: 다음 7일 · 전체 · 완료 빼고
    var range by rememberSaveable { mutableStateOf("next7") }
    var owner by rememberSaveable { mutableStateOf("all") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now(SEOUL)
    val open = d.liveTasks.filter { !it.done }
    val shown = d.liveTasks.filter { inRange(it, range, today) && inOwner(it, owner) && (showDone || !it.done) }
    val groups = buildList {
        val unsorted = shown.filter { d.unsorted(it) }
        if (unsorted.isNotEmpty()) add(Group(null, UNSORTED, null, unsorted))
        d.activeProjects.forEach { p ->
            val rows = shown.filter { it.projectId == p.id }
            if (rows.isNotEmpty()) add(Group(PlaceType.PROJECT, p.name, projectKey(p.id), rows))
        }
        d.activeAreas.forEach { a ->
            val rows = shown.filter { it.areaId == a.id }
            if (rows.isNotEmpty()) add(Group(PlaceType.AREA, a.name, areaKey(a.id), rows))
        }
    }

    PaneFrame(onBack) {
        item(key = "head") {
            // 보기 전환은 좁은 칸에서도 제목과 같은 줄, 완료 포함은 그 아래 줄 오른쪽에 고정한다
            // (11/06 사용자: 기간·담당 버튼 줄 끝에 두면 숫자 자릿수에 따라 같은 줄·다음 줄을 오갔음)
            Head(
                d.workspace.name, ParaGlyph.allTasks, ParaGlyph.tint(null), "모든 할 일", wide = true,
                buttons = {
                    // 보기 전환: 목록만 P1. 캘린더·매트릭스는 자리만 두고 P4에서 연다(FR-24/25)
                    DarkSeg(
                        listOf(SegItem("list", "목록"), SegItem("cal", "캘린더", laterTag = "P4"), SegItem("matrix", "매트릭스", laterTag = "P4")),
                        "list", onLater = { onEvent(ParaEvent.Later(it)) },
                    ) {}
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Row(
                    Modifier.clip(ControlShape).clickable { showDone = !showDone }.padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = showDone, onCheckedChange = { showDone = it })
                    Text("완료 포함", fontSize = 12.sp, color = Wb.colors.muted)
                }
            }
        }
        item(key = "intro") {
            Text(
                buildAnnotatedString {
                    append("이 공간의 ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("프로젝트·반복실행") }
                    append(" 할 일을 프로젝트별로 묶어 봐요. 오늘 할 일은 ‘오늘’ 화면에서 봐요.")
                },
                fontSize = 15.sp, color = Wb.colors.body,
            )
        }
        item(key = "quick") {
            // 목업 .quick-card. '#'이 없으면 이 기기에서 마지막에 넣은 곳(DEC-70)
            MockCard {
                QuickAddBar(d.places, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { r ->
                    val shownHere = dayInRange(r.dueDate, range, today) && ownerIn(r.assignee != null, owner)
                    onEvent(ParaEvent.QuickAdded(quickAddedText(r) + if (shownHere) "" else " · 지금 고른 탭 밖이라 ‘전체’에서 보여요"))
                }
            }
        }
        item(key = "toolbar") {
            // 기간·담당 두 묶음은 늘 한 줄(11/05 사용자: 숫자가 두 자리가 되면 '전체·내 할 일·팔로업'만 아래 줄로 떨어졌음).
            // 그래도 넘치면 뒤 묶음이 남은 폭만 받아 그 안에서 옆으로 민다
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkSeg(
                    RANGES.map { (k, label) -> SegItem(k, label, count = open.count { inRange(it, k, today) && inOwner(it, owner) }, alert = k == "late") },
                    range,
                    compact = layout.compactSeg,
                ) { range = it }
                DarkSeg(
                    OWNERS.map { (k, label) -> SegItem(k, label, count = open.count { inRange(it, range, today) && inOwner(it, k) }) },
                    owner,
                    compact = layout.compactSeg,
                ) { owner = it }
            }
        }
        if (groups.isEmpty()) item(key = "empty") {
            MockCard {
                EmptyNote(
                    if (range == "late") "지연된 할 일이 없어요" else "여기엔 할 일이 없어요",
                    "다른 탭을 보거나 위에 한 줄로 추가해 보세요.",
                )
            }
        }
        groups.forEach { g ->
            item(key = "g-${g.key ?: "unsorted"}") {
                MockCard {
                    GroupHead(g) { g.key?.let { onEvent(ParaEvent.Select(it)) } }
                    if (g.type == null) Note("할 일을 눌러 ‘어디에’를 고르면 그 프로젝트·반복실행으로 옮겨져요.", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    HorizontalDivider(color = Wb.colors.border)
                    TaskLines(sorted(g.rows), d, readOnly = false, layout, onEvent)
                }
            }
        }
        item(key = "foot") {
            // 목업 아래 .note(굵은 글씨 자리 그대로)
            Text(
                buildAnnotatedString {
                    fun bold(s: String) = withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s) }
                    append("빠른 입력은 "); bold("오늘·내일·모레, 10/24, 금요일, 다음 주 월요일, 3시·15:00"); append(", ")
                    bold("!높음"); append(", "); bold("#프로젝트"); append(", "); bold("@사람(팔로업)")
                    append("을 규칙으로 알아봐요(AI 비용 없음). 못 알아본 말은 제목에 그대로 남아요. 기한은 ")
                    bold("날짜만"); append(" / "); bold("날짜+시각"); append(" / "); bold("모호(확인 필요)")
                    append("를 구분해 보존해요. 회의에서 나온 할 일은 기록 화면에서 골라 추가해요. 반복·하위 작업·태그는 P4예요.")
                },
                fontSize = 12.sp, lineHeight = 18.sp, color = Wb.colors.muted,
            )
        }
    }
}

/** 목업 .grp-h: 이름(누르면 그 상세) · N건 · 오른쪽 종류 칩 */
@Composable
private fun GroupHead(g: Group, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = g.key != null, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ParaGlyph.place(g.type), contentDescription = null, tint = ParaGlyph.tint(g.type), modifier = Modifier.size(16.dp))
            Text(g.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(start = 6.dp))
        }
        Text("${g.rows.size}건", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        when (g.type) {
            PlaceType.PROJECT -> WbChip("프로젝트", Tone.Mute)
            PlaceType.AREA -> WbChip("반복실행", Tone.Mute)
            null -> Unit
        }
    }
}
