package com.lumocast.util

/**
 * Annex-B（起始码分隔）与 NAL 单元工具。
 * 编解码器两侧都用它：编码器侧抽 SPS/PPS 做 CONFIG 帧，解码器侧把 CSD 拆成 csd-0/csd-1。
 */
object AnnexB {

    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    private val START_4 = byteArrayOf(0, 0, 0, 1)

    /** 拆分出所有 NAL 单元（不含起始码，去掉尾部 0 填充字节）。 */
    fun nals(data: ByteArray, off: Int = 0, len: Int = data.size): List<ByteArray> {
        val out = ArrayList<ByteArray>(8)
        val end = off + len
        var i = off
        var start = -1
        while (i + 2 < end) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            if (b0 == 0 && b1 == 0 && b2 == 1) {
                if (start >= 0) addNal(out, data, start, i)
                start = i + 3
                i += 3
            } else if (i + 3 < end && b0 == 0 && b1 == 0 && b2 == 0 && (data[i + 3].toInt() and 0xFF) == 1) {
                if (start >= 0) addNal(out, data, start, i)
                start = i + 4
                i += 4
            } else {
                i++
            }
        }
        if (start in 0 until end) addNal(out, data, start, end)
        return out
    }

    private fun addNal(out: MutableList<ByteArray>, data: ByteArray, s: Int, e: Int) {
        var end = e
        while (end > s && data[end - 1].toInt() == 0) end--
        if (end > s) out.add(data.copyOfRange(s, end))
    }

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) 0 else nal[0].toInt() and 0x1F

    /** 从一段 Annex-B 数据中抽取 SPS 与 PPS（带 4 字节起始码），用于生成 CONFIG 包。 */
    fun extractCsd(data: ByteArray): ByteArray? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals(data)) {
            when (nalType(nal)) {
                NAL_SPS -> if (sps == null) sps = nal
                NAL_PPS -> if (pps == null) pps = nal
            }
        }
        if (sps == null || pps == null) return null
        return withStartCode(sps) + withStartCode(pps)
    }

    fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(START_4.size + nal.size)
        System.arraycopy(START_4, 0, out, 0, START_4.size)
        System.arraycopy(nal, 0, out, START_4.size, nal.size)
        return out
    }

    fun videoNals(data: ByteArray): List<ByteArray> =
        nals(data).filter { nalType(it) in NAL_SLICE..NAL_IDR }
}
