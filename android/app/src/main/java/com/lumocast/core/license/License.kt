package com.lumocast.core.license

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 离线 license 校验（方案见 docs/PRICING.md §3）。
 *
 * token 形如：LC1.<base64url(payload_json)>.<base64url(rsa_sha256_sig_of_segment)>
 * 签名对象是 **中间那一段 base64url 字符串的 ASCII 字节**，不是解码后的 JSON —— 
 * 这样四个平台的实现不会因为 JSON 序列化差异（空格、键序）而互相不兼容。
 */
object License {

    private const val TAG = "LumoLicense"
    private const val PREFIX = "LC1"

    /** 与 server/keys/dev-public.pem 配对；正式发行请换成你自己的公钥。 */
    private const val PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQsFAAOCAQEAqWXUbtuz8ZCyjpeh6Y5UCgGQkkuy897yqYkx7CbABr8f5AL" +
            "+s/pZWr4TPgrOdwqw+Y+oNQJlj1ihhy5XpwY/FCa3zP79k5zKCgwvc73DXnBcmWx6ut9Px99qIk/YIW" +
            "+VsViHdkirK5h0h0XEP7U+ohJI4yGvDkS5CPqsgwrlfwG/M/RX5qcEk2QvjSYEI/uX45hYH36RgqB9CV" +
            "609lpYXP3qlV5U13fvdW8EG+ecQknnesIK1/bdgasnIW6sVxNXiNniJAraP10NeS1MnVr9Sl5kf2+aeEt" +
            "hFTQDYTW8GrqzFDH+p8c72oXrGWMJbS01j+A5Bwa3QuRX+VYYdYbYjQIDAQAB"

    data class Entitlement(
        val plan: String,
        val seats: Int,
        val expiryEpochSec: Long,
        val mid: String?,
        val org: String? = null
    ) {
        val isPro: Boolean get() = plan != PLAN_FREE
        val isLifetime: Boolean get() = expiryEpochSec == 0L
        val expired: Boolean
            get() = expiryEpochSec != 0L && System.currentTimeMillis() / 1000 > expiryEpochSec
        val active: Boolean get() = isPro && !expired

        companion object {
            const val PLAN_FREE = "free"
            const val PLAN_YEARLY = "pro_yearly"
            const val PLAN_LIFETIME = "pro_lifetime"
            const val PLAN_TEAM = "team"
            val FREE = Entitlement(PLAN_FREE, 1, 0L, null)
        }
    }

    /** 设备指纹：ANDROID_ID 的 SHA-256 前 8 字节（16 位 hex）。不是硬件序列号，卸载重装会变。 */
    fun deviceId(context: Context): String {
        val raw = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun verify(token: String?, context: Context): Entitlement {
        if (token.isNullOrBlank()) return Entitlement.FREE
        val parts = token.trim().split('.')
        if (parts.size != 3 || parts[0] != PREFIX) return Entitlement.FREE
        val payloadSeg = parts[1]
        val sigSeg = parts[2]

        return try {
            val key = publicKey()
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(payloadSeg.toByteArray(Charsets.US_ASCII))
            val ok = verifier.verify(b64urlDecode(sigSeg))
            if (!ok) {
                Log.w(TAG, "signature mismatch")
                return Entitlement.FREE
            }

            val json = JSONObject(String(b64urlDecode(payloadSeg), Charsets.UTF_8))
            if (json.optInt("v", 1) != 1) return Entitlement.FREE
            val plan = json.optString("plan", Entitlement.PLAN_FREE)
            if (plan !in setOf(
                    Entitlement.PLAN_YEARLY,
                    Entitlement.PLAN_LIFETIME,
                    Entitlement.PLAN_TEAM
                )
            ) return Entitlement.FREE

            val ent = Entitlement(
                plan = plan,
                seats = json.optInt("seats", 1),
                expiryEpochSec = json.optLong("exp", 0L),
                mid = json.optString("mid", "").ifBlank { null },
                org = json.optString("org", "").ifBlank { null }
            )
            if (ent.expired) {
                Log.i(TAG, "license expired at ${ent.expiryEpochSec}")
                return Entitlement.FREE
            }
            // 设备不匹配不直接拒绝：给 7 天宽限期，让用户有时间去换绑
            val localMid = deviceId(context)
            if (ent.mid != null && ent.mid != localMid) {
                Log.i(TAG, "license bound to ${ent.mid}, local $localMid → grace period")
            }
            ent
        } catch (t: Throwable) {
            Log.w(TAG, "verify failed: ${t.message}")
            Entitlement.FREE
        }
    }

    private fun publicKey(): java.security.PublicKey {
        val der = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    private fun b64urlDecode(s: String): ByteArray {
        val norm = s.replace('-', '+').replace('_', '/')
        val pad = (4 - norm.length % 4) % 4
        return Base64.decode(norm + "=".repeat(pad), Base64.NO_WRAP)
    }
}
