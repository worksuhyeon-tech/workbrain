package com.workbrain.app.tasks

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.workbrain.app.reminders.ReminderScheduler
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.PillShape
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbIcons

/*
 * 미리 알림 고르기(DEC-108, Design §3.19, 목업 quickadd-android .rem-row·.rem-menu):
 * 빠른 입력 기한 칸과 할 일 서랍이 같이 쓰는 줄 하나 + 삼성 리마인더처럼 떠 있는 목록 + 직접 설정 작은 창.
 */

/**
 * 🔔 줄: 지금 값(또는 '미리 알림 없음')과 둘째 줄(울리는 때). 누르면 목록. enabled가 아니면(기한 없음·모호) 흐리게 안내만.
 * 처음 알림을 정할 때 알림 권한·'알람 및 리마인더' 허용을 한 번 묻는다
 */
@Composable
fun RemindRow(value: Int?, enabled: Boolean, whenText: String?, onPick: (Int?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val askPermission = rememberRemindPermission()
    fun pick(v: Int?) {
        onPick(v)
        if (v != null) askPermission()
    }
    Box(modifier.fillMaxWidth()) {
        PickerRow(
            WbIcons.Bell, if (enabled) remindLabel(value) else "기한을 정하면 미리 알림을 넣을 수 있어요", whenText,
            active = value != null, enabled = enabled, clickLabel = "미리 알림 고르기",
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp), containerColor = Wb.colors.card) {
            REMIND_PRESETS.forEach { v -> PickMenuItem(remindLabel(v), on = v == value) { open = false; pick(v) } }
            val customOn = value != null && value !in REMIND_PRESETS
            PickMenuItem(if (customOn) "직접 설정 · ${remindLabel(value)}" else "직접 설정…", on = customOn) { open = false; custom = true }
        }
    }
    if (custom) CustomRemindDialog(onDismiss = { custom = false }) { v -> custom = false; pick(v) }
}

/** 직접 설정: 숫자 + [분·시간·일], 1분~30일 */
@Composable
private fun CustomRemindDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(RemindUnit.HOUR) }
    val minutes = customRemind(amount.toIntOrNull(), unit)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Wb.colors.card,
        title = { Text("미리 알림 직접 설정", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amount, onValueChange = { v -> amount = v.filter(Char::isDigit).take(5) },
                    singleLine = true, placeholder = { Text("예: 2") }, shape = ControlShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text("${unit.label} 전") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemindUnit.entries.forEach { u -> ChoicePill(u.label, on = u == unit) { unit = u } }
                }
                if (amount.isNotEmpty() && minutes == null) Text("1분부터 30일까지 정할 수 있어요", fontSize = 12.sp, color = Wb.colors.crit)
            }
        },
        confirmButton = { TextButton(onClick = { minutes?.let(onPick) }, enabled = minutes != null) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

// ── 미리 알림·반복 줄이 같이 쓰는 모양(목업 .rem-row·.rem-menu) ──

/** 기한 칸·서랍의 줄: 아이콘 · 값(정했으면 진하게) · 둘째 줄 · ›. enabled가 아니면 흐리게 안내만 */
@Composable
internal fun PickerRow(
    icon: ImageVector, text: String, sub: String?, active: Boolean, enabled: Boolean, clickLabel: String, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = clickLabel, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Wb.colors.body, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(text, fontSize = 15.sp, color = if (enabled && active) Wb.colors.ink else Wb.colors.muted)
            if (enabled) sub?.let { Text(it, fontSize = 12.sp, color = Wb.colors.muted) }
        }
        if (enabled) Text("›", fontSize = 18.sp, color = Wb.colors.muted, modifier = Modifier.padding(end = 4.dp))
    }
}

/** 떠 있는 목록 한 줄: 고른 것은 파랗고 굵게 + ✓ */
@Composable
internal fun PickMenuItem(label: String, on: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Wb.colors.primary else Wb.colors.ink) },
        trailingIcon = if (on) ({ Icon(WbIcons.Check, contentDescription = "고름", tint = Wb.colors.primary, modifier = Modifier.size(18.dp)) }) else null,
        onClick = onClick,
    )
}

/** 직접 설정 창의 고르기 알약(단위·요일) */
@Composable
internal fun ChoicePill(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 40.dp).defaultMinSize(minWidth = 40.dp).clip(PillShape)
            .border(1.dp, if (on) Wb.colors.primary else Wb.colors.borderStrong, PillShape)
            .clickable(role = Role.Checkbox, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Wb.colors.primary else Wb.colors.body) }
}

/**
 * 처음 알림을 정할 때 한 번: 알림 권한(Android 13+)을 묻고, 정확한 시각 예약이 꺼져 있으면 '알람 및 리마인더' 허용을 안내한다.
 * 허용하지 않아도 알림은 저장되고 몇 분 늦게 울릴 수 있다(Design §3.19)
 */
@Composable
fun rememberRemindPermission(): () -> Unit {
    val context = LocalContext.current
    var askExact by remember { mutableStateOf(false) }
    // 알림 권한을 물은 뒤(허용했든 안 했든) 이어서 정확한 시각 허용을 안내한다
    fun checkExact() {
        if (!ReminderScheduler.canExact(context) && !exactAsked(context)) {
            markExactAsked(context)
            askExact = true
        }
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checkExact() }
    if (askExact) {
        AlertDialog(
            onDismissRequest = { askExact = false },
            containerColor = Wb.colors.card,
            title = { Text("정확한 시각에 울리려면", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink) },
            text = { Text("설정에서 WorkBrain의 '알람 및 리마인더'를 허용해 주세요. 허용하지 않으면 알림이 몇 분 늦게 울릴 수 있어요.", fontSize = 14.sp, color = Wb.colors.body) },
            confirmButton = {
                TextButton(onClick = {
                    askExact = false
                    openExactAlarmSettings(context)
                }) { Text("설정 열기") }
            },
            dismissButton = { TextButton(onClick = { askExact = false }) { Text("나중에") } },
        )
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        else checkExact()
    }
}

private const val PREFS = "reminders_ui"
private const val EXACT_ASKED = "exact_asked"

private fun exactAsked(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(EXACT_ASKED, false)

private fun markExactAsked(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(EXACT_ASKED, true).apply()

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
