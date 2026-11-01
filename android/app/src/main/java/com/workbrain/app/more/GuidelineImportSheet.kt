package com.workbrain.app.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.GuidelineRef
import com.workbrain.app.ui.Banner
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Tone
import com.workbrain.app.ui.Wb

/**
 * 가져오기 확인 시트(목업 U6): 적용 전에 무엇이 들어가는지 글자 수로 보여 주고, 참고 파일은 골라서 뺄 수 있다.
 * 취소하거나 상한을 넘기면 기존 지침은 그대로다. refsConsentOn: 참고 파일 전송 동의가 이미 켜져 있는지
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidelineImportSheet(
    candidate: SkillZip.Candidate,
    refsConsentOn: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onApply: (refs: List<GuidelineRef>, grantRefsConsent: Boolean) -> Unit,
) {
    var pickedNames by remember { mutableStateOf(candidate.refs.map { it.name }.toSet()) }
    // 외부(OpenAI) 전송 동의라 기본은 꺼 둔다. 켜는 것은 사용자가 직접 체크할 때만
    var consent by remember { mutableStateOf(false) }

    val picked = candidate.refs.filter { it.name in pickedNames }
    val skillChars = SkillZip.charCount(candidate.skill)
    val totalChars = skillChars + picked.sumOf { SkillZip.charCount(it.content) }
    val overReason = when {
        skillChars > SkillZip.SKILL_LIMIT -> "SKILL.md가 ${"%,d".format(SkillZip.SKILL_LIMIT)}자를 넘어서 적용할 수 없어요."
        totalChars > SkillZip.TOTAL_LIMIT -> "합계가 ${"%,d".format(SkillZip.TOTAL_LIMIT)}자를 넘어요. 참고 파일 체크를 줄여 보세요."
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x7309141E),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        ) {
            Text("이 지침으로 바꿀까요?", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
            Text(candidate.sourceName, style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted, modifier = Modifier.padding(top = 2.dp))
            Text(
                "적용하면 지금 지침과 참고 파일이 통째로 바뀌어요. 이미 만든 회의록은 그대로예요.",
                style = MaterialTheme.typography.bodySmall,
                color = Wb.colors.muted,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )

            // SKILL.md는 지침의 본체라 빼면 가져올 이유가 없다. 체크 고정
            FileCheckRow("SKILL.md", skillChars, checked = true, enabled = false, onChange = {})
            candidate.refs.forEach { ref ->
                HorizontalDivider(color = Wb.colors.border)
                FileCheckRow(ref.name, SkillZip.charCount(ref.content), checked = ref.name in pickedNames, enabled = !busy) { on ->
                    pickedNames = if (on) pickedNames + ref.name else pickedNames - ref.name
                }
            }
            HorizontalDivider(color = Wb.colors.border)

            Text(
                "합계 ${"%,d".format(totalChars)} / ${"%,d".format(SkillZip.TOTAL_LIMIT)}자 · SKILL.md ${"%,d".format(skillChars)} / ${"%,d".format(SkillZip.SKILL_LIMIT)}자",
                style = MaterialTheme.typography.labelLarge,
                color = if (overReason != null) Wb.colors.crit else Wb.colors.muted,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (candidate.ignoredCount > 0) {
                Text(
                    "쓰지 않는 파일 ${candidate.ignoredCount}개는 무시했어요 (assets 등)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Wb.colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            overReason?.let { Banner(it, Tone.Crit, Modifier.padding(top = 10.dp)) }

            if (picked.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = consent,
                        onCheckedChange = { consent = it },
                        enabled = !busy,
                        colors = CheckboxDefaults.colors(uncheckedColor = Wb.colors.borderStrong),
                    )
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(
                            "인물 사전 등 참고 파일은 회의록을 만들 때 OpenAI로 함께 보내져요. 보내도 좋아요",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Wb.colors.body,
                        )
                        Text(
                            if (refsConsentOn) "지금은 전송이 켜져 있어요. 끄려면 지침 화면의 스위치를 쓰세요."
                            else "체크하지 않으면 참고 파일은 저장만 하고 AI에는 보내지 않아요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Wb.colors.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("취소", enabled = !busy, onClick = onDismiss)
                PrimaryButton(
                    if (busy) "바꾸는 중…" else "적용",
                    enabled = !busy && overReason == null,
                    modifier = Modifier.weight(1f),
                    onClick = { onApply(picked, consent && picked.isNotEmpty()) },
                )
            }
        }
    }
}

@Composable
private fun FileCheckRow(name: String, chars: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(uncheckedColor = Wb.colors.borderStrong),
        )
        Text(name, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.ink, modifier = Modifier.weight(1f))
        Text("${"%,d".format(chars)}자", style = MaterialTheme.typography.bodySmall, color = Wb.colors.muted)
    }
}
