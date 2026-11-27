package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.ExteriorElevation
import com.example.sitedraft.ui.canvas.HouseOutline
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoofPlan
import com.example.sitedraft.ui.canvas.RoofSettings
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.Wings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An old house drawn room by room in 2x4 walls (Dan, Nov 26 2026, the Ransom House): its outside walls
 * are found from the plan, and a one-story wing on the plan's own walls stands for the part off the back.
 * A 20' x 16' main house (0,0)-(240",192") with a partition, and a 10' x 12' room off its east side.
 */
class HouseOutlineTest {
    private val inch = 50f / 12f
    private fun sym(name: String, cat: String, l: Float, t: Float, r: Float, b: Float, id: String, props: Map<String, Float> = emptyMap()) =
        DrawingElement.Symbol(id = id, symbolName = name, category = cat, color = Color.White,
            center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch), properties = props)
    private fun wall(l: Float, t: Float, r: Float, b: Float, id: String) = sym("2x4 Wall", PlanSymbols.WALLS, l, t, r, b, id)
    private val house = listOf(
        wall(0f, 0f, 240f, 4.5f, "n"), wall(0f, 187.5f, 240f, 192f, "s"),
        wall(0f, 4.5f, 4.5f, 187.5f, "w"), wall(235.5f, 4.5f, 240f, 187.5f, "e"),
        wall(120f, 4.5f, 124.5f, 187.5f, "partition"),
        // The back room: its walls butt the main house's east wall, a hair short of it at the south (as measured).
        wall(240f, 0f, 360f, 4.5f, "bn"), wall(240.4f, 139.5f, 360f, 144f, "bs"), wall(355.5f, 4.5f, 360f, 139.5f, "be")
    )
    private val window = DrawingElement.Symbol(id = "win", symbolName = "Window 36", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
        center = Offset(357.75f * inch, 72f * inch), size = Size(4.5f * inch, 30f * inch), rotation = 0f,
        properties = mapOf(PlanSymbols.PROP_SILL_IN to 20f, PlanSymbols.PROP_HEIGHT_IN to 50f))
    private val wing = sym(Wings.NAME, Wings.CATEGORY, 240f, 0f, 360f, 144f, "wg",
        Wings.defaults() + mapOf(Wings.WALLS to 1f, Wings.WALL_H to 85.5f, Wings.END to 1f))
    private fun state(withWing: Boolean = true) = CanvasState(elements = house + window + (if (withWing) listOf(wing) else emptyList()),
        building = BuildingSettings(roof = RoofSettings(pitch = 12f), gradeExposureIn = 20f, upperFloors = listOf(
            com.example.sitedraft.ui.canvas.UpperFloor(name = "Second Floor", elevationIn = 1317.25f))))

    @Test
    fun `outside walls are found whatever they were drawn as, and partitions aren't`() {
        val s = state()
        val ext = HouseOutline.of(s).exterior
        assertTrue(listOf("n", "s", "w", "bn", "bs", "be").all { it in ext })
        assertFalse("partition" in ext)
        // The main house's east wall is outside where the back room doesn't cover it.
        assertTrue("e" in ext)
        assertEquals(setOf(ViewerSide.RIGHT), HouseOutline.facing(s.elements.first { it.id == "be" } as DrawingElement.Symbol, s))
    }

    @Test
    fun `with no roof, elevations or wing an old house's plan is just a plan`() {
        val s = CanvasState(elements = house)
        assertNull(RoofPlan.footprint(s))
    }

    @Test
    fun `the main house leaves out the wing standing for the back room`() {
        val fp = RoofPlan.footprint(state())!!
        assertEquals(0f, fp.left / inch, 0.01f); assertEquals(240f, fp.right / inch, 0.01f)
        assertEquals(0f, fp.top / inch, 0.01f); assertEquals(192f, fp.bottom / inch, 0.01f)
        // Without the wing it's the whole outline.
        assertEquals(360f, RoofPlan.footprint(state(withWing = false))!!.right / inch, 0.01f)
    }

    @Test
    fun `a one story wing on a two story house has its own wall height and dies into the house wall`() {
        val s = state()
        assertEquals(1200f + 85.5f, Wings.plateIn(wing, s.building), 0.01f)
        val full = wing.copy(properties = wing.properties + (Wings.FULL to 1f))
        assertEquals(ExteriorElevation.plateIn(s.building), Wings.plateIn(full, s.building), 0.01f)
        assertTrue(Wings.planWalls(wing, RoofPlan.footprint(s), inch).isEmpty())
        assertNull(Wings.joint(wing, RoofPlan.footprint(s), inch))
    }

    @Test
    fun `the back room's window is drawn with the wing on the east elevation`() {
        val s = state()
        val east = ExteriorElevation.geometry(s, ExteriorElevation.Face.EAST)
        assertNotNull(east)
        val headY = -(1200f + 20f + 50f)
        assertTrue(east!!.segs.any { kotlin.math.abs(it.a.y - headY) < 0.01f && kotlin.math.abs(it.b.y - headY) < 0.01f })
        // ...and only once: not again as a house window.
        // Looking west, 72" down from the north wall of a house whose south face is 192" down: 120" from the left.
        val heads = east.segs.filter { kotlin.math.abs(it.a.y - headY) < 0.01f && kotlin.math.abs(it.b.y - headY) < 0.01f && kotlin.math.abs((it.a.x + it.b.x) / 2f - 120f) < 0.5f }
        assertEquals(1, heads.size)
        // The west elevation doesn't show it.
        val west = ExteriorElevation.geometry(s, ExteriorElevation.Face.WEST)!!
        assertTrue(west.segs.none { kotlin.math.abs(it.a.y - headY) < 0.01f && kotlin.math.abs(it.b.y - headY) < 0.01f })
    }

    /**
     * A flat on top (Dan, Nov 26): a 20' x 28' two-story main house, hip ends, 12/12, with a full-height 14' wide
     * front gable off its west side. The slopes stop at the gable's ridge height, where its valleys end.
     */
    @Test
    fun `a flat on top sits at the main gable's ridge and the hips run up to its corners`() {
        val t = 6.5f
        val main = listOf(
            sym("2x6 Wall", PlanSymbols.WALLS, 0f, 0f, 240f, t, "n"), sym("2x6 Wall", PlanSymbols.WALLS, 0f, 336f - t, 240f, 336f, "s"),
            sym("2x6 Wall", PlanSymbols.WALLS, 0f, t, t, 336f - t, "w"), sym("2x6 Wall", PlanSymbols.WALLS, 240f - t, t, 240f, 336f - t, "e"))
        val gable = sym(Wings.NAME, Wings.CATEGORY, -96f, 84f, 0f, 252f, "front", Wings.defaults() + mapOf(Wings.FULL to 1f, Wings.END to 1f))
        val roof = RoofSettings(framing = com.example.sitedraft.ui.canvas.RoofFraming.RAFTERS, pitch = 12f, heelIn = 4f, endA = com.example.sitedraft.ui.canvas.RoofEnd.HIP,
            endB = com.example.sitedraft.ui.canvas.RoofEnd.HIP, flatTop = true)
        val s = CanvasState(elements = main + gable, building = BuildingSettings(roof = roof, gradeExposureIn = 20f,
            upperFloors = listOf(com.example.sitedraft.ui.canvas.UpperFloor(name = "Second Floor", elevationIn = 1317.25f))))
        val plate = ExteriorElevation.plateIn(s.building)
        // The gable is 168" wide: its ridge 84" up at 12/12 from the plate plus the heel.
        val deck = RoofPlan.flatDeckIn(s, roof)!!
        assertEquals(plate + 4f + 84f, deck, 0.01f)
        assertEquals(84f, RoofPlan.deckInsetIn(s, roof)!!, 0.01f)
        // The main house's own ridge would be 120" up (20' span): the deck is below it.
        val g = RoofPlan.geometry(s, roof)!!
        val ridges = g.lines.filter { it.kind == RoofPlan.Kind.RIDGE }
        assertEquals(4, ridges.size)
        // The deck runs north-south 84" in from each wall: x 84..156 at 1' = 50px.
        assertTrue(ridges.all { l -> listOf(l.a, l.b).all { (kotlin.math.abs(it.x / inch - 84f) < 0.01f || kotlin.math.abs(it.x / inch - 156f) < 0.01f) } })
        // Elevation: marked as the flat roof, no ridge.
        val east = ExteriorElevation.geometry(s, ExteriorElevation.Face.EAST)!!
        assertEquals(deck, east.marks.first { it.name == "FLAT ROOF" }.elevationIn, 0.01f)
        assertTrue(east.marks.none { it.name == "RIDGE" })
        // Without the flat it peaks as before.
        assertNull(RoofPlan.flatDeckIn(s, roof.copy(flatTop = false)))
    }
}
