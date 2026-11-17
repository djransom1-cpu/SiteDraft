package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/**
 * Dimensions made in one tap. Everything here returns ordinary [DrawingElement.Dimension]s, so
 * each one can be dragged, re-offset, retyped or deleted afterwards like a hand-drawn one.
 *
 * - [plan]: string dimensions around the outside of the building (openings to their centres,
 *   the wall jogs and the interior walls that meet the outside walls, the overall size), plus
 *   the inside width and depth of each room.
 * - [elevation]: an elevation's item widths and overall length across the top, its counter,
 *   upper cabinet and ceiling heights down the right side, and each window's sill and head
 *   (each door's head) beside it.
 * - [quick]: the one thing under a tap: a wall's length, a door or window's width, a cabinet or
 *   fixture's width, or the inside size of the room.
 *
 * Dimensions made for a plan or an elevation carry an owner ([PLAN_OWNER] or the elevation's
 * id), so running it again replaces them, and an elevation's dimensions move and delete with it.
 */
object AutoDimension {
    const val PLAN_OWNER = "plan"

    /** First string this far off the building or the elevation, inches; the next ones [TIER_STEP_IN] apart. */
    const val TIER_IN = 24f
    const val TIER_STEP_IN = 18f
    const val ELEV_TIER_IN = 12f

    data class PlanOptions(val outside: Boolean = true, val rooms: Boolean = true, val interiorToCenter: Boolean = true)

    private enum class Side { TOP, BOTTOM, LEFT, RIGHT }

    /**
     * A dimension measuring [a] to [b], its line pushed [dist] px toward [toward]. Dimension's own
     * offset is signed along the left-hand normal of a->b, so the sign is worked out here.
     */
    fun dim(a: Offset, b: Offset, toward: Offset, dist: Float, state: CanvasState, owner: String?): DrawingElement.Dimension {
        val d = b - a
        val len = d.getDistance()
        val u = if (len > 0.0001f) d / len else Offset(1f, 0f)
        val perp = Offset(-u.y, u.x)
        val sign = if (perp.x * toward.x + perp.y * toward.y >= 0f) 1f else -1f
        return DrawingElement.Dimension(
            start = a,
            end = b,
            value = len / state.scale.ratio,
            unit = state.unitSystem,
            format = state.dimensionFormat,
            color = Elevation.visibleInk(state.currentColor, state.backgroundColor),
            offset = sign * dist,
            ownerId = owner
        )
    }

    /** Sorted, with values closer than [tol] to the one before dropped. */
    fun merged(values: List<Float>, tol: Float): List<Float> {
        val out = mutableListOf<Float>()
        for (v in values.sorted()) if (out.isEmpty() || v - out.last() > tol) out += v
        return out
    }

    // ---------------------------------------------------------------- plan

    fun plan(state: CanvasState, opts: PlanOptions = PlanOptions()): List<DrawingElement.Dimension> {
        val walls = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.WALLS }.map { FloorPlan.bounds(it) }
        if (walls.isEmpty()) return emptyList()
        val outer = Rect(walls.minOf { it.left }, walls.minOf { it.top }, walls.maxOf { it.right }, walls.maxOf { it.bottom })
        val out = mutableListOf<DrawingElement.Dimension>()
        if (opts.outside) for (side in Side.entries) out += sideStrings(side, walls, outer, state, opts.interiorToCenter)
        if (opts.rooms) for (room in rooms(walls, outer, state)) out += roomDims(room, state, PLAN_OWNER)
        return out
    }

    /** Along-the-side coordinate of a point, and the extent of a box, for one side of the building. */
    private fun along(side: Side, p: Offset) = if (side == Side.TOP || side == Side.BOTTOM) p.x else p.y
    private fun span(side: Side, r: Rect) = if (side == Side.TOP || side == Side.BOTTOM) Pair(r.left, r.right) else Pair(r.top, r.bottom)
    private fun faces(side: Side, r: Rect) = if (side == Side.TOP || side == Side.BOTTOM) r.width >= r.height else r.height > r.width

    /** How far out a box reaches on [side]: a smaller number is further out, whichever side it is. */
    private fun outness(side: Side, r: Rect) = when (side) {
        Side.TOP -> r.top
        Side.BOTTOM -> -r.bottom
        Side.LEFT -> r.left
        Side.RIGHT -> -r.right
    }

    private fun innerFace(side: Side, r: Rect) = when (side) {
        Side.TOP -> r.bottom
        Side.BOTTOM -> -r.top
        Side.LEFT -> r.right
        Side.RIGHT -> -r.left
    }

    /** The world point on the building's outside line at [a] along the side. */
    private fun onEdge(side: Side, outer: Rect, a: Float) = when (side) {
        Side.TOP -> Offset(a, outer.top)
        Side.BOTTOM -> Offset(a, outer.bottom)
        Side.LEFT -> Offset(outer.left, a)
        Side.RIGHT -> Offset(outer.right, a)
    }

    private fun outward(side: Side) = when (side) {
        Side.TOP -> Offset(0f, -1f)
        Side.BOTTOM -> Offset(0f, 1f)
        Side.LEFT -> Offset(-1f, 0f)
        Side.RIGHT -> Offset(1f, 0f)
    }

    /**
     * The strings on one side, closest first: openings (to their centres) from corner to corner,
     * then the wall jogs and interior walls, then overall. A string the same as the one inside
     * it is left out.
     */
    private fun sideStrings(side: Side, walls: List<Rect>, outer: Rect, state: CanvasState, toCenter: Boolean): List<DrawingElement.Dimension> {
        val inch = state.inchesToPx(1f)
        val tol = inch
        val (lo, hi) = span(side, outer)
        // The outline seen from this side: for each stretch between wall edges, how far out the outermost wall reaches.
        val cuts = merged(walls.flatMap { val s = span(side, it); listOf(s.first, s.second) }.filter { it in lo..hi } + listOf(lo, hi), 0.01f)
        fun outermostOn(sd: Side, a: Float): Float? = walls.filter { val s = span(sd, it); a > s.first && a < s.second }.minOfOrNull { outness(sd, it) }
        fun outermostAt(a: Float): Float? = outermostOn(side, a)
        /**
         * On the outline of the building as seen from [sd]: the outermost wall along most of its
         * length. Sampled along it, not just at its middle, which can land right on a jog.
         */
        fun onOutline(sd: Side, w: Rect): Boolean {
            val s = span(sd, w)
            val samples = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f).map { s.first + (s.second - s.first) * it }
            val outer = samples.count { a -> outermostOn(sd, a)?.let { abs(outness(sd, w) - it) <= tol } == true }
            return outer * 2 > samples.size
        }
        val crossSides = if (side == Side.TOP || side == Side.BOTTOM) listOf(Side.LEFT, Side.RIGHT) else listOf(Side.TOP, Side.BOTTOM)
        val jogs = mutableListOf(lo, hi)
        var prev: Float? = null
        for (k in 0 until cuts.size - 1) {
            val here = outermostAt((cuts[k] + cuts[k + 1]) / 2f)
            val changed = (here == null) != (prev == null) || (here != null && prev != null && abs(here - prev) > tol)
            if (k > 0 && changed) jogs += cuts[k]
            prev = here
        }
        // The outside walls on this side: they lie on the outline.
        val outsideWalls = walls.filter { w -> faces(side, w) && onOutline(side, w) }
        // Interior walls that butt into (or run into) an outside wall from inside.
        val partitions = mutableListOf<Float>()
        for (p in walls) {
            if (faces(side, p)) continue
            val s = span(side, p)
            val mid = (s.first + s.second) / 2f
            val o = outermostAt(mid) ?: continue
            if (outness(side, p) <= o + tol) continue // part of the outline itself
            if (crossSides.any { onOutline(it, p) }) continue // an end wall (or a jog) of the building, not an interior wall
            outsideWalls.firstOrNull { w ->
                val ws = span(side, w)
                mid > ws.first && mid < ws.second && outness(side, p) <= innerFace(side, w) + tol
            } ?: continue
            if (toCenter) partitions += mid else { partitions += s.first; partitions += s.second }
        }
        // Doors and windows in the outside walls, to their centres.
        val openings = state.elements.filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.DOORS_WINDOWS }
            .map { Elevation.openingAnchor(it) }
            .filter { a -> outsideWalls.any { it.inflate(2f * inch).contains(a) } }
            .map { along(side, it) }

        val strings = mutableListOf<List<Float>>()
        if (openings.isNotEmpty()) strings += merged(jogs + openings, 0.25f * inch)
        strings += merged(jogs + partitions, 0.25f * inch)
        strings += listOf(lo, hi)
        val out = mutableListOf<DrawingElement.Dimension>()
        var dist = TIER_IN * inch
        var last: List<Float>? = null
        for (points in strings) {
            if (points.size < 2 || points == last) continue
            for (k in 0 until points.size - 1) {
                out += dim(onEdge(side, outer, points[k]), onEdge(side, outer, points[k + 1]), outward(side), dist, state, PLAN_OWNER)
            }
            last = points
            dist += TIER_STEP_IN * inch
        }
        return out
    }

    /**
     * The rooms: the area around the middle of each cell of the wall grid, bounded by walls on
     * all four sides, at least 2' each way, biggest first and never overlapping one already taken.
     */
    private fun rooms(walls: List<Rect>, outer: Rect, state: CanvasState): List<Rect> {
        val inch = state.inchesToPx(1f)
        val xs = merged(walls.flatMap { listOf(it.left, it.right) }, 0.5f)
        val ys = merged(walls.flatMap { listOf(it.top, it.bottom) }, 0.5f)
        val found = mutableListOf<Rect>()
        for (i in 0 until xs.size - 1) for (j in 0 until ys.size - 1) {
            val p = Offset((xs[i] + xs[i + 1]) / 2f, (ys[j] + ys[j + 1]) / 2f)
            if (walls.any { it.contains(p) }) continue
            val r = FloorPlan.areaAt(p, state) ?: continue
            if (r.width < 24f * inch || r.height < 24f * inch) continue
            if (r.left < outer.left - 0.5f || r.right > outer.right + 0.5f || r.top < outer.top - 0.5f || r.bottom > outer.bottom + 0.5f) continue
            // An L-shaped room can give a box that reaches past the corner, through a wall: not a room.
            if (walls.any { it.overlaps(r.deflate(1f)) }) continue
            if (found.none { abs(it.left - r.left) < 0.5f && abs(it.top - r.top) < 0.5f && abs(it.right - r.right) < 0.5f && abs(it.bottom - r.bottom) < 0.5f }) found += r
        }
        val taken = mutableListOf<Rect>()
        for (r in found.sortedByDescending { it.width * it.height }) {
            if (taken.none { it.overlaps(r.deflate(0.5f)) }) taken += r
        }
        return taken
    }

    /**
     * A room's inside width and depth, wall face to wall face, set OUTSIDE its walls: the width
     * over its top wall, the depth beside its left wall, 9" past the wall's outer face (12" from
     * the face when there is no wall there).
     */
    fun roomDims(room: Rect, state: CanvasState, owner: String?): List<DrawingElement.Dimension> {
        val inch = state.inchesToPx(1f)
        val walls = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.WALLS }.map { FloorPlan.bounds(it) }
        val topWall = walls.filter { abs(it.bottom - room.top) <= inch && it.right > room.left + inch && it.left < room.right - inch }.maxOfOrNull { it.height }
        val leftWall = walls.filter { abs(it.right - room.left) <= inch && it.bottom > room.top + inch && it.top < room.bottom - inch }.maxOfOrNull { it.width }
        val up = (topWall?.let { it + 9f * inch }) ?: (12f * inch)
        val side = (leftWall?.let { it + 9f * inch }) ?: (12f * inch)
        return listOf(
            dim(Offset(room.left, room.top), Offset(room.right, room.top), Offset(0f, -1f), up, state, owner),
            dim(Offset(room.left, room.top), Offset(room.left, room.bottom), Offset(-1f, 0f), side, state, owner)
        )
    }

    /**
     * The elevations that belong to [room]: those of the walls around it (a wall whose face is
     * on one of the room's sides and runs along it) and of an island standing in it.
     */
    fun elevationsOf(room: Rect, state: CanvasState): List<DrawingElement.ElevationView> {
        val inch = state.inchesToPx(1f)
        return state.elements.filterIsInstance<DrawingElement.ElevationView>().filter { v ->
            val host = state.elements.find { it.id == v.spec.wallId } as? DrawingElement.Symbol ?: return@filter false
            val b = FloorPlan.bounds(host)
            when {
                PlanSymbols.isIsland(host) -> room.contains(host.center)
                host.category == PlanSymbols.WALLS -> {
                    val alongX = b.right > room.left + inch && b.left < room.right - inch
                    val alongY = b.bottom > room.top + inch && b.top < room.bottom - inch
                    (alongX && (abs(b.bottom - room.top) <= inch || abs(b.top - room.bottom) <= inch)) ||
                        (alongY && (abs(b.right - room.left) <= inch || abs(b.left - room.right) <= inch))
                }
                else -> room.contains(host.center)
            }
        }
    }

    /** The owner tag for one room's dimensions, so tapping the same room again replaces them. */
    fun roomOwner(room: Rect): String =
        "room:${kotlin.math.round(room.left)},${kotlin.math.round(room.top)},${kotlin.math.round(room.right)},${kotlin.math.round(room.bottom)}"

    /** The dimensions for the room [p] is in (see [FloorPlan.areaAt]); none outside any room. */
    fun room(p: Offset, state: CanvasState): List<DrawingElement.Dimension> {
        val r = FloorPlan.areaAt(p, state) ?: return emptyList()
        return roomDims(r, state, roomOwner(r))
    }

    // ---------------------------------------------------------------- elevation

    fun elevation(view: DrawingElement.ElevationView, state: CanvasState): List<DrawingElement.Dimension> {
        val wall = state.elements.find { it.id == view.spec.wallId } as? DrawingElement.Symbol ?: return emptyList()
        val inch = state.inchesToPx(1f)
        val spec = view.spec
        val w = view.size.width
        val heightIn = view.size.height / inch
        val o = view.origin
        fun p(x: Float, hIn: Float) = Offset(o.x + x, o.y - hIn * inch)
        val owner = view.id
        val up = Offset(0f, -1f)
        val right = Offset(1f, 0f)
        val out = mutableListOf<DrawingElement.Dimension>()
        fun across(xs: List<Float>, dist: Float) {
            for (k in 0 until xs.size - 1) out += dim(p(xs[k], heightIn), p(xs[k + 1], heightIn), up, dist, state, owner)
        }
        fun upAt(x: Float, hs: List<Float>, toward: Offset, dist: Float) {
            for (k in 0 until hs.size - 1) out += dim(p(x, hs[k]), p(x, hs[k + 1]), toward, dist, state, owner)
        }
        val regular = spec.showerFace < 0 && spec.stairSide < 0 && spec.islandFace < 0
        if (!regular) {
            // Showers and stairs draw their own detail dimensions; add the overall size.
            across(listOf(0f, w), ELEV_TIER_IN * inch)
            upAt(w, listOf(0f, heightIn), right, ELEV_TIER_IN * inch)
            return out
        }
        val items = Elevation.itemsOnWall(wall, spec.side, state).filter { it.kind != Elevation.Kind.CUT_WALL }
        // Across the top: every item's width, then the overall length.
        val xs = merged(listOf(0f, w) + items.flatMap { listOf(it.from, it.to) }, 0.25f * inch)
        var dist = ELEV_TIER_IN * inch
        if (xs.size > 2) {
            across(xs, dist)
            dist += TIER_STEP_IN * inch
        }
        across(listOf(0f, w), dist)
        // Down the right side: counter (or vanity) height, the uppers' bottom and top, the ceiling.
        val hs = mutableListOf(0f, spec.wallHeightIn)
        for (it in items) {
            if (it.kind != Elevation.Kind.FIXTURE) continue
            val s = it.symbol
            if (PlanSymbols.isUpperCabinet(s)) {
                val (bottom, top) = Elevation.upperSpan(s, spec)
                hs += bottom
                hs += top
            } else if (Elevation.sitsAtCounter(s)) {
                hs += if (s.category == PlanSymbols.CABINETS && PlanSymbols.isVanity(s.symbolName)) PlanSymbols.vanityTop(s) else spec.counterHeightIn
            }
        }
        val heights = merged(hs.filter { it in 0f..spec.wallHeightIn }, 0.25f)
        dist = ELEV_TIER_IN * inch
        if (heights.size > 2) {
            upAt(w, heights, right, dist)
            dist += TIER_STEP_IN * inch
        }
        upAt(w, listOf(0f, spec.wallHeightIn), right, dist)
        // Each window's sill and head, each door's head, just left of it.
        for (it in items) {
            if (it.kind != Elevation.Kind.OPENING) continue
            val name = it.symbol.symbolName
            val props = it.symbol.properties
            val stack = if (PlanSymbols.isWindow(name)) {
                val sill = props[PlanSymbols.PROP_SILL_IN] ?: spec.sillIn
                listOf(0f, sill, sill + (props[PlanSymbols.PROP_HEIGHT_IN] ?: spec.windowHeightIn))
            } else listOf(0f, props[PlanSymbols.PROP_HEIGHT_IN] ?: spec.doorHeightIn)
            upAt(it.from, stack, Offset(-1f, 0f), 4f * inch)
        }
        return out
    }

    // ---------------------------------------------------------------- one tap

    /**
     * The dimension(s) for the thing under [p]: a door or window first (it sits in a wall), then
     * a cabinet, fixture or other symbol, then a wall; on bare floor, the room's inside size.
     */
    fun quick(p: Offset, state: CanvasState): List<DrawingElement.Dimension> {
        val inch = state.inchesToPx(1f)
        val symbols = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { hits(it, p) }
        val pick = symbols.lastOrNull { it.category == PlanSymbols.DOORS_WINDOWS }
            ?: symbols.lastOrNull { it.category != PlanSymbols.WALLS }
            ?: symbols.lastOrNull()
        if (pick == null) {
            val room = FloorPlan.areaAt(p, state) ?: return emptyList()
            return roomDims(room, state, null)
        }
        val dir = rotateAbout(Offset(1f, 0f), Offset.Zero, pick.rotation)
        val front = rotateAbout(Offset(0f, 1f), Offset.Zero, pick.rotation)
        return when (pick.category) {
            PlanSymbols.WALLS -> {
                val b = FloorPlan.bounds(pick)
                val walls = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.WALLS }.map { FloorPlan.bounds(it) }
                val middle = Offset(walls.map { it.center.x }.average().toFloat(), walls.map { it.center.y }.average().toFloat())
                if (b.width >= b.height) {
                    val outside = if (b.center.y <= middle.y) Offset(0f, -1f) else Offset(0f, 1f)
                    val y = if (outside.y < 0f) b.top else b.bottom
                    listOf(dim(Offset(b.left, y), Offset(b.right, y), outside, 18f * inch, state, null))
                } else {
                    val outside = if (b.center.x <= middle.x) Offset(-1f, 0f) else Offset(1f, 0f)
                    val x = if (outside.x < 0f) b.left else b.right
                    listOf(dim(Offset(x, b.top), Offset(x, b.bottom), outside, 18f * inch, state, null))
                }
            }
            PlanSymbols.DOORS_WINDOWS -> {
                val a = Elevation.openingAnchor(pick)
                val half = dir * (pick.size.width / 2f)
                // On the side away from a door's swing.
                listOf(dim(a - half, a + half, -front, 12f * inch, state, null))
            }
            else -> {
                // Across the front (the symbol's back is its top edge), pushed out in front of it.
                val frontMid = pick.center + front * (pick.size.height / 2f)
                val half = dir * (pick.size.width / 2f)
                listOf(dim(frontMid - half, frontMid + half, front, 12f * inch, state, null))
            }
        }
    }

    private fun hits(s: DrawingElement.Symbol, p: Offset): Boolean {
        val local = rotateAbout(p, s.center, -s.rotation) - s.center
        return abs(local.x) <= s.size.width / 2f && abs(local.y) <= s.size.height / 2f
    }

    // ---------------------------------------------------------------- strings by hand

    /**
     * A new dimension that starts where [previous] ended and runs the same way continues its
     * string: it takes the same dimension line, so it needs no third tap. Null when it does not
     * line up (a different direction, or not starting at [previous]'s end).
     */
    fun continued(previous: DrawingElement.Dimension, start: Offset, end: Offset, tol: Float): Float? {
        if ((start - previous.end).getDistance() > tol) return null
        val a = previous.end - previous.start
        val b = end - start
        val la = a.getDistance()
        val lb = b.getDistance()
        if (la < 0.0001f || lb < 0.0001f) return null
        val cross = (a.x * b.y - a.y * b.x) / (la * lb)
        if (abs(cross) > 0.01f) return null
        val dot = (a.x * b.x + a.y * b.y) / (la * lb)
        return if (dot > 0f) previous.offset else -previous.offset
    }
}

/** True for a dimension that the plan ([AutoDimension.PLAN_OWNER]) or the elevation [id] made. */
fun DrawingElement.isOwnedBy(id: String?): Boolean = id != null && this is DrawingElement.Dimension && ownerId == id
