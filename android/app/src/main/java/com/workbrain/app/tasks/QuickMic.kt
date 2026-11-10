package com.workbrain.app.tasks

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.workbrain.app.record.ChosenProject
import com.workbrain.app.record.RecorderLaunch
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons

/*
 * 빠른 입력 마이크(DEC-89): 짧게 누르면 말로 넣기(기기 음성 인식 → 입력 줄, [추가]를 눌러야 저장),
 * 길게 누르면 녹음 메모(녹음 화면을 열고 바로 시작, 올리기 창 종류 처음 값 '혼자 메모').
 * 길게 누르는 시간은 시스템 기본값(combinedClickable)을 따른다. 아래 띠(QuickAddDock)와 상세 입력 줄(QuickAddBar)이 같이 쓴다
 */

private const val MIC_DENIED = "마이크 권한이 없어 말로 넣을 수 없어요. 설정 › 앱 › WorkBrain › 권한에서 마이크를 허용해 주세요."

/** recordProject: 길게 눌러 녹음할 때 미리 고를 프로젝트(프로젝트 상세 입력 줄), 없으면 null */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MicButton(quick: QuickAdd, recordProject: ChosenProject?) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // 권한을 거부해도 안내만 띄우고 입력 줄은 그대로 쓴다
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) VoiceInput.start(context, quick.draftKey) else VoiceInput.fail(quick.draftKey, MIC_DENIED)
    }
    val onTap = {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when {
            // 듣는 중에 다시 누르면 멈추고 그때까지 들은 것으로 마무리한다
            quick.listening -> VoiceInput.stop()
            hasMic -> VoiceInput.start(context, quick.draftKey)
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val onLongPress = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        VoiceInput.cancel()
        RecorderLaunch.open(start = true, kind = "memo", project = recordProject)
    }
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (quick.listening) Wb.colors.crit else Color.Transparent)
            .combinedClickable(
                onClickLabel = if (quick.listening) "말로 넣기 멈추기" else "말로 넣기",
                onLongClickLabel = "녹음 메모 시작",
                onLongClick = onLongPress,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            WbIcons.Mic,
            contentDescription = "마이크",
            tint = if (quick.listening) Color.White else Wb.colors.body,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 말로 넣기 상태 줄: 듣는 중(빨간 점 + 중간 글) · 안내(권한 거부·못 들음은 빨강, 기본 인식 안내는 회색). 보일 것이 없으면 자리도 차지하지 않는다 */
@Composable
fun VoiceStatus(quick: QuickAdd, modifier: Modifier = Modifier) {
    val message = quick.voiceMessage
    if (!quick.listening && message == null) return
    // 화면 읽기 프로그램도 듣는 중·안내가 바뀔 때마다 읽는다
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (quick.listening) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Wb.colors.crit, CircleShape))
                Text(
                    quick.partial.ifBlank { "듣고 있어요 · 말을 마치면 저절로 멈춰요" },
                    fontSize = 13.sp,
                    color = if (quick.partial.isBlank()) Wb.colors.muted else Wb.colors.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        message?.let {
            Text(it.text, fontSize = 12.sp, color = if (it.isError) Wb.colors.crit else Wb.colors.muted, modifier = Modifier.padding(top = if (quick.listening) 4.dp else 0.dp))
        }
    }
}
