package com.lumocast.data

import android.content.Context
import android.content.SharedPreferences
import com.lumocast.core.net.PairTokens

/**
 * 极简设置存储。刻意不用 DataStore：这里只有 8 个键、读写同步，
 * 引入 Flow/协程管道带来的复杂度远大于收益。
 */
class Prefs private constructor(private val sp: SharedPreferences) {

    var deviceName: String
        get() = sp.getString(KEY_NAME, null) ?: defaultName()
        set(v) = sp.edit().putString(KEY_NAME, v.take(32)).apply()

    var pinEnabled: Boolean
        get() = sp.getBoolean(KEY_PIN_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_PIN_ENABLED, v).apply()

    var pin: String
        get() = sp.getString(KEY_PIN, null) ?: randomPin().also { pin = it }
        set(v) = sp.edit().putString(KEY_PIN, v.filter { it.isDigit() }.take(4)).apply()

    var maxFps: Int
        get() = sp.getInt(KEY_MAX_FPS, 60)
        set(v) = sp.edit().putInt(KEY_MAX_FPS, v.coerceIn(15, 60)).apply()

    /** 0 = 跟随设备，720/1080 = 限制短边像素 */
    var maxShortSide: Int
        get() = sp.getInt(KEY_MAX_SHORT, 0)
        set(v) = sp.edit().putInt(KEY_MAX_SHORT, v).apply()

    var lastHost: String?
        get() = sp.getString(KEY_LAST_HOST, null)
        set(v) = sp.edit().putString(KEY_LAST_HOST, v).apply()

    var recordingEnabled: Boolean
        get() = sp.getBoolean(KEY_RECORD, false)
        set(v) = sp.edit().putBoolean(KEY_RECORD, v).apply()

    /** 是否允许观看端触摸回控；还需要系统无障碍服务实际开启。默认关闭。 */
    var remoteControlEnabled: Boolean
        get() = sp.getBoolean(KEY_REMOTE_CONTROL, false)
        set(v) = sp.edit().putBoolean(KEY_REMOTE_CONTROL, v).apply()

    // ---------------------------------------------------------- 主机：配对令牌

    /** 校验观看端带来的 token；过期条目顺手清理并写回。 */
    fun validatePairToken(token: String, now: Long = System.currentTimeMillis()): Boolean {
        if (token.isBlank()) return false
        synchronized(pairLock) {
            val map = PairTokens.parse(sp.getString(KEY_PAIR_TOKENS, null))
            PairTokens.prune(map, now)
            val ok = PairTokens.isValid(map, token, now)
            sp.edit().putString(KEY_PAIR_TOKENS, PairTokens.serialize(map)).apply()
            return ok
        }
    }

    /** PIN 验证通过后签发一个新 token（7 天），返回给观看端保存。 */
    fun issuePairToken(now: Long = System.currentTimeMillis()): String {
        synchronized(pairLock) {
            val map = PairTokens.parse(sp.getString(KEY_PAIR_TOKENS, null))
            val token = PairTokens.issue(map, now = now)
            sp.edit().putString(KEY_PAIR_TOKENS, PairTokens.serialize(map)).apply()
            return token
        }
    }

    fun revokeAllPairTokens() {
        synchronized(pairLock) {
            sp.edit().remove(KEY_PAIR_TOKENS).apply()
        }
    }

    fun pairTokenCount(now: Long = System.currentTimeMillis()): Int {
        synchronized(pairLock) {
            val map = PairTokens.parse(sp.getString(KEY_PAIR_TOKENS, null))
            PairTokens.prune(map, now)
            return map.size
        }
    }

    // ---------------------------------------------------------- 观看端：记住上次配对令牌

    fun viewerPairToken(hostKey: String): String? {
        if (sp.getString(KEY_VIEWER_PAIR_HOST, null) != hostKey) return null
        return sp.getString(KEY_VIEWER_PAIR_TOKEN, null)
    }

    fun setViewerPairToken(hostKey: String, token: String?) {
        if (token.isNullOrBlank()) {
            sp.edit()
                .remove(KEY_VIEWER_PAIR_TOKEN)
                .remove(KEY_VIEWER_PAIR_HOST)
                .apply()
        } else {
            sp.edit()
                .putString(KEY_VIEWER_PAIR_TOKEN, token)
                .putString(KEY_VIEWER_PAIR_HOST, hostKey)
                .apply()
        }
    }

    private fun defaultName(): String =
        "Android " + (android.os.Build.MODEL ?: "").take(18).ifBlank { "device" }

    private fun randomPin(): String = (1000..9999).random().toString()

    companion object {
        private const val KEY_NAME = "device_name"
        private const val KEY_PIN_ENABLED = "pin_enabled"
        private const val KEY_PIN = "pin"
        private const val KEY_MAX_FPS = "max_fps"
        private const val KEY_MAX_SHORT = "max_short_side"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_RECORD = "record_enabled"
        private const val KEY_REMOTE_CONTROL = "remote_control_enabled"
        private const val KEY_PAIR_TOKENS = "pair_tokens"
        private const val KEY_VIEWER_PAIR_TOKEN = "viewer_pair_token"
        private const val KEY_VIEWER_PAIR_HOST = "viewer_pair_host"

        private val pairLock = Any()

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(
                    context.applicationContext.getSharedPreferences("lumocast", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
