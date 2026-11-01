package com.workbrain.app.review

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.workbrain.app.data.MinutesWord
import java.io.File

/**
 * 회의록 Word 공유(FR-29, DEC-60): 서버가 만든 .docx를 앱 캐시에 잠깐 두고 안드로이드 공유 창을 띄운다.
 * Gmail을 고르면 첨부된 채로 메일 쓰기가 열려, 지금 Claude에서 만든 Word를 회사 메일로 보내던 흐름을 대신한다.
 */
object MinutesShare {
    private const val DIR = "shared"
    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun share(context: Context, word: MinutesWord, title: String) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        // 지난 공유 파일은 남길 이유가 없다(회의록 원본은 서버에 있음). 받는 앱이 아직 읽는 중일 수 있어 이번 파일만 새로 쓴다
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(word.meetingDate))
        file.writeBytes(word.bytes)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = DOCX_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            // 메일 앱이면 제목 칸에 바로 들어간다(Design §3.8 ④ 제목 형식)
            putExtra(Intent.EXTRA_SUBJECT, subject(title, word))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "회의록 Word 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 한글 파일 이름은 받는 쪽에서 다운로드가 실패한 적이 있어 ASCII로만 만든다(스킬 규칙) */
    private fun fileName(meetingDate: String): String {
        val ymd = meetingDate.filter(Char::isDigit).take(8)
        return if (ymd.length == 8) "meeting_minutes_$ymd.docx" else "meeting_minutes.docx"
    }

    private fun subject(title: String, word: MinutesWord): String {
        val date = if (word.meetingDate.isNotBlank()) " (${word.meetingDate})" else ""
        return (if (word.draft) "[초안] " else "") + "[WorkBrain] 회의록 — $title$date"
    }
}
