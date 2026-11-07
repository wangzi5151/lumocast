package com.lumocast.core.net

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 「首次配对后 7 天免 PIN」的令牌账本。
 *
 * - 令牌是 256-bit `SecureRandom`，Base64URL 编码；
 * - 账本只保存 token → 过期时间（毫秒），持久化由调用方（Prefs）负责；
 * - 过期即失效；用户可以一次性吊销全部令牌；
 * - 校验用常量时间比较，避免按字节提前返回泄漏前缀。
 *
 * 纯 JVM（`java.util.Base64` 在 minSdk 26 可用），方便单元测试。
 */
object PairTokens {

    const val TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
    const val MAX_TOKENS: Int = 32

    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 格式：每行 `token|expiresAtMillis`；坏行直接跳过，不让损坏的存储拖垮握手。 */
    fun parse(raw: String?): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        if (raw.isNullOrBlank()) return out
        for (line in raw.lineSequence()) {
            val sep = line.lastIndexOf('|')
            if (sep <= 0) continue
            val token = line.substring(0, sep)
            val expiresAt = line.substring(sep + 1).toLongOrNull() ?: continue
            if (!isWellFormed(token)) continue
            if (out.size >= MAX_TOKENS * 2) break
            out[token] = expiresAt
        }
        return out
    }

    fun serialize(map: Map<String, Long>): String =
        map.entries
            .sortedBy { it.value }
            .take(MAX_TOKENS)
            .joinToString("\n") { "${it.key}|${it.value}" }

    fun prune(map: MutableMap<String, Long>, now: Long = System.currentTimeMillis()) {
        map.entries.removeAll { it.value <= now }
        if (map.size > MAX_TOKENS) {
            map.entries.sortedBy { it.value }
                .take(map.size - MAX_TOKENS)
                .forEach { map.remove(it.key) }
        }
    }

    fun isValid(map: Map<String, Long>, token: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!isWellFormed(token)) return false
        for ((key, expiresAt) in map) {
            if (expiresAt > now && constantTimeEquals(key, token)) return true
        }
        return false
    }

    fun issue(
        map: MutableMap<String, Long>,
        token: String = newToken(),
        now: Long = System.currentTimeMillis()
    ): String {
        prune(map, now)
        map[token] = now + TTL_MS
        return token
    }

    fun isWellFormed(token: String): Boolean =
        token.length in 20..64 &&
            token.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
