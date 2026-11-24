package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The roof framing plan (Dan, Nov 22 2026), on the Roof structural layer, built live from the
 * first-floor exterior walls like the foundation is: move a wall and the roof follows. One symbol
 * stands for the whole roof (its frame is the eave line, so it can be picked); what it draws comes
 * from the building's [RoofSettings], edited on the Roof layer or by selecting the roof.
 *
 * Plan geometry for a rectangular footprint (L shapes later): the eave line the overhang past the
 * outside of the walls (the rake overhang at a gable end), the ridge down the middle the long way,
 * a hip end's ridge stopping half the span in from the wall (Dan's rule, as ProCalc's RoofEngine:
 * a hip takes half the span off the ridge) with hips at 45 deg from the eave corners, and the
 * trusses or rafters at their spacing from the end walls, the ones in a hip end cut off by the hip
 * (jacks). The numbers (run, rise, rafter and hip lengths, ridge length, count, sheathing) are
 * ProCalc's engines, unchanged.
 */
object RoofPlan {
    const val CATEGORY = "Roof"
    const val SYMBOL = "Roof"
    const val ID = "roof:main"
    const val LAYER = "roof-structural"

    fun isRoof(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY && e.symbolName == SYMBOL

    /** The roof plan (finish) on its own layer for the A sheets: the same roof, drawn with its roofing. */
    const val FINISH_SYMBOL = "Roof Plan"
    const val FINISH_ID = "roof:finish"
    const val FINISH_LAYER = "roof-plan"
    /** The roof plan draws the roofing pattern on each plane: off (Dan, Nov 22). */
    private const val SHOW_ROOFING_PATTERN = false

    fun isRoofFinish(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY && e.symbolName == FINISH_SYMBOL

    /** The outside of the first-floor exterior walls, world px; null when the plan has none. */
    fun footprint(state: CanvasState): Rect? {
        val of = Layers.resolver(state)
        val walls = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.WALLS && it.symbolName == FloorPlan.EXTERIOR_WALL && of(it).id == Building.FLOOR1_PLAN && !DerivedLayers.isGenerated(it)
        }
        if (walls.isEmpty()) return null
        return walls.map { FloorPlan.bounds(it) }.reduce { a, r -> Rect(min(a.left, r.left), min(a.top, r.top), max(a.right, r.right), max(a.bottom, r.bottom)) }
    }

    /** A line of the roof plan, world px, and how it is drawn. */
    data class Line(val a: Offset, val b: Offset, val kind: Kind)
    enum class Kind { EAVE, RIDGE, HIP, MEMBER, WALL }

    data class Geometry(
        val eave: Rect,
        val footprint: Rect,
        /** The ridge runs left-right on the page (else up-down). */
        val ridgeAcross: Boolean,
        val lines: List<Line>,
        /** Building width across the ridge and length along it, inches. */
        val spanIn: Float,
        val lengthIn: Float,
        val ridgeStart: Offset,
        val ridgeEnd: Offset
    )

    fun geometry(state: CanvasState, r: RoofSettings): Geometry? {
        val fp = footprint(state) ?: return null
        val k = state.inchesToPx(1f)
        if (fp.width <= 0f || fp.height <= 0f) return null
        val across = (fp.width >= fp.height) != r.turnRidge
        // Work along the ridge (u) and across it (v); swap back to the page at the end.
        val L = if (across) fp else Rect(fp.top, fp.left, fp.bottom, fp.right)
        fun page(u: Float, v: Float) = if (across) Offset(u, v) else Offset(v, u)
        val ov = r.overhangIn * k
        val endOv = { e: RoofEnd -> if (e == RoofEnd.HIP) ov else r.rakeIn * k }
        val eu1 = L.left - endOv(r.endA)
        val eu2 = L.right + endOv(r.endB)
        val ev1 = L.top - ov
        val ev2 = L.bottom + ov
        val span = L.height
        val vc = L.center.y
        var ru1 = if (r.endA == RoofEnd.HIP) L.left + span / 2f else eu1
        var ru2 = if (r.endB == RoofEnd.HIP) L.right - span / 2f else eu2
        if (ru1 > ru2) { val m = (ru1 + ru2) / 2f; ru1 = m; ru2 = m }
        val lines = mutableListOf<Line>()
        fun line(u1: Float, v1: Float, u2: Float, v2: Float, kind: Kind) { lines += Line(page(u1, v1), page(u2, v2), kind) }
        // Eave line and the walls under it (dashed).
        line(eu1, ev1, eu2, ev1, Kind.EAVE); line(eu2, ev1, eu2, ev2, Kind.EAVE)
        line(eu2, ev2, eu1, ev2, Kind.EAVE); line(eu1, ev2, eu1, ev1, Kind.EAVE)
        line(L.left, L.top, L.right, L.top, Kind.WALL); line(L.right, L.top, L.right, L.bottom, Kind.WALL)
        line(L.right, L.bottom, L.left, L.bottom, Kind.WALL); line(L.left, L.bottom, L.left, L.top, Kind.WALL)
        line(ru1, vc, ru2, vc, Kind.RIDGE)
        if (r.endA == RoofEnd.HIP) { line(eu1, ev1, ru1, vc, Kind.HIP); line(eu1, ev2, ru1, vc, Kind.HIP) }
        if (r.endB == RoofEnd.HIP) { line(eu2, ev1, ru2, vc, Kind.HIP); line(eu2, ev2, ru2, vc, Kind.HIP) }
        // Trusses or rafters at their spacing from the end wall, eave to ridge on each side; in a hip
        // end they stop at the hip (jacks), which runs at 45 deg from the eave corner.
        val oc = max(r.ocIn, 4f) * k
        val count = ((L.width) / oc).toInt()
        val positions = (0..count).map { L.left + it * oc } + (if (L.left + count * oc < L.right - k) listOf(L.right) else emptyList())
        for (u in positions) {
            // How far in from each eave the hip reaches at this point (0 outside a hip end).
            val inA = if (r.endA == RoofEnd.HIP && u < ru1) (u - eu1) else Float.MAX_VALUE
            val inB = if (r.endB == RoofEnd.HIP && u > ru2) (eu2 - u) else Float.MAX_VALUE
            val reach = min(min(inA, inB), vc - ev1)
            line(u, ev1, u, ev1 + reach, Kind.MEMBER)
            line(u, ev2, u, ev2 - reach, Kind.MEMBER)
        }
        // A hip end's own jacks (Dan, Nov 22): square to the end wall, from its eave to the hip, laid
        // out from the king common in the middle (which runs to the end of the ridge) at the spacing.
        val endPositions = buildList {
            add(vc)
            var i = 1
            while (vc - i * oc >= L.top - 0.01f) { add(vc - i * oc); add(vc + i * oc); i++ }
        }.filter { it >= L.top - 0.01f && it <= L.bottom + 0.01f }
        for (v in endPositions) {
            val reach = min(v - ev1, ev2 - v)
            if (r.endA == RoofEnd.HIP) line(eu1, v, eu1 + min(reach, ru1 - eu1), v, Kind.MEMBER)
            if (r.endB == RoofEnd.HIP) line(eu2, v, eu2 - min(reach, eu2 - ru2), v, Kind.MEMBER)
        }
        val ridgeStart = page(ru1, vc)
        val ridgeEnd = page(ru2, vc)
        val eave = if (across) Rect(eu1, ev1, eu2, ev2) else Rect(ev1, eu1, ev2, eu2)
        return Geometry(eave, fp, across, lines, span / k, L.width / k, ridgeStart, ridgeEnd)
    }

    /** ProCalc's numbers for this roof: rafter (or top chord) and hip cuts, ridge, count, sheathing. */
    data class Numbers(val rafter: RafterEngine.RafterResult, val roof: RoofEngine.RoofResult)

    fun numbers(state: CanvasState, r: RoofSettings): Numbers? {
        val g = geometry(state, r) ?: return null
        val rafter = RafterEngine.calculate(span = g.spanIn.toDouble(), pitchVal = r.pitch.toDouble(), heel = r.heelIn.toDouble(), overhang = r.overhangIn.toDouble())
        val end = { e: RoofEnd -> if (e == RoofEnd.HIP) 1 else 0 }
        val roof = RoofEngine.calculate(
            span = g.spanIn.toDouble(), bldgLen = g.lengthIn.toDouble(), overhang = r.overhangIn.toDouble(), pitch = r.pitch.toDouble(),
            oc = r.ocIn.toDouble(), leftEndType = end(r.endA), rightEndType = end(r.endB), isTruss = r.framing == RoofFraming.TRUSSES, bothSides = true
        )
        return Numbers(rafter, roof)
    }

    /** Inches as plans write them, to the nearest 1/16". */
    private fun ftIn(inches: Double): String = Building.elevationText((inches * 16.0).roundToInt() / 16f)

    /** The roof's numbers as lines for the Roof panel. */
    fun summary(state: CanvasState, r: RoofSettings): List<String> {
        val n = numbers(state, r) ?: return listOf("Draw the first-floor exterior (2x6) walls first: the roof is built over them.")
        val g = geometry(state, r)!!
        val member = if (r.framing == RoofFraming.TRUSSES) "Trusses" else "${r.rafterSize} rafters"
        return listOfNotNull(
            "Span ${ftIn(g.spanIn.toDouble())}, ridge length ${ftIn(n.roof.correctedRidgeLen)}",
            "${pitchText(r.pitch)} pitch: run ${ftIn(n.rafter.run)}, rise ${ftIn(n.rafter.rise)}, ${ftIn(n.rafter.totRise)} with the heel",
            "$member @ ${formatInches(r.ocIn)}\" o.c.: ${n.roof.commonCount} " + (if (r.framing == RoofFraming.TRUSSES) "trusses" else "commons"),
            "Rafter to wall ${ftIn(n.rafter.rafterLen)}, tail ${ftIn(n.rafter.tailLen)}, overall ${ftIn(n.rafter.overall)}",
            if (n.roof.totalHips > 0) "Hips: ${n.roof.totalHips} at ${ftIn(n.rafter.hipOverall)} overall (hip pitch ${pitchText(n.rafter.hipPitch.toFloat())})" else null,
            "Sheathing about ${n.roof.totalSqFt.roundToInt()} sq ft, ${n.roof.sheetCount} sheets; sub-fascia ${(n.roof.totalSubFasciaLF / 12.0).roundToInt()} lf",
            "Roofing: ${r.covering.label}, about ${kotlin.math.ceil(n.roof.totalSqFt / 100.0).toInt()} squares (same area as the sheathing, no waste)"
        )
    }

    /** Porch roofs (Porches.roof), on the framing plan with their rafters, on the roof plan as outline, ridge and hips. */
    private fun DrawScope.drawPorchRoofs(state: CanvasState, ink: Color, finish: Boolean) {
        val k = state.inchesToPx(1f).takeIf { it > 0f } ?: return
        val heavy = max(k * 1.0f, 1.5f)
        val thin = max(k * 0.4f, 0.8f)
        val faint = if (ink.luminance() > 0.5f) Color(0xFF9AA4B0) else Color(0xFF8A8A8A)
        val dash = PathEffect.dashPathEffect(floatArrayOf(k * 8f, k * 5f))
        val text = k * 6f
        fun label(t: String, at: Offset) {
            val w = text * 0.6f * t.length
            drawPageText(t, Offset(at.x - w / 2f, at.y - text * 0.6f), text, ink, bold = true, maxWidth = w + text * 2f)
        }
        for (p in state.elements.filterIsInstance<DrawingElement.Symbol>().filter { Porches.isPorch(it) }) {
            val d = Porches.roof(p, state) ?: continue
            for (l in d.lines) when (l.kind) {
                Kind.EAVE, Kind.RIDGE, Kind.HIP -> drawLine(ink, l.a, l.b, strokeWidth = heavy)
                Kind.MEMBER -> if (!finish) drawLine(ink, l.a, l.b, strokeWidth = thin)
                Kind.WALL -> drawLine(faint, l.a, l.b, strokeWidth = thin, pathEffect = dash)
            }
            val pitch = pitchText(Porches.pitchOf(p, state.building))
            d.pitchAt.forEach { label(pitch, it) }
            d.shedArrow?.let { (a, b) ->
                drawLine(ink, a, b, strokeWidth = thin)
                val dir = (b - a) / max((b - a).getDistance(), 0.001f)
                val n = Offset(-dir.y, dir.x)
                drawLine(ink, b, b - dir * text + n * text * 0.5f, strokeWidth = thin)
                drawLine(ink, b, b - dir * text - n * text * 0.5f, strokeWidth = thin)
            }
            val style = Porches.roofStyles[Porches.prop(p, Porches.ROOF_STYLE).coerceIn(0, 2)].uppercase()
            label(if (finish) "PORCH $style ROOF" else "PORCH $style ROOF, RAFTERS @ ${formatInches(state.building.roof?.ocIn ?: 24f)}\" O.C.", d.labelAt)
        }
        // Garage roofs (Garages.roof3d): the same lines, flattened onto the plan.
        for (gr in state.elements.filterIsInstance<DrawingElement.Symbol>().filter { Garages.isGarage(it) }) {
            val segs = Garages.roof3d(gr, state)
            if (segs.isEmpty()) continue
            for (l in segs) when (l.kind) {
                Kind.EAVE, Kind.RIDGE, Kind.HIP -> drawLine(ink, l.a, l.b, strokeWidth = heavy)
                Kind.MEMBER -> if (!finish) drawLine(ink, l.a, l.b, strokeWidth = thin)
                Kind.WALL -> drawLine(faint, l.a, l.b, strokeWidth = thin, pathEffect = dash)
            }
            val c = gr.boundingRect().center
            val p = pitchText(state.building.roof?.pitch ?: 6f)
            label(if (finish) "GARAGE ROOF $p" else "GARAGE TRUSSES @ ${formatInches(state.building.roof?.ocIn ?: 24f)}\" O.C., $p", c)
        }
        // Wing roofs (Wings.roof3d): tied into the house roof with valleys.
        for (wr in Wings.wings(state)) {
            val segs = Wings.roof3d(wr, state)
            if (segs.isEmpty()) continue
            for (l in segs) when (l.kind) {
                Kind.EAVE, Kind.RIDGE, Kind.HIP -> drawLine(ink, l.a, l.b, strokeWidth = heavy)
                Kind.MEMBER -> if (!finish) drawLine(ink, l.a, l.b, strokeWidth = thin)
                Kind.WALL -> drawLine(faint, l.a, l.b, strokeWidth = thin, pathEffect = dash)
            }
            val p = pitchText(state.building.roof?.pitch ?: 6f)
            label(if (finish) "WING ROOF $p" else "WING TRUSSES @ ${formatInches(state.building.roof?.ocIn ?: 24f)}\" O.C., $p", wr.boundingRect().center)
        }
    }

    /** The garages', wings' and porches' boxes past their walls by the overhang: what their roofs on the roof plan cover. */
    fun addOnBounds(state: CanvasState): List<Rect> {
        val k = state.inchesToPx(1f).takeIf { it > 0f } ?: return emptyList()
        val ov = maxOf(state.building.roof?.overhangIn ?: 12f, state.building.roof?.rakeIn ?: 12f) * k + 6f * k
        return state.elements.filterIsInstance<DrawingElement.Symbol>()
            .filter { (Garages.isGarage(it) || Wings.isWing(it) || Porches.isPorch(it)) && !DerivedLayers.isGenerated(it) }
            .map { it.boundingRect().inflate(ov) }
    }

    fun pitchText(pitch: Float): String {
        val p = (pitch * 4f).roundToInt() / 4f
        return (if (p == p.toInt().toFloat()) "${p.toInt()}" else formatInches(p)) + "/12"
    }

    /** Draws the roof plan for the building's roof settings, [ink] black on paper or a light canvas, white on blueprint. */
    fun DrawScope.drawRoof(state: CanvasState, ink: Color) {
        drawPorchRoofs(state, ink, finish = false)
        val r = state.building.roof ?: return
        val g = geometry(state, r) ?: return
        val k = state.inchesToPx(1f)
        val heavy = max(k * 1.0f, 1.5f)
        val thin = max(k * 0.4f, 0.8f)
        val faint = if (ink.luminance() > 0.5f) Color(0xFF9AA4B0) else Color(0xFF8A8A8A)
        val dash = PathEffect.dashPathEffect(floatArrayOf(k * 8f, k * 5f))
        for (l in Wings.trimHouseRoof(g.lines, state)) when (l.kind) {
            Kind.EAVE, Kind.RIDGE, Kind.HIP -> drawLine(ink, l.a, l.b, strokeWidth = heavy)
            Kind.MEMBER -> drawLine(ink, l.a, l.b, strokeWidth = thin)
            Kind.WALL -> drawLine(faint, l.a, l.b, strokeWidth = thin, pathEffect = dash)
        }
        // Labels: what the members are, the pitch on each side, and the ridge.
        val text = k * 7f
        val member = if (r.framing == RoofFraming.TRUSSES) "TRUSSES @ ${formatInches(r.ocIn)}\" O.C." else "${r.rafterSize} RAFTERS @ ${formatInches(r.ocIn)}\" O.C."
        val c = g.footprint.center
        fun label(t: String, at: Offset, size: Float = text) {
            val w = size * 0.6f * t.length
            drawPageText(t, Offset(at.x - w / 2f, at.y - size * 0.6f), size, ink, bold = true, maxWidth = w + size * 2f)
        }
        val off = if (g.ridgeAcross) Offset(0f, g.footprint.height / 4f) else Offset(g.footprint.width / 4f, 0f)
        label(member, c + off * 0.5f)
        label(pitchText(r.pitch), c - off)
        label(pitchText(r.pitch), c + off)
        val ridgeMid = (g.ridgeStart + g.ridgeEnd) / 2f
        label("RIDGE", ridgeMid - (if (g.ridgeAcross) Offset(0f, text * 1.2f) else Offset(text * 2.5f, 0f)), text * 0.8f)
    }

    /**
     * The roof plan (finish), [ink] black on paper: the eave and rake line, ridge and hips, the
     * roofing on each plane (courses parallel to that plane's eave at the exposure as seen from
     * above, or seams and ribs down the slope), the pitch on each plane and the roofing named.
     */
    fun DrawScope.drawRoofFinish(state: CanvasState, ink: Color) {
        drawPorchRoofs(state, ink, finish = true)
        val r = state.building.roof ?: return
        val g = geometry(state, r) ?: return
        val k = state.inchesToPx(1f)
        val heavy = max(k * 1.0f, 1.5f)
        val thin = max(k * 0.3f, 0.6f)
        // Work along the ridge (u) and across it (v), as the framing does.
        val across = g.ridgeAcross
        fun page(u: Float, v: Float) = if (across) Offset(u, v) else Offset(v, u)
        val e = g.eave
        val eu1 = if (across) e.left else e.top
        val eu2 = if (across) e.right else e.bottom
        val ev1 = if (across) e.top else e.left
        val ev2 = if (across) e.bottom else e.right
        val ru1 = if (across) g.ridgeStart.x else g.ridgeStart.y
        val ru2 = if (across) g.ridgeEnd.x else g.ridgeEnd.y
        val vc = if (across) g.ridgeStart.y else g.ridgeStart.x
        fun seg(u1: Float, v1: Float, u2: Float, v2: Float, w: Float) = drawLine(ink, page(u1, v1), page(u2, v2), strokeWidth = w)

        val rc = r.covering
        val spacing = (r.coveringIn ?: rc.defaultIn).coerceAtLeast(1f)
        // A course's exposure is measured up the slope; seen from above it is shorter by the pitch.
        val planCourse = spacing * k * 12f / kotlin.math.sqrt(144f + r.pitch * r.pitch)
        val down = (if (rc.courses) rc.barrelIn else spacing) * k

        /**
         * Hatches one plane, [poly] its outline (u, v). [alongU]: its eave runs along u (a side
         * plane), else along v (a hip end). Courses run parallel to the eave from [eaveAt] toward
         * [dir]; seams and barrels run square to it.
         */
        fun plane(poly: List<Pair<Float, Float>>, alongU: Boolean, eaveAt: Float, dir: Float) {
            fun cut(fixedIsV: Boolean, c: Float): Pair<Float, Float>? {
                val hits = mutableListOf<Float>()
                for (i in poly.indices) {
                    val (u1, v1) = poly[i]; val (u2, v2) = poly[(i + 1) % poly.size]
                    val a1 = if (fixedIsV) v1 else u1; val a2 = if (fixedIsV) v2 else u2
                    val b1 = if (fixedIsV) u1 else v1; val b2 = if (fixedIsV) u2 else v2
                    if (a1 == a2 || c < min(a1, a2) || c > max(a1, a2)) continue
                    hits += b1 + (c - a1) / (a2 - a1) * (b2 - b1)
                }
                return if (hits.size >= 2) hits.min() to hits.max() else null
            }
            fun drawAt(fixedIsV: Boolean, c: Float) {
                val (lo, hi) = cut(fixedIsV, c) ?: return
                if (fixedIsV) seg(lo, c, hi, c, thin) else seg(c, lo, c, hi, thin)
            }
            // Courses: lines parallel to the eave (constant v on a side plane, constant u on a hip end).
            if (rc.courses && planCourse > 0.5f) {
                val span = poly.maxOf { if (alongU) it.second else it.first } - poly.minOf { if (alongU) it.second else it.first }
                var d = planCourse
                while (d < span - 0.5f) { drawAt(alongU, eaveAt + dir * d); d += planCourse }
            }
            // Seams, ribs or barrels: square to the eave.
            if (down > 0.5f) {
                val lo = poly.minOf { if (alongU) it.first else it.second }
                val hi = poly.maxOf { if (alongU) it.first else it.second }
                var c = lo + down
                while (c < hi - 0.5f) { drawAt(!alongU, c); c += down }
            }
        }
        // No roofing pattern on the roof plan (Dan, Nov 22): it is a clear picture for the roofers and
        // carpenters, so the roofing is named, not drawn. [plane] stays for when a pattern is wanted.
        if (SHOW_ROOFING_PATTERN) {
            plane(listOf(eu1 to ev1, eu2 to ev1, ru2 to vc, ru1 to vc), alongU = true, eaveAt = ev1, dir = 1f)
            plane(listOf(eu1 to ev2, eu2 to ev2, ru2 to vc, ru1 to vc), alongU = true, eaveAt = ev2, dir = -1f)
            if (r.endA == RoofEnd.HIP) plane(listOf(eu1 to ev1, ru1 to vc, eu1 to ev2), alongU = false, eaveAt = eu1, dir = 1f)
            if (r.endB == RoofEnd.HIP) plane(listOf(eu2 to ev1, ru2 to vc, eu2 to ev2), alongU = false, eaveAt = eu2, dir = -1f)
        }

        // The outline, ridge and hips over the pattern.
        for (l in Wings.trimHouseRoof(g.lines, state)) if (l.kind == Kind.EAVE || l.kind == Kind.RIDGE || l.kind == Kind.HIP) drawLine(ink, l.a, l.b, strokeWidth = heavy)

        // The pitch on each side plane and the roofing named in the middle, on a white patch so they read over the pattern.
        val text = k * 7f
        fun label(t: String, at: Offset) {
            val w = text * 0.6f * t.length
            drawRect(state.backgroundColor, topLeft = Offset(at.x - w / 2f - text * 0.3f, at.y - text * 0.8f), size = androidx.compose.ui.geometry.Size(w + text * 0.6f, text * 1.6f))
            drawPageText(t, Offset(at.x - w / 2f, at.y - text * 0.6f), text, ink, bold = true, maxWidth = w + text * 2f)
        }
        val mid = (ru1 + ru2) / 2f
        label(pitchText(r.pitch), page(mid, (ev1 + vc) / 2f))
        label(pitchText(r.pitch), page(mid, (ev2 + vc) / 2f))
        label(rc.label.uppercase(), page(mid, (ev1 + vc) / 2f + text * 2.2f))
    }
}
