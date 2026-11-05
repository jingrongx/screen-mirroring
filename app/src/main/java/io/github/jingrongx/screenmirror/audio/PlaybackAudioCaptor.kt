package io.github.jingrongx.screenmirror.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import androidx.annotation.RequiresApi
import kotlin.concurrent.thread

/**
 * 捕获系统播放的音频（媒体/游戏/未知来源），输出 48kHz 单声道 PCM16。
 * 需要 MediaProjection 授权，且仅在 Android 10（API 29）及以上可用。
 */
@RequiresApi(29)
class PlaybackAudioCaptor(private val projection: MediaProjection) {

    val sampleRate = 48_000
    val channels = 1

    private var record: AudioRecord? = null

    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    /** @return 是否成功启动 */
    fun start(onPcm: (ByteArray) -> Unit): Boolean {
        if (capturing) return true
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * 2 / 5) // 至少约 200ms
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        this.record = record
        capturing = true
        captureThread = thread(name = "audio-capture") {
            runCatching { record.startRecording() }
            val chunk = ShortArray(sampleRate / 50) // 20ms 一帧，降低音频路径延迟
            while (capturing) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) break
                val bytes = ByteArray(n * 2)
                java.nio.ByteBuffer.wrap(bytes)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .put(chunk, 0, n)
                onPcm(bytes)
            }
            runCatching { record.stop() }
        }
        return true
    }

    fun stop() {
        capturing = false
        captureThread?.let { runCatching { it.join(1000) } }
        captureThread = null
        record?.release()
        record = null
    }
}
