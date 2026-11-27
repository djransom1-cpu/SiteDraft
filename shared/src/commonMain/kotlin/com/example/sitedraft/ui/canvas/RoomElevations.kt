package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Elevations by room (Dan, Nov 25): tap inside a room and each of its walls gets an elevation cut to that room,
 * its ends at the room's corners, instead of the whole long wall. Uses the same room finder as Dimension room.
 */
object RoomElevations {
    val directions = listOf("north", "east", "south", "west")

    /** One wall of the room as an elevation: the wall, the side it's seen from, and the room's stretch of it. */
    data class Face(val wall: DrawingElement.Symbol, val side: ViewerSide, val fromIn: Float, val toIn: Float, val direction: String)

    fun faces(p: Offset, state: CanvasState): List<Face> {
        val room = FloorPlan.areaAt(p, state) ?: return emptyList()
        val inch = state.inchesToPx(1f)
        val walls = Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.WALLS && !DerivedLayers.isGenerated(it) }
        /** The wall along one side of the room that covers most of it. */
        fun along(onSide: (Rect) -> Boolean, horizontal: Boolean): DrawingElement.Symbol? = walls
            .map { it to FloorPlan.bounds(it) }
            .filter { (_, b) -> onSide(b) }
            .maxByOrNull { (_, b) ->
                if (horizontal) min(b.right, room.right) - max(b.left, room.left) else min(b.bottom, room.bottom) - max(b.top, room.top)
            }?.takeIf { (_, b) ->
                (if (horizontal) min(b.right, room.right) - max(b.left, room.left) else min(b.bottom, room.bottom) - max(b.top, room.top)) > inch
            }?.first
        fun face(wall: DrawingElement.Symbol?, side: ViewerSide, a: Offset, b: Offset, dir: String): Face? {
            wall ?: return null
            val wb = FloorPlan.bounds(wall)
            val x1 = Elevation.alongOf(wb, side, a) / inch
            val x2 = Elevation.alongOf(wb, side, b) / inch
            return Face(wall, side, min(x1, x2), max(x1, x2), dir)
        }
        return listOfNotNull(
            // North wall, seen from the room looking north: its left end is west.
            face(along({ abs(it.bottom - room.top) <= inch }, true), ViewerSide.BELOW, Offset(room.left, room.top), Offset(room.right, room.top), "north"),
            face(along({ abs(it.left - room.right) <= inch }, false), ViewerSide.LEFT, Offset(room.right, room.top), Offset(room.right, room.bottom), "east"),
            face(along({ abs(it.top - room.bottom) <= inch }, true), ViewerSide.ABOVE, Offset(room.right, room.bottom), Offset(room.left, room.bottom), "south"),
            face(along({ abs(it.right - room.left) <= inch }, false), ViewerSide.RIGHT, Offset(room.left, room.bottom), Offset(room.left, room.top), "west")
        )
    }
}
