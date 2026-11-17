package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val sha = "ab".repeat(32)
    private fun json(code: String = "42", channel: String = "main", apk: String = "StreamLink.apk", s: String = sha) =
        "{\"versionCode\": $code, \"channel\": \"$channel\", \"apk\": \"$apk\", \"sha256\": \"$s\"}\n"

    @Test
    fun parsesVersionJson() {
        val v = UpdateLogic.parseVersionJson(json())!!
        assertEquals(42, v.versionCode)
        assertEquals("main", v.channel)
        assertEquals("StreamLink.apk", v.apk)
        assertEquals(sha, v.sha256)
        assertEquals(sha, UpdateLogic.parseVersionJson(json(s = sha.uppercase()))!!.sha256)
    }

    @Test
    fun rejectsBadVersionJson() {
        assertNull(UpdateLogic.parseVersionJson(""))
        assertNull(UpdateLogic.parseVersionJson("not json"))
        assertNull(UpdateLogic.parseVersionJson("[1,2]"))
        assertNull(UpdateLogic.parseVersionJson(json(code = "\"42\"")))
        assertNull(UpdateLogic.parseVersionJson(json(code = "4.5")))
        assertNull(UpdateLogic.parseVersionJson(json(code = "0")))
        assertNull(UpdateLogic.parseVersionJson(json(code = "99999999999")))
        assertNull(UpdateLogic.parseVersionJson(json(s = "abc")))
        assertNull(UpdateLogic.parseVersionJson(json(s = "zz".repeat(32))))
        assertNull(UpdateLogic.parseVersionJson("{\"versionCode\": 5, \"channel\": \"main\"}"))
    }

    @Test
    fun newerLogic() {
        assertTrue(UpdateLogic.isNewer(43, 42))
        assertFalse(UpdateLogic.isNewer(42, 42))
        assertFalse(UpdateLogic.isNewer(41, 42))
    }

    @Test
    fun channelMapping() {
        assertEquals("latest", UpdateLogic.tagFor("main"))
        assertEquals("test-latest", UpdateLogic.tagFor("test"))
        assertNull(UpdateLogic.tagFor("other"))
        assertEquals("https://github.com/drauch69-cmd/tv/releases/download/latest/version.json", UpdateLogic.versionUrl("main"))
        assertEquals("https://github.com/drauch69-cmd/tv/releases/download/test-latest/version.json", UpdateLogic.versionUrl("test"))
        assertEquals("https://github.com/drauch69-cmd/tv/releases/download/test-latest/StreamLink-Test.apk", UpdateLogic.apkUrl("test"))
        assertEquals("https://github.com/drauch69-cmd/tv/releases/download/latest/StreamLink.apk", UpdateLogic.apkUrl("main"))
        assertNull(UpdateLogic.versionUrl("x"))
        assertNull(UpdateLogic.apkUrl("x"))
    }

    @Test
    fun evaluateDecidesNewerUpToDateOrInvalid() {
        assertTrue(UpdateLogic.evaluate(json("43"), "main", 42) is UpdateCheck.Newer)
        assertTrue(UpdateLogic.evaluate(json("42"), "main", 42) is UpdateCheck.UpToDate)
        assertTrue(UpdateLogic.evaluate(json("10"), "main", 42) is UpdateCheck.UpToDate)
        // wrong channel, or an APK name other than the channel's own
        assertTrue(UpdateLogic.evaluate(json(channel = "test", apk = "StreamLink-Test.apk"), "main", 1) is UpdateCheck.Invalid)
        assertTrue(UpdateLogic.evaluate(json(apk = "evil.apk"), "main", 1) is UpdateCheck.Invalid)
        assertTrue(UpdateLogic.evaluate(json(), "weird", 1) is UpdateCheck.Invalid)
        assertTrue(UpdateLogic.evaluate("garbage", "main", 1) is UpdateCheck.Invalid)
        val t = UpdateLogic.evaluate(json("50", "test", "StreamLink-Test.apk"), "test", 49)
        assertTrue(t is UpdateCheck.Newer)
        assertNotNull((t as UpdateCheck.Newer).info)
    }

    @Test
    fun checkSchedule() {
        val h = 60 * 60 * 1000L
        assertTrue(UpdateLogic.checkDue(1000, 0, 0))
        assertFalse(UpdateLogic.checkDue(5 * h, 1000, 0))
        assertTrue(UpdateLogic.checkDue(13 * h, 1000, 0))
        assertFalse(UpdateLogic.checkDue(10 * h + 10 * 60_000, 0, 10 * h))
        assertTrue(UpdateLogic.checkDue(10 * h + 31 * 60_000, 0, 10 * h))
    }

    @Test
    fun sha256Helper() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateLogic.sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateLogic.sha256Hex("abc".toByteArray()))
        assertEquals(UpdateLogic.sha256Hex("abc".toByteArray()), UpdateLogic.sha256Hex("abc".byteInputStream()))
        assertEquals("00ff10", UpdateLogic.hex(byteArrayOf(0, -1, 16)))
    }

    @Test
    fun signerComparison() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        assertTrue(UpdateLogic.sameSigners(listOf(a), listOf(a.copyOf())))
        assertTrue(UpdateLogic.sameSigners(listOf(a, b), listOf(b, a)))
        assertFalse(UpdateLogic.sameSigners(listOf(a), listOf(b)))
        assertFalse(UpdateLogic.sameSigners(listOf(a), listOf(a, b)))
        assertFalse(UpdateLogic.sameSigners(emptyList(), emptyList()))
        assertFalse(UpdateLogic.sameSigners(listOf(a), emptyList()))
    }

    @Test
    fun phoneUpdateField() {
        UpdateState.phase = UpdateState.Phase.NONE
        assertNull(UpdateState.phoneJson())
        UpdateState.build = 7
        UpdateState.phase = UpdateState.Phase.DOWNLOADING
        assertEquals(7 to false, UpdateState.phoneJson())
        UpdateState.phase = UpdateState.Phase.READY
        assertEquals(7 to true, UpdateState.phoneJson())
        UpdateState.phase = UpdateState.Phase.NONE
    }
}
