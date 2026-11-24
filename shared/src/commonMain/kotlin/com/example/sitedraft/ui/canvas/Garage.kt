package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.max

/**
 * Garages (Dan, Nov 23 2026), attached or detached, on the first-floor plan. Attached when it's
 * against an exterior wall of the house, detached otherwise (worked out from where it sits).
 *
 * Round 1: the plan (2x6 walls, the overhead door), the foundation (12" block frost walls on a
 * continuous footing at frost depth, 42"; an attached garage's footing butts the house footing, and
 * where the house has a basement it steps down to the basement footing with a lead wall, 3' to 5'
 * long), and the 4" slab. Round 2: the garage roof and the garage on the elevations.
 */
object Garages {
    const val CATEGORY = "Garage"
    const val NAME = "Garage"
    val library = listOf(NAME)

    const val DOOR_SIDE = "gaDoorSide"    // 0 north (top), 1 south (bottom), 2 west (left), 3 east (right)
    const val DOOR = "gaDoor"             // 0 one 16' door, 1 two 9' doors (Dan, for a 24' garage)
    const val ROOF_STYLE = "gaRoof"       // 0 continue the house roof, 1 gable turned 90 deg, 2 hip
    /** Not stored: setting it centres an attached garage on the house wall (even setbacks front and back). */
    const val CENTER = "gaCenter"
    /** Not stored: "wall,fromCornerIn,gapIn" puts the garage there (wall 0 N, 1 S, 2 W, 3 E; corner: west on N/S, north on E/W). */
    const val PLACE = "gaPlace"

    /** Where the garage now sits against the house: (wall, inches from the corner, gap inches), or null with no house. */
    fun placement(g: DrawingElement.Symbol, fp: Rect?, inch: Float): Triple<Int, Float, Float>? {
        fp ?: return null
        val r = g.boundingRect()
        val (side, _) = Porches.sideOf(r, fp)
        return when (side) {
            Porches.Side.TOP -> Triple(0, (r.left - fp.left) / inch, (fp.top - r.bottom) / inch)
            Porches.Side.BOTTOM -> Triple(1, (r.left - fp.left) / inch, (r.top - fp.bottom) / inch)
            Porches.Side.LEFT -> Triple(2, (r.top - fp.top) / inch, (fp.left - r.right) / inch)
            Porches.Side.RIGHT -> Triple(3, (r.top - fp.top) / inch, (r.left - fp.right) / inch)
        }
    }

    /** The garage's new centre for a placement (its size kept). */
    fun centerFor(g: DrawingElement.Symbol, fp: Rect, inch: Float, wall: Int, fromCornerIn: Float, gapIn: Float): Offset {
        val w = g.size.width; val h = g.size.height
        val off = fromCornerIn * inch; val gap = gapIn * inch
        return when (wall) {
            0 -> Offset(fp.left + off + w / 2f, fp.top - gap - h / 2f)
            1 -> Offset(fp.left + off + w / 2f, fp.bottom + gap + h / 2f)
            2 -> Offset(fp.left - gap - w / 2f, fp.top + off + h / 2f)
            else -> Offset(fp.right + gap + w / 2f, fp.top + off + h / 2f)
        }
    }

    /** Inches from the corner that centre it on its wall. */
    fun centeredOffsetIn(g: DrawingElement.Symbol, fp: Rect, inch: Float, wall: Int): Float =
        ((if (wall <= 1) fp.width - g.size.width else fp.height - g.size.height) / 2f) / inch
    val keys = setOf(DOOR_SIDE, DOOR, ROOF_STYLE)
    val doorSides = listOf("North", "South", "West", "East")
    val doorChoices = listOf("One 16' door", "Two 9' doors")
    val roofStyles = listOf("Continue the house roof", "Gable turned 90°", "Hip")

    const val WALL_IN = 5.5f              // 2x6 walls
    const val DOOR_HEIGHT_IN = 84f        // 7'
    const val FROST_IN = 42f
    const val SLAB_IN = 4f
    const val LEAD_WALL_IN = 48f          // the lead wall's run, within Dan's 3' to 5'
    const val COURSE_IN = 8f              // the 6" block course the walls sit on (Dan)
    const val TALL_DOOR_IN = 96f          // 8' doors whenever they fit (Dan), else 7'
    const val ROOF_DEAD_PSF = 15f
    const val WALL_PLF = 10f

    fun isGarage(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY
    fun defaults(): Map<String, Float> = mapOf(DOOR_SIDE to 1f, DOOR to 0f)
    fun prop(s: DrawingElement.Symbol, k: String): Int = (s.properties[k] ?: defaults()[k] ?: 0f).toInt()

    /** Against the house (within a few inches of an exterior wall's outside face)? Which side, and the face's coordinate. */
    fun attachment(g: DrawingElement.Symbol, fp: Rect?, inch: Float): Pair<Porches.Side, Float>? {
        fp ?: return null
        val r = g.boundingRect()
        val (side, hf) = Porches.sideOf(r, fp)
        val gap = when (side) {
            Porches.Side.TOP -> fp.top - r.bottom; Porches.Side.BOTTOM -> r.top - fp.bottom
            Porches.Side.LEFT -> fp.left - r.right; Porches.Side.RIGHT -> r.left - fp.right
        }
        val overlaps = if (side == Porches.Side.TOP || side == Porches.Side.BOTTOM) r.right > fp.left && r.left < fp.right else r.bottom > fp.top && r.top < fp.bottom
        return if (overlaps && kotlin.math.abs(gap) <= 6f * inch) side to hf else null
    }

    /** The garage wall that's the house's (0 north, 1 south, 2 west, 3 east), or null when detached. */
    fun sharedWall(g: DrawingElement.Symbol, state: CanvasState): Int? {
        val inch = state.inchesToPx(1f).takeIf { it > 0f } ?: return null
        val att = attachment(g, runCatching { RoofPlan.footprint(state) }.getOrNull(), inch) ?: return null
        return when (att.first) { Porches.Side.TOP -> 1; Porches.Side.BOTTOM -> 0; Porches.Side.LEFT -> 3; Porches.Side.RIGHT -> 2 }
    }

    /** The overhead doors' openings along the door wall, inches from its middle: (start, end) pairs. */
    fun doors(g: DrawingElement.Symbol): List<Pair<Float, Float>> = when (prop(g, DOOR)) {
        1 -> listOf(-9f * 12f - 12f to -12f, 12f to 9f * 12f + 12f)
        else -> listOf(-96f to 96f)
    }
    fun doorWidthIn(g: DrawingElement.Symbol): Float = if (prop(g, DOOR) == 1) 108f else 192f

    // ---- Heights (Dan): slab at grade, walls on an 8" course of 6" block, plate lined up with the house's ----
    fun slabTopIn(s: BuildingSettings): Float = ExteriorElevation.gradeIn(s)
    fun sillIn(s: BuildingSettings): Float = slabTopIn(s) + COURSE_IN
    fun plateIn(s: BuildingSettings): Float = s.datumIn + s.plateHeightIn

    /** The overhead door header: an LVL sized for its span and load (Dan: always). */
    data class Header(val plies: Int, val depthIn: Float, val spanFt: Float, val wPlf: Float) {
        val name get() = "$plies-PLY 1-3/4\" x ${formatInches(depthIn)}\" LVL"
    }

    /**
     * Sizes the header the way the Structural Engineering Suite's timber engine does a simple span:
     * LVL 2.0E (Fb 2,600, Fv 285, E 2,000,000 psi), CD 1.15 (snow), bending, shear, live deflection
     * L/360 and total L/240; the lightest 2- or 3-ply that passes. Span: the door plus 3" bearing.
     * Load: the roof's snow and 15 psf dead over half the garage plus the overhang when the door wall
     * carries the rafters (an eave wall), 2' of it when it's a gable end; and the wall, 10 plf.
     */
    fun header(g: DrawingElement.Symbol, state: CanvasState): Header {
        val s = state.building
        val inch = state.inchesToPx(1f).takeIf { it > 0f } ?: 1f
        val r = g.boundingRect()
        val doorAlongX = prop(g, DOOR_SIDE) <= 1
        val ridgeAlongX = ridgeAlongX(g, state)
        val acrossIn = (if (doorAlongX) r.height else r.width) / inch
        val ov = s.roof?.overhangIn ?: 12f
        val tribFt = if (doorAlongX == ridgeAlongX) (acrossIn / 2f + ov) / 12f else 2f
        val wDl = ROOF_DEAD_PSF * tribFt + WALL_PLF
        val wLl = s.structure.roofSnowPsf * tribFt
        val spanFt = (doorWidthIn(g) + 3f) / 12f
        val L = spanFt * 12f
        val depths = listOf(9.25f, 9.5f, 11.25f, 11.875f, 14f, 16f, 18f)
        val E = 2_000_000f; val fb = 2600f * 1.15f; val fv = 285f * 1.15f
        val options = (2..3).flatMap { n -> depths.map { d -> n to d } }.sortedBy { it.first * it.second }
        for ((n, d) in options) {
            val b = 1.75f * n
            val sx = b * d * d / 6f; val ix = b * d * d * d / 12f; val area = b * d
            val w = wDl + wLl
            val m = w * spanFt * spanFt / 8f * 12f
            val v = w * spanFt / 2f
            val dLive = 5f * (wLl / 12f) * L * L * L * L / (384f * E * ix)
            val dTot = 5f * (w / 12f) * L * L * L * L / (384f * E * ix)
            if (m / sx <= fb && 1.5f * v / area <= fv && dLive <= L / 360f && dTot <= L / 240f) return Header(n, d, spanFt, w)
        }
        return Header(3, 18f, spanFt, wDl + wLl)
    }

    /** 8' when the door, the header and the double top plate fit under the plate; else 7'. */
    fun doorHeightIn(g: DrawingElement.Symbol, state: CanvasState): Float {
        val s = state.building
        val room = plateIn(s) - 3f - header(g, state).depthIn - slabTopIn(s)
        return if (room >= TALL_DOOR_IN) TALL_DOOR_IN else DOOR_HEIGHT_IN
    }

    /** The garage on the plan: its walls, the overhead door(s), its name and size. */
    fun DrawScope.drawGarage(e: DrawingElement.Symbol, state: CanvasState, ink: Color) {
        val inch = state.inchesToPx(1f).takeIf { it > 0f } ?: return
        val r = e.boundingRect()
        val w = WALL_IN * inch
        val lineW = max(1f, inch * 0.6f)
        val side = prop(e, DOOR_SIDE).coerceIn(0, 3)
        val alongX = side == 0 || side == 1
        val mid = if (alongX) r.center.x else r.center.y
        val gaps = doors(e).map { (a, b) -> mid + a * inch to mid + b * inch }
        fun hRun(y: Float, x1: Float, x2: Float, cut: Boolean) {
            if (!cut) { drawLine(ink, Offset(x1, y), Offset(x2, y), strokeWidth = lineW); return }
            var x = x1
            for ((a, b) in gaps.sortedBy { it.first }) { drawLine(ink, Offset(x, y), Offset(a, y), strokeWidth = lineW); x = b }
            drawLine(ink, Offset(x, y), Offset(x2, y), strokeWidth = lineW)
        }
        fun vRun(x: Float, y1: Float, y2: Float, cut: Boolean) {
            if (!cut) { drawLine(ink, Offset(x, y1), Offset(x, y2), strokeWidth = lineW); return }
            var y = y1
            for ((a, b) in gaps.sortedBy { it.first }) { drawLine(ink, Offset(x, y), Offset(x, a), strokeWidth = lineW); y = b }
            drawLine(ink, Offset(x, y), Offset(x, y2), strokeWidth = lineW)
        }
        // Outside and inside faces of the walls; the door wall broken at the doors. An attached garage
        // shares the house wall (Dan): no wall of its own on that side, the others run to the house.
        val shared = sharedWall(e, state)
        val l0 = if (shared == 2) 0f else w; val r0 = if (shared == 3) 0f else w
        val t0 = if (shared == 0) 0f else w; val b0 = if (shared == 1) 0f else w
        if (shared != 0) { hRun(r.top, r.left, r.right, side == 0); hRun(r.top + w, r.left + l0, r.right - r0, side == 0) }
        if (shared != 1) { hRun(r.bottom, r.left, r.right, side == 1); hRun(r.bottom - w, r.left + l0, r.right - r0, side == 1) }
        if (shared != 2) { vRun(r.left, r.top, r.bottom, side == 2); vRun(r.left + w, r.top + t0, r.bottom - b0, side == 2) }
        if (shared != 3) { vRun(r.right, r.top, r.bottom, side == 3); vRun(r.right - w, r.top + t0, r.bottom - b0, side == 3) }
        // The doors: jambs across the wall, the door itself dashed inside (it's overhead).
        val dash = PathEffect.dashPathEffect(floatArrayOf(inch * 6f, inch * 4f))
        for ((a, b) in gaps) when (side) {
            0 -> { drawLine(ink, Offset(a, r.top), Offset(a, r.top + w), lineW); drawLine(ink, Offset(b, r.top), Offset(b, r.top + w), lineW)
                drawLine(ink, Offset(a, r.top + w + 2f * inch), Offset(b, r.top + w + 2f * inch), lineW, pathEffect = dash) }
            1 -> { drawLine(ink, Offset(a, r.bottom), Offset(a, r.bottom - w), lineW); drawLine(ink, Offset(b, r.bottom), Offset(b, r.bottom - w), lineW)
                drawLine(ink, Offset(a, r.bottom - w - 2f * inch), Offset(b, r.bottom - w - 2f * inch), lineW, pathEffect = dash) }
            2 -> { drawLine(ink, Offset(r.left, a), Offset(r.left + w, a), lineW); drawLine(ink, Offset(r.left, b), Offset(r.left + w, b), lineW)
                drawLine(ink, Offset(r.left + w + 2f * inch, a), Offset(r.left + w + 2f * inch, b), lineW, pathEffect = dash) }
            else -> { drawLine(ink, Offset(r.right, a), Offset(r.right - w, a), lineW); drawLine(ink, Offset(r.right, b), Offset(r.right - w, b), lineW)
                drawLine(ink, Offset(r.right - w - 2f * inch, a), Offset(r.right - w - 2f * inch, b), lineW, pathEffect = dash) }
        }
        val ts = inch * 9f
        val wFt = Building.elevationText(r.width / inch).removeSuffix("-0\"").let { if (it.endsWith("'")) it else it }
        val hFt = Building.elevationText(r.height / inch).removeSuffix("-0\"")
        val attached = attachment(e, runCatching { RoofPlan.footprint(state) }.getOrNull(), inch) != null
        val hdr = header(e, state)
        val dh = doorHeightIn(e, state)
        val lines = listOf("${if (attached) "ATTACHED" else "DETACHED"} GARAGE", "$wFt x $hFt", "4\" CONC. SLAB AT GRADE, SLOPE 2\" TO DOOR",
            "${doorChoices.getOrElse(prop(e, DOOR)) { "" }.uppercase()}, ${if (dh >= TALL_DOOR_IN) "8'" else "7'"} HIGH", "HDR: ${hdr.name}") +
            // The code's separation from the house (IRC R302.5.1, Table R302.6).
            (if (attached) listOf("1/2\" GYP. MIN. ON GARAGE SIDE OF WALLS/CEILING COMMON TO HOUSE (R302.6)",
                "DOOR TO HOUSE: 1-3/8\" SOLID WOOD/STEEL OR 20-MIN. RATED (R302.5.1)") else emptyList())
        lines.forEachIndexed { i, t ->
            val size = if (i == 0) ts else if (i < 5) ts * 0.8f else ts * 0.6f
            val tw = size * 0.6f * t.length
            if (tw < r.width * 0.95f) drawPageText(t, Offset(r.center.x - tw / 2f, r.center.y - ts * 3f + i * ts * 1.3f), size, ink, bold = i == 0, maxWidth = r.width)
        }
    }

    /**
     * What holds it up: frost walls and a continuous footing round the open sides (all four when
     * detached), the side footings butting the house footing; where the house has a basement, those
     * ends step down to it (lead walls, marked on the footing). The slab goes on the framing layer.
     */
    fun foundation(g: DrawingElement.Symbol, fp: Rect?, s: BuildingSettings, inch: Float): List<Porches.Part> {
        val r = g.boundingRect()
        val att = attachment(g, fp, inch)
        val wall = FoundationWall.BLOCK_12
        val t = wall.thicknessIn * inch
        val f = s.footingWidthIn * inch
        val p = (f - t) / 2f
        val out = mutableListOf<Porches.Part>()
        val thick = mapOf(Structure.THICK to s.footingThicknessIn)
        fun part(key: String, name: String, cat: String, rr: Rect, props: Map<String, Float> = emptyMap()) =
            out.add(Porches.Part(key, name, cat, rr.center, rr.size, props))
        if (att == null) {
            // Detached: four walls; top and bottom footings own the corners, the sides butt them.
            part("w-n", wall.symbol, PlanSymbols.WALLS, Rect(r.left, r.top, r.right, r.top + t))
            part("w-s", wall.symbol, PlanSymbols.WALLS, Rect(r.left, r.bottom - t, r.right, r.bottom))
            part("w-w", wall.symbol, PlanSymbols.WALLS, Rect(r.left, r.top + t, r.left + t, r.bottom - t))
            part("w-e", wall.symbol, PlanSymbols.WALLS, Rect(r.right - t, r.top + t, r.right, r.bottom - t))
            val own = (Structure.JOIN_OWN * 4).toFloat(); val butt = (Structure.JOIN_BUTT * 4).toFloat()
            part("f-n", Structure.FOOTING, Structure.FOUNDATION, Rect(r.left - p, r.top - p, r.right + p, r.top - p + f), thick + mapOf(Structure.JOIN to own, Structure.INNER to 1f))
            part("f-s", Structure.FOOTING, Structure.FOUNDATION, Rect(r.left - p, r.bottom + p - f, r.right + p, r.bottom + p), thick + mapOf(Structure.JOIN to own, Structure.INNER to -1f))
            part("f-w", Structure.FOOTING, Structure.FOUNDATION, Rect(r.left - p, r.top - p + f, r.left - p + f, r.bottom + p - f), thick + mapOf(Structure.JOIN to butt, Structure.INNER to 1f))
            part("f-e", Structure.FOOTING, Structure.FOUNDATION, Rect(r.right + p - f, r.top - p + f, r.right + p, r.bottom + p - f), thick + mapOf(Structure.JOIN to butt, Structure.INNER to -1f))
            return out
        }
        // Attached: three sides, as a porch's foundation is laid out, at frost depth.
        val (side, hf) = att
        fun world(u1: Float, u2: Float, v1: Float, v2: Float): Rect = when (side) {
            Porches.Side.TOP -> Rect(u1, hf - v2, u2, hf - v1)
            Porches.Side.BOTTOM -> Rect(u1, hf + v1, u2, hf + v2)
            Porches.Side.LEFT -> Rect(hf - v2, u1, hf - v1, u2)
            Porches.Side.RIGHT -> Rect(hf + v1, u1, hf + v2, u2)
        }
        val alongX = side == Porches.Side.TOP || side == Porches.Side.BOTTOM
        val pu1 = if (alongX) r.left else r.top
        val pu2 = if (alongX) r.right else r.bottom
        val depth = when (side) { Porches.Side.TOP -> hf - r.top; Porches.Side.BOTTOM -> r.bottom - hf; Porches.Side.LEFT -> hf - r.left; Porches.Side.RIGHT -> r.right - hf }
        val towardHouse = when (side) { Porches.Side.TOP, Porches.Side.LEFT -> 1f; else -> -1f }
        part("w-out", wall.symbol, PlanSymbols.WALLS, world(pu1, pu2, depth - t, depth))
        part("w-a", wall.symbol, PlanSymbols.WALLS, world(pu1, pu1 + t, 0f, depth - t))
        part("w-b", wall.symbol, PlanSymbols.WALLS, world(pu2 - t, pu2, 0f, depth - t))
        part("f-out", Structure.FOOTING, Structure.FOUNDATION, world(pu1 - p, pu2 + p, depth + p - f, depth + p),
            thick + mapOf(Structure.JOIN to (Structure.JOIN_OWN * 4).toFloat(), Structure.INNER to towardHouse))
        val houseP = Building.footingProjections(s).first * inch
        val v2 = depth + p - f
        if (v2 > houseP) {
            // Which end of the side pieces is the house's, in their own frame (start = smaller x or y).
            val houseEnd = when (side) { Porches.Side.TOP, Porches.Side.LEFT -> 2f; else -> 1f }
            val joins = (Structure.JOIN_BUTT * 4).toFloat()
            if (s.foundation == FoundationType.BASEMENT && v2 - houseP > LEAD_WALL_IN * inch) {
                // Lead walls (Dan): the first 4' out from the house footing is its own footing, stepping down to the
                // basement footing; the rest of the side runs at frost depth.
                val vl = houseP + LEAD_WALL_IN * inch
                val lead = mapOf(Structure.STEP to houseEnd)
                part("lw-a", Structure.FOOTING, Structure.FOUNDATION, world(pu1 - p, pu1 - p + f, houseP, vl), thick + lead + mapOf(Structure.JOIN to joins, Structure.INNER to 1f))
                part("lw-b", Structure.FOOTING, Structure.FOUNDATION, world(pu2 + p - f, pu2 + p, houseP, vl), thick + lead + mapOf(Structure.JOIN to joins, Structure.INNER to -1f))
                part("f-a", Structure.FOOTING, Structure.FOUNDATION, world(pu1 - p, pu1 - p + f, vl, v2), thick + mapOf(Structure.JOIN to joins, Structure.INNER to 1f))
                part("f-b", Structure.FOOTING, Structure.FOUNDATION, world(pu2 + p - f, pu2 + p, vl, v2), thick + mapOf(Structure.JOIN to joins, Structure.INNER to -1f))
            } else {
                part("f-a", Structure.FOOTING, Structure.FOUNDATION, world(pu1 - p, pu1 - p + f, houseP, v2), thick + mapOf(Structure.JOIN to joins, Structure.INNER to 1f))
                part("f-b", Structure.FOOTING, Structure.FOUNDATION, world(pu2 + p - f, pu2 + p, houseP, v2), thick + mapOf(Structure.JOIN to joins, Structure.INNER to -1f))
            }
        }
        return out
    }

    /** The slab on the framing layer, inside the walls. */
    fun slab(g: DrawingElement.Symbol, inch: Float, shared: Int? = null): Porches.Part {
        val r = g.boundingRect()
        val w = WALL_IN * inch
        val rr = Rect(r.left + (if (shared == 2) 0f else w), r.top + (if (shared == 0) 0f else w),
            r.right - (if (shared == 3) 0f else w), r.bottom - (if (shared == 1) 0f else w))
        return Porches.Part("slab", Structure.SLAB, Structure.FRAMING, rr.center, rr.size, mapOf(Structure.THICK to SLAB_IN))
    }

    // ---- The garage roof (round 2): worked out in 3D, so the plan and every elevation agree ----

    /** A roof line in the plan (world px) with its heights at each end (inches). */
    data class Seg3(val a: Offset, val za: Float, val b: Offset, val zb: Float, val kind: RoofPlan.Kind)

    /** Does the garage ridge run along x (across the page)? */
    fun ridgeAlongX(g: DrawingElement.Symbol, state: CanvasState): Boolean {
        val inch = state.inchesToPx(1f).takeIf { it > 0f } ?: 1f
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull()
        val r = g.boundingRect()
        val att = attachment(g, fp, inch)
        val houseAcross = state.building.roof?.let { hr -> runCatching { RoofPlan.geometry(state, hr) }.getOrNull()?.ridgeAcross } ?: (r.width >= r.height)
        val style = prop(g, ROOF_STYLE).coerceIn(0, 2)
        if (att == null) return r.width >= r.height
        return when (style) { 1 -> !houseAcross; else -> houseAcross }
    }

    fun roof3d(g: DrawingElement.Symbol, state: CanvasState): List<Seg3> = roof3dWith(g, state, ridgeAlongX(g, state), null)

    /**
     * The roof over a box against the house (a garage, or a wing: Wings), its ridge along x when [alongX];
     * [hipPick] says whether the far end is a hip (null: the garage's own roof style decides).
     */
    fun roof3dWith(g: DrawingElement.Symbol, state: CanvasState, alongX: Boolean, hipPick: Boolean?): List<Seg3> {
        val s = state.building
        val k = state.inchesToPx(1f).takeIf { it > 0f } ?: return emptyList()
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull()
        val r = g.boundingRect()
        val att = attachment(g, fp, k)
        val hr = s.roof
        val pitch = (hr?.pitch ?: 6f) / 12f
        val ov = (hr?.overhangIn ?: 12f) * k
        val rake = (hr?.rakeIn ?: 12f) * k
        val heel = hr?.heelIn ?: 4f
        val atWall = plateIn(s) + heel
        val eaveZ = atWall - ov / k * pitch
        val style = prop(g, ROOF_STYLE).coerceIn(0, 2)
        val out = mutableListOf<Seg3>()
        fun seg(ax: Float, ay: Float, za: Float, bx: Float, by: Float, zb: Float, kind: RoofPlan.Kind) { out += Seg3(Offset(ax, ay), za, Offset(bx, by), zb, kind) }
        // Work in (a along the ridge, c across it); a1..a2 the garage along the ridge, c1..c2 across.
        fun pt(a: Float, c: Float) = if (alongX) Offset(a, c) else Offset(c, a)
        fun s3(a1: Float, c1: Float, z1: Float, a2: Float, c2: Float, z2: Float, kind: RoofPlan.Kind) {
            val p = pt(a1, c1); val q = pt(a2, c2); seg(p.x, p.y, z1, q.x, q.y, z2, kind)
        }
        val a1 = if (alongX) r.left else r.top
        val a2 = if (alongX) r.right else r.bottom
        val c1 = if (alongX) r.top else r.left
        val c2 = if (alongX) r.bottom else r.right
        val cm = (c1 + c2) / 2f
        val half = (c2 - c1) / 2f
        val ridgeZ = atWall + half / k * pitch
        // Which end of the ridge is against the house (an end it dies into), if any.
        val houseAtA1 = att != null && ((alongX && att.first == Porches.Side.RIGHT) || (!alongX && att.first == Porches.Side.BOTTOM))
        val houseAtA2 = att != null && ((alongX && att.first == Porches.Side.LEFT) || (!alongX && att.first == Porches.Side.TOP))
        val ridgeAgainstHouse = houseAtA1 || houseAtA2
        // The house's eave faces the garage (its ridge runs along the wall the garage is on).
        val houseAcross = hr?.let { runCatching { RoofPlan.geometry(state, it) }.getOrNull()?.ridgeAcross }
        // A house roof plane faces the garage: the house's eave side, or a hip end (Dan's house: a hip on the west).
        val hipEndFaces = att != null && hr != null && houseAcross != null && when (att.first) {
            Porches.Side.LEFT -> houseAcross && hr.endA == RoofEnd.HIP
            Porches.Side.RIGHT -> houseAcross && hr.endB == RoofEnd.HIP
            Porches.Side.TOP -> !houseAcross && hr.endA == RoofEnd.HIP
            Porches.Side.BOTTOM -> !houseAcross && hr.endB == RoofEnd.HIP
        }
        val houseEaveFaces = att != null && houseAcross != null && s.upperFloors.isEmpty() &&
            (houseAcross == (att.first == Porches.Side.TOP || att.first == Porches.Side.BOTTOM) || hipEndFaces)
        // Hip: picked, or "continue the house roof" on a house whose end on that side is a hip (Dan: match the house).
        val houseEndHip = hr != null && att != null && when (att.first) {
            Porches.Side.LEFT, Porches.Side.TOP -> hr.endA == RoofEnd.HIP
            Porches.Side.RIGHT, Porches.Side.BOTTOM -> hr.endB == RoofEnd.HIP
        }
        val hip = hipPick ?: (style == 2 || (style == 0 && houseEndHip))
        // Is the side at [c] (across the ridge) in line with a house end that is a hip?
        fun flushHipAt(c: Float): Boolean {
            if (fp == null || hr == null || houseAcross == null || att == null) return false
            val ridgeIntoHouse = (alongX && (att.first == Porches.Side.LEFT || att.first == Porches.Side.RIGHT)) ||
                (!alongX && (att.first == Porches.Side.TOP || att.first == Porches.Side.BOTTOM))
            if (!ridgeIntoHouse) return false
            // Across this ridge is the house's length when the house ridge runs the same way as the wall the box is on.
            return if (!alongX) houseAcross && ((kotlin.math.abs(c - fp.left) < k && hr.endA == RoofEnd.HIP) || (kotlin.math.abs(c - fp.right) < k && hr.endB == RoofEnd.HIP))
            else !houseAcross && ((kotlin.math.abs(c - fp.top) < k && hr.endA == RoofEnd.HIP) || (kotlin.math.abs(c - fp.bottom) < k && hr.endB == RoofEnd.HIP))
        }
        val endOv = if (hip) ov else rake
        val e1 = if (houseAtA1) a1 else a1 - endOv
        val e2 = if (houseAtA2) a2 else a2 + endOv
        // Ridge: a hip end stops it half the width in.
        val r1 = if (hip && !houseAtA1) a1 - ov + (half + ov) else e1
        val r2 = if (hip && !houseAtA2) a2 + ov - (half + ov) else e2
        s3(r1, cm, ridgeZ, maxOf(r1, r2), cm, ridgeZ, RoofPlan.Kind.RIDGE)
        // Eaves down both sides, along the ridge: into a house roof they stop where they meet it.
        val houseRoof = if (houseEaveFaces && hr != null) Triple(atWall, hr.pitch / 12f, hr.overhangIn) else null
        val veIn = houseRoof?.let { Porches.meetV(it, eaveZ, 0f, 0f) * k } ?: 0f
        val ee1 = if (houseAtA1 && houseRoof != null) e1 + veIn else e1
        val ee2 = if (houseAtA2 && houseRoof != null) e2 - veIn else e2
        s3(ee1, c1 - ov, eaveZ, ee2, c1 - ov, eaveZ, RoofPlan.Kind.EAVE)
        s3(ee1, c2 + ov, eaveZ, ee2, c2 + ov, eaveZ, RoofPlan.Kind.EAVE)
        // The ends: against the house the roof dies into the wall (its outline on the wall); else a gable rake or a hip.
        for ((end, atHouse, rEnd) in listOf(Triple(e1, houseAtA1, r1), Triple(e2, houseAtA2, r2))) {
            if (atHouse && houseEaveFaces && hr != null) {
                // Into the house's eave side: the ridge runs on until it meets the house roof, valleys from the eaves.
                val into = if (end == e2) 1f else -1f
                val house = Triple(atWall, hr.pitch / 12f, hr.overhangIn)
                val vr = Porches.meetV(house, ridgeZ, 0f, 0f) * k
                val ve = Porches.meetV(house, eaveZ, 0f, 0f) * k
                val ridgeIn = end - into * vr
                val eaveIn = end - into * ve
                s3(end, cm, ridgeZ, ridgeIn, cm, ridgeZ, RoofPlan.Kind.RIDGE)
                // A side lined up with a house hip end (Dan, Nov 23): that slope and the hip end are one plane, so no
                // valley there; the house's hip stops at this ridge and the hip end's rafters run up to it.
                if (!flushHipAt(c1)) s3(eaveIn, c1 - ov, eaveZ, ridgeIn, cm, ridgeZ, RoofPlan.Kind.HIP)
                if (!flushHipAt(c2)) s3(eaveIn, c2 + ov, eaveZ, ridgeIn, cm, ridgeZ, RoofPlan.Kind.HIP)
            } else if (atHouse) {
                s3(end, c1 - ov, eaveZ, end, cm, ridgeZ, RoofPlan.Kind.EAVE); s3(end, cm, ridgeZ, end, c2 + ov, eaveZ, RoofPlan.Kind.EAVE)
            } else if (hip) {
                s3(end, c1 - ov, eaveZ, end, c2 + ov, eaveZ, RoofPlan.Kind.EAVE)
                s3(end, c1 - ov, eaveZ, rEnd, cm, ridgeZ, RoofPlan.Kind.HIP); s3(end, c2 + ov, eaveZ, rEnd, cm, ridgeZ, RoofPlan.Kind.HIP)
            } else {
                s3(end, c1 - ov, eaveZ, end, cm, ridgeZ, RoofPlan.Kind.EAVE); s3(end, cm, ridgeZ, end, c2 + ov, eaveZ, RoofPlan.Kind.EAVE)
            }
        }
        // A side against the house (the ridge runs along it): the near slope dies into the wall at the wall's line.
        if (att != null && !ridgeAgainstHouse) {
            val nearC = if ((alongX && att.first == Porches.Side.BOTTOM) || (!alongX && att.first == Porches.Side.RIGHT)) c1 else c2
            s3(e1, nearC, atWall, e2, nearC, atWall, RoofPlan.Kind.WALL)
        }
        // Trusses square to the ridge at the house's spacing. In a hip end they stop at the hips (jacks from each eave),
        // and the end gets its own jacks square to the end wall, out from the king common at the middle (as the house's).
        val oc = (hr?.ocIn ?: 24f).coerceAtLeast(4f) * k
        val hipA1 = hip && !houseAtA1
        val hipA2 = hip && !houseAtA2
        var a = a1
        while (a <= a2 + 0.5f) {
            val inA = if (hipA1 && a < r1) a - e1 else Float.MAX_VALUE
            val inB = if (hipA2 && a > r2) e2 - a else Float.MAX_VALUE
            val reach = minOf(inA, inB)
            if (reach == Float.MAX_VALUE) s3(a, c1 - ov, eaveZ, a, c2 + ov, eaveZ, RoofPlan.Kind.MEMBER)
            else if (reach > 0f) {
                s3(a, c1 - ov, eaveZ, a, c1 - ov + reach, eaveZ, RoofPlan.Kind.MEMBER)
                s3(a, c2 + ov, eaveZ, a, c2 + ov - reach, eaveZ, RoofPlan.Kind.MEMBER)
            }
            a += oc
        }
        for ((isHip, end, rEnd) in listOf(Triple(hipA1, e1, r1), Triple(hipA2, e2, r2))) {
            if (!isHip) continue
            val dir = if (end == e1) 1f else -1f
            var i = 0
            while (true) {
                val offs = if (i == 0) listOf(0f) else listOf(-i * oc, i * oc)
                if (i > 0 && cm - i * oc < c1 - 0.01f) break
                for (dc in offs) {
                    val c = cm + dc
                    val run = minOf(c - (c1 - ov), (c2 + ov) - c, kotlin.math.abs(rEnd - end))
                    if (run > 0f) s3(end, c, eaveZ, end + dir * run, c, eaveZ, RoofPlan.Kind.MEMBER)
                }
                i++
            }
        }
        return out
    }

    /** The overhead door wall as the elevations name it. */
    fun doorView(g: DrawingElement.Symbol): ViewerSide = when (prop(g, DOOR_SIDE).coerceIn(0, 3)) {
        0 -> ViewerSide.ABOVE; 1 -> ViewerSide.BELOW; 2 -> ViewerSide.LEFT; else -> ViewerSide.RIGHT
    }

    /**
     * The garage in an exterior elevation (x along the face, e the elevation), from its plan and the
     * roof's 3D lines: the walls on the 8" block course from the slab (at grade) to the plate, lap
     * siding, the overhead doors with their panels and the header over them on the door wall, the
     * frost wall and footing dashed below grade, and the roof. An attached garage on the far side of
     * the house isn't drawn.
     */
    fun elevation(g: DrawingElement.Symbol, state: CanvasState, view: ViewerSide, fp: Rect, k: Float): Pair<List<Porches.ESeg>, List<Porches.ELabel>> {
        val segs = mutableListOf<Porches.ESeg>(); val labels = mutableListOf<Porches.ELabel>()
        val pen = ExteriorElevation.Pen.MEDIUM
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, p: ExteriorElevation.Pen = pen) { segs += Porches.ESeg(x1, e1, x2, e2, p) }
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, p: ExteriorElevation.Pen = pen) { line(x1, e1, x2, e1, p); line(x2, e1, x2, e2, p); line(x2, e2, x1, e2, p); line(x1, e2, x1, e1, p) }
        val att = attachment(g, fp, k)
        if (att != null) {
            val gs = Porches.viewerSide(att.first)
            val opposite = (gs == ViewerSide.ABOVE && view == ViewerSide.BELOW) || (gs == ViewerSide.BELOW && view == ViewerSide.ABOVE) ||
                (gs == ViewerSide.LEFT && view == ViewerSide.RIGHT) || (gs == ViewerSide.RIGHT && view == ViewerSide.LEFT)
            if (opposite) return segs to labels
        }
        val s = state.building
        val r = g.boundingRect()
        val xs = listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).map { Elevation.alongOf(fp, view, it) / k }
        val xa = xs.min(); val xb = xs.max()
        val slab = slabTopIn(s); val sill = sillIn(s); val plate = plateIn(s)
        // Doors on this face?
        val onDoorFace = doorView(g) == view
        val dh = doorHeightIn(g, state)
        val hdr = header(g, state)
        val doorXs: List<Pair<Float, Float>> = if (!onDoorFace) emptyList() else {
            val side = prop(g, DOOR_SIDE).coerceIn(0, 3)
            doors(g).map { (a, b) ->
                val pa: Offset; val pb: Offset
                when (side) {
                    0 -> { pa = Offset(r.center.x + a * k, r.top); pb = Offset(r.center.x + b * k, r.top) }
                    1 -> { pa = Offset(r.center.x + a * k, r.bottom); pb = Offset(r.center.x + b * k, r.bottom) }
                    2 -> { pa = Offset(r.left, r.center.y + a * k); pb = Offset(r.left, r.center.y + b * k) }
                    else -> { pa = Offset(r.right, r.center.y + a * k); pb = Offset(r.right, r.center.y + b * k) }
                }
                val x1 = Elevation.alongOf(fp, view, pa) / k; val x2 = Elevation.alongOf(fp, view, pb) / k
                minOf(x1, x2) to maxOf(x1, x2)
            }
        }
        // Walls up to the soffit (they don't show above the fascia), the block course, siding broken at the doors.
        val hr0 = s.roof
        val soffit = plate + (hr0?.heelIn ?: 4f) - (hr0?.overhangIn ?: 12f) * (hr0?.pitch ?: 6f) / 12f - 6f
        val wallTop = minOf(plate, soffit)
        rect(xa, slab, xb, wallTop, ExteriorElevation.Pen.HEAVY)
        fun across(e: Float, p: ExteriorElevation.Pen) {
            var x = xa
            for ((a, b) in doorXs.filter { e < slab + dh + (if (p == ExteriorElevation.Pen.THIN) hdr.depthIn else 0f) }.sortedBy { it.first }) { if (a > x) line(x, e, a, e, p); x = b }
            if (xb > x) line(x, e, xb, e, p)
        }
        across(sill, ExteriorElevation.Pen.MEDIUM)
        // The house's exterior finish above the block course (Dan, Nov 23: match the house), broken at the doors
        // and their headers; plain lap siding when the job has no finish set.
        val fin = s.exterior ?: ExteriorFinish(main = WallFinish.LAP, mainIn = 6f, cornerTrimIn = 0f)
        val doorHoles = doorXs.map { (a, b) -> Rect(a - 1.5f, slab, b + 1.5f, slab + dh + hdr.depthIn) }
        ExteriorElevation.finishWall(fin, xa, xb, sill, wallTop, doorHoles, null, ::line)
        // Doors with three panel lines, the header over each.
        for ((a, b) in doorXs) {
            rect(a, slab, b, slab + dh, ExteriorElevation.Pen.MEDIUM)
            for (i in 1..3) line(a, slab + dh * i / 4f, b, slab + dh * i / 4f, ExteriorElevation.Pen.THIN)
            rect(a - 1.5f, slab + dh, b + 1.5f, slab + dh + hdr.depthIn, ExteriorElevation.Pen.THIN)
            labels += Porches.ELabel("${formatFeetInches((b - a) / 12f)} x ${formatFeetInches(dh / 12f)} OVERHEAD DOOR", (a + b) / 2f, slab + dh / 2f)
        }
        // Labels inside the wall, clear of the fascia: the header just under the door head, the name above the door label.
        if (doorXs.isNotEmpty()) labels += Porches.ELabel("HEADER: ${hdr.name}", (xa + xb) / 2f, slab + dh - 6f)
        labels += Porches.ELabel(if (att != null) "ATTACHED GARAGE" else "GARAGE", (xa + xb) / 2f, if (doorXs.isNotEmpty()) slab + dh * 0.72f else minOf(plate, wallTop) - 14f)
        // Frost wall and footing below grade, hidden.
        val ftgTop = slab - FROST_IN + s.footingThicknessIn
        line(xa, slab, xa, ftgTop, ExteriorElevation.Pen.HIDDEN); line(xb, slab, xb, ftgTop, ExteriorElevation.Pen.HIDDEN)
        val p = (s.footingWidthIn - FoundationWall.BLOCK_12.thicknessIn) / 2f
        // The footing at frost depth; seen along an attached garage's side over a basement, the lead wall steps down.
        val alongSide = att != null && (att.first == Porches.Side.LEFT || att.first == Porches.Side.RIGHT) == (view == ViewerSide.ABOVE || view == ViewerSide.BELOW)
        val houseX = if (alongSide && s.foundation == FoundationType.BASEMENT) {
            val hx = listOf(fp.topLeft, fp.bottomRight).map { Elevation.alongOf(fp, view, it) / k }
            if (xa in (hx.min() - 0.5f)..(hx.max() + 0.5f)) xa else xb
        } else null
        footingOnElevation(xa, xb, p, slab, s, houseX, ::line, labels)
        line(xa - 24f, slab, xb + 24f, slab, ExteriorElevation.Pen.HEAVY)
        roofOnElevation(roof3d(g, state), ridgeAlongX(g, state), s, view, fp, k, ::line)
        return segs to labels
    }

    /**
     * A frost footing in an elevation, dashed: from [xa] to [xb] past its ends by [p], bottom [FROST_IN] under
     * [groundE]. With [houseX] (the end at a basement house, seen along the side) its last 4' is the lead wall,
     * stepping down in three steps to the basement footing (Dan: 3' to 5').
     */
    fun footingOnElevation(xa: Float, xb: Float, p: Float, groundE: Float, s: BuildingSettings, houseX: Float?,
                           line: (Float, Float, Float, Float, ExteriorElevation.Pen) -> Unit, labels: MutableList<Porches.ELabel>) {
        val th = s.footingThicknessIn
        val frostBot = groundE - FROST_IN
        val hid = ExteriorElevation.Pen.HIDDEN
        if (houseX == null) {
            line(xa - p, frostBot, xb + p, frostBot, hid); line(xb + p, frostBot, xb + p, frostBot + th, hid)
            line(xb + p, frostBot + th, xa - p, frostBot + th, hid); line(xa - p, frostBot + th, xa - p, frostBot, hid)
            return
        }
        val outX = if (houseX == xa) xb else xa
        val dir = if (houseX == xa) 1f else -1f                     // from the house outward
        val bsmtBot = Building.footingBottomIn(s)
        val leadStart = houseX + dir * LEAD_WALL_IN
        val outEnd = outX + dir * p
        line(outEnd, frostBot, leadStart, frostBot, hid)
        line(outEnd, frostBot + th, leadStart, frostBot + th, hid)
        line(outEnd, frostBot, outEnd, frostBot + th, hid)
        val steps = 3
        var prevBot = frostBot
        for (i in 0 until steps) {
            val x1 = leadStart - dir * LEAD_WALL_IN * i / steps
            val x2 = leadStart - dir * LEAD_WALL_IN * (i + 1) / steps
            val bot = frostBot + (bsmtBot - frostBot) * (i + 1) / steps
            line(x1, prevBot + th, x1, bot + th, hid)
            line(x1, prevBot, x1, bot, hid)
            line(x1, bot + th, x2, bot + th, hid)
            line(x1, bot, x2, bot, hid)
            prevBot = bot
        }
        labels += Porches.ELabel("LEAD WALL: FTG STEPS DOWN TO BSMT FTG", leadStart - dir * LEAD_WALL_IN / 2f, bsmtBot - 10f)
    }

    /** A roof from its 3D lines in an elevation: each eave and rake with its 6" fascia band, shingle courses on a face seen. */
    fun roofOnElevation(roofLines3: List<Seg3>, alongX: Boolean, s: BuildingSettings, view: ViewerSide, fp: Rect, k: Float,
                        line: (Float, Float, Float, Float, ExteriorElevation.Pen) -> Unit, outline3: List<Seg3> = roofLines3,
                        openEnds: List<Float> = emptyList()) {
        fun proj(ls: List<Seg3>) = ls.filter { it.kind != RoofPlan.Kind.MEMBER && it.kind != RoofPlan.Kind.WALL }
            .map { l -> Triple(Elevation.alongOf(fp, view, l.a) / k to l.za, Elevation.alongOf(fp, view, l.b) / k to l.zb, l.kind) }
        val drawn = proj(roofLines3)
        // The shingle courses run to the roof's whole outline, lines hidden from this view included.
        val projected = proj(outline3)
        if (projected.isEmpty()) return
        for ((a, b, kind) in drawn) {
            line(a.first, a.second, b.first, b.second, ExteriorElevation.Pen.HEAVY)
            if (kind == RoofPlan.Kind.EAVE && kotlin.math.abs(a.first - b.first) > 0.5f) {
                line(a.first, a.second - 6f, b.first, b.second - 6f, ExteriorElevation.Pen.MEDIUM)
                // Close the band at its ends, except where it runs on into the house's ([openEnds]).
                if (openEnds.none { kotlin.math.abs(it - a.first) < 0.5f }) line(a.first, a.second, a.first, a.second - 6f, ExteriorElevation.Pen.HEAVY)
                if (openEnds.none { kotlin.math.abs(it - b.first) < 0.5f }) line(b.first, b.second, b.first, b.second - 6f, ExteriorElevation.Pen.HEAVY)
            }
        }
        val hr = s.roof
        val seesFace = alongX == (view == ViewerSide.ABOVE || view == ViewerSide.BELOW)
        if (hr != null && seesFace) {
            val exposure = (hr.coveringIn ?: hr.covering.defaultIn).coerceAtLeast(2f)
            val dz = exposure * hr.pitch / kotlin.math.sqrt(144f + hr.pitch * hr.pitch)
            val zLo = projected.minOf { minOf(it.first.second, it.second.second) }
            val zHi = projected.maxOf { maxOf(it.first.second, it.second.second) }
            var z = zLo + dz
            while (z < zHi - 0.5f && dz > 0.5f) {
                val xs = projected.mapNotNull { (a, b, _) ->
                    val (x1, z1) = a; val (x2, z2) = b
                    if (z1 == z2 || z < minOf(z1, z2) || z > maxOf(z1, z2)) null else x1 + (z - z1) / (z2 - z1) * (x2 - x1)
                }
                if (xs.size >= 2) line(xs.min(), z, xs.max(), z, ExteriorElevation.Pen.THIN)
                z += dz
            }
        }
    }
}
