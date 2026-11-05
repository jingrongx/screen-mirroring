package io.github.jingrongx.screenmirror.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

/** 控制端音频播放：接收受控端发来的 48kHz 单声道 PCM16 流 */
class PcmPlayer(
    private val sampleRate: Int = 48_000,
) {
    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * 2 / 10) // 约 100ms 缓冲：吸收抖动同时压低音画同步延迟
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf)
            .build()
        runCatching { t.play() }
        track = t
    }

    /** 非阻塞写入：缓冲满时丢弃数据，避免网络抖动后音频延迟无限累积 */
    fun write(pcm: ByteArray) {
        val t = track ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        } else {
            runCatching { t.write(pcm, 0, pcm.size) }
        }
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }
}
