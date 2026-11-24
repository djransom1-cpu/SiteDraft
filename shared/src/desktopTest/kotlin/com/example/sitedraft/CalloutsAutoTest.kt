package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.Callouts
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DerivedLayers
import com.example.sitedraft.ui.canvas.DetailKit
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Stairs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section and detail markers placed for you (Dan, Nov 24 2026). */
class CalloutsAutoTest {
    private val inch = 50f / 12f
    private fun wall(id: String, l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(
        id = id, symbolName = FloorPlan.EXTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch), layerId = "floor1-plan")
    private val house = listOf(
        wall("top", 0f, 0f, 480f, 6.5f), wall("bottom", 0f, 281.5f, 480f, 288f),
        wall("left", 0f, 6.5f, 6.5f, 281.5f), wall("right", 473.5f, 6.5f, 480f, 281.5f))
    private val stair = DrawingElement.Symbol(id = "st", symbolName = Stairs.STRAIGHT, category = PlanSymbols.STAIRS,
        center = Offset(240f * inch, 140f * inch), size = Size(36f * inch, 120f * inch), color = Color.White,
        properties = Stairs.defaults(Stairs.STRAIGHT), layerId = "floor1-plan")
    private fun co(s: CanvasState, key: String) = s.elements.firstOrNull { it.id == DerivedLayers.GEN_PREFIX + "co:" + key } as DrawingElement.Symbol?

    @Test
    fun `markers go on the plan once their drawings are in the model`() {
        val base = CanvasState(elements = house + stair, building = BuildingSettings(autoLayers = true))
        val none = DerivedLayers.sync(base)
        assertNull(co(none, "wall"))
        assertNull(co(none, "stair"))
        val wallDetail = DetailKit.newSymbol(base, DetailKit.Detail.HOUSE_WALL, Offset(5000f, 0f))
        val stairDetail = DetailKit.newSymbol(base, DetailKit.Detail.STAIR, Offset(9000f, 0f))
        val s = DerivedLayers.sync(base.copy(elements = base.elements + wallDetail + stairDetail))
        val cut = co(s, "wall")
        assertNotNull(cut)
        assertEquals(Callouts.SECTION, cut!!.symbolName)
        assertEquals(listOf(Callouts.Target.HOUSE_WALL), Callouts.targets(cut))
        // Through the south wall, square to it.
        assertEquals(284.75f, cut.center.y / inch, 0.6f)
        assertEquals(90f, cut.rotation, 0.01f)
        val st = co(s, "stair")
        assertNotNull(st)
        assertEquals(Callouts.DETAIL, st!!.symbolName)
        assertEquals(listOf(Callouts.Target.STAIR), Callouts.targets(st))
        // The leader starts at the stair.
        val synced = s.elements.first { it.id == "st" } as DrawingElement.Symbol
        assertEquals(synced.center.x / inch, (st.center.x - st.size.width / 2f) / inch, 0.6f)
        assertEquals(synced.center.y / inch, st.center.y / inch, 0.6f)
        assertTrue(st.layerId == "floor1-plan")
    }
}
