package com.lumocast.ui

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumocast.core.media.AudioPlayer
import com.lumocast.core.media.H264Decoder
import com.lumocast.core.net.Proto
import com.lumocast.core.net.ViewerClient

/** 观看端 UI 状态。 */
data class ViewerUiState(
    val phase: Phase = Phase.CONNECTING,
    val error: String? = null,
    val width: Int = 1080,
    val height: Int = 1920,
    val fps: Double = 0.0,
    val mbps: Double = 0.0,
    val rttMs: Int = 0,
    /** 端到端（玻璃到玻璃）估算延迟，含编码 + 网络 + 解码 */
    val latencyMs: Int = 0,
    val canTouch: Boolean = false,
    val hostName: String = "",
    val audio: Boolean = false,
    val droppedFrames: Int = 0
) {
    enum class Phase { CONNECTING, LIVE, ERROR }
}

/**
 * 观看会话控制器：ViewerClient（网络）→ AudioPlayer / H264Decoder（解码上屏）。
 *
 * 音画同步策略（audio-master）：
 *   1. 音频包一到就解码进 AudioTrack，不做任何等待；
 *   2. 用「已写入帧数 − 播放头位置」反推出**正在听到**的宿主时间 audioClock；
 *   3. 视频帧 pts 与 audioClock 比较，落后 > 150ms 就丢弃（等下一个 IDR 或后续 delta），
 *      超前则照常送解码器（解码器内部按 pts 排队，不需要我们插帧）。
 * 结果：声音永远连续，画面偶尔掉一帧 —— 这是人类感知上最优的取舍。
 */
class ViewerController(
    private val ip: String,
    private val port: Int,
    private val pin: String?,
    private val deviceName: String
) {
    var state by mutableStateOf(ViewerUiState())
        private set

    private var client: ViewerClient? = null
    private var decoder: H264Decoder? = null
    private var audio: AudioPlayer? = null
    private var surface: Surface? = null
    private var csd: ByteArray? = null

    @Volatile private var running = false
    @Volatile private var frames = 0L
    @Volatile private var bytes = 0L
    @Volatile private var dropped = 0
    @Volatile private var lastPtsMs = 0L
    @Volatile private var lastFrameAtMs = 0L

    private var connectThread: Thread? = null
    private var statsThread: Thread? = null

    /** 视频落后音频超过这个值就丢帧（150ms ≈ 一个完整音节，超过就会被看出来「对不上口型」） */
    private val lateThresholdMs = 150L

    // ------------------------------------------------------------ lifecycle

    fun start() {
        if (running) return
        running = true
        connectThread = Thread({ connectLoop() }, "lumo-viewer").apply {
            isDaemon = true
            start()
        }
        statsThread = Thread({ statsLoop() }, "lumo-stats").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            client?.close()
        } catch (_: Throwable) {
        }
        client = null
        audio?.release()
        audio = null
        decoder?.release()
        decoder = null
        connectThread?.interrupt()
        statsThread?.interrupt()
    }

    fun attachSurface(s: Surface) {
        surface = s
        maybeConfigureDecoder()
    }

    fun detachSurface() {
        surface = null
        decoder?.release()
        decoder = null
    }

    fun requestKeyFrame() {
        client?.requestKeyFrame()
    }

    fun sendTouch(action: String, nx: Float, ny: Float) {
        client?.sendTouch(action, nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    fun sendKey(code: String) {
        client?.sendKey(code)
    }

    // ------------------------------------------------------------ internals

    private fun connectLoop() {
        val c = ViewerClient(
            host = ip,
            port = port,
            pin = pin,
            deviceName = deviceName,
            listener = object : ViewerClient.Listener {
                override fun onWelcome(welcome: ViewerClient.Welcome) {
                    state = state.copy(
                        phase = ViewerUiState.Phase.LIVE,
                        width = welcome.width,
                        height = welcome.height,
                        canTouch = welcome.canTouch,
                        hostName = welcome.name
                    )
                    maybeConfigureDecoder()
                    lastFrameAtMs = SystemClock.elapsedRealtime()
                }

                override fun onConfig(csd: ByteArray) {
                    this@ViewerController.csd = csd
                    maybeConfigureDecoder()
                }

                override fun onAudioConfig(asc: ByteArray) {
                    val player = audio ?: AudioPlayer().also { audio = it }
                    val ok = player.configure(asc, Proto.AUDIO_SAMPLE_RATE, Proto.AUDIO_CHANNELS)
                    if (ok) state = state.copy(audio = true)
                }

                override fun onAudio(data: ByteArray, ptsMs: Long) {
                    audio?.submit(data, ptsMs)
                }

                override fun onVideo(data: ByteArray, ptsMs: Long, keyFrame: Boolean) {
                    frames++
                    bytes += data.size.toLong()
                    lastFrameAtMs = SystemClock.elapsedRealtime()

                    // ---- 音画同步闸门：丢掉已经「过期」的 delta 帧
                    val clock = audio?.currentHostMs()
                    if (clock != null && !keyFrame && ptsMs - clock < -lateThresholdMs) {
                        dropped++
                        return
                    }
                    lastPtsMs = ptsMs
                    decoder?.submit(data, ptsMs, keyFrame)
                }

                override fun onHostControl(json: String) {
                    if (json.contains("\"end\"")) {
                        state = state.copy(phase = ViewerUiState.Phase.ERROR, error = "主机结束了共享")
                    }
                    if (json.contains("\"rotate\"")) {
                        // 旋转后码流尺寸变化：清掉旧解码器，等新的 CONFIG
                        decoder?.release()
                        decoder = null
                        csd = null
                    }
                }

                override fun onClosed(reason: String) {
                    if (!running) return
                    state = state.copy(phase = ViewerUiState.Phase.ERROR, error = reason)
                }
            }
        )
        client = c
        c.connect()
    }

    private fun maybeConfigureDecoder() {
        val s = surface ?: return
        val media = csd ?: return
        decoder?.release()
        decoder = H264Decoder(s, state.width, state.height) { msg ->
            Log.w("LumoViewerUI", "decoder: $msg")
        }.also { it.configure(media) }
    }

    private fun statsLoop() {
        var lastFrames = 0L
        var lastBytes = 0L
        while (running) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return
            }
            val f = frames
            val b = bytes
            val fps = (f - lastFrames).toDouble()
            val mbps = (b - lastBytes) * 8.0 / 1_000_000.0
            lastFrames = f
            lastBytes = b

            if (state.phase != ViewerUiState.Phase.LIVE) continue

            // 1.5s 没有新帧 → 主动要 IDR（比干等或重连都快）
            val stalled = SystemClock.elapsedRealtime() - lastFrameAtMs > 1500
            if (stalled && lastFrameAtMs > 0) client?.requestKeyFrame()

            // 端到端延迟：localNow − (pts − offset)
            val offset = client?.clockOffsetMs()
            val latency = if (offset != null && lastPtsMs > 0) {
                (SystemClock.elapsedRealtime() - (lastPtsMs - offset)).toInt().coerceIn(0, 3000)
            } else 0

            state = state.copy(
                fps = fps,
                mbps = mbps,
                rttMs = client?.currentRtt() ?: 0,
                latencyMs = latency,
                droppedFrames = dropped
            )
        }
    }
}
