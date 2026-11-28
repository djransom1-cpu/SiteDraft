package com.example.sitedraft.ui.canvas

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mirrors (Dan, Nov 28, for Natalie): a framed oval and a framed rectangle, both sized as you like, and a full
 * frameless mirror over a bathroom vanity. They hang on a wall like a TV: a thin line in the plan, drawn in the
 * elevations. Width is the plan symbol's width; bottom and height use the opening keys (PROP_SILL_IN, PROP_HEIGHT_IN)
 * so the same property handler sets them; the frame has its own key.
 */
object Mirrors {
    const val RECT = "Mirror Rectangle"
    const val OVAL = "Mirror Oval"
    const val VANITY = "Mirror Over Vanity"
    val names = listOf(RECT, OVAL, VANITY)

    /** Frame width, inches (0 = frameless). */
    const val PROP_FRAME_IN = "mirrorFrameIn"

    /** Plan footprints, inches: width along the wall x 1" off it. */
    val footprints = mapOf(RECT to Pair(30f, 1f), OVAL to Pair(24f, 1f), VANITY to Pair(48f, 1f))

    /** The 4" backsplash a full vanity mirror sits on, and where it stops (just under a 80" door head's casing). */
    const val VANITY_SPLASH_IN = 4f
    const val VANITY_TOP_OF_MIRROR_IN = 72f

    fun isMirror(name: String) = name in names

    fun frameIn(s: DrawingElement.Symbol): Float = s.properties[PROP_FRAME_IN] ?: when (s.symbolName) {
        RECT -> 2f
        OVAL -> 1.5f
        else -> 0f
    }

    /** Bottom and top, inches off the floor. A vanity mirror with nothing set sits on the backsplash of [vanityTopIn]. */
    fun span(s: DrawingElement.Symbol, vanityTopIn: Float? = null): Pair<Float, Float> {
        val p = s.properties
        return when (s.symbolName) {
            VANITY -> {
                val bottom = p[PlanSymbols.PROP_SILL_IN] ?: ((vanityTopIn ?: 34.5f) + VANITY_SPLASH_IN)
                bottom to (bottom + (p[PlanSymbols.PROP_HEIGHT_IN] ?: (VANITY_TOP_OF_MIRROR_IN - bottom)))
            }
            OVAL -> {
                val bottom = p[PlanSymbols.PROP_SILL_IN] ?: 42f
                bottom to bottom + (p[PlanSymbols.PROP_HEIGHT_IN] ?: 32f)
            }
            else -> {
                val bottom = p[PlanSymbols.PROP_SILL_IN] ?: 40f
                bottom to bottom + (p[PlanSymbols.PROP_HEIGHT_IN] ?: 36f)
            }
        }
    }

    /** The mirror in an elevation between [from] and [to] (px along the view), [bottom] to [top] inches up. */
    fun drawElevation(s: DrawingElement.Symbol, from: Float, to: Float, bottom: Float, top: Float, pen: Appliances.ElevPen) {
        val inch = pen.inch
        val f = frameIn(s)
        if (s.symbolName == OVAL) {
            ellipse(pen, from, to, bottom, top, 2f)
            if (f > 0f) ellipse(pen, from + f * inch, to - f * inch, bottom + f, top - f, 1f)
            glint(pen, from + f * inch, to - f * inch, bottom + f, top - f, inset = 0.3f)
            return
        }
        pen.rect(from, to, bottom, top, 2f)
        if (f > 0f) pen.rect(from + f * inch, to - f * inch, bottom + f, top - f, 1f)
        glint(pen, from + f * inch, to - f * inch, bottom + f, top - f, inset = 0.15f)
    }

    /** The usual glass mark: two short parallel diagonals in the upper left. */
    private fun glint(pen: Appliances.ElevPen, x1: Float, x2: Float, h1: Float, h2: Float, inset: Float) {
        val w = x2 - x1
        val hIn = h2 - h1
        if (w <= 0f || hIn <= 0f) return
        val ax = x1 + w * inset
        val ah = h2 - hIn * inset
        val len = minOf(w, hIn * pen.inch) * 0.22f
        pen.line(ax, ah - len / pen.inch, ax + len, ah, 1f)
        pen.line(ax + len * 0.35f, ah - len / pen.inch, ax + len * 1.35f, ah, 1f)
    }

    private fun ellipse(pen: Appliances.ElevPen, x1: Float, x2: Float, h1: Float, h2: Float, sw: Float) {
        val cx = (x1 + x2) / 2f
        val ch = (h1 + h2) / 2f
        val rx = (x2 - x1) / 2f
        val rh = (h2 - h1) / 2f
        val n = 48
        var px = cx + rx
        var ph = ch
        for (k in 1..n) {
            val a = 2.0 * PI * k / n
            val x = cx + (cos(a) * rx).toFloat()
            val h = ch + (sin(a) * rh).toFloat()
            pen.line(px, ph, x, h, sw)
            px = x; ph = h
        }
    }
}
