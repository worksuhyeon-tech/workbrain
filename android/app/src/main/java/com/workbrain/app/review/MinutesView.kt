package com.workbrain.app.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.MinutesDocument
import com.workbrain.app.data.MinutesTable
import com.workbrain.app.ui.PrimaryButton
import com.workbrain.app.ui.Wb
import com.workbrain.app.ui.WbCard

/**
 * 기록 상세의 '회의록 문서'(FR-30, 목업 S05 mview-doc). 문서가 없으면 '회의록 만들기' 버튼만 둔다(DEC-56).
 * making: 만드는 중(1~2분 걸려 진행 표시를 둔다)
 */
@Composable
fun MinutesPane(document: MinutesDocument?, making: Boolean, onMake: () -> Unit) {
    WbCard {
        if (document == null) {
            Text("아직 회의록이 없어요", style = MaterialTheme.typography.titleSmall, color = Wb.colors.ink)
            Text(
                "회의록 지침대로 회의 개요·핵심 요약·결정사항·후속 조치를 정리해요. 1~2분쯤 걸려요.",
                style = MaterialTheme.typography.bodySmall,
                color = Wb.colors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            if (making) MakingRow() else PrimaryButton("회의록 만들기", onClick = onMake, modifier = Modifier.fillMaxWidth())
            return@WbCard
        }
        Text("초안 — 검토 전", style = MaterialTheme.typography.labelMedium, color = Wb.colors.muted)
        if (document.title.isNotBlank()) {
            Text(document.title, style = MaterialTheme.typography.titleMedium, color = Wb.colors.ink, modifier = Modifier.padding(top = 4.dp))
        }
        document.sections.forEach { section ->
            Text(
                section.heading,
                style = MaterialTheme.typography.titleSmall,
                color = Wb.colors.ink,
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
            )
            HorizontalDivider(color = Wb.colors.border, modifier = Modifier.padding(bottom = 6.dp))
            section.lines.forEach { line ->
                Text(marked(line), style = MaterialTheme.typography.bodyMedium, color = Wb.colors.body, modifier = Modifier.padding(vertical = 2.dp))
            }
            section.table?.let { MinutesTableView(it) }
        }
    }
}

@Composable
private fun MakingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Wb.colors.primary)
        Spacer(Modifier.width(10.dp))
        Text("회의록 만드는 중 · 다 되면 알림으로 알려 드릴게요", style = MaterialTheme.typography.bodyMedium, color = Wb.colors.primary)
    }
}

/** 표는 화면보다 넓어서 옆으로 밀어 본다(목업 tbl-wrap). 번호 열만 좁게 */
@Composable
private fun MinutesTableView(table: MinutesTable) {
    val widths = table.columns.map { if (it == "번호") 44.dp else 160.dp }
    Column(
        Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())
            .border(1.dp, Wb.colors.border),
    ) {
        TableRow(table.columns, widths, header = true)
        table.rows.forEach { row ->
            HorizontalDivider(color = Wb.colors.border)
            TableRow(row, widths, header = false)
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, widths: List<Dp>, header: Boolean) {
    Row(
        Modifier.background(if (header) Wb.colors.surface else Wb.colors.card),
        horizontalArrangement = Arrangement.Start,
    ) {
        cells.forEachIndexed { i, cell ->
            Text(
                marked(cell),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                color = if (header) Wb.colors.ink else Wb.colors.body,
                modifier = Modifier.width(widths.getOrElse(i) { 160.dp }).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

// 사용자 회의록 스킬의 표기 규칙: [확인 필요]는 빨간 굵게, [미정]은 회색 굵게
@Composable
private fun marked(text: String): AnnotatedString {
    val crit = Wb.colors.crit
    val muted = Wb.colors.muted
    return buildAnnotatedString {
        var rest = text
        while (rest.isNotEmpty()) {
            val next = listOf("[확인 필요]", "[미정]").mapNotNull { tag -> rest.indexOf(tag).takeIf { it >= 0 }?.let { it to tag } }.minByOrNull { it.first }
            if (next == null) { append(rest); break }
            append(rest.substring(0, next.first))
            withStyle(SpanStyle(color = if (next.second == "[미정]") muted else crit, fontWeight = FontWeight.Bold)) { append(next.second) }
            rest = rest.substring(next.first + next.second.length)
        }
    }
}
