package com.example.sitedraft

import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingScale
import com.example.sitedraft.ui.canvas.dimensionScale
import com.example.sitedraft.ui.canvas.inchesToPx
import org.junit.Assert.assertEquals
import org.junit.Test

class DimensionScaleTest {
    private val state = CanvasState(scale = DrawingScale(ratio = 50f))

    @Test
    fun `zoomed in, dimension text keeps its screen size`() {
        assertEquals(1f / 4f, dimensionScale(state, 4f), 0.0001f)
    }

    @Test
    fun `zoomed out, dimension text stops at 6 inches and shrinks with the drawing`() {
        val cap = state.inchesToPx(6f) / 36f
        assertEquals(cap, dimensionScale(state, 0.25f), 0.0001f)
        // On screen it is then 36 * cap * zoom px: a quarter of that at a quarter of the zoom.
        assertEquals(36f * cap * 0.25f, 36f * dimensionScale(state, 0.25f) * 0.25f, 0.0001f)
    }

    @Test
    fun `a printed sheet keeps its paper-sized lettering`() {
        val print = state.copy(labelScale = 0.52f)
        assertEquals(0.52f / 0.1f, dimensionScale(print, 0.1f), 0.0001f)
    }
}
