package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The building section A-A (Dan, Nov 25, for the architect demo): a cut across the whole house, square to the
 * ridge, footing to ridge, drawn live from the Job specs (foundation, floors, plate height, roof) and the plan's
 * footprint, so it always agrees with the plans, the elevations and the wall section.
 *
 * Shows: grade, footings with their rebar note, the foundation walls, the basement slab, the centre beam with its
 * post and pad (when the span needs one), sill, joists, rim and subfloor, each floor's exterior walls to the plate,
 * any upper floors, the roof (trusses with webs, or rafters with ceiling joists) with the overhang, fascia and
 * soffit, attic insulation, and level marks down the right side. Notes on the left.
 *
 * Real inches: x = 0 at the outside face of the framing on the left wall, across the span; y = minus the
 * elevation (Building elevations, the first-floor subfloor at the datum, 100'-0" by default).
 */
object BuildingSection {
    private const val SHEATHING_IN = 0.4375f
    private const val SIDING_IN = 0.75f
    private const val BEAM_DEPTH_IN = 8f
    private const val BEAM_FLANGE_IN = 5.25f
    private const val PAD_IN = 24f
    private const val PAD_THICK_IN = 12f

    /** The width of the cut (across the ridge), inches, from the plan's footprint; 28' when there's no plan yet. */
    fun spanIn(state: CanvasState): Float {
        val fp = RoofPlan.footprint(state) ?: return 336f
        val k = state.inchesToPx(1f)
        val r = state.building.roof
        val across = (fp.width >= fp.height) != (r?.turnRidge ?: false)
        return (if (across) fp.height else fp.width) / k
    }

    fun geometry(state: CanvasState): DetailKit.Geometry {
        val s = state.building
        val r = s.roof
        val W = spanIn(state)
        val parts = mutableListOf<Shower.Part>()
        val notes = mutableListOf<DetailKit.Note>()
        fun pt(x: Float, e: Float) = Offset(x, -e)
        fun poly(p: List<Offset>, closed: Boolean = true, ink: Shower.Ink = Shower.Ink.LINE) = parts.add(Shower.Part.Poly(p, closed, ink))
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.LINE) =
            poly(listOf(pt(x1, e1), pt(x2, e1), pt(x2, e2), pt(x1, e2)), ink = ink)
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, ink: Shower.Ink = Shower.Ink.THIN) = parts.add(Shower.Part.Seg(pt(x1, e1), pt(x2, e2), ink))
        fun note(text: String, x: Float, e: Float) { notes += DetailKit.Note(text, pt(x, e), true) }
        fun both(f: (mirror: (Float) -> Float) -> Unit) { f { it }; f { W - it } }
        fun xr(m: (Float) -> Float, a: Float, b: Float) = minOf(m(a), m(b)) to maxOf(m(a), m(b))

        val fw = Building.foundationWallIn(s)
        val stud = WallSection.studDepthIn(state)
        val foundTop = Building.foundationTopIn(s)
        val footTop = Building.footingTopIn(s)
        val footBot = Building.footingBottomIn(s)
        val sillTop = Building.sillTopIn(s)
        val subfloor = s.datumIn
        val (projOut, projIn) = Building.footingProjections(s)
        val grade = foundTop - (s.gradeExposureIn ?: 8f)
        val basement = s.foundation == FoundationType.BASEMENT

        // Grade, past the house each side.
        line(-60f, grade, 0f, grade, Shower.Ink.LINE); line(W, grade, W + 60f, grade, Shower.Ink.LINE)

        // Footings and foundation walls, both sides.
        both { m ->
            val (a, b) = xr(m, -projOut, fw + projIn)
            rect(a, footBot, b, footTop)
            val (c, d) = xr(m, 0f, fw)
            rect(c, footTop, d, foundTop)
            // Drain tile inside and outside the footing.
            parts.add(Shower.Part.Ring(pt(m(-projOut - 3f), footTop + 2f), 2f, Shower.Ink.THIN))
            parts.add(Shower.Part.Ring(pt(m(fw + projIn + 3f), footTop + 2f), 2f, Shower.Ink.THIN))
        }
        val bars = "${s.footingBarsTop} #${s.rebarSize} TOP" + if (s.footingBarsBottom > 0) ", ${s.footingBarsBottom} #${s.rebarSize} BOTTOM" else ""
        note("${formatInches(s.footingWidthIn)}\" x ${formatInches(s.footingThicknessIn)}\" CONCRETE FOOTING, $bars", -projOut + 2f, (footTop + footBot) / 2f)
        note("${s.drainTile.label.uppercase()} DRAIN TILE, INSIDE AND OUTSIDE", -projOut - 3f, footTop + 2f)
        note("${s.foundationWall.callout} FOUNDATION WALL", fw / 2f, (footTop + foundTop) / 2f)
        if (s.foundationFoamIn > 0f) {
            rect(-s.foundationFoamIn, grade - 12f, 0f, foundTop - 2f, Shower.Ink.THIN)
            note("${formatInches(s.foundationFoamIn)}\" RIGID FOAM", -s.foundationFoamIn / 2f, grade - 2f)
        }
        line(-60f, grade, -s.foundationFoamIn, grade, Shower.Ink.LINE)
        note("FINISH GRADE", -40f, grade)

        // Basement slab between the walls.
        if (basement) {
            rect(fw, footTop, W - fw, Building.basementFloorIn(s), Shower.Ink.THIN)
            note("${formatInches(s.basementSlabIn)}\" CONCRETE SLAB", fw + 30f, (footTop + Building.basementFloorIn(s)) / 2f)
        }

        // Centre beam, post and pad when the joists can't span wall to wall (Dan: one centre beam, W8 at least).
        val clear = W - 2f * fw
        val beams = if (basement) StructuralDesign.beamCount(clear, s.structure.maxJoistSpanFt) else 0
        for (i in 1..beams) {
            val x = fw + clear * i / (beams + 1)
            val beamTop = foundTop
            val beamBot = beamTop - BEAM_DEPTH_IN
            // W shape: flanges and web.
            rect(x - BEAM_FLANGE_IN / 2f, beamTop - 0.4f, x + BEAM_FLANGE_IN / 2f, beamTop)
            rect(x - BEAM_FLANGE_IN / 2f, beamBot, x + BEAM_FLANGE_IN / 2f, beamBot + 0.4f)
            rect(x - 0.15f, beamBot + 0.4f, x + 0.15f, beamTop - 0.4f)
            rect(x - 2.75f, beamTop, x + 2.75f, sillTop, Shower.Ink.THIN)
            val floorTop = Building.basementFloorIn(s)
            rect(x - 1.75f, floorTop, x + 1.75f, beamBot)
            rect(x - 3f, floorTop, x + 3f, floorTop + 0.5f, Shower.Ink.THIN)
            rect(x - PAD_IN / 2f, footTop - PAD_THICK_IN, x + PAD_IN / 2f, footTop, Shower.Ink.DASHED)
            if (i == 1) {
                note("STEEL BEAM (SEE STRUCTURAL CALCULATIONS), 2x ON TOP FOR NAILING", x, beamBot + BEAM_DEPTH_IN / 2f)
                note("STEEL COLUMN ON 6\" x 6\" PLATE", x, (floorTop + beamBot) / 2f)
                note("PAD FOOTING UNDER SLAB", x - PAD_IN / 2f + 2f, footTop - PAD_THICK_IN / 2f)
            }
        }

        // First floor: sill, rim, joists, subfloor.
        both { m ->
            val (a, b) = xr(m, 0f, stud)
            rect(a, foundTop, b, sillTop)
            val (c, d) = xr(m, 0f, 1.5f)
            rect(c, sillTop, d, sillTop + s.joistDepthIn)
        }
        rect(1.5f, sillTop, W - 1.5f, sillTop + s.joistDepthIn, Shower.Ink.THIN)
        rect(0f, sillTop + s.joistDepthIn, W, subfloor)
        note("TREATED ${WallSection.wallLabel(state)} SILL ON SILL SEAL, ANCHOR BOLTS", stud / 2f, (foundTop + sillTop) / 2f)
        val (joistSize, joistOc) = WallSection.joist(state)
        note("$joistSize FLOOR JOISTS @ ${formatInches(joistOc)}\" O.C. (SEE FRAMING PLAN)", W * 0.3f, sillTop + s.joistDepthIn / 2f)
        note("${formatInches(s.subfloorIn)}\" SUBFLOOR, GLUED AND NAILED", W * 0.22f, subfloor - s.subfloorIn / 2f)

        // Each floor's exterior walls to the plate; upper floors get their own floor system.
        val floors = listOf(subfloor) + s.upperFloors.map { it.elevationIn }
        val plateTop = Building.roofPlateIn(s) ?: (floors.last() + s.plateHeightIn)
        floors.forEachIndexed { i, base ->
            val top = if (i == floors.lastIndex) plateTop else floors[i + 1] - s.subfloorIn - s.joistDepthIn
            both { m ->
                val (a, b) = xr(m, 0f, stud)
                rect(a, base, b, top)
                line(a, base + 1.5f, b, base + 1.5f)
                line(a, top - 1.5f, b, top - 1.5f); line(a, top - 3f, b, top - 3f)
                // Sheathing and siding on the outside face.
                val (c, d) = xr(m, -SHEATHING_IN - SIDING_IN, 0f)
                rect(c, if (i == 0) foundTop + 1f else base - s.joistDepthIn - s.subfloorIn, d, top, Shower.Ink.THIN)
            }
            if (i > 0) {
                val jBot = base - s.subfloorIn - s.joistDepthIn
                rect(1.5f, jBot, W - 1.5f, base - s.subfloorIn, Shower.Ink.THIN)
                rect(0f, base - s.subfloorIn, W, base)
                note("${Building.floorName(s, i + 1).uppercase()} FRAMING (SEE FRAMING PLAN)", W * 0.3f, (jBot + base) / 2f)
            }
            parts.add(Shower.Part.Label(Building.elevationText(top - base) + " TO PLATE", pt(stud + 10f, (base + top) / 2f + 6f), 1f, Shower.Ink.LINE))
        }
        note("${WallSection.wallLabel(state)} STUDS @ 16\" O.C., DOUBLE TOP PLATE", stud / 2f, plateTop - 20f)
        note("7/16\" OSB SHEATHING, HOUSE WRAP, SIDING", -SHEATHING_IN - SIDING_IN / 2f, subfloor + 40f)

        // The roof: trusses (or rafters and ceiling joists) across the span, overhang each side.
        var ridgeTop = plateTop
        if (r != null && r.pitch > 0f) {
            val rise = r.pitch / 12f
            val heel = plateTop + r.heelIn
            val ov = r.overhangIn
            val ridge = heel + (W / 2f) * rise
            ridgeTop = ridge + 6f
            val eaveL = heel - ov * rise
            // Top chord / rafter top, and the roofing on it.
            poly(listOf(pt(-ov, eaveL), pt(W / 2f, ridge), pt(W + ov, eaveL)), closed = false)
            poly(listOf(pt(-ov, eaveL + 1f), pt(W / 2f, ridge + 1f), pt(W + ov, eaveL + 1f)), closed = false, ink = Shower.Ink.THIN)
            // Bottom chord / ceiling joists on the plates.
            rect(0f, plateTop, W, plateTop + 3.5f, Shower.Ink.THIN)
            val truss = r.framing == RoofFraming.TRUSSES
            if (truss) {
                // Fink webs.
                val q1 = W / 3f; val q2 = 2f * W / 3f
                fun topAt(x: Float) = heel + minOf(x, W - x) * rise
                line(q1, plateTop + 3.5f, W / 4f, topAt(W / 4f)); line(q1, plateTop + 3.5f, W / 2f, ridge)
                line(q2, plateTop + 3.5f, 3f * W / 4f, topAt(3f * W / 4f)); line(q2, plateTop + 3.5f, W / 2f, ridge)
                // Heels.
                line(0f, plateTop, 0f, heel); line(W, plateTop, W, heel)
                note("ENGINEERED TRUSSES @ ${formatInches(r.ocIn)}\" O.C., ${RoofPlan.pitchText(r.pitch)} (SEE ROOF FRAMING PLAN)", W * 0.15f, heel + W * 0.15f * rise - 3f)
            } else {
                note("${r.rafterSize} RAFTERS @ ${formatInches(r.ocIn)}\" O.C., ${RoofPlan.pitchText(r.pitch)}; CEILING JOISTS (SEE ROOF FRAMING PLAN)", W * 0.15f, heel + W * 0.15f * rise - 3f)
                rect(W / 2f - 0.75f, ridge - 9f, W / 2f + 0.75f, ridge, Shower.Ink.THIN)
            }
            // Fascia and soffit each side.
            both { m ->
                val (a, b) = xr(m, -ov - 1.5f, -ov)
                rect(a, eaveL - 6f, b, eaveL + 1f, Shower.Ink.THIN)
                if (r.soffit == SoffitType.BOXED) line(m(-ov), eaveL - 6f, m(-SHEATHING_IN - SIDING_IN), eaveL - 6f)
            }
            note("1/2\" OSB ROOF SHEATHING, ${r.covering.label.uppercase()}, ICE AND WATER SHIELD ${formatInches(r.iceWaterFt)}' UP FROM EAVES", W * 0.08f, heel + W * 0.08f * rise + 1f)
            note("FASCIA AND ${r.soffit.label.uppercase()} SOFFIT", -ov - 0.75f, eaveL - 3f)
            // Attic insulation over the ceiling.
            if (r.attic != AtticInsulation.SPRAY_FOAM) {
                line(12f, plateTop + 3.5f + 14f, W - 12f, plateTop + 3.5f + 14f, Shower.Ink.DASHED)
                note("R-${r.atticR} ${r.attic.label.uppercase()} ATTIC INSULATION; 1/2\" DRYWALL CEILING", W * 0.4f, plateTop + 10f)
            }
        } else {
            rect(0f, plateTop, W, plateTop + 3.5f, Shower.Ink.THIN)
            note("ROOF NOT SET UP YET (JOB SPECS > ROOF)", W / 2f, plateTop + 3.5f)
        }

        // Level marks down the right side.
        val markX = W + (r?.overhangIn ?: 12f) + 18f
        fun level(name: String, e: Float) {
            line(W + 4f, e, markX + 6f, e, Shower.Ink.DASHED)
            parts.add(Shower.Part.Poly(listOf(pt(markX, e), pt(markX - 3f, e + 5f), pt(markX + 3f, e + 5f)), true, Shower.Ink.LINE))
            parts.add(Shower.Part.Label("$name ${Building.elevationText(e)}", pt(markX + 8f, e + 12f), 1f, Shower.Ink.LINE))
        }
        if (r != null && r.pitch > 0f) level("T.O. RIDGE", plateTop + r.heelIn + (W / 2f) * (r.pitch / 12f))
        level("T.O. PLATE", plateTop)
        s.upperFloors.forEach { level("T.O. ${it.name.uppercase()} SUBFLOOR", it.elevationIn) }
        level("T.O. SUBFLOOR", subfloor)
        level("T.O. FOUNDATION", foundTop)
        if (basement) level("T.O. SLAB", Building.basementFloorIn(s))
        level("T.O. FOOTING", footTop)

        val ov = r?.overhangIn ?: 12f
        val leftCol = -ov - 70f
        // Room for the notes' text to the left of their column (about 5" of paper at 1/4" = 1'-0") and the level marks' to the right.
        val extent = Rect(leftCol - 260f, -(ridgeTop + 12f), markX + 150f, -(footBot - 12f))
        return DetailKit.Geometry(parts, notes, extent, leftCol, markX + 150f)
    }
}
