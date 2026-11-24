package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The overhang (eave) detail (Dan, Nov 22 2026), for the A sheets with the wall details, drawn
 * from the roof settings so it always matches the roof: pitch, heel, overhang, trusses or rafters
 * and their size and spacing, the roofing, and the eave as built on this job - the soffit (boxed
 * level, sloped under the tails, or open tails), the attic (blown or batts with baffles, or spray
 * foam at the deck) with its R-value, and the ice and water shield up from the eave (Dan: two
 * rolls, about 6'). The wall under it is the first-floor exterior wall (2x6 or 2x4).
 *
 * Real inches: x = 0 at the outside face of the wall framing, inward positive; y = minus the
 * elevation above the top of the top plate.
 */
object OverhangDetail {
    private const val SHEATHING_IN = 0.4375f
    private const val SIDING_IN = 0.75f
    private const val DRYWALL_IN = 0.5f
    private const val SUB_FASCIA_W = 1.5f
    private const val RUN_IN = 42f
    private const val WALL_SHOWN_IN = 24f
    private const val NOTE_GAP_IN = 16f

    /** Actual depth of a 2x member ("2x8" -> 7 1/4"). */
    fun depthOf(size: String): Float = when (size.substringAfter("x").toIntOrNull()) {
        4 -> 3.5f; 6 -> 5.5f; 8 -> 7.25f; 10 -> 9.25f; 12 -> 11.25f; else -> 7.25f
    }

    /** [wallShownIn]: how much wall under the plate; [breakLine] false when a full-height section carries on below (WallSections). */
    fun geometry(state: CanvasState, wallShownIn: Float = WALL_SHOWN_IN, breakLine: Boolean = true): DetailKit.Geometry {
        val r = state.building.roof ?: RoofSettings()
        val parts = mutableListOf<Shower.Part>()
        val notes = mutableListOf<DetailKit.Note>()
        fun y(e: Float) = -e
        fun pt(x: Float, e: Float) = Offset(x, y(e))
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) =
            parts.add(Shower.Part.Poly(listOf(pt(x1, e1), pt(x2, e1), pt(x2, e2), pt(x1, e2)), true, ink))
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.THIN) = parts.add(Shower.Part.Seg(pt(x1, e1), pt(x2, e2), ink))
        fun poly(ink: Shower.Ink, closed: Boolean, p: List<Offset>) = parts.add(Shower.Part.Poly(p, closed, ink))
        fun note(text: String, x: Float, e: Float, left: Boolean) { notes += DetailKit.Note(text, pt(x, e), left) }
        // A piece of lumber cut by the section shows its end with an X corner to corner (Dan, Nov 22).
        fun cut(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) {
            rect(x1, e1, x2, e2, ink)
            line(x1, e1, x2, e2); line(x1, e2, x2, e1)
        }

        val stud = WallSection.studDepthIn(state)
        val t = r.pitch / 12f
        val cosA = 1f / sqrt(1f + t * t)
        val trusses = r.framing == RoofFraming.TRUSSES
        val d = if (trusses) 3.5f else depthOf(r.rafterSize)
        val heel = r.heelIn
        val ov = r.overhangIn
        val face = -(SHEATHING_IN + SIDING_IN)                 // outside face of the siding
        fun top(x: Float) = heel + x * t                        // top of the rafter / top chord
        fun bot(x: Float) = top(x) - d / cosA                   // its underside
        val tail = -ov                                           // the plumb cut at the end of the tail

        // ---- The wall: studs, double top plate, sheathing and wrap, siding, drywall ----
        val wallBottom = -wallShownIn
        cut(0f, -3f, stud, -1.5f)
        cut(0f, -1.5f, stud, 0f)
        line(0f, wallBottom, 0f, -3f, Shower.Ink.LINE)
        line(stud, wallBottom, stud, -3f, Shower.Ink.LINE)
        rect(-SHEATHING_IN, wallBottom, 0f, 0f, Shower.Ink.THIN)
        rect(stud, wallBottom, stud + DRYWALL_IN, -0.0f, Shower.Ink.THIN)
        // A break line across the bottom of the wall.
        val bx1 = face - 2f; val bx2 = stud + DRYWALL_IN + 2f; val bm = (bx1 + bx2) / 2f
        if (breakLine) poly(Shower.Ink.THIN, false, listOf(pt(bx1, wallBottom), pt(bm - 1.5f, wallBottom), pt(bm - 0.5f, wallBottom + 1.5f), pt(bm + 0.5f, wallBottom - 1.5f), pt(bm + 1.5f, wallBottom), pt(bx2, wallBottom)))

        // ---- The roof member ----
        if (trusses) {
            // Bottom chord on the plate, the raised heel at the wall, the top chord over it.
            rect(0f, 0f, RUN_IN, 3.5f)
            rect(0f, 3.5f, 3.5f, bot(3.5f).coerceAtLeast(3.5f))
            note("TRUSSES @ ${formatInches(r.ocIn)}\" O.C., ${formatInches(heel)}\" HEEL", RUN_IN * 0.6f, bot(RUN_IN * 0.6f) - 1f, false)
        } else {
            // Rafter on a birdsmouth seat at the plate; the ceiling joist beside it.
            rect(0f, 0f, RUN_IN, 5.5f, Shower.Ink.THIN)
            note("CEILING JOISTS", RUN_IN * 0.8f, 2.75f, false)
            note("${r.rafterSize} RAFTERS @ ${formatInches(r.ocIn)}\" O.C.", RUN_IN * 0.6f, (top(RUN_IN * 0.6f) + bot(RUN_IN * 0.6f)) / 2f, false)
        }
        // Top chord / rafter from the tail to the break. A rafter whose underside drops below the plate
        // sits on a birdsmouth: its underside runs to the outside of the wall, the seat across the plate,
        // then the underside again from where it clears the plate.
        poly(Shower.Ink.LINE, false, listOf(pt(tail, top(tail)), pt(RUN_IN, top(RUN_IN))))
        // The tail (Dan, Nov 22): cut plumb the height of the 2x6 sub-fascia, then level along the sub-fascia's
        // bottom back to the underside. A member shallower than that (a truss's 2x4) is just cut plumb.
        // The sub-fascia's top is dropped to the roof plane at its outer face, so the sheathing bears on it:
        // the tail's level cut is at the sub-fascia's bottom.
        val cutBottom = top(tail - SUB_FASCIA_W) - 5.5f
        val levelCut = bot(tail) < cutBottom
        val tailStart = if (levelCut) pt(((cutBottom - (heel - d / cosA)) / t), cutBottom) else pt(tail, bot(tail))
        if (levelCut) line(tail, cutBottom, tailStart.x, cutBottom, Shower.Ink.LINE)
        if (!trusses && bot(0f) < 0f) {
            val clear = ((d / cosA - heel) / t).coerceAtLeast(0f)   // where the underside rises to the plate top
            poly(Shower.Ink.LINE, false, listOf(tailStart, pt(0f, bot(0f)), pt(0f, 0f), pt(clear, 0f), pt(RUN_IN, bot(RUN_IN))))
        } else {
            poly(Shower.Ink.LINE, false, listOf(tailStart, pt(RUN_IN, bot(RUN_IN))))
        }
        line(tail, top(tail), tail, if (levelCut) cutBottom else bot(tail), Shower.Ink.LINE)    // the plumb cut
        // Break across the member at the right.
        line(RUN_IN, top(RUN_IN) + 2f, RUN_IN, (if (trusses) 0f else bot(RUN_IN)) - 1f)

        // ---- Sub-fascia, fascia wrap, drip edge, sheathing, ice and water, roofing ----
        // A 2x6 sub-fascia, standard (Dan): the tail is cut plumb its height and level along its bottom.
        val sfDepth = 5.5f
        val sfTop = top(tail - SUB_FASCIA_W)   // dropped to the roof plane at its outer edge (Dan)
        val sfBot = sfTop - sfDepth
        cut(tail - SUB_FASCIA_W, sfBot, tail, sfTop)
        note("2x6 SUB-FASCIA", tail - SUB_FASCIA_W / 2f, sfBot + sfDepth * 0.6f, true)
        val fx = tail - SUB_FASCIA_W - 0.5f
        poly(Shower.Ink.THIN, false, listOf(pt(fx, sfTop + 0.4f), pt(fx, sfBot - 0.75f), pt(fx + 2f, sfBot - 0.75f)))
        note("ALUMINUM FASCIA", fx, sfBot + 1.5f, true)
        // Roof sheathing (Dan, Nov 22): 1/2" OSB is standard up to 24" o.c.; 5/8" over wider spacing.
        val roofSheathing = if (r.ocIn <= 24f) 0.5f else 0.625f
        val skTop = { x: Float -> top(x) + roofSheathing / cosA }
        val x0 = tail - SUB_FASCIA_W
        poly(Shower.Ink.THIN, true, listOf(pt(x0, top(x0)), pt(RUN_IN, top(RUN_IN)), pt(RUN_IN, skTop(RUN_IN)), pt(x0, skTop(x0))))
        note("${if (roofSheathing == 0.5f) "1/2" else "5/8"}\" OSB ROOF SHEATHING", RUN_IN * 0.85f, (top(RUN_IN * 0.85f) + skTop(RUN_IN * 0.85f)) / 2f, false)
        // Drip edge over the sheathing's edge and down the fascia.
        poly(Shower.Ink.LINE, false, listOf(pt(x0 + 2f, skTop(x0 + 2f) + 0.1f), pt(x0 - 0.75f, skTop(x0) + 0.1f), pt(x0 - 0.75f, skTop(x0) - 1.25f)))
        note("DRIP EDGE", x0 - 0.75f, skTop(x0) - 0.6f, true)
        // Ice and water shield, then the roofing.
        val iwTop = { x: Float -> skTop(x) + 0.12f }
        val iwEnd = min(tail + r.iceWaterFt * 12f * cosA, RUN_IN)
        line(x0, iwTop(x0), iwEnd, iwTop(iwEnd), Shower.Ink.DASHED)
        val rolls = (r.iceWaterFt / 3f).let { if (it == it.toInt().toFloat()) "${it.toInt()} ROLL${if (it > 1f) "S" else ""} " else "" }
        note("ICE & WATER SHIELD, $rolls(${formatInches(r.iceWaterFt)}') UP FROM EAVE", RUN_IN * 0.3f, iwTop(RUN_IN * 0.3f), false)
        val rfTop = { x: Float -> skTop(x) + 0.45f }
        line(x0 - 0.5f, rfTop(x0 - 0.5f), RUN_IN, rfTop(RUN_IN), Shower.Ink.LINE)
        note(r.covering.label.uppercase(), RUN_IN * 0.45f, rfTop(RUN_IN * 0.45f), false)
        note("UNDERLAYMENT ABOVE THE ICE & WATER SHIELD", RUN_IN * 0.75f, iwTop(RUN_IN * 0.75f), false)
        // Pitch: rise over 12 above the roof.
        val px = RUN_IN - 14f
        val pe = rfTop(px) + 5f
        poly(Shower.Ink.THIN, false, listOf(pt(px, pe), pt(px + 12f * 0.5f, pe), pt(px + 12f * 0.5f, pe + r.pitch * 0.5f)))
        parts.add(Shower.Part.Label(RoofPlan.pitchText(r.pitch), pt(px + 7f, pe + r.pitch * 0.25f + 1f), 1f, Shower.Ink.LINE))

        // ---- The soffit ----
        val siding = { topE: Float ->
            rect(face, wallBottom, -SHEATHING_IN, topE, Shower.Ink.THIN)
            note("SIDING OVER HOUSE WRAP", face, wallBottom * 0.5f, true)
            note("7/16\" OSB WALL SHEATHING", -SHEATHING_IN / 2f, wallBottom * 0.75f, true)
        }
        when (r.soffit) {
            SoffitType.BOXED -> {
                // Level soffit at the bottom of the sub-fascia, on 2x4 lookouts back to a 2x4 nailer on the wall.
                val sb = sfBot
                rect(tail, sb, face, sb + 3.5f, Shower.Ink.THIN)
                cut(face - 1.5f, sb, face, sb + 3.5f, Shower.Ink.THIN)
                line(fx, sb - 0.75f, face, sb - 0.75f, Shower.Ink.LINE)
                note("VENTED SOFFIT", (tail + face) / 2f, sb - 0.75f, true)
                note("2x4 LOOKOUTS", (tail + face) / 2f + 2f, sb + 1.75f, true)
                note("2x4 NAILER", face - 0.75f, sb + 2.5f, true)
                siding(sb - 0.75f)
            }
            SoffitType.SLOPED -> {
                line(fx, bot(tail) - 0.5f, face, bot(face) - 0.5f, Shower.Ink.LINE)
                note("VENTED SOFFIT UNDER THE TAILS", (tail + face) / 2f, bot((tail + face) / 2f) - 0.5f, true)
                siding(bot(face) - 0.5f)
            }
            SoffitType.OPEN -> {
                // Exposed tails; a vented frieze block between the rafters at the wall.
                cut(-SHEATHING_IN - 1.5f, 0f, -SHEATHING_IN, max(bot(-SHEATHING_IN - 0.75f), 1f), Shower.Ink.THIN)
                note("VENTED FRIEZE BLOCK", -SHEATHING_IN - 0.75f, bot(-1f) * 0.5f, true)
                note("EXPOSED RAFTER TAILS", tail * 0.6f, (top(tail * 0.6f) + bot(tail * 0.6f)) / 2f, true)
                siding(bot(face))
            }
        }

        // ---- The attic ----
        val ceilTop = if (trusses) 3.5f else 5.5f
        when (r.attic) {
            AtticInsulation.BLOWN, AtticInsulation.BATTS -> {
                // A baffle under the deck keeps the air path open from the soffit; insulation over the plate to the R-value's depth.
                val baffle = { x: Float -> top(x) - 1.75f / cosA }
                line(-SHEATHING_IN, baffle(-SHEATHING_IN), RUN_IN * 0.8f, baffle(RUN_IN * 0.8f), Shower.Ink.DASHED)
                note("RAFTER VENT (BAFFLE)", RUN_IN * 0.5f, baffle(RUN_IN * 0.5f), false)
                val perInch = if (r.attic == AtticInsulation.BLOWN) 3.2f else 3.1f
                val depth = r.atticR / perInch
                val pts = mutableListOf(pt(0f, ceilTop))
                var x = 0f
                while (x <= RUN_IN) { pts += pt(x, min(ceilTop + depth, baffle(x) - 0.25f).coerceAtLeast(ceilTop)); x += 2f }
                pts += pt(RUN_IN, ceilTop)
                parts.add(Shower.Part.Poly(pts, true, Shower.Ink.FAINT_DASHED))
                note("R-${r.atticR} ${if (r.attic == AtticInsulation.BLOWN) "BLOWN INSULATION" else "BATT INSULATION"}", RUN_IN * 0.75f, ceilTop + min(depth, 8f) * 0.5f, false)
            }
            AtticInsulation.SPRAY_FOAM -> {
                val foam = 5.5f
                poly(Shower.Ink.FAINT_DASHED, true, listOf(pt(0f, top(0f)), pt(RUN_IN, top(RUN_IN)), pt(RUN_IN, top(RUN_IN) - foam / cosA), pt(0f, top(0f) - foam / cosA)))
                note("R-${r.atticR} CLOSED-CELL SPRAY FOAM AT THE DECK (UNVENTED)", RUN_IN * 0.5f, top(RUN_IN * 0.5f) - foam / cosA / 2f, false)
            }
        }
        note("1/2\" DRYWALL", stud + DRYWALL_IN / 2f, wallBottom * 0.6f, false)
        note("DBL TOP PLATE", stud * 0.5f, -1.5f, false)
        note("${WallSection.wallLabel(state)} STUDS", stud * 0.5f, wallBottom * 0.85f, false)
        // The overhang, labelled under the eave.
        parts.add(Shower.Part.Label("${formatInches(ov)}\" OVERHANG", pt(tail, sfBot - 4f), 0.9f, Shower.Ink.LINE))

        val leftCol = fx - 6f
        val rightCol = RUN_IN + 4f
        val topE = rfTop(RUN_IN) + 10f
        val extent = Rect(leftCol - NOTE_GAP_IN - 30f, y(topE), rightCol + NOTE_GAP_IN + 46f, y(wallBottom - 4f))
        return DetailKit.Geometry(parts, notes, extent, leftCol, rightCol)
    }
}
