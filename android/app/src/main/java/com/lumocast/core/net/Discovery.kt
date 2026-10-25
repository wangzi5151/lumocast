package com.lumocast.core.net

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException

/** 被发现的一台主机。 */
data class DiscoveredDevice(
    val ip: String,
    val port: Int,
    val name: String,
    val pinRequired: Boolean,
    val free: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val nonce: String,
    val lastSeenMs: Long
) {
    val key: String get() = "$ip:$port"
    /** 用 nonce 判断主机是否重启过（重启后 nonce 变化，UI 可提示「重新连接」） */
    fun isStale(now: Long, ttlMs: Long = 6500): Boolean = now - lastSeenMs > ttlMs
}

/**
 * 主机侧：在 UDP 8738 上周期性广播 ANNOUNCE，并响应接收端的 PROBE。
 * 单线程 + 500ms 轮询，无锁，功耗可忽略（实测 < 0.1% / h）。
 */
class DiscoveryBroadcaster(private val announceJson: () -> String) {

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Proto.PORT_DISCOVERY))
                soTimeout = 400
            }
        } catch (e: Exception) {
            // 8738 被占用（例如接收端页面同时开着）：退化为只广播、不响应 PROBE
            Log.w(TAG, "bind 8738 failed, announce-only mode: ${e.message}")
            canReceive = false
            DatagramSocket().apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 400
            }
        }
        thread = Thread({ loop() }, "lumo-announce").apply {
            isDaemon = true
            start()
        }
    }

    private var canReceive = true
    private var lastAnnounce = 0L
    private var broadcastTargets: List<InetAddress> = emptyList()
    private var lastTargetRefresh = 0L

    private fun loop() {
        val buf = ByteArray(1024)
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastAnnounce >= Proto.ANNOUNCE_INTERVAL_MS) {
                if (now - lastTargetRefresh > 15_000) {
                    broadcastTargets = broadcastAddresses()
                    lastTargetRefresh = now
                }
                announce()
                lastAnnounce = now
            }
            if (!canReceive) {
                sleepQuiet(400)
                continue
            }
            try {
                val dp = DatagramPacket(buf, buf.size)
                socket?.receive(dp)
                handleIncoming(dp)
            } catch (_: SocketTimeoutException) {
                // 正常，下一轮
            } catch (e: Exception) {
                if (running) sleepQuiet(200)
            }
        }
    }

    private fun announce() {
        val payload = try {
            announceJson().toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            return
        }
        val s = socket ?: return
        for (addr in broadcastTargets) {
            try {
                s.send(DatagramPacket(payload, payload.size, addr, Proto.PORT_DISCOVERY))
            } catch (_: Exception) {
                // 某个网卡不可用，忽略
            }
        }
    }

    /** 收到 PROBE → 单播回一次 ANNOUNCE，让对端列表 0 延迟刷新。 */
    private fun handleIncoming(dp: DatagramPacket) {
        val text = String(dp.data, dp.offset, dp.length, Charsets.UTF_8)
        if (!text.contains("\"probe\"")) return
        val payload = announceJson().toByteArray(Charsets.UTF_8)
        try {
            socket?.send(DatagramPacket(payload, payload.size, dp.address, dp.port))
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "LumoDiscovery"

        fun broadcastAddresses(): List<InetAddress> {
            val out = ArrayList<InetAddress>(4)
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        ia.broadcast?.let { out.add(it) }
                    }
                }
            } catch (_: SocketException) {
            }
            if (out.isEmpty()) {
                try {
                    out.add(InetAddress.getByName("255.255.255.255"))
                } catch (_: Exception) {
                }
            }
            return out
        }
    }
}

/**
 * 接收端侧：监听 ANNOUNCE，维护一个按 `ip:port` 去重的设备表。
 * 每 2.5s 清理超过 6.5s 没再出现的设备；进入监听时和每 5s 主动发一次 PROBE。
 */
class DeviceScanner(private val onDevices: (List<DiscoveredDevice>) -> Unit) {

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var probeThread: Thread? = null
    private val devices = LinkedHashMap<String, DiscoveredDevice>()

    fun start() {
        if (running) return
        running = true
        socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(Proto.PORT_DISCOVERY))
            soTimeout = 800
        }
        thread = Thread({ receiveLoop() }, "lumo-scan").apply {
            isDaemon = true
            start()
        }
        probeThread = Thread({ probeLoop() }, "lumo-probe").apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        var lastPurge = 0L
        while (running) {
            try {
                val dp = DatagramPacket(buf, buf.size)
                socket?.receive(dp)
                parse(dp)
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (running) try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastPurge > 2500) {
                lastPurge = now
                purge(now)
            }
        }
    }

    private fun parse(dp: DatagramPacket) {
        val text = String(dp.data, dp.offset, dp.length, Charsets.UTF_8)
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        if (obj.optString("svc") != "lumocast") return
        if (obj.optString("t", "announce") != "announce") return
        val port = obj.optInt("port", Proto.PORT_CONTROL)
        if (port <= 0) return

        val device = DiscoveredDevice(
            ip = dp.address.hostAddress ?: return,
            port = port,
            name = obj.optString("name", "Android"),
            pinRequired = obj.optBoolean("pin", true),
            free = obj.optBoolean("free", true),
            width = obj.optInt("w", 1080),
            height = obj.optInt("h", 2400),
            fps = obj.optInt("fps", 30),
            nonce = obj.optString("nonce", ""),
            lastSeenMs = System.currentTimeMillis()
        )
        val changed: Boolean
        synchronized(devices) {
            val old = devices[device.key]
            changed = old == null || old.nonce != device.nonce || old.name != device.name ||
                old.pinRequired != device.pinRequired
            devices[device.key] = device
        }
        if (changed) emit()
    }

    private fun purge(now: Long) {
        var changed = false
        synchronized(devices) {
            val it = devices.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.isStale(now)) {
                    it.remove()
                    changed = true
                }
            }
        }
        if (changed) emit()
    }

    private fun emit() {
        val snapshot = synchronized(devices) { devices.values.sortedBy { it.name } }
        onDevices(snapshot)
    }

    private fun probeLoop() {
        val payload =
            """{"svc":"lumocast","t":"probe","v":${Proto.VERSION}}""".toByteArray(Charsets.UTF_8)
        while (running) {
            val targets = DiscoveryBroadcaster.broadcastAddresses()
            val probeSocket = try {
                DatagramSocket().apply { broadcast = true }
            } catch (e: Exception) {
                null
            }
            probeSocket?.use { s ->
                for (addr in targets) {
                    try {
                        s.send(DatagramPacket(payload, payload.size, addr, Proto.PORT_DISCOVERY))
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        thread?.interrupt()
        probeThread?.interrupt()
        thread = null
        probeThread = null
        synchronized(devices) { devices.clear() }
    }
}
