package com.workbrain.app.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.workbrain.app.ui.ControlShape
import com.workbrain.app.ui.Wb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * '언제 다시 볼까요?'(FR-28, DEC-63, 목업 snooze-menu): 내일 · 다음 주 월요일 · 날짜 고르기.
 * 고른 날 0시에 검토함 '오늘'로 다시 나온다. 미뤄도 근거 보관 기한은 늘지 않아(DEC-15) 그 전날까지만 고를 수 있다.
 * count: 미룰 후보 수. lastAllowed: 고를 수 있는 마지막 날. evidenceUntil: 고른 후보 중 가장 이른 근거 전사 보관 마지막 날
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeSheet(count: Int, lastAllowed: LocalDate, evidenceUntil: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val tomorrow = Snooze.today().plusDays(1)
    val monday = Snooze.nextMonday()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Wb.colors.card,
        scrimColor = Color(0x7309141E),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("언제 다시 볼까요?", style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink)
            Text(
                "후보 ${count}개를 그날 0시에 ‘오늘’로 다시 보여 드려요. 미뤄도 할 일은 바뀌지 않아요.",
                style = MaterialTheme.typography.bodySmall,
                color = Wb.colors.muted,
            )
            if (lastAllowed < tomorrow) {
                // 근거 전사가 내일 안에 지워지면 미룬 날에는 승인할 수 없다
                Text("근거 보관 기간이 곧 끝나 미룰 수 없어요. 오늘 검토해 주세요.", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.crit)
            } else {
                SnoozeOption("내일", Snooze.label(tomorrow)) { onPick(tomorrow) }
                // 일요일에는 다음 주 월요일이 곧 내일이라 한 번만 보인다(한 주는 월요일 시작, DEC-31)
                if (monday != tomorrow && monday <= lastAllowed) {
                    SnoozeOption("다음 주 월요일", Snooze.label(monday)) { onPick(monday) }
                }
                SnoozeOption("날짜 고르기", null) { picking = true }
            }
            evidenceUntil?.let {
                Text(
                    "근거 보관 ${Snooze.label(it)}까지 · 미뤄도 늘지 않고, 지나면 승인할 수 없어요",
                    style = MaterialTheme.typography.bodySmall,
                    color = Wb.colors.muted,
                )
            }
        }
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            // 날짜 고르기 칸은 UTC 0시 기준 밀리초로 날짜를 준다
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return d >= tomorrow && d <= lastAllowed
                }
                override fun isSelectableYear(year: Int): Boolean = year in tomorrow.year..lastAllowed.year
            },
        )
        val chosen = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(enabled = chosen != null, onClick = { picking = false; chosen?.let(onPick) }) {
                    Text(chosen?.let { "${Snooze.label(it)}까지 미루기" } ?: "미루기", color = Wb.colors.primary)
                }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("취소") } },
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

/** 미룰 날 한 줄: 왼쪽 이름, 오른쪽 날짜(목업 snooze-menu 버튼) */
@Composable
private fun SnoozeOption(name: String, date: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ControlShape,
        color = Wb.colors.surface,
        border = BorderStroke(1.dp, Wb.colors.border),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = Wb.colors.ink, modifier = Modifier.weight(1f))
            date?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Wb.colors.muted) }
            if (date == null) Text("›", style = MaterialTheme.typography.bodyLarge, color = Wb.colors.muted)
        }
    }
}
