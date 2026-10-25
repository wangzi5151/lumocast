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
    private val maxFps: Int,
    /** 真实编码尺寸：welcome 里必须给对，否则接收端按错误宽高比渲染（拉伸/黑边）。 */
    private val initWidth: Int = 0,
    private val initHeight: Int = 0,
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

    private val videoW: Int get() = if (rotationW > 0) rotationW else initWidth.takeIf { it > 0 } ?: 1080
    private val videoH: Int get() = if (rotationH > 0) rotationH else initHeight.takeIf { it > 0 } ?: 1920
    private val maxViewers: Int
        get() = Proto.MAX_VIEWERS

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
        broadcastControl("""{"t":"end"}""")
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
                reject(socket, "busy", "观看设备已达上限（${Proto.MAX_VIEWERS} 台）")
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
                    append(",\"w\":").append(videoW)
                    append(",\"h\":").append(videoH)
                    append(",\"fps\":").append(maxFps)
                    append(",\"codec\":\"avc\"")
                    append(",\"pin_required\":").append(pinRequired)
                    append(",\"name\":").append(JSONObject.quote(deviceName))
                    append(",\"cap\":[\"h264\",\"audio\",\"av\"")
                    append(",\"touch\",\"record\",\"hd\"")
                    append("]}")
                }
            )

            // 之后用空闲超时兜底：对端每 1s 有 PING，8s 没有任何字节 = 链路已死
            socket.soTimeout = Proto.NET_IDLE_TIMEOUT_MS

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
            } catch (_: java.net.SocketTimeoutException) {
                close("idle") // 半开连接：Wi-Fi 断了但没收到 FIN
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
        /**
         * 给二维码 / UI 用的本机地址。
         *
         * 直接「第一个非 loopback IPv4」是错的：开着数据网络、VPN、Tailscale、Docker 的机器上
         * 常常先枚举到 rmnet0(10.x) / tun0 / 172.17.x —— 二维码里就变成一个对方根本连不上的地址，
         * 表现就是「扫了没反应 / 一直转圈」。所以按网卡名打分：Wi-Fi > 以太网 > 其它，虚拟网卡直接排除。
         */
        fun localIpv4(): String? {
            data class Cand(val name: String, val host: String, val score: Int)
            val out = ArrayList<Cand>()
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    val name = (nif.name ?: "").lowercase()
                    if (name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("tap") ||
                        name.startsWith("ppp") || name.startsWith("rmnet") || name.startsWith("ccmni") ||
                        name.startsWith("clat") || name.startsWith("ip6") || name.startsWith("sit") ||
                        name.contains("docker") || name.contains("veth")
                    ) continue
                    val base = when {
                        name.startsWith("wlan") -> 100
                        name.startsWith("ap") || name.startsWith("softap") -> 90
                        name.startsWith("eth") -> 80
                        name.startsWith("p2p") -> 40
                        else -> 10
                    }
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val host = addr.hostAddress ?: continue
                            val bonus = when {
                                host.startsWith("192.168.") -> 5
                                host.startsWith("10.") -> 3
                                host.startsWith("172.") -> 1
                                else -> 0
                            }
                            out.add(Cand(name, host, base + bonus))
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return out.maxByOrNull { it.score }?.host
        }
    }
}
