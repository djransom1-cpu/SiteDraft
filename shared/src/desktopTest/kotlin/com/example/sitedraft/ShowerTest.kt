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
import com.example.sitedraft.ui.canvas.FillPattern
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Shower
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1' = 50px, so 1" = 4.1667px. */
class ShowerTest {
    private val inch = 50f / 12f

    private fun shower(name: String, props: Map<String, Float> = emptyMap(), rotation: Float = 0f): DrawingElement.Symbol {
        val (w, d) = Shower.footprints.getValue(name)
        return DrawingElement.Symbol(
            symbolName = name, category = PlanSymbols.SHOWERS, center = Offset(500f, 500f),
            size = Size(w * inch, d * inch), color = Color.White, rotation = rotation,
            properties = Shower.defaults(name) + props
        )
    }

    private fun view(s: DrawingElement.Symbol, face: Int): List<DrawingElement> {
        val state = CanvasState(elements = listOf(s))
        val spec = ElevationSpec(wallId = s.id, side = ViewerSide.BELOW, showerFace = face)
        val v = DrawingElement.ElevationView(spec = spec, origin = Offset(0f, 2000f), size = Elevation.sizePx(s, spec, state), color = Color.White)
        return Elevation.partsOf(v, state)
    }

    @Test
    fun `every shower in the library has a footprint and sensible defaults`() {
        for (name in Shower.library) {
            assertTrue(name, PlanSymbols.footprintInches.containsKey(name))
            assertTrue(name, Shower.defaults(name).keys.containsAll(Shower.keys))
        }
        assertEquals(0f, Shower.defaults("Walk-In Shower 60x36")[Shower.CURB_H])
    }

    @Test
    fun `placing a shower from the library carries its settings`() {
        val vm = DrawingManager()
        vm.onEvent(DrawingEvent.SelectSymbol("Alcove Shower 60x32", PlanSymbols.SHOWERS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(300f, 300f)))
        val s = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        assertEquals(84f, s.properties[Shower.TILE_H])
        vm.onEvent(DrawingEvent.UpdateElementProperty(s.id, Shower.TILE_H, "96"))
        val updated = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        assertEquals(96f, updated.properties[Shower.TILE_H])
    }

    @Test
    fun `a back wall elevation is the shower width by the ceiling, tiled to the tile height`() {
        val s = shower("Alcove Shower 60x32")
        val size = Elevation.sizePx(s, ElevationSpec(wallId = s.id, side = ViewerSide.BELOW, showerFace = Shower.BACK), CanvasState(elements = listOf(s)))
        assertEquals(60f * inch, size.width, 0.01f)
        assertEquals(96f * inch, size.height, 0.01f)
        val tile = view(s, Shower.BACK).filterIsInstance<DrawingElement.Rectangle>().single { it.fill.pattern == FillPattern.TILE }
        assertEquals(84f * inch, tile.size.height, 0.01f)
        assertEquals(12f * inch, tile.fill.spacing, 0.01f)
    }

    @Test
    fun `the valve wall shows the valve and head, with their heights dimensioned`() {
        val s = shower("Alcove Shower 60x32") // valve on the left wall by default
        val left = view(s, Shower.LEFT)
        assertTrue(left.filterIsInstance<DrawingElement.Text>().any { it.text == "VALVE" })
        val dims = left.filterIsInstance<DrawingElement.Dimension>().map { it.value * 12f }
        assertTrue(dims.toString(), dims.any { kotlin.math.abs(it - 48f) < 0.01f })
        assertTrue(dims.toString(), dims.any { kotlin.math.abs(it - 80f) < 0.01f })
        assertTrue(view(s, Shower.BACK).filterIsInstance<DrawingElement.Text>().none { it.text == "VALVE" })
    }

    @Test
    fun `door plus panel splits the front at the door width, sliding overlaps`() {
        val s = shower("Alcove Shower 60x32")
        val panels = Shower.runLayout(s, 60f)
        assertEquals(listOf(Shower.Pane.FIXED, Shower.Pane.DOOR), panels.map { it.kind })
        assertEquals(36f, panels[1].from, 0.01f)
        val sliding = Shower.runLayout(s.copy(properties = s.properties + (Shower.GLASS to 2f)), 60f)
        assertTrue(sliding[0].to > sliding[1].from)
    }

    @Test
    fun `door plus panel can hinge off the panel`() {
        // Door at the right end (the default): hinged on the wall it's at the run's far end; off the panel, at its near end.
        val s = shower("Alcove Shower 60x32")
        val onWall = Shower.runLayout(s, 60f).first { it.kind == Shower.Pane.DOOR }
        assertEquals(false, onWall.hingeAtFrom)
        val onPanel = Shower.runLayout(s.copy(properties = s.properties + (Shower.HINGE_ON to 1f)), 60f).first { it.kind == Shower.Pane.DOOR }
        assertEquals(true, onPanel.hingeAtFrom)
        assertEquals(36f, onPanel.from, 0.01f)
        // Door at the left end: the panel is on its right.
        val left = s.copy(properties = s.properties + (Shower.DOOR_SIDE to 0f) + (Shower.HINGE_ON to 1f))
        assertEquals(false, Shower.runLayout(left, 60f).first { it.kind == Shower.Pane.DOOR }.hingeAtFrom)
    }

    @Test
    fun `every face of every shower type builds`() {
        for (name in Shower.library) {
            val s = shower(name, mapOf(Shower.BENCH_WALL to 0f))
            for (f in Shower.faces(Shower.type(name))) {
                assertTrue("$name face $f", view(s, f).size > 5)
            }
            for (g in 0..4) {
                val sg = shower(name, mapOf(Shower.GLASS to g.toFloat()))
                assertTrue("$name glass $g", view(sg, Shower.FRONT).isNotEmpty())
            }
        }
    }

    @Test
    fun `walls go outside the tiled sides, turned with the shower`() {
        val s = shower("Alcove Shower 60x32")
        val state = CanvasState(elements = listOf(s))
        val walls = Shower.wallsFor(s, state)
        assertEquals(3, walls.size)
        val t = 4.5f * inch
        val back = walls.first()
        assertEquals(60f * inch + 2f * t, back.size.width, 0.01f)
        assertEquals(500f - 16f * inch - t / 2f, back.center.y, 0.01f)
        // Corner shower: back and left only.
        assertEquals(2, Shower.wallsFor(shower("Corner Shower 36x36"), state).size)
        // Turned 90: the back wall stands vertical on the right (clockwise turn).
        val turned = Shower.wallsFor(s.copy(rotation = 90f), state).first()
        assertTrue(turned.size.height > turned.size.width)
        assertTrue(turned.center.x > 500f)
    }

    @Test
    fun `a slab seat is stone over the tile, a full bench is tiled top and front`() {
        val name = "Alcove Shower 60x32"
        val full = shower(name, mapOf(Shower.BENCH_WALL to Shower.BACK.toFloat()))
        val slab = shower(name, mapOf(Shower.BENCH_WALL to Shower.BACK.toFloat(), Shower.BENCH_STYLE to Shower.BENCH_SLAB.toFloat()))
        for (f in Shower.faces(Shower.type(name))) assertTrue("slab face $f", view(slab, f).size > 5)
        fun rows(s: DrawingElement.Symbol) = com.example.sitedraft.ui.canvas.TileTakeoff.rows(CanvasState(elements = listOf(s)))
        val fullWall = rows(full).first { it.where.contains("wall", ignoreCase = true) }
        val slabWall = rows(slab).first { it.where.contains("wall", ignoreCase = true) }
        // The full bench adds its top and front (60" x (15" + 18")) to the wall tile; the slab adds none.
        assertEquals(60f * 33f / 144f, fullWall.netSqFt - slabWall.netSqFt, 0.05f)
        assertTrue(slabWall.note, slabWall.note.contains("slab"))
        // The floor runs under the slab, not under the full bench.
        val fullFloor = rows(full).first { it.where == "Shower floor" }
        val slabFloor = rows(slab).first { it.where == "Shower floor" }
        assertEquals(60f * 15f / 144f, slabFloor.netSqFt - fullFloor.netSqFt, 0.05f)
    }

    @Test
    fun `corner shelves and a ledge draw on their walls, and tiled ones count in the tile`() {
        val name = "Alcove Shower 60x32"
        val corner = shower(name, mapOf(Shower.SHELF_KIND to Shower.SHELF_CORNER.toFloat(), Shower.SHELF_WHERE to 1f, Shower.SHELF_N to 3f))
        assertEquals(listOf(36f, 48f, 60f), Shower.shelfHeights(corner))
        for (f in Shower.faces(Shower.type(name))) assertTrue("corner face $f", view(corner, f).size > 5)
        val ledge = shower(name, mapOf(Shower.SHELF_KIND to Shower.SHELF_LEDGE.toFloat(), Shower.SHELF_WHERE to Shower.BACK.toFloat(), Shower.SHELF_MAT to 1f))
        for (f in Shower.faces(Shower.type(name))) assertTrue("ledge face $f", view(ledge, f).size > 5)
        fun wall(s: DrawingElement.Symbol) = com.example.sitedraft.ui.canvas.TileTakeoff.rows(CanvasState(elements = listOf(s))).first { it.where == "Shower walls" }
        val plain = shower(name)
        // Two tiled 60" x 9" ledges: top and bottom of each plus the 1 1/4" edge.
        assertEquals(2f * (2f * 60f * 9f + 60f * 1.25f) / 144f, wall(ledge).netSqFt - wall(plain).netSqFt, 0.05f)
        assertTrue(wall(corner).note.contains("stone shelves"))
        // A back-right corner on a corner shower (its right side is glass) has no shelves.
        val cs = shower("Corner Shower 36x36", mapOf(Shower.SHELF_KIND to Shower.SHELF_CORNER.toFloat(), Shower.SHELF_WHERE to 1f))
        assertTrue(Shower.shelfHeights(cs).isEmpty())
    }
}
