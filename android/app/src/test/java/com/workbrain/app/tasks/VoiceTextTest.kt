package com.workbrain.app.tasks

import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * TC-26 말로 넣기(DEC-89): 인식기 글의 '샵·느낌표·골뱅이'를 단축어 기호로 바꾸고, 있던 글 뒤에 이어 붙인다.
 * 바꾼 글은 QuickParse가 손으로 친 글과 똑같이 해석해야 한다. 예시는 합성 이름만 쓴다
 */
class VoiceTextTest {
    private val friday = LocalDate.of(2026, 11, 6)
    private val crm = Place(PlaceType.PROJECT, "p1", "CRM 이관")
    private val claim = Place(PlaceType.PROJECT, "p2", "사고접수 개편")

    @Test fun `샵 낱말은 프로젝트 기호로`() {
        assertEquals("보고서 #CRM", VoiceText.normalize("보고서 샵 CRM"))
        assertEquals("보고서 #CRM", VoiceText.normalize("보고서 샾 CRM"))
        assertEquals("보고서 #사고접수", VoiceText.normalize("보고서 해시태그 사고접수"))
        assertEquals("#사고접수 보고", VoiceText.normalize("해시 사고접수 보고"))
    }

    @Test fun `낱말 안에 든 샵 해시는 그대로`() {
        assertEquals("네일샵 예약", VoiceText.normalize("네일샵 예약"))
        assertEquals("해시값 확인", VoiceText.normalize("해시값 확인"))
        // 뒤에 아무것도 없으면 붙일 곳이 없어 그대로 둔다
        assertEquals("보고서 샵", VoiceText.normalize("보고서 샵"))
    }

    @Test fun `느낌표 높음 낮음과 골뱅이`() {
        assertEquals("견적 회신 !높음", VoiceText.normalize("견적 회신 느낌표 높음"))
        assertEquals("견적 회신 !낮음", VoiceText.normalize("견적 회신 느낌표낮음"))
        assertEquals("견적 회신 @박지훈", VoiceText.normalize("견적 회신 골뱅이 박지훈"))
        // 우선순위 낱말이 아니면 느낌표는 그대로 글자
        assertEquals("느낌표 그리기", VoiceText.normalize("느낌표 그리기"))
    }

    @Test fun `끝 마침표와 여러 칸 띄어쓰기`() {
        assertEquals("내일 보고", VoiceText.normalize("  내일   보고. "))
        assertEquals("내일 보고", VoiceText.normalize("내일 보고。"))
    }

    @Test fun `있던 글 뒤에 한 칸 띄어 붙인다`() {
        assertEquals("내일 보고", VoiceText.append("", "내일 보고."))
        assertEquals("#CRM 내일 보고", VoiceText.append("#CRM ", "내일 보고"))
        assertEquals("#CRM 내일 보고", VoiceText.append("#CRM", "내일 보고"))
        // 못 들었으면 있던 글 그대로
        assertEquals("#CRM", VoiceText.append("#CRM", "  "))
    }

    @Test fun `말로 넣은 한 줄도 손으로 친 것처럼 해석된다`() {
        val heard = VoiceText.append("", "내일 3시까지 테스트 결과 정리 샵 CRM")
        assertEquals("내일 3시까지 테스트 결과 정리 #CRM", heard)
        val r = QuickParse.parse(heard, friday, listOf(crm, claim), defaultPlace = null)
        assertEquals("테스트 결과 정리", r.title)
        assertEquals(LocalDate.of(2026, 11, 7), r.dueDate)
        assertEquals(LocalTime.of(15, 0), r.dueTime)
        assertEquals(crm, r.place)
    }
}
