package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.sqrt

/**
 * The stair section detail (Dan, Nov 24 2026): a cut through the first straight stair on the plan, so it always
 * matches it - its risers and treads, the nosing, notched 2x12 stringers, the finish floors top and bottom, the
 * floor above with a double header at the top of the stair and the opening's header back where the headroom
 * holds 6'-8" over the nosing line, the headroom line and the handrail. A stair's numbers when there is none.
 *
 * Real inches: x = 0 at the face of the bottom riser, up the stair positive; y = minus the height above the
 * bottom finish floor.
 */
object StairDetail {
    private const val NOTE_GAP_IN = 16f
    private const val TREAD_THK_IN = 1f
    private const val RISER_THK_IN = 0.75f
    private const val STRINGER_IN = 11.25f
    private const val FLOOR_SHOWN_IN = 36f

    /** The stair the detail is of: the first straight (or L / U) stair on the plan, not generated. */
    fun pick(state: CanvasState): DrawingElement.Symbol? {
        val stairs = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.STAIRS && !DerivedLayers.isGenerated(it) }
        return stairs.firstOrNull { Stairs.type(it.symbolName) == Stairs.Type.STRAIGHT }
            ?: stairs.firstOrNull { Stairs.type(it.symbolName) == Stairs.Type.L_LANDING || Stairs.type(it.symbolName) == Stairs.Type.U_LANDING }
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
        fun cut(x1: Float, e1: Float, x2: Float, e2: Float) { rect(x1, e1, x2, e2); line(x1, e1, x2, e2); line(x1, e2, x2, e1) }
        fun breakAt(x: Float, e1: Float, e2: Float) {
            val m = (e1 + e2) / 2f
            parts.add(Shower.Part.Poly(listOf(pt(x, e1 - 2f), pt(x, m - 0.8f), pt(x - 0.8f, m - 0.3f), pt(x + 0.8f, m + 0.3f), pt(x, m + 0.8f), pt(x, e2 + 2f)), false, Shower.Ink.THIN))
        }

        val b = state.building
        val stair = pick(state)
        val l = stair?.let { Stairs.layout(it) } ?: Stairs.Layout(14, 108f / 14f, 10f, 1f, 36f, 14, 0)
        val n = l.risers
        val r = l.riser
        val t = l.tread
        val nose = l.nosing
        // Finish floors: from the stair when it says, else 3/4" on a framed floor (the top), the bottom on a slab when it goes to the basement.
        val down = stair?.let { Stairs.goesDown(it, state) } ?: false
        val bottomOnSlab = down && b.foundation == FoundationType.BASEMENT
        val finTop = stair?.let { Stairs.finishIn(it, Stairs.FIN_TOP, false) } ?: Stairs.FINISH_FRAMED_IN
        val finBot = stair?.let { Stairs.finishIn(it, Stairs.FIN_BOT, bottomOnSlab) } ?: if (bottomOnSlab) 0f else Stairs.FINISH_FRAMED_IN
        val sub = b.subfloorIn
        val joist = b.joistDepthIn
        val top = n * r                                // top finish floor
        val xTop = (n - 1) * t                         // face of the top riser
        fun nosingLine(x: Float) = r * (x / t + 1f)    // through the nosings, up from the bottom finish floor

        // ---- The floor below ----
        line(-FLOOR_SHOWN_IN, 0f, xTop + FLOOR_SHOWN_IN, 0f, Shower.Ink.LINE)
        if (bottomOnSlab) {
            rect(-FLOOR_SHOWN_IN, -finBot - 4f, xTop + FLOOR_SHOWN_IN, -finBot, Shower.Ink.THIN)
            note(if (finBot > 0f) "FINISH FLOOR ON 4\" CONCRETE SLAB" else "4\" CONCRETE SLAB (FINISH FLOOR)", -FLOOR_SHOWN_IN + 6f, -2f, true)
        } else {
            rect(-FLOOR_SHOWN_IN, -finBot - sub, xTop + FLOOR_SHOWN_IN, -finBot, Shower.Ink.THIN)
            note("${formatInches(finBot)}\" FINISH FLOOR ON ${formatInches(sub)}\" SUBFLOOR", -FLOOR_SHOWN_IN + 6f, -finBot / 2f, true)
        }

        // ---- Risers, treads ----
        for (i in 1..n) {
            val x = (i - 1) * t
            rect(x, (i - 1) * r, x + RISER_THK_IN, i * r - TREAD_THK_IN, Shower.Ink.THIN)   // riser board
            if (i < n) rect(x - nose, i * r - TREAD_THK_IN, x + t + RISER_THK_IN, i * r)      // tread, nosing out over the riser
        }
        note("5/4 TREADS, ${formatInches(nose)}\" NOSING", (n / 2) * t - nose, (n / 2) * r, false)
        note("3/4\" RISER BOARDS", (n / 2 - 1) * t, (n / 2 - 1) * r + r / 2f, true)

        // ---- Stringer: notched 2x12, bottom on the floor, top against the header ----
        val s = r / t
        val saw = mutableListOf(pt(RISER_THK_IN, 0f))
        for (i in 1 until n) {
            val x = (i - 1) * t + RISER_THK_IN
            saw += pt(x, i * r - TREAD_THK_IN); saw += pt(x + t, i * r - TREAD_THK_IN)
        }
        val drop = STRINGER_IN * sqrt(1f + s * s)
        fun under(x: Float) = s * (x - RISER_THK_IN) + r - TREAD_THK_IN - drop
        val xFoot = RISER_THK_IN + (drop - r + TREAD_THK_IN) / s    // where its underside meets the floor
        val xEnd = xTop + RISER_THK_IN
        val outline = saw + listOf(pt(xEnd, under(xEnd)), pt(xFoot, 0f))
        parts.add(Shower.Part.Poly(outline, true, Shower.Ink.LINE))
        note("2x12 STRINGERS, NOTCHED, 3 MIN. AT 36\" WIDE", (xFoot + xEnd) / 2f, under((xFoot + xEnd) / 2f) + 3f, false)

        // ---- The floor above: a double header at the top of the stair, and the opening's header back where the headroom holds ----
        val hb = top - finTop - sub - joist                                  // underside of the floor framing above
        val hdrW = 3f
        // The top: the stair hangs off a double header, the floor carries on beyond.
        cut(xEnd, hb, xEnd + hdrW, hb + joist)
        rect(xEnd + hdrW, hb, xEnd + hdrW + FLOOR_SHOWN_IN, hb + joist, Shower.Ink.THIN)
        rect(xEnd, hb + joist, xEnd + hdrW + FLOOR_SHOWN_IN, top - finTop, Shower.Ink.THIN)
        line(xEnd, top, xEnd + hdrW + FLOOR_SHOWN_IN, top, Shower.Ink.LINE)
        breakAt(xEnd + hdrW + FLOOR_SHOWN_IN, hb, top)
        note("(2) ${b.joistDepthIn.let { if (it >= 11f) "2x12" else if (it >= 9f) "2x10" else "2x8" }} HEADER, HANGERS, STRINGERS HUNG FROM IT", xEnd + hdrW, hb + joist / 2f, false)
        note("${formatInches(finTop)}\" FINISH FLOOR ON ${formatInches(sub)}\" SUBFLOOR", xEnd + hdrW + FLOOR_SHOWN_IN - 6f, top - finTop / 2f, false)
        // The low side of the opening: where 6'-8" over the nosing line still clears the framing.
        val xHdr = (t * ((hb - Stairs.HEADROOM_IN) / r - 1f)).coerceIn(0f, xTop - 12f)
        cut(xHdr - hdrW, hb, xHdr, hb + joist)
        rect(xHdr - hdrW - FLOOR_SHOWN_IN, hb, xHdr - hdrW, hb + joist, Shower.Ink.THIN)
        rect(xHdr - hdrW - FLOOR_SHOWN_IN, hb + joist, xHdr, top - finTop, Shower.Ink.THIN)
        line(xHdr - hdrW - FLOOR_SHOWN_IN, top, xHdr, top, Shower.Ink.LINE)
        breakAt(xHdr - hdrW - FLOOR_SHOWN_IN, hb, top)
        note("(2) HEADER AT THE FLOOR OPENING, TRIMMERS DOUBLED", xHdr - hdrW, hb + joist / 2f, true)

        // ---- Headroom and handrail, parallel to the nosing line ----
        val x1 = 0f
        val x2 = xTop
        parts.add(Shower.Part.Seg(pt(x1, nosingLine(x1)), pt(x2, nosingLine(x2)), Shower.Ink.FAINT_DASHED))
        parts.add(Shower.Part.Seg(pt(x1, nosingLine(x1) + Stairs.HEADROOM_IN), pt(x2, nosingLine(x2) + Stairs.HEADROOM_IN), Shower.Ink.DASHED))
        val clear = hb - nosingLine(xHdr)
        note("HEADROOM ${formatFeetInches(Stairs.HEADROOM_IN / 12f)} MIN. ABOVE THE NOSING LINE (${formatFeetInches(clear / 12f)} AT THE HEADER)", xHdr, nosingLine(xHdr) + Stairs.HEADROOM_IN, true)
        val railH = stair?.let { Stairs.prop(it, Stairs.RAIL_H) } ?: 36f
        val rx1 = t * 0.5f
        val rx2 = xTop - t * 0.5f
        line(rx1, nosingLine(rx1) + railH, rx2, nosingLine(rx2) + railH, Shower.Ink.LINE)
        line(rx1, nosingLine(rx1), rx1, nosingLine(rx1) + railH)
        line(rx2, nosingLine(rx2), rx2, nosingLine(rx2) + railH)
        note("HANDRAIL ${formatInches(railH)}\" ABOVE THE NOSINGS (34\"-38\")", (rx1 + rx2) * 0.7f, nosingLine((rx1 + rx2) * 0.7f) + railH, false)

        // ---- The stair's numbers ----
        note("$n RISERS @ ${formatInches(r)}\" = ${formatInches(top)}\" FINISH TO FINISH", xTop, top - r / 2f, false)
        note("${n - 1} TREADS @ ${formatInches(t)}\" = ${formatInches((n - 1) * t)}\" RUN", t * 1.5f, r, true)

        val leftCol = xHdr - hdrW - FLOOR_SHOWN_IN - 6f
        val rightCol = xEnd + hdrW + FLOOR_SHOWN_IN + 6f
        val topE = maxOf(top, nosingLine(xTop) + Stairs.HEADROOM_IN, nosingLine(rx2) + railH) + 8f
        val extent = Rect(leftCol - NOTE_GAP_IN - 64f, y(topE), rightCol + NOTE_GAP_IN + 72f, y(-finBot - (if (bottomOnSlab) 4f else sub) - 10f))
        return DetailKit.Geometry(parts, notes, extent, leftCol, rightCol)
    }
}
