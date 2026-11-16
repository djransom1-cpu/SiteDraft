package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.Appliances
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FillPattern
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.TileTakeoff
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1' = 50px, so 1" = 4.1667px. */
class AppliancesTest {
    private val inch = 50f / 12f
    private val t = 4.5f * inch
    private val face = t / 2f
    private val wall = DrawingElement.Symbol(symbolName = "2x4 Wall", category = PlanSymbols.WALLS, center = Offset(500f, 0f), size = Size(1000f, t), color = Color.White)

    /** An item against the wall's underside, its left edge at [xIn] inches from the wall's left end. */
    private fun at(name: String, cat: String, xIn: Float): DrawingElement.Symbol {
        val (w, d) = PlanSymbols.footprintInches.getValue(name)
        return DrawingElement.Symbol(symbolName = name, category = cat, color = Color.White,
            center = Offset((xIn + w / 2f) * inch, face + d / 2f * inch), size = Size(w * inch, d * inch))
    }

    @Test
    fun `a backsplash runs over the base cabinets and range, and stops at the refrigerator`() {
        val base1 = at("Base 36\"", PlanSymbols.CABINETS, 24f)
        val range = at(Appliances.RANGE_30, PlanSymbols.APPLIANCES, 60f)
        val base2 = at("Base 24\"", PlanSymbols.CABINETS, 90f)
        val fridge = at(Appliances.FRIDGE_36, PlanSymbols.APPLIANCES, 114f)
        val state = CanvasState(elements = listOf(wall, base1, range, base2, fridge))
        val spec = ElevationSpec(wallId = wall.id, side = ViewerSide.BELOW, tileFromIn = 36f, tileToIn = 54f, tileExtent = 1)
        val spans = Elevation.tileSpans(wall, spec, state)
        assertEquals(1, spans.size)
        assertEquals(24f * inch, spans[0].first, 0.01f)
        assertEquals(114f * inch, spans[0].second, 0.01f)
        // The whole wall when set to.
        assertEquals(1000f, Elevation.tileSpans(wall, spec.copy(tileExtent = 0), state).single().second, 0.01f)
        // The tile takeoff counts the same: 90" x 18" less the range's 6" backguard over 30".
        val view = DrawingElement.ElevationView(spec = spec, origin = Offset(0f, 2000f), size = Elevation.sizePx(wall, spec, state), color = Color.White)
        val row = TileTakeoff.rows(CanvasState(elements = state.elements + view)).single { it.where == "Wall" }
        assertEquals((90f * 18f - 30f * 6f) / 144f, row.netSqFt, 0.05f)
    }

    @Test
    fun `every appliance and hood draws in plan and elevation`() {
        val bare = Elevation.build(wall, ElevationSpec(wall.id, ViewerSide.BELOW), Offset(0f, 2000f), CanvasState(elements = listOf(wall))).size
        for (name in Appliances.appliances + Appliances.hoods) {
            val cat = if (name in Appliances.hoods) PlanSymbols.HOODS else PlanSymbols.APPLIANCES
            val s = at(name, cat, 40f)
            assertTrue(name, Appliances.planParts(s, s.size.width, s.size.height, inch).isNotEmpty())
            // Island items stand off the wall, so wall elevations leave them out by design.
            if (name.startsWith("Island")) continue
            val state = CanvasState(elements = listOf(wall, s))
            val parts = Elevation.build(wall, ElevationSpec(wall.id, ViewerSide.BELOW), Offset(0f, 2000f), state)
            // Something of its own is drawn on the wall (a cooktop is only its edge, plus grates for gas).
            assertTrue(name, parts.size > bare)
        }
        for (style in 0..2) {
            val hood = at("Wood Hood 42\"", PlanSymbols.HOODS, 40f).copy(properties = mapOf(Appliances.HOOD_STYLE to style.toFloat()))
            assertTrue(Elevation.build(wall, ElevationSpec(wall.id, ViewerSide.BELOW), Offset(0f, 2000f), CanvasState(elements = listOf(wall, hood))).size > 10)
        }
    }

    @Test
    fun `hoods and the over-range microwave are overlays, ranges are not`() {
        assertTrue(PlanSymbols.isOverlay(at("Chimney Hood 36\"", PlanSymbols.HOODS, 0f)))
        assertTrue(PlanSymbols.isOverlay(at(Appliances.OTR_MICRO, PlanSymbols.APPLIANCES, 0f)))
        assertTrue(!PlanSymbols.isOverlay(at(Appliances.RANGE_36, PlanSymbols.APPLIANCES, 0f)))
        assertTrue(FillPattern.TILE in TileTakeoff.tilePatterns)
    }
}
