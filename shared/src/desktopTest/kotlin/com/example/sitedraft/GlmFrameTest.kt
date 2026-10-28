package com.example.sitedraft

import com.example.sitedraft.data.laser.GLM_SYNC_REQUEST
import com.example.sitedraft.data.laser.glmCrcOk
import com.example.sitedraft.data.laser.parseGlmFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlmCrcTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `crc8 reproduces every captured frame`() {
        for (f in listOf(
            "C0 55 02 01 00 1A", "03 00 0A",
            "00 10 02 08 DE 01 00 00 00 00 00 00 00 00 00 00 00 00 22",
            "C0 55 10 06 08 DF 01 37 F8 38 40 00 00 00 00 00 00 00 00 A2",
            "C0 55 10 06 08 E0 01 61 C3 1B 40 00 00 00 00 00 00 00 00 88",
            "C0 55 10 02 09 DE 01 00 00 00 00 00 00 00 00 00 00 00 00 08"
        )) {
            assertTrue(f, hex(f).glmCrcOk())
        }
        assertFalse(hex("C0 55 02 01 00 1D").glmCrcOk())
    }

    @Test
    fun `sync request carries the accepted checksum`() {
        assertEquals(0x1A, GLM_SYNC_REQUEST.last().toInt() and 0xFF)
    }
}

/** Frames below were captured from a real GLM 50 C on 2026-10-28. */
class GlmFrameTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `push frame on measure yields distance and counter`() {
        val m = parseGlmFrame(hex("C0 55 10 06 08 DF 01 37 F8 38 40 00 00 00 00 00 00 00 00 A2"))!!
        assertEquals(2.890f, m.meters, 0.001f)
        assertEquals(0x01DF, m.counter)

        val m2 = parseGlmFrame(hex("C0 55 10 06 08 E0 01 61 C3 1B 40 00 00 00 00 00 00 00 00 88"))!!
        assertEquals(2.434f, m2.meters, 0.001f)
        assertEquals(0x01E0, m2.counter)
    }

    @Test
    fun `reply to the sync request with a stored measurement is parsed the same way`() {
        // Same payload layout behind a status byte and length instead of C0 55 length.
        val m = parseGlmFrame(hex("00 10 02 08 DE 01 37 F8 38 40 00 00 00 00 00 00 00 00 22"))!!
        assertEquals(2.890f, m.meters, 0.001f)
        assertEquals(0x01DE, m.counter)
    }

    @Test
    fun `frames without a distance are not measurements`() {
        // Reply before any measurement: distance zero.
        assertNull(parseGlmFrame(hex("00 10 02 08 DE 01 00 00 00 00 00 00 00 00 00 00 00 00 22")))
        // "Laser on" push (type bytes 02 09), distance zero.
        assertNull(parseGlmFrame(hex("C0 55 10 02 09 DE 01 00 00 00 00 00 00 00 00 00 00 00 00 08")))
        // Error replies and acknowledgements.
        assertNull(parseGlmFrame(hex("03 00 0A")))
        assertNull(parseGlmFrame(hex("01 00 0A")))
        assertNull(parseGlmFrame(hex("C4 55 56")))
        assertNull(parseGlmFrame(byteArrayOf()))
    }
}
