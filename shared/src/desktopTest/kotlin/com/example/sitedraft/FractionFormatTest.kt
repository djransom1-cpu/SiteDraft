package com.example.sitedraft

import com.example.sitedraft.ui.canvas.formatFeetInches
import com.example.sitedraft.ui.canvas.formatInches
import com.example.sitedraft.ui.canvas.parseFeet
import com.example.sitedraft.ui.canvas.parseInches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Imperial lengths read and type like a tape measure: fractions of an inch, not decimals. */
class FractionFormatTest {

    @Test
    fun `inches format as reduced sixteenths`() {
        assertEquals("6 1/2", formatInches(6.5f))
        assertEquals("3/4", formatInches(0.75f))
        assertEquals("5/16", formatInches(0.3125f))
        assertEquals("12", formatInches(12f))
        assertEquals("0", formatInches(0.01f))
        assertEquals("2 3/8", formatInches(2.37f)) // nearest sixteenth is 6/16 = 3/8
    }

    @Test
    fun `feet and inches format as architectural text`() {
        assertEquals("3'-6 1/2\"", formatFeetInches(3.5f + 0.5f / 12f))
        assertEquals("20'", formatFeetInches(20f))
        assertEquals("6 1/2\"", formatFeetInches(6.5f / 12f))
        assertEquals("0\"", formatFeetInches(0f))
        // A hair under 3' reads as 3', never 2'-12".
        assertEquals("3'", formatFeetInches(2.9999f))
    }

    @Test
    fun `typed inches accept fractions dashes and decimals`() {
        assertEquals(6.5f, parseInches("6 1/2")!!, 0.0001f)
        assertEquals(6.5f, parseInches("6-1/2")!!, 0.0001f)
        assertEquals(6.5f, parseInches("6.5")!!, 0.0001f)
        assertEquals(0.75f, parseInches("3/4")!!, 0.0001f)
        assertEquals(6.5f, parseInches("6 1/2\"")!!, 0.0001f)
        assertEquals(0f, parseInches("0")!!, 0.0001f)
        assertNull(parseInches("abc"))
        assertNull(parseInches("1/0"))
        assertNull(parseInches(""))
    }

    @Test
    fun `typed feet accept feet-inch forms`() {
        assertEquals(12.5f, parseFeet("12.5")!!, 0.0001f)
        assertEquals(12.5f, parseFeet("12'6\"")!!, 0.0001f)
        assertEquals(12.5f, parseFeet("12' 6")!!, 0.0001f)
        assertEquals(12.5f, parseFeet("12-6")!!, 0.0001f)
        assertEquals(12f + 6.5f / 12f, parseFeet("12' 6 1/2\"")!!, 0.0001f)
        assertEquals(12f + 6.5f / 12f, parseFeet("12ft 6 1/2in")!!, 0.0001f)
        assertEquals(2.5f, parseFeet("30\"")!!, 0.0001f)
        assertEquals(12f, parseFeet("12'")!!, 0.0001f)
        assertNull(parseFeet("twelve"))
    }
}
