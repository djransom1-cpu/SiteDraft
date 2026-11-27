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
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Trim
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** What the context cards change (Dan, Nov 27): a door's kind and width in place, an opening's own trim. 1' = 50px. */
class ContextCardsTest {
    private val inch = 50f / 12f

    private class Host : Platform {
        override val name = "test"
        override val storage: ProjectStorage = InMemoryStorage()
        override val prefs: KeyValuePrefs = InMemoryPrefs()
        override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? = null
        override fun exportText(fileName: String, text: String): String? = null
    }

    private fun manager(elements: List<DrawingElement>): DrawingManager {
        val host = Host()
        host.storage.write("project_Cards.json", Json { ignoreUnknownKeys = true }.encodeToString(CanvasState.serializer(), CanvasState(projectName = "Cards", elements = elements)))
        host.storage.write("last_project.txt", "Cards")
        val m = DrawingManager(host)
        Thread.sleep(300)
        m.loadProjectByName("Cards")
        return m
    }

    @Test
    fun `a door changed to a wider double door keeps its wall side`() {
        // A 3'-0" door swinging north from an east-west wall whose north face is at y 100".
        val door = DrawingElement.Symbol(id = "d", symbolName = "Door 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(60f * inch, (100f - 18f) * inch), size = Size(36f * inch, 36f * inch))
        val wall = DrawingElement.Symbol(id = "w", symbolName = FloorPlan.INTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
            center = Offset(60f * inch, 102.25f * inch), size = Size(240f * inch, 4.5f * inch))
        val m = manager(listOf(wall, door))
        m.onEvent(DrawingEvent.SetOpeningKind("d", "Door 6'-0\" Double", 72f))
        val d = m.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single { it.id == "d" }
        assertEquals("Door 6'-0\" Double", d.symbolName)
        assertEquals(72f, d.size.width / inch, 0.01f)
        // The wall side (the bottom edge, y = centre + half the depth) is where it was.
        assertEquals(100f, (d.center.y + d.size.height / 2f) / inch, 0.01f)
        assertEquals(60f, d.center.x / inch, 0.01f)
    }

    @Test
    fun `an opening's own trim overrides the room's, and none leaves it alone`() {
        val spec = ElevationSpec(wallId = "w", side = com.example.sitedraft.ui.canvas.ViewerSide.BELOW)
        val plain = DrawingElement.Symbol(symbolName = "Window 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White, center = Offset.Zero, size = Size(1f, 1f))
        assertSame(spec, Trim.specFor(plain, spec))
        val own = plain.copy(properties = mapOf(Trim.PROP_CASING_IN to 3.25f, Trim.PROP_HEAD to Trim.HEAD_BACKBAND.toFloat()))
        val s = Trim.specFor(own, spec)
        assertEquals(3.25f, s.casingIn, 0.001f)
        assertEquals(Trim.HEAD_BACKBAND, s.headStyle)
        assertEquals(spec.windowTrim, s.windowTrim)
    }

    private fun wall(id: String, l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(id = id, symbolName = FloorPlan.INTERIOR_WALL,
        category = PlanSymbols.WALLS, color = Color.White, center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch))

    @Test
    fun `the wall card changes a wall's type on its centre line and its length from the west end`() {
        val m = manager(listOf(wall("w", 0f, 100f, 120f, 104.5f)))
        m.onEvent(DrawingEvent.SetWallKind("w", "2x6 Wall"))
        var w = m.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        assertEquals(6.5f, w.size.height / inch, 0.01f)
        assertEquals(102.25f, w.center.y / inch, 0.01f)
        m.onEvent(DrawingEvent.SetWallLength("w", 96f))
        w = m.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        assertEquals(0f, FloorPlan.bounds(w).left / inch, 0.01f)
        assertEquals(96f, w.size.width / inch, 0.01f)
    }

    @Test
    fun `a cabinet keeps its back to the wall when it changes size, and its style goes to its room only`() {
        // A 10' x 10' room (inside 4.5" to 124.5"), a base cabinet against its north wall, another cabinet outside the room.
        val room = listOf(wall("n", 0f, 0f, 129f, 4.5f), wall("s", 0f, 124.5f, 129f, 129f), wall("wl", 0f, 4.5f, 4.5f, 124.5f), wall("e", 124.5f, 4.5f, 129f, 124.5f))
        fun cab(id: String, x: Float, y: Float) = DrawingElement.Symbol(id = id, symbolName = "Base 24\"", category = PlanSymbols.CABINETS, color = Color.White,
            center = Offset(x * inch, y * inch), size = Size(24f * inch, 24f * inch))
        val m = manager(room + listOf(cab("a", 30f, 16.5f), cab("b", 60f, 16.5f), cab("out", 200f, 200f)))
        m.onEvent(DrawingEvent.SetCabinetKind("a", "Base 36\"", 36f))
        fun sym(id: String) = m.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single { it.id == id }
        assertEquals(36f, sym("a").size.width / inch, 0.01f)
        assertEquals(4.5f, (sym("a").center.y - sym("a").size.height / 2f) / inch, 0.01f) // back still on the wall face
        m.onEvent(DrawingEvent.UpdateElementProperty("a", PlanSymbols.PROP_STYLE, PlanSymbols.STYLE_SHAKER.toString()))
        m.onEvent(DrawingEvent.ApplyCabinetStyleInRoom("a"))
        assertEquals(PlanSymbols.STYLE_SHAKER, PlanSymbols.doorStyle(sym("b")))
        assertEquals(PlanSymbols.STYLE_SLAB, PlanSymbols.doorStyle(sym("out")))
    }
}
