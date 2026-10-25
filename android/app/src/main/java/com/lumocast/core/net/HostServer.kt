package com.lumocast.core.net

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "LumoHost"

/** 待发送的一帧视频/配置。 */
class Frame(val type: Int, val flags: Int, val ptsMs: Long, val data: ByteArray)

/**
 * 主机侧 TCP 服务：握手 + 分发视频 + 收回控/统计。
 *
 * 线程模型：
 *  - accept 线程：接受连接 → 握手 → 交给 Client
 *  - 每客户端一个 tx 线程：从容量 2 的队列取帧写 socket（满了丢最旧 → 绝不阻塞编码）
 *  - 每客户端一个 rx 线程：读控制包
 */
class HostServer(
    private val deviceName: String,
    private val pinRequired: Boolean,
    val pin: String,
    private val freeMode: Boolean,
    private val maxFps: Int,
    private val onViewersChanged: (List<ViewerInfo>) -> Unit = {},
    private val onLog: (String) -> Unit = {},
    private val onNeedKeyFrame: () -> Unit = {},
    private val onTouch: (action: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    private val onKey: (String) -> Unit = {}
) {

    data class ViewerInfo(
        val id: String,
        val name: String,
        val ip: String,
        val rttMs: Int = 0,
        val dropFrames: Int = 0,
        val latencyMs: Int = 0
    )

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val pinFailures = HashMap<String, MutableList<Long>>()

    @Volatile private var csd: ByteArray? = null
    @Volatile private var audioConfig: ByteArray? = null
    @Volatile private var running = false
    @Volatile private var rotationW = 0
    @Volatile private var rotationH = 0

    var port: Int = 0
        private set

    val viewerCount: Int get() = clients.size
    private val maxViewers: Int
        get() = if (freeMode) Proto.MAX_VIEWERS_FREE else Proto.MAX_VIEWERS_PRO

    // ---------------------------------------------------------------- lifecycle

    fun start(): Int {
        var lastError: Exception? = null
        for (p in Proto.PORT_CONTROL..Proto.PORT_CONTROL_MAX) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(p), 8)
                }
                port = p
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (serverSocket == null) {
            throw IOException("无法监听端口 ${Proto.PORT_CONTROL}-${Proto.PORT_CONTROL_MAX}: ${lastError?.message}")
        }
        running = true
        acceptThread = Thread({ acceptLoop() }, "lumo-accept").apply {
            isDaemon = true
            start()
        }
        onLog("listening on $port")
        return port
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        for (c in clients.toList()) c.close("host_stop")
        clients.clear()
        publishViewers()
        acceptThread?.interrupt()
        acceptThread = null
    }

    // ---------------------------------------------------------------- data in

    /** 编码器产出 SPS/PPS：缓存一份，给新连接的客户端立即发送。 */
    fun submitConfig(csd: ByteArray) {
        if (this.csd?.contentEquals(csd) == true) return
        this.csd = csd
        for (c in clients) c.enqueue(Frame(Proto.T_CONFIG, 0, 0, csd))
    }

    fun submitFrame(data: ByteArray, ptsMs: Long, keyFrame: Boolean) {
        val f = Frame(Proto.T_VIDEO, if (keyFrame) Proto.F_KEYFRAME else 0, ptsMs, data)
        for (c in clients) c.enqueue(f)
    }

    /**
     * 音频配置（AudioSpecificConfig）。和视频的 CONFIG 一样，缓存一份给新客户端。
     * 音画同步的前提是两边都能把 pts 换算到同一个主机时钟，所以音频必须走同一条 TCP、
     * 同一个队列 —— 分成两条连接会让音频超前或落后几百毫秒。
     */
    fun submitAudioConfig(asc: ByteArray) {
        if (this.audioConfig?.contentEquals(asc) == true) return
        this.audioConfig = asc
        for (c in clients) c.enqueue(Frame(Proto.T_AUDIO_CONFIG, 0, 0, asc))
    }

    fun submitAudio(data: ByteArray, ptsMs: Long) {
        val f = Frame(Proto.T_AUDIO, 0, ptsMs, data)
        for (c in clients) c.enqueue(f)
    }

    fun broadcastControl(json: String) {
        for (c in clients) {
            try {
                c.writer.writeControl(json)
            } catch (_: Exception) {
            }
        }
    }

    fun notifyRotation(w: Int, h: Int) {
        rotationW = w
        rotationH = h
        csd = null // 旋转后需要新的 SPS/PPS
        broadcastControl("""{"t":"rotate","w":$w,"h":$h,"rot":0}""")
    }

    // ---------------------------------------------------------------- accept

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            Thread({ handleSocket(socket) }, "lumo-hs").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        val ip = socket.inetAddress?.hostAddress ?: "?"
        try {
            socket.tcpNoDelay = true              // 关 Nagle：一个包就发，不攒
            socket.sendBufferSize = 128 * 1024    // 内核排队越少，抖动越小
            socket.keepAlive = true
            try {
                socket.trafficClass = 0x10        // IPTOS_LOWDELAY
            } catch (_: Exception) {
            }
            socket.soTimeout = 10_000

            val line = readHandshakeLine(socket.getInputStream())
            if (line == null) {
                socket.close()
                return
            }
            val hello = try {
                JSONObject(line)
            } catch (e: Exception) {
                reject(socket, "unsupported", "握手格式错误")
                return
            }
            if (hello.optString("t") != "hello") {
                reject(socket, "unsupported", "期望 hello")
                return
            }
            val protoV = hello.optInt("v", 1)
            if (protoV > Proto.VERSION) {
                reject(socket, "unsupported", "对方协议版本过高，请升级 LumoCast")
                return
            }

            // ---- PIN
            if (pinRequired) {
                val provided = hello.optString("pin", "")
                if (!checkPin(ip, provided)) {
                    reject(socket, "bad_pin", "PIN 不正确")
                    onLog("bad pin from $ip")
                    return
                }
            }

            // ---- 容量
            if (clients.size >= maxViewers) {
                reject(socket, "busy", if (freeMode) "免费版同时只能 1 台设备观看" else "观看设备已达上限")
                return
            }

            val viewerName = hello.optString("device", "Unknown").take(48)
            val sid = java.lang.Long.toHexString(System.nanoTime() xor (Math.random() * 1e9).toLong())
            // BufferedOutputStream：把「16 字节头 + 负载」合并成一次 write 系统调用
            val writer = PacketWriter(java.io.BufferedOutputStream(socket.getOutputStream(), 128 * 1024))

            writeHandshakeLine(
                socket.getOutputStream(),
                buildString {
                    append("{\"t\":\"welcome\",\"v\":").append(Proto.VERSION)
                    append(",\"sid\":\"").append(sid).append('"')
                    append(",\"w\":").append(rotationW.coerceAtLeast(1080))
                    append(",\"h\":").append(rotationH.coerceAtLeast(1920))
                    append(",\"fps\":").append(maxFps)
                    append(",\"codec\":\"avc\"")
                    append(",\"free\":").append(freeMode)
                    append(",\"pin_required\":").append(pinRequired)
                    append(",\"name\":").append(JSONObject.quote(deviceName))
                    append(",\"cap\":[\"h264\",\"audio\",\"av\"")
                    if (!freeMode) append(",\"touch\",\"record\",\"hd\"")
                    append("]}")
                }
            )

            socket.soTimeout = 0 // 之后进入无超时的帧流模式

            val client = Client(sid, viewerName, ip, socket, writer)
            clients.add(client)
            publishViewers()
            onLog("viewer connected: $viewerName ($ip)")
            client.start()

            // 已经缓存过 CSD 就直接发，立即请求一个 IDR 让首帧最快出现
            csd?.let { client.enqueue(Frame(Proto.T_CONFIG, 0, 0, it)) }
            audioConfig?.let { client.enqueue(Frame(Proto.T_AUDIO_CONFIG, 0, 0, it)) }
            onNeedKeyFrame()

            client.readLoop()
        } catch (t: Throwable) {
            Log.w(TAG, "handshake failed from $ip: ${t.message}")
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun checkPin(ip: String, provided: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(pinFailures) {
            val list = pinFailures.getOrPut(ip) { ArrayList() }
            list.removeAll { now - it > 300_000 } // 5 分钟窗口
            if (list.size >= 5) return false
            val ok = provided.isNotEmpty() && provided == pin
            if (!ok) list.add(now)
            return ok
        }
    }

    private fun reject(socket: Socket, code: String, msg: String) {
        try {
            writeHandshakeLine(
                socket.getOutputStream(),
                """{"t":"error","code":"$code","msg":${JSONObject.quote(msg)}}"""
            )
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- client

    private inner class Client(
        val id: String,
        val name: String,
        val ip: String,
        private val socket: Socket,
        val writer: PacketWriter
    ) {
        private val queue = ArrayBlockingQueue<Frame>(8)
        private val reader = PacketReader(socket.getInputStream())
        private var txThread: Thread? = null

        @Volatile private var alive = true
        @Volatile var rttMs = 0
        @Volatile var dropFrames = 0

        fun start() {
            txThread = Thread({ txLoop() }, "lumo-tx").apply {
                isDaemon = true
                start()
            }
        }

        fun enqueue(f: Frame) {
            if (queue.offer(f)) return
            // 队列满 = 该客户端网络跟不上。优先丢「最旧的 delta 视频帧」：
            // 丢视频只是少一帧画面，丢音频会爆音，丢 IDR 会导致长时间花屏。
            val victim = queue.firstOrNull {
                it.type == Proto.T_VIDEO && (it.flags and Proto.F_KEYFRAME) == 0
            }
            if (victim != null) queue.remove(victim) else queue.poll()
            if (!queue.offer(f)) dropFrames++
        }

        private fun txLoop() {
            try {
                while (alive && running) {
                    val f = queue.take()
                    writer.write(f.type, f.flags, f.ptsMs, f.data)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                close("write_error")
            }
        }

        fun readLoop() {
            try {
                while (alive && running) {
                    val p = reader.next() ?: break
                    when (p.type) {
                        Proto.T_PING -> {
                            // v1.1：PONG = seq(4) + 客户端发送时刻(4) + 主机当前时钟(4)
                            // 接收端据此算出 hostClock - localClock 的偏移，用于音画同步。
                            val seq = Proto.getInt(p.data, 0)
                            val send = Proto.getInt(p.data, 4)
                            val pong = ByteArray(12)
                            Proto.putInt(pong, 0, seq)
                            Proto.putInt(pong, 4, send)
                            Proto.putInt(pong, 8, (SystemClock.elapsedRealtime() and 0xFFFFFFFFL).toInt())
                            writer.write(Proto.T_PONG, 0, 0, pong)
                        }

                        Proto.T_CONTROL -> handleControl(String(p.data, Charsets.UTF_8))
                        else -> Unit // 未知类型忽略（前向兼容）
                    }
                }
            } catch (_: Exception) {
            }
            close("peer_closed")
        }

        private fun handleControl(json: String) {
            val o = try {
                JSONObject(json)
            } catch (e: Exception) {
                return
            }
            when (o.optString("t")) {
                "kf" -> onNeedKeyFrame()
                "stat" -> {
                    rttMs = o.optInt("rtt", rttMs)
                    publishViewers()
                }

                "in" -> {
                    if (freeMode) return // 回控是 Pro 能力，由主机侧 license 决定
                    val x = o.optDouble("x", 0.0).toFloat()
                    val y = o.optDouble("y", 0.0).toFloat()
                    val action = when (o.optString("e")) {
                        "down" -> 0
                        "move" -> 1
                        "up" -> 2
                        else -> 3
                    }
                    onTouch(action, x, y)
                }

                "key" -> onKey(o.optString("code"))
                "bye" -> close("bye")
            }
        }

        fun close(reason: String) {
            if (!alive) return
            alive = false
            try {
                socket.close()
            } catch (_: Exception) {
            }
            txThread?.interrupt()
            clients.remove(this)
            publishViewers()
            onLog("viewer left: $name ($reason)")
        }
    }

    private fun publishViewers() {
        onViewersChanged(clients.map { ViewerInfo(it.id, it.name, it.ip, it.rttMs) })
    }

    companion object {
        /** 给二维码/UI 用：取第一个可用的 IPv4 局域网地址。 */
        fun localIpv4(): String? {
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith("192.168.") || host.startsWith("10.") ||
                                host.startsWith("172.") || host.startsWith("169.254.")
                            ) return host
                        }
                    }
                }
                // 兜底：任意一个非 loopback IPv4
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                    }
                }
            } catch (_: Exception) {
            }
            return null
        }
    }
}
