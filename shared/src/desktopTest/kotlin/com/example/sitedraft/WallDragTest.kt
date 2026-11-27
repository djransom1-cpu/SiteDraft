package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.sitedraft.platform.InMemoryPrefs
import com.example.sitedraft.platform.InMemoryStorage
import com.example.sitedraft.platform.KeyValuePrefs
import com.example.sitedraft.platform.PageSpec
import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.ProjectStorage
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingTool
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dan, Nov 27: dragging a wall should stretch the walls meeting it, as a typed move does. 1' = 50px. */
class WallDragTest {
    private val inch = 50f / 12f

    private fun wall(id: String, l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(
        id = id, symbolName = FloorPlan.INTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch)
    )

    private class Host : Platform {
        override val name = "test"
        override val storage: ProjectStorage = InMemoryStorage()
        override val prefs: KeyValuePrefs = InMemoryPrefs()
        override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? = null
        override fun exportText(fileName: String, text: String): String? = null
    }

    @Test
    fun `dragging a wall stretches the walls meeting it`() {
        // A 4 1/2" wall running east-west at y 120..124.5, with walls coming up to it from the south at x 0 and x 120.
        val north = wall("n", 0f, 120f, 124.5f, 124.5f)
        val west = wall("w", 0f, 124.5f, 4.5f, 240f)
        val east = wall("e", 120f, 124.5f, 124.5f, 240f)
        val host = Host()
        val st = CanvasState(projectName = "Drag", elements = listOf(north, west, east))
        host.storage.write("project_Drag.json", Json { ignoreUnknownKeys = true }.encodeToString(CanvasState.serializer(), st))
        host.storage.write("last_project.txt", "Drag")
        val m = DrawingManager(host)
        m.loadProjectByName("Drag")
        Thread.sleep(300) // the launch restore runs too
        m.loadProjectByName("Drag")
        m.onEvent(DrawingEvent.SelectTool(DrawingTool.MOVE))
        m.onEvent(DrawingEvent.SelectElements(setOf("n")))
        // Drag it 24" north.
        val c = north.center
        m.onEvent(DrawingEvent.Start(c))
        m.onEvent(DrawingEvent.Move(c + Offset(0f, -12f * inch)))
        m.onEvent(DrawingEvent.Move(c + Offset(0f, -24f * inch)))
        m.onEvent(DrawingEvent.End)
        val els = m.state.value.elements.filterIsInstance<DrawingElement.Symbol>().associateBy { it.id }
        val n = FloorPlan.bounds(els.getValue("n"))
        assertTrue("moved north: ${n.top / inch}", n.top / inch < 110f)
        // Both side walls now start at the wall's new south face and still end where they did.
        for (id in listOf("w", "e")) {
            val b = FloorPlan.bounds(els.getValue(id))
            assertEquals("$id top", n.bottom / inch, b.top / inch, 0.6f)
            assertEquals("$id bottom", 240f, b.bottom / inch, 0.6f)
        }
    }
}
