package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The basement column detail (Nov 22 2026), for the S sheets: the column on its pad footing and its
 * cap where it meets the beam, cut across the beam. Drawn from the structure design: the post
 * (lally or HSS) and its size, the 6"x6" base plate at the designed thickness, the pad's size and
 * thickness, the beam's shape. The column's middle is broken out; its real height is noted.
 *
 * Dan: no anchor bolts at the base, the slab locks the column in. Until he sets his, the cap is a
 * 6"x6" plate the same thickness bolted up through the bottom flange with (2) 1/2" bolts, and the
 * slab is 4" over the pad.
 *
 * Real inches: x = 0 at the column's centreline; y = minus the elevation above the top of the pad.
 */
object ColumnDetail {
    private const val SLAB_IN = 4f
    private const val SHOWN_IN = 16f                 // column shown above the plate and below the cap
    private const val NOTE_GAP_IN = 16f

    fun geometry(state: CanvasState): DetailKit.Geometry {
        val parts = mutableListOf<Shower.Part>()
        val notes = mutableListOf<DetailKit.Note>()
        fun y(e: Float) = -e
        fun pt(x: Float, e: Float) = Offset(x, y(e))
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) =
            parts.add(Shower.Part.Poly(listOf(pt(x1, e1), pt(x2, e1), pt(x2, e2), pt(x1, e2)), true, ink))
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.THIN) = parts.add(Shower.Part.Seg(pt(x1, e1), pt(x2, e2), ink))
        fun poly(ink: Shower.Ink, closed: Boolean, p: List<Offset>) = parts.add(Shower.Part.Poly(p, closed, ink))
        fun note(text: String, x: Float, e: Float, left: Boolean) { notes += DetailKit.Note(text, pt(x, e), left) }
        fun cut(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) {
            rect(x1, e1, x2, e2, ink); line(x1, e1, x2, e2); line(x1, e2, x2, e1)
        }

        val design = runCatching { StructuralDesign.design(state) }.getOrNull()
        val b = design?.beams?.firstOrNull()
        val specs = state.building.structure
        val post = b?.post ?: SteelEngine.AISC_DATABASE.firstOrNull { it.name == "Pipe 3 Std" } ?: SteelEngine.AISC_DATABASE.first { it.type != "W" }
        val beam = b?.shape ?: SteelEngine.AISC_DATABASE.first { it.name == "W8x18" }
        val plateT = (b?.postResult?.tp_provided ?: 0.375).toFloat()
        val padIn = b?.padIn ?: 24f
        val padT = b?.padThickIn ?: 10f
        val heightFt = b?.postHeightFt ?: 7.0
        val col = post.d.toFloat()
        val plate = StructuralDesign.PLATE_IN.toFloat()
        val lally = specs.post == PostType.LALLY
        fun plateTxt(t: Float) = when (t) { 0.375f -> "3/8"; 0.5f -> "1/2"; 0.75f -> "3/4"; else -> formatInches(t) }

        // ---- Pad footing, slab, base plate ----
        val slabHalf = padIn / 2f + 14f
        rect(-padIn / 2f, -padT, padIn / 2f, 0f)
        note("${padIn.toInt()}\" x ${padIn.toInt()}\" x ${formatInches(padT)}\" CONCRETE PAD FOOTING", padIn / 2f, -padT / 2f, false)
        val pb = plate / 2f
        rect(-pb, 0f, pb, plateT)
        note("${plate.toInt()}\"x${plate.toInt()}\"x${plateTxt(plateT)}\" BASE PLATE ON PAD, NO ANCHOR BOLTS", -pb, plateT / 2f, true)
        // Slab poured around the plate and up the column: it locks the column in.
        rect(-slabHalf, 0f, -col / 2f, SLAB_IN, Shower.Ink.THIN)
        rect(col / 2f, 0f, slabHalf, SLAB_IN, Shower.Ink.THIN)
        note("${formatInches(SLAB_IN)}\" CONCRETE SLAB POURED AROUND COLUMN (LOCKS IT IN)", slabHalf * 0.75f, SLAB_IN / 2f, false)
        note("STONE BASE", -slabHalf * 0.8f, -1.5f, true)
        line(-slabHalf, -0.01f, -padIn / 2f, -0.01f, Shower.Ink.FAINT_DASHED)
        line(padIn / 2f, -0.01f, slabHalf, -0.01f, Shower.Ink.FAINT_DASHED)

        // ---- The column, broken in the middle ----
        val c0 = plateT
        val breakLow = c0 + SHOWN_IN
        val breakHigh = breakLow + 3f
        val capBot = breakHigh + SHOWN_IN
        for (x in listOf(-col / 2f, col / 2f)) {
            line(x, c0, x, breakLow, Shower.Ink.LINE)
            line(x, breakHigh, x, capBot, Shower.Ink.LINE)
        }
        // Break lines across the column (a gap with two zigzags).
        for (eb in listOf(breakLow, breakHigh)) poly(Shower.Ink.THIN, false, listOf(pt(-col / 2f - 1f, eb), pt(-0.5f, eb), pt(-0.15f, eb + 0.6f),
            pt(0.15f, eb - 0.6f), pt(0.5f, eb), pt(col / 2f + 1f, eb)))
        val postName = post.name.uppercase()
        note(if (lally) "$postName LALLY COLUMN (CONCRETE FILLED), ${formatInches(col)}\" O.D." else "$postName STEEL COLUMN",
            col / 2f, breakLow - SHOWN_IN * 0.4f, false)
        parts.add(Shower.Part.Label("${Building.elevationText((heightFt * 12.0).toFloat())} COLUMN", pt(col / 2f + 6f, (breakLow + breakHigh) / 2f), 0.8f, Shower.Ink.LINE))

        // ---- Cap plate and the beam, cut across ----
        val capTop = capBot + plateT
        rect(-pb, capBot, pb, capTop)
        val d = beam.d.toFloat(); val bf = beam.bf.toFloat(); val tf = beam.tf.toFloat(); val tw = beam.tw.toFloat()
        val b0 = capTop; val b1 = b0 + d
        poly(Shower.Ink.LINE, true, listOf(
            pt(-bf / 2f, b0), pt(bf / 2f, b0), pt(bf / 2f, b0 + tf), pt(tw / 2f, b0 + tf), pt(tw / 2f, b1 - tf), pt(bf / 2f, b1 - tf),
            pt(bf / 2f, b1), pt(-bf / 2f, b1), pt(-bf / 2f, b1 - tf), pt(-tw / 2f, b1 - tf), pt(-tw / 2f, b0 + tf), pt(-bf / 2f, b0 + tf)))
        note("${beam.name.uppercase()} STEEL BEAM", bf / 2f, b0 + d * 0.5f, false)
        // (2) bolts up through the cap plate and the bottom flange, one each side of the web.
        val boltX = (bf / 2f + tw / 2f) / 2f
        for (x in listOf(-boltX, boltX)) {
            line(x, capBot - 0.6f, x, b0 + tf + 0.6f, Shower.Ink.LINE)
            line(x - 0.4f, b0 + tf + 0.3f, x + 0.4f, b0 + tf + 0.3f, Shower.Ink.LINE)
            line(x - 0.4f, capBot - 0.3f, x + 0.4f, capBot - 0.3f, Shower.Ink.LINE)
        }
        note("${plate.toInt()}\"x${plate.toInt()}\"x${plateTxt(plateT)}\" CAP PLATE, (2) 1/2\" BOLTS THROUGH BOTTOM FLANGE", -pb, capBot + plateT / 2f, true)
        // Nailer on the top flange (cut: it runs with the beam), joists across over it, subfloor.
        val nTop = b1 + 1.5f
        cut(-2.75f, b1, 2.75f, nTop)
        note("2x NAILER ON BEAM FOR JOIST NAILING", -2.75f, b1 + 0.75f, true)
        val jd = state.building.joistDepthIn
        val (joistLabel, _) = WallSection.joist(state)
        val half = slabHalf
        rect(-half, nTop, half, nTop + jd, Shower.Ink.THIN)
        note("$joistLabel FLOOR JOISTS, LAPPED OR SPLICED OVER BEAM", half * 0.7f, nTop + jd / 2f, false)
        rect(-half, nTop + jd, half, nTop + jd + 0.75f, Shower.Ink.THIN)
        note("3/4\" SUBFLOOR", -half * 0.7f, nTop + jd + 0.375f, true)

        val leftCol = -half - 4f
        val rightCol = half + 4f
        val extent = Rect(leftCol - NOTE_GAP_IN - 52f, y(nTop + jd + 6f), rightCol + NOTE_GAP_IN + 56f, y(-padT - 4f))
        return DetailKit.Geometry(parts, notes, extent, leftCol, rightCol)
    }
}
