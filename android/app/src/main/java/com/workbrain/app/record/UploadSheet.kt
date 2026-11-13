package com.workbrain.app.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import com.workbrain.app.ui.MAX_TITLE
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.SegmentPills
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Wb
import com.workbrain.app.data.Server
import kotlinx.coroutines.withTimeoutOrNull

private val KINDS = listOf("meeting" to "회의", "call" to "통화", "memo" to "혼자 메모")

/**
 * 올리기 전 확인(목업 .sheet): 제목(Q2), 기록 종류, 처음 한 번 외부 전사·분석 동의(FR-17).
 * 예전의 '클라우드에 올려도 되나요?'(M11)는 묻지 않는다(DEC-106, 2026-11-13 사용자 결정).
 * needConsent가 null이면 서버에 동의 여부를 묻는 중이라 '올리기'를 잠시 막는다.
 * onConfirm(title, kind, consentGiven, attendees) — title은 앞뒤 공백을 뗀 값, 비우면 원래 제목
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSheet(
    title: String,
    needConsent: Boolean?,
    initialAttendees: List<String>,
    initialKind: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, kind: String, consentGiven: Boolean, attendees: List<String>) -> Unit,
) {
    var titleText by remember { mutableStateOf(title) }
    var kind by remember { mutableStateOf(initialKind) }
    var attendees by remember { mutableStateOf(initialAttendees) }
    var consent by remember { mutableStateOf(false) }
    val canSend = needConsent == false || (needConsent == true && consent)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x7309141E),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 올리기 전에 제목을 고치면 서버 기록·녹음 목록·회의록에 같은 제목으로 남는다(메모 v1.2 8번)
            Text("제목", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it.take(MAX_TITLE) },
                singleLine = true,
                placeholder = { Text("예: 사고접수 개편 2차 범위 회의") },
                shape = ControlShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text("어떤 녹음인가요?", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
            SegmentPills(KINDS, kind) { kind = it }
            Text(
                "메모는 '제가 할게요'를 내 할 일로, 회의·통화는 '담당 확인'으로 정리해요.",
                style = MaterialTheme.typography.bodySmall,
                color = Wb.colors.muted,
            )

            if (kind != "memo") {
                Spacer(Modifier.height(8.dp))
                Text("참석자 (선택)", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
                Text(
                    "이름을 넣으면 받아쓰기에서 잘못 들린 이름을 바로잡아요. 명단만 보고 담당자를 정하지는 않아요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Wb.colors.muted,
                )
                AttendeesEditor(attendees, onChange = { attendees = it })
            }

            if (needConsent == null) {
                Text("외부 AI 처리 동의 여부를 확인하고 있어요…", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
            } else if (needConsent) {
                Spacer(Modifier.height(8.dp))
                Text("외부 AI 처리 동의 (처음 한 번)", style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                Text(
                    "음성은 OpenAI 전사(gpt-transcribe)로, 받아쓴 글은 OpenAI 분석(gpt-6-luna)으로 보내 회의록·할 일 후보를 만들어요. " +
                        "내가 승인하기 전까지 아무것도 바뀌지 않아요. 음성은 30일 뒤 자동으로 지워요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Wb.colors.body,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it }, colors = CheckboxDefaults.colors(checkedColor = Wb.colors.primary))
                    Text("동의해요", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.ink)
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("취소", onClick = onDismiss, modifier = Modifier.height(44.dp))
                PrimaryButton(
                    "올리기",
                    enabled = canSend,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val finalTitle = titleText.trim().ifEmpty { title }
                        onConfirm(finalTitle, kind, consent, if (kind == "memo") emptyList() else attendees)
                    },
                )
            }
        }
    }
}

/** 녹음 종류 처음 값: 통화 녹음 공유나 '통화'가 들어간 파일 이름이면 '통화', 나머지는 '회의' */
private fun defaultKind(s: RecordingSession) = if (s.channel == "call-share" || "통화" in s.title) "call" else "meeting"

/**
 * 올리기 창 앞뒤 처리: 동의 여부 확인 → 창 → 제목 저장 → 백그라운드 올리기 시작.
 * 기록 탭(가져온 파일·[올리기])과 녹음 화면(정지 뒤)이 같이 쓴다(DEC-89).
 * initialKind: 종류 처음 값(마이크 길게로 시작한 녹음은 '혼자 메모'), null이면 defaultKind. onStarted: 올리기를 맡긴 뒤
 */
@Composable
fun UploadSheetHost(target: RecordingSession, initialKind: String?, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    // 창은 바로 열고 동의 여부는 창 안에서 기다린다. 서버 요청 한도(150초)를 그대로 기다리면 창이 안 뜬 것처럼 보이므로
    // 5초 안에 답이 없거나 실패하면 '동의 필요'로 보고 동의 칸을 보여 준다
    var needConsent by remember(target.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(target.id) {
        needConsent = runCatching { withTimeoutOrNull(5_000) { !Server.hasConsent() } }.getOrNull() ?: true
    }
    UploadSheet(
        title = target.title,
        needConsent = needConsent,
        initialAttendees = target.attendees,
        initialKind = initialKind ?: defaultKind(target),
        onDismiss = onDismiss,
        onConfirm = { title, kind, consentGiven, attendees ->
            // 올리는 작업이 기기 메타에서 제목을 읽어 서버 기록을 만들므로, 시작 전에 먼저 적어 둔다
            if (title != target.title) RecordingStore.setTitle(RecordingStore.dir(context, target.id), title)
            UploadManager.start(context, target, kind, consentGiven, attendees)
            onStarted()
        },
    )
}
