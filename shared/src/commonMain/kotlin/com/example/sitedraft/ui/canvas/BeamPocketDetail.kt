package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The basement beam pocket detail (Nov 22 2026), for the S sheets with the structural details: the
 * steel beam the structure design picked bearing in a pocket in the 8" block foundation wall,
 * cut along the beam. Drawn from the job: the beam's shape (its depth and flanges), the sill and
 * joists, so it always matches the framing.
 *
 * Pocket defaults (common practice until Dan sets his): 4" of bearing, 1/2" air space at the end
 * of the beam, a 1/2" steel bearing plate on block cores filled solid two courses down, steel
 * shims as needed. Dan (Nov 22): the top of the beam is flush with the bottom of the sill plate and a
 * 2x on the beam gives the joists nailing, its top flush with the top of the sill.
 *
 * Real inches: x = 0 at the inside face of the foundation wall, into the basement positive;
 * y = minus the elevation above the top of the foundation wall.
 */
object BeamPocketDetail {
    private const val BEARING_IN = 4f
    private const val AIR_IN = 0.5f
    private const val PLATE_T_IN = 0.5f
    private const val SILL_SEAL_IN = 0.25f
    private const val SUBFLOOR_IN = 0.75f
    private const val COURSE_IN = 8f
    private const val RUN_IN = 40f
    private const val NOTE_GAP_IN = 16f

    /** The beam shown: the first beam line's designed shape, else a W8x18. */
    fun beamShape(state: CanvasState): SteelEngine.Shape =
        runCatching { StructuralDesign.design(state)?.beams?.firstOrNull()?.shape }.getOrNull()
            ?: SteelEngine.AISC_DATABASE.first { it.name == "W8x18" }

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
        // Lumber cut by the section shows its end with an X corner to corner (Dan, Nov 22).
        fun cut(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) {
            rect(x1, e1, x2, e2, ink); line(x1, e1, x2, e2); line(x1, e2, x2, e1)
        }

        val wall = state.building.foundationWall
        val WALL_IN = wall.thicknessIn             // the foundation wall (12" block unless picked)
        val shape = beamShape(state)
        val d = shape.d.toFloat()
        val tf = shape.tf.toFloat()
        val stud = WallSection.studDepthIn(state)
        val jd = state.building.joistDepthIn
        val (joistLabel, joistOc) = WallSection.joist(state)

        val back = -(BEARING_IN + AIR_IN)          // the back of the pocket
        val beamEnd = -BEARING_IN
        val bt = SILL_SEAL_IN                     // top of the beam = bottom of the sill plate (Dan)
        val beamBot = bt - d
        val pocketBot = beamBot - PLATE_T_IN
        val wallBottom = pocketBot - 2f * COURSE_IN - 6f

        // ---- The foundation wall, cut, with the pocket in it ----
        line(-WALL_IN, wallBottom, -WALL_IN, 0f, Shower.Ink.LINE)                 // outside face
        line(-WALL_IN, 0f, back, 0f, Shower.Ink.LINE)                             // top of the wall behind the pocket
        poly(Shower.Ink.LINE, false, listOf(pt(back, 0f), pt(back, pocketBot), pt(0f, pocketBot), pt(0f, wallBottom)))
        if (!wall.poured) {
            // Block courses: bed joints every 8" down from the top.
            var e = -COURSE_IN
            while (e > wallBottom + 1f) {
                line(-WALL_IN, e, if (e > pocketBot) back else 0f, e, Shower.Ink.FAINT)
                e -= COURSE_IN
            }
            // Cores filled solid under the pocket, two courses.
            rect(-WALL_IN + 1.25f, pocketBot - 2f * COURSE_IN, -1.25f, pocketBot, Shower.Ink.FAINT_DASHED)
            note("FILL BLOCK CORES SOLID UNDER POCKET (2 COURSES)", -WALL_IN / 2f, pocketBot - COURSE_IN, true)
            note("${wall.callout} FOUNDATION WALL", -WALL_IN, wallBottom + 3f, true)
        } else {
            note("${wall.callout} FOUNDATION WALL, POCKET FORMED IN", -WALL_IN, wallBottom + 3f, true)
        }
        // Break line across the bottom of the wall.
        val bm = -WALL_IN / 2f
        poly(Shower.Ink.THIN, false, listOf(pt(-WALL_IN - 2f, wallBottom), pt(bm - 1.2f, wallBottom), pt(bm - 0.4f, wallBottom + 1.2f),
            pt(bm + 0.4f, wallBottom - 1.2f), pt(bm + 1.2f, wallBottom), pt(2f, wallBottom)))

        // ---- Bearing plate and the beam ----
        rect(beamEnd, pocketBot, 0f, beamBot)
        note("1/2\" STEEL BEARING PLATE, STEEL SHIMS AS NEEDED", -BEARING_IN / 2f, pocketBot + PLATE_T_IN / 2f, true)
        line(beamEnd, bt, RUN_IN, bt, Shower.Ink.LINE)
        line(beamEnd, bt - tf, RUN_IN, bt - tf)
        line(beamEnd, beamBot + tf, RUN_IN, beamBot + tf)
        line(beamEnd, beamBot, RUN_IN, beamBot, Shower.Ink.LINE)
        line(beamEnd, bt, beamEnd, beamBot, Shower.Ink.LINE)
        note("${shape.name.uppercase()} STEEL BEAM", RUN_IN * 0.6f, beamBot / 2f, false)
        note("TOP OF BEAM FLUSH WITH BOTTOM OF SILL PLATE", RUN_IN * 0.85f, bt, false)
        parts.add(Shower.Part.Label("${formatInches(BEARING_IN)}\" BEARING", pt(-BEARING_IN / 2f, pocketBot - 2.5f), 0.8f, Shower.Ink.LINE))
        note("1/2\" AIR SPACE AT END OF BEAM", back + AIR_IN / 2f, (bt + beamBot) / 2f, true)

        // ---- Sill, nailer, joists, subfloor ----
        val sillBot = SILL_SEAL_IN
        val sillTop = sillBot + 1.5f
        rect(-WALL_IN, 0f, -WALL_IN + stud, sillBot, Shower.Ink.THIN)
        cut(-WALL_IN, sillBot, -WALL_IN + stud, sillTop)
        note("TREATED ${WallSection.wallLabel(state)} SILL ON SILL SEAL", -WALL_IN + 0.75f, sillBot + 0.75f, true)
        rect(-WALL_IN + stud, bt, RUN_IN, sillTop, Shower.Ink.THIN)
        note("2x NAILER ON BEAM FOR JOIST NAILING, FLUSH WITH TOP OF SILL", RUN_IN * 0.7f, (bt + sillTop) / 2f, false)
        val jTop = sillTop + jd
        cut(-WALL_IN, sillTop, -WALL_IN + 1.5f, jTop)
        note("RIM JOIST", -WALL_IN + 0.75f, sillTop + jd * 0.7f, true)
        var jx = -WALL_IN + joistOc - 0.75f
        var firstJoist = true
        while (jx + 1.5f < RUN_IN) {
            cut(jx, sillTop, jx + 1.5f, jTop)
            if (firstJoist) { note("$joistLabel FLOOR JOISTS @ ${formatInches(joistOc)}\" O.C.", jx + 1.5f, sillTop + jd * 0.5f, false); firstJoist = false }
            jx += joistOc
        }
        rect(-WALL_IN, jTop, RUN_IN, jTop + SUBFLOOR_IN, Shower.Ink.THIN)
        note("${formatInches(SUBFLOOR_IN)}\" SUBFLOOR", RUN_IN * 0.9f, jTop + SUBFLOOR_IN / 2f, false)
        // Break across everything at the right.
        val bx = RUN_IN
        poly(Shower.Ink.THIN, false, listOf(pt(bx, jTop + SUBFLOOR_IN + 2f), pt(bx, beamBot / 2f + 1.2f), pt(bx - 1.2f, beamBot / 2f + 0.4f),
            pt(bx + 1.2f, beamBot / 2f - 0.4f), pt(bx, beamBot / 2f - 1.2f), pt(bx, beamBot - 2f)))

        val leftCol = -WALL_IN - 6f
        val rightCol = RUN_IN + 4f
        val extent = Rect(leftCol - NOTE_GAP_IN - 40f, y(jTop + SUBFLOOR_IN + 6f), rightCol + NOTE_GAP_IN + 52f, y(wallBottom - 4f))
        return DetailKit.Geometry(parts, notes, extent, leftCol, rightCol)
    }
}
