package com.workbrain.app.record

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.workbrain.app.data.Server
import java.io.File

/**
 * 음성 파일 가져오기(DEC-40): 파일 고르기(upload) 또는 다른 앱의 '공유'(call-share, 통화 녹음 등)로 받은 파일을
 * 앱 저장소에 복사해 녹음 목록에 넣는다. 올리기는 앱 녹음과 같은 시트·같은 백그라운드 작업을 쓴다.
 * 클로바노트 등에서 받아쓴 txt도 받는다(DEC-53, importText).
 * 원본 파일은 건드리지 않는다(복사본만 7일 정리 규칙을 따름).
 */
object FileImporter {
    /** 받아쓰기 한 번 한도(처리 함수 STT_MAX_BYTES와 같음). 넘으면 나눠 처리하는 기능이 생길 때까지 거절 */
    private const val MAX_BYTES = 25L * 1024 * 1024

    sealed interface Result {
        data class Ok(val session: RecordingSession) : Result
        data class Rejected(val reason: String) : Result
    }

    /** mimeHint: 공유한 앱이 알려 준 형식(파일 정보를 못 읽을 때 확장자를 정하는 데 쓴다) */
    fun import(context: Context, uri: Uri, channel: String, mimeHint: String? = null): Result {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        var modified: Long? = null
        runCatching { resolver.query(uri, null, null, null, null) }.getOrNull()?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { modified = c.getLong(it) }
            }
        }
        val ext = (name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(runCatching { resolver.getType(uri) }.getOrNull() ?: mimeHint))?.let { if (it == "mpga" || it == "mpeg") "mp3" else it }
        if (ext == TEXT_EXT) return importText(context, uri, name, size, modified)
        if (ext == null || ext !in Server.AUDIO_TYPES) {
            return Result.Rejected("이 형식(${ext ?: "알 수 없음"})은 가져올 수 없어요. m4a·mp3·wav 음성이나 클로바노트 txt 파일을 골라 주세요.")
        }
        if (size > MAX_BYTES) return Result.Rejected("파일이 25MB를 넘어요. 지금은 25MB(약 100분)까지 처리할 수 있어요.")

        val title = name?.substringBeforeLast('.')?.trim()?.take(80)?.ifEmpty { null }
            ?: if (channel == "call-share") "통화 녹음" else "가져온 음성"
        val durationMs = runCatching {
            MediaMetadataRetriever().run {
                try { setDataSource(context, uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() }
            }
        }.getOrNull() ?: 0L
        val dir = RecordingStore.createImported(context, title, modified ?: System.currentTimeMillis(), durationMs, channel)
        val target = File(dir, "import.$ext")
        val copied = runCatching {
            resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        if (copied.isFailure || target.length() == 0L || target.length() > MAX_BYTES) {
            RecordingStore.deleteSession(dir)
            return Result.Rejected(if (target.length() > MAX_BYTES) "파일이 25MB를 넘어요." else "파일을 읽지 못했어요. 다른 앱에서 다시 공유해 주세요.")
        }
        val session = RecordingStore.list(context, activeId = null).first { it.id == dir.name }
        return Result.Ok(session)
    }

    /**
     * 받아쓴 글(DEC-53): 말한 문장만 꺼내 import.txt로 저장한다. 올릴 때는 음성 대신 이 글을 보내고 받아쓰기를 건너뛴다.
     * 공유로 받아도 채널은 text-import(서버가 음성 없이 처리할 기록으로 구분)
     */
    private fun importText(context: Context, uri: Uri, name: String?, size: Long, modified: Long?): Result {
        if (size > TEXT_MAX_BYTES) return Result.Rejected("글 파일이 1MB를 넘어요.")
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }.getOrNull() ?: return Result.Rejected("파일을 읽지 못했어요. 다시 골라 주세요.")
        if (raw.size > TEXT_MAX_BYTES) return Result.Rejected("글 파일이 1MB를 넘어요.")
        return saveText(context, raw.toString(Charsets.UTF_8), name?.substringBeforeLast('.'), modified)
    }

    /** 파일이 아니라 글 자체(EXTRA_TEXT)로 공유받았을 때. subject는 공유한 앱이 붙인 제목 */
    fun importSharedText(context: Context, text: String, subject: String?): Result {
        if (text.toByteArray().size > TEXT_MAX_BYTES) return Result.Rejected("공유한 글이 1MB를 넘어요.")
        // 클로바노트 '링크 공유'처럼 주소만 오면 내용을 받을 수 없다
        if (text.trim().let { it.startsWith("http") && it.none(Char::isWhitespace) }) {
            return Result.Rejected("링크는 가져올 수 없어요. 클로바노트에서 txt 파일로 내보내 공유해 주세요.")
        }
        return saveText(context, text, subject, modified = null)
    }

    private fun saveText(context: Context, raw: String, fallbackTitle: String?, modified: Long?): Result {
        val parsed = TranscriptText.parse(raw)
        if (parsed.body.isBlank()) return Result.Rejected("받아쓴 글이 없어요.")

        val title = (parsed.title ?: fallbackTitle)?.trim()?.take(80)?.ifEmpty { null } ?: "가져온 받아쓰기"
        val dir = RecordingStore.createImported(context, title, parsed.startedAt ?: modified ?: System.currentTimeMillis(), parsed.durationMs ?: 0L, TEXT_CHANNEL)
        File(dir, "import.$TEXT_EXT").writeText(parsed.body)
        val session = RecordingStore.list(context, activeId = null).first { it.id == dir.name }
        return Result.Ok(session)
    }

    const val TEXT_EXT = "txt"
    const val TEXT_CHANNEL = "text-import"
    // 서버 상한 30만 자(한글 UTF-8 약 3바이트)에 맞춘 값
    private const val TEXT_MAX_BYTES = 1024L * 1024
}

/** 다른 앱에서 '공유 → WorkBrain'으로 받은 파일·글을 녹음 화면에 넘기는 통로(MainActivity → HomeScreen) */
object SharedImport {
    sealed interface Item {
        data class File(val uri: Uri, val mime: String?) : Item
        data class Text(val text: String, val subject: String?) : Item
    }
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Item?>(null)
}
