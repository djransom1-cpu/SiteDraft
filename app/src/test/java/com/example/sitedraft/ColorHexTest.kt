package com.example.sitedraft

import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.parseHexColor
import com.example.sitedraft.ui.canvas.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorHexTest {

    @Test
    fun `opaque colour formats as six digits and round-trips`() {
        val c = Color(0xFF8B5A2B)
        assertEquals("#8B5A2B", c.toHexString())
        assertEquals(c, parseHexColor(c.toHexString()))
    }

    @Test
    fun `translucent colour formats as eight digits and round-trips`() {
        val c = Color(0x80FF0000)
        assertEquals("#80FF0000", c.toHexString())
        assertEquals(c, parseHexColor(c.toHexString()))
    }

    @Test
    fun `parser accepts short form, no hash, and lower case`() {
        assertEquals(Color(0xFFFF0000), parseHexColor("#F00"))
        assertEquals(Color(0xFF00FF00), parseHexColor("00ff00"))
        assertEquals(Color(0xFF0000FF), parseHexColor("  #0000ff "))
    }

    @Test
    fun `parser rejects partial and junk input`() {
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#"))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#GGGGGG"))
        assertNull(parseHexColor("red"))
    }
}
