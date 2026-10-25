package com.lumocast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lumocast.MainActivity
import com.lumocast.R
import com.lumocast.core.media.AudioCapture
import com.lumocast.core.media.ScreenEncoder
import com.lumocast.core.media.ScreenRecorder
import com.lumocast.core.net.DiscoveryBroadcaster
import com.lumocast.core.net.HostServer
import com.lumocast.core.net.Proto
import com.lumocast.data.HostState
import com.lumocast.data.LiveSession
import com.lumocast.data.Prefs
import com.lumocast.data.ViewerLite
import org.json.JSONObject
import java.io.File

/**
 * 常驻前台服务：持有 MediaProjection，串起 编码器 / TCP 服务 / 广播 / 录制。
 *
 * 为什么必须是 Service：MediaProjection 在 Activity 销毁后仍需要存活（用户会切到别的 App 演示），
 * Android 10+ 还要求 mediaProjection 类型的前台服务才能持续采集。
 */
class HostService : Service() {

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var server: HostServer? = null
    private var broadcaster: DiscoveryBroadcaster? = null
    private var recorder: ScreenRecorder? = null
    private var audio: AudioCapture? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private var prefs: Prefs? = null
    private var encWidth = 1080
    private var encHeight = 1920
    private var announced = false

    // ------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything("user_stop")
                return START_NOT_STICKY
            }

            ACTION_START -> {
                prefs = Prefs.get(this)
                startAsForeground()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null || code == 0) {
                    LiveSession.update { it.copy(error = "屏幕捕获授权无效，请重试") }
                    stopSelf()
                    return START_NOT_STICKY
                }
                begin(code, data)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEverything("destroy")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转/折叠屏展开：分辨率变了，必须重建编码器，否则接收端会花屏
        if (encoder != null && announced) {
            handler.post {
                Log.i(TAG, "configuration changed → restart encoder")
                restartEncoder()
            }
        }
    }

    // ------------------------------------------------------------ start

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.host_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    enableVibration(false)
                }
            )
        }
        val notif = buildNotification(0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(viewers: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (viewers > 0) getString(R.string.host_watchers, viewers) else getString(R.string.host_waiting)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.host_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.host_stop), stopIntent)
            .build()
    }

    private fun begin(resultCode: Int, data: Intent) {
        try {
            val size = computeSize()
            encWidth = size.first
            encHeight = size.second

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(resultCode, data) ?: run {
                LiveSession.update { it.copy(error = "无法获取屏幕捕获权限") }
                stopSelf()
                return
            }
            projection = p

            val pfx = prefs ?: Prefs.get(this).also { prefs = it }
            val pin = pfx.pin
            val fps = pfx.maxFps
            val bitrate = ScreenEncoder.suggestBitrate(encWidth, encHeight, fps)

            val srv = HostServer(
                deviceName = pfx.deviceName,
                pinRequired = pfx.pinEnabled,
                pin = pin,
                maxFps = fps,
                onViewersChanged = { infos ->
                    val lite = infos.map { ViewerLite(it.id, it.name, it.ip, it.rttMs) }
                    LiveSession.update { it.copy(viewers = lite) }
                    updateNotification(lite.size)
                },
                onLog = { Log.d(TAG, it) },
                onNeedKeyFrame = { encoder?.requestKeyFrame() },
                onTouch = { action, x, y ->
                    // 触摸回控需要系统级注入权限（root/无障碍），开源版只在 Pro + 无障碍模式下透传，
                    // 未实现时保持静默，避免给用户「按了没反应」的错觉 → 由接收端 UI 提示不支持。
                    Log.d(TAG, "touch $action ($x,$y) ignored: input injection not enabled")
                },
                onKey = { code -> Log.d(TAG, "key $code ignored") }
            )
            val port = srv.start()
            server = srv

            val ip = HostServer.localIpv4()
            LiveSession.setHost(
                HostState(
                    running = true,
                    ip = ip,
                    port = port,
                    pin = pin,
                    pinRequired = pfx.pinEnabled,
                    fps = fps,
                    bitrateKbps = bitrate / 1000,
                    width = encWidth,
                    height = encHeight
                )
            )

            val enc = ScreenEncoder(
                projection = p,
                width = encWidth,
                height = encHeight,
                densityDpi = resources.displayMetrics.densityDpi,
                fps = fps,
                initialBitrate = bitrate,
                listener = object : ScreenEncoder.Listener {
                    override fun onCodecConfig(csd: ByteArray) {
                        srv.submitConfig(csd)
                        recorder?.onConfig(csd)
                    }

                    override fun onVideoFrame(data: ByteArray, ptsMs: Long, keyFrame: Boolean) {
                        srv.submitFrame(data, ptsMs, keyFrame)
                        recorder?.onFrame(data, ptsMs * 1000, keyFrame)
                    }

                    override fun onError(message: String) {
                        LiveSession.update { it.copy(error = message) }
                        handler.post { stopEverything("encoder_error") }
                    }
                }
            )
            encoder = enc
            enc.start()

            // ---- 系统音频（Android 10+）
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                audio = AudioCapture(p, object : AudioCapture.Listener {
                    override fun onAudioConfig(asc: ByteArray) {
                        srv.submitAudioConfig(asc)
                    }

                    override fun onAudioFrame(data: ByteArray, ptsMs: Long) {
                        srv.submitAudio(data, ptsMs)
                    }

                    override fun onError(message: String) {
                        Log.w(TAG, "audio: $message")
                        LiveSession.update { it.copy(audio = false) }
                    }
                })
                audio?.start()
                LiveSession.update { it.copy(audio = audio?.supported == true) }
            } else {
                Log.i(TAG, "RECORD_AUDIO not granted → 静音投屏")
            }

            if (pfx.recordingEnabled) {
                val dir = File(cacheDir, "recordings").apply { mkdirs() }
                recorder = ScreenRecorder(
                    File(dir, "lumocast-${System.currentTimeMillis()}.mp4"), encWidth, encHeight
                )
            }

            // ---- 局域网广播
            val nonce = Integer.toHexString((System.nanoTime() and 0xFFFFFF).toInt())
            broadcaster = DiscoveryBroadcaster {
                JSONObject().apply {
                    put("svc", "lumocast")
                    put("t", "announce")
                    put("v", Proto.VERSION)
                    put("name", pfx.deviceName)
                    put("port", port)
                    put("pin", pfx.pinEnabled)
                    put("w", encWidth)
                    put("h", encHeight)
                    put("fps", fps)
                    put("nonce", nonce)
                    put("cap", org.json.JSONArray(listOf("h264")))
                }.toString()
            }.also { it.start() }
            announced = true

            acquireWifiLock()
            handler.post(adaptRunnable)
            Log.i(TAG, "hosting on $ip:$port ${encWidth}x$encHeight@$fps")
        } catch (t: Throwable) {
            Log.e(TAG, "begin failed", t)
            LiveSession.update { it.copy(error = t.message ?: "启动失败") }
            stopEverything("start_failed")
        }
    }

    private fun restartEncoder() {
        val old = encoder ?: return
        val p = projection ?: return
        old.stop()
        val size = computeSize()
        encWidth = size.first
        encHeight = size.second
        val pfx = prefs ?: return
        val fps = pfx.maxFps

        val enc = ScreenEncoder(
            projection = p,
            width = encWidth,
            height = encHeight,
            densityDpi = resources.displayMetrics.densityDpi,
            fps = fps,
            initialBitrate = ScreenEncoder.suggestBitrate(encWidth, encHeight, fps),
            listener = object : ScreenEncoder.Listener {
                override fun onCodecConfig(csd: ByteArray) {
                    server?.submitConfig(csd)
                }

                override fun onVideoFrame(data: ByteArray, ptsMs: Long, keyFrame: Boolean) {
                    server?.submitFrame(data, ptsMs, keyFrame)
                }

                override fun onError(message: String) {
                    LiveSession.update { it.copy(error = message) }
                }
            }
        )
        encoder = enc
        enc.start()
        server?.notifyRotation(encWidth, encHeight)
        LiveSession.update { it.copy(width = encWidth, height = encHeight) }
    }

    /** 屏幕尺寸 → 编码尺寸：必须偶数，短边不超过上限，长边不超过 2400。 */
    private fun computeSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            dm.widthPixels = bounds.width()
            dm.heightPixels = bounds.height()
            dm.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        }
        var w = dm.widthPixels
        var h = dm.heightPixels

        // 跟随用户设置，最高 1440p / 长边 2560
        val pref = prefs?.maxShortSide ?: 0
        val cap = if (pref in 1..Proto.MAX_SHORT_SIDE) pref else Proto.MAX_SHORT_SIDE
        val shortSide = minOf(w, h)
        if (shortSide > cap) {
            val scale = cap.toFloat() / shortSide
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        val longSideCap = Proto.MAX_LONG_SIDE
        if (maxOf(w, h) > longSideCap) {
            val scale = longSideCap.toFloat() / maxOf(w, h)
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        return (w / 2 * 2).coerceAtLeast(320) to (h / 2 * 2).coerceAtLeast(320)
    }

    // ------------------------------------------------------------ adaptive bitrate

    private val adaptRunnable = object : Runnable {
        override fun run() {
            val state = LiveSession.host.value
            val enc = encoder
            if (enc != null && state.viewers.isNotEmpty()) {
                val worstRtt = state.viewers.maxOf { it.rttMs }
                val current = enc.currentBitrate()
                val target = when {
                    worstRtt > 250 -> (current * 0.8).toInt()
                    worstRtt in 1..120 -> (current * 1.08).toInt()
                    else -> current
                }.coerceIn(Proto.BITRATE_MIN, Proto.BITRATE_MAX)
                if (Math.abs(target - current) > current / 8) {
                    enc.setBitrate(target)
                    LiveSession.update { it.copy(bitrateKbps = target / 1000) }
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    // ------------------------------------------------------------ stop

    private fun stopEverything(reason: String) {
        handler.removeCallbacks(adaptRunnable)
        try {
            audio?.stop()
        } catch (_: Throwable) {
        }
        audio = null
        try {
            recorder?.stop()?.let { Log.i(TAG, "recording saved: $it") }
        } catch (_: Throwable) {
        }
        recorder = null
        try {
            broadcaster?.stop()
        } catch (_: Throwable) {
        }
        broadcaster = null
        try {
            server?.stop()
        } catch (_: Throwable) {
        }
        server = null
        try {
            encoder?.stop()
        } catch (_: Throwable) {
        }
        encoder = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        releaseWifiLock()
        announced = false
        LiveSession.reset()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
        Log.i(TAG, "stopped: $reason")
    }

    private fun updateNotification(viewers: Int) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(viewers))
        } catch (_: Throwable) {
        }
    }

    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lumocast:host")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (t: Throwable) {
            Log.w(TAG, "wifi lock failed: ${t.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
        wifiLock = null
    }

    companion object {
        private const val TAG = "LumoHostService"
        private const val CHANNEL_ID = "lumocast_host"
        private const val NOTIF_ID = 4711

        const val ACTION_START = "com.lumocast.action.START"
        const val ACTION_STOP = "com.lumocast.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, HostService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HostService::class.java).setAction(ACTION_STOP))
        }
    }
}
