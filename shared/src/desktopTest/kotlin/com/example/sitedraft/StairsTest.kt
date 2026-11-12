package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Stairs
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.inchesToPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StairsTest {
    private val inch = 50f / 12f

    private fun stair(name: String, props: Map<String, Float> = emptyMap()): DrawingElement.Symbol {
        val s0 = DrawingElement.Symbol(symbolName = name, category = PlanSymbols.STAIRS, center = Offset(500f, 500f), size = Size.Zero, color = Color.White, properties = Stairs.defaults(name) + props)
        val (w, d) = Stairs.footprintIn(s0)
        return s0.copy(size = Size(w * inch, d * inch))
    }

    @Test
    fun `risers follow the 7 1-2 inch rule within 6 1-4 to 8 1-4`() {
        assertEquals(14, Stairs.riserCount(108f)) // 7.71"
        assertEquals(5, Stairs.riserCount(36f))   // 7.2"
        for (rise in listOf(20f, 30f, 50f, 96f, 108f, 120f, 150f)) {
            val r = rise / Stairs.riserCount(rise)
            assertTrue("$rise -> $r", r in 6.25f..8.25f)
        }
    }

    @Test
    fun `a straight stair is as long as its treads`() {
        val s = stair(Stairs.STRAIGHT)
        val (w, d) = Stairs.footprintIn(s)
        assertEquals(36f, w, 0.01f)
        assertEquals(13 * 10f, d, 0.01f)
    }

    @Test
    fun `changing the rise resizes the stair in the plan`() {
        val vm = DrawingManager()
        vm.onEvent(DrawingEvent.SelectSymbol(Stairs.STRAIGHT, PlanSymbols.STAIRS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(300f, 300f)))
        val s = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        vm.onEvent(DrawingEvent.UpdateElementProperty(s.id, Stairs.RISE, "36"))
        val after = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        assertEquals(vm.state.value.inchesToPx(4 * 10f), after.size.height, 0.01f)
    }

    @Test
    fun `every stair builds its plan and both side elevations`() {
        for (name in Stairs.library) {
            val s = stair(name, mapOf(Stairs.RAIL_LEFT to 2f, Stairs.RAIL_RIGHT to 2f))
            assertTrue(name, Stairs.planParts(s, s.size.width, s.size.height, inch).size > 4)
            for (side in 0..1) {
                val state = CanvasState(elements = listOf(s))
                val spec = ElevationSpec(wallId = s.id, side = ViewerSide.BELOW, stairSide = side)
                val v = DrawingElement.ElevationView(spec = spec, origin = Offset(0f, 2000f), size = Elevation.sizePx(s, spec, state), color = Color.White)
                assertTrue("$name $side", Elevation.partsOf(v, state).size > 10)
            }
        }
    }

    @Test
    fun `a guard gets balusters under 4 inches apart`() {
        val s = stair(Stairs.STRAIGHT, mapOf(Stairs.RAIL_RIGHT to 2f, Stairs.RAIL_LEFT to 0f))
        val state = CanvasState(elements = listOf(s))
        val spec = ElevationSpec(wallId = s.id, side = ViewerSide.BELOW, stairSide = 0)
        val parts = Stairs.buildElevation(s, 0, Offset(0f, 2000f), state, Color.White)
        val balusters = parts.filterIsInstance<DrawingElement.Rectangle>().filter { kotlin.math.abs(it.size.width - 1.25f * inch) < 0.01f }
            .map { it.topLeft.x }.sorted()
        assertTrue(balusters.size > 20)
        val gaps = balusters.zipWithNext { a, b -> (b - a) / inch - 1.25f }
        assertTrue(gaps.toString(), gaps.all { it < 4f })
    }

    @Test
    fun `a curved stair on a 4 foot open side and 8 foot wall tapers its treads`() {
        val s = stair(Stairs.CURVED, mapOf(Stairs.INNER_R to 48f, Stairs.OUTER_R to 96f))
        val c = Stairs.curve(s)
        // 10" at the walkline, 12" in from the 4' side: 8" at the open side, 16" at the wall.
        assertEquals(8f, c.treadAt(c.ri), 0.01f)
        assertEquals(16f, c.treadAt(c.ro), 0.01f)
        // 13 treads of 1/6 radian each: about 124 degrees of turn.
        assertEquals(13f / 6f, c.sweep, 0.001f)
        // The unrolled elevations: the open side runs 13 x 8", the wall side 13 x 16".
        val state = CanvasState(elements = listOf(s))
        val inside = Stairs.elevationSize(s, 0, state).width / inch
        val outside = Stairs.elevationSize(s, 1, state).width / inch
        assertEquals(13 * 8f + 48f, inside, 0.05f)
        assertEquals(13 * 16f + 48f, outside, 0.05f)
    }
}
