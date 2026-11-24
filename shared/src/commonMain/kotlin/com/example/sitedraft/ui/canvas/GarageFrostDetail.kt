package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The garage frost wall detail (Nov 23 2026), for the S sheets, by Dan's rules: the footing at
 * frost depth (42" in Michigan), 12" block up to grade, the slab at grade on stone and sloped to the
 * door, an 8" course of 6" block on top so the framing stays off the ground, the treated sill on
 * sill seal with anchor bolts, and the 2x6 wall. Footing size, bars and dowels from the building inputs.
 *
 * Real inches: x = 0 at the outside face of the frost wall; y = minus the height above the slab (grade).
 */
object GarageFrostDetail {
    private const val NOTE_GAP_IN = 16f
    private const val BLOCK6_IN = 5.625f
    private const val SHOWN_IN = 30f

    /** [wallTopIn]: the wall drawn up to this height above the slab with no break (the full garage wall section, WallSections). */
    fun geometry(state: CanvasState, wallTopIn: Float? = null): DetailKit.Geometry {
        val s = state.building
        val parts = mutableListOf<Shower.Part>()
        val notes = mutableListOf<DetailKit.Note>()
        fun y(e: Float) = -e
        fun pt(x: Float, e: Float) = Offset(x, y(e))
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) =
            parts.add(Shower.Part.Poly(listOf(pt(x1, e1), pt(x2, e1), pt(x2, e2), pt(x1, e2)), true, ink))
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.THIN) = parts.add(Shower.Part.Seg(pt(x1, e1), pt(x2, e2), ink))
        fun note(text: String, x: Float, e: Float, left: Boolean) { notes += DetailKit.Note(text, pt(x, e), left) }
        fun cut(x1: Float, e1: Float, x2: Float, e2: Float) { rect(x1, e1, x2, e2); line(x1, e1, x2, e2); line(x1, e2, x2, e1) }

        val wt = FoundationWall.BLOCK_12.thicknessIn
        val f = s.footingWidthIn
        val t = s.footingThicknessIn
        val p = (f - wt) / 2f
        val fBot = -Garages.FROST_IN
        val fTop = fBot + t
        // Footing and its bars.
        rect(-p, fBot, wt + p, fTop)
        note("${formatInches(f)}\" x ${formatInches(t)}\" CONC. FOOTING, BOTTOM ${Garages.FROST_IN.toInt()}\" BELOW GRADE (FROST DEPTH)", wt + p, (fBot + fTop) / 2f, false)
        val dia = s.rebarSize / 8f
        if (s.footingBarsTop > 0) {
            for (x in listOf(-p + 3f, wt + p - 3f)) parts.add(Shower.Part.Ring(pt(x, fTop - 3f), dia / 2f, Shower.Ink.LINE))
            note("(${s.footingBarsTop}) #${s.rebarSize} BARS, CONTINUOUS", -p + 3f, fTop - 3f, true)
        }
        // 12" block frost wall to grade.
        rect(0f, fTop, wt, 0f)
        var c = fTop + 8f; while (c < -0.5f) { line(0f, c, wt, c, Shower.Ink.FAINT); c += 8f }
        note("12\" BLOCK FROST WALL", 0f, (fTop) / 2f, true)
        line(wt / 2f, fBot + 3f, wt / 2f, 6f, Shower.Ink.LINE)
        note("#${s.rebarSize} DOWELS @ ${formatInches(s.dowelSpacingIn)}\" O.C., CELLS FILLED SOLID", wt / 2f, fTop + 10f, false)
        // Grade outside, the slab inside at grade on stone.
        line(-NOTE_GAP_IN - 8f, 0f, 0f, 0f, Shower.Ink.LINE)
        note("GRADE", -NOTE_GAP_IN, 0f, true)
        val run = 36f
        rect(BLOCK6_IN, -4f, BLOCK6_IN + run, 0f, Shower.Ink.THIN)
        rect(wt, -8f, BLOCK6_IN + run, -4f, Shower.Ink.FAINT_DASHED)
        note("4\" CONC. SLAB AT GRADE, SLOPE 2\" TO DOOR (THICKENED AT DOOR)", BLOCK6_IN + run - 4f, -2f, false)
        note("4\" STONE BASE, COMPACTED FILL", BLOCK6_IN + run - 4f, -6f, false)
        // The 8" course of 6" block on top, outside face flush.
        rect(0f, 0f, BLOCK6_IN, Garages.COURSE_IN)
        note("6\" BLOCK, ONE 8\" COURSE (KEEPS FRAMING OFF THE GROUND)", 0f, Garages.COURSE_IN / 2f, true)
        // Sill seal, treated sill, bolt, the 2x6 wall.
        val sill = Garages.COURSE_IN
        line(0f, sill + 0.15f, 5.5f, sill + 0.15f, Shower.Ink.ACCENT)
        cut(0f, sill + 0.25f, 5.5f, sill + 1.75f)
        note("TREATED 2x6 SILL ON SILL SEAL", 5.5f, sill + 1f, false)
        line(2.75f, sill - 6f, 2.75f, sill + 2.25f, Shower.Ink.ACCENT)
        note("1/2\" A.B. ${formatInches(s.anchorBoltEndIn)}\" FROM CORNERS, ${formatInches(s.anchorBoltSpacingIn)}\" O.C.", 2.75f, sill - 3f, false)
        val top = wallTopIn ?: (sill + 1.75f + SHOWN_IN)
        line(0f, sill + 1.75f, 0f, top, Shower.Ink.LINE); line(5.5f, sill + 1.75f, 5.5f, top, Shower.Ink.LINE)
        note("2x6 GARAGE WALL", 5.5f, top - 8f, false)
        if (wallTopIn == null) parts.add(Shower.Part.Poly(listOf(pt(-2f, top), pt(2.2f, top), pt(2.6f, top + 0.8f), pt(3f, top - 0.8f), pt(3.4f, top), pt(7.5f, top)), false, Shower.Ink.THIN))
        val leftCol = -p - 6f
        val rightCol = BLOCK6_IN + run + 4f
        val extent = Rect(leftCol - NOTE_GAP_IN - 60f, y(top + 6f), rightCol + NOTE_GAP_IN + 60f, y(fBot - 8f))
        return DetailKit.Geometry(parts, notes, extent, leftCol, rightCol)
    }
}
