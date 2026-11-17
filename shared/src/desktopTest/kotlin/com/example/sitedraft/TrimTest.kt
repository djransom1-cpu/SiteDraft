package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Trim
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Door and window casing and base, to Dan's layout rules. 1' = 50px. */
class TrimTest {
    private val inch = 50f / 12f
    private val spec = ElevationSpec(wallId = "w", side = ViewerSide.BELOW, trimOn = true)

    private fun width(from: Float, to: Float, window: Boolean, s: ElevationSpec): Float =
        Trim.outerEdges(from * inch, to * inch, window, s, inch).let { (a, b) -> (b - a) / inch }

    @Test
    fun `a 3-0 door's casing sits 3-16 off the jamb`() {
        // 36" + 2 x 3/16" reveal + 2 x 2 1/4" casing = 40 7/8".
        assertEquals(40.875f, width(0f, 36f, false, spec), 0.001f)
        assertEquals(42.875f, width(0f, 36f, false, spec.copy(casingIn = 3.25f)), 0.001f)
        // A backband adds 13/16" a side (1 1/16" wide on a 1/4" rabbet).
        assertEquals(40.875f + 2f * 13f / 16f, width(0f, 36f, false, spec.copy(headStyle = Trim.HEAD_BACKBAND)), 0.001f)
    }

    @Test
    fun `a 3' window is cased 9-16 in from the outside of its jamb`() {
        // Inside edges at 36 - 2 x 9/16 = 34 7/8"; outside with 2 1/4" casing 39 3/8".
        assertEquals(39.375f, width(0f, 36f, true, spec), 0.001f)
    }

    private val t = 4.5f * inch
    private val wall = DrawingElement.Symbol(symbolName = "2x4 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset(120f * inch, t / 2f), size = Size(240f * inch, t))

    private fun door(xIn: Float): DrawingElement.Symbol {
        val w = 36f * inch
        // The threshold (the symbol's bottom edge) on the wall's centre line, the swing above it.
        return DrawingElement.Symbol(symbolName = "Door 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(xIn * inch + w / 2f, t / 2f - w / 2f), size = Size(w, w))
    }

    @Test
    fun `the base stops at the door casing and the cabinets`() {
        val base = DrawingElement.Symbol(symbolName = "Base 36\"", category = PlanSymbols.CABINETS, color = Color.White,
            center = Offset(150f * inch, t + 12f * inch), size = Size(36f * inch, 24f * inch))
        val window = DrawingElement.Symbol(symbolName = "Window 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(210f * inch, t / 2f), size = Size(36f * inch, 6f * inch))
        val d = door(30f)
        val state = CanvasState(elements = listOf(wall, d, base, window))
        val items = Elevation.itemsOnWall(wall, ViewerSide.BELOW, state)
        assertEquals(3, items.size)
        val parts = Elevation.build(wall, spec.copy(baseIn = 3.25f), Offset(0f, 1000f), state)
        // Base pieces: rectangles 3 1/4" tall standing on the floor.
        val bases = parts.filterIsInstance<DrawingElement.Rectangle>().filter {
            kotlin.math.abs(it.size.height - 3.25f * inch) < 0.01f && kotlin.math.abs(it.topLeft.y + it.size.height - 1000f) < 0.01f
        }
        assertEquals(3, bases.size)
        val doorItem = items.first { it.kind == Elevation.Kind.OPENING && PlanSymbols.isDoor(it.symbol.symbolName) }
        val (casL, casR) = Trim.outerEdges(doorItem.from, doorItem.to, false, spec, inch)
        val cab = items.first { it.symbol.category == PlanSymbols.CABINETS }
        for (b in bases) {
            val a = b.topLeft.x
            val z = a + b.size.width
            assertTrue("base clear of the door casing", z <= casL + 0.01f || a >= casR - 0.01f)
            assertTrue("base clear of the cabinet", z <= cab.from + 0.01f || a >= cab.to - 0.01f)
        }
        // With trim on, the door and the window are cased: more lines than without.
        val plain = Elevation.build(wall, spec.copy(trimOn = false), Offset(0f, 1000f), state)
        assertTrue(parts.filterIsInstance<DrawingElement.Line>().size >= plain.filterIsInstance<DrawingElement.Line>().size + 14)
    }

    @Test
    fun `every head and window style draws`() {
        val window = DrawingElement.Symbol(symbolName = "Window 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(150f * inch, t / 2f), size = Size(36f * inch, 6f * inch))
        val state = CanvasState(elements = listOf(wall, door(30f), window))
        for (head in 0..2) for (win in 0..1) {
            val parts = Elevation.build(wall, spec.copy(headStyle = head, windowTrim = win), Offset(0f, 1000f), state)
            assertTrue("head $head window $win", parts.size > 25)
        }
    }
}
