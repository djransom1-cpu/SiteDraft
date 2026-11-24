package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset

/**
 * One layout for the whole house (Dan, Nov 24): studs, joists and ladder blocks all on the same
 * 16" marks, measured west to east from the outside of the west wall and north to south from the
 * outside of the north wall, wherever an interior wall starts. Members are centred on the marks.
 * Stud bays then sit over joist bays, open for the plumber and the HVAC runs.
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
        val o = if (ext.isEmpty()) null else ext.map { FloorPlan.bounds(it) }.let { rs -> Offset(rs.minOf { it.left }, rs.minOf { it.top }) }
        cacheFor = els
        cached = o
        return o
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
