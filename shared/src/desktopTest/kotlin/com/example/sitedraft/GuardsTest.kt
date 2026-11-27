package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.Guards
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Stairs
import com.example.sitedraft.ui.canvas.UpperFloor
import com.example.sitedraft.ui.canvas.inchesToPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards around a stair's floor opening (Dan, Nov 24 2026). */
class GuardsTest {
    private fun stairOn(b: BuildingSettings, goes: Int, extra: List<DrawingElement> = emptyList()): Pair<DrawingElement.Symbol, CanvasState> {
        val st0 = CanvasState(building = b)
        val inch = st0.inchesToPx(1f)
        val stair = DrawingElement.Symbol(id = "st", symbolName = Stairs.STRAIGHT, category = PlanSymbols.STAIRS, center = Offset(500f, 400f),
            size = Size(36f * inch, 120f * inch), color = Color.White, properties = Stairs.defaults(Stairs.STRAIGHT) + (Stairs.GOES to goes.toFloat()),
            layerId = "floor1-plan")
        val synced = Stairs.sync(st0.copy(elements = listOf(stair) + extra))
        return (synced.elements.first() as DrawingElement.Symbol) to synced
    }

    @Test
    fun `stair rise is finish to finish, 3-4 inch on framed floors and 0 on the slab unless set`() {
        val b = BuildingSettings(autoLayers = true)
        val (down, st) = stairOn(b, goes = 2)
        val sub = com.example.sitedraft.ui.canvas.Building.floorElevationIn(b, 1)!! - com.example.sitedraft.ui.canvas.Building.basementFloorIn(b)
        // Hardwood upstairs, bare slab below: 3/4" more than subfloor to slab.
        assertEquals(sub + 0.75f, Stairs.autoRiseIn(down, st)!!, 0.01f)
        // Set by hand: 1/2" tile on the slab, 1" at the top.
        val set = down.copy(properties = down.properties + (Stairs.FIN_BOT to 0.5f) + (Stairs.FIN_TOP to 1f))
        assertEquals(sub + 0.5f, Stairs.autoRiseIn(set, st)!!, 0.01f)
        // Up between two framed floors: the finishes cancel.
        val two = BuildingSettings(upperFloors = listOf(UpperFloor("Second floor", 1310f)), autoLayers = true)
        val (up, st2) = stairOn(two, goes = 1)
        assertEquals(1310f - com.example.sitedraft.ui.canvas.Building.floorElevationIn(two, 1)!!, Stairs.autoRiseIn(up, st2)!!, 0.01f)
    }

    @Test
    fun `a stair down to the basement gets a guard on its open sides`() {
        val (s0, st) = stairOn(BuildingSettings(autoLayers = true), goes = 2)
        // Stairs come in bare now (Nov 27): give this one its own guard on the right, as the test was written for.
        val s = s0.copy(properties = s0.properties + (Stairs.RAIL_RIGHT to Stairs.RAIL_GUARD.toFloat()))
        val g = Guards.forStair(s, st)
        // The far end and the left side (no rail); the right side already has the stair's own guard.
        assertEquals(setOf("end", "left"), g.map { it.id.substringAfterLast(":") }.toSet())
        assertTrue(g.all { it.layerId == "floor1-plan" && it.category == PlanSymbols.RAILINGS })
        assertEquals(36f, g.first().properties[Guards.HEIGHT]!!, 0.01f)
        // Only one carries the note.
        assertEquals(1, g.count { (it.properties[Guards.NOTE] ?: 0f) >= 0.5f })
        // Turned off on the stair: none.
        assertTrue(Guards.forStair(s.copy(properties = s.properties + (Stairs.GUARD_AUTO to 0f)), st).isEmpty())
    }

    @Test
    fun `a stair up to a second floor is guarded on the floor above, and not where a wall runs`() {
        val two = BuildingSettings(upperFloors = listOf(UpperFloor("Second floor", 1310f)), autoLayers = true)
        val (s, st) = stairOn(two, goes = 1)
        val g = Guards.forStair(s, st)
        assertEquals(setOf("end", "left", "right"), g.map { it.id.substringAfterLast(":") }.toSet())
        assertTrue(g.all { it.layerId == "floor2-plan" })
        // A wall on the second floor along the stair's left side: no guard there.
        val inch = st.inchesToPx(1f)
        val left = s.center.x - s.size.width / 2f
        val wall = DrawingElement.Symbol(id = "w", symbolName = FloorPlan.INTERIOR_WALL, category = PlanSymbols.WALLS,
            center = Offset(left - 2.25f * inch, s.center.y), size = Size(4.5f * inch, s.size.height), color = Color.White, layerId = "floor2-plan")
        val (s2, st2) = stairOn(two, goes = 1, extra = listOf(wall))
        assertEquals(setOf("end", "right"), Guards.forStair(s2, st2).map { it.id.substringAfterLast(":") }.toSet())
    }

    @Test
    fun postsAtMost8FeetEqualSpaces() {
        assertEquals(1, Guards.postSpaces(48f))
        assertEquals(1, Guards.postSpaces(96f))
        assertEquals(2, Guards.postSpaces(97f))
        assertEquals(3, Guards.postSpaces(240f))
        val xs = Guards.postXs(240f, 1f, 1.75f)
        assertEquals(4, xs.size)
        val gaps = xs.zipWithNext { a, b -> b - a }
        gaps.forEach { assertEquals(gaps[0], it, 0.01f) }
    }

    @Test
    fun `no end guard when a wall runs within a foot of the opening's far edge`() {
        val two = BuildingSettings(upperFloors = listOf(UpperFloor("Second floor", 1310f)), autoLayers = true)
        val (s, st) = stairOn(two, goes = 1)
        val inch = st.inchesToPx(1f)
        val far = s.center.y - s.size.height / 2f + Stairs.floorOpening(s, st)!!.size.height
        // A wall across the far end, 10" past the opening's edge.
        val wall = DrawingElement.Symbol(id = "w", symbolName = FloorPlan.INTERIOR_WALL, category = PlanSymbols.WALLS,
            center = Offset(s.center.x, far + 10f * inch + 2.25f * inch), size = Size(80f * inch, 4.5f * inch), color = Color.White, layerId = "floor2-plan")
        val (s2, st2) = stairOn(two, goes = 1, extra = listOf(wall))
        assertEquals(setOf("left", "right"), Guards.forStair(s2, st2).map { it.id.substringAfterLast(":") }.toSet())
        // 2' away: the end is still open, guarded.
        val away = wall.copy(center = wall.center + Offset(0f, 14f * inch))
        val (s3, st3) = stairOn(two, goes = 1, extra = listOf(away))
        assertTrue(Guards.forStair(s3, st3).any { it.id.endsWith(":end") })
    }
}
