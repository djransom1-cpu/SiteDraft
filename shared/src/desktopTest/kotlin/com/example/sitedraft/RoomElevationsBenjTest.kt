package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.RoomElevations
import com.example.sitedraft.ui.canvas.inchesToPx
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Benjeman (Kal's demo house, Nov 27): the bedroom's room elevations. Dan got three, not four. */
class RoomElevationsBenjTest {
    private val st: CanvasState = Json { ignoreUnknownKeys = true }
        .decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/benjeman.json").readText())
    private val inch = st.inchesToPx(1f)

    @Test
    fun `the bedroom gets all four walls`() {
        val p = Offset(-304f * inch, 76f * inch)
        val area = FloorPlan.areaAt(p, st)
        val faces = RoomElevations.faces(p, st)
        assertEquals("area ${area?.let { listOf(it.left / inch, it.top / inch, it.right / inch, it.bottom / inch) }} faces ${faces.map { it.direction + ":" + it.wall.id.take(8) }}",
            listOf("north", "east", "south", "west"), faces.map { it.direction })
    }

    @Test
    fun `the bedroom gets all four walls with the generated parts in`() {
        val full: CanvasState = Json { ignoreUnknownKeys = true }
            .decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/benjeman_full.json").readText())
        val p = Offset(-304f * inch, 76f * inch)
        val area = FloorPlan.areaAt(p, full)
        val faces = RoomElevations.faces(p, full)
        assertEquals("area ${area?.let { listOf(it.left / inch, it.top / inch, it.right / inch, it.bottom / inch) }} faces ${faces.map { it.direction + ":" + it.wall.id.take(8) }}",
            listOf("north", "east", "south", "west"), faces.map { it.direction })
    }

    @Test
    fun `the south wall shows the closet front and its double door in front of it`() {
        val p = Offset(-304f * inch, 76f * inch)
        val south = RoomElevations.faces(p, st).first { it.direction == "south" }
        val spec = com.example.sitedraft.ui.canvas.ElevationSpec(wallId = south.wall.id, side = south.side, fromIn = south.fromIn, toIn = south.toIn, foreIn = south.depthIn)
        val fore = com.example.sitedraft.ui.canvas.Elevation.itemsInFront(south.wall, spec, st)
        val ids = fore.map { it.kind.name + ":" + it.symbol.id.take(8) + " " + (it.from / inch).toInt() + "-" + (it.to / inch).toInt() }
        assertEquals("depth ${south.depthIn} fore $ids", listOf("FORE_WALL:7cf04145", "OPENING:7c48d69c"), fore.map { it.kind.name + ":" + it.symbol.id.take(8) })
        // Looking south the view's left is east; the view starts 4 1/2" in (the room), so the closet (x -376.5 to -312) runs 79 1/2" to 144".
        val w = fore.first()
        assertEquals(79.5f, w.from / inch, 1f)
        assertEquals(144f, w.to / inch, 1f)
    }
}
