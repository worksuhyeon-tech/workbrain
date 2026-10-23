package com.workbrain.app.record

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 받아쓴 글 가져오기(DEC-53): 클로바노트 txt 등에서 말한 문장만 꺼낸다.
 * 클로바노트 형식: 1줄 제목, 2줄 "2026.10.08 목 오후 3:06 ・ 53분 38초", 3줄 작성자, 이후 "참석자 1 00:00" 줄 + 말한 문장 줄.
 * 화자 표시·시각 줄은 뗀다. 익명 화자 번호로 담당을 추정하면 안 되고(NFR-02·DEC-27), 앱 녹음 받아쓰기와 같은 입력이 되게 한다.
 */
object TranscriptText {
    data class Parsed(val title: String?, val startedAt: Long?, val durationMs: Long?, val body: String)

    // "참석자 1 00:23", "김수현 1:02:03"
    private val SPEAKER_LINE = Regex("""^.{1,30}?\s+(\d{1,2}:)?\d{1,2}:\d{2}$""")
    private val DATE_LINE = Regex(
        """^(\d{4})\.(\d{1,2})\.(\d{1,2})\s+\S+\s+(오전|오후)\s+(\d{1,2}):(\d{2})""" +
            """(?:\s*[・·]\s*(?:(\d+)시간\s*)?(?:(\d+)분\s*)?(?:(\d+)초)?)?""",
    )

    fun parse(raw: String): Parsed {
        val lines = raw.removePrefix("﻿").lines().map { it.trim() }
        val date = lines.getOrNull(1)?.let { DATE_LINE.find(it) }
        val firstSpeaker = lines.indexOfFirst { SPEAKER_LINE.matches(it) }
        val isClova = date != null && firstSpeaker > 0
        if (!isClova) return Parsed(null, null, null, lines.filter { it.isNotEmpty() }.joinToString("\n"))

        val body = lines.drop(firstSpeaker).filter { it.isNotEmpty() && !SPEAKER_LINE.matches(it) }.joinToString("\n")
        val g = date!!.groupValues
        val hour12 = g[5].toInt() % 12
        val started = runCatching {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), if (g[4] == "오후") hour12 + 12 else hour12, g[6].toInt())
                .atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        }.getOrNull()
        val seconds = (g[7].toLongOrNull() ?: 0) * 3600 + (g[8].toLongOrNull() ?: 0) * 60 + (g[9].toLongOrNull() ?: 0)
        return Parsed(lines.first().ifEmpty { null }, started, seconds.takeIf { it > 0 }?.times(1000), body)
    }
}
