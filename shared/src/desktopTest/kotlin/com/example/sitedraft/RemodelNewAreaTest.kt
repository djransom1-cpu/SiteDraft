package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DerivedLayers
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Remodel
import com.example.sitedraft.ui.canvas.RoofSettings
import com.example.sitedraft.ui.canvas.Structure
import com.example.sitedraft.ui.canvas.WallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A remodel frames only the new area (Dan, Nov 24 2026). */
class RemodelNewAreaTest {
    private val inch = 50f / 12f
    private fun wall(id: String, l: Float, t: Float, r: Float, b: Float, status: WallStatus) = DrawingElement.Symbol(
        id = id, symbolName = "2x6 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch),
        properties = mapOf(Remodel.PROP_STATUS to status.code.toFloat()))
    /** A 40' x 24' existing house. */
    private val house = listOf(
        wall("top", 0f, 0f, 480f, 6.5f, WallStatus.EXISTING), wall("bottom", 0f, 281.5f, 480f, 288f, WallStatus.EXISTING),
        wall("left", 0f, 6.5f, 6.5f, 281.5f, WallStatus.EXISTING), wall("right", 473.5f, 6.5f, 480f, 281.5f, WallStatus.EXISTING))
    /** A new 16' x 12' addition off the south wall (three new walls). */
    private val addition = listOf(
        wall("a-w", 96f, 288f, 102.5f, 432f, WallStatus.NEW), wall("a-s", 96f, 425.5f, 288f, 432f, WallStatus.NEW),
        wall("a-e", 281.5f, 288f, 288f, 425.5f, WallStatus.NEW))
    private fun built(els: List<DrawingElement>) =
        DerivedLayers.sync(CanvasState(elements = els, building = BuildingSettings(autoLayers = true, roof = RoofSettings())))
    private fun gen(s: CanvasState, key: String) = s.elements.singleOrNull { it.id == DerivedLayers.GEN_PREFIX + key } as DrawingElement.Symbol?

    @Test
    fun `new walls on an existing house frame only the new area`() {
        val s = built(house + addition)
        // The existing house: no floor framing of its own, its foundation kept and drawn Existing.
        assertNull(gen(s, "bay:floor1"))
        val fw = gen(s, "fw:top")
        assertNotNull(fw)
        assertEquals(WallStatus.EXISTING, Remodel.statusOf(fw!!))
        // The addition: its own joist bay over its own walls, and foundation under each new wall, New.
        val bay = gen(s, DerivedLayers.NEW_AREA + "bay:floor1")
        assertNotNull(bay)
        assertEquals(192f, bay!!.size.width / inch, 0.6f)
        assertEquals(144f, bay.size.height / inch, 0.6f)
        assertEquals(360f, bay.center.y / inch, 0.6f)
        for (w in listOf("a-w", "a-s", "a-e")) {
            val nfw = gen(s, DerivedLayers.NEW_AREA + "fw:$w")
            assertNotNull(nfw)
            assertEquals(WallStatus.NEW, Remodel.statusOf(nfw!!))
        }
        // Nothing of the addition is drawn twice under the old ids.
        assertNull(gen(s, "fw:a-w"))
        assertTrue(s.elements.none { it.id.startsWith(DerivedLayers.GEN_PREFIX + "bay:") })
        assertTrue(s.elements.filterIsInstance<DrawingElement.Symbol>().none { it.symbolName == Structure.JOIST_BAY && Remodel.statusOf(it) == WallStatus.EXISTING })
    }

    @Test
    fun `an all-new house and an all-existing house build as before`() {
        val allNew = built(house.map { it.copy(properties = mapOf(Remodel.PROP_STATUS to WallStatus.NEW.code.toFloat())) })
        assertNotNull(gen(allNew, "bay:floor1"))
        val allOld = built(house)
        assertNull(gen(allOld, "bay:floor1"))
        assertNotNull(gen(allOld, "fw:top"))
        assertTrue(allOld.elements.none { it.id.startsWith(DerivedLayers.GEN_PREFIX + DerivedLayers.NEW_AREA) })
    }
}
