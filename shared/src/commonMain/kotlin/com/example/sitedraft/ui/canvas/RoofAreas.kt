package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A traced roof (Dan, Nov 26 2026: the Ransom House, an 1883 house with a west gable, a hip at the foyer, a flat where
 * the rafters frame in, a main ridge and a one-story wing; a rectangle can't describe it). Dan traces each roof area and
 * says what each side is ([RoofEdge]): an eave, a gable, or open where it runs into another roof.
 *
 * How the roof is worked out, the way it is framed:
 *  - each eave makes a plane rising at the area's pitch from where the roof meets the wall (top of plate plus the heel);
 *  - within an area the lowest plane wins, so planes meet in ridges and hips; a flat caps them (the flat's edge is the
 *    ridge the rafters frame into, as ProCalc's flat-roof ridge);
 *  - where areas overlap the highest roof wins, so they meet in valleys (a gable running into a main roof), and a lower
 *    roof that never reaches the higher one stops against its wall under the eave (a one-story wing).
 *
 * The lines (ridges, hips, valleys, the flat's edge) are the exact plane intersections, found where a fine grid over the
 * roof changes from one plane to the next. Plan inches: x east, y south, as the walls are drawn.
 */
object RoofAreas {
    /** Grid step, inches (the lines are exact; this only finds where they start and stop). */
    private const val STEP_IN = 2f
    private const val FLAT = 31

    fun active(state: CanvasState): Boolean = state.building.roof?.areas?.any { min(it.xs.size, it.ys.size) >= 3 } == true

    /** A roof surface, z = c0 + cx x + cy y (inches): an eave's plane, or a flat. */
    data class Surf(val c0: Float, val cx: Float, val cy: Float, val nx: Float = 0f, val ny: Float = 0f, val edge: Int = -1) {
        fun z(x: Float, y: Float) = c0 + cx * x + cy * y
    }

    class Area(
        val src: RoofArea, val poly: List<Offset>, val outer: List<Offset>, val inward: List<Offset>, val edges: List<RoofEdge>,
        val planes: List<Surf>, var flat: Surf?, val base: Float, val pitch: Float
    ) {
        fun surf(label: Int): Surf = if (label == FLAT) (flat ?: Surf(base, 0f, 0f)) else planes[label]
        val label: String get() = src.name.ifBlank { src.id }
    }

    fun inside(p: List<Offset>, x: Float, y: Float): Boolean {
        var c = false
        var j = p.size - 1
        for (i in p.indices) {
            val a = p[i]; val b = p[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    private fun meet(p1: Offset, d1: Offset, p2: Offset, d2: Offset): Offset? {
        val cr = d1.x * d2.y - d1.y * d2.x
        if (abs(cr) < 1e-6f) return null
        val w = p2 - p1
        val t = (w.x * d2.y - w.y * d2.x) / cr
        return p1 + d1 * t
    }

    /** The areas worked out: their planes, overhang outlines and flats. */
    fun areas(state: CanvasState): List<Area> {
        val s = state.building
        val r = s.roof ?: return emptyList()
        val plate = ExteriorElevation.plateIn(s)
        val out = mutableListOf<Area>()
        for (a in r.areas) {
            val n = min(a.xs.size, a.ys.size)
            if (n < 3) continue
            val poly = (0 until n).map { Offset(a.xs[it], a.ys[it]) }
            val pitch = a.pitch ?: r.pitch
            val slope = pitch / 12f
            val plateZ = if (a.wallHeightIn != null) s.datumIn + a.wallHeightIn else plate
            val base = plateZ + r.heelIn
            val edges = (0 until n).map { a.edges.getOrElse(it) { RoofEdge.EAVE } }
            val inward = MutableList(n) { Offset.Zero }
            val ovs = FloatArray(n)
            val planes = mutableListOf<Surf>()
            for (i in 0 until n) {
                val p = poly[i]; val q = poly[(i + 1) % n]
                val d = q - p
                val len = d.getDistance()
                if (len < 0.01f) continue
                var nn = Offset(-d.y / len, d.x / len)
                val m = (p + q) / 2f
                if (!inside(poly, m.x + nn.x * 0.5f, m.y + nn.y * 0.5f)) nn = -nn
                inward[i] = nn
                ovs[i] = when (edges[i]) { RoofEdge.EAVE -> a.overhangIn ?: r.overhangIn; RoofEdge.GABLE -> a.rakeIn ?: r.rakeIn; RoofEdge.OPEN -> 0f }
                if (edges[i] == RoofEdge.EAVE)
                    planes += Surf(base - (p.x * nn.x + p.y * nn.y) * slope, nn.x * slope, nn.y * slope, nn.x, nn.y, i)
            }
            val outer = (0 until n).map { i ->
                val h = (i - 1 + n) % n
                meet(poly[h] - inward[h] * ovs[h], poly[i] - poly[h], poly[i] - inward[i] * ovs[i], poly[(i + 1) % n] - poly[i])
                    ?: (poly[i] - inward[i] * ovs[i])
            }
            out += Area(a, poly, outer, inward, edges, planes, a.flatRiseIn?.let { Surf(base + it, 0f, 0f) }, base, pitch)
        }
        // A flat at another area's ridge (Dan: the west gable's ridge runs back to where the flat starts).
        for (ar in out) {
            val ref = ar.src.flatAtRidgeOf ?: continue
            val other = out.firstOrNull { it !== ar && (it.src.id == ref || it.src.name.equals(ref, ignoreCase = true)) } ?: continue
            ar.flat = Surf(ridgeZ(other), 0f, 0f)
        }
        return out
    }

    /** An area's own surface at a point (no other area): the lowest plane, capped by its flat. Label = plane index or [FLAT]. */
    fun own(a: Area, x: Float, y: Float): Pair<Float, Int> {
        var z = Float.MAX_VALUE
        var lab = FLAT
        a.planes.forEachIndexed { i, p -> val v = p.z(x, y); if (v < z) { z = v; lab = i } }
        if (lab == FLAT) z = a.base
        val f = a.flat
        if (f != null && z > f.c0) { z = f.c0; lab = FLAT }
        return z to lab
    }

    /** The highest point of an area's own roof inside its walls: its ridge. */
    fun ridgeZ(a: Area): Float {
        val xs = a.poly.map { it.x }; val ys = a.poly.map { it.y }
        var best = a.base
        var y = ys.min()
        while (y <= ys.max()) {
            var x = xs.min()
            while (x <= xs.max()) {
                if (inside(a.poly, x, y)) {
                    var z = Float.MAX_VALUE
                    for (p in a.planes) z = min(z, p.z(x, y))
                    if (a.planes.isNotEmpty()) best = max(best, z)
                }
                x += 1f
            }
            y += 1f
        }
        return best
    }

    /** The roof on top at a plan point: (z, area index, label), or null where there's no roof. */
    fun top(list: List<Area>, x: Float, y: Float): Triple<Float, Int, Int>? {
        var best: Triple<Float, Int, Int>? = null
        list.forEachIndexed { i, a ->
            if (!inside(a.outer, x, y)) return@forEachIndexed
            val (z, lab) = own(a, x, y)
            if (best == null || z > best!!.first + 0.01f) best = Triple(z, i, lab)
        }
        return best
    }

    enum class What { RIDGE, HIP, VALLEY, FLAT_EDGE, EAVE, RAKE, STEP, WALL, MEMBER }
    /** A line, plan inches, with its height at each end (for the elevations). */
    data class L3(val a: Offset, val za: Float, val b: Offset, val zb: Float, val what: What)

    class Built(val lines: List<L3>, val labels: List<Pair<String, Offset>>, val areas: List<Area>, val bounds: Rect, val memberText: String)

    private var cacheKey: Any? = null
    private var cache: Built? = null

    fun built(state: CanvasState): Built? {
        if (!active(state)) return null
        val s = state.building
        val key = listOf(s.roof, s.datumIn, ExteriorElevation.plateIn(s))
        if (key == cacheKey) return cache
        val b = runCatching { build(state) }.getOrNull()
        cacheKey = key; cache = b
        return b
    }

    private fun build(state: CanvasState): Built? {
        val r = state.building.roof ?: return null
        val list = areas(state)
        if (list.isEmpty()) return null
        val allX = list.flatMap { a -> a.outer.map { it.x } }
        val allY = list.flatMap { a -> a.outer.map { it.y } }
        val x0 = allX.min() - STEP_IN; val y0 = allY.min() - STEP_IN
        var g = STEP_IN
        while (((allX.max() - x0) / g + 3) * ((allY.max() - y0) / g + 3) > 400_000f) g *= 1.5f
        val nx = ceil((allX.max() - x0) / g).toInt() + 2
        val ny = ceil((allY.max() - y0) / g).toInt() + 2
        val grid = IntArray(nx * ny) { -1 }
        for (j in 0 until ny) for (i in 0 until nx) {
            val t = top(list, x0 + i * g, y0 + j * g) ?: continue
            grid[j * nx + i] = t.second * 32 + t.third
        }
        fun surfOf(code: Int) = list[code / 32].surf(code % 32)
        // Where the grid changes from one surface to another.
        val pts = HashMap<Long, MutableList<Offset>>()
        fun mark(c1: Int, c2: Int, p: Offset) {
            if (c1 == c2 || c1 < 0 || c2 < 0) return
            val k = min(c1, c2).toLong() shl 32 or max(c1, c2).toLong()
            pts.getOrPut(k) { mutableListOf() } += p
        }
        for (j in 0 until ny) for (i in 0 until nx) {
            val c = grid[j * nx + i]
            val x = x0 + i * g; val y = y0 + j * g
            if (i + 1 < nx) mark(c, grid[j * nx + i + 1], Offset(x + g / 2f, y))
            if (j + 1 < ny) mark(c, grid[(j + 1) * nx + i], Offset(x, y + g / 2f))
        }
        val lines = mutableListOf<L3>()
        fun runs(ts: List<Float>, gap: Float): List<Pair<Float, Float>> {
            if (ts.isEmpty()) return emptyList()
            val sorted = ts.sorted()
            val out = mutableListOf<Pair<Float, Float>>()
            var lo = sorted[0]; var prev = lo
            for (t in sorted.drop(1)) { if (t - prev > gap) { out += lo to prev; lo = t }; prev = t }
            out += lo to prev
            return out
        }
        for ((k, ps) in pts) {
            val ca = (k shr 32).toInt(); val cb = (k and 0xFFFFFFFFL).toInt()
            val sa = surfOf(ca); val sb = surfOf(cb)
            val sameArea = ca / 32 == cb / 32
            val diff = ps.map { abs(sa.z(it.x, it.y) - sb.z(it.x, it.y)) }.average().toFloat()
            val steep = list.maxOf { it.pitch } / 12f
            if (diff <= g * steep * 1.5f + 1f) {
                // A crease: the exact line where the two surfaces meet.
                val A = sa.cx - sb.cx; val B = sa.cy - sb.cy; val C = sa.c0 - sb.c0
                val l2 = A * A + B * B
                if (l2 < 1e-10f) continue
                val len = sqrt(l2)
                val p0 = Offset(-A * C / l2, -B * C / l2)
                val dir = Offset(-B / len, A / len)
                val what = when {
                    !sameArea -> What.VALLEY
                    ca % 32 == FLAT || cb % 32 == FLAT -> What.FLAT_EDGE
                    sa.nx * sb.nx + sa.ny * sb.ny < -0.9f -> What.RIDGE
                    else -> What.HIP
                }
                for ((t1, t2) in runs(ps.map { (it - p0).x * dir.x + (it - p0).y * dir.y }, g * 3f)) {
                    if (t2 - t1 < g) continue
                    val a = p0 + dir * (t1 - g / 2f); val b = p0 + dir * (t2 + g / 2f)
                    lines += L3(a, sa.z(a.x, a.y), b, sa.z(b.x, b.y), what)
                }
            } else {
                // A step: a lower roof stops against the higher one's wall (Dan: the wing under the main eave).
                val cx = ps.map { it.x }.average().toFloat(); val cy = ps.map { it.y }.average().toFloat()
                var sxx = 0f; var syy = 0f; var sxy = 0f
                for (p in ps) { val dx = p.x - cx; val dy = p.y - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
                val ang = 0.5f * kotlin.math.atan2(2f * sxy, sxx - syy)
                val dir = Offset(kotlin.math.cos(ang), kotlin.math.sin(ang))
                val c0 = Offset(cx, cy)
                val low = if (sa.z(cx, cy) < sb.z(cx, cy)) sa else sb
                for ((t1, t2) in runs(ps.map { (it - c0).x * dir.x + (it - c0).y * dir.y }, g * 3f)) {
                    if (t2 - t1 < g) continue
                    val a = c0 + dir * (t1 - g / 2f); val b = c0 + dir * (t2 + g / 2f)
                    lines += L3(a, low.z(a.x, a.y), b, low.z(b.x, b.y), What.STEP)
                }
            }
        }
        // Eaves and rakes where the area is the roof on top; the walls under them (dashed) where they're outside walls.
        list.forEachIndexed { ai, a ->
            val n = a.poly.size
            for (i in 0 until n) {
                if (a.edges[i] == RoofEdge.OPEN) continue
                val p = a.outer[i]; val q = a.outer[(i + 1) % n]
                val len = (q - p).getDistance()
                if (len < 0.5f) continue
                val d = (q - p) / len
                val ts = mutableListOf<Float>()
                var t = 0f
                while (t <= len) {
                    val m = p + d * t + a.inward[i] * 1f
                    if (top(list, m.x, m.y)?.second == ai) ts += t
                    t += g / 2f
                }
                for ((t1, t2) in runs(ts, g * 1.5f)) {
                    val s1 = max(0f, t1 - g / 4f); val s2 = min(len, t2 + g / 4f)
                    if (a.edges[i] == RoofEdge.EAVE) {
                        val e1 = p + d * s1; val e2 = p + d * s2
                        lines += L3(e1, own(a, e1.x, e1.y).first, e2, own(a, e2.x, e2.y).first, What.EAVE)
                    } else {
                        // A rake climbs to the ridge and down again: short pieces that follow the roof (the elevations need its heights).
                        val pieces = ceil((s2 - s1) / 4f).toInt().coerceAtLeast(1)
                        for (k in 0 until pieces) {
                            val e1 = p + d * (s1 + (s2 - s1) * k / pieces); val e2 = p + d * (s1 + (s2 - s1) * (k + 1) / pieces)
                            lines += L3(e1, own(a, e1.x, e1.y).first, e2, own(a, e2.x, e2.y).first, What.RAKE)
                        }
                    }
                }
                // The wall under it, where no other area covers it.
                val w1 = a.poly[i]; val w2 = a.poly[(i + 1) % n]
                val wl = (w2 - w1).getDistance()
                if (wl < 0.5f) continue
                val wd = (w2 - w1) / wl
                val ws = mutableListOf<Float>()
                t = 0f
                while (t <= wl) {
                    val m = w1 + wd * t - a.inward[i] * 1f
                    if (list.none { it !== a && inside(it.poly, m.x, m.y) }) ws += t
                    t += g / 2f
                }
                for ((t1, t2) in runs(ws, g * 1.5f)) lines += L3(w1 + wd * t1, a.base, w1 + wd * t2, a.base, What.WALL)
            }
        }
        // Rafters (the framing plan): square to each eave at the spacing, from the fascia up to where the plane ends.
        val oc = max(r.ocIn, 4f)
        list.forEachIndexed { ai, a ->
            a.planes.forEachIndexed { pi, pl ->
                val i = pl.edge
                val w1 = a.poly[i]; val w2 = a.poly[(i + 1) % a.poly.size]
                val wl = (w2 - w1).getDistance()
                val wd = (w2 - w1) / wl
                val nIn = a.inward[i]
                val ov = a.src.overhangIn ?: r.overhangIn
                val marks = buildList { var t = 0f; while (t < wl - 1f) { add(t); t += oc }; add(wl) }
                for (t in marks) {
                    val start = w1 + wd * t - nIn * ov
                    val ds = mutableListOf<Float>()
                    var d = 0f
                    var misses = 0
                    while (d < 3000f && misses < 40) {
                        val m = start + nIn * d
                        val hit = top(list, m.x, m.y)
                        if (hit != null && hit.second == ai && hit.third == pi) { ds += d; misses = 0 } else if (ds.isNotEmpty()) misses++
                        d += g
                    }
                    for ((d1, d2) in runs(ds, g * 1.5f)) {
                        val m1 = start + nIn * d1; val m2 = start + nIn * min(d2 + g / 2f, ds.last() + g / 2f)
                        lines += L3(m1, pl.z(m1.x, m1.y), m2, pl.z(m2.x, m2.y), What.MEMBER)
                    }
                }
            }
        }
        // Labels: the pitch on each plane, FLAT on a flat, at the middle of what each shows.
        val sums = HashMap<Int, FloatArray>()
        for (j in 0 until ny) for (i in 0 until nx) {
            val c = grid[j * nx + i]; if (c < 0) continue
            val acc = sums.getOrPut(c) { FloatArray(3) }
            acc[0] += x0 + i * g; acc[1] += y0 + j * g; acc[2] += 1f
        }
        val labels = sums.filter { it.value[2] * g * g > 36f * 36f }.map { (c, acc) ->
            val a = list[c / 32]
            val text = if (c % 32 == FLAT) "FLAT" else RoofPlan.pitchText(a.pitch)
            text to Offset(acc[0] / acc[2], acc[1] / acc[2])
        }
        val member = if (r.framing == RoofFraming.TRUSSES) "TRUSSES @ ${formatInches(r.ocIn)}\" O.C." else "${r.rafterSize} RAFTERS @ ${formatInches(r.ocIn)}\" O.C."
        return Built(lines, labels, list, Rect(allX.min(), allY.min(), allX.max(), allY.max()), member)
    }

    /** The traced roof's extent in world px (the roof symbol's frame). */
    fun boundsPx(state: CanvasState): Rect? {
        val b = built(state) ?: return null
        val k = state.inchesToPx(1f)
        return Rect(b.bounds.left * k, b.bounds.top * k, b.bounds.right * k, b.bounds.bottom * k)
    }

    /** Draws the traced roof: the framing plan ([framing], with the rafters) or the roof plan. */
    fun DrawScope.drawTraced(state: CanvasState, ink: Color, framing: Boolean) {
        val b = built(state) ?: return
        val k = state.inchesToPx(1f)
        if (k <= 0f) return
        val heavy = max(k * 1.0f, 1.5f)
        val thin = max(k * 0.4f, 0.8f)
        val faint = if (ink.luminance() > 0.5f) Color(0xFF9AA4B0) else Color(0xFF8A8A8A)
        val dash = PathEffect.dashPathEffect(floatArrayOf(k * 8f, k * 5f))
        val valleyDash = PathEffect.dashPathEffect(floatArrayOf(k * 14f, k * 4f))
        for (l in b.lines) {
            val a = l.a * k; val e = l.b * k
            when (l.what) {
                What.WALL -> drawLine(faint, a, e, strokeWidth = thin, pathEffect = dash)
                What.MEMBER -> if (framing) drawLine(ink, a, e, strokeWidth = thin)
                What.VALLEY -> drawLine(ink, a, e, strokeWidth = heavy, pathEffect = valleyDash)
                else -> drawLine(ink, a, e, strokeWidth = heavy)
            }
        }
        val text = k * 7f
        fun label(t: String, at: Offset, size: Float = text) {
            val w = size * 0.6f * t.length
            drawRect(state.backgroundColor, topLeft = Offset(at.x - w / 2f - size * 0.3f, at.y - size * 0.8f), size = androidx.compose.ui.geometry.Size(w + size * 0.6f, size * 1.6f))
            drawPageText(t, Offset(at.x - w / 2f, at.y - size * 0.6f), size, ink, bold = true, maxWidth = w + size * 2f)
        }
        for ((t, at) in b.labels) label(t, at * k)
        val c = b.bounds.center * k
        val bottom = Offset(c.x, b.bounds.bottom * k + text * 2.2f)
        label(if (framing) b.memberText else (state.building.roof?.covering?.label?.uppercase() ?: ""), bottom)
    }

    /** What the traced roof is, for the Roof panel and the connector. */
    fun summary(state: CanvasState): List<String> {
        val b = built(state) ?: return emptyList()
        val s = state.building
        return b.areas.map { a ->
            val ridge = ridgeZ(a)
            val flat = a.flat?.let { ", flat at ${Building.elevationText(it.c0)}" } ?: ""
            "${a.label}: ${RoofPlan.pitchText(a.pitch)}, plate ${Building.elevationText(a.base - (s.roof?.heelIn ?: 0f))}, ridge ${Building.elevationText(ridge)}$flat"
        }
    }
}
