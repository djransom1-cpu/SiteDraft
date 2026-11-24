package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The footing detail (Nov 23 2026), for the S sheets: the footing cut across, the bottom of the
 * foundation wall on it, the drain tile both sides, the slab inside. Everything comes from the
 * building inputs, so a change there changes the detail.
 *
 * Dan's rules:
 * - Footing size: thickness 8"-12" residential (much more on commercial), width from the code
 *   table; it has to stick out at least 2" past each face of the wall and no farther than it is
 *   thick (IRC R403.1.1). Centred under the wall unless set.
 * - Rebar: at least (2) bars in the top, often (2) top and (2) bottom; #4 residential, #5
 *   commercial, #6 when the soil or the loads call for it. 3" clear to the earth.
 * - Wall tie: a keyway on poured walls only, none on block; on both, vertical bars from the
 *   footing up into the wall, every 4' on a basement (code), as close as every other cell, the
 *   cells with bars filled solid.
 * - Drain tile both sides of the footing: Form-A-Drain (the forms stay and are the tile) or
 *   perforated pipe, in stone, with a cross pipe through the footing every 20'.
 * - Foam: on the foundation wall, not the footing, unless it's a slab on grade or a trench
 *   footing with no basement.
 *
 * Real inches: x = 0 at the outside face of the foundation wall, into the building positive;
 * y = minus the elevation above the top of the footing.
 */
object FootingDetail {
    private const val WALL_SHOWN_IN = 30f        // wall shown above the footing, then broken off
    private const val COVER_IN = 3f              // clear cover, concrete cast against earth
    private const val KEY_W_IN = 3.5f            // 2x4 keyway
    private const val KEY_D_IN = 1.5f
    private const val FAD_W_IN = 1.5f            // Form-A-Drain profile, beside the footing
    private const val STONE_OUT_IN = 12f         // stone outside the footing
    private const val STONE_UP_IN = 12f          // ...and up the wall above the top of the footing
    private const val SLAB_IN = 4f
    private const val SLAB_RUN_IN = 28f          // slab shown past the inside of the footing
    private const val HOOK_IN = 8f               // the dowel's hook along the bottom bars
    private const val NOTE_GAP_IN = 16f

    /** A bar's diameter, inches: the bar number in eighths (#4 = 1/2"). */
    fun barDiaIn(size: Int): Float = size / 8f

    fun barText(n: Int, size: Int, where: String) = "($n) #$size BARS $where, CONTINUOUS"

    fun geometry(state: CanvasState): DetailKit.Geometry {
        val s = state.building
        val parts = mutableListOf<Shower.Part>()
        val notes = mutableListOf<DetailKit.Note>()
        fun y(e: Float) = -e
        fun pt(x: Float, e: Float) = Offset(x, y(e))
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) =
            parts.add(Shower.Part.Poly(listOf(pt(x1, e1), pt(x2, e1), pt(x2, e2), pt(x1, e2)), true, ink))
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.THIN) = parts.add(Shower.Part.Seg(pt(x1, e1), pt(x2, e2), ink))
        fun poly(ink: Shower.Ink, closed: Boolean, p: List<Offset>) = parts.add(Shower.Part.Poly(p, closed, ink))
        fun note(text: String, x: Float, e: Float, left: Boolean) { notes += DetailKit.Note(text, pt(x, e), left) }
        fun label(text: String, x: Float, e: Float) = parts.add(Shower.Part.Label(text, pt(x, e), 0.8f, Shower.Ink.LINE))

        val wall = s.foundationWall
        val wt = wall.thicknessIn
        val w = s.footingWidthIn
        val t = s.footingThicknessIn
        val (outP, inP) = Building.footingProjections(s)
        val fx1 = -outP                 // outside face of the footing
        val fx2 = wt + inP              // inside face
        val slab = s.foundation == FoundationType.SLAB
        val basement = s.foundation == FoundationType.BASEMENT
        val top = WALL_SHOWN_IN

        // ---- The footing, with the keyway in its top on a poured wall ----
        val keyX1 = wt / 2f - KEY_W_IN / 2f
        val keyX2 = wt / 2f + KEY_W_IN / 2f
        if (wall.poured) {
            poly(Shower.Ink.LINE, true, listOf(pt(fx1, -t), pt(fx2, -t), pt(fx2, 0f), pt(keyX2, 0f), pt(keyX2, -KEY_D_IN),
                pt(keyX1, -KEY_D_IN), pt(keyX1, 0f), pt(fx1, 0f)))
        } else rect(fx1, -t, fx2, 0f)
        val sizeText = "${formatInches(w)}\" x ${formatInches(t)}\" CONCRETE FOOTING"
        val projText = if (kotlin.math.abs(outP - inP) < 0.01f) "${formatInches(outP)}\" PAST WALL EACH SIDE"
            else "${formatInches(outP)}\" PAST WALL OUTSIDE, ${formatInches(inP)}\" INSIDE"
        note("$sizeText, $projText", fx2, -t / 2f, false)
        note("ON UNDISTURBED SOIL", (fx1 + fx2) / 2f, -t, false)
        // Width dimension under the footing.
        val dimE = -t - 5f
        line(fx1, dimE, fx2, dimE)
        for (x in listOf(fx1, fx2)) { line(x, dimE - 1f, x, dimE + 1f); line(x, -t - 1f, x, dimE - 1f, Shower.Ink.FAINT) }
        label("${formatInches(w)}\"", (fx1 + fx2) / 2f, dimE - 2.5f)

        // ---- Rebar: continuous bars, 3" clear ----
        val dia = barDiaIn(s.rebarSize)
        fun bars(n: Int, e: Float) {
            if (n <= 0) return
            val x1 = fx1 + COVER_IN + dia / 2f
            val x2 = fx2 - COVER_IN - dia / 2f
            for (i in 0 until n) {
                val x = if (n == 1) (x1 + x2) / 2f else x1 + (x2 - x1) * i / (n - 1)
                parts.add(Shower.Part.Ring(pt(x, e), dia / 2f, Shower.Ink.LINE))
                parts.add(Shower.Part.Ring(pt(x, e), dia / 4f, Shower.Ink.LINE))
            }
        }
        val topBarE = -COVER_IN - dia / 2f
        val botBarE = -t + COVER_IN + dia / 2f
        bars(s.footingBarsTop, topBarE)
        bars(s.footingBarsBottom, botBarE)
        if (s.footingBarsTop > 0) note(barText(s.footingBarsTop, s.rebarSize, "TOP"), fx1 + COVER_IN, topBarE, true)
        if (s.footingBarsBottom > 0) note(barText(s.footingBarsBottom, s.rebarSize, "BOTTOM"), fx1 + COVER_IN, botBarE, true)
        if (s.footingBarsTop + s.footingBarsBottom > 0) label("3\" CLR.", fx2 - COVER_IN / 2f, (topBarE + 0f) / 2f + 0.2f)

        // ---- The wall on the footing, broken off above ----
        rect(0f, 0f, wt, top, Shower.Ink.LINE)
        if (wall.poured) {
            rect(keyX1, -KEY_D_IN, keyX2, 0f, Shower.Ink.THIN)
            note("2x4 KEYWAY", keyX1, -KEY_D_IN / 2f, true)
        } else {
            var c = s.courseIn
            while (c < top - 0.5f && s.courseIn > 0.5f) { line(0f, c, wt, c, Shower.Ink.FAINT); c += s.courseIn }
        }
        // Break line across the top of the wall.
        val bm = wt / 2f
        poly(Shower.Ink.THIN, false, listOf(pt(-3f, top), pt(bm - 1.2f, top), pt(bm - 0.4f, top + 1.2f), pt(bm + 0.4f, top - 1.2f),
            pt(bm + 1.2f, top), pt(wt + 3f, top)))
        note("${wall.callout} FOUNDATION WALL", wt, top - 6f, false)

        // ---- The dowel: hooked along the bottom of the footing, up into the wall ----
        val dx = wt / 2f + if (wall.poured) 0f else 2f
        val hookE = -t + COVER_IN + dia / 2f
        val dowelTop = top - 4f
        poly(Shower.Ink.LINE, false, listOf(pt(dx + HOOK_IN, hookE), pt(dx, hookE), pt(dx, dowelTop)))
        val oc = s.dowelSpacingIn
        val ocText = if (oc % 12f == 0f) "${(oc / 12f).toInt()}'-0\"" else "${formatInches(oc)}\""
        note("#${s.rebarSize} VERTICAL DOWELS @ $ocText O.C., HOOKED IN FOOTING", dx, top * 0.6f, false)
        if (!wall.poured) {
            // The cell the bar is in, filled solid.
            rect(dx - 2.5f, 0f, dx + 2.5f, top - 1.5f, Shower.Ink.FAINT_DASHED)
            note("CELLS WITH DOWELS FILLED SOLID", dx + 2.5f, top * 0.35f, false)
        }

        // ---- Foam: on the wall; on the footing only for a slab or trench footing ----
        val foam = s.foundationFoamIn
        if (foam > 0f) {
            rect(-foam, 0f, 0f, top, Shower.Ink.THIN)
            var e = 0f; var left = true
            while (e < top - 1f && foam > 0.25f) {
                val e2 = minOf(e + foam, top); line(if (left) -foam else 0f, e, if (left) 0f else -foam, e2, Shower.Ink.FAINT)
                left = !left; e = e2
            }
            note("${formatInches(foam)}\" RIGID FOAM ON WALL", -foam, top * 0.8f, true)
            if (slab) {
                rect(fx1 - foam, -t, fx1, 0f, Shower.Ink.THIN)
                note("${formatInches(foam)}\" RIGID FOAM ON FOOTING (NO BASEMENT)", fx1 - foam, -t * 0.3f, true)
            }
        }

        // ---- Drain tile both sides, in stone ----
        val outFace = if (slab && foam > 0f) fx1 - foam else fx1
        val stoneX1 = outFace - STONE_OUT_IN
        rect(stoneX1, -t, if (foam > 0f) -foam else 0f, STONE_UP_IN, Shower.Ink.FAINT_DASHED)
        note("WASHED STONE", stoneX1 + 2f, STONE_UP_IN - 3f, true)
        when (s.drainTile) {
            DrainTile.FORM_A_DRAIN -> {
                rect(outFace - FAD_W_IN, -t, outFace, 0f, Shower.Ink.LINE)
                rect(fx2, -t, fx2 + FAD_W_IN, 0f, Shower.Ink.LINE)
                // Its slots, short ticks up the face.
                for (x in listOf(outFace - FAD_W_IN / 2f, fx2 + FAD_W_IN / 2f)) {
                    var e = -t + 1.5f
                    while (e < -1f) { line(x - 0.35f, e, x + 0.35f, e); e += 2f }
                }
                note("FORM-A-DRAIN BOTH SIDES (FORMS STAY AS DRAIN TILE)", outFace - FAD_W_IN, -t / 2f, true)
            }
            DrainTile.PERFORATED -> {
                for (x in listOf(outFace - 3f, fx2 + 3f)) parts.add(Shower.Part.Ring(pt(x, -t + 2f), 2f, Shower.Ink.LINE))
                note("4\" PERFORATED DRAIN TILE BOTH SIDES", outFace - 3f, -t + 2f, true)
            }
        }
        // The cross pipe through the footing, ties the two lines together.
        line(fx1, -t + 1.25f, fx2, -t + 1.25f, Shower.Ink.FAINT_DASHED)
        line(fx1, -t + 2.75f, fx2, -t + 2.75f, Shower.Ink.FAINT_DASHED)
        note("CROSS PIPE THROUGH FOOTING @ 20' O.C., TIES BOTH SIDES", fx2 - 1f, -t + 2f, false)

        // ---- Inside: stone base and the slab (basement), crawl floor or nothing ----
        val runEnd = fx2 + SLAB_RUN_IN
        val inStart = if (s.drainTile == DrainTile.FORM_A_DRAIN) fx2 + FAD_W_IN else fx2
        if (basement || slab) {
            rect(inStart, -t, runEnd, 0f, Shower.Ink.FAINT_DASHED)
            note("STONE BASE", runEnd - 4f, -t / 2f, false)
            rect(wt, 0f, runEnd, SLAB_IN, Shower.Ink.THIN)
            note("${formatInches(SLAB_IN)}\" CONCRETE SLAB", runEnd - 4f, SLAB_IN / 2f, false)
        }

        val minX = stoneX1
        val maxX = runEnd
        val leftCol = minX - 4f
        val rightCol = maxX + 4f
        val extent = Rect(leftCol - NOTE_GAP_IN - 60f, y(top + 6f), rightCol + NOTE_GAP_IN + 60f, y(dimE - 6f))
        return DetailKit.Geometry(parts, notes, extent, leftCol, rightCol)
    }
}
