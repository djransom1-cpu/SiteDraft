package com.example.sitedraft.ui.canvas

import com.example.sitedraft.util.format
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max

/**
 * Something picked from a panel that lands where the next canvas tap is. Session-only state
 * (CanvasState keeps it @Transient). [label] is what the "tap to place" banner shows.
 */
sealed class Placement {
    abstract val label: String

    /** A library symbol; the tap is its centre. */
    data class Symbol(val name: String, val category: String) : Placement() {
        override val label get() = name
    }

    /**
     * The building outline by OUTSIDE dimensions in document units (ft or m); the tap is its
     * top-left outside corner.
     */
    data class Shell(val width: Float, val depth: Float, val wallName: String, override val label: String) : Placement()

    /**
     * A room by INSIDE dimensions in document units; the tap is its top-left inside corner, so
     * it can be snapped to the inside corner of the building or of another room.
     */
    data class Room(val name: String, val width: Float, val depth: Float, val wallName: String, override val label: String) : Placement()

    /** An elevation of a plan wall; the tap is the bottom-left corner of its floor line. */
    data class Elevation(val spec: ElevationSpec, override val label: String) : Placement()
}

/** Builds the wall sections of the floor-plan tool out of the library's wall symbols. */
object FloorPlan {
    const val EXTERIOR_WALL = "2x6 Wall"
    const val INTERIOR_WALL = "2x4 Wall"

    /** Room presets: name to (width, depth) in feet, inside dimensions. */
    val roomPresets: List<Pair<String, Pair<Float, Float>>> = listOf(
        "Bedroom" to Pair(12f, 12f),
        "Bathroom" to Pair(8f, 5f),
        "Kitchen" to Pair(12f, 10f),
        "Living Room" to Pair(16f, 14f),
        "Dining" to Pair(12f, 10f),
        "Office" to Pair(10f, 10f),
        "Laundry" to Pair(6f, 6f),
        "Closet" to Pair(5f, 2f),
        "Hall" to Pair(12f, 3.5f),
        "Garage" to Pair(22f, 22f)
    )

    /** The outline of a wall symbol (walls are never rotated by this tool). */
    fun bounds(wall: DrawingElement.Symbol): Rect =
        Rect(wall.center - Offset(wall.size.width / 2f, wall.size.height / 2f), wall.size)

    /**
     * The four walls around a rectangle whose OUTSIDE is [outer], each [thickness] thick. A side
     * that already has a wall along it (one of [existing], same orientation, touching or
     * overlapping, covering at least half its length) is left out, and the neighbouring walls are
     * shortened to butt against that wall instead of running into it. So a room dropped into a
     * corner of the building adds just the two walls it needs, and two rooms side by side share
     * one wall.
     */
    fun wallsAround(
        outer: Rect,
        thickness: Float,
        wallName: String,
        color: Color,
        existing: List<DrawingElement.Symbol>,
        tolerance: Float
    ): List<DrawingElement.Symbol> {
        val t = thickness
        val walls = existing.filter { it.category == PlanSymbols.WALLS }.map { bounds(it) }

        fun covered(candidate: Rect): Boolean {
            val horizontal = candidate.width >= candidate.height
            val length = if (horizontal) candidate.width else candidate.height
            return walls.any { e ->
                if ((e.width >= e.height) != horizontal) return@any false
                val centreGap = if (horizontal) abs(e.center.y - candidate.center.y) else abs(e.center.x - candidate.center.x)
                val touching = centreGap <= (t + (if (horizontal) e.height else e.width)) / 2f + tolerance
                val overlap = if (horizontal) minOf(e.right, candidate.right) - maxOf(e.left, candidate.left)
                              else minOf(e.bottom, candidate.bottom) - maxOf(e.top, candidate.top)
                touching && overlap >= 0.5f * length
            }
        }

        val top = Rect(outer.left, outer.top, outer.right, outer.top + t)
        val bottom = Rect(outer.left, outer.bottom - t, outer.right, outer.bottom)
        val left = Rect(outer.left, outer.top, outer.left + t, outer.bottom)
        val right = Rect(outer.right - t, outer.top, outer.right, outer.bottom)
        val skipTop = covered(top)
        val skipBottom = covered(bottom)
        val skipLeft = covered(left)
        val skipRight = covered(right)

        // Horizontal walls run the full width unless a side wall already exists there; vertical
        // walls always sit between the top and bottom walls (new or existing).
        val hLeft = if (skipLeft) outer.left + t else outer.left
        val hRight = if (skipRight) outer.right - t else outer.right
        val vTop = outer.top + t
        val vBottom = outer.bottom - t

        val rects = buildList {
            if (!skipTop) add(Rect(hLeft, outer.top, hRight, outer.top + t))
            if (!skipBottom) add(Rect(hLeft, outer.bottom - t, hRight, outer.bottom))
            if (!skipLeft) add(Rect(outer.left, vTop, outer.left + t, vBottom))
            if (!skipRight) add(Rect(outer.right - t, vTop, outer.right, vBottom))
        }
        return rects.filter { it.width > 0f && it.height > 0f }.map { r ->
            DrawingElement.Symbol(
                symbolName = wallName,
                category = PlanSymbols.WALLS,
                center = r.center,
                size = Size(r.width, r.height),
                color = color,
                properties = mapOf(
                    "widthInches" to (PlanSymbols.footprintInches[wallName]?.first ?: 48f),
                    "depthInches" to (PlanSymbols.footprintInches[wallName]?.second ?: 4.5f)
                )
            )
        }
    }

    /** How close (inches) a room's side has to come to an existing wall to be pulled onto it. */
    const val ROOM_MAGNET_IN = 12f

    /**
     * Pulls a room onto nearby walls so it shares them instead of standing a few inches off with
     * a second wall of its own. [insideCorner] is the room's top-left inside corner and
     * [width]/[depth] its inside size (px). For each existing wall running alongside one of the
     * room's sides (same direction, at least half the side's length alongside it) and within
     * [reach] of that side, the room is moved so that side sits on the wall's face. Returns the
     * adjusted inside corner; [wallsAround] then sees the wall is already there and skips it.
     */
    fun magnetRoomCorner(insideCorner: Offset, width: Float, depth: Float, existing: List<DrawingElement.Symbol>, reach: Float): Offset {
        val r = Rect(insideCorner, Size(width, depth))
        var dx = 0f
        var dy = 0f
        var bestDx = Float.MAX_VALUE
        var bestDy = Float.MAX_VALUE
        for (e in existing.filter { it.category == PlanSymbols.WALLS }.map { bounds(it) }) {
            if (e.width >= e.height) {
                val alongside = minOf(e.right, r.right) - maxOf(e.left, r.left)
                if (alongside < 0.5f * r.width) continue
                // Room below the wall (its top face to the wall's underside) or above it.
                for (shift in listOf(e.bottom - r.top, e.top - r.bottom)) {
                    if (abs(shift) <= reach && abs(shift) < bestDy) { bestDy = abs(shift); dy = shift }
                }
            } else {
                val alongside = minOf(e.bottom, r.bottom) - maxOf(e.top, r.top)
                if (alongside < 0.5f * r.height) continue
                for (shift in listOf(e.right - r.left, e.left - r.right)) {
                    if (abs(shift) <= reach && abs(shift) < bestDx) { bestDx = abs(shift); dx = shift }
                }
            }
        }
        return insideCorner + Offset(dx, dy)
    }

    /** Where a door or window goes once it is matched to the wall it was tapped on. */
    data class OpeningPlacement(val center: Offset, val rotation: Float, val size: Size?)

    /**
     * Puts a door or window INTO the wall nearest the tap (within a foot of it), or returns null
     * when the tap is not on a wall. A hinged door's threshold lands on the wall's centre line
     * and the door turns to the wall's direction; a window or sliding door is centred in the
     * wall and takes the wall's thickness. [snapped] is used for the position along the wall
     * when it still lies on that wall, so grid snapping keeps working; otherwise [raw] is used.
     */
    fun alignOpening(name: String, raw: Offset, snapped: Offset, state: CanvasState): OpeningPlacement? {
        val (wIn, _) = PlanSymbols.footprintInches[name] ?: return null
        val w = state.inchesToPx(wIn)
        val reach = state.inchesToPx(12f)
        val wall = state.elements.filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.WALLS && it.rotation == 0f }
            .map { bounds(it) }
            .filter { it.inflate(reach).contains(raw) }
            .minByOrNull { b ->
                if (b.width >= b.height) abs(raw.y - b.center.y) else abs(raw.x - b.center.x)
            } ?: return null
        val horizontal = wall.width >= wall.height
        val onWall = wall.inflate(reach).contains(snapped)
        val hinged = PlanSymbols.isDoor(name)
        return if (horizontal) {
            val x = if (onWall) snapped.x else raw.x
            if (hinged) OpeningPlacement(Offset(x, wall.center.y - w / 2f), 0f, null)
            else OpeningPlacement(Offset(x, wall.center.y), 0f, Size(w, wall.height))
        } else {
            val y = if (onWall) snapped.y else raw.y
            // Turned 90 degrees clockwise the threshold (bottom edge) becomes the left edge.
            if (hinged) OpeningPlacement(Offset(wall.center.x + w / 2f, y), 90f, null)
            else OpeningPlacement(Offset(wall.center.x, y), 0f, Size(wall.width, w))
        }
    }

    /**
     * The rectangles a ghost of [placement] covers if it lands at [point] (snapped): a symbol's
     * footprint (seated in its wall for doors and windows), a room's inside and outside, an
     * outline's outside and inside. Drawn while the pen hovers or drags so the user sees where
     * the thing goes before lifting.
     */
    fun previewRects(placement: Placement, point: Offset, state: CanvasState): List<Rect> {
        val ratio = state.scale.ratio
        return when (placement) {
            is Placement.Symbol -> {
                val opening = if (placement.category == PlanSymbols.DOORS_WINDOWS) alignOpening(placement.name, point, point, state) else null
                val footprint = PlanSymbols.footprintInches[placement.name]
                val size = opening?.size
                    ?: footprint?.let { (w, d) -> Size(state.inchesToPx(w), state.inchesToPx(d)) }
                    ?: Size(100f, 100f)
                val centre = opening?.center ?: point
                // A quarter-turned door is square, so the swapped size is the same; other
                // symbols are not turned by placement.
                listOf(Rect(centre - Offset(size.width / 2f, size.height / 2f), size))
            }
            is Placement.Shell -> {
                val t = state.inchesToPx(PlanSymbols.footprintInches[placement.wallName]?.second ?: 4.5f)
                val outer = Rect(point, Size(placement.width * ratio, placement.depth * ratio))
                listOf(outer, outer.deflate(t))
            }
            is Placement.Room -> {
                val t = state.inchesToPx(PlanSymbols.footprintInches[placement.wallName]?.second ?: 4.5f)
                val w = placement.width * ratio
                val d = placement.depth * ratio
                val corner = magnetRoomCorner(point, w, d, state.elements.filterIsInstance<DrawingElement.Symbol>(), state.inchesToPx(ROOM_MAGNET_IN))
                val inside = Rect(corner, Size(w, d))
                listOf(inside.inflate(t), inside)
            }
            is Placement.Elevation -> {
                val wall = state.elements.find { it.id == placement.spec.wallId } as? DrawingElement.Symbol ?: return emptyList()
                val size = Elevation.sizePx(wall, placement.spec, state)
                listOf(Rect(Offset(point.x, point.y - size.height), size))
            }
        }
    }

    /** "12' x 12'" or "3.66 x 3.66 m". */
    fun formatDims(width: Float, depth: Float, unitSystem: UnitSystem): String {
        fun n(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
        return if (unitSystem == UnitSystem.IMPERIAL) "${n(width)}' x ${n(depth)}'" else "${n(width)} x ${n(depth)} m"
    }

    /** The room's name and size, centred in its inside area. */
    fun roomLabel(room: Placement.Room, inside: Rect, state: CanvasState): DrawingElement.Text {
        val dims = formatDims(room.width, room.depth, state.unitSystem)
        val text = if (room.name.isBlank()) dims else "${room.name}\n$dims"
        val fontSize = state.inchesToPx(6f).coerceIn(10f, 36f)
        val longest = text.lines().maxOf { it.length }
        val approxWidth = longest * fontSize * 0.55f
        val lines = text.lines().size
        return DrawingElement.Text(
            text = text,
            topLeft = Offset(inside.center.x - approxWidth / 2f, inside.center.y - fontSize * 0.6f * lines),
            fontSize = fontSize,
            color = state.currentColor
        )
    }

    fun shellLabel(width: Float, depth: Float, unitSystem: UnitSystem) =
        "${formatDims(width, depth, unitSystem)} outline"

    fun roomPlacementLabel(name: String, width: Float, depth: Float, unitSystem: UnitSystem) =
        "${formatDims(width, depth, unitSystem)} ${name.ifBlank { "room" }}"
}
