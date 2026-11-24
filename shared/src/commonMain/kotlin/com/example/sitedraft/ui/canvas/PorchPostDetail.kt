package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The wood deck porch's post footing (Nov 23 2026), for the S sheets. Dan's two ways, both to frost
 * depth (42" in Michigan): the footing poured up to grade (a round pier, the post on a post base on
 * top), or the post down the hole on a "cookie" at the bottom, the cookie sized for the post's load.
 * Reads the first wood deck porch on the plan (its choice and its cookie size); a pier if there is none.
 *
 * Real inches: x = 0 at the post's centreline; y = minus the height above grade.
 */
object PorchPostDetail {
    private const val NOTE_GAP_IN = 16f
    private const val POST_IN = 5.5f
    private const val ABOVE_IN = 30f       // post shown above grade, then broken off

    /** The porch the detail is of, its cookie size, and whether it uses a cookie. */
    fun pick(state: CanvasState): Triple<DrawingElement.Symbol?, Float, Boolean> {
        val pc = state.elements.filterIsInstance<DrawingElement.Symbol>().firstOrNull { Porches.isPorch(it) && Porches.typeOf(it) == 2 }
            ?: return Triple(null, Porches.MIN_COOKIE_IN, false)
        val inch = state.inchesToPx(1f).takeIf { it > 0f } ?: 1f
        val r = pc.boundingRect()
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull()
        val (side, hf) = if (fp != null) Porches.sideOf(r, fp) else Porches.Side.BOTTOM to r.top
        val alongX = side == Porches.Side.TOP || side == Porches.Side.BOTTOM
        val len = (if (alongX) r.width else r.height) / inch
        val depth = when (side) {
            Porches.Side.TOP -> hf - r.top; Porches.Side.BOTTOM -> r.bottom - hf; Porches.Side.LEFT -> hf - r.left; Porches.Side.RIGHT -> r.right - hf
        } / inch
        val n = kotlin.math.ceil((len - 6f) / Porches.MAX_POST_SPACING_IN).toInt().coerceAtLeast(1)
        val cookie = Porches.cookieIn((len - 6f) / n, depth, Porches.prop(pc, Porches.ROOF) == 1, state.building)
        return Triple(pc, cookie, Porches.prop(pc, Porches.PIER) == 1)
    }

    fun geometry(state: CanvasState): DetailKit.Geometry {
        val parts = mutableListOf<Shower.Part>()
        val notes = mutableListOf<DetailKit.Note>()
        fun y(e: Float) = -e
        fun pt(x: Float, e: Float) = Offset(x, y(e))
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) =
            parts.add(Shower.Part.Poly(listOf(pt(x1, e1), pt(x2, e1), pt(x2, e2), pt(x1, e2)), true, ink))
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.THIN) = parts.add(Shower.Part.Seg(pt(x1, e1), pt(x2, e2), ink))
        fun note(text: String, x: Float, e: Float, left: Boolean) { notes += DetailKit.Note(text, pt(x, e), left) }

        val (_, cookie, useCookie) = pick(state)
        val frost = Porches.FROST_IN
        val h = POST_IN / 2f
        val run = 30f
        // Grade, both sides.
        line(-run, 0f, run, 0f, Shower.Ink.LINE)
        note("GRADE", run - 4f, 0f, false)
        // The post, broken off above.
        val postTop = ABOVE_IN
        if (!useCookie) {
            // Pier poured to grade (2" above), 12" round, from frost depth.
            val pr = Porches.PIER_DIA_IN / 2f
            rect(-pr, -frost, pr, 2f)
            note("${Porches.PIER_DIA_IN.toInt()}\" DIA. CONCRETE PIER POURED TO GRADE (2\" ABOVE)", pr, -frost / 2f, false)
            note("BOTTOM ${frost.toInt()}\" BELOW GRADE (FROST DEPTH), ON UNDISTURBED SOIL", 0f, -frost, false)
            rect(-h - 0.5f, 2f, h + 0.5f, 4f, Shower.Ink.THIN)
            note("GALV. POST BASE, ANCHORED IN PIER", -h - 0.5f, 3f, true)
            line(0f, -6f, 0f, 2f, Shower.Ink.ACCENT)
            rect(-h, 4f, h, postTop)
            note("6x6 TREATED POST", -h, postTop * 0.6f, true)
        } else {
            // The post down the hole on a cookie at frost depth.
            val c = cookie / 2f
            rect(-c, -frost - 8f, c, -frost)
            note("${cookie.toInt()}\" x ${cookie.toInt()}\" x 8\" CONCRETE COOKIE, SIZED FOR THE POST'S LOAD", c, -frost - 4f, false)
            note("${frost.toInt()}\" BELOW GRADE (FROST DEPTH), ON UNDISTURBED SOIL", 0f, -frost - 8f, false)
            rect(-h, -frost, h, postTop)
            note("6x6 TREATED POST, GROUND-CONTACT RATED", -h, postTop * 0.6f, true)
            // The hole, backfilled.
            rect(-c - 2f, -frost, -h, 0f, Shower.Ink.FAINT_DASHED)
            rect(h, -frost, c + 2f, 0f, Shower.Ink.FAINT_DASHED)
            note("BACKFILL, COMPACTED IN LIFTS", -c - 2f, -frost / 2f, true)
        }
        // Break line across the top of the post.
        parts.add(Shower.Part.Poly(listOf(pt(-h - 2f, postTop), pt(-0.6f, postTop), pt(-0.2f, postTop + 0.8f), pt(0.2f, postTop - 0.8f),
            pt(0.6f, postTop), pt(h + 2f, postTop)), false, Shower.Ink.THIN))
        val leftCol = -run - 4f
        val rightCol = run + 4f
        val extent = Rect(leftCol - NOTE_GAP_IN - 52f, y(postTop + 6f), rightCol + NOTE_GAP_IN + 60f, y(-frost - 14f))
        return DetailKit.Geometry(parts, notes, extent, leftCol, rightCol)
    }
}
