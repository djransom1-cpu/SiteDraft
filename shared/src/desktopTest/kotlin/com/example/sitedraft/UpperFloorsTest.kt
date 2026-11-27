package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.Building
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DerivedLayers
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoofArea
import com.example.sitedraft.ui.canvas.RoofEdge
import com.example.sitedraft.ui.canvas.RoofSettings
import com.example.sitedraft.ui.canvas.Stairs
import com.example.sitedraft.ui.canvas.UpperFloors
import com.example.sitedraft.ui.canvas.UpperFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The floors above made from the floor below (UpperFloors, Dan Nov 27). 1' = 50px. */
class UpperFloorsTest {
    private val inch = 50f / 12f

    private fun wall(id: String, x1: Float, y1: Float, x2: Float, y2: Float, name: String = FloorPlan.EXTERIOR_WALL) = DrawingElement.Symbol(
        id = id, symbolName = name, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((x1 + x2) / 2f * inch, (y1 + y2) / 2f * inch), size = Size((x2 - x1) * inch, (y2 - y1) * inch), layerId = Building.FLOOR1_PLAN
    )

    /** A 40' x 28' box of 2x6 walls, outside faces (0,0) to (480", 336"), and a 12' one-story wing on the east. */
    private fun house(): List<DrawingElement> = listOf(
        wall("n", 0f, 0f, 480f, 5.5f), wall("s", 0f, 330.5f, 480f, 336f), wall("w", 0f, 0f, 5.5f, 336f), wall("e", 474.5f, 0f, 480f, 336f),
        wall("wn", 480f, 0f, 624f, 5.5f), wall("ws", 480f, 330.5f, 624f, 336f), wall("we", 618.5f, 0f, 624f, 336f)
    )

    private val twoStory = BuildingSettings(upperFloors = listOf(UpperFloor("Second Floor", 1317.25f)))

    @Test
    fun `no upper floor, nothing`() {
        assertTrue(UpperFloors.parts(CanvasState(elements = house())).isEmpty())
    }

    @Test
    fun `every outside wall goes up without a traced roof`() {
        val parts = UpperFloors.parts(CanvasState(elements = house(), building = twoStory))
        assertEquals(7, parts.size)
        assertTrue(parts.all { it.layerId == "floor2-plan" && it.id.startsWith(DerivedLayers.GEN_PREFIX + "up2:") })
    }

    @Test
    fun `with a traced roof only the two-story part goes up, and the wall against the one-story wing becomes outside`() {
        val areas = listOf(
            RoofArea("main", "Main", listOf(0f, 480f, 480f, 0f), listOf(0f, 0f, 336f, 336f), listOf(RoofEdge.GABLE, RoofEdge.EAVE, RoofEdge.GABLE, RoofEdge.EAVE), wallHeightIn = 186f),
            RoofArea("wing", "Wing", listOf(480f, 624f, 624f, 480f), listOf(0f, 0f, 336f, 336f), listOf(RoofEdge.EAVE, RoofEdge.GABLE, RoofEdge.EAVE, RoofEdge.OPEN), wallHeightIn = 96f)
        )
        val st = CanvasState(elements = house(), building = twoStory.copy(roof = RoofSettings(areas = areas)))
        val ids = UpperFloors.parts(st).map { it.id.removePrefix(DerivedLayers.GEN_PREFIX + "up2:") }.toSet()
        assertEquals(setOf("n", "s", "w", "e"), ids)
    }

    @Test
    fun `a stair going up shows on the floor above going down`() {
        val stair = DrawingElement.Symbol(id = "st", symbolName = Stairs.STRAIGHT, category = PlanSymbols.STAIRS, color = Color.White,
            center = Offset(100f * inch, 100f * inch), size = Size(36f * inch, 140f * inch), layerId = Building.FLOOR1_PLAN, properties = Stairs.defaults(Stairs.STRAIGHT))
        val st = CanvasState(elements = house() + stair, building = twoStory)
        val copy = UpperFloors.parts(st).single { it.category == PlanSymbols.STAIRS }
        assertEquals("floor2-plan", copy.layerId)
        assertTrue(UpperFloors.isUpCopy(copy))
        assertTrue(Stairs.goesDown(copy, st))
        // Its view upstairs doesn't open the floor a second time.
        assertEquals(1, Stairs.floorOpenings(st.copy(elements = st.elements + copy)).size)
    }

    @Test
    fun `built even with Build from floor plan off, and an edit is kept`() {
        val st = CanvasState(elements = house(), building = twoStory.copy(autoLayers = false))
        val synced = DerivedLayers.sync(st)
        assertEquals(7, synced.elements.count { it.layerId == "floor2-plan" })
    }
}
