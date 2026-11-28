package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Mirrors
import com.example.sitedraft.ui.canvas.PlanSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors (Nov 28): in the furniture library with a footprint, and where they hang by default. */
class MirrorsTest {
    @Test
    fun `the three mirrors are in the library with footprints`() {
        for (n in Mirrors.names) {
            assertTrue(n, n in PlanSymbols.furniture)
            assertTrue(n, Mirrors.footprints.containsKey(n))
        }
    }

    @Test
    fun `a vanity mirror sits on the backsplash and stops at 72 inches`() {
        val m = DrawingElement.Symbol(symbolName = Mirrors.VANITY, category = PlanSymbols.FURNITURE, center = Offset.Zero, size = Size(48f, 1f), color = Color.White)
        val (b, t) = Mirrors.span(m, vanityTopIn = 34.5f)
        assertEquals(38.5f, b, 0.01f)
        assertEquals(72f, t, 0.01f)
        val set = m.copy(properties = mapOf(PlanSymbols.PROP_SILL_IN to 40f, PlanSymbols.PROP_HEIGHT_IN to 30f))
        assertEquals(40f to 70f, Mirrors.span(set, 34.5f))
    }
}
