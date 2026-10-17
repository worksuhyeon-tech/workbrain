package com.workbrain.app.record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 약 10초마다 다음 파일로 끊김 없이 넘어가며 녹음한다(MediaRecorder.setNextOutputFile).
 * 넘어간 이전 조각은 완결된 m4a로 닫히므로, 앱이 강제 종료돼도 잃는 것은 마지막 조각(최대 약 10초)뿐이다(FR-03, TC-03).
 * 음성 인식용 설정: 모노·16kHz·32kbps AAC → 60분 약 14MB(전사 API 25MB 한도 안).
 */
class SegmentRecorder(
    private val context: Context,
    private val dir: File,
    /** 조각이 확정될 때마다 지금까지 확정된 조각 수를 알린다(메인 스레드) */
    private val onConfirmed: (confirmedSegments: Int) -> Unit,
) {
    private var recorder: MediaRecorder? = null
    private var current = 0

    private fun file(index: Int) = File(dir, "seg-%05d.m4a".format(index))

    fun start() {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = r
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(SAMPLE_RATE)
        r.setAudioEncodingBitRate(BIT_RATE)
        r.setMaxFileSize(SEGMENT_BYTES)
        r.setOutputFile(file(current))
        r.setOnInfoListener { mr, what, _ ->
            when (what) {
                // 한도에 가까워지면 다음 파일을 미리 지정해 두어야 끊김 없이 넘어간다
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ->
                    runCatching { mr.setNextOutputFile(file(current + 1)) }
                MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                    current += 1
                    onConfirmed(current)
                }
            }
        }
        r.prepare()
        r.start()
    }

    fun pause() = recorder?.pause()
    fun resume() = recorder?.resume()

    /** 멈추고 마지막 조각까지 닫는다. 확정된 전체 조각 수를 돌려준다. */
    fun stop(): Int {
        val r = recorder ?: return current
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        // stop이 실패하면(녹음 직후 바로 정지 등) 마지막 조각은 비어 있거나 깨져 있으므로 지운다
        if (!ok) file(current).delete() else current += 1
        return current
    }

    fun release() {
        recorder?.release()
        recorder = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 32_000
        const val SEGMENT_SECONDS = 10
        val SEGMENT_BYTES: Long = BIT_RATE / 8L * SEGMENT_SECONDS
    }
}
