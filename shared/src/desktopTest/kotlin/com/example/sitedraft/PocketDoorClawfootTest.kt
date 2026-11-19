package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DxfExport
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.OpeningPosition
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Trim
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pocket doors and the clawfoot tub (Crewsync "New symbols to add"). 1' = 50px. */
class PocketDoorClawfootTest {
    private val inch = 50f / 12f
    private val t = 4.5f * inch
    private val wall = DrawingElement.Symbol(symbolName = "2x4 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset(120f * inch, t / 2f), size = Size(240f * inch, t))
    private fun pocket(xIn: Float, right: Boolean = false) = DrawingElement.Symbol(
        symbolName = "Pocket Door 2'-8\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
        center = Offset(xIn * inch, t / 2f), size = Size(32f * inch, t),
        properties = mapOf(PlanSymbols.PROP_HEIGHT_IN to 80f, PlanSymbols.PROP_HINGE_RIGHT to if (right) 1f else 0f))

    @Test
    fun `pocket doors are in the library and seat in the wall like a sliding door`() {
        assertTrue(PlanSymbols.doorsWindows.containsAll(listOf("Pocket Door 2'-6\"", "Pocket Door 2'-8\"", "Pocket Door 3'-0\"")))
        assertTrue(PlanSymbols.fixtures.contains(PlanSymbols.CLAWFOOT_TUB))
        val state = CanvasState(elements = listOf(wall))
        val placed = FloorPlan.alignOpening("Pocket Door 2'-8\"", Offset(60f * inch, t / 2f), Offset(60f * inch, t / 2f), state)
        assertNotNull(placed)
        assertEquals(32f * inch, placed!!.size!!.width, 0.01f)
        assertEquals(t, placed.size!!.height, 0.01f)
        assertTrue(PlanSymbols.turnsBySwappingSize(pocket(60f)))
    }

    @Test
    fun `the pocket door is measured on the wall and cased in the elevation`() {
        val d = pocket(60f)
        val state = CanvasState(elements = listOf(wall, d))
        // Centre at 60": near edge 44" from the left end.
        val dist = OpeningPosition.distance(d, state, OpeningPosition.Ref(fromEnd = false, toCenter = false, outside = false))
        assertEquals(44f, dist!! / inch, 0.01f)
        val items = Elevation.itemsOnWall(wall, ViewerSide.BELOW, state)
        assertEquals(Elevation.Kind.OPENING, items.single().kind)
        val spec = ElevationSpec(wallId = wall.id, side = ViewerSide.BELOW, trimOn = true, baseIn = 3.25f)
        val parts = Elevation.build(wall, spec, Offset(0f, 1000f), state)
        // A recessed pull: a 1 1/2" x 6" rectangle.
        val pulls = parts.filterIsInstance<DrawingElement.Rectangle>().filter {
            kotlin.math.abs(it.size.width - 1.5f * inch) < 0.01f && kotlin.math.abs(it.size.height - 6f * inch) < 0.01f
        }
        assertEquals(1, pulls.size)
        // Pocket on the left as drawn: the pull is at the right edge (the latch side), seen from below.
        val item = items.single()
        assertTrue(pulls[0].topLeft.x > (item.from + item.to) / 2f)
        val flipped = Elevation.build(wall, spec, Offset(0f, 1000f), CanvasState(elements = listOf(wall, pocket(60f, right = true))))
        val pull2 = flipped.filterIsInstance<DrawingElement.Rectangle>().single {
            kotlin.math.abs(it.size.width - 1.5f * inch) < 0.01f && kotlin.math.abs(it.size.height - 6f * inch) < 0.01f
        }
        assertTrue(pull2.topLeft.x < (item.from + item.to) / 2f)
        // The base stops at its casing, like any door.
        val (casL, casR) = Trim.outerEdges(item.from, item.to, false, spec, inch)
        val bases = parts.filterIsInstance<DrawingElement.Rectangle>().filter {
            kotlin.math.abs(it.size.height - spec.baseIn * inch) < 0.01f && kotlin.math.abs(it.topLeft.y + it.size.height - 1000f) < 0.01f
        }
        assertTrue(bases.isNotEmpty())
        for (b in bases) assertTrue(b.topLeft.x + b.size.width <= casL + 0.01f || b.topLeft.x >= casR - 0.01f)
    }

    @Test
    fun `the clawfoot tub draws in elevation and both export to DXF`() {
        val tub = DrawingElement.Symbol(symbolName = PlanSymbols.CLAWFOOT_TUB, category = PlanSymbols.FIXTURES, color = Color.White,
            center = Offset(180f * inch, t + 15f * inch), size = Size(60f * inch, 30f * inch))
        val state = CanvasState(elements = listOf(wall, pocket(60f), tub))
        val items = Elevation.itemsOnWall(wall, ViewerSide.BELOW, state)
        assertTrue(items.any { it.symbol.symbolName == PlanSymbols.CLAWFOOT_TUB && it.kind == Elevation.Kind.FIXTURE })
        val withTub = Elevation.build(wall, ElevationSpec(wallId = wall.id, side = ViewerSide.BELOW), Offset(0f, 1000f), state)
        val without = Elevation.build(wall, ElevationSpec(wallId = wall.id, side = ViewerSide.BELOW), Offset(0f, 1000f), CanvasState(elements = listOf(wall, pocket(60f))))
        assertTrue(withTub.size >= without.size + 10)
        val dxf = DxfExport.export(state)
        assertTrue(dxf.contains("ENTITIES"))
        assertTrue(dxf.length > DxfExport.export(CanvasState(elements = listOf(wall))).length)
    }
}
