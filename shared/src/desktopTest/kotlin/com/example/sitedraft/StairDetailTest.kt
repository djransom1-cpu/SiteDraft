package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DetailKit
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.StairDetail
import com.example.sitedraft.ui.canvas.Stairs
import com.example.sitedraft.ui.canvas.inchesToPx
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stair section detail (Dan, Nov 24 2026). */
class StairDetailTest {
    @Test
    fun `the stair detail follows the stair on the plan`() {
        val st0 = CanvasState(building = BuildingSettings(autoLayers = true))
        val inch = st0.inchesToPx(1f)
        val stair = DrawingElement.Symbol(id = "st", symbolName = Stairs.STRAIGHT, category = PlanSymbols.STAIRS, center = Offset(500f, 400f),
            size = Size(36f * inch, 120f * inch), color = Color.White, properties = Stairs.defaults(Stairs.STRAIGHT) + (Stairs.GOES to 2f),
            layerId = "floor1-plan")
        val st = Stairs.sync(st0.copy(elements = listOf(stair)))
        val s = st.elements.first() as DrawingElement.Symbol
        val l = Stairs.layout(s)
        val g = DetailKit.geometry(st, DetailKit.Detail.STAIR)
        assertTrue(g.parts.size > l.risers * 2)
        assertTrue(g.notes.any { it.text.startsWith("${l.risers} RISERS") })
        assertTrue(g.notes.any { it.text.startsWith("${l.treads} TREADS") })
        assertTrue(g.notes.any { it.text.contains("CONCRETE SLAB") })
        assertTrue(g.extent.width > 0f && g.extent.height > 0f)
        // No stair on the plan: still a detail, from a stair's numbers.
        assertTrue(StairDetail.geometry(st0).notes.any { it.text.contains("RISERS") })
    }
}
