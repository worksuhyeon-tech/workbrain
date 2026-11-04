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
import com.workbrain.app.data.ParaData
import com.workbrain.app.data.PlaceType
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
 */
@Composable
fun ParaTreePanel(
    d: ParaData,
    selectedKey: String?,
    openSections: String,
    adding: ParaSection?,
    onToggleSection: (ParaSection) -> Unit,
    onAdd: (ParaSection?) -> Unit,
    onCreate: (ParaSection, String, String) -> Unit,
    onSelect: (String) -> Unit,
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
                if (adding == s) NewForm(s, onCancel = { onAdd(null) }) { kind, name -> onCreate(s, kind, name) }
                if (open) rows.forEach { r ->
                    TreeRow(r.icon, r.tint, r.name, r.count, selectedKey == r.key, indent = true, archived = r.archived) { onSelect(r.key) }
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

/** 칸 아래 이름 입력 줄(목업 .tn-new). 자료는 종류(링크·메모)를 먼저 고른다. 기록 참조는 다음 단계 */
@Composable
private fun NewForm(s: ParaSection, onCancel: () -> Unit, onCreate: (kind: String, name: String) -> Unit) {
    var name by remember(s) { mutableStateOf("") }
    var kind by remember(s) { mutableStateOf("link") }
    val submit = { if (name.isNotBlank()) onCreate(kind, name.trim()) }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s == ParaSection.RESOURCES) DarkSeg(listOf(SegItem("link", "링크"), SegItem("note", "메모")), kind) { kind = it }
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
}

@Composable
private fun TreeRow(
    icon: ImageVector,
    tint: @Composable () -> Color,
    name: String,
    count: Int,
    selected: Boolean,
    indent: Boolean,
    archived: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(if (selected) Wb.colors.primary.copy(alpha = 0.13f) else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(start = if (indent) 28.dp else 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
