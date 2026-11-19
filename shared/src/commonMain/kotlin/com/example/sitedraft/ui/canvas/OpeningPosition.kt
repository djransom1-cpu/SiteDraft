package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Where a door, window or opening sits along its wall, the way it is laid out on site: so far
 * from the left (or top) end, or the right (or bottom) end, to the near edge of the opening or
 * to its centre, measured from the inside corner of the room or the outside corner of the
 * building. The choices are kept in the opening's properties, so the distance shown in
 * Properties always reads the same way, and typing a new distance slides it along the wall.
 */
object OpeningPosition {
    /** 0: measured from the left (top) end of the wall; 1: from the right (bottom) end. */
    const val PROP_FROM_END = "posFromEnd"
    /** 0: to the near edge of the opening; 1: to its centre. */
    const val PROP_TO_CENTER = "posToCenter"
    /** 0: from the inside corner (the room's wall face); 1: from the outside corner of the building. */
    const val PROP_OUTSIDE = "posOutside"

    data class Ref(val fromEnd: Boolean, val toCenter: Boolean, val outside: Boolean)

    fun ref(s: DrawingElement.Symbol) = Ref(
        fromEnd = (s.properties[PROP_FROM_END] ?: 0f) >= 0.5f,
        toCenter = (s.properties[PROP_TO_CENTER] ?: 0f) >= 0.5f,
        outside = (s.properties[PROP_OUTSIDE] ?: 0f) >= 0.5f
    )

    /** The wall the opening sits in. */
    fun hostWall(opening: DrawingElement.Symbol, state: CanvasState): DrawingElement.Symbol? {
        val inch = state.inchesToPx(1f)
        val a = Elevation.openingAnchor(opening)
        return Layers.onLevelOf(opening, state).filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.WALLS && FloorPlan.bounds(it).inflate(2f * inch).contains(a) }
            .minByOrNull { (FloorPlan.bounds(it).center - a).getDistanceSquared() }
    }

    /** Along-the-wall (x for a left-right wall, y for an up-down one) and across-it coordinates. */
    private class Axis(val horizontal: Boolean) {
        fun a(p: Offset) = if (horizontal) p.x else p.y
        fun a1(r: Rect) = if (horizontal) r.left else r.top
        fun a2(r: Rect) = if (horizontal) r.right else r.bottom
        fun n1(r: Rect) = if (horizontal) r.top else r.left
        fun n2(r: Rect) = if (horizontal) r.bottom else r.right
        fun along(d: Float) = if (horizontal) Offset(d, 0f) else Offset(0f, d)
    }

    /** The opening's width along the wall: a hinged door's leaf width, else its footprint along the wall. */
    private fun widthAlong(opening: DrawingElement.Symbol, ax: Axis): Float =
        if (PlanSymbols.isDoor(opening.symbolName)) opening.size.width
        else FloorPlan.bounds(opening).let { ax.a2(it) - ax.a1(it) }

    /**
     * The two reference points along the wall around [at]: the inside corners of the room (the
     * nearest wall faces crossing the room side of the wall, or the wall's own ends) or the
     * outside corners of the building (the wall's ends, or the outer face of a wall it butts into).
     */
    fun ends(wall: DrawingElement.Symbol, state: CanvasState, outside: Boolean, at: Float): Pair<Float, Float> {
        val ax = Axis(Elevation.isHorizontal(wall))
        val wb = FloorPlan.bounds(wall)
        val inch = state.inchesToPx(1f)
        val side = Elevation.interiorSide(wall, state)
        val insidePlus = side == null || side == ViewerSide.BELOW || side == ViewerSide.RIGHT
        // A line just past the chosen face of the wall: in the room, or outside the building.
        val probe = if (outside == insidePlus) ax.n1(wb) - 0.5f * inch else ax.n2(wb) + 0.5f * inch
        val crossing = Layers.onLevelOf(wall, state).filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.WALLS && it.id != wall.id }
            .map { FloorPlan.bounds(it) }
            .filter { ax.n1(it) < probe && ax.n2(it) > probe }
        return if (!outside) {
            val start = (crossing.filter { ax.a2(it) <= at }.map { ax.a2(it) } + ax.a1(wb)).max()
            val end = (crossing.filter { ax.a1(it) >= at }.map { ax.a1(it) } + ax.a2(wb)).min()
            start to end
        } else {
            val start = (crossing.filter { ax.a2(it) >= ax.a1(wb) - inch && ax.a1(it) <= ax.a1(wb) + inch }.map { ax.a1(it) } + ax.a1(wb)).min()
            val end = (crossing.filter { ax.a1(it) <= ax.a2(wb) + inch && ax.a2(it) >= ax.a2(wb) - inch }.map { ax.a2(it) } + ax.a2(wb)).max()
            start to end
        }
    }

    /** The opening's distance (px) from the chosen end, to its near edge or centre; null when it is not in a wall. */
    fun distance(opening: DrawingElement.Symbol, state: CanvasState, ref: Ref = ref(opening)): Float? {
        val wall = hostWall(opening, state) ?: return null
        val ax = Axis(Elevation.isHorizontal(wall))
        val at = ax.a(Elevation.openingAnchor(opening))
        val half = widthAlong(opening, ax) / 2f
        val (s, e) = ends(wall, state, ref.outside, at)
        val point = when {
            ref.toCenter -> at
            ref.fromEnd -> at + half
            else -> at - half
        }
        return if (ref.fromEnd) e - point else point - s
    }

    /** The opening slid along its wall so it sits [distancePx] from the chosen end; null when it is not in a wall. */
    fun placed(opening: DrawingElement.Symbol, state: CanvasState, distancePx: Float, ref: Ref = ref(opening)): DrawingElement.Symbol? {
        val wall = hostWall(opening, state) ?: return null
        val ax = Axis(Elevation.isHorizontal(wall))
        val at = ax.a(Elevation.openingAnchor(opening))
        val half = widthAlong(opening, ax) / 2f
        val (s, e) = ends(wall, state, ref.outside, at)
        val point = if (ref.fromEnd) e - distancePx else s + distancePx
        val newAt = when {
            ref.toCenter -> point
            ref.fromEnd -> point - half
            else -> point + half
        }
        return opening.move(ax.along(newAt - at)) as DrawingElement.Symbol
    }
}
