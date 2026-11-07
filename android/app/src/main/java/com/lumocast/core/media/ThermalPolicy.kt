package com.lumocast.core.media

/**
 * 把 Android 的系统热状态映射成「码率倍率 / 输出帧率上限 / 是否停止」。
 *
 * 纯 JVM、无 Android import：规则可单元测试。HostService 负责读取
 * `PowerManager.currentThermalStatus` 并把结果应用到编码器和发送节流。
 *
 * 数值与 `PowerManager.THERMAL_STATUS_*` 对齐（NONE=0 … SHUTDOWN=6）。
 */
object ThermalPolicy {

    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6

    data class Plan(
        val fpsCap: Int,
        val bitrateScale: Double,
        val stop: Boolean,
        val note: String?
    )

    fun plan(status: Int, baseFps: Int): Plan {
        val fps = baseFps.coerceAtLeast(1)
        return when {
            status >= CRITICAL -> Plan(
                fpsCap = fps,
                bitrateScale = 0.35,
                stop = true,
                note = "设备过热，已停止共享"
            )

            status >= SEVERE -> {
                val cap = (fps / 2).coerceAtLeast(15)
                Plan(
                    fpsCap = cap,
                    bitrateScale = 0.5,
                    stop = false,
                    note = "设备过热，已降到 ${cap}fps 并降低码率"
                )
            }

            status >= MODERATE -> {
                val cap = (fps * 3 / 4).coerceAtLeast(24).coerceAtMost(fps)
                Plan(
                    fpsCap = cap,
                    bitrateScale = 0.75,
                    stop = false,
                    note = "设备偏热，已降低码率和帧率"
                )
            }

            status >= LIGHT -> Plan(fps, 0.9, false, null)
            else -> Plan(fps, 1.0, false, null)
        }
    }
}
