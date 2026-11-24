package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.Building
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DerivedLayers
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FoundationType
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoofPlan
import com.example.sitedraft.ui.canvas.RoofSettings
import com.example.sitedraft.ui.canvas.Structure
import com.example.sitedraft.ui.canvas.Wings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wings: the house becomes an L (Nov 23 2026). */
class WingTest {
    private val inch = 50f / 12f
    private fun sym(name: String, cat: String, l: Float, t: Float, r: Float, b: Float, id: String, props: Map<String, Float> = emptyMap()) =
        DrawingElement.Symbol(id = id, symbolName = name, category = cat, color = Color.White,
            center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch), properties = props)
    private val house = listOf(
        sym("2x6 Wall", PlanSymbols.WALLS, 0f, 0f, 480f, 6.5f, "top"),
        sym("2x6 Wall", PlanSymbols.WALLS, 0f, 281.5f, 480f, 288f, "bottom"),
        sym("2x6 Wall", PlanSymbols.WALLS, 0f, 6.5f, 6.5f, 281.5f, "left"),
        sym("2x6 Wall", PlanSymbols.WALLS, 473.5f, 6.5f, 480f, 281.5f, "right")
    )
    /** A 14' x 20' wing off the north wall at the east end. */
    private fun wing(found: Int) = sym(Wings.NAME, Wings.CATEGORY, 312f, -240f, 480f, 0f, "w1", mapOf(Wings.FOUND to found.toFloat()))
    private fun built(g: DrawingElement.Symbol, f: FoundationType = FoundationType.BASEMENT) =
        DerivedLayers.sync(CanvasState(elements = house + g, building = BuildingSettings(autoLayers = true, foundation = f, roof = RoofSettings())))
    private fun gen(s: CanvasState, key: String) = s.elements.singleOrNull { it.id == DerivedLayers.GEN_PREFIX + key } as DrawingElement.Symbol?

    @Test
    fun `a crawl wing gets its own walls, keeps the house basement wall and steps its footings down`() {
        val g = wing(1)
        val s = built(g)
        // The house's footprint (roof, elevations) stays the house's.
        val fp = RoofPlan.footprint(s)!!
        assertEquals(480f, fp.width / inch, 0.01f); assertEquals(288f, fp.height / inch, 0.01f)
        // Three exterior walls on the plan, generated.
        assertNotNull(gen(s, "wg:w1:w-out")); assertNotNull(gen(s, "wg:w1:w-a")); assertNotNull(gen(s, "wg:w1:w-b"))
        assertEquals(168f, gen(s, "wg:w1:w-out")!!.size.width / inch, 0.01f)
        // The joint: between the wing's inside faces, the house wall's thickness.
        val j = Wings.joint(g, fp, inch)!!
        assertEquals(168f - 13f, j.width / inch, 0.01f)
        // Crawl: the house basement wall stays whole; the wing's side footings have lead walls down to it.
        assertNotNull(gen(s, "fw:top"))
        assertEquals(2f, Structure.prop(gen(s, "wg:w1:fd:lw-a")!!, Structure.STEP), 0.01f)
        assertEquals(0f, Structure.prop(gen(s, "wg:w1:fd:f-a")!!, Structure.STEP), 0.01f)
        // Its floor, with blocking at the rim along the walls that run with the joists; the house's too.
        assertNotNull(gen(s, "wgf:w1:joists"))
        assertTrue(s.elements.any { it.id.startsWith(DerivedLayers.GEN_PREFIX + "rim:wg") })
        assertNotNull(gen(s, "rim:house:a")); assertNotNull(gen(s, "rim:house:b"))
        // A roof, ridge square to the house wall (up the page), into the house roof.
        val roof = Wings.roof3d(g, s)
        assertTrue(roof.any { it.kind == RoofPlan.Kind.RIDGE && kotlin.math.abs(it.a.x - it.b.x) < 0.01f })
    }

    @Test
    fun `a basement wing takes the house basement wall out where it joins`() {
        val g = wing(0)
        val s = built(g)
        assertNull(gen(s, "fw:top"))
        assertNotNull(gen(s, "fw:top:a"))
        // The piece left runs from the west end to the wing's west foundation wall.
        val a = gen(s, "fw:top:a")!!
        assertEquals(312f + Building.foundationWallIn(s.building), (a.center.x + a.size.width / 2f) / inch, 0.6f)
        assertNull(gen(s, "wg:w1:fd:lw-a"))
        // A beam across the opening, in pockets in the wing's side walls, sized (Dan, Nov 23).
        val beam = gen(s, "jbeam:w1")!!
        assertEquals(Structure.BEAM, beam.symbolName)
        val t = Building.foundationWallIn(s.building)
        assertEquals(168f - 2f * t + 2f * Wings.POCKET_IN, beam.size.width / inch, 0.6f)
        assertTrue((beam.properties[Structure.BEAM_SECTION] ?: -1f) >= 0f)
        // A crawl wing needs none: the house basement wall stays.
        assertNull(gen(built(wing(1)), "jbeam:w1"))
    }

    @Test
    fun `a wing in line with a hip end has one valley and the house hip stops at its ridge`() {
        val hips = RoofSettings(endA = com.example.sitedraft.ui.canvas.RoofEnd.HIP, endB = com.example.sitedraft.ui.canvas.RoofEnd.HIP)
        fun st(g: DrawingElement.Symbol) = DerivedLayers.sync(CanvasState(elements = house + g,
            building = BuildingSettings(autoLayers = true, foundation = FoundationType.BASEMENT, roof = hips)))
        // Flush with the east end (the wing runs to x = 480): one valley, the far end's two hips.
        val flush = wing(1)
        assertEquals(3, Wings.roof3d(flush, st(flush)).count { it.kind == RoofPlan.Kind.HIP })
        // Set in from the corner: valleys both sides.
        val inset = sym(Wings.NAME, Wings.CATEGORY, 240f, -240f, 408f, 0f, "w2", mapOf(Wings.FOUND to 1f))
        assertEquals(4, Wings.roof3d(inset, st(inset)).count { it.kind == RoofPlan.Kind.HIP })
        // The house roof: its northeast hip now stops at the wing's ridge (x = 396", half the wing in from the wall).
        val s = st(flush)
        val g = RoofPlan.geometry(s, hips)!!
        val trimmed = Wings.trimHouseRoof(g.lines, s)
        val neHip = trimmed.filter { it.kind == RoofPlan.Kind.HIP && maxOf(it.a.x, it.b.x) / inch > 340f && minOf(it.a.y, it.b.y) / inch < 143f }
        assertEquals(1, neHip.size)
        assertEquals(396f, maxOf(neHip[0].a.x, neHip[0].b.x) / inch, 0.6f)
        // No house eave left along the north wall over the wing.
        assertTrue(trimmed.none { it.kind == RoofPlan.Kind.EAVE && kotlin.math.abs(it.a.y - it.b.y) < 0.01f && it.a.y / inch < 0f &&
            minOf(it.a.x, it.b.x) / inch > 300f })
    }

    @Test
    fun `the house behind a wing is hidden inside its outline`() {
        val sq = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f))
        val parts = Wings.outside(Offset(-5f, 5f), Offset(15f, 5f), sq)
        assertEquals(2, parts.size)
        assertEquals(0f, parts[0].second.x, 0.6f); assertEquals(10f, parts[1].first.x, 0.6f)
        assertTrue(Wings.outside(Offset(2f, 2f), Offset(8f, 8f), sq).isEmpty())
        assertEquals(4, Wings.convexHull(listOf(0f to 0f, 10f to 0f, 10f to 10f, 0f to 10f, 5f to 5f)).size)
    }
}
