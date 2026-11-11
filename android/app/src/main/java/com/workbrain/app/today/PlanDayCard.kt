package com.workbrain.app.today

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbrain.app.data.PlanDates
import com.workbrain.app.data.PlanKind
import com.workbrain.app.ui.GlassIcon
import com.workbrain.app.ui.GlassTint
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.SecondaryButton
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard
import com.workbrain.app.ui.WbIcons
import java.time.LocalDate

/**
 * 오늘 탭 화요일 카드(FR-33, DEC-87): 매주 화요일 날짜 줄 바로 아래에 '주간 업무계획 만드는 날'.
 * 그달 마지막 화요일이면 주간·월간 둘 다([주간 ›][월간 ›]). ×를 누르면 그날은 이 기기에서 숨긴다(다음 화요일에 다시 뜸).
 * 버튼은 업무계획 화면을 열기만 하고, 만들기는 그 화면의 [업무계획 만들기]로 한다(DEC-95 ③)
 */
@Composable
fun PlanDayCard(today: LocalDate, onOpen: (PlanKind) -> Unit, modifier: Modifier = Modifier) {
    if (!PlanDates.isPlanDay(today)) return
    val context = LocalContext.current
    var hidden by remember(today) { mutableStateOf(PlanCardPrefs.hiddenOn(context) == today.toString()) }
    if (hidden) return
    val monthly = PlanDates.isMonthlyDay(today)

    WbCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            GlassIcon(WbIcons.Document, GlassTint.Blue)
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
                Text(
                    if (monthly) "주간·월간 업무계획 만드는 날" else "주간 업무계획 만드는 날",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Wb.colors.ink,
                )
                Text(
                    if (monthly) "이번 주·이번 달 실적과 다음 계획 표 초안" else "이번 주 실적·다음 주 계획 표 초안",
                    fontSize = 13.sp, color = Wb.colors.muted, modifier = Modifier.padding(top = 2.dp),
                )
            }
            TextButton(
                onClick = { PlanCardPrefs.hide(context, today); hidden = true },
                modifier = Modifier.semantics { contentDescription = "오늘은 숨기기" },
            ) { Text("×", fontSize = 20.sp, color = Wb.colors.muted) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (monthly) {
                SecondaryButton("주간 ›", onClick = { onOpen(PlanKind.WEEKLY) })
                PrimaryButton("월간 ›", onClick = { onOpen(PlanKind.MONTHLY) })
            } else {
                PrimaryButton("열기 ›", onClick = { onOpen(PlanKind.WEEKLY) })
            }
        }
    }
}

/** 화요일 카드를 숨긴 날(이 기기만). 날짜가 바뀌면 저절로 다시 보인다 */
private object PlanCardPrefs {
    private const val PREFS = "today_plan_card"
    private const val KEY = "hidden_on"

    fun hiddenOn(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun hide(context: Context, day: LocalDate) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, day.toString()).apply()
}
