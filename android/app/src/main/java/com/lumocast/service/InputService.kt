package com.lumocast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * 可选的触摸回控注入服务。
 *
 * 安全边界：
 *  - 用户必须手动在系统「无障碍」设置里开启本服务；
 *  - 主机设置里还必须打开「允许触摸回控」（默认关闭）；
 *  - 只有通过 PIN / 配对令牌认证的观看者能发 `in` / `key`；
 *  - 不读取窗口内容（canRetrieveWindowContent=false），只做手势注入与三个全局按键。
 *
 * 能力边界：Android 不允许把一段「按下 → 移动 → 抬起」拆成多次注入，
 * 所以这里在收到 `up` 时把整条路径一次性交给系统 —— 支持点击与滑动，
 * 不支持需要实时按住不放的复杂手势。这个限制写进了 README / 设置页文案。
 */
class InputService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var path: Path? = null
    private var strokeStartedAt = 0L
    private var lastX = 0f
    private var lastY = 0f

    private val clearRunnable = Runnable { clearStroke() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility input service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun enqueueTouch(action: Int, nx: Float, ny: Float) {
        handler.post { applyTouch(action, nx, ny) }
    }

    private fun applyTouch(action: Int, nx: Float, ny: Float) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat().coerceAtLeast(1f)
        val h = dm.heightPixels.toFloat().coerceAtLeast(1f)
        val x = nx.coerceIn(0f, 1f) * (w - 1f)
        val y = ny.coerceIn(0f, 1f) * (h - 1f)

        when (action) {
            ACTION_DOWN -> {
                handler.removeCallbacks(clearRunnable)
                val p = Path().apply { moveTo(x, y) }
                path = p
                lastX = x
                lastY = y
                strokeStartedAt = SystemClock.uptimeMillis()
                handler.postDelayed(clearRunnable, 3000)
            }

            ACTION_MOVE -> {
                val p = path ?: return
                if (Math.abs(x - lastX) >= 1f || Math.abs(y - lastY) >= 1f) {
                    p.lineTo(x, y)
                    lastX = x
                    lastY = y
                }
                handler.removeCallbacks(clearRunnable)
                handler.postDelayed(clearRunnable, 3000)
            }

            ACTION_UP -> {
                val p = path ?: return
                if (Math.abs(x - lastX) >= 1f || Math.abs(y - lastY) >= 1f) {
                    p.lineTo(x, y)
                } else {
                    // 纯点击：GestureDescription 需要一个非空线段，补一个 1px 的微小位移
                    p.lineTo(x + 0.5f, y + 0.5f)
                }
                val duration = (SystemClock.uptimeMillis() - strokeStartedAt).coerceIn(40L, 1500L)
                dispatch(p, duration)
                clearStroke()
            }

            else -> clearStroke()
        }
    }

    private fun dispatch(p: Path, durationMs: Long) {
        try {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, durationMs.coerceAtLeast(1L)))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchGesture failed: ${t.message}")
        }
    }

    private fun clearStroke() {
        handler.removeCallbacks(clearRunnable)
        path = null
    }

    private fun injectKeyOnMain(code: String): Boolean {
        val action = when (code) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return false
        }
        return performGlobalAction(action)
    }

    companion object {
        private const val TAG = "LumoInput"

        private const val ACTION_DOWN = 0
        private const val ACTION_MOVE = 1
        private const val ACTION_UP = 2

        @Volatile private var instance: InputService? = null

        /** 用户是否已在系统设置里开启本服务。以系统列表为准，不轻信缓存实例。 */
        fun isReady(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val expected = ComponentName(context, InputService::class.java)
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { info ->
                        val si = info.resolveInfo?.serviceInfo ?: return@any false
                        ComponentName(si.packageName, si.name) == expected
                    }
            } catch (_: Throwable) {
                false
            }
        }

        fun injectTouch(action: Int, nx: Float, ny: Float): Boolean {
            val svc = instance ?: return false
            svc.enqueueTouch(action, nx, ny)
            return true
        }

        fun injectKey(code: String): Boolean {
            val svc = instance ?: return false
            svc.handler.post { svc.injectKeyOnMain(code) }
            return true
        }
    }
}
