package com.workbrain.app.record

import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 받아쓴 글 가져오기(DEC-53): 클로바노트·삼성 음성 녹음 txt에서 말한 문장만 꺼낸다.
 * 클로바노트 형식: 1줄 제목, 2줄 "2026.10.08 목 오후 3:06 ・ 53분 38초", 3줄 작성자, 이후 "참석자 1 00:00" 줄 + 말한 문장 줄.
 * 삼성 음성 녹음 형식: 1줄 "AI로 생성된 콘텐츠입니다", 다음 글 줄이 제목, 이후 "발화자 1  (00:03)" 줄 + 말한 문장 줄(날짜·길이 없음).
 * 본문(body)에서는 화자 표시·시각 줄을 뗀다. 익명 화자 번호로 담당을 추정하면 안 되고(NFR-02·DEC-27), 앱 녹음 받아쓰기와 같은 입력이 되게 한다.
 * 화자·시각은 전문 보기 화면에만 쓰도록 turns로 따로 넘긴다(DEC-54).
 */
object TranscriptText {
    /** 한 사람이 이어서 말한 차례. text는 여러 줄일 수 있다 */
    @Serializable data class Turn(val speaker: String?, val offsetMs: Long?, val text: String)

    data class Parsed(val title: String?, val startedAt: Long?, val durationMs: Long?, val body: String, val turns: List<Turn> = emptyList())

    // "참석자 1 00:23", "김수현 1:02:03", 삼성 "발화자 1  (00:03)"
    private val SPEAKER_LINE = Regex("""^(.{1,30}?)\s+\(?((?:\d{1,2}:)?\d{1,2}:\d{2})\)?$""")
    private val DATE_LINE = Regex(
        """^(\d{4})\.(\d{1,2})\.(\d{1,2})\s+\S+\s+(오전|오후)\s+(\d{1,2}):(\d{2})""" +
            """(?:\s*[・·]\s*(?:(\d+)시간\s*)?(?:(\d+)분\s*)?(?:(\d+)초)?)?""",
    )
    private const val SAMSUNG_HEADER = "AI로 생성된 콘텐츠입니다"

    fun parse(raw: String): Parsed {
        val lines = raw.removePrefix("﻿").lines().map { it.trim() }
        val firstSpeaker = lines.indexOfFirst { SPEAKER_LINE.matches(it) }

        // 삼성은 안내 문구와 제목 사이에 빈 줄을 두기도 해서, 안내 문구 뒤 첫 글 줄을 제목으로 쓴다
        if (lines.firstOrNull() == SAMSUNG_HEADER && firstSpeaker > 1) {
            val title = lines.subList(1, firstSpeaker).firstOrNull { it.isNotEmpty() }
            return spoken(lines.drop(firstSpeaker), title, null, null)
        }

        val date = lines.getOrNull(1)?.let { DATE_LINE.find(it) }
        val isClova = date != null && firstSpeaker > 0
        if (!isClova) return Parsed(null, null, null, lines.filter { it.isNotEmpty() }.joinToString("\n"))

        val g = date!!.groupValues
        val hour12 = g[5].toInt() % 12
        val started = runCatching {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), if (g[4] == "오후") hour12 + 12 else hour12, g[6].toInt())
                .atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        }.getOrNull()
        val seconds = (g[7].toLongOrNull() ?: 0) * 3600 + (g[8].toLongOrNull() ?: 0) * 60 + (g[9].toLongOrNull() ?: 0)
        return spoken(lines.drop(firstSpeaker), lines.first().ifEmpty { null }, started, seconds.takeIf { it > 0 }?.times(1000))
    }

    /** 첫 화자 줄부터: 화자 줄마다 차례를 새로 열고, 그 아래 글 줄을 그 차례에 담는다 */
    private fun spoken(fromFirstSpeaker: List<String>, title: String?, startedAt: Long?, durationMs: Long?): Parsed {
        val turns = mutableListOf<Turn>()
        for (line in fromFirstSpeaker) {
            if (line.isEmpty()) continue
            val speaker = SPEAKER_LINE.matchEntire(line)
            if (speaker != null) {
                turns += Turn(speaker.groupValues[1].trim(), clockToMs(speaker.groupValues[2]), "")
            } else {
                val last = turns.last()
                turns[turns.lastIndex] = last.copy(text = if (last.text.isEmpty()) line else last.text + "\n" + line)
            }
        }
        val spokenTurns = turns.filter { it.text.isNotEmpty() }
        return Parsed(title, startedAt, durationMs, spokenTurns.joinToString("\n") { it.text }, spokenTurns)
    }

    // "00:23" → 23초, "1:02:03" → 1시간 2분 3초
    private fun clockToMs(clock: String): Long =
        clock.split(':').fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000
}
