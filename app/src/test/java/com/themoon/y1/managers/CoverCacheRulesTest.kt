package com.themoon.y1.managers

import com.themoon.y1.managers.CoverCacheRules.PregenAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

class CoverCacheRulesTest {

    private fun formatHex(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    @Test
    fun md5Hex_knownValues() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", CoverCacheRules.md5Hex(""))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", CoverCacheRules.md5Hex("The quick brown fox jumps over the lazy dog"))
    }

    @Test
    fun md5Hex_sameAsTheOldFormatCode() {
        // The disk cache file names must not change.
        for (s in listOf("/storage/sdcard0/Music/A - B", "Ünïcödé – 日本語 - Album", "x")) {
            assertEquals(formatHex(s), CoverCacheRules.md5Hex(s))
        }
    }

    @Test
    fun toHex_allByteValues() {
        val bytes = ByteArray(256) { it.toByte() }
        val hex = CoverCacheRules.toHex(bytes)
        assertEquals(512, hex.length)
        assertEquals("00", hex.substring(0, 2))
        assertEquals("7f80", hex.substring(254, 258))
        assertEquals("ff", hex.substring(510))
    }

    @Test
    fun albumListSignature_ignoresOrderAndSeesChanges() {
        val a = listOf("/m/A - One", "/m/B - Two", "/m/C - Three")
        assertEquals(CoverCacheRules.albumListSignature(a), CoverCacheRules.albumListSignature(a.reversed()))
        assertNotEquals(CoverCacheRules.albumListSignature(a), CoverCacheRules.albumListSignature(a.dropLast(1)))
        assertNotEquals(CoverCacheRules.albumListSignature(a), CoverCacheRules.albumListSignature(a + "/m/D - Four"))
        assertNotEquals(CoverCacheRules.albumListSignature(a), CoverCacheRules.albumListSignature(listOf("/m/A - One", "/m/B - Two", "/m/C - Thre")))
        assertNotEquals(CoverCacheRules.albumListSignature(emptyList()), CoverCacheRules.albumListSignature(listOf("")))
    }

    @Test
    fun pregenAction_skipsThumbsAndAlbumsWithoutArt() {
        assertEquals(PregenAction.SKIP, CoverCacheRules.pregenAction(false, true) { error("not needed") })
        assertEquals(PregenAction.SKIP, CoverCacheRules.pregenAction(false, false) { true })
        assertEquals(PregenAction.EXTRACT, CoverCacheRules.pregenAction(false, false) { false })
    }

    @Test
    fun pregenAction_forceExtractsAll() {
        var checked = false
        assertEquals(PregenAction.EXTRACT, CoverCacheRules.pregenAction(true, true) { checked = true; true })
        assertEquals(PregenAction.EXTRACT, CoverCacheRules.pregenAction(true, false) { checked = true; true })
        assertFalse(checked)
    }
}
