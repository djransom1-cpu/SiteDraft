package com.example.sitedraft

import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.RoomNames
import com.example.sitedraft.ui.canvas.ElectricalCode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every room needs a name (Nov 28): unnamed rooms are found, and naming one puts the name on its label. */
class RoomNamesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun check(fixture: String) {
        val st: CanvasState = json.decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/$fixture").readText())
        val before = RoomNames.unnamed(st)
        if (before.isEmpty()) return
        val room = before.first()
        val after = st.copy(elements = RoomNames.named(room, "Pantry", st))
        assertEquals("$fixture: one fewer unnamed room", before.size - 1, RoomNames.unnamed(after).size)
        assertTrue(after.elements.filterIsInstance<DrawingElement.Text>().any { it.text.startsWith("Pantry\n") })
        // The tag sits inside its room, so a tap on it finds that room.
        val tag = RoomNames.tagRect(room, st.zoom)
        assertEquals(room, RoomNames.tagAt(tag.center, st))
    }

    @Test fun `practice house`() = check("practice_house.json")
    @Test fun `benjeman`() = check("benjeman_full.json")

    @Test
    fun `a room divider splits an open space into two rooms`() {
        val st: CanvasState = json.decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/practice_house.json").readText())
        val rooms = ElectricalCode.rooms(st)
        val big = rooms.maxBy { it.rect.width * it.rect.height }.rect
        val x = big.center.x
        val line = DrawingElement.Line(start = Offset(x, big.top), end = Offset(x, big.bottom), color = Color.White, strokeWidth = 2f, divider = true)
        val split = ElectricalCode.rooms(st.copy(elements = st.elements + line)).map { it.rect }
        assertTrue("more rooms: ${rooms.size} -> ${split.size}", split.size > rooms.size)
        assertTrue("left half", split.any { kotlin.math.abs(it.left - big.left) < 1f && it.right <= x + 1f })
        assertTrue("right half", split.any { kotlin.math.abs(it.right - big.right) < 1f && it.left >= x - 1f })
    }
}
