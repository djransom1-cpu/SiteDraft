package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max

/**
 * The typical wall section at the exterior wall (Nov 19 2026), for the inspector: footing to the
 * wall above the floor, every size from the drawing itself so it always matches the plan:
 *  - foundation type, footing (width, thickness, projection past the wall), block courses, sill,
 *    joist depth, subfloor, foam, anchor bolts: the building inputs ([BuildingSettings])
 *  - the wall: the first-floor plan's exterior walls (2x6 or 2x4)
 *  - joist size and spacing: the first-floor joist bay when there is one, otherwise the joist
 *    depth input at 16" o.c.
 * Dan's details: treated sill on sill seal, 1/2" anchor bolts, 2" foam outside the foundation,
 * drain tile at the footing, sheathing, house wrap, siding, cavity insulation, drywall.
 *
 * Geometry is in real inches: x = 0 at the outside face of the foundation wall, positive into the
 * house; y = minus the elevation (so up is up on the page). A sheet view draws it at its scale.
 */
object WallSection {
    /** One labelled item: the point the leader starts at, and which side the text goes. */
    data class Note(val text: String, val at: Offset, val outside: Boolean)
    data class Mark(val text: String, val y: Float)
    data class Geometry(val parts: List<Shower.Part>, val notes: List<Note>, val marks: List<Mark>, val extent: Rect)

    private const val BLOCK_IN = 7.625f
    private const val SHEATHING_IN = 0.4375f
    private const val SIDING_IN = 0.75f
    private const val DRYWALL_IN = 0.5f
    /** How far into the house the section runs before the break line. */
    private const val RUN_IN = 36f
    /** How much wall is shown above the floor before the break line. */
    private const val WALL_SHOWN_IN = 30f
    /** Notes column, beyond the drawing on each side. */
    private const val NOTE_GAP_IN = 14f

    /** Stud depth of the first-floor plan's exterior walls (5 1/2" for 2x6, 3 1/2" for 2x4). */
    fun studDepthIn(state: CanvasState): Float {
        val ext = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.WALLS && it.symbolName.startsWith("2x") && Layers.layerOf(it, state).id == Building.FLOOR1_PLAN
        }
        val name = ext.firstOrNull { it.symbolName == FloorPlan.EXTERIOR_WALL }?.symbolName ?: ext.firstOrNull()?.symbolName ?: FloorPlan.EXTERIOR_WALL
        return if (name.startsWith("2x4")) 3.5f else 5.5f
    }

    fun wallLabel(state: CanvasState): String = if (studDepthIn(state) < 4f) "2x4" else "2x6"

    /** Joist label and spacing: the first-floor bay's when there is one. */
    fun joist(state: CanvasState): Pair<String, Float> {
        val bay = state.elements.filterIsInstance<DrawingElement.Symbol>().firstOrNull {
            it.category == Structure.FRAMING && it.symbolName == Structure.JOIST_BAY && Layers.layerOf(it, state).id == "${Building.FLOOR1}-structural"
        }
        if (bay != null) return Structure.joistLabel(bay) to Structure.prop(bay, Structure.SPACING)
        val code = Structure.joistCodeFor(state.building.joistDepthIn)
        return Structure.joistSizes[code].first to 16f
    }

    fun geometry(state: CanvasState): Geometry {
        val s = state.building
        val parts = mutableListOf<Shower.Part>()
        val notes = mutableListOf<Note>()
        val marks = mutableListOf<Mark>()
        fun y(elev: Float) = -elev
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) =
            parts.add(Shower.Part.Poly(listOf(Offset(x1, y(e1)), Offset(x2, y(e1)), Offset(x2, y(e2)), Offset(x1, y(e2))), true, ink))
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.THIN) =
            parts.add(Shower.Part.Seg(Offset(x1, y(e1)), Offset(x2, y(e2)), ink))
        fun note(text: String, x: Float, e: Float, outside: Boolean) { notes += Note(text, Offset(x, y(e)), outside) }

        val stud = studDepthIn(state)
        val (joistLabel, joistOc) = joist(state)
        val datum = s.datumIn
        val subTop = datum
        val joistTop = datum - s.subfloorIn
        val sillTop = Building.sillTopIn(s)
        val slab = s.foundation == FoundationType.SLAB
        val run = BLOCK_IN + RUN_IN
        val wallTop = datum + WALL_SHOWN_IN

        if (!slab) {
            val fTop = Building.footingTopIn(s)
            val fBot = Building.footingBottomIn(s)
            val wTop = Building.foundationTopIn(s)
            val proj = s.footingProjectionIn
            // Footing, 6" (or as set) past the outside face.
            rect(-proj, fBot, s.footingWidthIn - proj, fTop)
            note("${formatInches(s.footingWidthIn)}\" x ${formatInches(s.footingThicknessIn)}\" CONC. FOOTING", s.footingWidthIn - proj - 2f, (fTop + fBot) / 2f, false)
            // Block wall, a line at every course.
            rect(0f, fTop, BLOCK_IN, wTop)
            var c = fTop + s.courseIn
            while (c < wTop - 0.1f && s.courseIn > 0.5f) { line(0f, c, BLOCK_IN, c, Shower.Ink.FAINT); c += s.courseIn }
            note("${s.blockCourses} COURSES 8\" BLOCK", BLOCK_IN, fTop + (wTop - fTop) * 0.55f, false)
            // Foam outside, full height of the block.
            if (s.foundationFoamIn > 0f) {
                val f = s.foundationFoamIn
                rect(-f, fTop, 0f, wTop, Shower.Ink.THIN)
                var e = fTop
                var left = true
                while (e < wTop - 1f && f > 0.25f) {
                    val e2 = minOf(e + f, wTop)
                    line(if (left) -f else 0f, e, if (left) 0f else -f, e2, Shower.Ink.FAINT)
                    left = !left; e = e2
                }
                note("${formatInches(f)}\" RIGID FOAM", -f, fTop + (wTop - fTop) * 0.7f, true)
            }
            // Drain tile beside the footing, outside, on its bottom.
            val tileX = -proj - 4f
            parts.add(Shower.Part.Ring(Offset(tileX, y(fBot + 2f)), 2f, Shower.Ink.LINE))
            note("4\" DRAIN TILE IN STONE", tileX - 2f, fBot + 2f, true)
            parts.add(Shower.Part.Poly(listOf(
                Offset(tileX - 6f, y(fBot)), Offset(tileX + 3f, y(fBot)), Offset(tileX + 3f, y(fBot + 8f)), Offset(tileX - 6f, y(fBot + 8f))
            ), true, Shower.Ink.FAINT_DASHED))
            // Grade outside, 8" below the top of the foundation.
            val grade = wTop - 8f
            line(-NOTE_GAP_IN - 6f, grade, -s.foundationFoamIn, grade, Shower.Ink.LINE)
            note("GRADE (8\" MIN. TO SILL)", -NOTE_GAP_IN, grade, true)
            // Basement floor: 4" slab on the footing; crawl space: the crawl floor line.
            if (s.foundation == FoundationType.BASEMENT) {
                rect(BLOCK_IN, fTop, run, fTop + 4f, Shower.Ink.THIN)
                note("4\" CONC. SLAB", run - 4f, fTop + 2f, false)
            } else {
                val crawl = sillTop - s.crawlClearanceIn
                line(BLOCK_IN, crawl, run, crawl, Shower.Ink.FAINT_DASHED)
                note("CRAWL FLOOR, ${formatInches(s.crawlClearanceIn)}\" CLEAR", run - 4f, crawl, false)
            }
            // Sill seal, treated sill (as wide as the studs), anchor bolt.
            line(0f, wTop + 0.15f, stud, wTop + 0.15f, Shower.Ink.ACCENT)
            rect(0f, wTop + 0.25f, stud, sillTop)
            note("TREATED ${wallLabel(state)} SILL ON SILL SEAL", stud, wTop + 0.75f, false)
            val bx = stud / 2f
            line(bx, wTop - 7f, bx, sillTop + 1f, Shower.Ink.ACCENT)
            line(bx - 1f, wTop - 7f, bx + 1f, wTop - 7f, Shower.Ink.ACCENT)
            rect(bx - 0.75f, sillTop, bx + 0.75f, sillTop + 0.5f, Shower.Ink.ACCENT)
            note("1/2\" A.B. ${formatInches(s.anchorBoltEndIn)}\" FROM CORNERS, ${formatInches(s.anchorBoltSpacingIn)}\" O.C.", bx, wTop - 4f, false)
            // Rim, joist running into the house, subfloor.
            rect(0f, sillTop, 1.5f, joistTop)
            note("RIM JOIST", 0.75f, sillTop + (joistTop - sillTop) * 0.8f, false)
            rect(1.5f, sillTop, run, joistTop, Shower.Ink.THIN)
            note("$joistLabel FLOOR JOISTS @ ${formatInches(joistOc)}\" O.C.", run - 6f, (sillTop + joistTop) / 2f, false)
            marks += Mark("T/FOOTING", fTop)
            marks += Mark("B/FOOTING", fBot)
            marks += Mark("T/FOUNDATION", wTop)
            marks += Mark("T/SILL", sillTop)
        } else {
            // Slab on grade: thickened edge under the wall, slab inside.
            val edge = datum - 12f
            rect(-s.footingProjectionIn, edge - s.footingThicknessIn, 18f, datum - 4f)
            rect(0f, datum - 4f, run, datum, Shower.Ink.THIN)
            note("4\" CONC. SLAB, THICKENED EDGE", run - 4f, datum - 2f, false)
            marks += Mark("T/SLAB", datum)
        }
        // Subfloor, then the wall on it.
        if (!slab) {
            rect(0f, joistTop, run, subTop, Shower.Ink.THIN)
            note("${formatInches(s.subfloorIn)}\" SUBFLOOR", run - 2f, (joistTop + subTop) / 2f, false)
        }
        rect(0f, datum, stud, datum + 1.5f)
        note("${wallLabel(state)} PLATE", stud, datum + 0.75f, false)
        line(0f, datum + 1.5f, 0f, wallTop, Shower.Ink.LINE)
        line(stud, datum + 1.5f, stud, wallTop, Shower.Ink.LINE)
        // Cavity insulation: a zigzag between the faces.
        var e = datum + 2f
        var toInside = true
        while (e < wallTop - 2f) {
            val e2 = minOf(e + stud * 0.6f, wallTop - 1f)
            line(if (toInside) 0.3f else stud - 0.3f, e, if (toInside) stud - 0.3f else 0.3f, e2, Shower.Ink.FAINT)
            toInside = !toInside; e = e2
        }
        note("${wallLabel(state)} STUDS @ 16\" O.C., INSULATION IN CAVITY", stud, datum + WALL_SHOWN_IN * 0.6f, false)
        // Drywall inside.
        rect(stud, datum + 1.5f, stud + DRYWALL_IN, wallTop, Shower.Ink.THIN)
        note("1/2\" DRYWALL", stud + DRYWALL_IN, datum + WALL_SHOWN_IN * 0.3f, false)
        // Outside: sheathing from the sill (covering the rim) up, wrap, siding.
        val sheathBottom = if (slab) datum else sillTop - 0.5f
        rect(-SHEATHING_IN, sheathBottom, 0f, wallTop, Shower.Ink.THIN)
        note("7/16\" SHEATHING", -SHEATHING_IN, datum + WALL_SHOWN_IN * 0.75f, true)
        line(-SHEATHING_IN - 0.15f, sheathBottom + 1f, -SHEATHING_IN - 0.15f, wallTop, Shower.Ink.DASHED)
        note("HOUSE WRAP", -SHEATHING_IN - 0.15f, datum + WALL_SHOWN_IN * 0.5f, true)
        val sx = -SHEATHING_IN - 0.3f
        var lap = sheathBottom + 1.5f
        // Laps 6" tall, each overlapping the one below by 1". Stops at the top of the wall shown
        // (a lap that reaches it is the last: stepping down 1" from it would loop forever).
        var laps = 0
        while (lap < wallTop - 0.5f && laps < 200) {
            val top = minOf(lap + 6f, wallTop)
            parts.add(Shower.Part.Poly(listOf(Offset(sx, y(lap + 1f)), Offset(sx - SIDING_IN, y(lap)), Offset(sx - SIDING_IN * 0.3f, y(top)), Offset(sx, y(top))), false, Shower.Ink.THIN))
            if (top >= wallTop) break
            lap = top - 1f
            laps++
        }
        note("SIDING", sx - SIDING_IN, datum + WALL_SHOWN_IN * 0.25f, true)
        marks += Mark(if (slab) "T/SLAB" else "T/SUBFLOOR", datum)

        // Break lines: across the floor at the right, across the wall at the top.
        fun breakV(x: Float, e1: Float, e2: Float) {
            val m = (e1 + e2) / 2f
            parts.add(Shower.Part.Poly(listOf(
                Offset(x, y(e1)), Offset(x, y(m - 1.5f)), Offset(x + 1.5f, y(m - 0.5f)), Offset(x - 1.5f, y(m + 0.5f)), Offset(x, y(m + 1.5f)), Offset(x, y(e2))
            ), false, Shower.Ink.THIN))
        }
        val floorBottom = if (slab) datum - 4f else Building.footingTopIn(s)
        breakV(run + 1f, floorBottom - 2f, datum + 2f)
        val bx1 = -SHEATHING_IN - SIDING_IN - 2f
        val bx2 = stud + DRYWALL_IN + 2f
        val bm = (bx1 + bx2) / 2f
        parts.add(Shower.Part.Poly(listOf(
            Offset(bx1, y(wallTop)), Offset(bm - 1.5f, y(wallTop)), Offset(bm - 0.5f, y(wallTop + 1.5f)), Offset(bm + 0.5f, y(wallTop - 1.5f)), Offset(bm + 1.5f, y(wallTop)), Offset(bx2, y(wallTop))
        ), false, Shower.Ink.THIN))

        val bottom = if (slab) datum - 12f - s.footingThicknessIn else Building.footingBottomIn(s)
        val extent = Rect(-NOTE_GAP_IN - 52f, y(wallTop + 4f), run + NOTE_GAP_IN + 34f, y(bottom - 4f))
        return Geometry(parts, notes, marks.distinctBy { it.text }, extent)
    }

    /** The drawing's size on paper at [denominator] (inches). */
    fun paperSizeIn(state: CanvasState, denominator: Int): Pair<Float, Float> {
        val e = geometry(state).extent
        return e.width / denominator to e.height / denominator
    }

    /**
     * Draws the section with its centre at [centrePx] on the page, at [dpi] page px per paper inch
     * and [denominator] (12 = 1" = 1'). Notes are the same size on paper at any scale.
     */
    fun DrawScope.drawSection(state: CanvasState, centrePx: Offset, dpi: Float, denominator: Int) {
        val g = geometry(state)
        val k = dpi / denominator
        fun map(o: Offset) = Offset(centrePx.x + (o.x - g.extent.center.x) * k, centrePx.y + (o.y - g.extent.center.y) * k)
        val lineW = max(1f, dpi / 100f)
        val heavy = Stroke(lineW * 1.6f, cap = StrokeCap.Round)
        val thin = Stroke(lineW * 0.8f, cap = StrokeCap.Round)
        val dashed = Stroke(lineW * 0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(lineW * 6f, lineW * 4f)))
        val black = Color.Black
        val grey = Color(0xFF888888)
        val accent = Color(0xFF1565C0)
        fun color(ink: Shower.Ink) = when (ink) {
            Shower.Ink.FAINT, Shower.Ink.FAINT_DASHED -> grey
            Shower.Ink.ACCENT -> accent
            else -> black
        }
        fun stroke(ink: Shower.Ink) = when (ink) {
            Shower.Ink.LINE -> heavy
            Shower.Ink.DASHED, Shower.Ink.FAINT_DASHED -> dashed
            else -> thin
        }
        for (p in g.parts) when (p) {
            is Shower.Part.Seg -> drawLine(color(p.ink), map(p.a), map(p.b), strokeWidth = stroke(p.ink).width,
                pathEffect = stroke(p.ink).pathEffect)
            is Shower.Part.Poly -> drawPath(Path().apply {
                val f = map(p.points[0]); moveTo(f.x, f.y); p.points.drop(1).forEach { val q = map(it); lineTo(q.x, q.y) }; if (p.closed) close()
            }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Ring -> drawCircle(color(p.ink), radius = p.r * k, center = map(p.c), style = stroke(p.ink))
            is Shower.Part.Label -> drawPageText(p.text, map(p.c), p.size * k, color(p.ink), bold = false, maxWidth = 4f * dpi)
        }
        // Notes: a leader from the part to a column outside the drawing, text beyond it. Stacked
        // so no two overlap.
        val text = 0.09f * dpi
        val rowGap = text * 1.5f
        val inside = g.notes.filter { !it.outside }.sortedBy { it.at.y }
        val outside = g.notes.filter { it.outside }.sortedBy { it.at.y }
        val rightCol = map(Offset(BLOCK_IN + RUN_IN + NOTE_GAP_IN, 0f)).x
        val leftCol = map(Offset(-NOTE_GAP_IN - 4f, 0f)).x
        fun place(list: List<Note>, colX: Float, onRight: Boolean) {
            var lastY = Float.NEGATIVE_INFINITY
            for (n in list) {
                val a = map(n.at)
                val ty = max(a.y, lastY + rowGap)
                lastY = ty
                val elbow = Offset(colX, ty)
                drawLine(black, a, Offset(a.x + (colX - a.x) * 0.7f, ty), strokeWidth = lineW * 0.6f)
                drawLine(black, Offset(a.x + (colX - a.x) * 0.7f, ty), elbow, strokeWidth = lineW * 0.6f)
                drawCircle(black, radius = lineW * 1.4f, center = a)
                val w = text * 0.58f * n.text.length
                val tx = if (onRight) colX + text * 0.4f else colX - text * 0.4f - w
                drawPageText(n.text, Offset(tx, ty - text * 0.65f), text, black, bold = false, maxWidth = w + text * 2f)
            }
        }
        place(inside, rightCol, true)
        place(outside, leftCol, false)
        // Elevation marks down the far left.
        val markX = map(Offset(g.extent.left, 0f)).x
        for (m in g.marks) {
            val my = map(Offset(0f, -m.y)).y
            drawLine(black, Offset(markX, my), Offset(map(Offset(-SHEATHING_IN - SIDING_IN, 0f)).x, my), strokeWidth = lineW * 0.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(lineW * 3f, lineW * 3f)))
            drawPageText("${m.text} ${Building.elevationText(m.y)}", Offset(markX, my - text * 1.3f), text * 0.9f, black, bold = true, maxWidth = 3f * dpi)
        }
    }
}
