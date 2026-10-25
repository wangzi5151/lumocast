package com.lumocast.core.net

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG_V = "LumoViewer"

/**
 * 接收端 TCP 客户端。
 *
 * 用法（在后台线程调用 connect()，回调都在该线程）：
 * ```
 * val client = ViewerClient(ip, port, pin, "LumoCast Android", listener)
 * client.connect()   // 阻塞直到断开
 * ```
 */
class ViewerClient(
    private val host: String,
    private val port: Int,
    private val pin: String?,
    private val deviceName: String,
    private val listener: Listener
) {

    data class Welcome(
        val sid: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val free: Boolean,
        val pinRequired: Boolean,
        val name: String,
        val canTouch: Boolean
    )

    interface Listener {
        fun onWelcome(welcome: Welcome)
        fun onConfig(csd: ByteArray)
        fun onVideo(data: ByteArray, ptsMs: Long, keyFrame: Boolean)

        /** v1.1 音频：AudioSpecificConfig（AAC-LC） */
        fun onAudioConfig(asc: ByteArray) {}

        /** v1.1 音频帧：裸 AAC，ptsMs 与视频同源于主机单调时钟 */
        fun onAudio(data: ByteArray, ptsMs: Long) {}

        fun onHostControl(json: String)
        fun onClosed(reason: String)
    }

    private var socket: Socket? = null
    private var writer: PacketWriter? = null
    private var reader: PacketReader? = null
    private var pingThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var rttMs = 0
    @Volatile private var lastPingSentMs = 0L
    @Volatile private var lastPts = 0L
    @Volatile private var clockOffset: Long? = null   // hostClock ≈ localClock + offset
    @Volatile var framesReceived = 0L
        private set

    /** 阻塞：连接 → 握手 → 收流，直到断开或 [close]。 */
    fun connect() {
        running = true
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.receiveBufferSize = 2 shl 20
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 12_000
            socket = s

            // ---- hello
            val hello = buildString {
                append("{\"t\":\"hello\",\"v\":").append(Proto.VERSION)
                append(",\"role\":\"viewer\",\"device\":").append(JSONObject.quote(deviceName))
                append(",\"pin\":").append(JSONObject.quote(pin ?: ""))
                append('}')
            }
            writeHandshakeLine(s.getOutputStream(), hello)

            // ---- welcome / error
            val line = readHandshakeLine(s.getInputStream())
                ?: run {
                    listener.onClosed("对端关闭了连接")
                    return
                }
            val obj = try {
                JSONObject(line)
            } catch (e: Exception) {
                listener.onClosed("握手响应无法解析")
                return
            }
            if (obj.optString("t") == "error") {
                listener.onClosed(obj.optString("msg", "被拒绝：${obj.optString("code")}"))
                return
            }
            if (obj.optString("t") != "welcome") {
                listener.onClosed("协议错误：期望 welcome")
                return
            }
            val capList = obj.optJSONArray("cap")
            val canTouch = capList != null && (0 until capList.length()).any { capList.optString(it) == "touch" }
            val welcome = Welcome(
                sid = obj.optString("sid"),
                width = obj.optInt("w", 1080),
                height = obj.optInt("h", 1920),
                fps = obj.optInt("fps", 30),
                free = obj.optBoolean("free", true),
                pinRequired = obj.optBoolean("pin_required", true),
                name = obj.optString("name", "Host"),
                canTouch = canTouch
            )
            listener.onWelcome(welcome)

            s.soTimeout = 0
            writer = PacketWriter(s.getOutputStream())
            reader = PacketReader(s.getInputStream())
            startPingLoop()

            // ---- 收流
            while (running) {
                val p = reader?.next() ?: break
                when (p.type) {
                    Proto.T_CONFIG -> listener.onConfig(p.data)
                    Proto.T_AUDIO_CONFIG -> listener.onAudioConfig(p.data)
                    Proto.T_AUDIO -> listener.onAudio(p.data, p.ptsMs)
                    Proto.T_VIDEO -> {
                        framesReceived++
                        lastPts = p.ptsMs
                        listener.onVideo(p.data, p.ptsMs, p.isKeyFrame)
                    }

                    Proto.T_CONTROL -> listener.onHostControl(String(p.data, Charsets.UTF_8))
                    Proto.T_PING -> {
                        val seq = Proto.getInt(p.data, 0)
                        val send = Proto.getInt(p.data, 4)
                        val pong = ByteArray(8)
                        Proto.putInt(pong, 0, seq)
                        Proto.putInt(pong, 4, send)
                        writer?.write(Proto.T_PONG, 0, 0, pong)
                    }

                    Proto.T_PONG -> {
                        val recv = android.os.SystemClock.elapsedRealtime()
                        val sentLocal = Proto.getInt(p.data, 4).toLong() and 0xFFFFFFFFL
                        val sample = (recv - sentLocal).toInt()
                        if (sample in 0..5000) rttMs = (rttMs * 3 + sample) / 4

                        // v1.1：12 字节 PONG 带主机当前时钟 → 算出音画同步用的时钟偏移
                        if (p.data.size >= 12) {
                            val hostNow = Proto.getInt(p.data, 8).toLong() and 0xFFFFFFFFL
                            val raw = hostNow + sample / 2 - recv
                            clockOffset = clockOffset?.let { (it * 0.85 + raw * 0.15).toLong() } ?: raw
                        }
                    }
                }
            }
            listener.onClosed("连接结束")
        } catch (e: IOException) {
            listener.onClosed(e.message ?: "网络错误")
        } catch (t: Throwable) {
            Log.e(TAG_V, "connect failed", t)
            listener.onClosed(t.message ?: "未知错误")
        } finally {
            close()
        }
    }

    private fun startPingLoop() {
        pingThread = Thread({
            var seq = 0
            while (running) {
                try {
                    val now = System.currentTimeMillis()
                    lastPingSentMs = now
                    writer?.writePing(seq++, now)
                    sendStat()
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Exception) {
                    return@Thread
                }
            }
        }, "lumo-ping").apply {
            isDaemon = true
            start()
        }
    }

    private fun sendStat() {
        try {
            writer?.writeControl(
                """{"t":"stat","recv":$framesReceived,"drop":0,"rtt":$rttMs,"dec_ms":0}"""
            )
        } catch (_: Exception) {
        }
    }

    fun requestKeyFrame() {
        try {
            writer?.writeControl("""{"t":"kf"}""")
        } catch (_: Exception) {
        }
    }

    /** 触摸回控：坐标是 [0,1] 归一化，主机侧再乘回屏幕尺寸。 */
    fun sendTouch(action: String, x: Float, y: Float) {
        try {
            writer?.writeControl(
                """{"t":"in","e":"$action","x":${"%.4f".format(x)},"y":${"%.4f".format(y)}}"""
            )
        } catch (_: Exception) {
        }
    }

    fun sendKey(code: String) {
        try {
            writer?.writeControl("""{"t":"key","code":"$code","down":true}""")
        } catch (_: Exception) {
        }
    }

    fun currentRtt(): Int = rttMs

    /** hostClock ≈ localClock + offset；未收到扩展 PONG 前为 null。 */
    fun clockOffsetMs(): Long? = clockOffset

    fun close() {
        if (!running && socket == null) return
        running = false
        try {
            writer?.writeControl("""{"t":"bye"}""")
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        pingThread?.interrupt()
        pingThread = null
    }
}
