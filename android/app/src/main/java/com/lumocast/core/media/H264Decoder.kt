package com.lumocast.core.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.lumocast.util.AnnexB
import java.nio.ByteBuffer

/**
 * 接收端 H.264 硬解，直接解到 SurfaceView 的 Surface 上（零拷贝上屏）。
 *
 * 从 CONFIG 包拿到 SPS/PPS 后建解码器；收到 `rotate` 通知时释放并按新尺寸重建。
 * 不做帧缓冲：解码即上屏，延迟就是网络 + 解码时间。
 */
class H264Decoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val onError: (String) -> Unit = {}
) {

    private var codec: MediaCodec? = null
    private var configured = false
    private val info = MediaCodec.BufferInfo()

    @Volatile var decodedFrames: Long = 0
        private set
    @Volatile var lastDecodeMs: Int = 0
        private set

    @Synchronized
    fun configure(csd: ByteArray): Boolean {
        release()
        return try {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (nal in AnnexB.nals(csd)) {
                when (AnnexB.nalType(nal)) {
                    AnnexB.NAL_SPS -> sps = nal
                    AnnexB.NAL_PPS -> pps = nal
                }
            }
            if (sps == null || pps == null) {
                onError("CONFIG 包缺少 SPS/PPS")
                return false
            }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(AnnexB.withStartCode(sps)))
                setByteBuffer("csd-1", ByteBuffer.wrap(AnnexB.withStartCode(pps)))
                // 低延迟：不缓冲、实时优先、无输出排序等待
                try {
                    setInteger("low-latency", 1)          // API 30+
                    setInteger(MediaFormat.KEY_OPERATING_RATE, Int.MAX_VALUE)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                } catch (_: Throwable) {
                }
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            configured = true
            Log.i(TAG, "decoder configured ${width}x$height")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "configure failed", t)
            onError(t.message ?: "解码器初始化失败")
            false
        }
    }

    /** @param ptsMs 主机侧单调时钟，仅用于统计；解码器不依赖它做同步。 */
    @Synchronized
    fun submit(data: ByteArray, ptsMs: Long, keyFrame: Boolean) {
        val c = codec ?: return
        if (!configured) return
        try {
            val inIndex = c.dequeueInputBuffer(2_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex)
                if (buf != null) {
                    buf.clear()
                    buf.put(data)
                    val ptsUs = if (keyFrame) ptsMs * 1000 else ptsMs * 1000
                    c.queueInputBuffer(inIndex, 0, data.size, ptsUs, 0)
                } else {
                    c.queueInputBuffer(inIndex, 0, 0, 0, 0)
                }
            }
            // 丢弃超时未上屏的旧帧，避免解码队列堆积导致的「越看越慢」
            while (true) {
                val outIndex = c.dequeueOutputBuffer(info, 0)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIndex < 0) continue
                c.releaseOutputBuffer(outIndex, true)
                decodedFrames++
            }
        } catch (t: Throwable) {
            Log.e(TAG, "submit failed", t)
            onError(t.message ?: "解码异常")
        }
    }

    @Synchronized
    fun release() {
        configured = false
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
    }

    companion object {
        private const val TAG = "LumoDecoder"
    }
}
