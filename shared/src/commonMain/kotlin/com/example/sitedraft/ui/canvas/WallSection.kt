package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

    private const val SHEATHING_IN = 0.4375f
    private const val SIDING_IN = 0.75f
    private const val DRYWALL_IN = 0.5f
    /** How far into the house the section runs before the break line. */
    private const val RUN_IN = 36f
    /** How much wall is shown above the floor before the break line. */
    private const val WALL_SHOWN_IN = 30f
    /** Notes column, beyond the drawing on each side. */
    private const val NOTE_GAP_IN = 14f

    // --- In model space (Dan, Nov 22): the section is a symbol on the canvas, drawn true size,
    // so it is edited there (its inputs, notes beside it) and sheets show it from there. ---

    /** The symbol that is the section in model space. */
    const val SYMBOL_NAME = "Typical Wall Section"
    const val CATEGORY = "Details"
    /** The section's own scale denominator (16 = 3/4" = 1'-0"): notes are sized for paper at it. */
    const val SCALE_KEY = "scaleDenominator"
    const val DEFAULT_DENOMINATOR = 16

    fun isSection(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY && e.symbolName == SYMBOL_NAME

    fun sectionIn(state: CanvasState): DrawingElement.Symbol? = state.elements.firstOrNull { isSection(it) } as? DrawingElement.Symbol

    fun denominatorOf(e: DrawingElement.Symbol): Int = (e.properties[SCALE_KEY] ?: DEFAULT_DENOMINATOR.toFloat()).toInt().coerceAtLeast(1)

    /** The section's size in world px: its real extent (notes included) at the drawing's scale. */
    fun worldSize(state: CanvasState): androidx.compose.ui.geometry.Size {
        val e = geometry(state).extent
        val k = PrintPage.worldPxPerInch(state)
        return androidx.compose.ui.geometry.Size(e.width * k, e.height * k)
    }

    /** A new section symbol centred at [center]. */
    fun newSymbol(state: CanvasState, center: Offset, denominator: Int = DEFAULT_DENOMINATOR): DrawingElement.Symbol =
        DrawingElement.Symbol(
            symbolName = SYMBOL_NAME, category = CATEGORY, center = center, size = worldSize(state),
            color = Color.White, properties = mapOf(SCALE_KEY to denominator.toFloat())
        )

    /** Where a new section goes: to the right of everything drawn, a few feet clear. */
    fun spotFor(state: CanvasState): Offset {
        val size = worldSize(state)
        val gap = PrintPage.worldPxPerInch(state) * 60f
        val rects = state.elements.map { it.boundingRect() }.filter { it.width > 0f || it.height > 0f }
        if (rects.isEmpty()) return Offset(size.width / 2f, size.height / 2f)
        val right = rects.maxOf { it.right }
        val top = rects.minOf { it.top }
        return Offset(right + gap + size.width / 2f, top + size.height / 2f)
    }

    /** Elements drawn inside the section's frame (notes, dimensions added beside it): they go with it onto a sheet. */
    fun ownedBy(section: DrawingElement.Symbol, state: CanvasState): List<DrawingElement> {
        val frame = Rect(section.center - Offset(section.size.width / 2f, section.size.height / 2f), section.size)
        return state.elements.filter { it.id != section.id && !isSection(it) && frame.contains(it.boundingRect().center) }
    }

    /** The section and what belongs to it, for leaving out of plan views. */
    fun sectionIds(state: CanvasState): Set<String> {
        val out = HashSet<String>()
        state.elements.filterIsInstance<DrawingElement.Symbol>().filter { isSection(it) }.forEach { sec ->
            out += sec.id
            ownedBy(sec, state).forEach { out += it.id }
        }
        return out
    }

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
            it.category == Structure.FRAMING && it.symbolName == Structure.JOIST_BAY && Layers.layerOf(it, state).id == "${Building.FLOOR1}-structural" &&
                !it.id.contains("pcf:")   // a porch's own joists aren't the house's
        }
        if (bay != null) return Structure.joistLabel(bay) to Structure.prop(bay, Structure.SPACING)
        val code = Structure.joistCodeFor(state.building.joistDepthIn)
        return Structure.joistSizes[code].first to 16f
    }

    fun geometry(state: CanvasState): Geometry {
        val s = state.building
        val BLOCK_IN = Building.foundationWallIn(s)   // the foundation wall's thickness (12" block unless picked)
        val parts = mutableListOf<Shower.Part>()
        val notes = mutableListOf<Note>()
        val marks = mutableListOf<Mark>()
        fun y(elev: Float) = -elev
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) =
            parts.add(Shower.Part.Poly(listOf(Offset(x1, y(e1)), Offset(x2, y(e1)), Offset(x2, y(e2)), Offset(x1, y(e2))), true, ink))
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.THIN) =
            parts.add(Shower.Part.Seg(Offset(x1, y(e1)), Offset(x2, y(e2)), ink))
        fun note(text: String, x: Float, e: Float, outside: Boolean) { notes += Note(text, Offset(x, y(e)), outside) }
        // A piece of lumber cut by the section shows its end with an X corner to corner (Dan, Nov 22).
        fun cut(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) {
            rect(x1, e1, x2, e2, ink)
            line(x1, e1, x2, e2); line(x1, e2, x2, e1)
        }

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
            val proj = Building.footingProjections(s).first
            // Footing, centred under the wall unless set (the code rule, Nov 23).
            rect(-proj, fBot, s.footingWidthIn - proj, fTop)
            note("${formatInches(s.footingWidthIn)}\" x ${formatInches(s.footingThicknessIn)}\" CONC. FOOTING", s.footingWidthIn - proj - 2f, (fTop + fBot) / 2f, false)
            // Block wall, a line at every course.
            rect(0f, fTop, BLOCK_IN, wTop)
            if (!s.foundationWall.poured) {
                var c = fTop + s.courseIn
                while (c < wTop - 0.1f && s.courseIn > 0.5f) { line(0f, c, BLOCK_IN, c, Shower.Ink.FAINT); c += s.courseIn }
                note("${s.blockCourses} COURSES ${s.foundationWall.callout}", BLOCK_IN, fTop + (wTop - fTop) * 0.55f, false)
            } else {
                note("${s.foundationWall.callout} WALL, ${Building.elevationText(wTop - fTop)} TALL", BLOCK_IN, fTop + (wTop - fTop) * 0.55f, false)
            }
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
            // Drain tile on both sides of the footing (Dan, Nov 22-23): Form-A-Drain (the forms stay) or
            // perforated pipe in stone. The footing detail ([FootingDetail]) shows it full size.
            val inFace = s.footingWidthIn - proj
            if (s.drainTile == DrainTile.FORM_A_DRAIN) {
                rect(-proj - 1.5f, fBot, -proj, fTop, Shower.Ink.THIN)
                rect(inFace, fBot, inFace + 1.5f, fTop, Shower.Ink.THIN)
                note("FORM-A-DRAIN BOTH SIDES OF FOOTING", -proj - 1.5f, (fTop + fBot) / 2f, true)
            } else {
                for ((tileX, dir) in listOf(-proj - 4f to -1f, inFace + 4f to 1f)) {
                    parts.add(Shower.Part.Ring(Offset(tileX, y(fBot + 2f)), 2f, Shower.Ink.LINE))
                    val a = tileX - dir * 3f; val b = tileX + dir * 6f
                    parts.add(Shower.Part.Poly(listOf(
                        Offset(minOf(a, b), y(fBot)), Offset(maxOf(a, b), y(fBot)), Offset(maxOf(a, b), y(fBot + 8f)), Offset(minOf(a, b), y(fBot + 8f))
                    ), true, Shower.Ink.FAINT_DASHED))
                }
                note("4\" DRAIN TILE IN STONE, BOTH SIDES", -proj - 6f, fBot + 2f, true)
            }
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
            cut(0f, wTop + 0.25f, stud, sillTop)
            note("TREATED ${wallLabel(state)} SILL ON SILL SEAL", stud, wTop + 0.75f, false)
            val bx = stud / 2f
            line(bx, wTop - 7f, bx, sillTop + 1f, Shower.Ink.ACCENT)
            line(bx - 1f, wTop - 7f, bx + 1f, wTop - 7f, Shower.Ink.ACCENT)
            rect(bx - 0.75f, sillTop, bx + 0.75f, sillTop + 0.5f, Shower.Ink.ACCENT)
            note("1/2\" A.B. ${formatInches(s.anchorBoltEndIn)}\" FROM CORNERS, ${formatInches(s.anchorBoltSpacingIn)}\" O.C.", bx, wTop - 4f, false)
            // Rim, joist running into the house, subfloor.
            cut(0f, sillTop, 1.5f, joistTop)
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
        cut(0f, datum, stud, datum + 1.5f)
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
        note("7/16\" OSB SHEATHING", -SHEATHING_IN, datum + WALL_SHOWN_IN * 0.75f, true)
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
    fun DrawScope.drawSection(state: CanvasState, centrePx: Offset, dpi: Float, denominator: Int, ink: Color = Color.Black) {
        val g = geometry(state)
        val k = dpi / denominator
        fun map(o: Offset) = Offset(centrePx.x + (o.x - g.extent.center.x) * k, centrePx.y + (o.y - g.extent.center.y) * k)
        val lineW = max(1f, dpi / 100f)
        val heavy = Stroke(lineW * 1.6f, cap = StrokeCap.Round)
        val thin = Stroke(lineW * 0.8f, cap = StrokeCap.Round)
        val dashed = Stroke(lineW * 0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(lineW * 6f, lineW * 4f)))
        // [ink] is black on paper and on a light canvas, white on the blueprint canvas.
        val black = ink
        val grey = if (ink.luminance() > 0.5f) Color(0xFFAAAAAA) else Color(0xFF888888)
        val accent = if (ink.luminance() > 0.5f) Color(0xFF80E0FF) else Color(0xFF1565C0)
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
        val rightCol = map(Offset(Building.foundationWallIn(state.building) + RUN_IN + NOTE_GAP_IN, 0f)).x
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
