package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoomElevations
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevations by room, and any length of a wall (Dan, Nov 25). */
class RoomElevationsTest {
    private val inch = 50f / 12f
    private fun wall(id: String, l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(
        id = id, symbolName = FloorPlan.EXTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch))

    // A long north wall (0..360), the room 144" wide x 120" deep at its west end, closed by a wall at x = 150.
    private val north = wall("n", 0f, 0f, 360f, 6f)
    private val state = CanvasState(elements = listOf(
        north,
        wall("w", 0f, 6f, 6f, 126f),
        wall("e", 150f, 6f, 154f, 126f),
        wall("s", 0f, 126f, 154f, 132f)
    ))

    @Test
    fun `tapping inside a room gives each wall cut to the room`() {
        val faces = RoomElevations.faces(Offset(70f * inch, 60f * inch), state)
        assertEquals(listOf("north", "east", "south", "west"), faces.map { it.direction })
        val n = faces.first { it.direction == "north" }
        assertEquals("n", n.wall.id)
        assertEquals(ViewerSide.BELOW, n.side)
        // The room's stretch of the long wall: inside faces 6" to 150".
        assertEquals(6f, n.fromIn, 0.01f)
        assertEquals(150f, n.toIn, 0.01f)
        val w = faces.first { it.direction == "west" }
        assertEquals(ViewerSide.RIGHT, w.side)
        assertEquals(120f, w.toIn - w.fromIn, 0.01f)
    }

    @Test
    fun `an elevation shows just its stretch of the wall`() {
        val whole = ElevationSpec(wallId = "n", side = ViewerSide.BELOW)
        assertEquals(360f * inch, Elevation.sizePx(north, whole, state).width, 0.5f)
        val room = whole.copy(fromIn = 6f, toIn = 150f)
        assertEquals(144f * inch, Elevation.sizePx(north, room, state).width, 0.5f)
        // The wall at x = 150 butts in at the stretch's right end; the one beyond (none here) would be left out.
        val items = Elevation.itemsInView(north, room, state)
        assertTrue(items.all { it.from >= 0f && it.to <= 144f * inch + 0.5f })
        // 0 to 0 is the whole wall.
        assertEquals(360f * inch, Elevation.sizePx(north, whole.copy(fromIn = 0f, toIn = 0f), state).width, 0.5f)
    }
}
