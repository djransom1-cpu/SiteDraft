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
import com.example.sitedraft.ui.canvas.Fill
import com.example.sitedraft.ui.canvas.FillPattern
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Shower
import com.example.sitedraft.ui.canvas.TileTakeoff
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.rotateAbout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1' = 50px, so 1" = 4.1667px. */
class TileTakeoffTest {
    private val inch = 50f / 12f
    private fun sym(name: String, cat: String, cx: Float, cy: Float, wIn: Float, dIn: Float, props: Map<String, Float> = emptyMap()) =
        DrawingElement.Symbol(symbolName = name, category = cat, center = Offset(cx, cy), size = Size(wIn * inch, dIn * inch), color = Color.White, properties = props)

    @Test
    fun `a hatched floor less the cabinet on it, counted in 12 x 24 tiles`() {
        // 8' x 5' room hatched with 12 x 24 tile, a 36" base cabinet in the corner.
        val hatch = DrawingElement.Rectangle(
            topLeft = Offset(0f, 0f), size = Size(96f * inch, 60f * inch), color = Color.White, strokeWidth = 0f,
            fill = Fill(pattern = FillPattern.TILE, spacing = 12f * inch, length = 24f * inch)
        )
        val base = sym("Base 36\"", PlanSymbols.CABINETS, 18f * inch, 12f * inch, 36f, 24f)
        val label = DrawingElement.Text(text = "Bathroom\n8' x 5'", topLeft = Offset(40f * inch, 30f * inch), fontSize = 20f, color = Color.White)
        val rows = TileTakeoff.rows(CanvasState(elements = listOf(hatch, base, label)))
        val floor = rows.single()
        assertEquals("Bathroom", floor.location)
        assertEquals(40f - 6f, floor.netSqFt, 0.01f)
        val total = TileTakeoff.totals(rows, 10f, 8f).single()
        assertEquals("12 x 24 grid", total.tile.label)
        assertEquals(37.4f, total.withWasteSqFt, 0.01f)
        assertEquals(19, total.tiles)   // 37.4 / 2 sq ft each, rounded up
        assertEquals(5, total.boxes)    // 8 sq ft a box
    }

    @Test
    fun `a shower counts its floor inside the curb and its walls with curb and niche`() {
        val name = "Alcove Shower 60x32"
        val s = sym(name, PlanSymbols.SHOWERS, 500f, 500f, 60f, 32f, Shower.defaults(name))
        val rows = TileTakeoff.rows(CanvasState(elements = listOf(s)))
        val floor = rows.single { it.where == "Shower floor" }
        assertEquals(60f * 27.5f / 144f, floor.netSqFt, 0.01f)
        assertEquals("2 x 2 grid", floor.tile.label)
        val walls = rows.single { it.where == "Shower walls" }
        // Back 60 x 84 and two sides 32 x 84, curb 60 x (4.5 + 2 x 4), niche sides 2 x (12 + 24) x 3.5.
        assertEquals((60f * 84f + 2f * 32f * 84f + 60f * 12.5f + 252f) / 144f, walls.netSqFt, 0.01f)
        assertEquals("12 x 24 grid", walls.tile.label)
    }

    @Test
    fun `a backsplash less the window in it`() {
        val t = 4.5f * inch
        val wall = sym("2x4 Wall", PlanSymbols.WALLS, 500f, 0f, 240f, 4.5f)
        val window = sym("Window 4'-0\"", PlanSymbols.DOORS_WINDOWS, 500f, 0f, 48f, 4.5f, mapOf(PlanSymbols.PROP_SILL_IN to 36f, PlanSymbols.PROP_HEIGHT_IN to 48f))
        val plan = listOf(wall, window)
        val spec = ElevationSpec(wallId = wall.id, side = ViewerSide.BELOW, tileFromIn = 36f, tileToIn = 54f, tileSizeIn = 3f, tileLengthIn = 6f, tilePattern = 1)
        val view = DrawingElement.ElevationView(spec = spec, origin = Offset(0f, 2000f), size = Elevation.sizePx(wall, spec, CanvasState(elements = plan)), color = Color.White)
        val rows = TileTakeoff.rows(CanvasState(elements = plan + view))
        val backsplash = rows.single { it.where == "Wall" }
        // 20' x 18" = 30 sq ft, less the 4' x 18" of window in the band.
        assertEquals(30f - 6f, backsplash.netSqFt, 0.05f)
        assertEquals("3 x 6 subway", backsplash.tile.label)
        assertTrue(t > 0f)
    }

    @Test
    fun `herringbone and hex carry 5 percent more waste`() {
        val rows = listOf(TileTakeoff.Row("A", "Floor", FillPattern.HERRINGBONE, 3f, 12f, 100f))
        val total = TileTakeoff.totals(rows, 10f, null).single()
        assertEquals(15f, total.wastePct, 0.001f)
        assertEquals(115f * 144f / 36f, total.tiles.toFloat(), 1f)
    }

    @Test
    fun `swinging a door to the other side keeps it in its opening, swapping the hinge mirrors it`() {
        val vm = DrawingManager()
        vm.onEvent(DrawingEvent.SelectSymbol("Door 3'-0\"", PlanSymbols.DOORS_WINDOWS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(300f, 300f)))
        val door = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        fun threshold(d: DrawingElement.Symbol) = rotateAbout(d.center + Offset(0f, d.size.height / 2f), d.center, d.rotation)
        val before = threshold(door)
        vm.onEvent(DrawingEvent.SwingDoorOtherSide(door.id))
        val swung = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        assertEquals(before.x, threshold(swung).x, 0.01f)
        assertEquals(before.y, threshold(swung).y, 0.01f)
        assertEquals((door.rotation + 180f) % 360f, swung.rotation, 0.01f)
        assertTrue(PlanSymbols.hingeRight(swung))
        vm.onEvent(DrawingEvent.SwapDoorHinge(door.id))
        val swapped = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        assertTrue(!PlanSymbols.hingeRight(swapped))
        assertEquals(swung.center.x, swapped.center.x, 0.01f)
    }
}
