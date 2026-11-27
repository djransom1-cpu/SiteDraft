package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.Mep
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MEP library (Dan, Nov 27). 1' = 50px. */
class MepTest {
    private val inch = 50f / 12f

    private fun sym(name: String, cat: String, xIn: Float, yIn: Float) = PlanSymbols.footprintInches.getValue(name).let { (w, d) ->
        DrawingElement.Symbol(symbolName = name, category = cat, color = Color.White, center = Offset(xIn * inch, yIn * inch), size = Size(w * inch, d * inch))
    }

    @Test
    fun `every electrical and HVAC symbol has a size and draws`() {
        for (n in Mep.electrical) assertTrue(n, Mep.planParts(sym(n, Mep.ELECTRICAL, 0f, 0f), 40f, 40f, inch).isNotEmpty())
        for (n in Mep.hvac) assertTrue(n, Mep.planParts(sym(n, Mep.HVAC, 0f, 0f), 40f, 40f, inch).isNotEmpty())
        // Every library entry is in exactly one page group.
        assertEquals(Mep.electrical.toSet(), Mep.electricalGroups.flatMap { it.sizes }.toSet())
        assertEquals(Mep.hvac.toSet(), Mep.hvacGroups.flatMap { it.sizes }.toSet())
        assertEquals(8f to 16f, Mep.trunkSize("Duct Trunk 8x16"))
    }

    @Test
    fun `Dan's heights to the bottom of the box`() {
        assertEquals(16f, Mep.defaultMountIn(Mep.DUPLEX), 0f)
        assertEquals(16f, Mep.defaultMountIn(Mep.GFCI), 0f)
        assertEquals(44f, Mep.defaultMountIn(Mep.SWITCH_3), 0f)
        assertEquals(40f, Mep.defaultMountIn(Mep.COUNTER), 0f)
    }

    @Test
    fun `a plug and a switch on a wall show on its elevation, a ceiling light doesn't`() {
        // An east-west wall, faces at y 100" and 104.5"; devices just south of it.
        val wall = DrawingElement.Symbol(id = "w", symbolName = FloorPlan.INTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
            center = Offset(60f * inch, 102.25f * inch), size = Size(120f * inch, 4.5f * inch))
        val plug = sym(Mep.DUPLEX, Mep.ELECTRICAL, 30f, 108.5f)
        val sw = sym(Mep.SWITCH, Mep.ELECTRICAL, 90f, 108.5f)
        val light = sym(Mep.CEILING_LIGHT, Mep.ELECTRICAL, 60f, 140f)
        val st = CanvasState(elements = listOf(wall, plug, sw, light))
        val items = Elevation.itemsOnWall(wall, ViewerSide.BELOW, st)
        assertEquals(setOf(plug.id, sw.id), items.filter { it.kind == Elevation.Kind.DEVICE }.map { it.symbol.id }.toSet())
        // And the north face sees neither.
        assertTrue(Elevation.itemsOnWall(wall, ViewerSide.ABOVE, st).none { it.kind == Elevation.Kind.DEVICE })
        // The elevation builds with them on it.
        val spec = ElevationSpec(wallId = "w", side = ViewerSide.BELOW)
        val v = DrawingElement.ElevationView(spec = spec, origin = Offset(0f, 2000f), size = Elevation.sizePx(wall, spec, st), color = Color.White)
        val bare = CanvasState(elements = listOf(wall, light))
        val vBare = v.copy(size = Elevation.sizePx(wall, spec, bare))
        // The plug's plate and two receptacles, the switch's plate and toggle.
        assertEquals(5, Elevation.partsOf(v, st).size - Elevation.partsOf(vBare, bare).size)
    }

    @Test
    fun `return grilles line up with the 16 inch layout`() {
        // Exterior walls from (0,0): the layout's marks at 0, 16, 32... inches.
        val ext = DrawingElement.Symbol(symbolName = FloorPlan.EXTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
            center = Offset(120f * inch, 3.25f * inch), size = Size(240f * inch, 6.5f * inch))
        val st = CanvasState(elements = listOf(ext))
        // One bay: centred between marks (40" is nearest 40, the middle of 32-48).
        val one = Mep.alignToFraming(sym(Mep.RETURN_6X14, Mep.HVAC, 37f, 10f), st)
        assertEquals(40f, one.center.x / inch, 0.01f)
        // Two bays: centred on the stud between them.
        val two = Mep.alignToFraming(sym(Mep.RETURN_6X30, Mep.HVAC, 37f, 10f), st)
        assertEquals(32f, two.center.x / inch, 0.01f)
        // Anything else stays where it is.
        assertEquals(37f, Mep.alignToFraming(sym(Mep.REG_4X10, Mep.HVAC, 37f, 10f), st).center.x / inch, 0.01f)
    }

    @Test
    fun `the cover legend lists the electrical and HVAC symbols the drawing uses`() {
        val st = CanvasState(elements = listOf(sym(Mep.GFCI, Mep.ELECTRICAL, 0f, 0f), sym(Mep.FAN_52, Mep.ELECTRICAL, 50f, 50f), sym(Mep.REG_4X10, Mep.HVAC, 90f, 90f)))
        val rows = com.example.sitedraft.ui.canvas.CoverSheet.mepLegend(st)
        assertEquals(listOf(Mep.GFCI, Mep.FAN_52, Mep.REG_4X10), rows.map { it.mep })
        assertTrue(rows[0].text, rows[0].text.contains("16\" A.F.F."))
    }

    @Test
    fun `duct sizing follows the friction chart`() {
        // 100 CFM at 0.10" per 100 ft needs about a 6" round; a 6" carries about 100-110 CFM.
        assertTrue(Mep.roundFor(100f) in 5.6f..6.0f)
        assertTrue(Mep.cfmFor(6f) in 100f..115f)
        // An 8x16 trunk is about a 12" round.
        assertEquals(12.2f, Mep.equivalentRound(8f, 16f), 0.1f)
        // 200 CFM on a round run: 8"; on a trunk: the smallest that's big enough.
        assertEquals("Round Duct 8\"", Mep.sizeFor("Round Duct 6\"", 200f))
        assertEquals("Duct Trunk 8x12", Mep.sizeFor("Duct Trunk 8x16", 300f))
        assertEquals(1.5f, Mep.pipeSizeIn("Drain 1-1/2\"")!!, 0.001f)
        assertEquals(0.75f, Mep.pipeSizeIn("Cold Water 3/4\"")!!, 0.001f)
    }

    @Test
    fun `MEP symbols go on their own layers, with sheets of their own`() {
        val st = CanvasState(elements = emptyList())
        assertEquals("floor1-electrical", com.example.sitedraft.ui.canvas.Layers.layerForNew(sym(Mep.DUPLEX, Mep.ELECTRICAL, 0f, 0f), st))
        assertEquals("floor1-mechanical", com.example.sitedraft.ui.canvas.Layers.layerForNew(sym(Mep.REG_4X10, Mep.HVAC, 0f, 0f), st))
        assertEquals("floor1-plumbing", com.example.sitedraft.ui.canvas.Layers.layerForNew(sym(Mep.CLEANOUT, Mep.PLUMBING, 0f, 0f), st))
        val choices = com.example.sitedraft.ui.canvas.Sheets.planChoices(st).toMap()
        val elec = choices.entries.first { it.key.endsWith("electrical plan") }.value!!
        assertTrue(elec.contains("floor1-plan") && elec.contains("floor1-electrical"))
        // The floor plan itself leaves them out.
        val plan = choices.entries.first { it.key.endsWith("floor plan") || it.key == "First floor plan" }.value!!
        assertTrue(plan.none { it.endsWith("-electrical") || it.endsWith("-mechanical") || it.endsWith("-plumbing") })
    }
}
