package com.lumocast.core.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * 接收端音频播放器：AAC 硬解 → **抖动缓冲** → AudioTrack。
 *
 * 为什么要软件抖动缓冲（v1.1.2）：
 *  之前的实现是「收到一包 AAC → 立刻解码 → 立刻用 WRITE_NON_BLOCKING 写 AudioTrack」。两个致命点：
 *   1. 网络一抖（TCP 里一个 IDR 200KB 排在音频前面，就能堵住 100~200ms），AudioTrack 立刻欠载 → 声音断一截；
 *   2. 不阻塞写：AudioTrack 缓冲满了就丢采样，直接是「咔」一下 + 后续时钟偏移。
 *  两者叠加就是「一顿一顿」。现在改成：解码出来的 PCM 先进队列，播放线程阻塞式写入，
 *  维持 [PREBUFFER_MS] 的预缓冲；缓冲领先超过 [MAX_LEAD_MS] 就丢最老的一块把延迟压回来。
 *  这样 **永远不丢采样**（不会爆音），最多是偶发的一次性空档，而且延迟有上限。
 *
 * 它同时是**音画同步的主时钟**：`currentHostMs()` 返回「此刻正在听到的那一采样点」的宿主时间。
 * 人耳对音频中断极其敏感，所以永远让视频去追音频，不能反过来。
 */
class AudioPlayer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val info = MediaCodec.BufferInfo()

    private var sampleRate = 48_000
    private var channels = 2
    private var bytesPerFrame = 4

    /** 一小块待播 PCM（一个 AAC 帧 = 1024 采样 ≈ 21.3ms） */
    private class Chunk(val pcm: ByteArray, val frames: Int, val ptsMs: Long)

    private val lock = Any()
    private val pending = ArrayDeque<Chunk>()          // 已解码、还没写进 AudioTrack
    /** (起始累积帧号, 该块首个采样的宿主时间)，用于把 playbackHeadPosition 反推成宿主时间 */
    private val timeline = ArrayDeque<LongArray>()

    private var queuedFrames = 0L      // pending 里的总帧数
    private var framesWritten = 0L     // 已写入 AudioTrack 的累计帧数（与 playbackHeadPosition 同一坐标）
    @Volatile private var playing = false
    @Volatile private var started = false

    private var thread: Thread? = null
    @Volatile private var running = false

    @Volatile var decodedChunks = 0L
        private set
    @Volatile var droppedChunks = 0L
        private set

    val active: Boolean get() = track != null && codec != null

    /** @param asc AudioSpecificConfig（2 字节常见，AAC-LC） */
    @Synchronized
    fun configure(asc: ByteArray, sampleRateHz: Int = 48_000, channelCount: Int = 2): Boolean {
        release()
        return try {
            sampleRate = sampleRateHz
            channels = channelCount
            bytesPerFrame = 2 * channels

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
            // 缓冲大约 100ms：比它小容易欠载，比它大纯粹加延迟
            val wantBytes = sampleRate * bytesPerFrame * TRACK_BUFFER_MS / 1000
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
                .setBufferSizeInBytes(maxOf(minBuf * 2, wantBytes))
                .setTransferMode(AudioTrack.MODE_STREAM)
                // 不用 PERFORMANCE_MODE_LOW_LATENCY：那个模式要求极小的缓冲，和抖动缓冲是两条路，
                // 在 Wi-Fi 投屏这种场景下只会换来更频繁的欠载。
                .build()
            track = t

            synchronized(lock) {
                pending.clear()
                timeline.clear()
                queuedFrames = 0
                framesWritten = 0
            }
            started = false
            playing = false

            running = true
            thread = Thread({ playbackLoop() }, "lumo-audio-play").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "audio configured ${sampleRate}Hz ×$channels, track=${t.bufferSizeInFrames} frames")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "audio configure failed", t)
            release()
            false
        }
    }

    /** 收包线程调用：只解码 + 入队，绝不做阻塞写（否则会拖慢视频收包）。 */
    @Synchronized
    fun submit(data: ByteArray, ptsMs: Long) {
        val c = codec ?: return
        try {
            val inIndex = c.dequeueInputBuffer(1_000)
            if (inIndex < 0) return
            val buf = c.getInputBuffer(inIndex) ?: return
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(inIndex, 0, data.size, ptsMs * 1000, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "audio queue failed: ${t.message}")
            return
        }

        try {
            while (true) {
                val outIndex = c.dequeueOutputBuffer(info, 0)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIndex < 0) continue
                val outBuf = c.getOutputBuffer(outIndex)
                if (outBuf != null && info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val pcm = ByteArray(info.size)
                    outBuf.get(pcm)
                    val frames = pcm.size / bytesPerFrame
                    // 用解码器输出自带的 pts：它跟着 AAC 帧一路传过来，比"最后一包的 pts"精确
                    val chunkPts = info.presentationTimeUs / 1000
                    synchronized(lock) {
                        pending.addLast(Chunk(pcm, frames, chunkPts))
                        queuedFrames += frames
                    }
                    decodedChunks++
                }
                c.releaseOutputBuffer(outIndex, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "audio decode failed: ${t.message}")
        }
    }

    /** 播放线程：维持预缓冲，阻塞写，延迟超上限就丢最老的块。 */
    private fun playbackLoop() {
        val prebufferFrames = (PREBUFFER_MS * sampleRate / 1000).toLong()
        val maxLeadFrames = (MAX_LEAD_MS * sampleRate / 1000).toLong()

        while (running) {
            val t = track
            if (t == null) {
                sleepQuiet(20)
                continue
            }

            var chunk: Chunk? = null
            synchronized(lock) {
                if (!playing) {
                    // 攒够预缓冲再开播，避免开播就欠载
                    if (queuedFrames >= prebufferFrames) {
                        try {
                            t.play()
                        } catch (_: Throwable) {
                        }
                        playing = true
                    }
                }
                if (playing) {
                    // 领先太多（网络抖动攒下来的）就把最老的丢掉，把延迟拉回实时
                    while (pending.size > 1 && queuedFrames > maxLeadFrames) {
                        val old = pending.removeFirst()
                        queuedFrames -= old.frames
                        droppedChunks++
                    }
                    chunk = pending.removeFirstOrNull()
                    if (chunk != null) queuedFrames -= chunk.frames
                }
            }

            val c = chunk
            if (c == null) {
                // 缓冲空了：暂停等重新攒够，避免一路"咔咔咔"（AudioTrack 欠载会填静音并继续走时钟）
                if (playing) {
                    synchronized(lock) {
                        if (pending.isEmpty()) {
                            playing = false
                            try {
                                t.pause()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                sleepQuiet(4)
                continue
            }

            val written = try {
                t.write(c.pcm, 0, c.pcm.size)   // 阻塞写：绝不丢采样
            } catch (t2: Throwable) {
                Log.w(TAG, "audio write failed: ${t2.message}")
                -1
            }
            if (written <= 0) {
                sleepQuiet(5)
                continue
            }
            val frames = written / bytesPerFrame
            synchronized(lock) {
                timeline.addLast(longArrayOf(framesWritten, c.ptsMs))
                framesWritten += frames
                while (timeline.size > 1 && headFrames() >= timeline[1][0]) timeline.removeFirst()
            }
            started = true
        }
    }

    private fun headFrames(): Long {
        val t = track ?: return 0
        return t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    }

    /**
     * 当前「正在听到」的宿主时间；还没开始播放时返回 null（此时不做同步，视频直接上屏，首帧最快）。
     */
    fun currentHostMs(): Long? {
        val t = track ?: return null
        if (!started) return null
        synchronized(lock) {
            val head = headFrames()
            // 欠载：AudioTrack 已经播到我们写过的数据之后 → 时钟卡住，别让它跳
            if (head >= framesWritten) return timeline.lastOrNull()?.get(1)
            while (timeline.size > 1 && head >= timeline[1][0]) timeline.removeFirst()
            val e = timeline.firstOrNull() ?: return null
            val delta = (head - e[0]).coerceAtLeast(0)
            return e[1] + delta * 1000 / sampleRate
        }
    }

    @Synchronized
    fun release() {
        running = false
        thread?.interrupt()
        thread = null
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
        playing = false
        synchronized(lock) {
            pending.clear()
            timeline.clear()
            queuedFrames = 0
            framesWritten = 0
        }
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "LumoAudioPlayer"

        /** 预缓冲：小于它不开播。40ms ≈ 两个 AAC 帧，够避开常见抖动，又不至于「声音慢半拍」。 */
        private const val PREBUFFER_MS = 40
        /** 领先上限：超过就把老数据丢掉追实时，延迟有天花板（否则网络一抖就永久垫高 200ms+）。 */
        private const val MAX_LEAD_MS = 180
        /** AudioTrack 自身缓冲 ≈ 60ms：抖动主要由上面的队列吸收，这里只要够填就行。 */
        private const val TRACK_BUFFER_MS = 60
    }
}
