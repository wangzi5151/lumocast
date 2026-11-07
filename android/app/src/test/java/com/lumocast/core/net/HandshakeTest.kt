package com.lumocast.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {

    private fun authorize(
        hello: Handshake.Hello?,
        pinRequired: Boolean,
        pin: String = "1234",
        pair: String? = null,
        maxVersion: Int = Proto.VERSION
    ): Handshake.Decision = Handshake.authorize(
        hello = hello,
        pinRequired = pinRequired,
        verifyPin = { it == pin },
        verifyPair = { it == pair },
        maxVersion = maxVersion
    )

    @Test
    fun parsesHelloAndOptionalPairToken() {
        val hello = Handshake.parseHello(
            """{"t":"hello","v":1,"role":"viewer","device":"Pixel","pin":"1234","pair":"tok"}"""
        )
        assertNotNull(hello)
        assertEquals(1, hello!!.version)
        assertEquals("Pixel", hello.device)
        assertEquals("1234", hello.pin)
        assertEquals("tok", hello.pairToken)

        val noPair = Handshake.parseHello("""{"t":"hello","v":1,"device":"Pad"}""")
        assertNull(noPair!!.pairToken)
    }

    @Test
    fun rejectsMalformedOrNonHelloLines() {
        assertNull(Handshake.parseHello("not json"))
        assertNull(Handshake.parseHello("""{"t":"welcome"}"""))
        val d = Handshake.authorize(hello = null, pinRequired = true)
        assertFalse(d.accepted)
        assertEquals("unsupported", d.code)
    }

    @Test
    fun highProtocolVersionIsRejectedBeforeCredentials() {
        val hello = Handshake.Hello(version = 2, device = "x", pin = "1234", pairToken = null)
        val d = authorize(hello, pinRequired = true)
        assertFalse(d.accepted)
        assertEquals("unsupported", d.code)
        assertFalse(d.viaPair)
    }

    @Test
    fun pinDisabledAcceptsWithoutPin() {
        val hello = Handshake.Hello(version = 1, device = "x", pin = "", pairToken = null)
        assertTrue(authorize(hello, pinRequired = false).accepted)
    }

    @Test
    fun correctPinIsAcceptedButNotViaPair() {
        val hello = Handshake.Hello(version = 1, device = "x", pin = "1234", pairToken = null)
        val d = authorize(hello, pinRequired = true)
        assertTrue(d.accepted)
        assertFalse(d.viaPair)
    }

    @Test
    fun validPairTokenBypassesPin() {
        val hello = Handshake.Hello(version = 1, device = "x", pin = "", pairToken = "tok")
        var pinChecked = false
        val d = Handshake.authorize(
            hello = hello,
            pinRequired = true,
            verifyPin = { pinChecked = true; false },
            verifyPair = { it == "tok" }
        )
        assertTrue(d.accepted)
        assertTrue(d.viaPair)
        assertFalse("valid pair token must not fall through to PIN", pinChecked)
    }

    @Test
    fun expiredPairTokenFallsBackToPin() {
        val hello = Handshake.Hello(version = 1, device = "x", pin = "1234", pairToken = "old")
        val d = authorize(hello, pinRequired = true, pair = "new")
        assertTrue(d.accepted)
        assertFalse(d.viaPair)
    }

    @Test
    fun badPinAndBadPairAreRejected() {
        val hello = Handshake.Hello(version = 1, device = "x", pin = "0000", pairToken = "old")
        val d = authorize(hello, pinRequired = true, pair = "new")
        assertFalse(d.accepted)
        assertEquals("bad_pin", d.code)
    }
}
