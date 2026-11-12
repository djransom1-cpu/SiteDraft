package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stairs built from one plan symbol (category [PlanSymbols.STAIRS]): straight, L and U with a
 * landing, open-stringer deck stairs, and spiral. Everything is kept as numbers in the symbol's
 * properties; the plan footprint follows the total rise and tread depth, and each stair gets a
 * live side elevation (an [ElevationSpec] with [ElevationSpec.stairSide] set) with its handrails
 * and guards.
 *
 * The risers are sized by Dan's rule: as close to 7 1/2" as the total rise allows, never under
 * 6 1/4" or over 8 1/4". In plan the stair climbs up the page: the bottom step is at the bottom
 * of the unrotated symbol.
 */
object Stairs {
    enum class Type { STRAIGHT, L_LANDING, U_LANDING, DECK, SPIRAL, CURVED }

    const val STRAIGHT = "Straight Stair"
    const val L_STAIR = "L Stair (landing)"
    const val U_STAIR = "U Stair (landing)"
    const val DECK = "Deck Stair (open)"
    const val SPIRAL = "Spiral Stair"
    const val CURVED = "Curved Stair"
    val library = listOf(STRAIGHT, L_STAIR, U_STAIR, DECK, CURVED, SPIRAL)

    fun type(name: String): Type = when (name) {
        L_STAIR -> Type.L_LANDING
        U_STAIR -> Type.U_LANDING
        DECK -> Type.DECK
        SPIRAL -> Type.SPIRAL
        CURVED -> Type.CURVED
        else -> Type.STRAIGHT
    }

    const val TARGET_RISER_IN = 7.5f
    const val MIN_RISER_IN = 6.25f
    const val MAX_RISER_IN = 8.25f

    // ---- Property keys (inches unless noted) ----
    const val RISE = "stRise"            // floor to floor
    const val TREAD = "stTread"          // tread depth (run), nosing to nosing
    const val NOSING = "stNosing"
    const val WIDTH = "stWidth"          // stair width; a spiral's diameter
    const val FLIGHT1 = "stFlight1"      // risers in the first flight of an L or U; 0 = half
    const val TURN = "stTurn"            // L: 0 turns right, 1 turns left
    const val PER_TURN = "stPerTurn"     // spiral: treads per full circle
    const val RAIL_LEFT = "stRailL"      // 0 none, 1 handrail, 2 guard with balusters
    const val RAIL_RIGHT = "stRailR"     // (a spiral's outer rail)
    const val RAIL_H = "stRailH"         // handrail height above the nosings
    const val GUARD_H = "stGuardH"       // guard height (on the sloped run it is the handrail height)
    // Curved stairs: the open (inside) and wall (outside) radii, and the walkline - where the
    // tread depth is measured, this far in from the narrow end. TURN: 0 right, 1 left.
    const val INNER_R = "stInnerR"
    const val OUTER_R = "stOuterR"
    const val WALKLINE = "stWalk"

    val keys = setOf(RISE, TREAD, NOSING, WIDTH, FLIGHT1, TURN, PER_TURN, RAIL_LEFT, RAIL_RIGHT, RAIL_H, GUARD_H, INNER_R, OUTER_R, WALKLINE)
    val railLabels = listOf("None", "Handrail", "Guard + balusters")
    const val RAIL_NONE = 0
    const val RAIL_HANDRAIL = 1
    const val RAIL_GUARD = 2

    fun defaults(name: String): Map<String, Float> {
        val t = type(name)
        return mapOf(
            RISE to if (t == Type.DECK) 36f else 108f,
            TREAD to if (t == Type.DECK) 10.5f else 10f,
            NOSING to 1f,
            WIDTH to when (t) { Type.DECK -> 48f; Type.SPIRAL -> 60f; else -> 36f },
            FLIGHT1 to 0f,
            TURN to 0f,
            PER_TURN to 12f,
            RAIL_LEFT to when (t) { Type.DECK -> RAIL_GUARD.toFloat(); Type.SPIRAL -> RAIL_NONE.toFloat(); else -> RAIL_HANDRAIL.toFloat() },
            RAIL_RIGHT to RAIL_GUARD.toFloat(),
            RAIL_H to 36f,
            GUARD_H to 36f,
            INNER_R to 48f,
            OUTER_R to 96f,
            WALKLINE to 12f,
        ).let { if (t == Type.CURVED) it + (RAIL_LEFT to RAIL_GUARD.toFloat()) + (RAIL_RIGHT to RAIL_HANDRAIL.toFloat()) else it }
    }

    fun prop(s: DrawingElement.Symbol, key: String): Float = s.properties[key] ?: defaults(s.symbolName)[key] ?: 0f
    fun code(s: DrawingElement.Symbol, key: String): Int = prop(s, key).toInt()

    /** Riser count for [riseIn]: nearest to 7 1/2" each, kept within 6 1/4"..8 1/4". */
    fun riserCount(riseIn: Float): Int {
        if (riseIn <= MAX_RISER_IN) return 1
        var n = (riseIn / TARGET_RISER_IN).roundToInt().coerceAtLeast(1)
        while (riseIn / n > MAX_RISER_IN) n++
        while (n > 1 && riseIn / n < MIN_RISER_IN) n--
        return n
    }

    /** The stair's numbers: risers, riser height, treads per flight. */
    data class Layout(val risers: Int, val riser: Float, val tread: Float, val nosing: Float, val width: Float, val flight1: Int, val flight2: Int) {
        val treads get() = risers - 1
        /** Treads in each flight (the landing is the top of the first flight's last riser). */
        val treads1 get() = (flight1 - 1).coerceAtLeast(0)
        val treads2 get() = (flight2 - 1).coerceAtLeast(0)
        val rise get() = risers * riser
    }

    fun layout(s: DrawingElement.Symbol): Layout {
        val rise = prop(s, RISE).coerceAtLeast(1f)
        val n = riserCount(rise)
        val t = type(s.symbolName)
        val f1 = if (t == Type.L_LANDING || t == Type.U_LANDING) {
            val chosen = code(s, FLIGHT1)
            (if (chosen in 1 until n) chosen else (n + 1) / 2).coerceIn(1, (n - 1).coerceAtLeast(1))
        } else n
        val width = if (t == Type.CURVED) (prop(s, OUTER_R) - prop(s, INNER_R)).coerceAtLeast(12f) else prop(s, WIDTH).coerceAtLeast(12f)
        return Layout(n, rise / n, prop(s, TREAD).coerceAtLeast(4f), prop(s, NOSING).coerceAtLeast(0f), width, f1, n - f1)
    }

    /**
     * A curved stair's geometry, inches: the radii, the walkline radius, the angle each tread
     * turns (its depth at the walkline over the walkline radius), the whole sweep, and [m] =
     * -1 when it turns right (the arc centre on the climber's right).
     */
    data class Curve(val ri: Float, val ro: Float, val rw: Float, val theta: Float, val sweep: Float, val m: Float) {
        /** Tread depth at radius [r]. */
        fun treadAt(r: Float) = r * theta
        /** A point on radius [r] at angle [a] (radians from the start), relative to the arc centre, page y down. */
        fun at(r: Float, a: Float) = Offset(m * r * cos(a), -r * sin(a))
    }

    fun curve(s: DrawingElement.Symbol): Curve {
        val l = layout(s)
        val ri = prop(s, INNER_R).coerceAtLeast(0f)
        val ro = prop(s, OUTER_R).coerceAtLeast(ri + 12f)
        val rw = (ri + prop(s, WALKLINE)).coerceIn(ri + 1f, ro)
        val theta = l.tread / rw
        return Curve(ri, ro, rw, theta, theta * l.treads, if (code(s, TURN) == 1) 1f else -1f)
    }

    /** The curved stair's plan extent around its arc centre, inches. */
    private fun curveBox(c: Curve): androidx.compose.ui.geometry.Rect {
        val pts = mutableListOf<Offset>()
        for (k in 0..64) {
            val a = c.sweep * k / 64f
            pts += c.at(c.ri, a); pts += c.at(c.ro, a)
        }
        var q = 0f
        while (q <= c.sweep) { pts += c.at(c.ro, q); q += (PI / 2).toFloat() }
        return androidx.compose.ui.geometry.Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    /** Plan footprint (width, depth) in inches. */
    fun footprintIn(s: DrawingElement.Symbol): Pair<Float, Float> {
        val l = layout(s)
        val w = l.width
        return when (type(s.symbolName)) {
            Type.STRAIGHT, Type.DECK -> Pair(w, (l.treads * l.tread).coerceAtLeast(l.tread))
            Type.L_LANDING -> Pair(w + l.treads2 * l.tread, l.treads1 * l.tread + w)
            Type.U_LANDING -> Pair(2f * w, maxOf(l.treads1, l.treads2) * l.tread + w)
            Type.SPIRAL -> Pair(w, w)
            Type.CURVED -> curveBox(curve(s)).let { Pair(it.width.coerceAtLeast(12f), it.height.coerceAtLeast(12f)) }
        }
    }

    /** Library footprints from the defaults. */
    val footprints: Map<String, Pair<Float, Float>> = library.associateWith { name ->
        footprintIn(DrawingElement.Symbol(symbolName = name, category = "Stairs", center = Offset.Zero, size = Size.Zero, color = Color.Black, properties = defaults(name)))
    }

    /** "13 risers @ 7 3/8", 12 treads @ 10"". */
    fun summary(s: DrawingElement.Symbol): String {
        val l = layout(s)
        val run = when (type(s.symbolName)) {
            Type.SPIRAL -> ""
            Type.CURVED -> curve(s).let { c ->
                ", treads ${formatInches(c.treadAt(c.ri))}\" inside, ${formatInches(c.treadAt(c.ro))}\" outside, turns ${(c.sweep * 180f / PI.toFloat()).roundToInt()} deg"
            }
            Type.L_LANDING, Type.U_LANDING -> ", runs ${formatFeetInches(l.treads1 * l.tread / 12f)} + landing + ${formatFeetInches(l.treads2 * l.tread / 12f)}"
            else -> ", run ${formatFeetInches(l.treads * l.tread / 12f)}"
        }
        val at = if (type(s.symbolName) == Type.CURVED) " at the walkline" else ""
        return "${l.risers} risers @ ${formatInches(l.riser)}\", ${l.treads} treads @ ${formatInches(l.tread)}\"$at$run"
    }

    // ---- Plan ----

    /** The shape the stair blanks under itself, in its own frame (null = its rectangle). */
    fun outline(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float): List<Offset>? {
        val hw = w / 2f
        val hh = h / 2f
        return when (type(s.symbolName)) {
            Type.L_LANDING -> {
                val sw = layout(s).width * inch
                val pts = listOf(Offset(-hw, hh), Offset(-hw + sw, hh), Offset(-hw + sw, -hh + sw), Offset(hw, -hh + sw), Offset(hw, -hh), Offset(-hw, -hh))
                if (code(s, TURN) == 1) pts.map { Offset(-it.x, it.y) } else pts
            }
            Type.SPIRAL -> (0 until 48).map { k ->
                val a = 2.0 * PI * k / 48.0
                Offset((hw * cos(a)).toFloat(), (hh * sin(a)).toFloat())
            }
            Type.CURVED -> {
                val c = curve(s)
                val centre = curveCentre(c, inch)
                (0..32).map { k -> centre + c.at(c.ro, c.sweep * k / 32f) * inch } +
                    (32 downTo 0).map { k -> centre + c.at(c.ri, c.sweep * k / 32f) * inch }
            }
            else -> null
        }
    }

    /** Plan parts in the stair's own frame (bottom step at the bottom), shared with the DXF. */
    fun planParts(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float): List<Shower.Part> {
        val t = type(s.symbolName)
        val l = layout(s)
        val hw = w / 2f
        val hh = h / 2f
        val T = l.tread * inch
        val W = l.width * inch
        val parts = mutableListOf<Shower.Part>()
        fun seg(a: Offset, b: Offset, ink: Shower.Ink = Shower.Ink.THIN) { parts += Shower.Part.Seg(a, b, ink) }
        fun arrow(points: List<Offset>) {
            if (points.size < 2) return
            parts += Shower.Part.Poly(points, false, Shower.Ink.ACCENT)
            val a = points[points.size - 2]
            val b = points.last()
            val d = (b - a) / (b - a).getDistance()
            val n = Offset(-d.y, d.x)
            val head = inch * 3f
            seg(b, b - d * head + n * head * 0.5f, Shower.Ink.ACCENT)
            seg(b, b - d * head - n * head * 0.5f, Shower.Ink.ACCENT)
            parts += Shower.Part.Label("UP", points.first() + Offset(inch * 5f, 0f), (inch * 3.5f).coerceAtLeast(6f), Shower.Ink.ACCENT)
        }
        /** A rail along the edge from [a] to [b]; [inward] points into the stair. */
        fun rail(kind: Int, a: Offset, b: Offset, inward: Offset) {
            when (kind) {
                RAIL_HANDRAIL -> seg(a + inward * (inch * 2.5f), b + inward * (inch * 2.5f), Shower.Ink.ACCENT)
                RAIL_GUARD -> {
                    seg(a + inward * (inch * 1.75f), b + inward * (inch * 1.75f), Shower.Ink.LINE)
                    for (p in listOf(a, b)) parts += Shower.Part.Poly(square(p + inward * (inch * 1.75f), inch * 1.75f), true, Shower.Ink.LINE)
                }
            }
        }
        val left = code(s, RAIL_LEFT)
        val right = code(s, RAIL_RIGHT)
        when (t) {
            Type.STRAIGHT, Type.DECK -> {
                parts += Shower.Part.Poly(listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh)), true, Shower.Ink.LINE)
                for (i in 1 until l.treads) seg(Offset(-hw, hh - i * T), Offset(hw, hh - i * T), Shower.Ink.THIN)
                if (t == Type.DECK) {
                    // Three stringers under the open treads.
                    for (x in listOf(-hw + inch * 1.5f, 0f, hw - inch * 1.5f)) seg(Offset(x, -hh), Offset(x, hh), Shower.Ink.DASHED)
                }
                arrow(listOf(Offset(0f, hh - T / 2f), Offset(0f, -hh + T / 2f)))
                rail(left, Offset(-hw, hh), Offset(-hw, -hh), Offset(1f, 0f))
                rail(right, Offset(hw, hh), Offset(hw, -hh), Offset(-1f, 0f))
            }
            Type.L_LANDING -> {
                val m = if (code(s, TURN) == 1) -1f else 1f
                fun p(x: Float, y: Float) = Offset(x * m, y)
                parts += Shower.Part.Poly(outline(s, w, h, inch)!!, true, Shower.Ink.LINE)
                // First flight up the left column, the landing square at its top, the second
                // flight along the top to the right.
                for (i in 1..l.treads1) seg(p(-hw, hh - i * T), p(-hw + W, hh - i * T))
                for (i in 1 until l.treads2 + 1) seg(p(-hw + W + (i - 1) * T, -hh), p(-hw + W + (i - 1) * T, -hh + W))
                arrow(listOf(p(-hw + W / 2f, hh - T / 2f), p(-hw + W / 2f, -hh + W / 2f), p(hw - T / 2f, -hh + W / 2f)))
                // Outer rails: the left edge, the landing's outside, the top edge.
                val outer = if (m > 0) left else right
                val inner = if (m > 0) right else left
                rail(outer, p(-hw, hh), p(-hw, -hh), Offset(m, 0f))
                rail(outer, p(-hw, -hh), p(hw, -hh), Offset(0f, 1f))
                rail(inner, p(-hw + W, hh), p(-hw + W, -hh + W), Offset(-m, 0f))
                rail(inner, p(-hw + W, -hh + W), p(hw, -hh + W), Offset(0f, -1f))
            }
            Type.U_LANDING -> {
                parts += Shower.Part.Poly(listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh)), true, Shower.Ink.LINE)
                seg(Offset(0f, hh), Offset(0f, -hh + W), Shower.Ink.LINE)
                // First flight up the left column to the landing across the top, second flight
                // down the right column (still climbing).
                for (k in 0..l.treads1) seg(Offset(-hw, -hh + W + k * T), Offset(0f, -hh + W + k * T))
                for (i in 0..l.treads2) seg(Offset(0f, -hh + W + i * T), Offset(hw, -hh + W + i * T))
                arrow(listOf(Offset(-hw / 2f, -hh + W + l.treads1 * T - T / 2f), Offset(-hw / 2f, -hh + W / 2f), Offset(hw / 2f, -hh + W / 2f), Offset(hw / 2f, -hh + W + l.treads2 * T - T / 2f)))
                rail(left, Offset(-hw, hh), Offset(-hw, -hh), Offset(1f, 0f))
                rail(left, Offset(-hw, -hh), Offset(hw, -hh), Offset(0f, 1f))
                rail(left, Offset(hw, -hh), Offset(hw, hh), Offset(-1f, 0f))
                rail(right, Offset(0f, hh), Offset(0f, -hh + W), Offset(0f, 0f))
            }
            Type.CURVED -> {
                val c = curve(s)
                val centre = curveCentre(c, inch)
                fun pt(r: Float, a: Float) = centre + c.at(r, a) * inch
                fun arc(r: Float, from: Float = 0f, to: Float = c.sweep) = (0..48).map { k -> pt(r, from + (to - from) * k / 48f) }
                parts += Shower.Part.Poly(arc(c.ro), false, Shower.Ink.LINE)
                parts += Shower.Part.Poly(arc(c.ri), false, Shower.Ink.LINE)
                for (i in 0..l.treads) seg(pt(c.ri, i * c.theta), pt(c.ro, i * c.theta), if (i == 0 || i == l.treads) Shower.Ink.LINE else Shower.Ink.THIN)
                arrow(arc(c.rw, c.theta / 2f, c.sweep - c.theta / 2f))
                fun curvedRail(kind: Int, r: Float, inward: Float) {
                    when (kind) {
                        RAIL_HANDRAIL -> parts += Shower.Part.Poly(arc(r + inward * 2.5f), false, Shower.Ink.ACCENT)
                        RAIL_GUARD -> {
                            parts += Shower.Part.Poly(arc(r + inward * 1.75f), false, Shower.Ink.LINE)
                            for (a in listOf(0f, c.sweep)) parts += Shower.Part.Poly(square(pt(r + inward * 1.75f, a), inch * 1.75f), true, Shower.Ink.LINE)
                        }
                    }
                }
                curvedRail(left, c.ri, 1f)
                curvedRail(right, c.ro, -1f)
            }
            Type.SPIRAL -> {
                val r = minOf(hw, hh)
                parts += Shower.Part.Ring(Offset.Zero, r, Shower.Ink.LINE)
                parts += Shower.Part.Ring(Offset.Zero, inch * 2.5f, Shower.Ink.LINE)
                val step = 360f / prop(s, PER_TURN).coerceAtLeast(3f)
                val start = 90f // the bottom tread starts straight down the page
                fun at(deg: Float, rr: Float) = Offset(rr * cos(deg * PI / 180.0).toFloat(), rr * sin(deg * PI / 180.0).toFloat())
                val shown = minOf(l.treads, (360f / step).toInt())
                for (i in 0..shown) seg(at(start - i * step, inch * 2.5f), at(start - i * step, r))
                val arc = (0..(shown * 4).coerceAtLeast(2)).map { k -> at(start - step / 2f - k * (shown * step - step) / (shown * 4).coerceAtLeast(2), r * 0.62f) }
                arrow(arc)
                if (right != RAIL_NONE) parts += Shower.Part.Ring(Offset.Zero, r - inch * 1.5f, if (right == RAIL_GUARD) Shower.Ink.LINE else Shower.Ink.ACCENT)
            }
        }
        return parts
    }

    /** Where the arc centre sits in the symbol's own frame (px): the footprint is centred on the stair. */
    private fun curveCentre(c: Curve, inch: Float): Offset = -curveBox(c).center * inch

    private fun square(c: Offset, half: Float) = listOf(c + Offset(-half, -half), c + Offset(half, -half), c + Offset(half, half), c + Offset(-half, half))

    /** Draws the stair centred on the origin (the caller has translated and rotated). */
    fun DrawScope.drawStairPlan(
        s: DrawingElement.Symbol, w: Float, h: Float, inch: Float,
        lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke
    ) {
        val dashed = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 3f, inch * 2f)))
        val heavy = Stroke(width = line.width, cap = StrokeCap.Butt)
        fun color(ink: Shower.Ink) = when (ink) {
            Shower.Ink.LINE, Shower.Ink.THIN, Shower.Ink.DASHED -> lineColor
            Shower.Ink.FAINT, Shower.Ink.FAINT_DASHED -> faint
            else -> accentColor
        }
        fun stroke(ink: Shower.Ink) = when (ink) {
            Shower.Ink.LINE, Shower.Ink.GLASS -> heavy
            Shower.Ink.DASHED, Shower.Ink.FAINT_DASHED -> dashed
            else -> thin
        }
        for (p in planParts(s, w, h, inch)) when (p) {
            is Shower.Part.Seg -> drawPath(Path().apply { moveTo(p.a.x, p.a.y); lineTo(p.b.x, p.b.y) }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Poly -> drawPath(Path().apply {
                moveTo(p.points[0].x, p.points[0].y); p.points.drop(1).forEach { lineTo(it.x, it.y) }; if (p.closed) close()
            }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Ring -> drawCircle(color = color(p.ink), radius = p.r, center = p.c, style = stroke(p.ink))
            is Shower.Part.Label -> drawLabel(p.text, p.c, p.size, color(p.ink))
        }
    }

    // ---- Side elevation ----

    /** 0 = seen from the right side (climbing left to right), 1 = from the left (mirrored). */
    val sideLabels = listOf("Right side", "Left side")
    /** A curved stair's two elevations, each unrolled along its own edge. */
    val curvedSideLabels = listOf("Inside (open) side", "Outside (wall) side")
    fun sideLabelsFor(s: DrawingElement.Symbol) = if (type(s.symbolName) == Type.CURVED) curvedSideLabels else sideLabels

    private const val MARGIN_IN = 24f

    /** Developed length (inches) of the run: flights and landing laid out flat. */
    private fun developedRunIn(s: DrawingElement.Symbol, side: Int = 0): Float {
        val l = layout(s)
        return when (type(s.symbolName)) {
            Type.CURVED -> curve(s).let { c -> c.treadAt(if (side == 1) c.ro else c.ri) * l.treads }
            Type.L_LANDING, Type.U_LANDING -> l.treads1 * l.tread + l.width + l.treads2 * l.tread
            Type.SPIRAL -> l.width
            else -> l.treads * l.tread
        }
    }

    fun elevationSize(s: DrawingElement.Symbol, side: Int, state: CanvasState): Size {
        val l = layout(s)
        val railTop = maxOf(prop(s, RAIL_H), prop(s, GUARD_H)) + 6f
        return Size(state.inchesToPx(developedRunIn(s, side) + 2f * MARGIN_IN), state.inchesToPx(l.rise + railTop))
    }

    /**
     * The side elevation as canvas elements; [origin] is the left end of the lower floor line.
     * Straight and deck stairs are a true side view; an L or U is drawn unfolded (first
     * flight, landing, second flight in one line), and a spiral is projected onto the page.
     */
    fun buildElevation(s: DrawingElement.Symbol, side: Int, origin: Offset, state: CanvasState, color: Color): List<DrawingElement> {
        val inch = state.inchesToPx(1f)
        val t = type(s.symbolName)
        val l = layout(s)
        val curved = t == Type.CURVED
        val R = l.riser
        // A curved stair is unrolled along the edge it is seen from: its tread there.
        val T = if (curved) curve(s).let { c -> c.treadAt(if (side == 1) c.ro else c.ri) } else l.tread
        val nose = l.nosing
        val n = l.risers
        val run = developedRunIn(s, side)
        val total = run + 2f * MARGIN_IN
        val mirror = side == 1 && !curved
        val out = mutableListOf<DrawingElement>()
        val heavy = 3f
        val medium = 2f
        val light = 1f
        // x in inches from the lower floor's start of the stair (the first riser at 0).
        fun px(xIn: Float, hIn: Float): Offset {
            val fromLeft = MARGIN_IN + xIn
            val x = if (mirror) total - fromLeft else fromLeft
            return Offset(origin.x + x * inch, origin.y - hIn * inch)
        }
        fun line(x1: Float, h1: Float, x2: Float, h2: Float, sw: Float = light) {
            out += DrawingElement.Line(start = px(x1, h1), end = px(x2, h2), color = color, strokeWidth = sw)
        }
        fun box(x1: Float, x2: Float, h1: Float, h2: Float, sw: Float = light) {
            val a = px(x1, h2)
            val b = px(x2, h1)
            out += DrawingElement.Rectangle(
                topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)), size = Size(abs(b.x - a.x), abs(b.y - a.y)),
                color = color, strokeWidth = sw
            )
        }
        fun text(str: String, xIn: Float, hIn: Float, sizeIn: Float = 4f) {
            val p = px(xIn, hIn)
            val x = if (mirror) p.x - str.length * sizeIn * inch * 0.5f else p.x
            out += DrawingElement.Text(text = str, topLeft = Offset(x, p.y), fontSize = (sizeIn * inch).coerceIn(8f, 30f), color = color)
        }
        fun dim(x1: Float, h1: Float, x2: Float, h2: Float, offsetIn: Float) {
            var a = px(x1, h1)
            var b = px(x2, h2)
            // Keep horizontal dimensions left to right so the offset lands on the same side.
            if (abs(a.y - b.y) < 0.01f && a.x > b.x) { val tmp = a; a = b; b = tmp }
            val len = (b - a).getDistance() / inch
            if (len < 0.5f) return
            out += DrawingElement.Dimension(
                start = a, end = b, value = if (state.unitSystem == UnitSystem.METRIC) len * 0.0254f else len / 12f,
                unit = state.unitSystem, format = state.dimensionFormat, color = color, offset = offsetIn * inch
            )
        }

        val nearKind = if (curved) code(s, if (side == 1) RAIL_RIGHT else RAIL_LEFT) else code(s, if (mirror) RAIL_LEFT else RAIL_RIGHT)
        // Unrolled, the far edge of a curved stair runs at another pitch: leave its rail off.
        val farKind = if (curved) RAIL_NONE else code(s, if (mirror) RAIL_RIGHT else RAIL_LEFT)
        val railH = prop(s, RAIL_H)
        val guardH = prop(s, GUARD_H)

        // Floors: the lower floor across the whole view, the upper floor from the top riser on.
        line(-MARGIN_IN, 0f, run + MARGIN_IN, 0f, heavy)

        if (t == Type.SPIRAL) {
            val r = l.width / 2f
            val cx = r
            val step = 360f / prop(s, PER_TURN).coerceAtLeast(3f)
            // Centre pole, then each tread at its height reaching out to where its outer edge
            // lies across the page.
            box(cx - 2.5f, cx + 2.5f, 0f, l.rise + railH, medium)
            val outerPts = mutableListOf<Pair<Float, Float>>()
            for (i in 0 until n - 1) {
                val a = (90f - i * step - step / 2f) * PI.toFloat() / 180f
                val x = cx + r * cos(a.toDouble()).toFloat()
                val hTop = (i + 1) * R
                box(minOf(cx, x), maxOf(cx, x), hTop - 1.5f, hTop, light)
                outerPts += Pair(x, hTop)
            }
            line(cx - r, n * R, cx + r + MARGIN_IN, n * R, heavy) // upper floor
            if (nearKind != RAIL_NONE) {
                for (k in 0 until outerPts.size - 1) {
                    val (x1, h1) = outerPts[k]
                    val (x2, h2) = outerPts[k + 1]
                    line(x1, h1 + railH, x2, h2 + railH, medium)
                    if (nearKind == RAIL_GUARD) line(x1, h1, x1, h1 + railH, light)
                }
                outerPts.lastOrNull()?.let { (x, h) -> if (nearKind == RAIL_GUARD) line(x, h, x, h + railH, light) }
            }
            dim(cx - r, 0f, cx + r, 0f, 10f)
            dim(cx + r + 6f, 0f, cx + r + 6f, l.rise, 0f)
            text("Spiral stair, ${formatFeetInches(l.width / 12f)} dia. ${summary(s)}", -MARGIN_IN, -20f)
            return out
        }

        // Where each riser stands along the developed run, and the walking-surface heights.
        val landingAfter = if (t == Type.L_LANDING || t == Type.U_LANDING) l.flight1 - 1 else -1
        val riserX = FloatArray(n)
        for (i in 0 until n) {
            riserX[i] = if (landingAfter >= 0 && i > landingAfter) l.treads1 * T + l.width + (i - landingAfter - 1) * T else i * T
        }
        val topX = riserX[n - 1]
        line(topX - nose, n * R, run + MARGIN_IN, n * R, heavy) // upper floor
        line(topX, n * R, topX, n * R - 10f, medium)              // floor framing edge at the top

        // Treads and risers.
        val open = t == Type.DECK
        for (i in 0 until n) {
            val x = riserX[i]
            if (!open) line(x, i * R, x, (i + 1) * R, medium)
            if (i < n - 1) {
                val xEnd = riserX[i + 1]
                if (open) box(x - nose, xEnd, (i + 1) * R - 1.5f, (i + 1) * R, medium)
                else line(x - nose, (i + 1) * R, xEnd, (i + 1) * R, medium)
            }
        }
        // Stringer under each flight: a line parallel to the pitch, 5" below the inside corners
        // (open stringers 3 1/2", what is left of a cut 2x12).
        val throat = if (open) 3.5f else 5f
        val slope = R / T
        val drop = throat * sqrt(1f + slope * slope)
        fun stringer(x0: Float, h0: Float, x1: Float) {
            // From where the throat line meets the level it starts on to the top of the flight.
            val startX = x0 + drop / slope
            val endH = h0 + slope * (x1 - x0) - drop
            if (x1 > startX) line(startX, h0, x1, endH, medium)
            line(x1, endH, x1, h0 + slope * (x1 - x0), light)
            if (open) {
                // The cut stringer's sawtooth shows between the open treads.
                var k = 0
                var x = x0
                while (x < x1 - 0.1f) {
                    line(x, h0 + k * R, x, h0 + (k + 1) * R - 1.5f, light)
                    x += T
                    k++
                }
            }
        }
        if (landingAfter >= 0) {
            stringer(0f, 0f, riserX[landingAfter])
            val lx = riserX[landingAfter]
            val lh = (landingAfter + 1) * R
            // Landing: platform at the top of the first flight, its framing below.
            box(lx, riserX[landingAfter + 1], lh - 10f, lh, light)
            line(lx + 2f, 0f, lx + 2f, lh - 10f, light)
            line(riserX[landingAfter + 1] - 2f, 0f, riserX[landingAfter + 1] - 2f, lh - 10f, light)
            stringer(riserX[landingAfter + 1], lh, topX)
        } else stringer(0f, 0f, topX)

        // Rail line over the nosings: sloped over each flight, level across a landing.
        val nosings = (0 until n).map { i -> Pair(riserX[i] - nose, (i + 1) * R) }
        val railPts = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until n) {
            val (x, h) = nosings[i]
            if (i > 0) {
                val (px0, ph0) = nosings[i - 1]
                if (x - px0 > T * 1.01f) railPts += Pair(x - T, ph0) // across the landing at its height
            }
            railPts += Pair(x, h)
        }
        fun railAt(x: Float): Float {
            if (x <= railPts.first().first) return railPts.first().second
            for (k in 0 until railPts.size - 1) {
                val (x1, h1) = railPts[k]
                val (x2, h2) = railPts[k + 1]
                if (x <= x2) return h1 + (h2 - h1) * (x - x1) / (x2 - x1)
            }
            return railPts.last().second
        }
        fun surfaceAt(x: Float): Float {
            var h = 0f
            for (i in 0 until n) if (x >= riserX[i] - nose) h = (i + 1) * R
            return h
        }
        fun drawRail(kind: Int, near: Boolean) {
            if (kind == RAIL_NONE) return
            val sw = if (near) medium else light
            val hgt = if (kind == RAIL_GUARD) maxOf(railH, guardH) else railH
            for (k in 0 until railPts.size - 1) {
                val (x1, h1) = railPts[k]
                val (x2, h2) = railPts[k + 1]
                line(x1, h1 + hgt, x2, h2 + hgt, sw)
                if (near) line(x1, h1 + hgt - 1.5f, x2, h2 + hgt - 1.5f, light)
            }
            val (lastX, lastH) = railPts.last()
            // 12" level extension at the top of a handrail; a guard carries on over the floor.
            line(lastX, lastH + hgt, lastX + 12f, lastH + hgt, sw)
            if (!near) return
            if (kind == RAIL_HANDRAIL) {
                // Wall brackets every 4' or so.
                var x = railPts.first().first + 6f
                while (x < lastX) { line(x, railAt(x) + hgt - 1.5f, x, railAt(x) + hgt - 4f, light); x += 48f }
            } else {
                // Newel posts at the bottom and top, 3 1/2" square, and balusters under 4" apart.
                val (firstX, _) = railPts.first()
                box(firstX - T * 0.5f - 1.75f, firstX - T * 0.5f + 1.75f, 0f, hgt + R + 6f, medium)
                box(lastX + 12f, lastX + 15.5f, lastH, lastH + hgt + 6f, medium)
                line(firstX - T * 0.5f, R + hgt, firstX, R + hgt, sw)
                val per = ceil(T / 5.25f).toInt().coerceAtLeast(1)
                val spacing = T / per
                var x = firstX + spacing / 2f
                while (x < lastX + 12f) {
                    val bottom = if (x > lastX) lastH else surfaceAt(x)
                    val top = if (x > lastX) lastH + hgt else railAt(x) + hgt - 1.5f
                    if (top > bottom) box(x - 0.625f, x + 0.625f, bottom, top, light)
                    x += if (x > lastX - 0.1f) 4.75f else spacing
                }
            }
        }
        drawRail(farKind, near = false)
        drawRail(nearKind, near = true)

        // Dimensions: total run under the floor, total rise at the bottom end, rail height.
        dim(0f, 0f, topX, 0f, 10f)
        dim(-12f, 0f, -12f, n * R, 0f)
        if (nearKind != RAIL_NONE && railPts.size > 1) {
            val (x, h) = railPts[railPts.size / 2]
            val hgt = if (nearKind == RAIL_GUARD) maxOf(railH, guardH) else railH
            dim(x, h, x, h + hgt, 0f)
        }
        val unfolded = when {
            landingAfter >= 0 -> " (unfolded)"
            curved -> curve(s).let { c -> " (unrolled along the ${if (side == 1) "outside, R ${formatFeetInches(c.ro / 12f)}" else "inside, R ${formatFeetInches(c.ri / 12f)}"})" }
            else -> ""
        }
        text("${summary(s)}$unfolded", -MARGIN_IN, -20f)
        return out
    }
}
