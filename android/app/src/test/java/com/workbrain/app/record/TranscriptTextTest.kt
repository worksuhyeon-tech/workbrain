package com.workbrain.app.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 받아쓴 글 가져오기(DEC-53·54·112) — 예시는 모두 지어낸 것. 웹 transcriptText.test.ts와 같은 경우 */
class TranscriptTextTest {
    @Test fun `삼성 화자 있음 - 안내 문구 뒤 첫 글 줄이 제목, (00_03) 시각`() {
        val p = TranscriptText.parse(listOf("AI로 생성된 콘텐츠입니다", "", "주간 점검", "발화자 1  (00:03)", "점검 시작합니다.").joinToString("\r\n"))
        assertEquals("주간 점검", p.title)
        assertNull(p.startedAt)
        assertEquals(listOf(TranscriptText.Turn("발화자 1", 3000, "점검 시작합니다.")), p.turns)
    }

    @Test fun `삼성 화자 분리 끔 - 안내 문구·시각 줄을 떼고 말한 문장만, 제목·화자 없음`() {
        val p = TranscriptText.parse(
            listOf("AI로 생성된 콘텐츠입니다", "00:00", "요거는 제가 보내 놓겠습니다.", "00:05", "아 네. 좋습니다.", "", "1:02:03", "감사합니다.").joinToString("\r\n"),
        )
        assertNull(p.title)
        assertEquals("요거는 제가 보내 놓겠습니다.\n아 네. 좋습니다.\n감사합니다.", p.body)
        assertEquals(emptyList<TranscriptText.Turn>(), p.turns)
    }

    @Test fun `안내 문구만 있고 화자·시각 줄이 없으면 안내 문구만 뗀다`() {
        assertEquals("그냥 글", TranscriptText.parse("AI로 생성된 콘텐츠입니다\n그냥 글").body)
    }

    @Test fun `형식을 모르면 빈 줄만 빼고 글 그대로`() {
        val p = TranscriptText.parse("﻿그냥 메모\n\n둘째 줄")
        assertEquals("그냥 메모\n둘째 줄", p.body)
        assertEquals(emptyList<TranscriptText.Turn>(), p.turns)
    }
}
