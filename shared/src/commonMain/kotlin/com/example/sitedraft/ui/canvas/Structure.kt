package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Foundation and floor-framing symbols (Nov 19 2026), drawn on the Foundation and floor
 * structural layers. Like [Stairs], each one is a single library symbol with its settings kept as
 * numbers in its properties, and its plan is a list of [Shower.Part]s so the canvas and the DXF
 * export draw the same thing.
 *
 * Foundation: continuous footing, pad footing, pier, slab. (Foundation walls are the 8" concrete
 * and block walls in the Walls category.)
 * Framing: a joist bay laid out like ProCalc's floor layout (spacing, joist size, span direction,
 * evenly spaced beams), a beam, a post, a floor opening (the joist bay cuts its joists there and
 * doubles the trimmers and headers), and a ladder (two joists with blocks between them, under a
 * wall that runs with the joists).
 */
object Structure {
    const val FOUNDATION = "Foundation"
    const val FRAMING = "Framing"
    val categories = setOf(FOUNDATION, FRAMING)

    const val FOOTING = "Continuous Footing"
    const val PAD = "Pad Footing"
    const val PIER = "Pier (round)"
    const val SLAB = "Slab"
    const val ANCHOR_BOLT = "Anchor Bolt"
    val foundationLibrary = listOf(FOOTING, PAD, PIER, SLAB, ANCHOR_BOLT)

    const val JOIST_BAY = "Joist Bay"
    const val BEAM = "Beam"
    const val POST = "Post 6x6"
    const val OPENING = "Floor Opening"
    const val LADDER = "Ladder"
    val framingLibrary = listOf(JOIST_BAY, BEAM, POST, OPENING, LADDER)

    /** Default footprints, inches: (width along the page, depth down the page). */
    val footprints: Map<String, Pair<Float, Float>> = mapOf(
        FOOTING to Pair(96f, 18f),
        PAD to Pair(24f, 24f),
        PIER to Pair(12f, 12f),
        SLAB to Pair(120f, 120f),
        ANCHOR_BOLT to Pair(3f, 3f),
        JOIST_BAY to Pair(192f, 144f),
        BEAM to Pair(96f, 4.5f),
        POST to Pair(5.5f, 5.5f),
        OPENING to Pair(36f, 120f),
        LADDER to Pair(96f, 16f)
    )

    // ---- Property keys ----
    const val THICK = "stThick"          // footing / pad / slab thickness, inches
    const val SPACING = "jSpacing"       // joist spacing o.c., inches
    const val JOIST = "jSize"            // joist size code: index into joistSizes
    const val DIRECTION = "jDir"         // 0 joists run across the page (left-right), 1 up-down
    const val BEAMS = "jBeams"           // evenly spaced beams across the joists
    const val BLOCKS = "ldBlocks"        // ladder: block spacing o.c., inches
    /** On a wall: 1 = bearing wall (the layer builder carries it down to a footing). */
    const val BEARING = "bearing"

    val keys = setOf(THICK, SPACING, JOIST, DIRECTION, BEAMS, BLOCKS, BEARING)

    /** Joist sizes: label and depth in inches. */
    val joistSizes = listOf("2x8" to 7.25f, "2x10" to 9.25f, "2x12" to 11.25f, "TJI 9 1/2" to 9.5f, "TJI 11 7/8" to 11.875f, "TJI 14" to 14f)
    val directionLabels = listOf("Across", "Up and down")

    /**
     * Ladder block spacing: Dan blocks at 16" o.c., laid out from the same end as the wall so the
     * blocks land under the studs. The code maximum (IRC / 2015 Michigan Residential Code figure
     * R502.2: doubled joists under a bearing partition, separated for pipes, blocked 4 ft o.c.
     * at most) is [MAX_LADDER_BLOCKS_IN]. Editable per ladder.
     */
    const val DEFAULT_LADDER_BLOCKS_IN = 16f
    const val MAX_LADDER_BLOCKS_IN = 48f
    /** R502.2 / R502.10: a header spanning more than 4 ft is doubled; more than 6 ft needs hangers. */
    const val SINGLE_HEADER_MAX_IN = 48f
    const val HANGER_OVER_IN = 72f

    fun isStructure(s: DrawingElement.Symbol) = s.category in categories

    fun defaults(name: String): Map<String, Float> = when (name) {
        FOOTING -> mapOf(THICK to 8f)
        PAD -> mapOf(THICK to 12f)
        SLAB -> mapOf(THICK to 4f)
        JOIST_BAY -> mapOf(SPACING to 16f, JOIST to 1f, DIRECTION to 0f, BEAMS to 0f)
        LADDER -> mapOf(BLOCKS to DEFAULT_LADDER_BLOCKS_IN)
        else -> emptyMap()
    }

    fun prop(s: DrawingElement.Symbol, key: String): Float = s.properties[key] ?: defaults(s.symbolName)[key] ?: 0f
    fun code(s: DrawingElement.Symbol, key: String): Int = prop(s, key).roundToInt()

    fun joistLabel(s: DrawingElement.Symbol) = joistSizes.getOrElse(code(s, JOIST)) { joistSizes[1] }.first

    /** The joist size whose depth is nearest [depthIn] (the building input), as a code. */
    fun joistCodeFor(depthIn: Float): Int =
        joistSizes.indices.minByOrNull { abs(joistSizes[it].second - depthIn) } ?: 1

    // ---- Joist bay layout ----

    /**
     * A joist bay's layout in its own frame (origin at the centre, unrotated, px): joists run
     * along [alongX] (left-right) or up-down. [joists] are the cross-positions of the inside
     * joists (the two edges are the end joists / rims), [beams] the along-positions of the beams.
     */
    data class BayLayout(val alongX: Boolean, val length: Float, val width: Float, val joists: List<Float>, val beams: List<Float>)

    fun bayLayout(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float): BayLayout {
        val alongX = code(s, DIRECTION) == 0
        val length = if (alongX) w else h
        val width = if (alongX) h else w
        val sp = (prop(s, SPACING).coerceAtLeast(4f)) * inch
        val joists = mutableListOf<Float>()
        var c = -width / 2f + sp
        while (c < width / 2f - inch * 0.75f && joists.size < 2000) { joists += c; c += sp }
        val n = code(s, BEAMS).coerceIn(0, 20)
        val beams = (1..n).map { -length / 2f + it * length / (n + 1) }
        return BayLayout(alongX, length, width, joists, beams)
    }

    /** Joists in the bay, ends included, counted per span (a beam starts a new span). */
    fun joistCount(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float): Int {
        val l = bayLayout(s, w, h, inch)
        return (l.joists.size + 2) * (l.beams.size + 1)
    }

    fun summary(s: DrawingElement.Symbol, state: CanvasState): String {
        val inch = state.inchesToPx(1f)
        if (inch <= 0f) return ""
        return when (s.symbolName) {
            JOIST_BAY -> {
                val l = bayLayout(s, s.size.width, s.size.height, inch)
                val spanIn = l.length / inch / (l.beams.size + 1)
                val beams = if (l.beams.isEmpty()) "no beam" else "${l.beams.size} beam" + if (l.beams.size > 1) "s" else ""
                "${joistCount(s, s.size.width, s.size.height, inch)} joists, ${joistLabel(s)} @ ${formatInches(prop(s, SPACING))}\" o.c., " +
                    "span ${formatFeetInches(spanIn / 12f)}, $beams"
            }
            LADDER -> {
                val lenIn = maxOf(s.size.width, s.size.height) / inch
                val blocks = (floor(lenIn / prop(s, BLOCKS).coerceAtLeast(4f)).toInt() + 1).coerceAtLeast(2)
                "$blocks blocks @ ${formatInches(prop(s, BLOCKS))}\" o.c. between two joists, ${formatFeetInches(lenIn / 12f)} long"
            }
            FOOTING -> "${formatInches(minOf(s.size.width, s.size.height) / inch)}\" wide x ${formatInches(prop(s, THICK))}\" thick"
            PAD -> "${formatInches(s.size.width / inch)}\" x ${formatInches(s.size.height / inch)}\" x ${formatInches(prop(s, THICK))}\" thick"
            SLAB -> "${formatInches(prop(s, THICK))}\" slab"
            else -> ""
        }
    }

    // ---- Plan geometry ----

    /**
     * Everything the plan shows, in the symbol's own frame (origin at its centre, unrotated).
     * [openings] are floor openings over a joist bay, in the bay's frame (px).
     */
    fun planParts(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float, openings: List<Rect> = emptyList()): List<Shower.Part> {
        val hw = w / 2f
        val hh = h / 2f
        val out = mutableListOf<Shower.Part>()
        fun rect(r: Rect, ink: Shower.Ink) = out.add(Shower.Part.Poly(listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft), true, ink))
        fun seg(a: Offset, b: Offset, ink: Shower.Ink) = out.add(Shower.Part.Seg(a, b, ink))
        val box = Rect(-hw, -hh, hw, hh)
        val labelSize = (inch * 4f).coerceAtLeast(6f)
        val long = maxOf(w, h)
        fun label(text: String, at: Offset = Offset.Zero, ink: Shower.Ink = Shower.Ink.ACCENT, size: Float = labelSize) {
            if (long > inch * text.length * 3f) out.add(Shower.Part.Label(text, at, size, ink))
        }
        when (s.symbolName) {
            FOOTING -> {
                // Hidden line: the footing is below the wall it carries.
                rect(box, Shower.Ink.DASHED)
                label("${formatInches(minOf(w, h) / inch)}x${formatInches(prop(s, THICK))} FTG", ink = Shower.Ink.FAINT)
            }
            PAD -> {
                rect(box, Shower.Ink.DASHED)
                seg(Offset(-hw, -hh), Offset(hw, hh), Shower.Ink.FAINT_DASHED)
                seg(Offset(hw, -hh), Offset(-hw, hh), Shower.Ink.FAINT_DASHED)
                out.add(Shower.Part.Label("${formatInches(w / inch)}x${formatInches(h / inch)}x${formatInches(prop(s, THICK))}",
                    Offset(0f, hh + labelSize), labelSize * 0.8f, Shower.Ink.FAINT))
            }
            PIER -> {
                val r = minOf(hw, hh)
                out.add(Shower.Part.Ring(Offset.Zero, r, Shower.Ink.LINE))
                val k = r * 0.7f
                seg(Offset(-k, -k), Offset(k, k), Shower.Ink.THIN)
                seg(Offset(k, -k), Offset(-k, k), Shower.Ink.THIN)
            }
            SLAB -> {
                rect(box, Shower.Ink.LINE)
                // Light stipple: a sparse grid of short ticks reads as concrete without hiding what's under it.
                val step = inch * 18f
                if (step > 0f && w / step < 60f && h / step < 60f) {
                    var y = -hh + step / 2f
                    var row = 0
                    while (y < hh) {
                        var x = -hw + step / 2f + if (row % 2 == 1) step / 2f else 0f
                        while (x < hw) { seg(Offset(x - inch, y), Offset(x + inch, y), Shower.Ink.FAINT); x += step }
                        y += step; row++
                    }
                }
                label("${formatInches(prop(s, THICK))}\" CONC. SLAB")
            }
            JOIST_BAY -> joistBay(s, w, h, inch, openings, out, labelSize)
            BEAM -> {
                // Heavy hidden line on both faces with the centre line: a beam under the joists.
                rect(box, Shower.Ink.DASHED)
                if (w >= h) seg(Offset(-hw, 0f), Offset(hw, 0f), Shower.Ink.FAINT_DASHED) else seg(Offset(0f, -hh), Offset(0f, hh), Shower.Ink.FAINT_DASHED)
                label("BEAM", if (w >= h) Offset(0f, -hh - labelSize) else Offset(hw + labelSize * 2f, 0f), size = labelSize * 0.8f)
            }
            ANCHOR_BOLT -> {
                // A 1/2" bolt drawn big enough to see at 1/4" = 1': ring with a cross.
                val r = minOf(hw, hh)
                out.add(Shower.Part.Ring(Offset.Zero, r, Shower.Ink.ACCENT))
                seg(Offset(-r, 0f), Offset(r, 0f), Shower.Ink.ACCENT)
                seg(Offset(0f, -r), Offset(0f, r), Shower.Ink.ACCENT)
            }
            POST -> {
                rect(box, Shower.Ink.LINE)
                seg(Offset(-hw, -hh), Offset(hw, hh), Shower.Ink.THIN)
                seg(Offset(hw, -hh), Offset(-hw, hh), Shower.Ink.THIN)
            }
            OPENING -> {
                rect(box, Shower.Ink.LINE)
                seg(Offset(-hw, -hh), Offset(hw, hh), Shower.Ink.ACCENT)
                seg(Offset(hw, -hh), Offset(-hw, hh), Shower.Ink.ACCENT)
                label("OPEN")
            }
            LADDER -> {
                // Two joists along the long sides, blocks across between them.
                val alongX = w >= h
                val len = if (alongX) w else h
                val half = len / 2f
                val wid = if (alongX) hh else hw
                fun p(a: Float, c: Float) = if (alongX) Offset(a, c) else Offset(c, a)
                seg(p(-half, -wid), p(half, -wid), Shower.Ink.LINE)
                seg(p(-half, wid), p(half, wid), Shower.Ink.LINE)
                val sp = prop(s, BLOCKS).coerceAtLeast(4f) * inch
                var a = -half
                var n = 0
                while (a <= half + 0.01f && n < 500) {
                    seg(p(a, -wid), p(a, wid), Shower.Ink.ACCENT)
                    a += sp; n++
                }
                if (a - sp < half - inch) seg(p(half, -wid), p(half, wid), Shower.Ink.ACCENT)
            }
        }
        return out
    }

    private fun joistBay(
        s: DrawingElement.Symbol, w: Float, h: Float, inch: Float, openings: List<Rect>,
        out: MutableList<Shower.Part>, labelSize: Float
    ) {
        val hw = w / 2f
        val hh = h / 2f
        val l = bayLayout(s, w, h, inch)
        fun p(along: Float, cross: Float) = if (l.alongX) Offset(along, cross) else Offset(cross, along)
        fun seg(a: Offset, b: Offset, ink: Shower.Ink) = out.add(Shower.Part.Seg(a, b, ink))
        val halfL = l.length / 2f
        val halfW = l.width / 2f
        // Rim / end joists all round.
        out.add(Shower.Part.Poly(listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh)), true, Shower.Ink.LINE))
        // Openings in along/cross terms.
        data class Cut(val a1: Float, val a2: Float, val c1: Float, val c2: Float)
        val cuts = openings.map { r ->
            if (l.alongX) Cut(r.left, r.right, r.top, r.bottom) else Cut(r.top, r.bottom, r.left, r.right)
        }.filter { it.a2 > -halfL && it.a1 < halfL && it.c2 > -halfW && it.c1 < halfW }
        // Joists, cut where an opening crosses them.
        for (c in l.joists) {
            val blocking = cuts.filter { c > it.c1 && c < it.c2 }.sortedBy { it.a1 }
            var from = -halfL
            for (cut in blocking) {
                if (cut.a1 > from) seg(p(from, c), p(cut.a1, c), Shower.Ink.THIN)
                from = maxOf(from, cut.a2)
            }
            if (from < halfL) seg(p(from, c), p(halfL, c), Shower.Ink.THIN)
        }
        // Trimmers along the opening's sides (full length) and headers across its ends. Per
        // R502.2 / R502.10 a header over 4 ft is doubled (so are its trimmers) and over 6 ft it
        // hangs in hangers.
        val d = inch * 1.5f
        for (cut in cuts) {
            val headerIn = (cut.c2 - cut.c1) / inch
            val double = headerIn > SINGLE_HEADER_MAX_IN
            val trimmers = if (double) listOf(cut.c1, cut.c1 - d, cut.c2, cut.c2 + d) else listOf(cut.c1, cut.c2)
            for (c in trimmers) if (c > -halfW && c < halfW) seg(p(-halfL, c), p(halfL, c), Shower.Ink.LINE)
            val t1 = (cut.c1 - if (double) d else 0f).coerceAtLeast(-halfW)
            val t2 = (cut.c2 + if (double) d else 0f).coerceAtMost(halfW)
            val headers = if (double) listOf(cut.a1, cut.a1 - d, cut.a2, cut.a2 + d) else listOf(cut.a1, cut.a2)
            for (a in headers) if (a > -halfL && a < halfL) seg(p(a, t1), p(a, t2), Shower.Ink.LINE)
            if (headerIn > HANGER_OVER_IN) for (a in listOf(cut.a1 - d * 2f, cut.a2 + d * 2f)) {
                if (a > -halfL && a < halfL) out.add(Shower.Part.Label("HGRS", p(a, (cut.c1 + cut.c2) / 2f), labelSize * 0.7f, Shower.Ink.ACCENT))
            }
        }
        // Beams across the joists.
        for (b in l.beams) {
            seg(p(b - inch * 2.25f, -halfW), p(b - inch * 2.25f, halfW), Shower.Ink.DASHED)
            seg(p(b + inch * 2.25f, -halfW), p(b + inch * 2.25f, halfW), Shower.Ink.DASHED)
        }
        // Size and spacing, with an arrow along the span (one per span).
        val text = "${joistLabel(s)} @ ${formatInches(prop(s, SPACING))}\" o.c."
        val spans = l.beams.size + 1
        val spanLen = l.length / spans
        for (i in 0 until spans) {
            val mid = -halfL + spanLen * (i + 0.5f)
            if (spanLen > inch * text.length * 2.5f && l.width > labelSize * 3f) {
                out.add(Shower.Part.Label(text, p(mid, -labelSize), labelSize, Shower.Ink.ACCENT))
                val a1 = mid - spanLen * 0.3f
                val a2 = mid + spanLen * 0.3f
                val y = labelSize * 0.6f
                seg(p(a1, y), p(a2, y), Shower.Ink.ACCENT)
                for ((tip, dir) in listOf(a1 to 1f, a2 to -1f)) {
                    seg(p(tip, y), p(tip + dir * labelSize, y - labelSize * 0.5f), Shower.Ink.ACCENT)
                    seg(p(tip, y), p(tip + dir * labelSize, y + labelSize * 0.5f), Shower.Ink.ACCENT)
                }
            }
        }
    }

    /** Floor openings over [bay] on its layer, in the bay's own frame (unrotated, px). */
    fun openingsOver(bay: DrawingElement.Symbol, state: CanvasState): List<Rect> {
        val bayLayer = Layers.layerOf(bay, state).id
        val rad = -bay.rotation * PI.toFloat() / 180f
        val cs = cos(rad)
        val sn = sin(rad)
        return state.elements.filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == FRAMING && it.symbolName == OPENING && Layers.layerOf(it, state).id == bayLayer }
            .map { o ->
                val d = o.center - bay.center
                val local = Offset(d.x * cs - d.y * sn, d.x * sn + d.y * cs)
                // An opening turned 90 degrees relative to the bay swaps its sides.
                val rel = (((o.rotation - bay.rotation) % 180f) + 180f) % 180f
                val swap = abs(rel - 90f) < 1f
                val ow = if (swap) o.size.height else o.size.width
                val oh = if (swap) o.size.width else o.size.height
                Rect(local.x - ow / 2f, local.y - oh / 2f, local.x + ow / 2f, local.y + oh / 2f)
            }
    }

    fun DrawScope.drawStructurePlan(
        s: DrawingElement.Symbol, state: CanvasState, w: Float, h: Float, inch: Float,
        lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke
    ) {
        val dashed = Stroke(width = line.width, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 4f, inch * 2.5f)))
        val faintDashed = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 2f, inch * 1.5f)))
        fun color(ink: Shower.Ink) = when (ink) {
            Shower.Ink.LINE, Shower.Ink.THIN, Shower.Ink.DASHED -> lineColor
            Shower.Ink.FAINT, Shower.Ink.FAINT_DASHED -> faint
            else -> accentColor
        }
        fun stroke(ink: Shower.Ink) = when (ink) {
            Shower.Ink.LINE, Shower.Ink.GLASS -> line
            Shower.Ink.DASHED -> dashed
            Shower.Ink.FAINT_DASHED -> faintDashed
            else -> thin
        }
        val openings = if (s.symbolName == JOIST_BAY) openingsOver(s, state) else emptyList()
        for (p in planParts(s, w, h, inch, openings)) when (p) {
            is Shower.Part.Seg -> drawPath(Path().apply { moveTo(p.a.x, p.a.y); lineTo(p.b.x, p.b.y) }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Poly -> drawPath(Path().apply {
                moveTo(p.points[0].x, p.points[0].y); p.points.drop(1).forEach { lineTo(it.x, it.y) }; if (p.closed) close()
            }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Ring -> drawCircle(color = color(p.ink), radius = p.r, center = p.c, style = stroke(p.ink))
            is Shower.Part.Label -> drawLabel(p.text, p.c, p.size, color(p.ink))
        }
    }
}
