package com.lumocast.core.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * LumoCast 线协议 v1 —— 与 docs/PROTOCOL.md 严格一致。
 *
 * 帧结构（16 字节定长头 + payload）：
 *   0  'L'   1  'C'   2  version   3  type
 *   4  flags 5..7 rsv 8..11 pts_ms(BE u32) 12..15 payload_len(BE u32)
 *
 * 所有实现（Kotlin / JS / Swift）必须逐字节对齐，`scripts/protocol-test.mjs` 负责回归。
 */
object Proto {
    const val MAGIC_0 = 0x4C
    const val MAGIC_1 = 0x43
    const val VERSION = 1
    const val HEADER_SIZE = 16
    const val MAX_PAYLOAD = 4 * 1024 * 1024
    const val MAX_LINE = 8 * 1024

    const val T_CONFIG = 1
    const val T_VIDEO = 2
    const val T_AUDIO = 3
    const val T_PING = 4
    const val T_PONG = 5
    const val T_CONTROL = 6

    /** v1.1：AudioSpecificConfig（AAC-LC，常见 2 字节）。必须先于任何 AUDIO 帧发出。 */
    const val T_AUDIO_CONFIG = 7

    const val F_KEYFRAME = 0x01
    const val F_KEYFRAME_REQUESTED = 0x02

    const val PORT_CONTROL = 8737
    const val PORT_CONTROL_MAX = 8747
    const val PORT_DISCOVERY = 8738
    const val ANNOUNCE_INTERVAL_MS = 2000L

    /**
     * 链路空闲判定。Wi-Fi 断开时 TCP 不会收到 FIN —— 连接会一直「看起来还在」，
     * 主机端于是把死连接当成在观看的客户端（占满名额、后续设备连不上）。
     * 双方都用这个超时：这么久没收到任何字节就判定链路已死。
     */
    const val NET_IDLE_TIMEOUT_MS = 8_000
    /** 同时观看设备上限（无付费分层，所有人一样） */
    const val MAX_VIEWERS = 5

    // ---- 分辨率与码率上限
    const val MAX_LONG_SIDE = 2560
    const val MAX_SHORT_SIDE = 1440
    const val BITRATE_MIN = 1_500_000
    const val BITRATE_MAX = 24_000_000

    // ---- 音频
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNELS = 2
    const val AUDIO_BITRATE = 160_000

    fun writeHeader(dst: ByteArray, off: Int = 0, type: Int, flags: Int, ptsMs: Long, len: Int) {
        dst[off] = MAGIC_0.toByte()
        dst[off + 1] = MAGIC_1.toByte()
        dst[off + 2] = VERSION.toByte()
        dst[off + 3] = type.toByte()
        dst[off + 4] = flags.toByte()
        dst[off + 5] = 0
        dst[off + 6] = 0
        dst[off + 7] = 0
        putInt(dst, off + 8, (ptsMs and 0xFFFFFFFFL).toInt())
        putInt(dst, off + 12, len)
    }

    fun putInt(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v ushr 24).toByte()
        dst[off + 1] = (v ushr 16).toByte()
        dst[off + 2] = (v ushr 8).toByte()
        dst[off + 3] = v.toByte()
    }

    fun getInt(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 24) or
            ((src[off + 1].toInt() and 0xFF) shl 16) or
            ((src[off + 2].toInt() and 0xFF) shl 8) or
            (src[off + 3].toInt() and 0xFF)
}

/** 一个已解析的协议包。 */
class Packet(val type: Int, val flags: Int, val ptsMs: Long, val data: ByteArray) {
    val isKeyFrame: Boolean get() = flags and Proto.F_KEYFRAME != 0
}

/** 阻塞式读包器；不可跨线程共享。 */
class PacketReader(private val input: InputStream) {
    private val header = ByteArray(Proto.HEADER_SIZE)

    /** @return null 表示对端正常关闭 */
    fun next(): Packet? {
        val first = input.read()
        if (first == -1) return null
        header[0] = first.toByte()
        readFully(header, 1, Proto.HEADER_SIZE - 1)

        if ((header[0].toInt() and 0xFF) != Proto.MAGIC_0 ||
            (header[1].toInt() and 0xFF) != Proto.MAGIC_1
        ) throw IOException("bad magic")
        val ver = header[2].toInt() and 0xFF
        if (ver != Proto.VERSION) throw IOException("unsupported protocol version $ver")

        val type = header[3].toInt() and 0xFF
        val flags = header[4].toInt() and 0xFF
        val pts = Proto.getInt(header, 8).toLong() and 0xFFFFFFFFL
        val len = Proto.getInt(header, 12)
        if (len < 0 || len > Proto.MAX_PAYLOAD) throw IOException("payload too large: $len")

        val data = ByteArray(len)
        readFully(data, 0, len)
        return Packet(type, flags, pts, data)
    }

    private fun readFully(b: ByteArray, off: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(b, off + read, len - read)
            if (n <= 0) throw IOException("stream closed")
            read += n
        }
    }
}

/** 线程安全写包器：多个观看者各自持有一个实例，但同一 socket 只有一个写者。 */
class PacketWriter(private val out: OutputStream) {
    private val lock = Any()
    private val header = ByteArray(Proto.HEADER_SIZE)

    fun write(type: Int, flags: Int, ptsMs: Long, payload: ByteArray, off: Int = 0, len: Int = payload.size) {
        Proto.writeHeader(header, 0, type, flags, ptsMs, len)
        synchronized(lock) {
            out.write(header, 0, Proto.HEADER_SIZE)
            out.write(payload, off, len)
            out.flush()
        }
    }

    fun writeControl(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        write(Proto.T_CONTROL, 0, 0L, bytes)
    }

    fun writePing(seq: Int, sendMs: Long) {
        val p = ByteArray(8)
        Proto.putInt(p, 0, seq)
        Proto.putInt(p, 4, sendMs.toInt())
        write(Proto.T_PING, 0, 0L, p)
    }
}

/** 读一行 JSON 握手（以 \n 结尾，\r 会被忽略）。 */
fun readHandshakeLine(input: InputStream, max: Int = Proto.MAX_LINE): String? {
    val sb = StringBuilder(128)
    while (true) {
        val b = input.read()
        if (b == -1) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) return sb.toString()
        if (b != '\r'.code) sb.append(b.toChar())
        if (sb.length > max) throw IOException("handshake line too long")
    }
}

fun writeHandshakeLine(out: OutputStream, json: String) {
    out.write((json + "\n").toByteArray(Charsets.UTF_8))
    out.flush()
}
