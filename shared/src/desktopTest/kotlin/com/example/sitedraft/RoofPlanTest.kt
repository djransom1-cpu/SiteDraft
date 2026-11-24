package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.Building
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoofEnd
import com.example.sitedraft.ui.canvas.RoofFraming
import com.example.sitedraft.ui.canvas.RoofPlan
import com.example.sitedraft.ui.canvas.RoofSettings
import com.example.sitedraft.ui.canvas.levels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The roof framing plan (RoofPlan) and ProCalc's engines behind it. 1' = 50px. */
class RoofPlanTest {
    private val inch = 50f / 12f

    /** A 40' x 28' box of 2x6 exterior walls, outside faces on (0,0) to (480", 336"). */
    private fun box(): List<DrawingElement> {
        val t = 5.5f
        fun wall(id: String, cx: Float, cy: Float, w: Float, h: Float) = DrawingElement.Symbol(id = id, symbolName = FloorPlan.EXTERIOR_WALL,
            category = PlanSymbols.WALLS, color = Color.White, center = Offset(cx * inch, cy * inch), size = Size(w * inch, h * inch))
        return listOf(
            wall("n", 240f, t / 2f, 480f, t), wall("s", 240f, 336f - t / 2f, 480f, t),
            wall("w", t / 2f, 168f, t, 336f), wall("e", 480f - t / 2f, 168f, t, 336f)
        )
    }

    @Test
    fun `no walls, no roof`() {
        assertNull(RoofPlan.geometry(CanvasState(), RoofSettings()))
    }

    @Test
    fun `the eave line is the overhang past the walls and the ridge runs the long way`() {
        val s = CanvasState(elements = box())
        val g = RoofPlan.geometry(s, RoofSettings(overhangIn = 12f, rakeIn = 6f))!!
        assertTrue(g.ridgeAcross)
        assertEquals(336f, g.spanIn, 0.1f)
        assertEquals(480f, g.lengthIn, 0.1f)
        assertEquals(-6f * inch, g.eave.left, 0.1f)   // rake at a gable end
        assertEquals(-12f * inch, g.eave.top, 0.1f)   // eave overhang
        // Gable both ends: the ridge runs out to the rakes.
        assertEquals(g.eave.left, g.ridgeStart.x, 0.1f)
        assertEquals(g.eave.right, g.ridgeEnd.x, 0.1f)
    }

    @Test
    fun `a hip end stops the ridge half the span in from the wall`() {
        val s = CanvasState(elements = box())
        val g = RoofPlan.geometry(s, RoofSettings(endA = RoofEnd.HIP))!!
        assertEquals(168f * inch, g.ridgeStart.x, 0.1f) // half of the 28' span
        assertEquals(2, g.lines.count { it.kind == RoofPlan.Kind.HIP }) // two hips at the one hip end
        val n = RoofPlan.numbers(s, RoofSettings(endA = RoofEnd.HIP, endB = RoofEnd.HIP))!!
        // ProCalc: each hip end takes half the span off the ridge: 40' - 14' - 14' = 12'.
        assertEquals(144.0, n.roof.correctedRidgeLen, 0.01)
        assertEquals(4, n.roof.totalHips)
    }

    @Test
    fun `numbers are ProCalc's - run, rise and rafter for a 6 pitch on a 28' span`() {
        val s = CanvasState(elements = box())
        val n = RoofPlan.numbers(s, RoofSettings(framing = RoofFraming.RAFTERS, pitch = 6f, ocIn = 16f, heelIn = 4f))!!
        assertEquals(167.25, n.rafter.run, 0.01)          // 336 / 2 - 1 1/2 / 2
        assertEquals(83.625, n.rafter.rise, 0.01)         // 6 in 12
        assertEquals(87.625, n.rafter.totRise, 0.01)      // plus the heel
        assertTrue(RoofPlan.summary(s, RoofSettings()).isNotEmpty())
    }

    @Test
    fun `the roof level sits the wall height above the top floor once there is a roof`() {
        val none = BuildingSettings()
        assertNull(none.let { s -> CanvasState(building = s).levels.first { it.id == Building.ROOF }.elevationIn })
        val roofed = BuildingSettings(roof = RoofSettings())
        val plate = CanvasState(building = roofed).levels.first { it.id == Building.ROOF }.elevationIn
        assertNotNull(plate)
        assertEquals(1200f + 97.125f, plate!!, 0.001f)
        // A taller ceiling moves it.
        assertEquals(1200f + 109.125f, CanvasState(building = roofed.copy(plateHeightIn = 109.125f)).levels.first { it.id == Building.ROOF }.elevationIn!!, 0.001f)
    }
}
