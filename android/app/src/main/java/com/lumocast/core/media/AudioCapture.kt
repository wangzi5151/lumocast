package com.lumocast.core.media

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.lumocast.core.net.Proto

/**
 * 系统播放音频采集（Android 10 / API 29+ 的 AudioPlaybackCapture）。
 *
 * 关键点：
 *  - 采集的是**其他 App 播放的媒体音频**，不是麦克风 —— 所以投屏时对方能听到视频里的声音，
 *    而不是你房间的噪音；
 *  - 走 AAC-LC 硬编（48kHz 立体声 160kbps，20ms 一帧），单路音频只占 0.2% CPU；
 *  - 时间戳用「已编码样本数 / 采样率」，与视频共用主机单调时钟，接收端据此做音画同步。
 *
 * API 29 以下没有 AudioPlaybackCapture，此时静音投屏（视频照常），
 * 并在 UI 里明确告知，而不是假装支持。
 */
class AudioCapture(
    private val projection: MediaProjection,
    private val listener: Listener
) {

    interface Listener {
        fun onAudioConfig(asc: ByteArray)
        fun onAudioFrame(data: ByteArray, ptsMs: Long)
        fun onError(message: String)
    }

    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null

    @Volatile private var running = false

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun start(): Boolean {
        if (!supported) {
            listener.onError("Android 10 以下不支持采集系统播放音频，将只投画面")
            return false
        }
        if (running) return true
        running = true
        thread = Thread({ loop() }, "lumocast-audio").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun loop() {
        var rec: AudioRecord? = null
        var enc: MediaCodec? = null
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Proto.AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                Proto.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf * 2, 8192)

            rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError("音频采集初始化失败")
                return
            }
            record = rec
            rec.startRecording()

            val mediaFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                Proto.AUDIO_SAMPLE_RATE,
                Proto.AUDIO_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, Proto.AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            codec = enc

            val pcm = ByteArray(bufferSize)
            val info = MediaCodec.BufferInfo()
            // 关键：音频 pts 以前是「从 0 开始的已编码样本数」，和视频的 pts（主机单调时钟，
            // 由 VirtualDisplay 打的时间戳）完全不是一个坐标轴 —— 接收端的音画同步因此一直是空转。
            // 现在把第一包锚到主机 uptime，之后按样本数推进，两边就同源了。
            val baseHostMs = android.os.SystemClock.uptimeMillis()
            var samplesQueued = 0L

            while (running) {
                val read = rec.read(pcm, 0, pcm.size)
                if (read <= 0) continue

                val inIndex = enc.dequeueInputBuffer(2_000)
                if (inIndex >= 0) {
                    val inBuf = enc.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.put(pcm, 0, read)
                        val ptsUs = (baseHostMs + samplesQueued * 1000L / Proto.AUDIO_SAMPLE_RATE) * 1000L
                        // 每声道 2 字节
                        samplesQueued += (read / 2 / Proto.AUDIO_CHANNELS).toLong()
                        enc.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                    }
                }

                while (true) {
                    val outIndex = enc.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        enc.outputFormat.getByteBuffer("csd-0")?.let { csd ->
                            val asc = ByteArray(csd.remaining()).also { csd.get(it) }
                            listener.onAudioConfig(asc)
                        }
                        continue
                    }
                    if (outIndex < 0) continue
                    val outBuf = enc.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        outBuf.get(data)
                        listener.onAudioFrame(data, info.presentationTimeUs / 1000)
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio capture failed", t)
            listener.onError(t.message ?: "音频采集异常")
        } finally {
            try {
                record?.stop()
            } catch (_: Throwable) {
            }
            try {
                record?.release()
            } catch (_: Throwable) {
            }
            record = null
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            codec = null
            Log.i(TAG, "audio capture stopped")
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    companion object {
        private const val TAG = "LumoAudio"
    }
}
