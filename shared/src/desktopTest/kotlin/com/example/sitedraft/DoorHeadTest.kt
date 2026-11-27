package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DoorHead
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.LayerEdits
import com.example.sitedraft.ui.canvas.PlanSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The job's door head line (Dan, Nov 25): doors and windows line up with it. */
class DoorHeadTest {
    private fun opening(id: String, name: String, props: Map<String, Float>) = DrawingElement.Symbol(
        id = id, symbolName = name, category = PlanSymbols.DOORS_WINDOWS, center = Offset.Zero,
        size = Size(150f, 150f), color = Color.White, properties = props)

    private fun prop(s: CanvasState, id: String, key: String) =
        (s.elements.first { it.id == id } as DrawingElement.Symbol).properties[key]

    private val state = CanvasState(elements = listOf(
        opening("door", "Door 3'-0\"", mapOf(PlanSymbols.PROP_HEIGHT_IN to 80f)),
        opening("win", "Window 3'-0\"", mapOf(PlanSymbols.PROP_SILL_IN to 32f, PlanSymbols.PROP_HEIGHT_IN to 48f)),
        // A stacked gable window, set above the line by hand.
        opening("stacked", "Window 3'-0\"", mapOf(PlanSymbols.PROP_SILL_IN to 90f, PlanSymbols.PROP_HEIGHT_IN to 24f)),
        // A taller door set by hand.
        opening("tall", "Door 3'-0\"", mapOf(PlanSymbols.PROP_HEIGHT_IN to 84f))
    ))

    @Test
    fun `default door head is 6'8"`() {
        assertEquals(80f, BuildingSettings().doorHeadIn, 0.001f)
    }

    @Test
    fun `openings on the old line follow a new door head, others stay`() {
        val s = DoorHead.follow(state, 80f, 96f)
        assertEquals(96f, prop(s, "door", PlanSymbols.PROP_HEIGHT_IN)!!, 0.001f)
        // The window keeps its height; its sill moves so its head is on the new line.
        assertEquals(48f, prop(s, "win", PlanSymbols.PROP_SILL_IN)!!, 0.001f)
        assertEquals(48f, prop(s, "win", PlanSymbols.PROP_HEIGHT_IN)!!, 0.001f)
        assertEquals(90f, prop(s, "stacked", PlanSymbols.PROP_SILL_IN)!!, 0.001f)
        assertEquals(84f, prop(s, "tall", PlanSymbols.PROP_HEIGHT_IN)!!, 0.001f)
    }

    @Test
    fun `same head changes nothing`() {
        assertSame(state, DoorHead.follow(state, 80f, 80f))
    }

    @Test
    fun `setting the job's door head moves the openings with it`() {
        val r = LayerEdits.setBuilding(state, state.building.copy(doorHeadIn = 96f))
        assertEquals(null, r.refused)
        assertEquals(96f, r.state.building.doorHeadIn, 0.001f)
        assertEquals(96f, prop(r.state, "door", PlanSymbols.PROP_HEIGHT_IN)!!, 0.001f)
        assertEquals(48f, prop(r.state, "win", PlanSymbols.PROP_SILL_IN)!!, 0.001f)
    }

    @Test
    fun `a door head out of range is refused`() {
        assertNotNull(LayerEdits.setBuilding(state, state.building.copy(doorHeadIn = 40f)).refused)
    }
}
