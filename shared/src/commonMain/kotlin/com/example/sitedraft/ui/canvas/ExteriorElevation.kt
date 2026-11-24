package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.max
import kotlin.math.min

/**
 * The exterior elevations (Dan, Nov 22 2026): south, north, east and west, live from the plan and
 * the roof, true size in model space on the Exterior elevation layer, put on sheets from there.
 *
 * Built for the rectangular footprint the roof is (the outside of the first-floor exterior walls):
 *  - grade, the foundation showing above it (the exposure, set per job), the rest of the
 *    foundation wall and the footing dashed below grade
 *  - the wall face from the top of the foundation to the soffit (the top plate with no roof yet)
 *  - the doors and windows in the exterior walls of every floor, drawn by type at their sill and
 *    head, with casing
 *  - the roof: the eave side with the ridge (a hip end stops it half the span in), the end side
 *    as a gable (the wall runs up into it) or a hip; ridge = plate + heel + half the span x pitch
 *  - level marks: ridge, top of plate, each floor, top of foundation, grade
 *
 * Geometry is in real inches: x along the face from the viewer's left corner of the walls,
 * y = minus the elevation (up is up on the page).
 */
object ExteriorElevation {
    const val CATEGORY = "Exterior"
    const val SYMBOL = "Exterior Elevation"
    const val LAYER = "exterior"
    const val PROP_FACE = "face"
    /** Text is sized for paper at 1/4" = 1'-0". */
    const val DENOMINATOR = 48
    private const val FASCIA_IN = 6f
    private const val CASING_IN = 3.5f
    private const val GRADE_RUN_IN = 36f
    /** How far off the footprint an opening's centre may be and still be in that face's wall. */
    private const val FACE_REACH_IN = 10f
    private const val TEXT_PAPER_IN = 0.1f

    enum class Face(val title: String, val side: ViewerSide) {
        SOUTH("South Elevation", ViewerSide.BELOW),
        NORTH("North Elevation", ViewerSide.ABOVE),
        EAST("East Elevation", ViewerSide.RIGHT),
        WEST("West Elevation", ViewerSide.LEFT)
    }

    fun idFor(f: Face) = "extelev:${f.name.lowercase()}"

    fun isExterior(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY && e.symbolName == SYMBOL

    fun faceOf(e: DrawingElement.Symbol): Face = Face.entries.getOrElse((e.properties[PROP_FACE] ?: 0f).toInt()) { Face.SOUTH }

    fun all(state: CanvasState): List<DrawingElement.Symbol> =
        state.elements.filterIsInstance<DrawingElement.Symbol>().filter { isExterior(it) }.sortedBy { faceOf(it).ordinal }

    /** Top of the foundation wall (top of slab on a slab). */
    fun foundationTopIn(s: BuildingSettings): Float = if (s.foundation == FoundationType.SLAB) s.datumIn else Building.foundationTopIn(s)

    /** Grade: the exposure below the top of the foundation. */
    fun gradeIn(s: BuildingSettings): Float = foundationTopIn(s) - (s.gradeExposureIn ?: 8f)

    /** Top of the plate the roof sits on: the roof's, or the top floor plus the wall height. */
    fun plateIn(s: BuildingSettings): Float =
        Building.roofPlateIn(s) ?: ((s.upperFloors.lastOrNull()?.elevationIn ?: s.datumIn) + s.plateHeightIn)

    /**
     * The house's exterior finish on a wall face from [x0] to [x1] (Dan, Nov 23: a garage and a wing match the house):
     * the wainscot with its sill from [fTop], the main finish up to [wallTop], and with [gableApexIn] the gable's
     * finish up into the triangle; courses stop at [holes] (x1, e1, x2, e2 in a Rect: left/right x, top/bottom elevation).
     * [corners] draws the corner boards.
     */
    fun finishWall(fin: ExteriorFinish, x0: Float, x1: Float, fTop: Float, wallTop: Float, holes: List<Rect>, gableApexIn: Float?,
                   line: (Float, Float, Float, Float, Pen) -> Unit, corners: Boolean = true) {
        fun cutsAtE(e: Float) = holes.filter { e > it.top && e < it.bottom }.map { it.left to it.right }
        fun cutsAtX(x: Float) = holes.filter { x > it.left && x < it.right }.map { it.top to it.bottom }
        fun minus(lo: Float, hi: Float, cuts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            var out = listOf(lo to hi)
            for ((c1, c2) in cuts) out = out.flatMap { (a, b) ->
                if (c2 <= a || c1 >= b) listOf(a to b) else listOfNotNull((a to c1).takeIf { c1 > a }, (c2 to b).takeIf { b > c2 })
            }
            return out.filter { it.second - it.first > 0.25f }
        }
        val trim = if (!corners || fin.main == WallFinish.BRICK || fin.main == WallFinish.STONE) 0f else fin.cornerTrimIn.coerceAtLeast(0f)
        val left = x0 + trim
        val right = x1 - trim
        class Region(val eMin: Float, val eMax: Float, val xAt: (Float) -> Pair<Float, Float>?, val eAt: (Float) -> Pair<Float, Float>?)
        fun box(e1: Float, e2: Float) = Region(e1, e2, { e -> if (e in e1..e2) left to right else null }, { x -> if (x in left..right) e1 to e2 else null })
        fun hLine(rg: Region, e: Float) { rg.xAt(e)?.let { (a, b) -> minus(a, b, cutsAtE(e)).forEach { (lo, hi) -> line(lo, e, hi, e, Pen.THIN) } } }
        fun vLine(rg: Region, x: Float, e1: Float = -Float.MAX_VALUE, e2: Float = Float.MAX_VALUE) {
            rg.eAt(x)?.let { (a, b) -> minus(max(a, e1), min(b, e2), cutsAtX(x)).forEach { (lo, hi) -> line(x, lo, x, hi, Pen.THIN) } }
        }
        fun pattern(rg: Region, f: WallFinish, spacingIn: Float?) {
            val sp = (spacingIn ?: f.defaultIn).coerceAtLeast(1f)
            when (f) {
                WallFinish.LAP, WallFinish.BRICK -> { var e = rg.eMin + sp; while (e < rg.eMax - 0.1f) { hLine(rg, e); e += sp } }
                WallFinish.SHAKE -> {
                    var e = rg.eMin; var row = 0
                    while (e < rg.eMax - 0.1f) {
                        val top = min(e + sp, rg.eMax)
                        if (top < rg.eMax - 0.1f) hLine(rg, top)
                        var x = left + (if (row % 2 == 0) 4.5f else 9f)
                        while (x < right - 0.5f) { vLine(rg, x, e, top); x += 9f }
                        e = top; row++
                    }
                }
                WallFinish.STONE -> {
                    val heights = listOf(1f, 0.75f, 1.25f, 0.85f, 1.15f)
                    val joints = listOf(14f, 18f, 12f, 16f, 20f)
                    var e = rg.eMin; var row = 0
                    while (e < rg.eMax - 0.1f) {
                        val top = min(e + sp * heights[row % heights.size], rg.eMax)
                        if (top < rg.eMax - 0.1f) hLine(rg, top)
                        var x = left + 4f + (row * 7f) % 13f; var j = row
                        while (x < right - 0.5f) { vLine(rg, x, e, top); x += joints[j % joints.size]; j++ }
                        e = top; row++
                    }
                }
                WallFinish.BOARD_BATTEN -> {
                    var x = left + sp / 2f
                    while (x < right - 0.5f) { vLine(rg, x - 1.25f); vLine(rg, x + 1.25f); x += sp }
                }
            }
        }
        if (trim > 0f) { line(left, fTop, left, wallTop, Pen.THIN); line(right, fTop, right, wallTop, Pen.THIN) }
        var mainBottom = fTop
        fin.wainscot?.let { w ->
            val top = min(fTop + fin.wainscotHeightIn, wallTop)
            pattern(box(fTop, top), w, fin.wainscotIn)
            val sillTop = min(top + fin.wainscotSillIn.coerceAtLeast(0f), wallTop)
            val reachOut = if (sillTop > top) 1f else 0f
            minus(x0 - reachOut, x1 + reachOut, cutsAtE(top)).forEach { (lo, hi) -> line(lo, top, hi, top, Pen.MEDIUM) }
            if (sillTop > top) {
                minus(x0 - reachOut, x1 + reachOut, cutsAtE(sillTop)).forEach { (lo, hi) -> line(lo, sillTop, hi, sillTop, Pen.MEDIUM) }
                line(x0 - reachOut, top, x0 - reachOut, sillTop, Pen.MEDIUM)
                line(x1 + reachOut, top, x1 + reachOut, sillTop, Pen.MEDIUM)
                holes.filter { it.top < sillTop && it.bottom > top }.forEach { h ->
                    line(h.left, top, h.left, sillTop, Pen.MEDIUM); line(h.right, top, h.right, sillTop, Pen.MEDIUM)
                }
            }
            mainBottom = sillTop
        }
        pattern(box(mainBottom, wallTop), fin.main, fin.mainIn)
        gableApexIn?.let { apex ->
            val base = wallTop
            val mid = (x0 + x1) / 2f
            val hwMax = (x1 - x0) / 2f
            if (apex > base && hwMax > 0f) {
                val gable = Region(base, apex,
                    { e -> if (e in base..apex) { val hw = (apex - e) / (apex - base) * hwMax; max(left, mid - hw) to min(right, mid + hw) } else null },
                    { x -> if (x in x0..x1) base to (apex - kotlin.math.abs(x - mid) / hwMax * (apex - base)) else null })
                val g = fin.gable
                if (g != null && g != fin.main) minus(x0, x1, cutsAtE(base)).forEach { (lo, hi) -> line(lo, base, hi, base, Pen.MEDIUM) }
                pattern(gable, g ?: fin.main, if (g != null) fin.gableIn else fin.mainIn)
            }
        }
    }

    enum class Pen { HEAVY, MEDIUM, THIN, HIDDEN }
    data class Seg(val a: Offset, val b: Offset, val pen: Pen)
    /** A level mark's text, [at] its top-left. */
    data class Label(val text: String, val at: Offset)
    data class Mark(val name: String, val elevationIn: Float)
    data class Geometry(val segs: List<Seg>, val labels: List<Label>, val marks: List<Mark>, val extent: Rect, val textIn: Float)

    fun geometry(state: CanvasState, face: Face): Geometry? {
        val fp = RoofPlan.footprint(state) ?: return null
        val k = state.inchesToPx(1f)
        if (fp.width <= 0f || fp.height <= 0f || k <= 0f) return null
        val s = state.building
        val side = face.side
        val horizontal = side == ViewerSide.BELOW || side == ViewerSide.ABOVE
        val wallLen = (if (horizontal) fp.width else fp.height) / k
        val textIn = TEXT_PAPER_IN * DENOMINATOR
        val segs = mutableListOf<Seg>()
        fun y(e: Float) = -e
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, pen: Pen) { segs += Seg(Offset(x1, y(e1)), Offset(x2, y(e2)), pen) }
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, pen: Pen) {
            line(x1, e1, x2, e1, pen); line(x2, e1, x2, e2, pen); line(x2, e2, x1, e2, pen); line(x1, e2, x1, e1, pen)
        }

        val roofLabels = mutableListOf<Label>()
        // The roofing pattern on a roof face seen in the elevation, [poly] its outline (x, elevation):
        // courses across it at the exposure (measured up the slope), seams or barrels down it.
        fun hatch(poly: List<Pair<Float, Float>>, r: RoofSettings) {
            val rc = r.covering
            val spacing = (r.coveringIn ?: rc.defaultIn).coerceAtLeast(1f)
            fun clipH(e: Float): Pair<Float, Float>? {
                val xs = mutableListOf<Float>()
                for (i in poly.indices) {
                    val (x1, e1) = poly[i]; val (x2, e2) = poly[(i + 1) % poly.size]
                    if (e1 == e2) continue
                    if (e < min(e1, e2) || e > max(e1, e2)) continue
                    xs += x1 + (e - e1) / (e2 - e1) * (x2 - x1)
                }
                return if (xs.size >= 2) xs.min() to xs.max() else null
            }
            fun clipV(x: Float): Pair<Float, Float>? {
                val es = mutableListOf<Float>()
                for (i in poly.indices) {
                    val (x1, e1) = poly[i]; val (x2, e2) = poly[(i + 1) % poly.size]
                    if (x1 == x2) continue
                    if (x < min(x1, x2) || x > max(x1, x2)) continue
                    es += e1 + (x - x1) / (x2 - x1) * (e2 - e1)
                }
                return if (es.size >= 2) es.min() to es.max() else null
            }
            val eMin = poly.minOf { it.second }
            val eMax = poly.maxOf { it.second }
            val xMin = poly.minOf { it.first }
            val xMax = poly.maxOf { it.first }
            if (rc.courses) {
                val rise = spacing * r.pitch / kotlin.math.sqrt(144f + r.pitch * r.pitch)
                var e = eMin + rise
                while (rise > 0.05f && e < eMax - 0.05f) {
                    clipH(e)?.let { (a, b) -> line(a, e, b, e, Pen.THIN) }
                    e += rise
                }
            }
            val down = if (rc.courses) rc.barrelIn else spacing
            if (down > 0f) {
                var x = xMin + down
                while (x < xMax - 0.05f) {
                    clipV(x)?.let { (lo, hi) -> line(x, lo, x, hi, Pen.THIN) }
                    x += down
                }
            }
            // The roofing named over the ridge.
            val text = rc.label.uppercase()
            val w = text.length * TEXT_PAPER_IN * DENOMINATOR * 0.62f
            roofLabels += Label(text, Offset((xMin + xMax) / 2f - w / 2f, y(eMax + TEXT_PAPER_IN * DENOMINATOR * 1.8f)))
        }

        val fTop = foundationTopIn(s)
        val grade = gradeIn(s)
        val plate = plateIn(s)
        var leftReach = 0f
        var rightReach = 0f
        // Where the wall face stops: at each corner, and across the middle (null = follows the gable).
        var wallTopEnds = plate
        var ridgeIn: Float? = null
        // A gable end: the wall runs up into a triangle to here (the underside of the rake trim at the peak).
        var gableApexIn: Float? = null

        val r = s.roof
        val g = r?.let { RoofPlan.geometry(state, it) }
        if (r != null && g != null) {
            val pitch = r.pitch / 12f
            val roofAtWall = plate + r.heelIn
            val ov = r.overhangIn
            val eaveTop = roofAtWall - ov * pitch
            val soffit = eaveTop - FASCIA_IN
            val ridge = roofAtWall + g.spanIn / 2f * pitch
            ridgeIn = ridge
            val eaveSide = g.ridgeAcross == horizontal
            if (eaveSide) {
                // endA is the west end (ridge across) or the north end (ridge up the page).
                val (leftEnd, rightEnd) = when (side) {
                    ViewerSide.BELOW, ViewerSide.LEFT -> r.endA to r.endB
                    ViewerSide.ABOVE, ViewerSide.RIGHT -> r.endB to r.endA
                }
                val eL = if (leftEnd == RoofEnd.HIP) -ov else -r.rakeIn
                val eR = wallLen + if (rightEnd == RoofEnd.HIP) ov else r.rakeIn
                var rL = if (leftEnd == RoofEnd.HIP) g.spanIn / 2f else eL
                var rR = if (rightEnd == RoofEnd.HIP) wallLen - g.spanIn / 2f else eR
                if (rL > rR) { val m = (rL + rR) / 2f; rL = m; rR = m }
                line(eL, eaveTop, rL, ridge, Pen.HEAVY)
                line(rL, ridge, rR, ridge, Pen.HEAVY)
                line(rR, ridge, eR, eaveTop, Pen.HEAVY)
                hatch(listOf(eL to eaveTop, rL to ridge, rR to ridge, eR to eaveTop), r)
                // The fascia along the eave.
                rect(eL, soffit, eR, eaveTop, Pen.MEDIUM)
                wallTopEnds = soffit
                leftReach = -eL
                rightReach = eR - wallLen
            } else {
                val end = when (side) {
                    ViewerSide.BELOW, ViewerSide.RIGHT -> r.endB
                    ViewerSide.ABOVE, ViewerSide.LEFT -> r.endA
                }
                val eL = -ov
                val eR = wallLen + ov
                val mid = wallLen / 2f
                line(eL, eaveTop, mid, ridge, Pen.HEAVY)
                line(mid, ridge, eR, eaveTop, Pen.HEAVY)
                if (end == RoofEnd.HIP) {
                    // The hip comes down to the eave: fascia straight across, the wall stops at the soffit.
                    hatch(listOf(eL to eaveTop, mid to ridge, eR to eaveTop), r)
                    rect(eL, soffit, eR, eaveTop, Pen.MEDIUM)
                    wallTopEnds = soffit
                } else {
                    // A gable: the rake trim under the roof line, the wall up into the gable.
                    line(eL, eaveTop, eL, soffit, Pen.MEDIUM)
                    line(eL, soffit, mid, ridge - FASCIA_IN, Pen.MEDIUM)
                    line(mid, ridge - FASCIA_IN, eR, soffit, Pen.MEDIUM)
                    line(eR, soffit, eR, eaveTop, Pen.MEDIUM)
                    wallTopEnds = soffit + ov * pitch
                    gableApexIn = ridge - FASCIA_IN
                }
                leftReach = ov
                rightReach = ov
            }
        } else {
            line(0f, plate, wallLen, plate, Pen.HEAVY)
        }

        // The wall face, standing on the foundation.
        line(0f, fTop, 0f, wallTopEnds, Pen.HEAVY)
        line(wallLen, fTop, wallLen, wallTopEnds, Pen.HEAVY)
        line(0f, fTop, wallLen, fTop, Pen.MEDIUM)

        // The foundation: what shows above grade, the rest and the footing dashed below.
        if (fTop > grade) {
            line(0f, grade, 0f, fTop, Pen.MEDIUM)
            line(wallLen, grade, wallLen, fTop, Pen.MEDIUM)
        }
        if (s.foundation != FoundationType.SLAB) {
            val fgTop = Building.footingTopIn(s)
            val fgBottom = Building.footingBottomIn(s)
            val proj = Building.footingProjections(s).first
            line(0f, min(grade, fTop), 0f, fgTop, Pen.HIDDEN)
            line(wallLen, min(grade, fTop), wallLen, fgTop, Pen.HIDDEN)
            rect(-proj, fgBottom, wallLen + proj, fgTop, Pen.HIDDEN)
        }
        line(-leftReach - GRADE_RUN_IN, grade, wallLen + rightReach + GRADE_RUN_IN, grade, Pen.HEAVY)

        // Doors and windows in this face's walls, on every floor.
        val of = Layers.resolver(state)
        val levelById = state.levels.associateBy { it.id }
        val reach = FACE_REACH_IN * k
        // Each opening with its casing: the siding stops at it.
        val holes = mutableListOf<Rect>()
        for (o in state.elements.filterIsInstance<DrawingElement.Symbol>()) {
            val kind = Schedules.kindOf(o) ?: continue
            val layer = of(o)
            if (layer.trade != LayerTrade.FLOOR_PLAN) continue
            val host = OpeningPosition.hostWall(o, state) ?: continue
            if (host.symbolName != FloorPlan.EXTERIOR_WALL) continue
            if (Wings.isWingPart(host)) continue   // a wing's own walls: drawn with the wing
            val a = Elevation.openingAnchor(o)
            val near = listOf(
                ViewerSide.ABOVE to (a.y - fp.top), ViewerSide.BELOW to (fp.bottom - a.y),
                ViewerSide.RIGHT to (fp.right - a.x), ViewerSide.LEFT to (a.x - fp.left)
            ).minByOrNull { kotlin.math.abs(it.second) }!!
            if (near.first != side || kotlin.math.abs(near.second) > reach) continue
            val base = levelById[layer.levelId]?.elevationIn ?: s.datumIn
            val c = Elevation.alongOf(fp, side, a) / k
            val w = Schedules.widthAlongPx(o) / k
            val x1 = c - w / 2f
            val x2 = c + w / 2f
            if (kind == Schedules.Kind.WINDOW) {
                val sill = base + (o.properties[PlanSymbols.PROP_SILL_IN] ?: 36f)
                val head = sill + (o.properties[PlanSymbols.PROP_HEIGHT_IN] ?: 48f)
                val mid = (sill + head) / 2f
                rect(x1, sill, x2, head, Pen.MEDIUM)
                rect(x1 - CASING_IN, sill - 1.5f, x2 + CASING_IN, head + CASING_IN, Pen.THIN)
                holes += Rect(x1 - CASING_IN, sill - 1.5f, x2 + CASING_IN, head + CASING_IN)
                when (PlanSymbols.windowType(o)) {
                    PlanSymbols.WINDOW_SLIDER -> line(c, sill, c, head, Pen.THIN)
                    PlanSymbols.WINDOW_CASEMENT -> {
                        // The swing lines meet at the hinge side.
                        val vertical = o.size.height > o.size.width
                        val half = (if (vertical) o.size.height else o.size.width) / 2f
                        val edge = if (PlanSymbols.hingeRight(o)) half else -half
                        val hinge = o.center + if (vertical) Offset(0f, edge) else Offset(edge, 0f)
                        val hingeAtRight = Elevation.alongOf(fp, side, hinge) > Elevation.alongOf(fp, side, o.center)
                        val hx = if (hingeAtRight) x2 else x1
                        val lx = if (hingeAtRight) x1 else x2
                        line(lx, head, hx, mid, Pen.THIN)
                        line(lx, sill, hx, mid, Pen.THIN)
                    }
                    PlanSymbols.WINDOW_AWNING -> { line(x1, sill, c, head, Pen.THIN); line(x2, sill, c, head, Pen.THIN) }
                    PlanSymbols.WINDOW_FIXED -> {}
                    else -> line(x1, mid, x2, mid, Pen.THIN) // double hung: the meeting rail
                }
            } else {
                val head = base + (o.properties[PlanSymbols.PROP_HEIGHT_IN] ?: PlanSymbols.DOOR_HEIGHT_IN)
                rect(x1, base, x2, head, Pen.MEDIUM)
                // Casing up the sides and across the head.
                line(x1 - CASING_IN, base, x1 - CASING_IN, head + CASING_IN, Pen.THIN)
                line(x1 - CASING_IN, head + CASING_IN, x2 + CASING_IN, head + CASING_IN, Pen.THIN)
                line(x2 + CASING_IN, head + CASING_IN, x2 + CASING_IN, base, Pen.THIN)
                holes += Rect(x1 - CASING_IN, base, x2 + CASING_IN, head + CASING_IN)
                if (PlanSymbols.isSlidingDoor(o.symbolName)) {
                    line(c, base, c, head, Pen.THIN)
                } else if (PlanSymbols.isDoor(o.symbolName)) {
                    // The knob on the latch side.
                    val latchLocal = Offset(if (PlanSymbols.hingeRight(o)) -o.size.width / 2f else o.size.width / 2f, o.size.height / 2f)
                    val latch = rotateAbout(o.center + latchLocal, o.center, o.rotation)
                    val latchAtRight = Elevation.alongOf(fp, side, latch) > Elevation.alongOf(fp, side, a)
                    val kx = if (latchAtRight) x2 - 3f else x1 + 3f
                    rect(kx - 1f, base + 35f, kx + 1f, base + 37f, Pen.THIN)
                }
            }
        }

        // The exterior finish on the wall face (Dan, Nov 22): wainscot, main finish, the gable's own.
        s.exterior?.let { fin ->
            // Holes as (x1, e1, x2, e2) in elevation inches (Rect here holds elevations, not page y).
            fun cutsAtE(e: Float) = holes.filter { e > it.top && e < it.bottom }.map { it.left to it.right }
            fun cutsAtX(x: Float) = holes.filter { x > it.left && x < it.right }.map { it.top to it.bottom }
            fun minus(lo: Float, hi: Float, cuts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
                var out = listOf(lo to hi)
                for ((c1, c2) in cuts) out = out.flatMap { (a, b) ->
                    if (c2 <= a || c1 >= b) listOf(a to b) else listOfNotNull((a to c1).takeIf { c1 > a }, (c2 to b).takeIf { b > c2 })
                }
                return out.filter { it.second - it.first > 0.25f }
            }
            val trim = if (fin.main == WallFinish.BRICK || fin.main == WallFinish.STONE) 0f else fin.cornerTrimIn.coerceAtLeast(0f)
            val left = trim
            val right = wallLen - trim
            // A region of the wall: where a level line crosses it, and where a plumb line does.
            class Region(val eMin: Float, val eMax: Float, val xAt: (Float) -> Pair<Float, Float>?, val eAt: (Float) -> Pair<Float, Float>?)
            fun box(e1: Float, e2: Float) = Region(e1, e2, { e -> if (e in e1..e2) left to right else null }, { x -> if (x in left..right) e1 to e2 else null })
            fun hLine(rg: Region, e: Float) { rg.xAt(e)?.let { (a, b) -> minus(a, b, cutsAtE(e)).forEach { (lo, hi) -> line(lo, e, hi, e, Pen.THIN) } } }
            fun vLine(rg: Region, x: Float, e1: Float = -Float.MAX_VALUE, e2: Float = Float.MAX_VALUE) {
                rg.eAt(x)?.let { (a, b) -> minus(max(a, e1), min(b, e2), cutsAtX(x)).forEach { (lo, hi) -> line(x, lo, x, hi, Pen.THIN) } }
            }
            fun pattern(rg: Region, f: WallFinish, spacingIn: Float?) {
                val sp = (spacingIn ?: f.defaultIn).coerceAtLeast(1f)
                when (f) {
                    WallFinish.LAP, WallFinish.BRICK -> { var e = rg.eMin + sp; while (e < rg.eMax - 0.1f) { hLine(rg, e); e += sp } }
                    WallFinish.SHAKE -> {
                        var e = rg.eMin; var row = 0
                        while (e < rg.eMax - 0.1f) {
                            val top = min(e + sp, rg.eMax)
                            if (top < rg.eMax - 0.1f) hLine(rg, top)
                            // Staggered joints, 9" apart, half a shingle over each course.
                            var x = left + (if (row % 2 == 0) 4.5f else 9f)
                            while (x < right - 0.5f) { vLine(rg, x, e, top); x += 9f }
                            e = top; row++
                        }
                    }
                    WallFinish.STONE -> {
                        val heights = listOf(1f, 0.75f, 1.25f, 0.85f, 1.15f)
                        val joints = listOf(14f, 18f, 12f, 16f, 20f)
                        var e = rg.eMin; var row = 0
                        while (e < rg.eMax - 0.1f) {
                            val top = min(e + sp * heights[row % heights.size], rg.eMax)
                            if (top < rg.eMax - 0.1f) hLine(rg, top)
                            var x = left + 4f + (row * 7f) % 13f; var j = row
                            while (x < right - 0.5f) { vLine(rg, x, e, top); x += joints[j % joints.size]; j++ }
                            e = top; row++
                        }
                    }
                    WallFinish.BOARD_BATTEN -> {
                        var x = left + sp / 2f
                        while (x < right - 0.5f) { vLine(rg, x - 1.25f); vLine(rg, x + 1.25f); x += sp }
                    }
                }
            }
            if (trim > 0f) {
                line(left, fTop, left, wallTopEnds, Pen.THIN)
                line(right, fTop, right, wallTopEnds, Pen.THIN)
            }
            var mainBottom = fTop
            fin.wainscot?.let { w ->
                val top = min(fTop + fin.wainscotHeightIn, wallTopEnds)
                pattern(box(fTop, top), w, fin.wainscotIn)
                // The stone sill on top of the wainscot (Dan, Nov 22): a band its height, standing 1" past
                // the corners, stopping at doors and windows like the courses do. With no sill, a cap line.
                val sillTop = min(top + fin.wainscotSillIn.coerceAtLeast(0f), wallTopEnds)
                val reachOut = if (sillTop > top) 1f else 0f
                minus(-reachOut, wallLen + reachOut, cutsAtE(top)).forEach { (lo, hi) -> line(lo, top, hi, top, Pen.MEDIUM) }
                if (sillTop > top) {
                    minus(-reachOut, wallLen + reachOut, cutsAtE(sillTop)).forEach { (lo, hi) -> line(lo, sillTop, hi, sillTop, Pen.MEDIUM) }
                    line(-reachOut, top, -reachOut, sillTop, Pen.MEDIUM)
                    line(wallLen + reachOut, top, wallLen + reachOut, sillTop, Pen.MEDIUM)
                    // Where a door cuts through the sill, the sill returns at the casing.
                    holes.filter { it.top < sillTop && it.bottom > top }.forEach { h ->
                        line(h.left, top, h.left, sillTop, Pen.MEDIUM)
                        line(h.right, top, h.right, sillTop, Pen.MEDIUM)
                    }
                }
                mainBottom = sillTop
            }
            pattern(box(mainBottom, wallTopEnds), fin.main, fin.mainIn)
            gableApexIn?.let { apex ->
                val base = wallTopEnds
                val mid = wallLen / 2f
                if (apex > base) {
                    val gable = Region(base, apex,
                        { e -> if (e in base..apex) { val hw = (apex - e) / (apex - base) * mid; max(left, mid - hw) to min(right, mid + hw) } else null },
                        { x -> if (x in 0f..wallLen) base to (apex - kotlin.math.abs(x - mid) / mid * (apex - base)) else null })
                    val g = fin.gable
                    // A band where the gable's finish starts (clear of any window up in the gable).
                    if (g != null && g != fin.main) minus(0f, wallLen, cutsAtE(base)).forEach { (lo, hi) -> line(lo, base, hi, base, Pen.MEDIUM) }
                    pattern(gable, g ?: fin.main, if (g != null) fin.gableIn else fin.mainIn)
                }
            }
        }

        // Porches on this face or sticking out past it (Porches.elevation, Nov 23).
        val porchLabels = mutableListOf<Label>()
        for (pc in state.elements.filterIsInstance<DrawingElement.Symbol>().filter { Porches.isPorch(it) && of(it).id == Building.FLOOR1_PLAN }) {
            val (ps, pl, masks) = Porches.elevationWithMasks(pc, state, side, fp, k)
            // In front of the house wall it hides the siding and brick behind its columns, beam, roof and deck (Dan, Nov 24).
            for (m in masks) {
                val poly = m.map { Offset(it.first, it.second) }
                val kept = segs.flatMap { sg ->
                    if (sg.pen == Pen.HIDDEN) return@flatMap listOf(sg)
                    Wings.outside(Offset(sg.a.x, -sg.a.y), Offset(sg.b.x, -sg.b.y), poly).map { (a, b) -> Seg(Offset(a.x, -a.y), Offset(b.x, -b.y), sg.pen) }
                }
                segs.clear(); segs += kept
            }
            ps.forEach { line(it.x1, it.e1, it.x2, it.e2, it.pen) }
            pl.forEach { porchLabels += Label(it.text, Offset(it.x - it.text.length * textIn * 0.31f, y(it.e + textIn * 0.5f))) }
        }

        // Garages (Garages, Nov 23): walls on the block course, overhead doors and header, the frost wall below grade, the roof.
        for (ga in state.elements.filterIsInstance<DrawingElement.Symbol>().filter { Garages.isGarage(it) && of(it).id == Building.FLOOR1_PLAN }) {
            val (gs, gl) = Garages.elevation(ga, state, side, fp, k)
            gs.forEach { line(it.x1, it.e1, it.x2, it.e2, it.pen) }
            gl.forEach { porchLabels += Label(it.text, Offset(it.x - it.text.length * textIn * 0.31f, y(it.e + textIn * 0.5f))) }
        }

        // Wings (Wings, Nov 23): looking at a wing's far end it stands in front of the house, so the house lines
        // behind its outline (above grade) are taken out first; then the wing itself.
        for (wg in Wings.wings(state)) {
            val (ws, wl, hull) = Wings.elevation(wg, state, side, fp, k)
            for (outline in hull.orEmpty()) {
                val poly = outline.map { Offset(it.first, it.second) }
                val kept = segs.flatMap { sg ->
                    if (sg.pen == Pen.HIDDEN) return@flatMap listOf(sg)
                    Wings.outside(Offset(sg.a.x, -sg.a.y), Offset(sg.b.x, -sg.b.y), poly).map { (a, b) -> Seg(Offset(a.x, -a.y), Offset(b.x, -b.y), sg.pen) }
                }
                segs.clear(); segs += kept
            }
            ws.forEach { line(it.x1, it.e1, it.x2, it.e2, it.pen) }
            wl.forEach { porchLabels += Label(it.text, Offset(it.x - it.text.length * textIn * 0.31f, y(it.e + textIn * 0.5f))) }
        }

        // Level marks down the right, highest first; a label is pushed down when it would overlap the one above.
        val marks = mutableListOf<Mark>()
        ridgeIn?.let { marks += Mark("RIDGE", it) }
        marks += Mark("TOP OF PLATE", plate)
        s.upperFloors.reversed().forEach { marks += Mark(it.name.uppercase(), it.elevationIn) }
        marks += Mark(if (s.foundation == FoundationType.SLAB) "TOP OF SLAB" else "FIRST FLOOR", s.datumIn)
        if (s.foundation != FoundationType.SLAB) marks += Mark("TOP OF FOUNDATION", fTop)
        marks += Mark("GRADE", grade)
        if (state.elements.any { Porches.isPorch(it) }) marks += Mark("PORCH DECK", Porches.deckTopIn(s))
        val labels = (roofLabels + porchLabels).toMutableList()
        val x0 = wallLen + rightReach + 24f
        var lastTop = Float.MAX_VALUE
        for (m in marks.sortedByDescending { it.elevationIn }) {
            line(x0, m.elevationIn, x0 + 18f, m.elevationIn, Pen.THIN)
            val textTop = min(m.elevationIn + textIn * 1.1f, lastTop - textIn * 0.2f)
            labels += Label("${m.name} ${Building.elevationText(m.elevationIn)}", Offset(x0 + 22f, y(textTop)))
            lastTop = textTop - textIn * 1.1f
        }

        // Extent: every line and label, a little margin.
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rr = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        fun take(p: Offset) { l = min(l, p.x); t = min(t, p.y); rr = max(rr, p.x); b = max(b, p.y) }
        segs.forEach { take(it.a); take(it.b) }
        labels.forEach { lb ->
            take(lb.at)
            take(lb.at + Offset(lb.text.length * textIn * 0.62f, textIn * 1.3f))
        }
        val margin = 12f
        return Geometry(segs, labels, marks, Rect(l - margin, t - margin, rr + margin, b + margin), textIn)
    }

    /** The elevation's size in world px (null with no exterior walls). */
    fun worldSize(state: CanvasState, face: Face): Size? {
        val g = geometry(state, face) ?: return null
        val k = state.inchesToPx(1f)
        return Size(g.extent.width * k, g.extent.height * k)
    }

    /**
     * Where new elevations go: in a row under everything drawn (south, north, east, west, left
     * to right), ten feet clear of it, tops lined up.
     */
    fun spots(state: CanvasState): Map<Face, Offset> {
        val k = state.inchesToPx(1f)
        val rects = state.elements.filter { !isExterior(it) }.map { it.boundingRect() }.filter { it.width > 0f || it.height > 0f }
        val left = rects.minOfOrNull { it.left } ?: 0f
        val top = (rects.maxOfOrNull { it.bottom } ?: 0f) + 120f * k
        var x = left
        val out = mutableMapOf<Face, Offset>()
        for (f in Face.entries) {
            val size = worldSize(state, f) ?: continue
            out[f] = Offset(x + size.width / 2f, top + size.height / 2f)
            x += size.width + 60f * k
        }
        return out
    }

    /** Draws [element]'s elevation true size at its centre, [ink] black on paper or a light canvas. */
    fun DrawScope.drawExterior(element: DrawingElement.Symbol, state: CanvasState, ink: Color) {
        val g = geometry(state, faceOf(element)) ?: return
        val k = state.inchesToPx(1f)
        fun map(p: Offset) = Offset(element.center.x + (p.x - g.extent.center.x) * k, element.center.y + (p.y - g.extent.center.y) * k)
        val heavy = max(k * 1.2f, 1.5f)
        val medium = max(k * 0.7f, 1f)
        val thin = max(k * 0.35f, 0.7f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(k * 6f, k * 4f))
        for (sg in g.segs) {
            val a = map(sg.a)
            val b = map(sg.b)
            when (sg.pen) {
                Pen.HEAVY -> drawLine(ink, a, b, strokeWidth = heavy)
                Pen.MEDIUM -> drawLine(ink, a, b, strokeWidth = medium)
                Pen.THIN -> drawLine(ink, a, b, strokeWidth = thin)
                Pen.HIDDEN -> drawLine(ink, a, b, strokeWidth = thin, pathEffect = dash)
            }
        }
        val size = g.textIn * k
        for (lb in g.labels) {
            drawPageText(lb.text, map(lb.at), size, ink, bold = false, maxWidth = lb.text.length * size * 0.7f + size)
        }
    }
}
