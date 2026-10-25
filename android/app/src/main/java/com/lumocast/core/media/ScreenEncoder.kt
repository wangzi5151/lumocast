package com.lumocast.core.media

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.lumocast.util.AnnexB

/**
 * 屏幕采集 + H.264 硬编。
 *
 * 设计要点（见 docs/ARCHITECTURE.md §2）：
 *  - VirtualDisplay 直接输出到编码器的 input Surface，零拷贝；
 *  - drain 循环固定 4ms 轮询（比 sleep(16) 更稳，实测延迟低 12ms）；
 *  - 码率变更不重建编码器，只用 setParameters；关键帧按需插入。
 *
 * 所有 MediaCodec 调用都在同一个 HandlerThread 上，避免线程安全问题。
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val fps: Int,
    initialBitrate: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onCodecConfig(csd: ByteArray)
        fun onVideoFrame(data: ByteArray, ptsMs: Long, keyFrame: Boolean)
        fun onError(message: String)
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var bitrate = initialBitrate
    private var csdSent = false

    private val bufferInfo = MediaCodec.BufferInfo()

    fun start() {
        if (running) return
        running = true
        val t = HandlerThread("lumocast-enc").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户在系统托盘/通知里点了「停止录制」
                if (!stopping) {
                    listener.onError("系统已停止屏幕捕获")
                    stop()
                }
            }
        }, h)

        h.post { setup() }
    }

    private fun setup() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 秒
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // ---- 低延迟三件套（缺一不可）
                putIfSupported(this, MediaFormat.KEY_LATENCY, 0)      // 不缓冲
                putIfSupported(this, "max-bframes", 0)                // 无 B 帧
                putIfSupported(this, MediaFormat.KEY_OPERATING_RATE, Int.MAX_VALUE) // 实时优先
                putIfSupported(this, MediaFormat.KEY_PRIORITY, 0)
            }

            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            codec = c
            inputSurface = surface

            virtualDisplay = projection.createVirtualDisplay(
                "LumoCast",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                handler
            )

            handler?.post(drainRunnable)
            Log.i(TAG, "encoder started ${width}x$height @${fps}fps ${bitrate / 1000}kbps")
        } catch (t: Throwable) {
            Log.e(TAG, "encoder setup failed", t)
            listener.onError(t.message ?: "编码器初始化失败")
            stop()
        }
    }

    private fun putIfSupported(format: MediaFormat, key: String, value: Int) {
        try {
            format.setInteger(key, value)
        } catch (_: Throwable) {
            // 老设备/某些芯片不接受该参数，忽略
        }
    }

    private val drainRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                drain()
            } catch (t: Throwable) {
                Log.e(TAG, "drain failed", t)
                listener.onError(t.message ?: "编码异常")
                stop()
                return
            }
            handler?.postDelayed(this, 4)
        }
    }

    private fun drain() {
        val c = codec ?: return
        while (true) {
            val index = c.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    val csd0 = fmt.getByteBuffer("csd-0")
                    val csd1 = fmt.getByteBuffer("csd-1")
                    if (csd0 != null && csd1 != null) {
                        val a = ByteArray(csd0.remaining()).also { csd0.get(it) }
                        val b = ByteArray(csd1.remaining()).also { csd1.get(it) }
                        csdSent = true
                        listener.onCodecConfig(a + b)
                    }
                }

                index >= 0 -> {
                    val buf = c.getOutputBuffer(index)
                    if (buf != null && bufferInfo.size > 0) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        val data = ByteArray(bufferInfo.size)
                        buf.get(data)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            AnnexB.extractCsd(data)?.let {
                                csdSent = true
                                listener.onCodecConfig(it)
                            }
                        } else {
                            val key = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            // 首个 IDR 之前必须已经发过 SPS/PPS，否则接收端会黑屏
                            if (key && !csdSent) {
                                AnnexB.extractCsd(data)?.let {
                                    csdSent = true
                                    listener.onCodecConfig(it)
                                }
                            }
                            listener.onVideoFrame(data, bufferInfo.presentationTimeUs / 1000, key)
                        }
                    }
                    c.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /** 接收端反馈花屏/首帧超时 → 立刻插 IDR（不重建编码器，避免码率抖动）。 */
    fun requestKeyFrame() {
        handler?.post {
            try {
                codec?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            } catch (t: Throwable) {
                Log.w(TAG, "requestKeyFrame failed: ${t.message}")
            }
        }
    }

    /** 自适应码率：2~12 Mbps，只改参数不重建。 */
    fun setBitrate(bps: Int) {
        val clamped = bps.coerceIn(
            com.lumocast.core.net.Proto.BITRATE_MIN,
            com.lumocast.core.net.Proto.BITRATE_MAX
        )
        if (clamped == bitrate) return
        bitrate = clamped
        handler?.post {
            try {
                codec?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped)
                })
            } catch (t: Throwable) {
                Log.w(TAG, "setBitrate failed: ${t.message}")
            }
        }
    }

    fun currentBitrate(): Int = bitrate

    fun stop() {
        if (stopping) return
        stopping = true
        running = false
        handler?.removeCallbacksAndMessages(null)
        handler?.post {
            try {
                virtualDisplay?.release()
            } catch (_: Throwable) {
            }
            virtualDisplay = null
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
                inputSurface?.release()
            } catch (_: Throwable) {
            }
            inputSurface = null
            try {
                projection.stop()
            } catch (_: Throwable) {
            }
            thread?.quitSafely()
            thread = null
            handler = null
            Log.i(TAG, "encoder stopped")
        }
    }

    companion object {
        private const val TAG = "LumoEncoder"

        /** 依据屏幕物理尺寸给出一个「够用且不过热」的初始码率。 */
        fun suggestBitrate(width: Int, height: Int, fps: Int): Int {
            val pixels = width.toLong() * height
            // 屏幕内容（大量静止区域）比相机视频好压，但字边缘对码率敏感，
            // 所以按像素量给档位，再按帧率线性放大。
            val base = when {
                pixels >= 2560L * 1440 -> 14_000_000   // 1440p
                pixels >= 2400L * 1080 -> 10_000_000   // 20:9 的长屏 1080p+
                pixels >= 1920L * 1080 -> 8_000_000
                pixels >= 1280L * 720 -> 4_500_000
                else -> 2_500_000
            }
            return (base * fps / 30).coerceIn(
                com.lumocast.core.net.Proto.BITRATE_MIN,
                com.lumocast.core.net.Proto.BITRATE_MAX
            )
        }
    }
}
