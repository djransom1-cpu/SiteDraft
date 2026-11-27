package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.ElectricalCode
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.Mep
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.inchesToPx
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Electrical to code on Practice House's real first floor (Dan's check, Nov 27 11:48): the errors he and Claude found
 * on the plan, each held to here so they stay fixed.
 */
class ElectricalPracticeHouseTest {
    private val st: CanvasState = Json { ignoreUnknownKeys = true }
        .decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/practice_house.json").readText())
    private val inch = st.inchesToPx(1f)
    private val out = ElectricalCode.apply(st)
    private val e = out.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == Mep.ELECTRICAL }
    private fun rectIn(l: Float, t: Float, r: Float, b: Float) = Rect(l * inch, t * inch, r * inch, b * inch)
    private fun named(n: String) = e.filter { it.symbolName == n }
    private fun at(s: DrawingElement.Symbol) = s.center / inch

    private val living = rectIn(-13f, 191f, 445f, 353.5f)
    private val masterBath = rectIn(243.5f, 6.5f, 344.5f, 102.5f)
    private val kitchen = rectIn(-113.5f, 6.5f, 66.5f, 186.5f)

    @Test
    fun `the living room is its own room with plugs, not the laundry`() {
        val plugs = named(Mep.DUPLEX).filter { living.inflate(inch).contains(it.center) }
        assertTrue("living room plugs: ${plugs.size}", plugs.size >= 4)
    }

    @Test
    fun `only the front door is an outside door`() {
        assertEquals("exterior lights at ${named(Mep.EXTERIOR_LIGHT).map(::at)}", 1, named(Mep.EXTERIOR_LIGHT).size)
        assertEquals("weatherproof plugs front and back: ${named(Mep.WP).map(::at)}", 2, named(Mep.WP).size)
    }

    @Test
    fun `the unlabeled master bath is a bath`() {
        val shower = st.elements.filterIsInstance<DrawingElement.Symbol>().first { it.category == PlanSymbols.SHOWERS }
        val sb = FloorPlan.bounds(shower).inflate(2f * inch)
        assertTrue("plug in the shower", e.none { Mep.isOutlet(it.symbolName) && sb.contains(it.center) })
        assertTrue("bath fan", named(Mep.BATH_FAN).any { masterBath.contains(it.center) })
        assertTrue("no smoke/CO in a bath or kitchen", (named(Mep.SMOKE_CO) + named(Mep.SMOKE)).none { masterBath.contains(it.center) || kitchen.contains(it.center) })
    }

    @Test
    fun `the master bedroom's smoke alarm is by its hall door`() {
        val bed = rectIn(71f, 6.5f, 239f, 186.5f)
        val sd = named(Mep.SMOKE).single { bed.contains(it.center) }
        val hall = Offset(239f, 167f); val bath = Offset(241f, 60f)
        assertTrue("smoke at ${at(sd)}", (at(sd) - hall).getDistance() < (at(sd) - bath).getDistance())
    }

    @Test
    fun `the kitchen switch is at the opening to the living room`() {
        val sw = e.filter { Mep.isSwitch(it.symbolName) && kitchen.inflate(inch).contains(it.center) }
        assertTrue("kitchen switches at ${sw.map(::at)}", sw.any { at(it).y > 170f && kotlin.math.abs(at(it).x - (-23.5f)) < 48f })
    }

    @Test
    fun `smoke and CO outside the bedrooms`() {
        assertTrue("smoke/CO at ${named(Mep.SMOKE_CO).map(::at)}", named(Mep.SMOKE_CO).isNotEmpty())
    }

    @Test
    fun `no can over the island, and the stair has a light`() {
        val island = st.elements.filterIsInstance<DrawingElement.Symbol>().first { PlanSymbols.isIsland(it) }
        assertTrue(named(Mep.CAN_6).none { FloorPlan.bounds(island).contains(it.center) })
        assertTrue(named(Mep.PENDANT).size >= 2)
        val stair = st.elements.filterIsInstance<DrawingElement.Symbol>().first { it.category == PlanSymbols.STAIRS }
        assertTrue("stair light", named(Mep.CEILING_LIGHT).any { FloorPlan.bounds(stair).contains(it.center) })
    }
}
