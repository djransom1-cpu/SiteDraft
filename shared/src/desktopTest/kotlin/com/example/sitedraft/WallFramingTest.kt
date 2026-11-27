package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.WallFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wall framing elevations and the stud count (Dan, Nov 24 2026). */
class WallFramingTest {
    private val inch = 50f / 12f
    /** A 14' 2x4 wall along y = 0..4.5", a 3'-0" door 24" from its west end and a 3'-0" window at 36" sill near the east end. */
    private fun state(): Pair<DrawingElement.Symbol, CanvasState> {
        val wall = DrawingElement.Symbol(id = "w", symbolName = "2x4 Wall", category = PlanSymbols.WALLS, color = Color.White,
            center = Offset(84f * inch, 2.25f * inch), size = Size(168f * inch, 4.5f * inch))
        val door = DrawingElement.Symbol(id = "d", symbolName = "Door 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(42f * inch, (2.25f - 18f) * inch), size = Size(36f * inch, 36f * inch))
        val window = DrawingElement.Symbol(id = "n", symbolName = "Window 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(120f * inch, 2.25f * inch), size = Size(36f * inch, 4.5f * inch),
            properties = mapOf(PlanSymbols.PROP_SILL_IN to 36f, PlanSymbols.PROP_HEIGHT_IN to 48f))
        return wall to CanvasState(elements = listOf(wall, door, window))
    }

    @Test
    fun `a wall meeting the middle of another gets a wall buck there, a corner gets Dan's L`() {
        val (wall, s0) = state()
        // A 2x4 wall coming in from the south at 80" along (a T), and one at the wall's east end (an L).
        val tee = DrawingElement.Symbol(id = "t", symbolName = "2x4 Wall", category = PlanSymbols.WALLS, color = Color.White,
            center = Offset(80f * inch, (4.5f + 48f) * inch), size = Size(4.5f * inch, 96f * inch))
        val ell = DrawingElement.Symbol(id = "l", symbolName = "2x4 Wall", category = PlanSymbols.WALLS, color = Color.White,
            center = Offset((168f - 2.25f) * inch, (4.5f + 48f) * inch), size = Size(4.5f * inch, 96f * inch))
        val s = s0.copy(elements = s0.elements + tee + ell)
        val bucks = com.example.sitedraft.ui.canvas.FramingLayout.bucks(s)["w"]!!
        // The T, and the L at the east end (Dan, Nov 25: end stud plus a flat one beside it, for drywall backing).
        assertEquals(2, bucks.size)
        val t = bucks.single { it.corner == 0 }
        assertEquals(80f, t.centerPx / inch, 0.01f)
        assertEquals(1, t.side)   // the south face
        assertEquals(1, bucks.single { it.corner != 0 }.corner)
        val plain = WallFraming.frame(wall, s0)
        val f = WallFraming.frame(wall, s)
        assertEquals(2, f.flats.size)
        assertEquals(3.5f, f.flats[0].second, 0.01f)          // a 2x4 wall's flat stud is 3 1/2" long
        // The L's flat stud right beside the east end stud: 168 - 1 1/2 - 3 1/2 = 163.
        assertTrue(f.flats.any { kotlin.math.abs(it.first - 163f) < 0.01f })
        // Two studs side by side and the flat one: 80" centred, so the pair from 76 3/4" to 79 3/4", the flat to 83 1/4".
        assertTrue(f.studs.any { kotlin.math.abs(it - 76.75f) < 0.01f } && f.studs.any { kotlin.math.abs(it - 78.25f) < 0.01f })
        assertTrue(f.flats.any { kotlin.math.abs(it.first - 79.75f) < 0.01f })
        assertTrue(f.studCount > plain.studCount)
        assertTrue(WallFraming.countText(f).contains("2 wall bucks"))
    }

    @Test
    fun `a wall's framing has jacks, kings, headers and cripples on layout at its openings`() {
        val (wall, s) = state()
        val f = WallFraming.frame(wall, s)
        assertEquals(2, f.openings.size)
        assertEquals(4, f.jacks.size)
        assertEquals(4, f.kings.size)
        // Door RO 38" wide (Dan's rule, interior), window 36 3/4".
        assertEquals(38f, f.openings[0].x2 - f.openings[0].x1, 0.01f)
        assertEquals(36.75f, f.openings[1].x2 - f.openings[1].x1, 0.01f)
        // No full stud inside a rough opening; every cripple is inside one.
        for (op in f.openings) assertTrue(f.studs.none { it + 1.5f > op.x1 + 0.01f && it < op.x2 - 0.01f })
        assertTrue(f.cripples.isNotEmpty())
        assertTrue(f.cripples.all { (x, _, _) -> f.openings.any { x >= it.x1 - 0.01f && x + 1.5f <= it.x2 + 0.01f } })
        // Under the window, cripples from the bottom plate to the rough sill; studs 92 5/8" precut on a 97 1/8" wall.
        assertTrue(f.cripples.any { (_, b, t) -> b == 1.5f && t < 36f })
        assertEquals(92.625f, f.precutIn, 0.01f)
        assertTrue(WallFraming.countText(f).contains("4 jacks"))
        // The elevation draws.
        assertTrue(WallFraming.buildElevation(wall, ViewerSide.BELOW, Offset.Zero, s, Color.White).size > 20)
    }
}
