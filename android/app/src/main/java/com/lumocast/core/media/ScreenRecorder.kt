package com.lumocast.core.media

import android.media.MediaCodec
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 附加的 MP4 录制消费者（Pro 功能）。
 *
 * 它和网络发送**共享同一份编码器输出**：不额外编码、不额外耗电，
 * 只是在 submitFrame 时多写一次 MediaMuxer。
 */
class ScreenRecorder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int
) {
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var started = false
    private var csd: ByteArray? = null
    private var frames = 0L

    @Synchronized
    fun onConfig(spsPps: ByteArray) {
        csd = spsPps
        ensureMuxer()
    }

    /** @param ptsUs 编码器给的微秒时间戳（与 socket 上发送的 pts 同源） */
    @Synchronized
    fun onFrame(annexB: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        ensureMuxer()
        val m = muxer ?: return
        if (!started) return
        // MediaMuxer 期望 AVCC（4 字节长度前缀），编码器给的是 Annex-B，需转换
        val avcc = AnnexBToAvcc.convert(annexB) ?: return
        try {
            val buf = ByteBuffer.allocateDirect(avcc.size)
            buf.put(avcc)
            buf.position(0)
            val info = MediaCodec.BufferInfo().apply {
                set(0, avcc.size, ptsUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            m.writeSampleData(trackIndex, buf, info)
            frames++
        } catch (t: Throwable) {
            Log.w(TAG, "writeSampleData failed: ${t.message}")
        }
    }

    private fun ensureMuxer() {
        if (muxer != null) return
        try {
            val m = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = android.media.MediaFormat.createVideoFormat(
                android.media.MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            )
            // 用 Annex-B 形式的 SPS/PPS 作为 csd
            csd?.let {
                val avccCsd = AnnexBToAvcc.toAvccCsd(it) ?: return
                val buf = ByteBuffer.wrap(avccCsd)
                // csd-0 需要的是完整 avcC 盒子里的内容；这里用 MediaFormat 的通用约定
                format.setByteBuffer("csd-0", buf)
            }
            trackIndex = m.addTrack(format)
            m.start()
            muxer = m
            started = true
            Log.i(TAG, "recording → ${outputFile.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "muxer init failed", t)
            muxer = null
            started = false
        }
    }

    @Synchronized
    fun stop(): String? {
        try {
            if (started) muxer?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "muxer stop failed: ${t.message}")
        }
        try {
            muxer?.release()
        } catch (_: Throwable) {
        }
        muxer = null
        started = false
        return if (frames > 0) outputFile.absolutePath else null
    }

    companion object {
        private const val TAG = "LumoRecorder"
    }
}

/** Annex-B ⇄ AVCC 工具，录制与浏览器端共用同一套逻辑。 */
object AnnexBToAvcc {

    fun convert(annexB: ByteArray): ByteArray? {
        val nals = com.lumocast.util.AnnexB.nals(annexB).filter {
            val t = com.lumocast.util.AnnexB.nalType(it)
            t in 1..5
        }
        if (nals.isEmpty()) return null
        var size = 0
        for (n in nals) size += 4 + n.size
        val out = ByteArray(size)
        var pos = 0
        for (n in nals) {
            out[pos] = (n.size ushr 24).toByte()
            out[pos + 1] = (n.size ushr 16).toByte()
            out[pos + 2] = (n.size ushr 8).toByte()
            out[pos + 3] = n.size.toByte()
            pos += 4
            System.arraycopy(n, 0, out, pos, n.size)
            pos += n.size
        }
        return out
    }

    /** 生成 avcC（AVCDecoderConfigurationRecord），含 SPS/PPS。 */
    fun toAvccCsd(csd: ByteArray): ByteArray? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (n in com.lumocast.util.AnnexB.nals(csd)) {
            when (com.lumocast.util.AnnexB.nalType(n)) {
                7 -> sps = n
                8 -> pps = n
            }
        }
        val s = sps ?: return null
        val p = pps ?: return null
        if (s.size < 4) return null
        val out = ByteArray(11 + s.size + p.size)
        out[0] = 1                                   // configurationVersion
        out[1] = s[1]                                 // AVCProfileIndication
        out[2] = s[2]                                 // profile_compatibility
        out[3] = s[3]                                 // AVCLevelIndication
        out[4] = 0xFF.toByte()                        // lengthSizeMinusOne = 3
        out[5] = 0xE1.toByte()                        // numOfSequenceParameterSets = 1
        out[6] = ((s.size ushr 8) and 0xFF).toByte()
        out[7] = (s.size and 0xFF).toByte()
        System.arraycopy(s, 0, out, 8, s.size)
        var pos = 8 + s.size
        out[pos] = 1                                  // numOfPictureParameterSets
        out[pos + 1] = ((p.size ushr 8) and 0xFF).toByte()
        out[pos + 2] = (p.size and 0xFF).toByte()
        System.arraycopy(p, 0, out, pos + 3, p.size)
        return out
    }
}
