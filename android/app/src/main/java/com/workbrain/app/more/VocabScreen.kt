package com.workbrain.app.more

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.Server
import com.workbrain.app.data.VocabAddResult
import com.workbrain.app.data.VocabRow
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.EmptyState
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import com.workbrain.app.ui.WbIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VOCAB_LIMIT = 200

/**
 * 내 용어집(DEC-33): 업무에서 자주 쓰는 말을 등록하면 받아쓰기 힌트와 회의록 표기 기준으로 쓴다.
 * 클로바노트 단어 등록과 같은 규칙(200개, 한글·영문·숫자 20자 이내)이고, 같은 엑셀 모양으로 가져오고 내보낸다.
 */
@Composable
fun VocabScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var terms by remember { mutableStateOf<List<VocabRow>?>(null) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }

    suspend fun load() {
        runCatching { terms = Server.vocab() }
            .onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
    }
    LaunchedEffect(Unit) { load() }

    fun add(list: List<String>, fromFile: Boolean) {
        if (list.isEmpty()) {
            message = (if (fromFile) "파일에서 단어를 찾지 못했어요" else "단어를 입력해 주세요") to Tone.Warn
            return
        }
        busy = true
        scope.launch {
            message = runCatching { Server.addVocab(list) }.fold(
                { r -> summary(r, fromFile).also { if (r.added > 0) input = "" } },
                { "저장하지 못했어요. 인터넷 연결을 확인해 주세요" to Tone.Crit },
            )
            load()
            busy = false
        }
    }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val isXlsx = context.contentResolver.getType(uri) == VocabFile.XLSX_MIME || uri.toString().endsWith(".xlsx", true)
                    context.contentResolver.openInputStream(uri)!!.use { VocabFile.readTerms(it, isXlsx) }
                }
            }
            read.fold({ add(it, fromFile = true) }, { message = "파일을 읽지 못했어요. 클로바노트에서 받은 엑셀이나 한 줄에 한 단어인 텍스트 파일을 골라 주세요" to Tone.Crit })
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VocabFile.XLSX_MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 파일은 오래된 것부터(클로바노트에 다시 올렸을 때 등록 순서가 유지되게)
        val list = terms.orEmpty().reversed().map { it.term }
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { VocabFile.writeXlsx(it, list) } }
            }.fold({ "${list.size}개를 엑셀로 저장했어요" to Tone.Good }, { "저장하지 못했어요" to Tone.Crit })
        }
    }

    val list = terms.orEmpty()
    Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Text("← 더보기", color = Wb.colors.link) }
        PageHeader("내 용어집", "자주 쓰는 말", Modifier.padding(horizontal = 16.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                WbCard {
                    Text(
                        "등록한 단어는 받아쓰기 힌트와 회의록 표기 기준으로 쓰여요. 특수 문자 없이 한글·영문·숫자 20자 이내.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Wb.colors.muted,
                    )
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(20) },
                            placeholder = { Text("자주 사용하는 단어") },
                            singleLine = true,
                            shape = ControlShape,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (!busy) add(listOf(input), fromFile = false) }),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Wb.colors.borderStrong,
                                focusedContainerColor = Wb.colors.card,
                                unfocusedContainerColor = Wb.colors.card,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.padding(start = 8.dp))
                        PrimaryButton("등록", enabled = !busy && input.isNotBlank(), onClick = { add(listOf(input), fromFile = false) })
                    }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("파일 가져오기", enabled = !busy, onClick = {
                            importFile.launch(arrayOf(VocabFile.XLSX_MIME, "text/plain", "text/csv", "text/comma-separated-values"))
                        })
                        SecondaryButton("엑셀로 내보내기", enabled = !busy && list.isNotEmpty(), onClick = {
                            exportFile.launch("workbrain_words_ko.xlsx")
                        })
                    }
                }
            }
            message?.let { (text, tone) -> item { Banner(text, tone) } }
            item {
                Text(
                    "등록 ${list.size}/${VOCAB_LIMIT}개",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (list.size >= VOCAB_LIMIT) Wb.colors.warn else Wb.colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (terms != null && list.isEmpty()) item {
                EmptyState("아직 비어 있어요", "클로바노트에서 '다운로드'한 엑셀을 그대로 가져올 수 있어요.", icon = WbIcons.Document, tint = GlassTint.Purple)
            }
            if (list.isNotEmpty()) item {
                WbCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    list.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(color = Wb.colors.border)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.term, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.ink, modifier = Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    runCatching { Server.deleteVocab(row.id) }
                                        .onSuccess { message = "'${row.term}'을(를) 지웠어요" to Tone.Mute }
                                        .onFailure { message = "지우지 못했어요" to Tone.Crit }
                                    load()
                                    busy = false
                                }
                            }) { Text("삭제", color = Wb.colors.muted) }
                        }
                    }
                }
            }
        }
    }
}

/** 서버 결과를 한 줄 안내로: 넣은 개수 + 건너뛴 이유 */
private fun summary(r: VocabAddResult, fromFile: Boolean): Pair<String, Tone> {
    val skipped = listOfNotNull(
        r.duplicate.takeIf { it > 0 }?.let { "이미 있음 $it" },
        r.invalid.takeIf { it > 0 }?.let { "형식 안 맞음 $it" },
        r.overLimit.takeIf { it > 0 }?.let { "200개 초과 $it" },
    )
    if (!fromFile && r.added == 0) {
        return when {
            r.duplicate > 0 -> "이미 등록된 단어예요"
            r.overLimit > 0 -> "200개까지 등록할 수 있어요. 안 쓰는 단어를 지워 주세요"
            else -> "특수 문자 없이 한글·영문·숫자 20자 이내로 입력해 주세요"
        } to Tone.Warn
    }
    val head = "${r.added}개 등록했어요"
    return (if (skipped.isEmpty()) head else "$head · 건너뜀: ${skipped.joinToString(", ")}") to
        (if (r.overLimit > 0 || (r.added == 0 && skipped.isNotEmpty())) Tone.Warn else Tone.Good)
}
