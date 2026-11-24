package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Full-height wall sections (Dan, Nov 23 2026), for the S sheets with the other details, put together from the
 * pieces that are already drawn live from the job, so they always agree with the plans and elevations:
 *  - the exterior wall section, subfloor to roof: the floor at the rim (top of the foundation, sill, rim, joists,
 *    subfloor), the wall its full height, and the eave from [OverhangDetail] on top
 *  - the garage wall section, footing to roof: [GarageFrostDetail] with the wall run up to the garage plate, and the
 *    eave from [OverhangDetail] on top (the garage plate lines up with the house's)
 * Real inches: x = 0 at the outside face of the framing; y = minus the elevation.
 */
object WallSections {
    private const val SHEATHING_IN = 0.4375f
    private const val SIDING_IN = 0.75f
    private const val RUN_IN = 30f

    private fun shifted(g: DetailKit.Geometry, dy: Float): DetailKit.Geometry {
        fun m(o: Offset) = Offset(o.x, o.y + dy)
        val parts = g.parts.map { p ->
            when (p) {
                is Shower.Part.Seg -> p.copy(a = m(p.a), b = m(p.b))
                is Shower.Part.Poly -> p.copy(points = p.points.map(::m))
                is Shower.Part.Ring -> p.copy(c = m(p.c))
                is Shower.Part.Label -> p.copy(c = m(p.c))
            }
        }
        return DetailKit.Geometry(parts, g.notes.map { it.copy(at = m(it.at)) }, g.extent.translate(0f, dy), g.leftColX, g.rightColX)
    }

    private fun joined(a: DetailKit.Geometry, b: DetailKit.Geometry, extra: List<Shower.Part> = emptyList(), notes: List<DetailKit.Note> = emptyList()): DetailKit.Geometry {
        val e = Rect(minOf(a.extent.left, b.extent.left), minOf(a.extent.top, b.extent.top), maxOf(a.extent.right, b.extent.right), maxOf(a.extent.bottom, b.extent.bottom))
        return DetailKit.Geometry(a.parts + b.parts + extra, a.notes + b.notes + notes, e, minOf(a.leftColX, b.leftColX), maxOf(a.rightColX, b.rightColX))
    }

    /** The house's exterior wall from the subfloor to the roof. Elevations here are above the top of the subfloor. */
    fun house(state: CanvasState): DetailKit.Geometry {
        val s = state.building
        val wallH = s.plateHeightIn
        // The eave and the wall above the floor, the plate's top at wallH.
        val top = shifted(OverhangDetail.geometry(state, wallShownIn = wallH, breakLine = false), -wallH)
        val parts = mutableListOf<Shower.Part>()
        val notes = mutableListOf<DetailKit.Note>()
        fun pt(x: Float, e: Float) = Offset(x, -e)
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) =
            parts.add(Shower.Part.Poly(listOf(pt(x1, e1), pt(x2, e1), pt(x2, e2), pt(x1, e2)), true, ink))
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.THIN) = parts.add(Shower.Part.Seg(pt(x1, e1), pt(x2, e2), ink))
        fun cut(x1: Float, e1: Float, x2: Float, e2: Float) { rect(x1, e1, x2, e2); line(x1, e1, x2, e2); line(x1, e2, x2, e1) }
        fun note(text: String, x: Float, e: Float, left: Boolean) { notes += DetailKit.Note(text, pt(x, e), left) }
        val stud = WallSection.studDepthIn(state)
        val joistTop = -s.subfloorIn
        val sillTop = joistTop - s.joistDepthIn
        val wTop = sillTop - s.sillIn
        // Bottom plate on the subfloor.
        cut(0f, 0f, stud, 1.5f)
        note("${WallSection.wallLabel(state)} BOTTOM PLATE", stud, 0.75f, false)
        // Subfloor, rim, joists, sill on sill seal, the top of the foundation wall with a break.
        rect(0f, joistTop, RUN_IN, 0f, Shower.Ink.THIN)
        note("${formatInches(s.subfloorIn)}\" SUBFLOOR, GLUED AND NAILED", RUN_IN - 2f, joistTop / 2f, false)
        cut(0f, sillTop, 1.5f, joistTop)
        note("RIM JOIST", 0.75f, sillTop + (joistTop - sillTop) * 0.8f, true)
        rect(1.5f, sillTop, RUN_IN, joistTop, Shower.Ink.THIN)
        note("FLOOR JOISTS (SEE FRAMING PLAN)", RUN_IN - 6f, (sillTop + joistTop) / 2f, false)
        line(0f, wTop + 0.15f, stud, wTop + 0.15f, Shower.Ink.ACCENT)
        cut(0f, wTop + 0.25f, stud, sillTop)
        note("TREATED ${WallSection.wallLabel(state)} SILL ON SILL SEAL", stud, wTop + 0.75f, false)
        val fw = s.foundationWall.thicknessIn
        val fBot = wTop - 16f
        line(0f, fBot, 0f, wTop, Shower.Ink.LINE); line(fw, fBot, fw, wTop, Shower.Ink.LINE); line(0f, wTop, fw, wTop, Shower.Ink.LINE)
        note(s.foundationWall.callout + " FOUNDATION WALL (SEE TYPICAL WALL SECTION)", fw, wTop - 8f, false)
        val bm = fw / 2f
        parts.add(Shower.Part.Poly(listOf(pt(-3f, fBot), pt(bm - 1.5f, fBot), pt(bm - 0.5f, fBot + 1.5f), pt(bm + 0.5f, fBot - 1.5f), pt(bm + 1.5f, fBot), pt(fw + 3f, fBot)), false, Shower.Ink.THIN))
        // Sheathing and siding carried down over the rim to the sill.
        rect(-SHEATHING_IN, wTop - 0.5f, 0f, 0f, Shower.Ink.THIN)
        rect(-(SHEATHING_IN + SIDING_IN), wTop - 1f, -SHEATHING_IN, 0f, Shower.Ink.THIN)
        // Floor-to-plate height, as built.
        parts.add(Shower.Part.Label("${Building.elevationText(wallH)} SUBFLOOR TO TOP OF PLATE", pt(stud + 14f, wallH / 2f), 0.9f, Shower.Ink.LINE))
        val lower = DetailKit.Geometry(parts, notes, Rect(top.extent.left, -1.5f, top.extent.right, -(fBot - 6f)), top.leftColX, top.rightColX)
        return joined(top, lower)
    }

    /** The garage wall from the footing to the roof. Elevations here are above the slab (grade). */
    fun garage(state: CanvasState): DetailKit.Geometry {
        val s = state.building
        val plateAbove = Garages.plateIn(s) - Garages.slabTopIn(s)
        val bottom = GarageFrostDetail.geometry(state, wallTopIn = plateAbove - 3f)
        val sill = Garages.COURSE_IN + 1.75f
        val top = shifted(OverhangDetail.geometry(state, wallShownIn = plateAbove - sill, breakLine = false), -plateAbove)
        val label = Shower.Part.Label("${Building.elevationText(plateAbove)} SLAB TO TOP OF PLATE", Offset(19.5f, -plateAbove / 2f), 0.9f, Shower.Ink.LINE)
        return joined(bottom, top, listOf(label))
    }
}
