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
            // 192KB：2MB 是 8Mbps 下足足 2 秒的缓冲，一次网络抖动就会把这些数据全变成「迟到的画面」。
            // 留 192KB 足够吸收 Wi-Fi 抖动，又不会把延迟永久垫高。
            s.receiveBufferSize = 192 * 1024
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

            // 收流阶段也要有超时：Wi-Fi 掉线不会收到 FIN，否则会一直「画面僵住」而不知道断了
            s.soTimeout = Proto.NET_IDLE_TIMEOUT_MS
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
                        val recv = android.os.SystemClock.uptimeMillis()
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
        } catch (e: java.net.SocketTimeoutException) {
            listener.onClosed("网络中断：${Proto.NET_IDLE_TIMEOUT_MS / 1000} 秒没有收到数据")
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
                    // 必须用开机单调时钟（uptime），不能用 System.currentTimeMillis()：
                    // 后者是墙钟毫秒（约 1.7e12），塞进 32 位 int 后会绕圈，
                    // 对端减出来的 RTT 变成一个巨大的负数被过滤掉 —— 结果 rtt 永远是 0，
                    // 延迟显示失真、主机自适应码率也完全瞎了。
                    val now = android.os.SystemClock.uptimeMillis()
                    lastPingSentMs = now
                    writer?.writePing(seq++, now)
                    sendStat()
                } catch (_: Exception) {
                    // 单次写失败（拥塞、瞬时丢包）不能让心跳线程退出：
                    // 心跳一停，主机 8 秒后就把这条连接判死 → 用户看到的就是「连一会儿自己断了」
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
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
