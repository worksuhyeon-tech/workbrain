package com.workbrain.app.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
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
 * madeWith: 이 문서를 만들 때 쓴 지침 안내(머리 줄). outdated: 그 뒤 지침이 바뀌어 새 지침으로 다시 만들 수 있음
 */
@Composable
fun MinutesPane(document: MinutesDocument?, making: Boolean, madeWith: String, outdated: Boolean, onMake: () -> Unit) {
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
        DocHeader(madeWith, making, outdated, onMake)
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

/**
 * 문서 머리 한 줄: 왼쪽은 상태·지침 출처, 오른쪽은 다시 만들기 글자 버튼.
 * 안내를 여러 줄로 쌓으면 본문이 화면 중간부터 시작해서 한 줄로 줄였다.
 * 다시 만들기는 항상 둔다: 지침 번호는 그대로인데 참고 파일 동의만 바꾼 경우에도 다시 만들 수 있어야 해서다.
 * 지침이 바뀐 문서만 눈에 띄게(파란 굵은 글씨) 알린다. 다시 만들어도 새 할 일 후보는 생기지 않는다
 */
@Composable
private fun DocHeader(madeWith: String, making: Boolean, outdated: Boolean, onMake: () -> Unit) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("초안 · $madeWith", style = MaterialTheme.typography.labelMedium, color = Wb.colors.muted, modifier = Modifier.weight(1f))
        if (making) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Wb.colors.primary)
            Spacer(Modifier.width(6.dp))
            Text("다시 만드는 중", style = MaterialTheme.typography.labelMedium, color = Wb.colors.primary)
        } else {
            // 글자만 있는 버튼은 누르는 영역이 좁아 실기기에서 눌리지 않은 적이 있어 최소 크기를 둔다
            TextButton(
                onClick = onMake,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    if (outdated) "새 지침으로 다시 만들기 ›" else "다시 만들기",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (outdated) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (outdated) Wb.colors.link else Wb.colors.muted,
                )
            }
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

/**
 * 표는 화면 폭에 맞춰 열을 나눈다(펼친 화면). 최소 폭으로도 안 들어가면 최소 폭 그대로 두고 옆으로 밀어 본다(접은 화면, 목업 tbl-wrap).
 * 가져온 지침에 따라 열 이름이 바뀌어도 같은 규칙이 되도록, 열 이름이 아니라 칸의 글자 양으로 너비를 정한다.
 */
@Composable
private fun MinutesTableView(table: MinutesTable) {
    if (table.columns.isEmpty()) return
    val oneLineWidths = rememberOneLineWidths(table)
    BoxWithConstraints(Modifier.padding(top = 6.dp).fillMaxWidth()) {
        val layout = remember(table, oneLineWidths, maxWidth) { tableLayout(table, oneLineWidths, maxWidth) }
        // 접기·펼치기로 스크롤 여부가 바뀌어도 상태 순서가 같도록 항상 만든다
        val scrollState = rememberScrollState()
        val scroll = if (layout.scrolls) Modifier.horizontalScroll(scrollState) else Modifier
        Column(scroll.border(1.dp, Wb.colors.border)) {
            TableRow(table.columns, layout.widths, header = true)
            table.rows.forEach { row ->
                HorizontalDivider(color = Wb.colors.border)
                TableRow(row, layout.widths, header = false)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, widths: List<Dp>, header: Boolean) {
    val line = Wb.colors.border
    // 한 줄의 칸 높이를 맞춰야 세로선이 줄 끝까지 이어진다
    Row(Modifier.height(IntrinsicSize.Min).background(if (header) Wb.colors.surface else Wb.colors.card)) {
        widths.forEachIndexed { i, width ->
            val isLast = i == widths.lastIndex
            Text(
                marked(cells.getOrElse(i) { "" }),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                color = if (header) Wb.colors.ink else Wb.colors.body,
                modifier = Modifier.width(width).fillMaxHeight()
                    .drawBehind {
                        if (!isLast) drawLine(line, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                    }
                    .padding(horizontal = CellPaddingH, vertical = 8.dp),
            )
        }
    }
}

private data class TableLayout(val widths: List<Dp>, val scrolls: Boolean)

private val CellPaddingH = 10.dp
private val LongColumnMin = 160.dp // 한 줄에 안 들어가는 설명 열의 최소 폭

/** 열마다 가장 긴 칸(제목 줄 포함)을 줄바꿈 없이 그렸을 때의 폭. 실제 글꼴로 재야 날짜·이름이 중간에서 끊기지 않는다 */
@Composable
private fun rememberOneLineWidths(table: MinutesTable): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val body = MaterialTheme.typography.bodyMedium
    return remember(table, density, body) {
        table.columns.indices.map { i ->
            val header = measurer.measure(table.columns[i], body.copy(fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false).size.width
            val cells = table.rows.mapNotNull { it.getOrNull(i) }
                .maxOfOrNull { measurer.measure(it, body, maxLines = 1, softWrap = false).size.width } ?: 0
            with(density) { maxOf(header, cells).toDp() } + CellPaddingH * 2 + 6.dp // 세로선·굵게 표시되는 [미정]·반올림 여유
        }
    }
}

/**
 * 열 너비 계산. 가장 긴 칸이 한 줄에 들어가는 열(번호·담당자·기한)은 그 폭으로 고정하고,
 * 남는 폭은 설명 열에 칸 평균 글자 수 비율로 나눈다. 설명 열이 최소 폭보다 좁아지면 최소 폭으로 고정하고 다시 나눈다
 */
private fun tableLayout(table: MinutesTable, oneLineWidths: List<Dp>, available: Dp): TableLayout {
    val count = table.columns.size
    val fitsOneLine = oneLineWidths.map { it <= LongColumnMin }
    val minWidths = oneLineWidths.mapIndexed { i, w -> if (fitsOneLine[i]) w else LongColumnMin }
    val minTotal = minWidths.fold(0.dp) { sum, w -> sum + w }
    if (minTotal > available) return TableLayout(minWidths, scrolls = true)
    // 짧은 열만 있는 표도 다른 표와 같은 폭이 되도록 비율대로 늘린다
    if (fitsOneLine.all { it }) return TableLayout(minWidths.map { it * (available / minTotal) }, scrolls = false)

    val averageChars = (0 until count).map { i ->
        val cells = table.rows.mapNotNull { it.getOrNull(i) }
        if (cells.isEmpty()) table.columns[i].length.toFloat() else cells.map { it.length }.average().toFloat()
    }
    // 아주 긴 열 하나가 폭을 다 가져가지 않도록 비중에 상한을 둔다
    val weights = averageChars.map { it.coerceIn(4f, 60f) }
    val widths = minWidths.toMutableList()
    val fixed = fitsOneLine.toMutableList()
    while (true) {
        val flexible = (0 until count).filterNot { fixed[it] }
        if (flexible.isEmpty()) break
        val free = available - (0 until count).filter { fixed[it] }.fold(0.dp) { sum, i -> sum + widths[i] }
        val totalWeight = flexible.sumOf { weights[it].toDouble() }.toFloat()
        val tooNarrow = flexible.filter { free * (weights[it] / totalWeight) < minWidths[it] }
        if (tooNarrow.isEmpty()) {
            flexible.forEach { widths[it] = free * (weights[it] / totalWeight) }
            break
        }
        tooNarrow.forEach { fixed[it] = true } // 너비는 이미 최소 폭
    }
    return TableLayout(widths, scrolls = false)
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
