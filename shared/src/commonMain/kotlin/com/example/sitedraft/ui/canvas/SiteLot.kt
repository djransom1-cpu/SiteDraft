package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The site plan's lot (Dan, Nov 22), on the Site layer, from the survey's bearings and distances
 * ([SitePlan] in the building settings). One live symbol draws it all from the drawing:
 *  - the lot lines with their bearing and distance, the closure if the calls don't close
 *  - each line's setback as a dashed line inside the lot
 *  - the house: the outside of the first-floor exterior walls, with its distance to each lot line
 *  - a north arrow and the street's name
 * The surveyor's own site plan, when there is one, is imported onto the Site layer as a PDF and
 * calibrated like any plan; this is for the jobs typed in from the survey.
 */
object SiteLot {
    const val CATEGORY = "Site"
    const val SYMBOL = "Lot"
    const val ID = "site:lot"
    const val LAYER = "site"
    /** Text is sized for paper at 1" = 20' (denominator 240). */
    private const val TEXT_REAL_IN = 0.1f * 240f

    fun isLot(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY && e.symbolName == SYMBOL

    /**
     * A bearing as surveys write it, in degrees clockwise from north (0..360): "N 45°30'15\" E",
     * "N45-30-15E", "S 12 05 W", "N 45.5 E". Null when it can't be read.
     */
    fun azimuthOf(bearing: String): Double? {
        val t = bearing.trim().uppercase().replace("°", " ").replace("'", " ").replace("\"", " ").replace("-", " ").replace("’", " ").replace("”", " ")
        val m = Regex("""^([NS])\s*([0-9.]+)(?:\s+([0-9.]+))?(?:\s+([0-9.]+))?\s*([EW])$""").find(t.replace(Regex("\\s+"), " ").trim()) ?: return null
        val (ns, d, mi, se, ew) = m.destructured
        val deg = (d.toDoubleOrNull() ?: return null) + (mi.toDoubleOrNull() ?: 0.0) / 60.0 + (se.toDoubleOrNull() ?: 0.0) / 3600.0
        if (deg > 90.0) return null
        return when {
            ns == "N" && ew == "E" -> deg
            ns == "S" && ew == "E" -> 180.0 - deg
            ns == "S" && ew == "W" -> 180.0 + deg
            else -> (360.0 - deg) % 360.0
        }
    }

    /** The calls walked from the point of beginning, in feet on the page before placing (x east, y south). */
    fun traverse(site: SitePlan): List<Offset>? {
        if (site.calls.isEmpty()) return null
        var p = Offset.Zero
        val out = mutableListOf(p)
        for (c in site.calls) {
            val az = azimuthOf(c.bearing) ?: return null
            val r = az * PI / 180.0
            p += Offset((sin(r) * c.distanceFt).toFloat(), (-cos(r) * c.distanceFt).toFloat())
            out += p
        }
        return out
    }

    /** How far the last point lands from the point of beginning, feet, and the precision (1 : n), n null when closed exactly. */
    fun closure(site: SitePlan): Pair<Float, Int?>? {
        val pts = traverse(site) ?: return null
        val miss = (pts.last() - pts.first()).getDistance()
        val perimeter = site.calls.sumOf { it.distanceFt.toDouble() }.toFloat()
        return miss to (if (miss < 0.005f) null else (perimeter / miss).roundToInt())
    }

    /** A line of the house's roof plan as copied onto the site plan. */
    data class RoofLine(val a: Offset, val b: Offset, val heavy: Boolean)

    /**
     * The site plan in its own frame (world px, origin the middle of the lot): the lot's corners
     * (closed back to the start when the calls close), the setbacks, the house's footprint and a
     * copy of its roof plan, the extent and which way north is, all turned to fit the sheet.
     */
    data class Geometry(
        val corners: List<Offset>,
        val closes: Boolean,
        val setback: List<Offset>?,
        /** The outside of the walls, four corners, or null with no exterior walls. */
        val house: List<Offset>?,
        val roof: List<RoofLine>,
        val extent: Rect,
        val north: Offset,
        /** The garage, wing and porches (Nov 23): name and outline, on the lot like the house. */
        val addOns: List<Pair<String, List<Offset>>> = emptyList()
    )

    fun geometry(state: CanvasState): Geometry? {
        val site = state.building.site ?: return null
        val feet = traverse(site) ?: return null
        if (feet.size < 3) return null
        val k = state.inchesToPx(12f) // world px per foot
        val closes = (feet.last() - feet.first()).getDistance() < 0.05f
        val ring = if (closes) feet.dropLast(1) else feet
        val mid = Offset(ring.map { it.x }.average().toFloat(), ring.map { it.y }.average().toFloat())
        // The whole site plan turned to fit the sheet.
        val v = site.viewRotationDeg * PI.toFloat() / 180f
        val cv = cos(v); val sv = sin(v)
        fun view(p: Offset) = Offset(p.x * cv - p.y * sv, p.x * sv + p.y * cv)
        val lot = ring.map { view((it - mid) * k) }
        // The house: its footprint and roof plan, centred on the lot, turned and moved on it.
        val fp = RoofPlan.footprint(state)
        val h = site.rotationDeg * PI.toFloat() / 180f
        val ch = cos(h); val sh = sin(h)
        val at = Offset(site.shiftEastFt * k, site.shiftSouthFt * k)
        fun house(p: Offset): Offset {
            val d = p - (fp?.center ?: Offset.Zero)
            return view(Offset(d.x * ch - d.y * sh, d.x * sh + d.y * ch) + at)
        }
        val houseCorners = fp?.let { listOf(it.topLeft, it.topRight, it.bottomRight, it.bottomLeft).map(::house) }
        val houseRoof = state.building.roof?.let { r -> RoofPlan.geometry(state, r) }?.lines?.let { Wings.trimHouseRoof(it, state) }
            ?.filter { it.kind == RoofPlan.Kind.EAVE || it.kind == RoofPlan.Kind.RIDGE || it.kind == RoofPlan.Kind.HIP }
            ?.map { RoofLine(house(it.a), house(it.b), it.kind == RoofPlan.Kind.EAVE) } ?: emptyList()
        // The garage, wing and porches go on the lot too, with their roofs: the setbacks are checked to all of it.
        val boxes = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { !DerivedLayers.isGenerated(it) }
        val addOns = boxes.mapNotNull { e ->
            val name = when { Garages.isGarage(e) -> "GARAGE"; Wings.isWing(e) -> "WING"; Porches.isPorch(e) -> "PORCH"; else -> return@mapNotNull null }
            val r = e.boundingRect()
            name to listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft).map(::house)
        }
        val addOnRoof = boxes.flatMap { e ->
            when { Garages.isGarage(e) -> Garages.roof3d(e, state); Wings.isWing(e) -> Wings.roof3d(e, state); else -> emptyList() }
        }.filter { it.kind == RoofPlan.Kind.EAVE || it.kind == RoofPlan.Kind.RIDGE || it.kind == RoofPlan.Kind.HIP }
            .map { RoofLine(house(it.a), house(it.b), it.kind == RoofPlan.Kind.EAVE) }
        val roof = houseRoof + addOnRoof
        val setback = if (closes) setbackRing(lot, site.calls.map { it.setbackFt * k }) else null
        val all = lot + (houseCorners ?: emptyList()) + roof.flatMap { listOf(it.a, it.b) } + addOns.flatMap { it.second }
        val pad = 30f * k
        val extent = Rect(all.minOf { it.x } - pad, all.minOf { it.y } - pad, all.maxOf { it.x } + pad, all.maxOf { it.y } + pad)
        return Geometry(if (closes) lot + lot.first() else lot, closes, setback, houseCorners, roof, extent, view(Offset(0f, -1f)), addOns)
    }

    /** Where a new site plan goes: to the right of everything drawn, ten feet clear. */
    fun spotFor(state: CanvasState, size: androidx.compose.ui.geometry.Size): Offset {
        val k = state.inchesToPx(12f)
        val rects = state.elements.filter { !isLot(it) }.map { it.boundingRect() }.filter { it.width > 0f || it.height > 0f }
        if (rects.isEmpty()) return Offset(size.width / 2f, size.height / 2f)
        return Offset(rects.maxOf { it.right } + 10f * k + size.width / 2f, rects.minOf { it.top } + size.height / 2f)
    }

    /** Each edge moved in by its setback, consecutive ones met at their crossing: the buildable area. */
    private fun setbackRing(ring: List<Offset>, setbacks: List<Float>): List<Offset>? {
        val n = ring.size
        if (n < 3 || setbacks.all { it <= 0f }) return null
        val area2 = (0 until n).sumOf { i -> val p = ring[i]; val q = ring[(i + 1) % n]; (p.x * q.y - q.x * p.y).toDouble() }
        // Screen y is down: a positive sum runs clockwise on screen, and inside is to the right of each edge.
        val inSign = if (area2 > 0) 1f else -1f
        val lines = (0 until n).map { i ->
            val p = ring[i]; val q = ring[(i + 1) % n]
            val d = q - p; val len = d.getDistance().coerceAtLeast(1e-3f)
            val normal = Offset(-d.y / len, d.x / len) * inSign
            val off = normal * setbacks.getOrElse(i) { 0f }
            (p + off) to (q + off)
        }
        return (0 until n).map { i ->
            val (a1, a2) = lines[(i - 1 + n) % n]
            val (b1, b2) = lines[i]
            cross(a1, a2, b1, b2) ?: b1
        }
    }

    private fun cross(a1: Offset, a2: Offset, b1: Offset, b2: Offset): Offset? {
        val r = a2 - a1; val s = b2 - b1
        val den = r.x * s.y - r.y * s.x
        if (abs(den) < 1e-6f) return null
        val t = ((b1.x - a1.x) * s.y - (b1.y - a1.y) * s.x) / den
        return a1 + r * t
    }

    /** The nearest point to [p] on segment [a]-[b]. */
    fun nearestOn(p: Offset, a: Offset, b: Offset): Offset {
        val d = b - a
        val l2 = d.x * d.x + d.y * d.y
        if (l2 <= 0f) return a
        val t = (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / l2).coerceIn(0f, 1f)
        return a + d * t
    }

    /** The house's nearest corner to each lot line and that line's nearest point: for checking the setbacks. */
    fun houseDistances(g: Geometry): List<Pair<Offset, Offset>> {
        val hc = (g.house ?: return emptyList()) + g.addOns.flatMap { it.second }
        return (0 until g.corners.size - 1).map { i ->
            val a = g.corners[i]; val b = g.corners[i + 1]
            hc.map { c -> c to nearestOn(c, a, b) }.minByOrNull { (c, n) -> (c - n).getDistance() }!!
        }
    }

    private fun feetText(ft: Float): String {
        val inches = (ft * 12f * 2f).roundToInt() / 2f
        val f = (inches / 12f).toInt()
        return "$f'-${formatInches(inches - f * 12f)}\""
    }

    /** Draws the lot, the setbacks, the house with its distances, the north arrow and the street. */
    fun DrawScope.drawLot(element: DrawingElement.Symbol, state: CanvasState, ink: Color) {
        val site = state.building.site ?: return
        val g0 = geometry(state) ?: return
        // Drawn where the site plan's frame was put: drag the frame and the whole site plan goes with it.
        val o = element.center - g0.extent.center
        fun m(p: Offset) = p + o
        val g = g0.copy(
            corners = g0.corners.map(::m), setback = g0.setback?.map(::m), house = g0.house?.map(::m),
            roof = g0.roof.map { RoofLine(m(it.a), m(it.b), it.heavy) },
            extent = g0.extent.translate(o),
            addOns = g0.addOns.map { (n, pts) -> n to pts.map(::m) }
        )
        val k = state.inchesToPx(1f)
        val heavy = max(k * 3f, 1.5f)
        val thin = max(k * 1f, 0.7f)
        val text = TEXT_REAL_IN * k
        val dash = PathEffect.dashPathEffect(floatArrayOf(k * 36f, k * 18f))
        val dashDot = PathEffect.dashPathEffect(floatArrayOf(k * 60f, k * 12f, k * 6f, k * 12f))
        // Lot lines (property line: long dash, dot) with the bearing and distance along each.
        for (i in 0 until g.corners.size - 1) {
            val a = g.corners[i]; val b = g.corners[i + 1]
            drawLine(ink, a, b, strokeWidth = heavy, pathEffect = dashDot)
            val call = site.calls.getOrNull(i) ?: continue
            val label = "${call.bearing.trim()}  ${fixed2(call.distanceFt)}'"
            var ang = (atan2(b.y - a.y, b.x - a.x) * 180.0 / PI).toFloat()
            if (ang > 90f) ang -= 180f
            if (ang < -90f) ang += 180f
            val m = (a + b) / 2f
            val w = label.length * text * 0.6f
            rotate(ang, pivot = m) {
                drawPageText(label, Offset(m.x - w / 2f, m.y - text * 1.6f), text, ink, bold = false, maxWidth = w + text * 2f)
            }
        }
        if (!g.closes) {
            val (miss, _) = closure(site)!!
            drawPageText("CALLS DO NOT CLOSE: ${fixed2(miss)}' MISSING", g.corners.last() + Offset(text, text), text, ink, bold = true, maxWidth = text * 40f)
        }
        // Setbacks, dashed, inside the lot.
        g.setback?.let { sb ->
            for (i in sb.indices) drawLine(ink, sb[i], sb[(i + 1) % sb.size], strokeWidth = thin, pathEffect = dash)
        }
        // The house, and its distance to each lot line.
        g.house?.let { h ->
            // The house: its walls, and the copy of its roof plan over them (eave, ridge, hips).
            for (i in h.indices) drawLine(ink, h[i], h[(i + 1) % h.size], strokeWidth = heavy * 1.3f)
            for (l in g.roof) drawLine(ink, l.a, l.b, strokeWidth = if (l.heavy) heavy else thin, pathEffect = if (l.heavy) dash else null)
            val c = Offset(h.map { it.x }.average().toFloat(), h.map { it.y }.average().toFloat())
            drawPageText("HOUSE", Offset(c.x - text * 1.5f, c.y + text * 0.6f), text, ink, bold = true, maxWidth = text * 6f)
            for ((name, pts) in g.addOns) {
                for (i in pts.indices) drawLine(ink, pts[i], pts[(i + 1) % pts.size], strokeWidth = heavy * 1.3f)
                val ac = Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
                drawPageText(name, Offset(ac.x - text * name.length * 0.3f, ac.y + text * 0.6f), text, ink, bold = true, maxWidth = text * 8f)
            }
            for ((i, cn) in houseDistances(g).withIndex()) {
                val (c, n) = cn
                val d = (c - n).getDistance() / (12f * k)
                if (d < 0.1f) continue
                drawLine(ink, c, n, strokeWidth = thin)
                val m = (c + n) / 2f
                // Flag a building closer to the line than its setback.
                val sb = site.calls.getOrNull(i)?.setbackFt ?: 0f
                // A corner past the line (outside the lot) is over the property line, not just close to it.
                val a0 = g.corners[i]; val b0 = g.corners[i + 1]
                val lotC = Offset(g.corners.map { it.x }.average().toFloat(), g.corners.map { it.y }.average().toFloat())
                fun side(p: Offset) = (b0.x - a0.x) * (p.y - a0.y) - (b0.y - a0.y) * (p.x - a0.x)
                val over = g.closes && side(c) * side(lotC) < 0f
                val t = when {
                    over -> "${feetText(d)} OVER THE PROPERTY LINE"
                    sb > 0f && d < sb - 0.01f -> "${feetText(d)} - INSIDE ${feetText(sb)} SETBACK"
                    else -> feetText(d)
                }
                drawPageText(t, m + Offset(text * 0.3f, -text * 0.6f), text, ink, bold = over || (sb > 0f && d < sb - 0.01f), maxWidth = text * 24f)
            }
        }
        // North arrow at the top right, and the street's name.
        val at = Offset(g.extent.right - text * 4f, g.extent.top + text * 5f)
        val tip = at + g.north * (text * 3f)
        val tail = at - g.north * (text * 3f)
        drawLine(ink, tail, tip, strokeWidth = heavy)
        val side = Offset(-g.north.y, g.north.x) * text
        drawLine(ink, tip, tip - g.north * text * 1.2f + side, strokeWidth = heavy)
        drawLine(ink, tip, tip - g.north * text * 1.2f - side, strokeWidth = heavy)
        drawPageText("N", tip + g.north * text * 0.6f - Offset(text * 0.3f, text * 0.6f), text, ink, bold = true, maxWidth = text * 2f)
        if (site.street.isNotBlank()) {
            drawPageText(site.street.uppercase(), Offset(g.extent.left + text, g.extent.bottom - text * 2f), text * 1.4f, ink, bold = true, maxWidth = text * 40f)
        }
    }

    /** Feet to two places, as surveys write them: 150.00. */
    fun fixed2(v: Float): String {
        val r = (v * 100f).roundToInt()
        val whole = r / 100
        val frac = abs(r % 100)
        return "$whole.${if (frac < 10) "0" else ""}$frac"
    }
}
