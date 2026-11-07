package com.lumocast.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ProtocolTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun headerMatchesKnownVectorSharedWithJavaScript() {
        val h = ByteArray(Proto.HEADER_SIZE)
        Proto.writeHeader(h, 0, Proto.T_VIDEO, Proto.F_KEYFRAME, 0x0000abcd, 0x00001234)
        // 与 web/js/protocol.js 及 scripts/protocol-test.mjs 的已知向量完全一致
        assertEquals("4c430102010000000000abcd00001234", hex(h))
    }

    @Test
    fun ptsAbove32BitsIsTruncatedToUint32() {
        val h = ByteArray(Proto.HEADER_SIZE)
        Proto.writeHeader(h, 0, Proto.T_VIDEO, 0, 0x1_0000_0005, 0)
        assertEquals(5, Proto.getInt(h, 8))
    }

    @Test
    fun writerAndReaderRoundTrip() {
        val out = ByteArrayOutputStream()
        val writer = PacketWriter(out)
        writer.write(Proto.T_VIDEO, Proto.F_KEYFRAME, 1234, byteArrayOf(1, 2, 3, 4, 5))
        writer.writeControl("""{"t":"kf"}""")

        val reader = PacketReader(ByteArrayInputStream(out.toByteArray()))
        val video = reader.next()!!
        assertEquals(Proto.T_VIDEO, video.type)
        assertTrue(video.isKeyFrame)
        assertEquals(1234L, video.ptsMs)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), video.data.toList())

        val control = reader.next()!!
        assertEquals(Proto.T_CONTROL, control.type)
        assertEquals("""{"t":"kf"}""", String(control.data, Charsets.UTF_8))
        assertNull(reader.next())
    }

    @Test
    fun pingPayloadCarriesSeqAndMonotonicTime() {
        val out = ByteArrayOutputStream()
        PacketWriter(out).writePing(7, 55_000)
        val p = PacketReader(ByteArrayInputStream(out.toByteArray())).next()!!
        assertEquals(Proto.T_PING, p.type)
        assertEquals(8, p.data.size)
        assertEquals(7, Proto.getInt(p.data, 0))
        assertEquals(55_000, Proto.getInt(p.data, 4))
    }

    @Test
    fun badMagicAndUnsupportedVersionAreRejected() {
        val badMagic = ByteArray(Proto.HEADER_SIZE)
        assertThrows(IOException::class.java) {
            PacketReader(ByteArrayInputStream(badMagic)).next()
        }

        val h = ByteArray(Proto.HEADER_SIZE)
        Proto.writeHeader(h, 0, Proto.T_VIDEO, 0, 0, 0)
        h[2] = 2
        assertThrows(IOException::class.java) {
            PacketReader(ByteArrayInputStream(h)).next()
        }
    }

    @Test
    fun oversizedPayloadIsRejectedBeforeAllocation() {
        val h = ByteArray(Proto.HEADER_SIZE)
        Proto.writeHeader(h, 0, Proto.T_VIDEO, 0, 0, Proto.MAX_PAYLOAD + 1)
        assertThrows(IOException::class.java) {
            PacketReader(ByteArrayInputStream(h)).next()
        }
    }

    @Test
    fun handshakeLineHandlesLfAndCrLf() {
        assertEquals(
            """{"t":"hello"}""",
            readHandshakeLine(ByteArrayInputStream("""{"t":"hello"}""".plus("\n").toByteArray()))
        )
        assertEquals(
            """{"t":"hello"}""",
            readHandshakeLine(ByteArrayInputStream("""{"t":"hello"}""".plus("\r\n").toByteArray()))
        )
        assertNull(readHandshakeLine(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun handshakeWriterAppendsSingleNewline() {
        val out = ByteArrayOutputStream()
        writeHandshakeLine(out, """{"t":"welcome"}""")
        assertEquals("""{"t":"welcome"}""".plus("\n"), out.toString(Charsets.UTF_8.name()))
    }
}
