package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.FramingLayout
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoofFraming
import com.example.sitedraft.ui.canvas.RoofPlan
import com.example.sitedraft.ui.canvas.RoofSettings
import com.example.sitedraft.ui.canvas.UpperFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Upper-floor joists and roof trusses stack on the one house layout (Dan, Nov 24 2026). */
class LayoutStackTest {
    private val inch = 50f / 12f
    private fun wall(id: String, l: Float, t: Float, r: Float, b: Float, layer: String) = DrawingElement.Symbol(
        id = id, symbolName = FloorPlan.EXTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch), layerId = layer)
    private val house = listOf(
        wall("top", 0f, 0f, 480f, 6.5f, "floor1-plan"), wall("bottom", 0f, 281.5f, 480f, 288f, "floor1-plan"),
        wall("left", 0f, 6.5f, 6.5f, 281.5f, "floor1-plan"), wall("right", 473.5f, 6.5f, 480f, 281.5f, "floor1-plan"))
    /** A second floor that overhangs the west wall by 2'. */
    private val upper = wall("up-left", -24f, 0f, -17.5f, 288f, "floor2-plan")
    private val b = BuildingSettings(upperFloors = listOf(UpperFloor("Second floor", 1310f)), autoLayers = true)

    @Test
    fun `an overhanging upper floor doesn't move the layout`() {
        val s = CanvasState(elements = house + upper, building = b)
        val o = FramingLayout.origin(s)!!
        assertEquals(0f, o.x / inch, 0.01f)
        assertEquals(0f, o.y / inch, 0.01f)
    }

    @Test
    fun `24 inch trusses land on the house layout, every third mark`() {
        val s = CanvasState(elements = house, building = b.copy(roof = RoofSettings(framing = RoofFraming.TRUSSES, ocIn = 24f, pitch = 6f)))
        val g = RoofPlan.geometry(s, s.building.roof!!)!!
        val xs = g.lines.filter { it.kind == RoofPlan.Kind.MEMBER && abs(it.a.x - it.b.x) < 0.01f }.map { it.a.x / inch }.distinct()
        assertTrue(xs.size > 10)
        for (x in xs) assertTrue("truss at $x", abs(x / 24f - Math.round(x / 24f)) < 0.01f)
        // Every 48" a truss sits on a 16" stud mark.
        assertTrue(xs.count { abs(x16(it)) < 0.01f } >= 9)
    }
    private fun x16(x: Float) = x / 16f - Math.round(x / 16f)
}
