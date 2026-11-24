package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DerivedLayers
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FoundationType
import com.example.sitedraft.ui.canvas.Garages
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Structure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garages, attached and detached (Nov 23 2026). */
class GarageTest {
    private val inch = 50f / 12f
    private fun sym(name: String, cat: String, l: Float, t: Float, r: Float, b: Float, id: String) =
        DrawingElement.Symbol(id = id, symbolName = name, category = cat, color = Color.White,
            center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch))
    private val house = listOf(
        sym("2x6 Wall", PlanSymbols.WALLS, 0f, 0f, 480f, 6.5f, "top"),
        sym("2x6 Wall", PlanSymbols.WALLS, 0f, 281.5f, 480f, 288f, "bottom"),
        sym("2x6 Wall", PlanSymbols.WALLS, 0f, 6.5f, 6.5f, 281.5f, "left"),
        sym("2x6 Wall", PlanSymbols.WALLS, 473.5f, 6.5f, 480f, 281.5f, "right")
    )
    private fun built(g: DrawingElement.Symbol, f: FoundationType = FoundationType.BASEMENT) =
        DerivedLayers.sync(CanvasState(elements = house + g, building = BuildingSettings(autoLayers = true, foundation = f)))
    private fun gen(s: CanvasState, key: String) = s.elements.singleOrNull { it.id == DerivedLayers.GEN_PREFIX + key } as DrawingElement.Symbol?

    @Test
    fun `a 24 x 24 garage on the west end is attached, with lead walls down to the basement footing`() {
        val g = sym(Garages.NAME, Garages.CATEGORY, -288f, 0f, 0f, 288f, "g1")
        val s = built(g)
        assertTrue(Garages.attachment(g, com.example.sitedraft.ui.canvas.RoofPlan.footprint(s), inch) != null)
        // Three frost walls, no wall against the house.
        assertTrue(gen(s, "ga:g1:w-out") != null && gen(s, "ga:g1:w-a") != null && gen(s, "ga:g1:w-b") != null)
        assertEquals("12\" Block Wall", gen(s, "ga:g1:w-out")!!.symbolName)
        // The outside (west) footing runs the full 24' plus its projections, owning the corners.
        val out = gen(s, "ga:g1:f-out")!!
        assertEquals(288f + 2f * 3.1875f, out.size.height / inch, 0.01f)
        // The side footings step down to the basement footing at the house end (west of the house face: their end).
        // The first 4' out from the house is its own lead wall footing, stepping down; the rest runs at frost depth.
        val lw = gen(s, "ga:g1:lw-a")!!
        assertEquals(2f, Structure.prop(lw, Structure.STEP), 0.01f)
        assertEquals(48f, lw.size.width / inch, 0.01f)
        val a = gen(s, "ga:g1:f-a")!!
        assertEquals(0f, Structure.prop(a, Structure.STEP), 0.01f)
        // On a crawl-space house, no lead walls.
        assertEquals(0f, Structure.prop(gen(built(g, FoundationType.CRAWL), "ga:g1:f-a")!!, Structure.STEP), 0.01f)
        // The slab on the framing layer, inside the garage's own walls and up to the shared house wall.
        val slab = gen(s, "gaf:g1:slab")!!
        assertEquals(288f - 5.5f, slab.size.width / inch, 0.01f)
    }

    @Test
    fun `a garage set apart is detached, with four walls and footings`() {
        val g = sym(Garages.NAME, Garages.CATEGORY, -600f, 0f, -312f, 288f, "g2")
        val s = built(g)
        for (k in listOf("w-n", "w-s", "w-w", "w-e", "f-n", "f-s", "f-w", "f-e")) assertTrue(k, gen(s, "ga:g2:$k") != null)
        assertTrue(s.elements.none { Structure.prop(it as? DrawingElement.Symbol ?: return@none false, Structure.STEP) > 0f && it.id.contains("g2") })
    }

    @Test
    fun `garage package - header, 8 foot doors, roof, elevations, placement and the frost wall detail`() {
        val g = sym(Garages.NAME, Garages.CATEGORY, -288f, 0f, 0f, 288f, "g1")
        val st = CanvasState(elements = house + g, building = BuildingSettings(autoLayers = true))
        // 16' door on the south (eave) wall: 13' of roof, 30 psf snow + 15 dead, + 10 plf wall = 595 plf over 16'-3".
        val h = Garages.header(g, st)
        assertEquals(595f, h.wPlf, 0.5f)
        assertEquals("2-PLY 1-3/4\" x 14\" LVL", h.name)
        // Plate 1297 1/8", less 3" plates and the 14" header, over the slab at grade 1180 1/2": 99 5/8" -> 8' doors fit.
        assertEquals(96f, Garages.doorHeightIn(g, st), 0.01f)
        // The roof: a ridge, eaves, trusses.
        val roof = Garages.roof3d(g, st)
        assertTrue(roof.any { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.RIDGE })
        assertTrue(roof.count { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.MEMBER } >= 12)
        // South elevation: the doors and the header named.
        val fp = com.example.sitedraft.ui.canvas.RoofPlan.footprint(st)!!
        val south = Garages.elevation(g, st, com.example.sitedraft.ui.canvas.ViewerSide.BELOW, fp, inch)
        assertTrue(south.second.any { it.text.startsWith("16' x 8' OVERHEAD DOOR") })
        assertTrue(south.second.any { it.text.startsWith("HEADER: 2-PLY") })
        // Seen from the east it's behind the house: not drawn.
        assertTrue(Garages.elevation(g, st, com.example.sitedraft.ui.canvas.ViewerSide.RIGHT, fp, inch).first.isEmpty())
        // Placement: west wall, 0 from the north corner, attached; centring a 24' garage on a 24' wall keeps it at 0.
        val pl = Garages.placement(g, fp, inch)!!
        assertEquals(2, pl.first); assertEquals(0f, pl.second, 0.01f); assertEquals(0f, pl.third, 0.01f)
        val moved = Garages.centerFor(g, fp, inch, 1, 36f, 0f)
        assertEquals((36f + 144f) * inch, moved.x, 0.1f)          // on the south wall, 3' from the west corner
        assertEquals((288f + 144f) * inch, moved.y, 0.1f)
        // The frost wall detail.
        val d = com.example.sitedraft.ui.canvas.GarageFrostDetail.geometry(st).notes.map { it.text }
        assertTrue(d.any { it.contains("42\" BELOW GRADE") })
        assertTrue(d.any { it.startsWith("6\" BLOCK, ONE 8\" COURSE") })
        assertTrue(d.any { it.startsWith("4\" CONC. SLAB AT GRADE") })
    }

    @Test
    fun `an attached garage shares the house wall and its roof runs into a hip end`() {
        val g = sym(Garages.NAME, Garages.CATEGORY, -288f, 36f, 0f, 252f, "g3")   // 24' x 18', 3' in from each side
        val hipHouse = BuildingSettings(roof = com.example.sitedraft.ui.canvas.RoofSettings(
            endA = com.example.sitedraft.ui.canvas.RoofEnd.HIP, endB = com.example.sitedraft.ui.canvas.RoofEnd.HIP))
        val st = CanvasState(elements = house + g, building = hipHouse)
        assertEquals(3, Garages.sharedWall(g, st))                       // its east wall is the house's west wall
        val roof = Garages.roof3d(g, st)
        // Valleys from the eaves to where the ridge meets the house's west hip, and a hip on its own west end to match the house.
        assertEquals(4, roof.count { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.HIP })
        val ridgeEast = roof.filter { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.RIDGE }.maxOf { maxOf(it.a.x, it.b.x) } / inch
        assertTrue("ridge runs in over the house, was $ridgeEast", ridgeEast > 12f)
        // No wall outline left on the house wall.
        assertTrue(roof.none { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.EAVE && kotlin.math.abs(it.a.x) < 0.5f && kotlin.math.abs(it.b.x) < 0.5f })
    }
}
