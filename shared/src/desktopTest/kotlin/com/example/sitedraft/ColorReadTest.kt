package com.example.sitedraft

import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.ColorSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Saved colours read safely (Nov 28: a plain ARGB number crashed the app on opening). */
class ColorReadTest {
    @Test fun `the app's own colours read back the same`() {
        for (c in listOf(Color.Black, Color.White, Color(0xFF003366), Color(0x80FF0000))) assertEquals(c, ColorSerializer.fromLong(c.value.toLong()))
    }
    @Test fun `a plain ARGB number reads as that colour`() {
        assertEquals(Color.Black, ColorSerializer.fromLong(0xFF000000L))
        assertEquals(Color.Black, ColorSerializer.fromLong(-16777216L))
        assertEquals(Color.White, ColorSerializer.fromLong(0xFFFFFFFFL))
    }
    @Test fun `anything else is black, never a crash`() {
        for (v in listOf(60L shl 32 or 60L, 0x7FFFFFFFFFFFFFFFL, 123456789012345L)) ColorSerializer.fromLong(v).red
    }
}
