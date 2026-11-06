package com.workbrain.app.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.LinkedRecord
import com.workbrain.app.data.ParaData
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.RecordBrief
import com.workbrain.app.data.ResourceRow
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.MAX_TITLE
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbChip
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.ParaGlyph
import com.workbrain.app.ui.Note
import com.workbrain.app.ui.SegItem
import com.workbrain.app.ui.DarkSeg
import com.workbrain.app.ui.SmallButton
import com.workbrain.app.ui.SEOUL
import java.time.OffsetDateTime

/**
 * PARA 4칸. 이름은 사용자 PARA 폴더 그대로(DEC-22), 옆 작은 글씨는 목업처럼 영어 이름. 깊이는 칸 → 항목(DEC-24).
 * 칸 이름 옆 (+)는 그 칸에 맞는 것만 만든다(터치 화면이라 늘 보인다). 4. 아카이브에는 (+)가 없다.
 */
enum class ParaSection(val no: Char, val label: String, val hint: String, val adds: Boolean, val placeholder: String) {
    PROJECTS('1', "1. 프로젝트", "Projects", true, "새 프로젝트 이름"),
    AREAS('2', "2. 반복실행", "Areas", true, "새 반복실행 이름"),
    RESOURCES('3', "3. 관심영역", "Resources", true, "자료 이름"),
    ARCHIVE('4', "4. 아카이브", "Archives", false, ""),
}

/** 트리에서 고른 것의 열쇠. 화면을 접고 펴도 남도록 글자로 저장한다: all · p:ID · a:ID · r:ID */
const val ALL_TASKS = "all"
fun projectKey(id: String) = "p:$id"
fun areaKey(id: String) = "a:$id"
fun resourceKey(id: String) = "r:$id"
fun placeKey(type: PlaceType, id: String) = if (type == PlaceType.PROJECT) projectKey(id) else areaKey(id)

/** 서버 시각 → 서울 'M/d'(트리 하위 기록·관련 기록 카드) */
fun shortDay(iso: String?): String? = iso?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(SEOUL).let { z -> "${z.monthValue}/${z.dayOfMonth}" } }.getOrNull()
}

/** 트리 한 줄 */
private data class TreeItem(val key: String, val icon: ImageVector, val tint: @Composable () -> Color, val name: String, val count: Int, val archived: Boolean = false)

private fun itemsOf(d: ParaData, s: ParaSection): List<TreeItem> = when (s) {
    ParaSection.PROJECTS -> d.activeProjects.map { TreeItem(projectKey(it.id), ParaGlyph.project(), { ParaGlyph.tint(PlaceType.PROJECT) }, it.name, d.openCount(PlaceType.PROJECT, it.id)) }
    ParaSection.AREAS -> d.activeAreas.map { TreeItem(areaKey(it.id), ParaGlyph.area(), { ParaGlyph.tint(PlaceType.AREA) }, it.name, d.openCount(PlaceType.AREA, it.id)) }
    ParaSection.RESOURCES -> d.activeResources.map { TreeItem(resourceKey(it.id), ParaGlyph.resource(it.kind), { ParaGlyph.resourceTint() }, it.title, 0) }
    // 아카이브 칸은 흐린 글씨(목업 .tn.arch). 프로젝트 → 반복실행 → 자료 순
    ParaSection.ARCHIVE ->
        d.projects.filter { it.archived }.map { TreeItem(projectKey(it.id), ParaGlyph.project(), { Wb.colors.muted }, it.name, 0, archived = true) } +
            d.areas.filter { it.archived }.map { TreeItem(areaKey(it.id), ParaGlyph.area(), { Wb.colors.muted }, it.name, 0, archived = true) } +
            d.resources.filter { it.archived }.map { TreeItem(resourceKey(it.id), ParaGlyph.resource(it.kind), { Wb.colors.muted }, it.title, 0, archived = true) }
}

/**
 * 목업 .side: 흰 패널 안에 공간 버튼 → 모든 할 일 → 1~4칸 → 범례.
 * adding: 지금 이름을 받는 칸(칸 아래 입력 줄, 목업 .tn-new). onCreate(칸, 종류, 이름)
 * 프로젝트 아래에는 연결된 기록이 하위 줄로 펼쳐진다(DEC-24 깊이 고정, DEC-69). openProjects = 펼친 프로젝트 ID
 */
@Composable
fun ParaTreePanel(
    d: ParaData,
    selectedKey: String?,
    openSections: String,
    adding: ParaSection?,
    openProjects: Set<String>,
    onToggleSection: (ParaSection) -> Unit,
    onAdd: (ParaSection?) -> Unit,
    onCreate: (section: ParaSection, kind: String, name: String, recordId: String?) -> Unit,
    onSelect: (String) -> Unit,
    onToggleProject: (String) -> Unit,
    onOpenRecord: (recordId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Wb.colors.card,
        border = BorderStroke(1.dp, Wb.colors.border),
    ) {
        Column(Modifier.padding(8.dp)) {
            WorkspaceButton(d.workspace.name, d.workspace.description)
            TreeRow(ParaGlyph.allTasks, { ParaGlyph.tint(null) }, "모든 할 일", d.liveTasks.count { !it.done }, selectedKey == ALL_TASKS, indent = false) { onSelect(ALL_TASKS) }
            ParaSection.entries.forEach { s ->
                val rows = itemsOf(d, s)
                val open = s.no in openSections
                SectionRow(s, rows.size, open, onToggle = { onToggleSection(s) }, onAdd = { onAdd(if (adding == s) null else s) })
                if (adding == s) NewForm(s, onCancel = { onAdd(null) }) { kind, name, recordId -> onCreate(s, kind, name, recordId) }
                if (open) rows.forEach { r ->
                    if (s == ParaSection.PROJECTS) {
                        val projectId = r.key.substringAfter(':')
                        val records = d.recordsOf(projectId)
                        val expanded = projectId in openProjects && records.isNotEmpty()
                        TreeRow(
                            r.icon, r.tint, r.name, r.count, selectedKey == r.key, indent = true,
                            expander = if (records.isEmpty()) Expander.SPACE else if (expanded) Expander.OPEN else Expander.CLOSED,
                            onExpand = { onToggleProject(projectId) },
                        ) { onSelect(r.key) }
                        if (expanded) records.forEach { rec -> RecordTreeRow(rec) { onOpenRecord(rec.recordId, rec.title) } }
                    } else {
                        TreeRow(r.icon, r.tint, r.name, r.count, selectedKey == r.key, indent = true, archived = r.archived) { onSelect(r.key) }
                    }
                }
            }
            Legend()
        }
    }
}

/** 업무 공간 단추(목업 .ws). P1은 본업 하나, 부업 추가는 P4(DEC-22) — 메뉴에 흐린 자리로만 */
@Composable
private fun WorkspaceButton(name: String, description: String?) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(bottom = 8.dp)) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(4.dp),
            color = Wb.colors.surface,
            border = BorderStroke(1.dp, Wb.colors.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(WbIcons.Building, contentDescription = null, tint = Wb.colors.body, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink)
                    description?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted) }
                }
                Icon(WbIcons.ChevronDown, contentDescription = "공간 고르기", tint = Wb.colors.muted, modifier = Modifier.size(14.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 260.dp, max = 320.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(ControlShape)
                    .background(Wb.colors.primary.copy(alpha = 0.10f)).clickable { open = false }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(WbIcons.Check, contentDescription = null, tint = Wb.colors.ink, modifier = Modifier.size(16.dp))
                Text(listOfNotNull(name, description).joinToString(" · "), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+ 새 공간 추가 (예: 부업)", fontSize = 14.sp, color = Wb.colors.muted, modifier = Modifier.weight(1f))
                WbChip("나중에 · P4", Tone.Mute)
            }
            Note("공간마다 PARA 트리·기록·검색·AI 연결이 따로예요. 본업 기록이 부업 쪽 제안에 섞이지 않아요.", Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun SectionRow(s: ParaSection, count: Int, open: Boolean, onToggle: () -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(4.dp))
            .clickable(onClickLabel = if (open) "접기" else "펼치기", onClick = onToggle)
            .heightIn(min = 36.dp).padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) WbIcons.ChevronDown else WbIcons.ChevronRight, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(16.dp))
        Text(s.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(start = 6.dp))
        Text(s.hint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted, modifier = Modifier.weight(1f).padding(start = 4.dp))
        if (s.adds) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "${s.label.drop(3)} 추가", onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(WbIcons.Plus, contentDescription = "${s.label.drop(3)} 추가", tint = Wb.colors.muted, modifier = Modifier.size(18.dp)) }
        }
        Text("$count", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted, modifier = Modifier.width(24.dp).padding(start = 6.dp))
    }
}

/**
 * 칸 아래 이름 입력 줄(목업 .tn-new). 자료는 종류(링크·메모·기록 참조)를 먼저 고른다.
 * 기록 참조는 기록을 먼저 고르고 그 제목이 이름 칸에 들어간다(DEC-71, 서버가 기록 없는 기록 참조를 받지 않음)
 */
@Composable
private fun NewForm(s: ParaSection, onCancel: () -> Unit, onCreate: (kind: String, name: String, recordId: String?) -> Unit) {
    var name by remember(s) { mutableStateOf("") }
    var kind by remember(s) { mutableStateOf("link") }
    var record by remember(s) { mutableStateOf<RecordBrief?>(null) }
    var picking by remember { mutableStateOf(false) }
    val recordRef = kind == ResourceRow.RECORD_REF
    val submit = {
        if (name.isNotBlank() && (!recordRef || record != null)) onCreate(kind, name.trim(), if (recordRef) record?.id else null)
    }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s == ParaSection.RESOURCES) {
            DarkSeg(listOf(SegItem("link", "링크"), SegItem("note", "메모"), SegItem(ResourceRow.RECORD_REF, "기록 참조")), kind) {
                kind = it
                if (it == ResourceRow.RECORD_REF && record == null) picking = true
            }
        }
        if (recordRef) RecordPickField(record) { picking = true }
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(MAX_TITLE) }, singleLine = true,
            placeholder = { Text(s.placeholder, fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 14.sp, color = Wb.colors.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            shape = ControlShape, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("추가", primary = true, onClick = submit)
            SmallButton("취소", onClick = onCancel)
        }
    }
    if (picking) {
        RecordPickSheet(onDismiss = { picking = false }) { r ->
            // 이름을 직접 고쳐 둔 게 아니면(비었거나 앞서 고른 기록 제목 그대로) 새 기록 제목으로 바꾼다
            if (name.isBlank() || name == record?.title?.take(MAX_TITLE)) name = r.title.take(MAX_TITLE)
            record = r
            picking = false
        }
    }
}

/** 기록 참조: 고른 기록(없으면 '기록 고르기'). 누르면 기록 고르기 서랍 */
@Composable
private fun RecordPickField(record: RecordBrief?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ControlShape,
        color = Wb.colors.card,
        border = BorderStroke(1.dp, if (record == null) Wb.colors.primary else Wb.colors.borderStrong),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(record?.let { recordIcon(it.channel) } ?: WbIcons.Mic, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    record?.title ?: "기록 고르기", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (record == null) Wb.colors.link else Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                record?.let { Text(recordMeta(it), fontSize = 12.sp, color = Wb.colors.muted) }
            }
            Icon(WbIcons.ChevronDown, contentDescription = "기록 고르기", tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** 프로젝트 줄 앞 펼침 화살표(목업 .tn .tw): 없음(다른 칸) · 자리만(연결된 기록 없음) · 접힘 · 펼침 */
private enum class Expander { NONE, SPACE, CLOSED, OPEN }

@Composable
private fun TreeRow(
    icon: ImageVector,
    tint: @Composable () -> Color,
    name: String,
    count: Int,
    selected: Boolean,
    indent: Boolean,
    archived: Boolean = false,
    expander: Expander = Expander.NONE,
    onExpand: () -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(if (selected) Wb.colors.primary.copy(alpha = 0.13f) else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            // 화살표 칸(24dp)이 있는 줄은 그만큼 덜 들여 써서 아이콘이 다른 줄과 같은 세로줄에 온다
            .padding(start = if (!indent) 8.dp else if (expander == Expander.NONE) 28.dp else 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expander != Expander.NONE) {
            Box(
                Modifier.size(width = 24.dp, height = 40.dp).then(
                    if (expander == Expander.SPACE) Modifier
                    else Modifier.clickable(onClickLabel = if (expander == Expander.OPEN) "기록 접기" else "기록 펼치기", onClick = onExpand),
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (expander != Expander.SPACE) {
                    Icon(if (expander == Expander.OPEN) WbIcons.ChevronDown else WbIcons.ChevronRight, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(14.dp))
                }
            }
        }
        Icon(icon, contentDescription = null, tint = tint(), modifier = Modifier.size(16.dp))
        Text(
            name, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (archived) Wb.colors.muted else if (selected) Wb.colors.ink else Wb.colors.body,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (count > 0) Text("$count", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 프로젝트 아래 하위 기록 줄(목업 .tn.rec): 작은 아이콘 · 기록 이름 · 날짜. 누르면 기록 상세 */
@Composable
private fun RecordTreeRow(r: LinkedRecord, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(onClickLabel = "기록 열기", onClick = onClick)
            .heightIn(min = 36.dp).padding(start = 52.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(recordIcon(r.channel), contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(13.dp))
        Text(r.title, fontSize = 13.sp, color = Wb.colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
        shortDay(r.at)?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Wb.colors.muted, modifier = Modifier.padding(start = 6.dp)) }
    }
}

/** 목업 .para-legend. 끌어서 옮기기는 아직 없어서(2단계) 지금 되는 옮기는 방법을 적는다 */
@Composable
private fun Legend() {
    HorizontalDivider(color = Wb.colors.border, modifier = Modifier.padding(top = 8.dp))
    val b = SpanStyle(fontWeight = FontWeight.Bold)
    Text(
        buildAnnotatedString {
            withStyle(b) { append("1") }; append(" 끝이 있는 일 · ")
            withStyle(b) { append("2") }; append(" 계속 맡는 일 · ")
            withStyle(b) { append("3") }; append(" 참고 자료 · ")
            withStyle(b) { append("4") }; append(" 끝난 것(복원 가능)\n")
            append("옮기기: 할 일은 눌러서 ‘어디에’ · 프로젝트는 [아카이브로] · 자료는 ‘연결 편집’")
        },
        fontSize = 11.sp, lineHeight = 17.sp, color = Wb.colors.muted,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}
