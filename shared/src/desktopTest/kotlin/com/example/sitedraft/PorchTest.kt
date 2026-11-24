package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DerivedLayers
import com.example.sitedraft.ui.canvas.DetailKit
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Porches
import com.example.sitedraft.ui.canvas.PorchPostDetail
import com.example.sitedraft.ui.canvas.Structure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Porches and what holds them up (Nov 23 2026). */
class PorchTest {
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
    // An 8' x 5' porch off the bottom (south) wall.
    private fun porch(type: Int, pier: Int = 0) = sym(Porches.NAME, Porches.CATEGORY, 120f, 288f, 216f, 348f, "porch1",
        Porches.defaults() + mapOf(Porches.TYPE to type.toFloat(), Porches.PIER to pier.toFloat()))
    private fun built(p: DrawingElement.Symbol) = DerivedLayers.sync(CanvasState(elements = house + p, building = BuildingSettings(autoLayers = true)))
    private fun gen(s: CanvasState, key: String) = s.elements.single { it.id == DerivedLayers.GEN_PREFIX + "pc:porch1:$key" } as DrawingElement.Symbol

    @Test
    fun `a framed porch gets a continuous footing at the basement footing, butting the house footing`() {
        val s = built(porch(0))
        val out = gen(s, "f-out")
        // 96" + 2 x 3 3/16" past the 12" block, 18" wide, outside edge 3 3/16" past the porch's 60".
        assertEquals(102.375f, out.size.width / inch, 0.01f)
        assertEquals(18f, out.size.height / inch, 0.01f)
        assertEquals(288f + 63.1875f, (out.center.y + out.size.height / 2f) / inch, 0.01f)
        assertEquals((Structure.JOIN_OWN * 4).toFloat(), Structure.prop(out, Structure.JOIN), 0.01f)
        // The sides run from the house footing's outside edge to the outside footing: 42".
        val a = gen(s, "f-a")
        assertEquals(42f, a.size.height / inch, 0.01f)
        assertEquals(288f + 3.1875f, (a.center.y - a.size.height / 2f) / inch, 0.01f)
        assertEquals("12\" Block Wall", gen(s, "w-out").symbolName)
    }

    @Test
    fun `a masonry porch is 12 inch block`() {
        assertEquals("12\" Block Wall", gen(built(porch(1)), "w-a").symbolName)
    }

    @Test
    fun `a wood deck porch gets posts on piers or cookies to frost depth`() {
        val piers = built(porch(2, 0))
        assertEquals(Structure.PIER, gen(piers, "pier0").symbolName)
        assertEquals(Structure.PIER, gen(piers, "pier1").symbolName)
        assertTrue(piers.elements.none { it.id.contains("pc:porch1:f-") })
        val cookies = built(porch(2, 1))
        // 90" between posts, 5' deep, with the roof: 95 psf on 18.75 sq ft on 2,000 psf soil -> 12", 16" the least.
        assertEquals(16f, gen(cookies, "ck0").size.width / inch, 0.01f)
        val st = CanvasState(elements = house + porch(2, 1))
        assertTrue(PorchPostDetail.geometry(st).notes.any { it.text.startsWith("16\" x 16\" x 8\" CONCRETE COOKIE") })
        val pierDetail = DetailKit.geometry(CanvasState(elements = house + porch(2, 0)), DetailKit.Detail.PORCH_POST)
        assertTrue(pierDetail.notes.any { it.text.startsWith("12\" DIA. CONCRETE PIER POURED TO GRADE") })
    }

    @Test
    fun `a roofed porch gets columns no more than 8 feet apart`() {
        // 96" porch, inset at the corners: 2 columns.
        assertEquals(2, Porches.columns(porch(0), androidx.compose.ui.geometry.Rect(0f, 0f, 480f * inch, 288f * inch), inch).size)
    }

    @Test
    fun `porch roofs - shed, gable and hip`() {
        fun roof(style: Int) = Porches.roof(porch(0).copy(properties = porch(0).properties + (Porches.ROOF_STYLE to style.toFloat())),
            CanvasState(elements = house + porch(0)))!!
        val shed = roof(0)
        assertTrue(shed.lines.none { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.RIDGE })
        assertTrue(shed.shedArrow != null)
        val gable = roof(1)
        assertEquals(1, gable.lines.count { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.RIDGE })
        assertTrue(gable.lines.none { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.HIP })
        val hip = roof(2)
        assertEquals(2, hip.lines.count { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.HIP })
        // The eave runs 12" past the porch on its three open sides: 96 + 24 = 120" wide.
        val eaveOut = hip.lines.filter { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.EAVE }.maxBy { kotlin.math.abs(it.a.x - it.b.x) }
        assertEquals(120f, kotlin.math.abs(eaveOut.a.x - eaveOut.b.x) / inch, 0.01f)
        // No roof, no drawing.
        assertTrue(Porches.roof(porch(0).copy(properties = porch(0).properties + (Porches.ROOF to 0f)), CanvasState(elements = house + porch(0))) == null)
        // A shed is 4/12 unless set; a gable or hip follows the house (6/12 without a roof).
        assertEquals(4f, Porches.pitchOf(porch(0), BuildingSettings()), 0.01f)
    }

    @Test
    fun `the porch deck sits 4 inches below the floor`() {
        assertEquals(1196f, Porches.deckTopIn(BuildingSettings()), 0.01f)   // 99'-8"
    }

    @Test
    fun `round 2 - porch floor framing and the porch on the elevations`() {
        // Framed: 2x8 joists at 16" o.c. inside the porch walls, running out from the house (up and down off a south wall).
        val s = built(porch(0))
        val bay = s.elements.single { it.id == DerivedLayers.GEN_PREFIX + "pcf:porch1:joists" } as DrawingElement.Symbol
        assertEquals(1, Structure.code(bay, Structure.DIRECTION))
        assertEquals("2x8", Structure.joistLabel(bay))
        // Masonry: a 4" cap instead.
        val m = built(porch(1))
        assertEquals(Structure.SLAB, (m.elements.single { it.id == DerivedLayers.GEN_PREFIX + "pcf:porch1:cap" } as DrawingElement.Symbol).symbolName)
        // The house's own joists stay the wall section's.
        assertEquals("2x10", com.example.sitedraft.ui.canvas.WallSection.joist(s).first)
        // Elevations: seen from the front on the south face, from the side on east and west, not from the north.
        val fp = androidx.compose.ui.geometry.Rect(0f, 0f, 480f * inch, 288f * inch)
        val st = CanvasState(elements = house + porch(0))
        val south = Porches.elevation(porch(0), st, com.example.sitedraft.ui.canvas.ViewerSide.BELOW, fp, inch)
        assertTrue(south.first.isNotEmpty())
        assertTrue(south.second.any { it.text.startsWith("PORCH SHED ROOF") })
        assertTrue(south.second.any { it.text == "SKIRT TO MATCH HOUSE" })
        assertTrue(Porches.elevation(porch(0), st, com.example.sitedraft.ui.canvas.ViewerSide.RIGHT, fp, inch).first.isNotEmpty())
        assertTrue(Porches.elevation(porch(0), st, com.example.sitedraft.ui.canvas.ViewerSide.ABOVE, fp, inch).first.isEmpty())
        // The south elevation itself picks it up.
        val g = com.example.sitedraft.ui.canvas.ExteriorElevation.geometry(st, com.example.sitedraft.ui.canvas.ExteriorElevation.Face.SOUTH)!!
        assertTrue(g.marks.any { it.name == "PORCH DECK" })
    }

    @Test
    fun `the porch roof lays onto the house roof when the house eave faces it`() {
        // House 40' x 24', ridge east-west (6/12, 12" overhang, 4" heel): the south eave faces the porch.
        val roofed = CanvasState(elements = house + porch(0), building = BuildingSettings(roof = com.example.sitedraft.ui.canvas.RoofSettings()))
        // 4/12 shed, fascia top 1297 1/8" (plate + 4" heel - 12" x 4/12) at 72" out: meets the 6/12 house roof 120" up from the wall.
        val hr = Porches.houseRoofFacing(porch(0), roofed, androidx.compose.ui.geometry.Rect(0f, 0f, 480f * inch, 288f * inch))!!
        assertEquals(1297.125f, Porches.eaveTopIn(porch(0), roofed.building), 0.01f)
        assertEquals(-120f, Porches.meetV(hr, 1297.125f, 72f, 4f / 12f), 0.05f)
        val d = Porches.roof(porch(0), roofed)!!
        val tie = d.lines.single { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.HIP }
        assertEquals(288f - 120f, tie.a.y / inch, 0.1f)
        // A gable porch at the house's 6/12 has its fascia exactly at the house's: plate + 4 - 12 x 6/12.
        val gp = porch(0).copy(properties = porch(0).properties + (Porches.ROOF_STYLE to 1f))
        assertEquals(1297.125f + 4f - 6f, Porches.eaveTopIn(gp, roofed.building), 0.01f)
        val east = Porches.elevation(porch(0), roofed, com.example.sitedraft.ui.canvas.ViewerSide.RIGHT,
            androidx.compose.ui.geometry.Rect(0f, 0f, 480f * inch, 288f * inch), inch)
        assertTrue(east.second.any { it.text == "PORCH ROOF LAPS ONTO HOUSE ROOF" })
        // A gable porch gets valleys where it meets the house roof.
        val gable = porch(0).copy(properties = porch(0).properties + (Porches.ROOF_STYLE to 1f))
        val g = Porches.roof(gable, roofed.copy(elements = house + gable))!!
        assertEquals(2, g.lines.count { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.HIP })
        // No house roof: it ties into the wall as before.
        assertTrue(Porches.roof(porch(0), CanvasState(elements = house + porch(0)))!!.lines.none { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.HIP })
    }
}
