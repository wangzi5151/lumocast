package com.lumocast.core.net

import org.json.JSONObject

/**
 * 握手解析与授权决策。
 *
 * 刻意不依赖 Android 类：这一层可以在纯 JVM 单元测试里跑
 * （`android/app/src/test/.../HandshakeTest.kt`），避免把「谁能连进来」的规则
 * 埋在 Socket / Service 代码里，改动后无法回归。
 */
object Handshake {

    data class Hello(
        val version: Int,
        val device: String,
        val pin: String,
        val pairToken: String?
    )

    data class Decision(
        val accepted: Boolean,
        val code: String = "",
        val message: String = "",
        /** 本次是通过持久化配对令牌免 PIN 进来的 */
        val viaPair: Boolean = false
    )

    /** @return null 表示不是合法 hello / 无法解析 */
    fun parseHello(line: String): Hello? {
        return try {
            val o = JSONObject(line)
            if (o.optString("t") != "hello") return null
            Hello(
                version = o.optInt("v", 1),
                device = o.optString("device", "Unknown").take(48),
                pin = o.optString("pin", ""),
                pairToken = o.optString("pair", "").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * @param verifyPin 主机提供的 PIN 校验（可做失败次数限制）；仅在需要时调用一次
     * @param verifyPair 主机提供的配对令牌校验；无效/过期令牌仍然会继续尝试 PIN
     */
    fun authorize(
        hello: Hello?,
        pinRequired: Boolean,
        verifyPin: (String) -> Boolean = { false },
        verifyPair: (String) -> Boolean = { false },
        maxVersion: Int = Proto.VERSION
    ): Decision {
        if (hello == null) {
            return Decision(false, "unsupported", "握手格式错误")
        }
        if (hello.version > maxVersion) {
            return Decision(false, "unsupported", "对方协议版本过高，请升级 LumoCast")
        }
        if (!pinRequired) {
            return Decision(true)
        }
        val token = hello.pairToken
        if (token != null && verifyPair(token)) {
            return Decision(true, viaPair = true)
        }
        if (verifyPin(hello.pin)) {
            return Decision(true)
        }
        return Decision(false, "bad_pin", "PIN 不正确")
    }
}
