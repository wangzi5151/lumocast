package com.lumocast.data

import android.content.Context
import android.content.SharedPreferences

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

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(
                    context.applicationContext.getSharedPreferences("lumocast", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
