package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CabinetList
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.OpeningPosition
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Upper heights, crown and valance, finished ends, and placing openings by distance. 1' = 50px. */
class UppersOpeningsTest {
    private val inch = 50f / 12f

    private fun wall(l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(
        symbolName = "2x6 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch)
    )

    private fun item(name: String, xIn: Float, yIn: Float, props: Map<String, Float> = emptyMap(), cat: String = PlanSymbols.CABINETS): DrawingElement.Symbol {
        val (w, d) = PlanSymbols.footprintInches.getValue(name)
        return DrawingElement.Symbol(symbolName = name, category = cat, color = Color.White, properties = props,
            center = Offset((xIn + w / 2f) * inch, (yIn + d / 2f) * inch), size = Size(w * inch, d * inch))
    }

    // A 20' x 12' building of 6" walls, a partition at 10', a 3' window centred 5' from the left in the top wall.
    private val top = wall(0f, 0f, 240f, 6f)
    private val walls = listOf(top, wall(0f, 138f, 240f, 144f), wall(0f, 6f, 6f, 138f), wall(234f, 6f, 240f, 138f), wall(117.75f, 6f, 122.25f, 138f))
    private val window = DrawingElement.Symbol(symbolName = "Window 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
        center = Offset(60f * inch, 3f * inch), size = Size(36f * inch, 6f * inch))

    @Test
    fun `an opening's distance reads from the chosen corner, edge or centre`() {
        val state = CanvasState(elements = walls + window)
        fun d(fromEnd: Boolean, toCenter: Boolean, outside: Boolean) =
            OpeningPosition.distance(window, state, OpeningPosition.Ref(fromEnd, toCenter, outside))!! / inch
        assertEquals(36f, d(false, false, false), 0.01f)   // inside corner (left wall's face at 6") to the near edge (42")
        assertEquals(42f, d(false, false, true), 0.01f)    // outside corner of the building
        assertEquals(54f, d(false, true, false), 0.01f)    // to the centre
        assertEquals(39.75f, d(true, false, false), 0.01f) // from the right: the partition's face at 117 3/4"
        // Typing 24" from the inside corner to the near edge slides it there.
        val moved = OpeningPosition.placed(window, state, 24f * inch, OpeningPosition.Ref(false, false, false))!!
        assertEquals(48f * inch, moved.center.x, 0.01f)
        assertEquals(window.center.y, moved.center.y, 0.001f)
    }

    @Test
    fun `a shorter upper keeps the top line, a taller one keeps the bottom`() {
        val spec = ElevationSpec(wallId = "w", side = ViewerSide.BELOW)
        assertEquals(54f to 84f, Elevation.upperSpan(item("Upper 30\"", 0f, 0f), spec))
        assertEquals(66f to 84f, Elevation.upperSpan(item("Upper 30\"", 0f, 0f, mapOf(PlanSymbols.PROP_UPPER_HEIGHT_IN to 18f)), spec))
        assertEquals(54f to 96f, Elevation.upperSpan(item("Upper 30\"", 0f, 0f, mapOf(PlanSymbols.PROP_UPPER_HEIGHT_IN to 42f)), spec))
    }

    @Test
    fun `crown runs on the uppers and a valance spans the sink gap`() {
        val w = wall(0f, 0f, 240f, 4.5f)
        val face = 4.5f
        val items = listOf(
            item("Upper 30\"", 10f, face), item("Upper 30\"", 76f, face),
            item("Base 36\"", 40f, face),
            DrawingElement.Symbol(symbolName = "Kitchen Sink", category = PlanSymbols.FIXTURES, color = Color.White,
                center = Offset(58f * inch, (face + 11f) * inch), size = Size(33f * inch, 22f * inch))
        )
        val state = CanvasState(elements = listOf(w) + items)
        val plain = ElevationSpec(wallId = w.id, side = ViewerSide.BELOW)
        val base = Elevation.build(w, plain, Offset(0f, 2000f), state)
        val crowned = Elevation.build(w, plain.copy(crownIn = 3.5f), Offset(0f, 2000f), state)
        val crowns = crowned.filterIsInstance<DrawingElement.Rectangle>().filter { kotlin.math.abs(it.size.height - 3.5f * inch) < 0.01f }
        assertEquals(2, crowns.size)
        assertTrue(crowns.all { kotlin.math.abs(it.topLeft.y - (2000f - 87.5f * inch)) < 0.01f })
        val valanced = Elevation.build(w, plain.copy(valance = true), Offset(0f, 2000f), state)
        assertTrue(valanced.filterIsInstance<DrawingElement.Line>().size >= base.filterIsInstance<DrawingElement.Line>().size + 12)
    }

    @Test
    fun `an open end of a run is listed as finished`() {
        // Two bases along the top wall, the first against the left wall: the second's right end is open.
        val b1 = item("Base 24\"", 6f, 6f)
        val b2 = item("Base 24\"", 30f, 6f)
        val state = CanvasState(elements = walls + b1 + b2)
        assertEquals(false to false, CabinetList.finishedEnds(b1, state))
        assertEquals(false to true, CabinetList.finishedEnds(b2, state))
        assertTrue(CabinetList.rows(state).any { it.description.endsWith("finish right end") && it.qty == 1 })
        assertNull(CabinetList.finishedEnds(item("Island 3'x6'", 60f, 60f), state))
    }
}
