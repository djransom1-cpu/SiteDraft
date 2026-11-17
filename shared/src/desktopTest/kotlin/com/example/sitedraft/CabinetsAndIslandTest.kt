package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CabinetList
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cabinet codes, the cabinet list by room, and island elevations. 1' = 50px. */
class CabinetsAndIslandTest {
    private val inch = 50f / 12f

    private fun cab(name: String, xIn: Float, yIn: Float, props: Map<String, Float> = emptyMap(), cat: String = PlanSymbols.CABINETS): DrawingElement.Symbol {
        val (w, d) = PlanSymbols.footprintInches.getValue(name)
        return DrawingElement.Symbol(symbolName = name, category = cat, color = Color.White, properties = props,
            center = Offset((xIn + w / 2f) * inch, (yIn + d / 2f) * inch), size = Size(w * inch, d * inch))
    }

    private fun wall(l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(
        symbolName = "2x4 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch)
    )

    @Test
    fun `cabinets get their shop codes`() {
        val s = CanvasState()
        assertEquals("B24", PlanSymbols.cabinetLabel(cab("Base 24\"", 0f, 0f), s))
        assertEquals("BD18", PlanSymbols.cabinetLabel(cab("Base 18\"", 0f, 0f, mapOf(PlanSymbols.PROP_FRONT to PlanSymbols.FRONT_THREE_DRAWERS.toFloat())), s))
        assertEquals("W2430", PlanSymbols.cabinetLabel(cab("Upper 24\"", 0f, 0f), s))
        assertEquals("W3036", PlanSymbols.cabinetLabel(cab("Upper 30\"", 0f, 0f, mapOf(PlanSymbols.PROP_UPPER_HEIGHT_IN to 36f)), s))
        assertEquals("WDC2430", PlanSymbols.cabinetLabel(cab(PlanSymbols.UPPER_CORNER, 0f, 0f), s))
        assertEquals("LS36", PlanSymbols.cabinetLabel(cab("Corner Base 36\"", 0f, 0f), s))
        assertEquals("P2484", PlanSymbols.cabinetLabel(cab("Pantry 24\"", 0f, 0f), s))
        assertEquals("V30", PlanSymbols.cabinetLabel(cab("Vanity 30\"", 0f, 0f), s))
        assertEquals("BF1-1/2", PlanSymbols.cabinetLabel(cab("Filler 1 1/2\"", 0f, 0f), s))
        assertEquals("WF3", PlanSymbols.cabinetLabel(cab("Upper Filler 3\"", 0f, 0f), s))
        assertEquals("ISL72", PlanSymbols.cabinetLabel(cab("Island 3'x6'", 0f, 0f), s))
        assertNull(PlanSymbols.cabinetLabel(cab("Toilet", 0f, 0f, cat = PlanSymbols.FIXTURES), s))
        // A sink set in its top makes a base a sink base.
        val base = cab("Base 36\"", 0f, 0f)
        val sink = DrawingElement.Symbol(symbolName = "Kitchen Sink", category = PlanSymbols.FIXTURES, color = Color.White,
            center = base.center, size = Size(33f * inch, 22f * inch))
        assertEquals("SB36", PlanSymbols.cabinetLabel(base, CanvasState(elements = listOf(base, sink))))
    }

    @Test
    fun `the cabinet list counts by room and code`() {
        // A 12' x 10' kitchen with a label, and a bare 6' x 10' room beside it.
        val walls = listOf(wall(0f, 0f, 240f, 4.5f), wall(0f, 124.5f, 240f, 129f), wall(0f, 4.5f, 4.5f, 124.5f),
            wall(148.5f, 4.5f, 153f, 124.5f), wall(235.5f, 4.5f, 240f, 124.5f))
        val label = DrawingElement.Text(text = "Kitchen\n12' x 10'", topLeft = Offset(60f * inch, 60f * inch), fontSize = 20f, color = Color.White)
        val cabs = listOf(cab("Base 24\"", 4.5f, 4.5f), cab("Base 24\"", 28.5f, 4.5f), cab("Upper 24\"", 4.5f, 4.5f), cab("Base 30\"", 160f, 4.5f))
        val rows = CabinetList.rows(CanvasState(elements = walls + label + cabs))
        val kitchen = rows.filter { it.room == "Kitchen" }
        assertEquals(listOf("B24" to 2, "W2430" to 1), kitchen.map { it.code to it.qty })
        val other = rows.filter { it.room != "Kitchen" }
        assertEquals(1, other.size)
        assertTrue(other[0].room.startsWith("Room "))
        assertEquals("B30", other[0].code)
        assertTrue(CabinetList.csv(rows).lines().first().startsWith("Room\tCode\tQty"))
    }

    @Test
    fun `an island has an elevation of each face`() {
        val island = cab("Island 3'x6'", 0f, 0f, mapOf(PlanSymbols.PROP_OVERHANG to 3f, PlanSymbols.PROP_OVERHANG_IN to 12f, PlanSymbols.PROP_STYLE to 1f))
        val state = CanvasState(elements = listOf(island))
        for (face in 0..3) {
            val spec = ElevationSpec(wallId = island.id, side = ViewerSide.BELOW, islandFace = face)
            val size = Elevation.sizePx(island, spec, state)
            assertEquals(if (face < 2) 72f * inch else 36f * inch, size.width, 0.01f)
            val view = DrawingElement.ElevationView(spec = spec, origin = Offset(0f, 1000f), size = size, color = Color.White)
            val parts = Elevation.partsOf(view, state)
            assertTrue("face $face", parts.size > (if (face < 2) 6 else 3))
            // The countertop reaches 12" past the right end in the front view (the overhang).
            if (face == 0) {
                val counter = parts.filterIsInstance<DrawingElement.Rectangle>().maxByOrNull { it.topLeft.x + it.size.width }!!
                assertEquals(72f * inch + 0.5f * inch + 12f * inch, counter.topLeft.x + counter.size.width, 0.05f)
            }
            // No wall frame: nothing reaches up to the wall height.
            assertTrue(parts.filterIsInstance<DrawingElement.Line>().none { it.start.y < 1000f - 40f * inch || it.end.y < 1000f - 40f * inch })
        }
    }
}
