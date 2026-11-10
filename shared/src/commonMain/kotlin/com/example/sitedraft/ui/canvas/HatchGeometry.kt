package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fill patterns as plain geometry (line segments and small circles), so a hatch can leave the
 * canvas: the DXF export writes these as real lines, clipped to the shape, on a HATCH layer.
 * Each pattern follows the same spacing and layout as its canvas version in Fill.kt; curves
 * (waves, shingles, gravel) come out as short straight runs.
 */
object HatchGeometry {
    data class Hatch(val segments: List<Pair<Offset, Offset>>, val dots: List<Pair<Offset, Float>>)

    /** The pattern laid over [b] (not yet clipped). SOLID and NONE give nothing. */
    fun pattern(fill: Fill, b: Rect): Hatch {
        val spacing = fill.spacing.coerceAtLeast(2f)
        val lw = fill.lineWidth.coerceAtLeast(0.5f)
        val seg = mutableListOf<Pair<Offset, Offset>>()
        val dots = mutableListOf<Pair<Offset, Float>>()
        fun line(a: Offset, c: Offset) { seg += a to c }
        fun poly(points: List<Offset>, closed: Boolean = false) {
            points.zipWithNext().forEach { (a, c) -> line(a, c) }
            if (closed && points.size > 2) line(points.last(), points.first())
        }
        fun hatch45(forward: Boolean, from: Float = 0f) {
            val step = spacing * 1.41421f
            val span = b.width + b.height
            var d = from
            while (d <= span) {
                if (forward) line(Offset(b.left + d, b.top), Offset(b.left + d - b.height, b.bottom))
                else line(Offset(b.left + d - b.height, b.top), Offset(b.left + d, b.bottom))
                d += step
            }
        }
        when (fill.pattern) {
            FillPattern.NONE, FillPattern.SOLID -> Unit
            FillPattern.DIAGONAL -> hatch45(true)
            FillPattern.DIAGONAL_BACK -> hatch45(false)
            FillPattern.CROSSHATCH -> { hatch45(true); hatch45(false) }
            FillPattern.STEEL -> { hatch45(true); hatch45(true, spacing * 0.35f) }
            FillPattern.TILE -> {
                val across = if (fill.length > 0f) fill.length else spacing
                var x = b.left
                while (x <= b.right) { line(Offset(x, b.top), Offset(x, b.bottom)); x += across }
                var y = b.top
                while (y <= b.bottom) { line(Offset(b.left, y), Offset(b.right, y)); y += spacing }
            }
            FillPattern.BRICK, FillPattern.PLANKS -> {
                val len = if (fill.length > 0f) fill.length else if (fill.pattern == FillPattern.BRICK) spacing * 2f else spacing * 6f
                var y = b.top
                var row = 0
                while (y <= b.bottom) {
                    line(Offset(b.left, y), Offset(b.right, y))
                    var x = b.left + if (fill.pattern == FillPattern.BRICK) (if (row % 2 == 0) 0f else len / 2f) else ((row * 2f * spacing) % len)
                    while (x <= b.right) { line(Offset(x, y), Offset(x, minOf(y + spacing, b.bottom))); x += len }
                    y += spacing
                    row++
                }
            }
            FillPattern.HERRINGBONE -> if (fill.length > spacing) {
                herringbonePlanks(b, spacing, fill.length).forEach { poly(it, closed = true) }
            } else {
                var y = b.top - spacing
                while (y < b.bottom + spacing) {
                    val pts = mutableListOf(Offset(b.left, y))
                    var x = b.left
                    var up = false
                    while (x <= b.right) { x += spacing; pts += Offset(x, if (up) y else y + spacing); up = !up }
                    poly(pts)
                    y += spacing
                }
            }
            FillPattern.INSULATION -> {
                val band = spacing * 2f
                var y = b.top
                while (y < b.bottom) {
                    val bottom = y + band
                    val pts = mutableListOf(Offset(b.left, bottom))
                    var x = b.left
                    var up = true
                    while (x <= b.right) { x += spacing; pts += Offset(x, if (up) y else bottom); up = !up }
                    poly(pts)
                    y = bottom
                }
            }
            FillPattern.HEXAGON -> {
                val r = spacing
                val w = 1.7320508f * r
                val h = 1.5f * r
                var j = 0
                var cy = b.top
                while (cy < b.bottom + r) {
                    var cx = b.left + if (j % 2 == 0) 0f else w / 2f
                    while (cx < b.right + w) {
                        poly((0..5).map { k ->
                            val a = (60.0 * k - 30.0) * PI / 180.0
                            Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
                        }, closed = true)
                        cx += w
                    }
                    cy += h
                    j++
                }
            }
            FillPattern.SHINGLE -> {
                val r = spacing / 2f
                var j = 0
                var y = b.top
                while (y < b.bottom + r) {
                    var x = b.left + if (j % 2 == 0) 0f else r
                    while (x < b.right + r) {
                        // Upper half circle (screen angles 180..360), as 8 straight runs.
                        poly((0..8).map { k ->
                            val a = PI + PI * k / 8.0
                            Offset(x + r * cos(a).toFloat(), y + r * sin(a).toFloat())
                        })
                        x += spacing
                    }
                    y += r
                    j++
                }
            }
            FillPattern.WAVES -> {
                var y = b.top
                while (y <= b.bottom) {
                    val pts = mutableListOf(Offset(b.left, y))
                    var x = b.left
                    var up = true
                    while (x < b.right) {
                        val cy = if (up) y - spacing * 0.4f else y + spacing * 0.4f
                        for (k in 1..6) {
                            val t = k / 6f
                            // Quadratic from (x, y) through control (x + s/2, cy) to (x + s, y).
                            val px = (1 - t) * (1 - t) * x + 2 * (1 - t) * t * (x + spacing / 2f) + t * t * (x + spacing)
                            val py = (1 - t) * (1 - t) * y + 2 * (1 - t) * t * cy + t * t * y
                            pts += Offset(px, py)
                        }
                        x += spacing
                        up = !up
                    }
                    poly(pts)
                    y += spacing
                }
            }
            FillPattern.DOTS -> {
                var y = b.top
                while (y <= b.bottom) {
                    var x = b.left
                    while (x <= b.right) { dots += Offset(x, y) to lw * 1.2f; x += spacing }
                    y += spacing
                }
            }
            FillPattern.EARTH -> {
                val len = spacing * 0.6f
                var j = 0
                var y = b.top
                while (y < b.bottom) {
                    var x = b.left + if (j % 2 == 0) 0f else spacing / 2f
                    while (x < b.right) { line(Offset(x, y + len), Offset(x + len, y)); x += spacing }
                    y += spacing * 0.75f
                    j++
                }
            }
            FillPattern.GRASS -> {
                val h = spacing * 0.35f
                var j = 0
                var y = b.top
                while (y < b.bottom) {
                    var x = b.left + if (j % 2 == 0) 0f else spacing / 2f
                    while (x < b.right) {
                        val base = Offset(x, y + h)
                        line(base, Offset(x, y)); line(base, Offset(x - h * 0.6f, y + h * 0.3f)); line(base, Offset(x + h * 0.6f, y + h * 0.3f))
                        x += spacing
                    }
                    y += spacing
                    j++
                }
            }
            FillPattern.CONCRETE -> {
                val cell = spacing * 1.5f
                var j = 0
                var y = b.top
                while (y < b.bottom) {
                    var i = 0
                    var x = b.left
                    while (x < b.right) {
                        val cx = x + jitter(i, j, 1) * cell
                        val cy = y + jitter(i, j, 2) * cell
                        if ((i + j) % 3 == 0) {
                            val r = spacing * 0.35f
                            poly(listOf(Offset(cx, cy - r), Offset(cx + r, cy + r * 0.7f), Offset(cx - r, cy + r * 0.7f)), closed = true)
                        } else dots += Offset(cx, cy) to lw * 1.2f
                        x += cell
                        i++
                    }
                    y += cell
                    j++
                }
            }
            FillPattern.GRAVEL -> {
                val cell = spacing * 1.3f
                var j = 0
                var y = b.top
                while (y < b.bottom) {
                    var i = 0
                    var x = b.left + if (j % 2 == 0) 0f else cell / 2f
                    while (x < b.right) {
                        val rx = spacing * 0.3f * (0.7f + 0.6f * jitter(i, j, 3))
                        val ry = rx * (0.6f + 0.4f * jitter(i, j, 4))
                        poly((0..11).map { k ->
                            val a = 2.0 * PI * k / 12.0
                            Offset(x + rx * cos(a).toFloat(), y + ry * sin(a).toFloat())
                        }, closed = true)
                        x += cell
                        i++
                    }
                    y += cell
                    j++
                }
            }
        }
        return Hatch(seg, dots)
    }

    /** The pattern clipped to [outline] (a closed polygon): what is inside it. */
    fun clipped(fill: Fill, outline: List<Offset>): Hatch {
        if (outline.size < 3) return Hatch(emptyList(), emptyList())
        val b = Rect(outline.minOf { it.x }, outline.minOf { it.y }, outline.maxOf { it.x }, outline.maxOf { it.y })
        if (b.width <= 0f || b.height <= 0f) return Hatch(emptyList(), emptyList())
        val raw = pattern(fill, b)
        return Hatch(
            raw.segments.flatMap { (a, c) -> clipSegment(a, c, outline) },
            raw.dots.filter { (c, _) -> inside(c, outline) }
        )
    }

    /** Even-odd point in polygon. */
    fun inside(p: Offset, poly: List<Offset>): Boolean {
        var result = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val c = poly[j]
            if ((a.y > p.y) != (c.y > p.y) && p.x < (c.x - a.x) * (p.y - a.y) / (c.y - a.y) + a.x) result = !result
            j = i
        }
        return result
    }

    /** The pieces of segment [a]-[c] that lie inside [poly] (or outside it, [keepInside] false). */
    fun clipSegment(a: Offset, c: Offset, poly: List<Offset>, keepInside: Boolean = true): List<Pair<Offset, Offset>> {
        val d = c - a
        if (d.x == 0f && d.y == 0f) return emptyList()
        val ts = mutableListOf(0f, 1f)
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            val e = q - p
            val den = d.x * e.y - d.y * e.x
            if (den == 0f) continue
            val w = p - a
            val t = (w.x * e.y - w.y * e.x) / den
            val u = (w.x * d.y - w.y * d.x) / den
            if (t > 0f && t < 1f && u >= 0f && u <= 1f) ts += t
        }
        ts.sort()
        val out = mutableListOf<Pair<Offset, Offset>>()
        for (k in 0 until ts.size - 1) {
            val t0 = ts[k]
            val t1 = ts[k + 1]
            if (t1 - t0 < 1e-5f) continue
            val mid = a + d * ((t0 + t1) / 2f)
            if (inside(mid, poly) == keepInside) {
                val s = a + d * t0
                val e = a + d * t1
                val last = out.lastOrNull()
                // Join pieces that meet (a crossing exactly on a vertex splits a line needlessly).
                if (last != null && (last.second - s).getDistance() < 1e-3f) out[out.size - 1] = last.first to e else out += s to e
            }
        }
        return out
    }

    /** A closed outline as a polygon for clipping, in the element's own unrotated frame. */
    fun outlineOf(element: DrawingElement): List<Offset>? = when (element) {
        is DrawingElement.Rectangle -> Rect(element.topLeft, element.size).let { r -> listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft) }
        is DrawingElement.Oval -> ellipse(element.topLeft + Offset(element.size.width / 2f, element.size.height / 2f), element.size.width / 2f, element.size.height / 2f)
        is DrawingElement.Circle -> ellipse(element.center, element.radius, element.radius)
        is DrawingElement.Triangle -> element.getVertices().let { (a, b, c) -> listOf(a, b, c) }
        is DrawingElement.Freehand -> if (element.points.size < 3) null else element.points.map { it.offset }
        else -> null
    }

    private fun ellipse(c: Offset, rx: Float, ry: Float): List<Offset> = (0 until 48).map { k ->
        val a = 2.0 * PI * k / 48.0
        Offset(c.x + rx * cos(a).toFloat(), c.y + ry * sin(a).toFloat())
    }
}
