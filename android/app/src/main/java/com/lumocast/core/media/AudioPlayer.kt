package com.lumocast.core.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * 接收端音频播放器：AAC 硬解 → AudioTrack。
 *
 * 它同时是**音画同步的主时钟**：视频的 pts 与「当前正在听到的音频位置」比较，
 * 落后超过 150ms 的帧直接丢弃，超前太多就等下一帧。
 * 为什么用音频主时钟而不是视频主时钟：人耳对音频中断/拉伸极其敏感（>30ms 就能听出来），
 * 而眼睛对丢一帧毫无感觉。所以永远让视频去追音频，不能反过来。
 */
class AudioPlayer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val info = MediaCodec.BufferInfo()

    private var sampleRate = 48_000
    private var channels = 2

    /** 已写入 AudioTrack 的 PCM 帧数（每声道样本数） */
    private var writtenFrames = 0L
    /** 最近一次送进来的音频包的宿主时间戳 */
    @Volatile private var lastPtsMs = 0L
    @Volatile private var started = false
    @Volatile var decodedChunks = 0L
        private set

    val active: Boolean get() = track != null && codec != null

    /** @param asc AudioSpecificConfig（2 字节常见，AAC-LC） */
    @Synchronized
    fun configure(asc: ByteArray, sampleRateHz: Int = 48_000, channelCount: Int = 2): Boolean {
        release()
        return try {
            sampleRate = sampleRateHz
            channels = channelCount

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(asc))
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, 0)
            c.start()
            codec = c

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(
                            if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO
                            else AudioFormat.CHANNEL_OUT_MONO
                        )
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            t.play()
            track = t

            writtenFrames = 0
            started = false
            Log.i(TAG, "audio configured ${sampleRate}Hz ×$channels")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "audio configure failed", t)
            release()
            false
        }
    }

    @Synchronized
    fun submit(data: ByteArray, ptsMs: Long) {
        val c = codec ?: return
        val t = track ?: return
        try {
            val inIndex = c.dequeueInputBuffer(1_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex)
                if (buf != null) {
                    buf.clear()
                    buf.put(data)
                    c.queueInputBuffer(inIndex, 0, data.size, ptsMs * 1000, 0)
                }
            }
            while (true) {
                val outIndex = c.dequeueOutputBuffer(info, 0)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIndex < 0) continue
                val outBuf = c.getOutputBuffer(outIndex)
                if (outBuf != null && info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    // 非阻塞写：宁可丢一小段音频，也不让解码线程卡住拖累视频
                    val written = t.write(outBuf, info.size, AudioTrack.WRITE_NON_BLOCKING)
                    if (written > 0) {
                        writtenFrames += written / 2 / channels
                        lastPtsMs = ptsMs
                        started = true
                        decodedChunks++
                    }
                }
                c.releaseOutputBuffer(outIndex, false)
            }
        } catch (t2: Throwable) {
            Log.e(TAG, "audio submit failed", t2)
        }
    }

    /**
     * 当前「正在听到」的宿主时间。视频帧据此判断自己是否已经过时。
     * 还没开始播放时返回 null，表示暂不做同步（直接上屏，保证首帧最快）。
     */
    fun currentHostMs(): Long? {
        val t = track
        if (!started || t == null) return null
        val headFrames = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val pendingFrames = (writtenFrames - headFrames).coerceAtLeast(0)
        return lastPtsMs - pendingFrames * 1000 / sampleRate
    }

    @Synchronized
    fun release() {
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Throwable) {
        }
        track = null
        started = false
    }

    companion object {
        private const val TAG = "LumoAudioPlayer"
    }
}
