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
        if (ext == null || ext !in Server.AUDIO_TYPES) {
            return Result.Rejected("이 형식(${ext ?: "알 수 없음"})은 받아쓸 수 없어요. m4a·mp3·wav 파일을 골라 주세요.")
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
}

/** 다른 앱에서 '공유 → WorkBrain'으로 받은 파일을 녹음 화면에 넘기는 통로(MainActivity → HomeScreen) */
object SharedAudio {
    data class Item(val uri: Uri, val mime: String?)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Item?>(null)
}
