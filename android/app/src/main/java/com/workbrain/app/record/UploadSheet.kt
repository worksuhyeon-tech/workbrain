package com.workbrain.app.record

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.GlassIcon
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.WbIcons
import androidx.compose.ui.graphics.vector.ImageVector
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.SegmentPills
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Wb

private val KINDS = listOf("meeting" to "회의", "call" to "통화", "memo" to "혼자 메모")

// 앱이 내용을 보고 자동으로 판단하지 않고 사용자가 고른다(M11). 금지·모름이면 올리지 않는다
private data class PermissionOption(val value: String, val glyph: ImageVector, val tint: GlassTint, val title: String, val desc: String)
private val PERMISSIONS = listOf(
    PermissionOption("personal", WbIcons.Person, GlassTint.Purple, "개인 자료", "내 메모·개인 통화"),
    PermissionOption("sample", WbIcons.Flask, GlassTint.Green, "샘플·시험용", "테스트용으로 만든 녹음"),
    PermissionOption("company-approved", WbIcons.Building, GlassTint.Blue, "회사 자료 (외부 저장 허용 확인됨)", "허용 범위를 확인한 업무 자료"),
    PermissionOption("deny", WbIcons.Ban, GlassTint.Red, "허용 안 됨 · 잘 모르겠음", "올리지 않고 이 기기에만 보관해요"),
)

/**
 * 올리기 전 확인(목업 .sheet): 기록 종류, 클라우드 저장 허용(M11), 처음 한 번 외부 전사·분석 동의(FR-17).
 * onConfirm(kind, permission, consentGiven)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSheet(
    title: String,
    needConsent: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (kind: String, permission: String, consentGiven: Boolean) -> Unit,
) {
    var kind by remember { mutableStateOf("meeting") }
    var permission by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf(false) }
    val canSend = permission != null && permission != "deny" && (!needConsent || consent)

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
            Text(title, style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
            Text("어떤 녹음인가요?", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
            SegmentPills(KINDS, kind) { kind = it }
            Text(
                "메모는 '제가 할게요'를 내 할 일로, 회의·통화는 '담당 확인'으로 정리해요.",
                style = MaterialTheme.typography.bodySmall,
                color = Wb.colors.muted,
            )

            Spacer(Modifier.height(8.dp))
            Text("이 자료를 클라우드에 올려도 되나요?", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
            Text("앱이 내용을 보고 자동으로 판단하지 않아요. 직접 골라 주세요.", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
            PERMISSIONS.forEach { opt -> PermissionCard(opt, selected = permission == opt.value) { permission = opt.value } }

            if (needConsent) {
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
                    if (permission == "deny") "올리지 않아요" else "올리기",
                    enabled = canSend,
                    modifier = Modifier.weight(1f),
                    onClick = { permission?.let { onConfirm(kind, it, consent) } },
                )
            }
        }
    }
}

/** 목업 .sheet .opt: 아이콘 + 굵은 제목 + 설명. 고르면 파란 테두리, '허용 안 됨'은 붉은 테두리 */
@Composable
private fun PermissionCard(opt: PermissionOption, selected: Boolean, onClick: () -> Unit) {
    val isDeny = opt.value == "deny"
    val borderColor = when {
        selected && isDeny -> Wb.colors.crit
        selected -> Wb.colors.primary
        isDeny -> Wb.colors.crit.copy(alpha = 0.35f)
        else -> Wb.colors.border
    }
    Surface(
        onClick = onClick,
        shape = ControlShape,
        color = if (selected) (if (isDeny) Wb.colors.crit else Wb.colors.primary).copy(alpha = 0.06f) else Wb.colors.card,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIcon(opt.glyph, opt.tint, size = 36.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(opt.title, style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
                Text(opt.desc, style = MaterialTheme.typography.bodySmall, color = Wb.colors.body)
            }
        }
    }
}
