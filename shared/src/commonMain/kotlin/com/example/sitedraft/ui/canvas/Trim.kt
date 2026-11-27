package com.example.sitedraft.ui.canvas

/**
 * Door and window casing and baseboard on an elevation, laid out the way a trim carpenter
 * sets it (all in inches; x along the wall in px, heights in inches, through an [Appliances.ElevPen]):
 *
 * - A door's casing stands 3/16" back from the jamb's inside edge (a 3/16" reveal), so it covers
 *   9/16" of a 3/4" jamb: on a 3-0 door with 2 1/4" casing the inside edges are 36 3/8" apart
 *   and the outside edges 40 7/8".
 * - A window's size is to the outside of its jamb, so its casing starts 9/16" in from each side.
 * - Heads: standard casing is mitered at the corners; with a backband (1 1/16" wide on a 1/4"
 *   rabbet, so it adds 13/16" a side) the head is the same stock and the legs butt into it; a
 *   cornice head (a 5 1/2" or taller frieze, a 1/2" bed molding and a small crown cap that
 *   stands 3/4" past the legs each side) also has the legs butting into it, its legs cased
 *   with backband.
 * - Windows are picture framed (casing all round) or have a stool and apron: a 1" stool at the
 *   bottom of the window with ears 3/4" past the casing, the side casing standing on it, the
 *   apron (casing stock) under it as long as the outside of the casing.
 * - Base runs along the floor and stops at the door casing and at anything standing on the floor.
 */
object Trim {
    const val REVEAL_IN = 3f / 16f
    const val JAMB_IN = 0.75f
    /** How far a window's casing starts in from the outside of its jamb. */
    const val WINDOW_IN_IN = JAMB_IN - REVEAL_IN
    const val BACKBAND_IN = 13f / 16f
    const val MIN_FRIEZE_IN = 5.5f
    const val BED_IN = 0.5f
    const val CAP_IN = 1.25f
    const val CAP_PROJECTION_IN = 0.75f
    const val STOOL_IN = 1f
    const val EAR_IN = 0.75f

    const val HEAD_MITERED = 0
    const val HEAD_BACKBAND = 1
    const val HEAD_CORNICE = 2
    val headLabels = listOf("Mitered", "Backband", "Cornice head")
    val windowLabels = listOf("Picture frame", "Stool & apron")
    val casingChoices = listOf(2.25f, 3.25f)
    val baseChoices = listOf(3.25f, 4.25f, 5.25f)

    /** The face width of a leg: the casing, plus the backband for backband and cornice heads. */
    fun legIn(spec: ElevationSpec): Float = spec.casingIn + if (spec.headStyle == HEAD_MITERED) 0f else BACKBAND_IN

    /**
     * Outside edges (px) of the casing around an opening that runs [from]..[to] (px) along the
     * wall. Doors are sized to the opening inside the jamb, windows to the outside of their jamb.
     */
    fun outerEdges(from: Float, to: Float, window: Boolean, spec: ElevationSpec, inch: Float): Pair<Float, Float> {
        val inner = if (window) WINDOW_IN_IN else -REVEAL_IN
        val leg = legIn(spec)
        return (from + (inner - leg) * inch) to (to - (inner - leg) * inch)
    }

    /**
     * Casing round one opening. [bottomIn]/[topIn]: the opening (0 and the door height for a
     * door; the sill and head for a window, to the outside of the jamb).
     */
    fun drawOpening(p: Appliances.ElevPen, from: Float, to: Float, bottomIn: Float, topIn: Float, window: Boolean, spec: ElevationSpec) {
        val inch = p.inch
        val c = spec.casingIn
        val leg = legIn(spec)
        val inner = if (window) WINDOW_IN_IN else -REVEAL_IN
        // Inside edges of the casing (px) and the inside of the head (in).
        val xi1 = from + inner * inch
        val xi2 = to - inner * inch
        val xo1 = xi1 - leg * inch
        val xo2 = xi2 + leg * inch
        val headIn = if (window) topIn - WINDOW_IN_IN else topIn + REVEAL_IN
        val stoolApron = window && spec.windowTrim == 1
        // Where the legs stand: the floor (door), the stool (stool and apron), or the bottom piece (picture frame).
        val bottomInner = if (window) bottomIn + WINDOW_IN_IN else 0f
        val legsFrom = when {
            !window -> 0f
            stoolApron -> bottomIn
            else -> bottomInner - (if (spec.headStyle == HEAD_MITERED) c else leg)
        }
        val mitered = spec.headStyle == HEAD_MITERED
        val headTop = when (spec.headStyle) {
            HEAD_MITERED -> headIn + c
            HEAD_BACKBAND -> headIn + leg
            else -> headIn + maxOf(spec.friezeIn, MIN_FRIEZE_IN)
        }
        // The casing stands in front of any wall tile: hide the tile behind each piece first.
        val blankStart = if (window && !stoolApron && !mitered) bottomInner else legsFrom
        p.blank(xo1, xi1, blankStart, if (mitered) headTop else headIn)
        p.blank(xi2, xo2, blankStart, if (mitered) headTop else headIn)
        p.blank(xo1, xo2, headIn, headTop)
        if (window && !stoolApron) p.blank(xo1, xo2, legsFrom, bottomInner)
        // Legs: outer and inner edges; the backband line just inside the outer edge. On a
        // picture frame with a butted bottom the legs stand on the bottom piece.
        val legTop = if (mitered) headTop else headIn
        val legStart = if (window && !stoolApron && !mitered) bottomInner else legsFrom
        p.line(xo1, legStart, xo1, legTop, 2f)
        p.line(xo2, legStart, xo2, legTop, 2f)
        p.line(xi1, if (window && !stoolApron) bottomInner else legsFrom, xi1, headIn, 1f)
        p.line(xi2, if (window && !stoolApron) bottomInner else legsFrom, xi2, headIn, 1f)
        if (!mitered) {
            p.line(xo1 + BACKBAND_IN * inch, legStart, xo1 + BACKBAND_IN * inch, headIn, 1f)
            p.line(xo2 - BACKBAND_IN * inch, legStart, xo2 - BACKBAND_IN * inch, headIn, 1f)
        }
        // Head.
        p.line(xi1, headIn, xi2, headIn, 1f)
        when (spec.headStyle) {
            HEAD_MITERED -> {
                p.line(xo1, headTop, xo2, headTop, 2f)
                p.line(xo1, headTop, xi1, headIn, 1f)
                p.line(xo2, headTop, xi2, headIn, 1f)
            }
            HEAD_BACKBAND -> {
                // Flat head the full width; the legs butt into it (the level joint at headIn).
                p.line(xo1, headIn, xo2, headIn, 1f)
                p.line(xo1, headIn, xo1, headTop, 2f)
                p.line(xo2, headIn, xo2, headTop, 2f)
                p.line(xo1, headTop, xo2, headTop, 2f)
                // Backband along the top and down the ends of the head, mitered at the corners.
                val b = BACKBAND_IN * inch
                p.line(xo1 + b, headIn, xo1 + b, headTop - BACKBAND_IN, 1f)
                p.line(xo2 - b, headIn, xo2 - b, headTop - BACKBAND_IN, 1f)
                p.line(xo1 + b, headTop - BACKBAND_IN, xo2 - b, headTop - BACKBAND_IN, 1f)
                p.line(xo1, headTop, xo1 + b, headTop - BACKBAND_IN, 1f)
                p.line(xo2, headTop, xo2 - b, headTop - BACKBAND_IN, 1f)
            }
            else -> {
                // Cornice: frieze the width of the legs, bed molding, crown cap past the legs.
                p.rect(xo1, xo2, headIn, headTop, 2f)
                val bedProj = CAP_PROJECTION_IN / 2f
                p.rect(xo1 - bedProj * inch, xo2 + bedProj * inch, headTop, headTop + BED_IN, 1f)
                val capBottom = headTop + BED_IN
                p.rect(xo1 - CAP_PROJECTION_IN * inch, xo2 + CAP_PROJECTION_IN * inch, capBottom, capBottom + CAP_IN, 2f)
                p.line(xo1 - CAP_PROJECTION_IN * inch, capBottom + CAP_IN * 0.45f, xo2 + CAP_PROJECTION_IN * inch, capBottom + CAP_IN * 0.45f, 1f)
            }
        }
        if (!window) return
        if (stoolApron) {
            // Stool with ears past the casing, the apron under it as long as the outside of the casing.
            p.rect(xo1 - EAR_IN * inch, xo2 + EAR_IN * inch, bottomIn - STOOL_IN, bottomIn, 2f)
            p.rect(xo1, xo2, bottomIn - STOOL_IN - c, bottomIn - STOOL_IN, 2f)
        } else {
            // Picture frame: the bottom piece like the head (mitered, or a flat piece the legs stand on).
            p.line(xi1, bottomInner, xi2, bottomInner, 1f)
            p.line(xo1, legsFrom, xo2, legsFrom, 2f)
            if (mitered) {
                p.line(xo1, legsFrom, xi1, bottomInner, 1f)
                p.line(xo2, legsFrom, xi2, bottomInner, 1f)
            } else {
                p.line(xo1, bottomInner, xo2, bottomInner, 1f)
                p.line(xo1, legsFrom, xo1, bottomInner, 2f)
                p.line(xo2, legsFrom, xo2, bottomInner, 2f)
            }
        }
    }

    /** Windows closer than this (inches, jamb to jamb) with the same head and sill are trimmed as one unit. */
    const val GANG_GAP_IN = 8f

    /**
     * Windows side by side, sorted along the wall ([from], [to] px; [bottomIn], [topIn] each): groups that are
     * trimmed as one unit (Dan, Nov 25). Each group is a list of indices into [windows].
     */
    fun gangs(windows: List<FloatArray>, inch: Float): List<List<Int>> {
        val out = mutableListOf<MutableList<Int>>()
        for (i in windows.indices.sortedBy { windows[it][0] }) {
            val w = windows[i]
            val last = out.lastOrNull()?.last()?.let { windows[it] }
            if (last != null && w[0] - last[1] <= GANG_GAP_IN * inch && kotlin.math.abs(w[2] - last[2]) < 0.25f && kotlin.math.abs(w[3] - last[3]) < 0.25f)
                out.last() += i
            else out += mutableListOf(i)
        }
        return out
    }

    /**
     * Two or more windows trimmed as one unit (Dan, Nov 25): one long head across them, legs only at the two
     * outside ends, solid trim filling between the windows, and the stool and apron (or the picture-frame bottom)
     * running through. [spans]: each window's jamb, px along the wall, sorted.
     */
    fun drawGang(p: Appliances.ElevPen, spans: List<Pair<Float, Float>>, bottomIn: Float, topIn: Float, spec: ElevationSpec) {
        // The whole group as one opening: head, end legs, stool and apron or bottom.
        drawOpening(p, spans.first().first, spans.last().second, bottomIn, topIn, true, spec)
        val inch = p.inch
        val headIn = topIn - WINDOW_IN_IN
        val bottomInner = bottomIn + WINDOW_IN_IN
        val stoolApron = spec.windowTrim == 1
        val from = if (stoolApron) bottomIn else bottomInner
        // Between each pair: solid trim from one window's inside casing edge to the next's.
        for (k in 0 until spans.size - 1) {
            val a = spans[k].second - WINDOW_IN_IN * inch
            val b = spans[k + 1].first + WINDOW_IN_IN * inch
            if (b <= a) continue
            p.blank(a, b, from, headIn)
            p.rect(a, b, from, headIn, 1f)
        }
    }

    /**
     * Base along the floor over [length] px, except where [blocked] (px ranges: door casings,
     * cabinets, tubs and anything else standing on the floor).
     */
    fun drawBase(p: Appliances.ElevPen, length: Float, blocked: List<Pair<Float, Float>>, spec: ElevationSpec) {
        val h = spec.baseIn
        if (h <= 0f) return
        val cuts = blocked.map { (a, b) -> a.coerceIn(0f, length) to b.coerceIn(0f, length) }.filter { it.second > it.first }.sortedBy { it.first }
        var x = 0f
        val runs = mutableListOf<Pair<Float, Float>>()
        for ((a, b) in cuts) {
            if (a > x + 0.5f) runs += x to a
            x = maxOf(x, b)
        }
        if (length > x + 0.5f) runs += x to length
        for ((a, b) in runs) {
            p.rect(a, b, 0f, h, 2f)
            p.line(a, h - 0.75f, b, h - 0.75f, 1f) // the cap bead
        }
    }
}
