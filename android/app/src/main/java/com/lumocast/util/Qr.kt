package com.lumocast.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 生成连接二维码（内容为 lumocast://connect?... 深链，任何平台扫到都能直接连）。 */
object Qr {

    fun payload(ip: String, port: Int, pin: String?, name: String): String {
        val sb = StringBuilder("lumocast://connect?v=1&ip=").append(ip).append("&port=").append(port)
        if (!pin.isNullOrBlank()) sb.append("&pin=").append(pin)
        sb.append("&name=").append(java.net.URLEncoder.encode(name, "UTF-8"))
        return sb.toString()
    }

    fun bitmap(text: String, size: Int = 512, dark: Int = Color.parseColor("#0B0D17")): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) dark else Color.WHITE)
            }
        }
        bmp
    } catch (t: Throwable) {
        null
    }
}
