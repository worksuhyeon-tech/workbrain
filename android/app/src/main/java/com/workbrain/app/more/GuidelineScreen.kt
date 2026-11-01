package com.workbrain.app.more

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.GuidelineRef
import com.workbrain.app.data.GuidelineRejected
import com.workbrain.app.data.GuidelineRow
import com.workbrain.app.data.Server
import com.workbrain.app.record.FileImporter
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.PageHeader
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SectionLabel
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 서버 시각(UTC ISO)을 사용자 표시 시간대(Asia/Seoul)의 짧은 글자로 바꾼다. 읽지 못하면 null */
fun guidelineStamp(iso: String?, pattern: String): String? =
    iso?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
    }

/**
 * 회의록 지침(FR-30 2단계, 목업 settings '회의록 지침'): 앱에서 편집하지 않고 Claude 스킬 폴더를 zip으로 가져와 쓴다.
 * 다시 가져오면 지침과 참고 파일이 통째로 바뀌고, 이미 만든 회의록은 그대로다.
 * incomingZip: '공유 → WorkBrain'으로 받은 zip. 받으면 onIncomingHandled로 알려 한 번만 처리한다
 */
@Composable
fun GuidelineScreen(onBack: (() -> Unit)?, incomingZip: Uri?, onIncomingHandled: () -> Unit) {
    // null이면 펼친 화면의 오른쪽 칸이라 뒤로 가기가 없다
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<GuidelineRow?>(null) }
    var refsConsentOn by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var candidate by remember { mutableStateOf<SkillZip.Candidate?>(null) }
    var previewOpen by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { Server.guideline() to Server.refsConsent() }
            .onSuccess { (row, consent) -> current = row; refsConsentOn = consent }
            .onFailure { message = "불러오지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit }
    }
    LaunchedEffect(Unit) { load() }

    fun readZip(uri: Uri) {
        busy = true
        message = null
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val name = FileImporter.displayName(context, uri) ?: "skill.zip"
                    context.contentResolver.openInputStream(uri)!!.use { SkillZip.read(it, name) }
                }
            }
            read.fold(
                { result ->
                    when (result) {
                        is SkillZip.Result.Ok -> candidate = result.candidate
                        is SkillZip.Result.Rejected -> message = "${result.reason} 기존 지침은 그대로예요." to Tone.Warn
                    }
                },
                { message = "파일을 읽지 못했어요. 다시 골라 주세요. 기존 지침은 그대로예요." to Tone.Crit },
            )
            busy = false
        }
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readZip(uri)
    }
    LaunchedEffect(incomingZip) {
        val uri = incomingZip ?: return@LaunchedEffect
        onIncomingHandled()
        readZip(uri)
    }

    fun apply(picked: SkillZip.Candidate, refs: List<GuidelineRef>, grantRefsConsent: Boolean) {
        busy = true
        scope.launch {
            val failure = runCatching { Server.setGuideline(picked.skill, refs, picked.sourceName) }.exceptionOrNull()
            candidate = null
            if (failure != null) {
                val reason = (failure as? GuidelineRejected)?.message ?: "저장하지 못했어요. 인터넷 연결을 확인해 주세요."
                message = "$reason 기존 지침은 그대로예요." to Tone.Crit
            } else {
                // 동의는 지침이 바뀐 뒤에 켠다: 실패해도 참고 파일은 저장만 되고 AI에는 가지 않는다
                val consentFailed = grantRefsConsent && refs.isNotEmpty() && refsConsentOn != true &&
                    runCatching { Server.setRefsConsent(true) }.isFailure
                message = if (consentFailed) {
                    "지침은 바꿨지만 참고 파일 전송 동의를 저장하지 못했어요. 아래 스위치에서 다시 켜 주세요." to Tone.Warn
                } else {
                    "지침을 바꿨어요 · 새로 만드는 회의록부터 적용돼요" to Tone.Good
                }
            }
            load()
            busy = false
        }
    }

    fun removeRef(row: GuidelineRow, ref: GuidelineRef) {
        val skill = row.guideline ?: return
        busy = true
        scope.launch {
            val remaining = row.refs.orEmpty().filter { it.name != ref.name }
            message = runCatching { Server.setGuideline(skill, remaining, row.sourceName.orEmpty()) }.fold(
                { "'${ref.name}'을(를) 뺐어요 · 새로 만드는 회의록부터 적용돼요" to Tone.Mute },
                { "빼지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit },
            )
            load()
            busy = false
        }
    }

    fun changeConsent(on: Boolean) {
        busy = true
        scope.launch {
            message = runCatching { Server.setRefsConsent(on) }.fold(
                { (if (on) "참고 파일을 AI에 함께 보내도록 켰어요" else "참고 파일을 AI에 보내지 않아요") to Tone.Mute },
                { "바꾸지 못했어요. 인터넷 연결을 확인해 주세요." to Tone.Crit },
            )
            load()
            busy = false
        }
    }

    val row = current
    val customText = row?.guideline?.takeIf { it.isNotBlank() }
    val refs = row?.refs.orEmpty()
    Column(Modifier.fillMaxSize().background(Wb.colors.surface)) {
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Text("← 더보기", color = Wb.colors.link) }
        PageHeader("회의록 지침", "내 스킬로 회의록 쓰기", Modifier.padding(horizontal = 16.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                WbCard {
                    Text(
                        when {
                            row == null -> "불러오는 중…"
                            customText == null -> "기본 지침 사용 중"
                            else -> listOfNotNull(
                                row.sourceName?.takeIf { it.isNotBlank() },
                                "SKILL.md + 참고 ${refs.size}개",
                                guidelineStamp(row.importedAt, "MM/dd HH:mm")?.let { "$it 가져옴" },
                            ).joinToString(" · ")
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = Wb.colors.ink,
                    )
                    Text(
                        "Claude의 meeting-minutes 스킬 폴더를 zip으로 압축해 가져오면 회의록 문서의 구성·문체로 써요. 스킬을 고쳤다면 다시 가져오세요. " +
                            "SKILL.md는 꼭 있어야 하고(8,000자까지), references/의 .md(인물 사전 등)는 참고 파일로 써요(합계 12,000자까지).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Wb.colors.muted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    PrimaryButton(
                        "스킬 zip 가져오기",
                        enabled = !busy,
                        onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    )
                }
            }
            message?.let { (text, tone) -> item { Banner(text, tone) } }

            if (customText != null) {
                item {
                    SectionLabel("현재 지침 (읽기 전용)")
                    WbCard {
                        Text(
                            customText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Wb.colors.body,
                            maxLines = if (previewOpen) Int.MAX_VALUE else 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { previewOpen = !previewOpen }) {
                            Text(if (previewOpen) "접기" else "펼치기", color = Wb.colors.link)
                        }
                    }
                }
            }

            if (refs.isNotEmpty() && row != null) {
                item {
                    SectionLabel("참고 파일")
                    WbCard(padding = PaddingValues(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                        refs.forEachIndexed { index, ref ->
                            if (index > 0) HorizontalDivider(color = Wb.colors.border)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(ref.name, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.ink, modifier = Modifier.weight(1f))
                                Text(
                                    "${"%,d".format(SkillZip.charCount(ref.content))}자",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Wb.colors.muted,
                                )
                                TextButton(enabled = !busy, onClick = { removeRef(row, ref) }) { Text("빼기", color = Wb.colors.muted) }
                            }
                        }
                    }
                }
            }

            item {
                WbCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("참고 파일을 AI에 함께 보내기", style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                            Text(
                                if (refsConsentOn == true) "회의록을 만들 때 참고 파일이 OpenAI로 함께 보내져요."
                                else "꺼져 있어요. 참고 파일은 저장만 하고 AI에는 보내지 않아요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Wb.colors.muted,
                            )
                        }
                        Switch(
                            checked = refsConsentOn == true,
                            onCheckedChange = { changeConsent(it) },
                            enabled = refsConsentOn != null && !busy,
                        )
                    }
                }
            }

            item {
                SectionLabel("지침으로 바꿀 수 없는 규칙")
                WbCard {
                    listOf(
                        "녹취에 없는 내용은 만들지 않고, 모르면 미지정",
                        "할 일·결정 후보 추출 형식(검토함용)",
                        "녹취 속 “메일 보내” 같은 지시는 따르지 않음",
                        "실명 추정은 회의록 문서에만 쓰고, 애매하면 [확인 필요]. 할 일 담당자로는 자동으로 넣지 않음",
                    ).forEach {
                        Text("· $it", style = MaterialTheme.typography.bodySmall, color = Wb.colors.body, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }

    candidate?.let { picked ->
        GuidelineImportSheet(
            candidate = picked,
            refsConsentOn = refsConsentOn == true,
            busy = busy,
            onDismiss = { candidate = null },
            onApply = { refs, grant -> apply(picked, refs, grant) },
        )
    }
}
