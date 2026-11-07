package com.lumocast.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairTokensTest {

    @Test
    fun generatedTokenIsHighEntropyAndUrlSafe() {
        val a = PairTokens.newToken()
        val b = PairTokens.newToken()
        assertEquals(43, a.length) // 32 bytes → 43 base64url chars
        assertTrue(PairTokens.isWellFormed(a))
        assertNotEquals(a, b)
    }

    @Test
    fun issuedTokenIsValidUntilExactlySevenDays() {
        val now = 1_000_000L
        val map = linkedMapOf<String, Long>()
        val token = PairTokens.issue(map, now = now)
        assertTrue(PairTokens.isValid(map, token, now))
        assertTrue(PairTokens.isValid(map, token, now + PairTokens.TTL_MS - 1))
        assertFalse(PairTokens.isValid(map, token, now + PairTokens.TTL_MS))
    }

    @Test
    fun pruneRemovesExpiredEntries() {
        val now = 10_000L
        val map = linkedMapOf("expiredToken_1234567890" to now - 1, "liveToken_123456789012" to now + 1000)
        PairTokens.prune(map, now)
        assertEquals(1, map.size)
        assertTrue(map.containsKey("liveToken_123456789012"))
    }

    @Test
    fun serializeAndParseRoundTrip() {
        val now = 5_000L
        val map = linkedMapOf<String, Long>()
        PairTokens.issue(map, "token_abcdefghijklmnopqrstuvwxyz", now)
        PairTokens.issue(map, "token_zyxwvutsrqponmlkjihgfedcba", now)
        val parsed = PairTokens.parse(PairTokens.serialize(map))
        assertEquals(map, parsed)
    }

    @Test
    fun malformedAndNonTokenStringsAreRejected() {
        val map = linkedMapOf<String, Long>()
        PairTokens.issue(map, now = 0)
        assertFalse(PairTokens.isValid(map, "short", 0))
        assertFalse(PairTokens.isValid(map, "has spaces and is long enough", 0))
        assertFalse(PairTokens.isValid(map, "../etc/passwd/abcdefghijklmnop", 0))
        assertFalse(PairTokens.isValid(map, "", 0))
    }

    @Test
    fun parseIgnoresCorruptLines() {
        val raw = "goodToken_abcdefghijklmnop|999999\nbad line\nnoexpiry|\n|123\n" +
            "anotherToken_abcdefghijklmnop|notanumber"
        val map = PairTokens.parse(raw)
        assertEquals(1, map.size)
        assertTrue(map.containsKey("goodToken_abcdefghijklmnop"))
    }

    @Test
    fun oldestTokensAreDroppedBeyondLimit() {
        val now = 1_000L
        val map = linkedMapOf<String, Long>()
        repeat(PairTokens.MAX_TOKENS + 10) { i ->
            PairTokens.issue(map, "token_%040d".format(i), now + i)
        }
        PairTokens.prune(map, now)
        assertEquals(PairTokens.MAX_TOKENS, map.size)
    }
}
