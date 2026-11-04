package com.workbrain.app.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.AreaRow
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.data.ProjectRow
import com.workbrain.app.data.ResourceRow
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.MAX_TITLE
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons
import com.workbrain.app.ui.ParaGlyph
import com.workbrain.app.ui.Note

private const val MAX_PURPOSE = 500
private const val MAX_NOTE = 2_000
private const val MAX_URL = 2_000
private val HTTP = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

/** 주소 앞에 http(s)://가 없으면 https://를 붙인다(example.com/a → https://example.com/a). 형식이 틀리면 null */
fun normalizeUrl(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val withScheme = if (t.contains("://")) t else "https://$t"
    return withScheme.takeIf { HTTP.matches(it) && it.length <= MAX_URL }
}

/** 수정 서랍 공통 틀: 제목 · 설명 · 칸들 · [취소][저장] */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(title: String, note: String, canSave: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, fields: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x6609141E),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Wb.colors.ink)
            Note(note, Modifier.padding(bottom = 4.dp))
            fields()
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("취소", onClick = onDismiss, modifier = Modifier.height(44.dp))
                PrimaryButton("저장", enabled = canSave, modifier = Modifier.weight(1f), onClick = onSave)
            }
        }
    }
}

@Composable
private fun FieldName(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink, modifier = Modifier.padding(top = 10.dp))
}

/** 프로젝트 [수정]: 이름 · 반복실행 연결 · 목적(목업 '이름·반복실행 연결·목적 직접 수정') */
@Composable
fun ProjectEditSheet(p: ProjectRow, areas: List<AreaRow>, onDismiss: () -> Unit, onSave: (name: String, areaId: String?, purpose: String?) -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var areaId by remember { mutableStateOf(p.areaId) }
    var purpose by remember { mutableStateOf(p.purpose.orEmpty()) }
    EditSheet(
        "프로젝트 수정", "이름·반복실행 연결·목적을 고쳐요.",
        canSave = name.isNotBlank(), onDismiss = onDismiss,
        onSave = { onSave(name.trim(), areaId, purpose.trim().ifEmpty { null }) },
    ) {
        FieldName("이름")
        OutlinedTextField(name, { name = it.take(MAX_TITLE) }, singleLine = true, shape = ControlShape, modifier = Modifier.fillMaxWidth())
        FieldName("반복실행")
        AreaField(areas, areaId) { areaId = it }
        FieldName("목적")
        OutlinedTextField(
            purpose, { purpose = it.take(MAX_PURPOSE) }, minLines = 3,
            placeholder = { Text("예: 모바일 사고접수 단계를 줄이고 고객 안내를 정리한다") },
            supportingText = { Text("${purpose.length}/$MAX_PURPOSE") },
            shape = ControlShape, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 반복실행 고르기: 연결 없음 + 보관되지 않은 반복실행(지금 연결이 보관된 것이면 이름에 표시) */
@Composable
private fun AreaField(areas: List<AreaRow>, value: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = areas.firstOrNull { it.id == value }
    val label = when {
        value == null -> "반복실행 연결 없음"
        current == null -> "알 수 없는 반복실행"
        current.archived -> "${current.name} (아카이브)"
        else -> current.name
    }
    Box {
        Surface(
            onClick = { open = true }, shape = ControlShape, color = Wb.colors.card,
            border = BorderStroke(1.dp, Wb.colors.borderStrong), modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(WbIcons.Repeat, contentDescription = null, tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, color = Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                Icon(WbIcons.ChevronDown, contentDescription = "반복실행 고르기", tint = Wb.colors.muted, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp)) {
            DropdownMenuItem(text = { Text("반복실행 연결 없음") }, onClick = { open = false; onPick(null) })
            areas.filterNot { it.archived }.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(WbIcons.Repeat, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { open = false; onPick(a.id) },
                )
            }
        }
    }
}

/** 반복실행 [수정]: 이름 · 무엇을 계속 맡는 일인지(목업 🧭 한 줄) */
@Composable
fun AreaEditSheet(a: AreaRow, onDismiss: () -> Unit, onSave: (name: String, description: String?) -> Unit) {
    var name by remember { mutableStateOf(a.name) }
    var desc by remember { mutableStateOf(a.description.orEmpty()) }
    EditSheet(
        "반복실행 수정", "이름과 무엇을 계속 맡는 일인지 적어요.",
        canSave = name.isNotBlank(), onDismiss = onDismiss,
        onSave = { onSave(name.trim(), desc.trim().ifEmpty { null }) },
    ) {
        FieldName("이름")
        OutlinedTextField(name, { name = it.take(MAX_TITLE) }, singleLine = true, shape = ControlShape, modifier = Modifier.fillMaxWidth())
        FieldName("무엇을 계속 맡는 일인지")
        OutlinedTextField(
            desc, { desc = it.take(MAX_PURPOSE) }, minLines = 3,
            placeholder = { Text("예: 매월 말 지급 건수·금액·지연 건을 집계해 보고한다") },
            supportingText = { Text("${desc.length}/$MAX_PURPOSE") },
            shape = ControlShape, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 자료 [수정]: 이름 + 내용(링크면 주소, 메모면 글). 종류는 만들 때 정한 그대로 */
@Composable
fun ResourceEditSheet(r: ResourceRow, onDismiss: () -> Unit, onSave: (title: String, url: String?, note: String?) -> Unit) {
    var title by remember { mutableStateOf(r.title) }
    var url by remember { mutableStateOf(r.url.orEmpty()) }
    var note by remember { mutableStateOf(r.note.orEmpty()) }
    val link = r.kind == "link"
    val cleanUrl = normalizeUrl(url)
    val urlError = link && url.isNotBlank() && cleanUrl == null
    EditSheet(
        "자료 수정", "자료는 링크·짧은 메모만 담아요(새 문서 편집기는 없어요).",
        canSave = title.isNotBlank() && !urlError, onDismiss = onDismiss,
        onSave = { onSave(title.trim(), if (link) cleanUrl else null, if (link) null else note.trim().ifEmpty { null }) },
    ) {
        FieldName("이름")
        OutlinedTextField(title, { title = it.take(MAX_TITLE) }, singleLine = true, shape = ControlShape, modifier = Modifier.fillMaxWidth())
        if (link) {
            FieldName("주소")
            OutlinedTextField(
                url, { url = it.take(MAX_URL) }, singleLine = true, placeholder = { Text("https://") }, isError = urlError,
                supportingText = { if (urlError) Text("http:// 또는 https://로 여는 웹 주소만 넣을 수 있어요") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = ControlShape, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            FieldName("메모")
            OutlinedTextField(
                note, { note = it.take(MAX_NOTE) }, minLines = 4, placeholder = { Text("짧게 적어 두세요") },
                supportingText = { Text("${note.length}/$MAX_NOTE") }, shape = ControlShape, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 자료 '연결 편집': 연결할 프로젝트·반복실행을 고르거나 해제한다(목업) */
@Composable
fun LinkEditDialog(places: List<Place>, current: List<Place>, onDismiss: () -> Unit, onSave: (List<Place>) -> Unit) {
    var picked by remember { mutableStateOf(current.map { it.type to it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("연결 편집") },
        text = {
            Column {
                Note("연결할 프로젝트·반복실행을 고르거나 해제해요.", Modifier.padding(bottom = 8.dp))
                if (places.isEmpty()) {
                    Text("연결할 프로젝트·반복실행이 없어요.", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted)
                } else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    listOf(PlaceType.PROJECT to "1. 프로젝트", PlaceType.AREA to "2. 반복실행").forEach { (kind, heading) ->
                        val group = places.filter { it.type == kind }
                        if (group.isNotEmpty()) {
                            item(key = "h-$kind") { Text(heading, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Wb.colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                            items(group, key = { "${it.type}-${it.id}" }) { p ->
                                val key = p.type to p.id
                                Row(
                                    Modifier.fillMaxWidth().clip(ControlShape).clickable { picked = if (key in picked) picked - key else picked + key },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = key in picked, onCheckedChange = { picked = if (it) picked + key else picked - key })
                                    Icon(ParaGlyph.place(p.type), contentDescription = null, tint = ParaGlyph.tint(p.type), modifier = Modifier.size(16.dp))
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge, color = Wb.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(places.filter { (it.type to it.id) in picked }) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
