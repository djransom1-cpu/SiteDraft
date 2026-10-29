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
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's kitchen (2026-10-29): a 12'-9" x 10' room with a hinged door in the east wall.
 * The door was missing from that wall's elevation, and the elevation said "looking west"
 * (seen from outside the room). 1' = 50px.
 */
class ElevationDoorTest {
    private val inch = 50f / 12f
    private val t = 4.5f * inch
    private val w = 153f * inch // 12'-9"
    private val d = 120f * inch // 10'

    private fun wall(cx: Float, cy: Float, ww: Float, hh: Float) = DrawingElement.Symbol(
        symbolName = "2x4 Wall", category = PlanSymbols.WALLS, center = Offset(cx, cy), size = Size(ww, hh), color = Color.White
    )

    private val north = wall(w / 2f, 0f, w, t)
    private val south = wall(w / 2f, d, w, t)
    private val west = wall(0f, d / 2f, t, d - t)
    private val east = wall(w, d / 2f, t, d - t)
    private val walls = listOf(north, south, west, east)

    /** A door placed the way the app places it: FloorPlan.alignOpening on a tap at [tap]. */
    private fun doorAt(tap: Offset, state: CanvasState): DrawingElement.Symbol {
        val name = "Door 3'-0\""
        val placement = checkNotNull(FloorPlan.alignOpening(name, tap, tap, state)) { "the tap is not on a wall" }
        return DrawingElement.Symbol(
            symbolName = name, category = PlanSymbols.DOORS_WINDOWS, center = placement.center,
            size = Size(36f * inch, 36f * inch), rotation = placement.rotation, color = Color.White
        )
    }

    private fun doorRectHeights(wall: DrawingElement.Symbol, side: ViewerSide, state: CanvasState): List<Float> =
        Elevation.build(wall, ElevationSpec(wall.id, side), Offset(0f, 2000f), state)
            .filterIsInstance<DrawingElement.Rectangle>()
            .map { it.size.height / inch }
            .filter { kotlin.math.abs(it - 80f) < 0.5f }

    @Test
    fun `a hinged door placed in the east wall shows in that wall's elevation from both sides`() {
        val base = CanvasState(elements = walls)
        val door = doorAt(Offset(w, d / 2f), base)
        val state = base.copy(elements = walls + door)
        for (side in listOf(ViewerSide.LEFT, ViewerSide.RIGHT)) {
            val items = Elevation.itemsOnWall(east, side, state)
            assertEquals("door seen ${side.looking}", listOf(door.id), items.filter { it.kind == Elevation.Kind.OPENING }.map { it.symbol.id })
            val item = items.single { it.kind == Elevation.Kind.OPENING }
            assertEquals(36f * inch, item.to - item.from, 0.01f)
            assertEquals("an 80\" door panel ${side.looking}", 1, doorRectHeights(east, side, state).size)
        }
        // The door belongs to the east wall only.
        assertTrue(Elevation.itemsOnWall(west, ViewerSide.RIGHT, state).none { it.kind == Elevation.Kind.OPENING })
    }

    @Test
    fun `a door that swings into the room is found too`() {
        // Same door turned the other way: the threshold is now the symbol's right edge.
        val inward = DrawingElement.Symbol(
            symbolName = "Door 3'-0\"", category = PlanSymbols.DOORS_WINDOWS,
            center = Offset(w - 18f * inch, d / 2f), size = Size(36f * inch, 36f * inch), rotation = 270f, color = Color.White
        )
        val anchor = Elevation.openingAnchor(inward)
        assertEquals(w, anchor.x, 0.01f)
        val state = CanvasState(elements = walls + inward)
        assertEquals(1, Elevation.itemsOnWall(east, ViewerSide.LEFT, state).count { it.kind == Elevation.Kind.OPENING })
    }

    @Test
    fun `a hinged door in a horizontal wall is found`() {
        val base = CanvasState(elements = walls)
        val door = doorAt(Offset(w / 2f, 0f), base)
        val state = base.copy(elements = walls + door)
        assertEquals(1, Elevation.itemsOnWall(north, ViewerSide.BELOW, state).count { it.kind == Elevation.Kind.OPENING })
        assertEquals(1, doorRectHeights(north, ViewerSide.BELOW, state).size)
    }

    @Test
    fun `bare walls are seen from inside the room`() {
        val base = CanvasState(elements = walls)
        val state = base.copy(elements = walls + doorAt(Offset(w, d / 2f), base))
        assertEquals("looking east", Elevation.defaultSide(east, state).looking)
        assertEquals("looking west", Elevation.defaultSide(west, state).looking)
        assertEquals("looking north", Elevation.defaultSide(north, state).looking)
        assertEquals("looking south", Elevation.defaultSide(south, state).looking)
    }
}
