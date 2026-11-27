package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset

/**
 * One layout for the whole house (Dan, Nov 24): studs, joists and ladder blocks all on the same
 * 16" marks, measured west to east from the outside of the west wall and north to south from the
 * outside of the north wall, wherever an interior wall starts. Members are centred on the marks.
 * Stud bays then sit over joist bays, open for the plumber and the HVAC runs. Upper-floor joists and the roof
 * trusses stack on it too (24" trusses land on every third 16" mark, every 48"); the first floor's walls set it.
 */
object FramingLayout {
    const val SPACING_IN = 16f

    private var cacheFor: List<DrawingElement>? = null
    private var cached: Offset? = null

    /** The layout origin: the north-west outside corner of the exterior walls (world px), or null when there are none. */
    fun origin(state: CanvasState): Offset? {
        val els = state.elements
        if (els === cacheFor) return cached
        val ext = els.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.WALLS && it.symbolName == FloorPlan.EXTERIOR_WALL && !DerivedLayers.isGenerated(it) &&
                Remodel.status(it) != WallStatus.DEMO && it.rotation % 90f == 0f
        }
        // The first floor's walls set it (an upper floor that overhangs doesn't move the layout under it).
        val of = Layers.resolver(state)
        val first = ext.filter { of(it).id == Building.FLOOR1_PLAN }
        val base = first.ifEmpty { ext }
        val o = if (base.isEmpty()) null else base.map { FloorPlan.bounds(it) }.let { rs -> Offset(rs.minOf { it.left }, rs.minOf { it.top }) }
        cacheFor = els
        cached = o
        return o
    }

    /**
     * A wall buck (Dan, Nov 24): where a wall meets the face of another (a T), the wall being met gets two studs side by
     * side and a third laid flat against the face the other wall comes in on, beside the pair, centred on the meeting
     * wall: nailing for the drywall on both sides of it. [centerPx] from the wall's start (west / north end); [side] the
     * face in the wall's own drawing frame: +1 its +y face (south of a west-east wall, west of a north-south one), -1 the other.
     *
     * A corner buck (Dan, Nov 25, an L): where a wall butts into this one at its end, this wall keeps its end stud and
     * gets a second stud turned flat right beside it on the face the other wall comes in on, nailed to it in an L. The
     * flat stud runs past the other wall's inside face, so the other wall's end stud nails into it and the inside
     * corner has drywall backing on both walls. [corner] -1 at the wall's start, +1 at its end, 0 for a T.
     */
    data class Buck(val centerPx: Float, val side: Int, val corner: Int = 0)

    private var bucksFor: List<DrawingElement>? = null
    private var bucks: Map<String, List<Buck>> = emptyMap()

    /**
     * Every framed wall's bucks, by wall id. A wall whose end touches another's face (within an inch) away from that
     * wall's ends makes a buck there; one at the wall's end makes a corner buck (an L: the end stud and a flat one).
     */
    fun bucks(state: CanvasState): Map<String, List<Buck>> {
        val els = state.elements
        if (els === bucksFor) return bucks
        val inch = state.inchesToPx(1f)
        val walls = els.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.WALLS && !DerivedLayers.isGenerated(it) && Remodel.status(it) != WallStatus.DEMO && it.rotation % 90f == 0f
        }
        val out = HashMap<String, MutableList<Buck>>()
        val tol = inch
        for (w in walls) {
            val depthIn = Schedules.studDepthIn(w.symbolName) ?: continue
            val wb = FloorPlan.bounds(w)
            val horiz = Elevation.isHorizontal(w)
            val len = if (horiz) wb.width else wb.height
            val half = (3f + depthIn) / 2f * inch
            for (b in walls) {
                if (b.id == w.id || Elevation.isHorizontal(b) == horiz) continue
                val bb = FloorPlan.bounds(b)
                val c: Float
                val side: Int
                if (horiz) {
                    c = bb.center.x - wb.left
                    if (bb.center.x <= wb.left || bb.center.x >= wb.right) continue
                    side = when {
                        bb.top >= wb.top - tol && bb.top <= wb.bottom + tol && bb.bottom > wb.bottom + tol -> 1
                        bb.bottom <= wb.bottom + tol && bb.bottom >= wb.top - tol && bb.top < wb.top - tol -> -1
                        else -> 0
                    }
                } else {
                    c = bb.center.y - wb.top
                    if (bb.center.y <= wb.top || bb.center.y >= wb.bottom) continue
                    side = when {
                        bb.right <= wb.right + tol && bb.right >= wb.left - tol && bb.left < wb.left - tol -> 1   // comes in from the west
                        bb.left >= wb.left - tol && bb.left <= wb.right + tol && bb.right > wb.right + tol -> -1  // from the east
                        else -> 0
                    }
                }
                if (side == 0) continue
                // Near the wall's end it's a corner (an L): the end stud and a flat stud beside it.
                val corner = when {
                    c < half + 1.5f * inch -> -1
                    c > len - half - 1.5f * inch -> 1
                    else -> 0
                }
                out.getOrPut(w.id) { mutableListOf() } += Buck(c, side, corner)
            }
        }
        bucksFor = els
        bucks = out
        return out
    }

    private var spansFor: List<DrawingElement>? = null
    private var spans: Map<String, List<Pair<Float, Float>>> = emptyMap()

    /**
     * The rough openings in each wall (Dan, Nov 24: no layout studs in an opening; a jack and a
     * king stud each side), by wall id: from / to, px from the wall's left (west) or top (north)
     * end. Rough opening: a door or cased opening is its width plus 2", a window plus 3/4" (Dan's
     * RO rules); a pocket door takes its pocket too. Cripples above and below stay on layout.
     */
    fun openingSpans(state: CanvasState): Map<String, List<Pair<Float, Float>>> {
        val els = state.elements
        if (els === spansFor) return spans
        val inch = state.inchesToPx(1f)
        val out = HashMap<String, MutableList<Pair<Float, Float>>>()
        for (o in els.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.DOORS_WINDOWS }) {
            val wall = OpeningPosition.hostWall(o, state) ?: continue
            if (wall.rotation % 90f != 0f) continue
            val horizontal = Elevation.isHorizontal(wall)
            val wb = FloorPlan.bounds(wall)
            val ob = FloorPlan.bounds(o)
            var a1 = if (horizontal) ob.left else ob.top
            var a2 = if (horizontal) ob.right else ob.bottom
            if (PlanSymbols.isDoor(o.symbolName)) {
                // A hinged door's footprint is its swing: the leaf width centred on it.
                val mid = (a1 + a2) / 2f
                a1 = mid - o.size.width / 2f; a2 = mid + o.size.width / 2f
            }
            if (PlanSymbols.isPocketDoor(o.symbolName)) {
                val w = a2 - a1
                if (PlanSymbols.pocketRight(o)) a2 += w else a1 -= w
            }
            val ro = (if (PlanSymbols.isWindow(o.symbolName)) 0.75f else 2f) * inch / 2f
            val start = if (horizontal) wb.left else wb.top
            out.getOrPut(wall.id) { mutableListOf() } += (a1 - ro - start) to (a2 + ro - start)
        }
        spansFor = els
        spans = out
        return out
    }

    /**
     * From an edge at [edge] (world px, along one axis) to the first layout mark past it, with the
     * layout starting at [origin] every [sp] px. A mark right on the edge counts as the next one.
     */
    fun firstMark(edge: Float, origin: Float, sp: Float): Float {
        if (sp <= 0f) return sp
        val r = (((edge - origin) % sp) + sp) % sp
        return if (r < sp * 0.001f || sp - r < sp * 0.001f) sp else sp - r
    }
}
