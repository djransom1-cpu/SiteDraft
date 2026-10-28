package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset

/** Where the next laser-measured wall goes, in canvas space (y grows downwards). */
enum class WallDirection(val label: String, val unit: Offset) {
    UP("Up", Offset(0f, -1f)),
    RIGHT("Right", Offset(1f, 0f)),
    DOWN("Down", Offset(0f, 1f)),
    LEFT("Left", Offset(-1f, 0f)),
}

/**
 * Laser wall mode. The cursor is where the next wall starts; each measurement adds a wall from
 * it in [direction] and moves it to the wall's end. [roomStart] is where the first wall of the
 * current room began, so "Close room" can draw the last wall back to it. Session-only state:
 * CanvasState keeps it @Transient so it never lands in a project file.
 */
data class WallMode(
    val active: Boolean = false,
    val cursor: Offset? = null,
    val roomStart: Offset? = null,
    val direction: WallDirection = WallDirection.RIGHT,
)

/** Distance from the cursor to an arrow tip, and the tap radius around arrows/cursor, in screen px. */
const val WALL_ARROW_DISTANCE = 56f
const val WALL_HIT_RADIUS = 26f

sealed class WallHit {
    /** No cursor yet: this tap places it. */
    object Place : WallHit()
    data class Arrow(val direction: WallDirection) : WallHit()
    /** Tap on the marker itself: start dragging it. */
    object Cursor : WallHit()
}

/** What a press at [world] means for wall mode, or null if wall mode does not claim it. */
fun wallModeHit(state: CanvasState, world: Offset): WallHit? {
    val wall = state.wallMode
    if (!wall.active) return null
    val c = wall.cursor ?: return WallHit.Place
    val z = state.zoom
    WallDirection.entries.firstOrNull { d ->
        ((c + d.unit * (WALL_ARROW_DISTANCE / z)) - world).getDistance() < WALL_HIT_RADIUS / z
    }?.let { return WallHit.Arrow(it) }
    if ((c - world).getDistance() < WALL_HIT_RADIUS / z) return WallHit.Cursor
    return null
}
