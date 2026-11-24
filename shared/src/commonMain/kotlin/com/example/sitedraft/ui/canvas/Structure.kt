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
    const val BEAM_SECTION = "jBeamShape" // the beams' steel shape: index into SteelEngine.AISC_DATABASE (-1 none)
    const val BLOCKS = "ldBlocks"        // ladder: block spacing o.c., inches
    /** On a wall: 1 = bearing wall (the layer builder carries it down to a footing). */
    const val BEARING = "bearing"
    /** On a footing: drain tile both sides (Nov 23): 0 none, 1 Form-A-Drain, 2 perforated pipe; cross pipes every [CROSS_PIPE_IN]. */
    const val DRAIN = "ftgDrain"
    const val CROSS_PIPE_IN = 240f
    /**
     * On a footing (Nov 23): how its ends meet the footings round the corner, start + 3 x end, each
     * 0 free, [JOIN_OWN] runs to the outside corner (its inner edge stops short there), [JOIN_BUTT]
     * stops against the one that owns the corner (no end line); [INNER] which side is inside, +1/-1
     * across the piece. Together they draw one continuous outline, no lines across the corners.
     */
    const val JOIN = "ftgJoin"
    const val INNER = "ftgIn"
    const val JOIN_OWN = 1
    /**
     * On a footing (Nov 23): which end steps down to a basement footing with a lead wall (Dan: from
     * frost depth down to the basement footing over 3' to 5'): 1 the start, 2 the end, 0 neither.
     */
    const val STEP = "ftgStep"
    const val JOIN_BUTT = 2

    val keys = setOf(THICK, SPACING, JOIST, DIRECTION, BEAMS, BLOCKS, BEARING, DRAIN, JOIN, INNER, STEP)

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

    /**
     * [origin] is the house layout's origin (FramingLayout) relative to the bay's top-left corner,
     * px, when the bay is square to the page: the joists then land on its marks. Null = the first
     * joist one spacing in from the bay's edge.
     */
    fun bayLayout(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float, origin: Offset? = null): BayLayout {
        val alongX = code(s, DIRECTION) == 0
        val length = if (alongX) w else h
        val width = if (alongX) h else w
        val sp = (prop(s, SPACING).coerceAtLeast(4f)) * inch
        val joists = mutableListOf<Float>()
        val first = origin?.let { FramingLayout.firstMark(0f, if (alongX) it.y else it.x, sp) } ?: sp
        var c = -width / 2f + first
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
    fun planParts(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float, openings: List<Rect> = emptyList(), origin: Offset? = null): List<Shower.Part> {
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
                // Hidden line: the footing is below the wall it carries. Pieces that meet at the corners
                // (JOIN) leave out the lines between them, so the footing reads as one outline.
                val join = prop(s, JOIN).toInt()
                val jStart = join % 3
                val jEnd = join / 3
                val alongX = w >= h
                val len = if (alongX) w else h
                val half = if (alongX) hh else hw
                val width = 2f * half
                val inSide = if (prop(s, INNER) < 0f) -1f else 1f
                fun p(a: Float, c: Float) = if (alongX) Offset(a, c) else Offset(c, a)
                if (join == 0) rect(box, Shower.Ink.DASHED) else {
                    val a1 = -len / 2f; val a2 = len / 2f
                    seg(p(a1, -inSide * half), p(a2, -inSide * half), Shower.Ink.DASHED)                     // outside edge
                    val i1 = if (jStart == JOIN_OWN) a1 + width else a1
                    val i2 = if (jEnd == JOIN_OWN) a2 - width else a2
                    if (i2 > i1) seg(p(i1, inSide * half), p(i2, inSide * half), Shower.Ink.DASHED)            // inside edge
                    if (jStart != JOIN_BUTT) seg(p(a1, -half), p(a1, half), Shower.Ink.DASHED)
                    if (jEnd != JOIN_BUTT) seg(p(a2, -half), p(a2, half), Shower.Ink.DASHED)
                }
                label("${formatInches(minOf(w, h) / inch)}x${formatInches(prop(s, THICK))} FTG", ink = Shower.Ink.FAINT)
                // A lead wall at one end: steps across the footing down to the basement footing, and the note.
                val step = prop(s, STEP).toInt()
                if (step > 0) {
                    val runPx = minOf(Garages.LEAD_WALL_IN * inch, len)
                    val sign = if (step == 1) 1f else -1f
                    val a0 = if (step == 1) -len / 2f else len / 2f
                    for (i in 1..3) { val a = a0 + sign * runPx * i / 4f; seg(p(a, -half), p(a, half), Shower.Ink.THIN) }
                    out.add(Shower.Part.Label("LEAD WALL: STEPS DOWN TO BSMT FTG", p(a0 + sign * runPx / 2f, -half - labelSize), labelSize * 0.8f, Shower.Ink.ACCENT))
                }
                // Drain tile down both sides (Dan, Nov 22-23), with a cross pipe through the footing every 20'.
                val drain = prop(s, DRAIN).toInt()
                if (drain > 0) {
                    val off = (if (drain == 1) 0.75f else 3f) * inch
                    // At a corner the lines meet: outside lines run on round it, inside ones stop at the other's.
                    fun ext(j: Int, outside: Boolean) = when (j) {
                        JOIN_OWN -> if (outside) off else -(width + off)
                        JOIN_BUTT -> if (outside) width + off else -off
                        else -> 0f
                    }
                    for (side in listOf(-1f, 1f)) {
                        val s1 = -len / 2f - (if (join == 0) 0f else ext(jStart, side == -inSide))
                        val s2 = len / 2f + (if (join == 0) 0f else ext(jEnd, side == -inSide))
                        if (s2 > s1) seg(p(s1, side * (half + off)), p(s2, side * (half + off)), Shower.Ink.FAINT_DASHED)
                    }
                    val n = (len / (CROSS_PIPE_IN * inch)).toInt()
                    for (i in 1..n) {
                        val a = -len / 2f + len * i / (n + 1)
                        seg(p(a, -half - off), p(a, half + off), Shower.Ink.FAINT_DASHED)
                    }
                    label(if (drain == 1) "FORM-A-DRAIN BOTH SIDES" else "DRAIN TILE BOTH SIDES",
                        p(0f, -(half + off) - labelSize), Shower.Ink.FAINT)
                }
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
            JOIST_BAY -> joistBay(s, w, h, inch, openings, out, labelSize, origin)
            BEAM -> {
                // Heavy hidden line on both faces with the centre line: a beam under the joists.
                rect(box, Shower.Ink.DASHED)
                if (w >= h) seg(Offset(-hw, 0f), Offset(hw, 0f), Shower.Ink.FAINT_DASHED) else seg(Offset(0f, -hh), Offset(0f, hh), Shower.Ink.FAINT_DASHED)
                // Its steel section when it was sized (a wing's beam across the opening, Wings.jointBeam).
                val sec = s.properties[BEAM_SECTION]?.toInt()?.let { SteelEngine.AISC_DATABASE.getOrNull(it) }
                val exist = if (Remodel.statusOf(s) == WallStatus.EXISTING) "EXIST. " else ""
                label(exist + if (sec != null) "${sec.name} BEAM" else "BEAM", if (w >= h) Offset(0f, -hh - labelSize) else Offset(hw + labelSize * 2f, 0f), size = labelSize * 0.8f)
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
            // An existing ladder isn't shown on an existing-conditions plan (Remodel).
            LADDER -> if (Remodel.statusOf(s) != WallStatus.EXISTING) {
                // Two joists along the long sides, blocks across between them.
                val alongX = w >= h
                val len = if (alongX) w else h
                val half = len / 2f
                val wid = if (alongX) hh else hw
                fun p(a: Float, c: Float) = if (alongX) Offset(a, c) else Offset(c, a)
                // Both joists and every block at their real 1 1/2" (Dan, Nov 24): two lines each.
                val t = inch * 1.5f
                for (face in listOf(-wid, wid)) {
                    val inward = if (face < 0f) 1f else -1f
                    seg(p(-half, face), p(half, face), Shower.Ink.LINE)
                    seg(p(-half, face + inward * t), p(half, face + inward * t), Shower.Ink.LINE)
                }
                val sp = prop(s, BLOCKS).coerceAtLeast(4f) * inch
                fun block(a: Float) {
                    val a1 = (a - t / 2f).coerceIn(-half, half - t)
                    seg(p(a1, -wid + t), p(a1, wid - t), Shower.Ink.ACCENT)
                    seg(p(a1 + t, -wid + t), p(a1 + t, wid - t), Shower.Ink.ACCENT)
                }
                // Blocks at both ends, and between on the house layout (lined up with the studs above,
                // Dan) or every spacing from the start when there is no layout.
                val start = origin?.let { FramingLayout.firstMark(0f, if (alongX) it.x else it.y, sp) } ?: sp
                block(-half)
                var a = -half + start
                var n = 0
                while (a < half - t && n < 500) {
                    if (a > -half + t) block(a)
                    a += sp; n++
                }
                block(half)
            }
        }
        return out
    }

    private fun joistBay(
        s: DrawingElement.Symbol, w: Float, h: Float, inch: Float, openings: List<Rect>,
        out: MutableList<Shower.Part>, labelSize: Float, origin: Offset? = null
    ) {
        val hw = w / 2f
        val hh = h / 2f
        val l = bayLayout(s, w, h, inch, origin)
        fun p(along: Float, cross: Float) = if (l.alongX) Offset(along, cross) else Offset(cross, along)
        fun seg(a: Offset, b: Offset, ink: Shower.Ink) = out.add(Shower.Part.Seg(a, b, ink))
        val halfL = l.length / 2f
        val halfW = l.width / 2f
        // Existing framing (Remodel, Dan Nov 24), as an existing-conditions plan shows it: the bay's
        // outline and, in each span, the way the joists run with EXISTING and their size; no joists.
        if (Remodel.statusOf(s) == WallStatus.EXISTING) {
            out.add(Shower.Part.Poly(listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh)), true, Shower.Ink.LINE))
            // An existing beam's size isn't the design engine's pick: it's whatever is there (field verify).
            for (b in l.beams) {
                seg(p(b - inch * 2.25f, -halfW), p(b - inch * 2.25f, halfW), Shower.Ink.DASHED)
                seg(p(b + inch * 2.25f, -halfW), p(b + inch * 2.25f, halfW), Shower.Ink.DASHED)
                out.add(Shower.Part.Label("EXIST. STEEL BEAM", p(b + labelSize * 1.2f, -halfW * 0.6f), labelSize, Shower.Ink.ACCENT))
            }
            val size = "${joistLabel(s)} @ ${formatInches(prop(s, SPACING))}\" O.C."
            val spans = l.beams.size + 1
            val spanLen = l.length / spans
            for (i in 0 until spans) {
                val mid = -halfL + spanLen * (i + 0.5f)
                if (spanLen > inch * size.length * 2.5f && l.width > labelSize * 4f) {
                    out.add(Shower.Part.Label("EXISTING", p(mid, -labelSize * 2.2f), labelSize, Shower.Ink.ACCENT))
                    out.add(Shower.Part.Label(size, p(mid, -labelSize), labelSize, Shower.Ink.ACCENT))
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
            return
        }
        // Every member is drawn at its real 1 1/2" thickness, two lines each (Dan, Nov 24).
        val t = inch * 1.5f
        /** A member [t] thick with one face on cross line [face], the other toward [dir], from [a1] to [a2] along. */
        fun member(face: Float, dir: Float, a1: Float, a2: Float, ink: Shower.Ink) {
            if (a2 <= a1) return
            seg(p(a1, face), p(a2, face), ink)
            seg(p(a1, face + dir * t), p(a2, face + dir * t), ink)
        }
        /** A member [t] thick across the joists with one face on along line [face], from [c1] to [c2] across. */
        fun crossMember(face: Float, dir: Float, c1: Float, c2: Float, ink: Shower.Ink) {
            if (c2 <= c1) return
            seg(p(face, c1), p(face, c2), ink)
            seg(p(face + dir * t, c1), p(face + dir * t, c2), ink)
        }
        // Rim / end joists all round, with their inside face.
        out.add(Shower.Part.Poly(listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh)), true, Shower.Ink.LINE))
        if (hw > t && hh > t) out.add(Shower.Part.Poly(listOf(Offset(-hw + t, -hh + t), Offset(hw - t, -hh + t), Offset(hw - t, hh - t), Offset(-hw + t, hh - t)), true, Shower.Ink.THIN))
        val inA1 = -halfL + t
        val inA2 = halfL - t
        // Openings in along/cross terms.
        data class Cut(val a1: Float, val a2: Float, val c1: Float, val c2: Float) {
            val headerIn get() = (c2 - c1)
        }
        val cuts = openings.map { r ->
            if (l.alongX) Cut(r.left, r.right, r.top, r.bottom) else Cut(r.top, r.bottom, r.left, r.right)
        }.filter { it.a2 > -halfL && it.a1 < halfL && it.c2 > -halfW && it.c1 < halfW }
        // Header plies at each end: per R502.10 doubled over 4 ft (hangers over 6 ft).
        fun headerPlies(cut: Cut) = if (cut.headerIn / inch > SINGLE_HEADER_MAX_IN) 2 else 1
        // Trimmers, always doubled to carry the header (Dan, Nov 24): two plies outside each side.
        fun inTrimmers(c: Float) = cuts.any { cut ->
            val lo = c - t / 2f
            val hi = c + t / 2f
            (hi > cut.c1 - 2f * t && lo < cut.c1) || (hi > cut.c2 && lo < cut.c2 + 2f * t)
        }
        // Joists, 1 1/2" each, between the rims. Where an opening crosses one it stops at the
        // header's face (tail joists); one that lands on a trimmer is left out (the trimmer is there).
        for (c in l.joists) {
            if (inTrimmers(c)) continue
            val blocking = cuts.filter { c > it.c1 && c < it.c2 }.sortedBy { it.a1 }
            var from = inA1
            for (cut in blocking) {
                val hd = headerPlies(cut) * t
                val stop = cut.a1 - hd
                if (stop > from) member(c - t / 2f, 1f, from, stop, Shower.Ink.THIN)
                from = maxOf(from, cut.a2 + hd)
            }
            if (from < inA2) member(c - t / 2f, 1f, from, inA2, Shower.Ink.THIN)
        }
        for (cut in cuts) {
            // Trimmers: full length, two plies on each side of the opening.
            if (cut.c1 - 2f * t > -halfW) { member(cut.c1, -1f, inA1, inA2, Shower.Ink.LINE); member(cut.c1 - t, -1f, inA1, inA2, Shower.Ink.LINE) }
            if (cut.c2 + 2f * t < halfW) { member(cut.c2, 1f, inA1, inA2, Shower.Ink.LINE); member(cut.c2 + t, 1f, inA1, inA2, Shower.Ink.LINE) }
            // Headers across each end, between the trimmers.
            val plies = headerPlies(cut)
            val c1 = cut.c1.coerceAtLeast(-halfW + t)
            val c2 = cut.c2.coerceAtMost(halfW - t)
            for (k in 0 until plies) {
                if (cut.a1 - (k + 1) * t > inA1) crossMember(cut.a1 - k * t, -1f, c1, c2, Shower.Ink.LINE)
                if (cut.a2 + (k + 1) * t < inA2) crossMember(cut.a2 + k * t, 1f, c1, c2, Shower.Ink.LINE)
            }
            if (cut.headerIn / inch > HANGER_OVER_IN) for (a in listOf(cut.a1 - plies * t - t * 2f, cut.a2 + plies * t + t * 2f)) {
                if (a > -halfL && a < halfL) out.add(Shower.Part.Label("HGRS", p(a, (cut.c1 + cut.c2) / 2f), labelSize * 0.7f, Shower.Ink.ACCENT))
            }
        }
        // Beams across the joists, named with their steel shape when sized (StructuralDesign).
        val beamName = SteelEngine.AISC_DATABASE.getOrNull((s.properties[BEAM_SECTION] ?: -1f).toInt())?.name
        for (b in l.beams) {
            seg(p(b - inch * 2.25f, -halfW), p(b - inch * 2.25f, halfW), Shower.Ink.DASHED)
            seg(p(b + inch * 2.25f, -halfW), p(b + inch * 2.25f, halfW), Shower.Ink.DASHED)
            if (beamName != null) out.add(Shower.Part.Label("$beamName BEAM", p(b + labelSize * 1.2f, -halfW * 0.6f), labelSize, Shower.Ink.ACCENT))
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
        // The house layout's origin from the symbol's top-left corner (FramingLayout), when square to the page.
        val origin = if (s.rotation % 360f == 0f) FramingLayout.origin(state)?.let { it - (s.center - Offset(w / 2f, h / 2f)) } else null
        for (p in planParts(s, w, h, inch, openings, origin)) when (p) {
            is Shower.Part.Seg -> drawPath(Path().apply { moveTo(p.a.x, p.a.y); lineTo(p.b.x, p.b.y) }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Poly -> drawPath(Path().apply {
                moveTo(p.points[0].x, p.points[0].y); p.points.drop(1).forEach { lineTo(it.x, it.y) }; if (p.closed) close()
            }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Ring -> drawCircle(color = color(p.ink), radius = p.r, center = p.c, style = stroke(p.ink))
            is Shower.Part.Label -> drawLabel(p.text, p.c, p.size, color(p.ink))
        }
    }
}
