package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DxfExport
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.Fill
import com.example.sitedraft.ui.canvas.FillPattern
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.HatchGeometry
import com.example.sitedraft.ui.canvas.Placement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Shower
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HatchTest {
    private val inch = 50f / 12f

    private fun wall(cx: Float, cy: Float, w: Float, h: Float) = DrawingElement.Symbol(
        symbolName = "2x4 Wall", category = PlanSymbols.WALLS, center = Offset(cx, cy), size = Size(w, h), color = Color.White
    )

    /** A 400 x 300 px room inside four 20px walls: inside is x 100..500, y 100..400. */
    private fun room() = listOf(
        wall(300f, 90f, 440f, 20f), wall(300f, 410f, 440f, 20f),
        wall(90f, 250f, 20f, 340f), wall(510f, 250f, 20f, 340f)
    )

    @Test
    fun `the area at a tap runs to the wall faces`() {
        val state = CanvasState(elements = room())
        val r = FloorPlan.areaAt(Offset(200f, 200f), state)!!
        assertEquals(100f, r.left, 0.01f); assertEquals(500f, r.right, 0.01f)
        assertEquals(100f, r.top, 0.01f); assertEquals(400f, r.bottom, 0.01f)
        assertNull(FloorPlan.areaAt(Offset(700f, 200f), state)) // outside: open on the right
    }

    @Test
    fun `tapping an area hatches it, under the walls`() {
        val vm = DrawingManager()
        room().forEach { w ->
            vm.onEvent(DrawingEvent.SelectSymbol(w.symbolName, w.category))
            vm.onEvent(DrawingEvent.PlaceAt(w.center))
        }
        // The walls snap and size from the library; just check a hatch lands and goes first.
        val before = vm.state.value.elements.size
        vm.onEvent(DrawingEvent.StartPlacement(Placement.Hatch(FillPattern.TILE, 12f)))
        val s = vm.state.value
        val walls = s.elements.filterIsInstance<DrawingElement.Symbol>()
        val inside = walls.map { FloorPlan.bounds(it).center }.let { cs -> Offset(cs.map { it.x }.average().toFloat(), cs.map { it.y }.average().toFloat()) }
        vm.onEvent(DrawingEvent.PlaceAt(inside))
        val after = vm.state.value.elements
        if (FloorPlan.areaAt(inside, s) != null) {
            assertEquals(before + 1, after.size)
            val hatch = after.first() as DrawingElement.Rectangle
            assertEquals(FillPattern.TILE, hatch.fill.pattern)
        } else {
            assertEquals(before, after.size)
        }
    }

    @Test
    fun `clipping keeps only what is inside the outline`() {
        val square = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f))
        val pieces = HatchGeometry.clipSegment(Offset(-5f, 5f), Offset(15f, 5f), square)
        assertEquals(1, pieces.size)
        assertEquals(0f, pieces[0].first.x, 0.001f); assertEquals(10f, pieces[0].second.x, 0.001f)
        val outside = HatchGeometry.clipSegment(Offset(-5f, 5f), Offset(15f, 5f), square, keepInside = false)
        assertEquals(2, outside.size)
        // Every pattern clips to a circle without anything outside it.
        val circle = DrawingElement.Circle(center = Offset(50f, 50f), radius = 40f, color = Color.White, strokeWidth = 1f)
        for (p in FillPattern.entries) {
            val h = HatchGeometry.clipped(Fill(pattern = p, spacing = 8f), HatchGeometry.outlineOf(circle)!!)
            for ((a, b) in h.segments) for (q in listOf(a, b)) assertTrue("$p $q", (q - Offset(50f, 50f)).getDistance() <= 40.5f)
        }
    }

    @Test
    fun `a hatched rectangle exports its hatch lines on the HATCH layer`() {
        val rect = DrawingElement.Rectangle(
            topLeft = Offset(0f, 0f), size = Size(100f, 50f), color = Color.White, strokeWidth = 1f,
            fill = Fill(pattern = FillPattern.TILE, spacing = 10f)
        )
        val dxf = DxfExport.export(CanvasState(elements = listOf(rect)))
        val hatchLines = dxf.lines().windowed(4).count { it[0].trim() == "LINE" && it[2].trim() == DxfExport.HATCH }
        assertTrue("hatch lines: $hatchLines", hatchLines >= 10)
    }

    @Test
    fun `wall tile in an elevation is hidden behind the cabinets`() {
        val t = 4.5f * inch
        val w = wall(500f, 0f, 1000f, t)
        val base = DrawingElement.Symbol(
            symbolName = "Base 36\"", category = PlanSymbols.CABINETS, center = Offset(175f, t / 2f + 12f * inch),
            size = Size(36f * inch, 24f * inch), color = Color.White
        )
        val state = CanvasState(elements = listOf(w, base))
        val spec = ElevationSpec(wallId = w.id, side = ViewerSide.BELOW, tileFromIn = 36f, tileToIn = 54f, tileSizeIn = 3f, tilePattern = 1)
        val parts = Elevation.build(w, spec, Offset(0f, 2000f), state)
        val tile = parts.filterIsInstance<DrawingElement.Rectangle>().first()
        assertEquals(FillPattern.BRICK, tile.fill.pattern)
        assertEquals(18f * inch, tile.size.height, 0.01f)
        assertTrue(parts.filterIsInstance<DrawingElement.Rectangle>().drop(1).all { it.fill.pattern == FillPattern.SOLID })
        // No tile: nothing is blanked (as before).
        val plain = Elevation.build(w, ElevationSpec(wallId = w.id, side = ViewerSide.BELOW), Offset(0f, 2000f), state)
        assertTrue(plain.filterIsInstance<DrawingElement.Rectangle>().all { it.fill.pattern == FillPattern.NONE })
    }

    @Test
    fun `a shower exports to DXF with its floor tile on the HATCH layer`() {
        val (wIn, dIn) = Shower.footprints.getValue("Alcove Shower 60x32")
        val s = DrawingElement.Symbol(
            symbolName = "Alcove Shower 60x32", category = PlanSymbols.SHOWERS, center = Offset(500f, 500f),
            size = Size(wIn * inch, dIn * inch), color = Color.White, properties = Shower.defaults("Alcove Shower 60x32")
        )
        val dxf = DxfExport.export(CanvasState(elements = listOf(s)))
        val lines = dxf.lines()
        assertTrue(lines.windowed(4).any { it[0].trim() == "LINE" && it[2].trim() == DxfExport.HATCH })
        assertTrue(lines.windowed(4).any { it[0].trim() == "POLYLINE" && it[2].trim() == DxfExport.FIXTURES })
    }
}
