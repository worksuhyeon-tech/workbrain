package com.workbrain.app.record

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 10초 조각(m4a)들을 다시 인코딩하지 않고 하나의 m4a로 이어 붙인다(음질 손실 없음).
 * 같은 녹음기 설정으로 만든 조각이라 오디오 형식이 같다. 깨진 조각(강제 종료 직전 마지막 조각)은 건너뛴다.
 * 결과의 길이(초)를 돌려준다.
 */
object AudioMerger {
    fun merge(segments: List<File>, out: File): Int {
        var muxer: MediaMuxer? = null
        var track = -1
        var offsetUs = 0L
        val buffer = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        try {
            for (seg in segments) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(seg.path)
                } catch (e: Exception) {
                    ex.release()
                    continue
                }
                val audio = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                if (audio == null) { ex.release(); continue }
                ex.selectTrack(audio)
                if (muxer == null) {
                    muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    track = muxer.addTrack(ex.getTrackFormat(audio))
                    muxer.start()
                }
                var lastUs = 0L
                while (true) {
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = offsetUs + ex.sampleTime
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(track, buffer, info)
                    lastUs = ex.sampleTime
                    ex.advance()
                }
                // 다음 조각은 이 조각 끝 + AAC 한 프레임(1024샘플) 뒤에서 이어진다
                offsetUs += lastUs + AAC_FRAME_US
                ex.release()
            }
        } finally {
            muxer?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        if (muxer == null) error("합칠 수 있는 녹음 조각이 없어요")
        return (offsetUs / 1_000_000L).toInt()
    }

    private const val AAC_FRAME_US = 1024L * 1_000_000L / SegmentRecorder.SAMPLE_RATE
}
