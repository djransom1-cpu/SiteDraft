package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BarnDoors
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Schedules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Barn doors: track length and what the open panel runs into (Dan, Nov 26-27). 1' = 50px. */
class BarnDoorsTest {
    private val inch = 50f / 12f

    private fun wall(id: String, l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(
        id = id, symbolName = FloorPlan.INTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch))

    /** A 36" barn door in the middle of an east-west wall (y 0 to 4 1/2"), hung on its north face, at x [cx]. */
    private fun barn(cx: Float, slidesRight: Boolean, name: String = "Door 3'-0\" Barn", w: Float = 36f) = DrawingElement.Symbol(
        id = "d", symbolName = name, category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
        properties = mapOf(PlanSymbols.PROP_HINGE_RIGHT to if (slidesRight) 1f else 0f),
        center = Offset(cx * inch, (2.25f - w / 2f) * inch), size = Size(w * inch, w * inch))

    private val host = wall("h", 0f, 0f, 144f, 4.5f)

    @Test
    fun `the track is twice the panel, and shows in the schedule`() {
        assertEquals(76f, BarnDoors.trackIn(36f), 0.01f)
        val s = CanvasState(elements = listOf(host, barn(60f, true)))
        assertEquals("Track 6'-4\"", BarnDoors.trackText(barn(60f, true), s))
        val row = Schedules.rows(s, Schedules.Kind.DOOR).single()
        assertTrue(row.operation, row.operation.contains("track 6'-4\""))
    }

    @Test
    fun `a clear slide has no warnings`() {
        val s = CanvasState(elements = listOf(host, barn(40f, true)))
        assertEquals(emptyList<String>(), BarnDoors.warnings(barn(40f, true), s))
    }

    @Test
    fun `it warns about a wall on its face, the wall's end, a window and a switch`() {
        // Slides east from 59" to 97": a wall coming off the north face at 80".
        val tee = wall("t", 80f, -60f, 84.5f, 0f)
        var s = CanvasState(elements = listOf(host, tee, barn(40f, true)))
        assertTrue(BarnDoors.warnings(barn(40f, true), s).any { it.contains("into a wall") })
        // The same wall on the other face doesn't matter.
        s = CanvasState(elements = listOf(host, wall("t", 80f, 4.5f, 84.5f, 60f), barn(40f, true)))
        assertEquals(emptyList<String>(), BarnDoors.warnings(barn(40f, true), s))
        // Sliding west from 21" to -17": past the end of the wall.
        s = CanvasState(elements = listOf(host, barn(40f, false)))
        assertTrue(BarnDoors.warnings(barn(40f, false), s).any { it.contains("past the end") })
        // A switch on the north face at 75".
        val sw = DrawingElement.Symbol(id = "sw", symbolName = "Switch", category = "Electrical", color = Color.White,
            center = Offset(75f * inch, -1f * inch), size = Size(4f * inch, 4f * inch))
        s = CanvasState(elements = listOf(host, sw, barn(40f, true)))
        assertTrue(BarnDoors.warnings(barn(40f, true), s).any { it.contains("switch") })
    }
}
