package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Porches (Dan, Nov 23 2026). Drawn on the first-floor plan against an exterior wall; the
 * foundation layer builds what holds it up.
 *
 * Dan's kinds:
 * - Framed: footings and foundation walls, a framed floor, a skirt that matches the house.
 * - Masonry: a block structure, filled, with a concrete cap and brick or stone veneer. The walls are
 *   12" block (fill pushes on them).
 * - Wood deck: a wood deck on posts, the house side on a ledger.
 * A roof, if there is one, on wood columns, masonry columns or a mix (masonry at the corners here).
 *
 * Footings: any footing that ties into the basement footing is at that level, so a framed or
 * masonry porch's footing runs continuous all the way round at basement footing level, butting the
 * house footing at both ends. Wood deck posts go to frost depth: the footing poured up to grade (a
 * pier), or the post on a "cookie" at the bottom sized for its load.
 *
 * Round 2 (later): porch framing, the cap and veneer, the skirt, porch elevations, the roof.
 */
object Porches {
    const val CATEGORY = "Porch"
    const val NAME = "Porch"
    val library = listOf(NAME)

    const val TYPE = "pcType"         // 0 framed, 1 masonry, 2 wood deck
    const val ROOF = "pcRoof"         // 0 none, 1 roof on columns
    const val COLUMNS = "pcCols"      // 0 wood, 1 masonry, 2 mix (masonry corners, wood between)
    const val PIER = "pcPier"         // wood deck: 0 pier poured to grade, 1 post on a cookie
    const val ROOF_STYLE = "pcRoofStyle" // 0 shed, 1 gable facing out, 2 hip
    const val PITCH = "pcPitch"       // roof pitch, inches per foot; unset = 4/12 shed, the house's pitch otherwise
    val keys = setOf(TYPE, ROOF, COLUMNS, PIER, ROOF_STYLE)
    val roofStyles = listOf("Shed", "Gable", "Hip")

    val types = listOf("Framed (skirt)", "Masonry (cap, veneer)", "Wood deck on posts")
    val columnTypes = listOf("Wood columns", "Masonry columns", "Masonry corners, wood between")
    val pierTypes = listOf("Pier poured to grade", "Post on a cookie")

    /** Posts and columns no more than this far apart, inches. */
    const val MAX_POST_SPACING_IN = 96f
    const val FROST_IN = 42f
    const val DECK_LIVE_PSF = 40f
    const val DECK_DEAD_PSF = 10f
    const val ROOF_DEAD_PSF = 15f
    const val PIER_DIA_IN = 12f
    /** The porch deck's top sits this far below the house's finished floor (Dan). */
    const val DECK_DROP_IN = 4f
    /** Top of the porch deck, inches (100'-0" datum = the first floor). */
    fun deckTopIn(s: BuildingSettings): Float = s.datumIn - DECK_DROP_IN
    const val MIN_COOKIE_IN = 16f

    fun isPorch(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY
    fun defaults(): Map<String, Float> = mapOf(TYPE to 0f, ROOF to 1f, COLUMNS to 0f, PIER to 0f)
    fun prop(s: DrawingElement.Symbol, k: String): Int = (s.properties[k] ?: defaults()[k] ?: 0f).toInt()
    fun typeOf(s: DrawingElement.Symbol) = prop(s, TYPE).coerceIn(0, 2)

    /** Which side of the house a porch is on, and the house face's coordinate (world px). */
    enum class Side { TOP, BOTTOM, LEFT, RIGHT }
    fun sideOf(r: Rect, fp: Rect): Pair<Side, Float> {
        val d = listOf(
            Side.TOP to abs(r.bottom - fp.top), Side.BOTTOM to abs(r.top - fp.bottom),
            Side.LEFT to abs(r.right - fp.left), Side.RIGHT to abs(r.left - fp.right)
        ).minBy { it.second }.first
        return d to when (d) { Side.TOP -> fp.top; Side.BOTTOM -> fp.bottom; Side.LEFT -> fp.left; Side.RIGHT -> fp.right }
    }

    /** One piece the foundation layer gets: an id suffix, what it is, where, how big, its settings. */
    data class Part(val key: String, val name: String, val category: String, val center: Offset, val size: Size, val props: Map<String, Float> = emptyMap())

    /** Equally spaced spots from [a] to [b], no more than [max] apart, ends included. */
    fun spots(a: Float, b: Float, max: Float): List<Float> {
        val n = ceil(((b - a) / max).toDouble()).toInt().coerceAtLeast(1)
        return (0..n).map { a + (b - a) * it / n }
    }

    /**
     * A wood deck post's cookie, inches square: its load (deck, and the roof if there is one, over
     * half the porch's depth by the post spacing) over the soil's bearing, up to the next 2", 16" the least.
     */
    fun cookieIn(spacingIn: Float, depthIn: Float, roof: Boolean, s: BuildingSettings): Float {
        val areaSqFt = spacingIn / 12f * (depthIn / 2f / 12f)
        val psf = DECK_LIVE_PSF + DECK_DEAD_PSF + if (roof) s.structure.roofSnowPsf + ROOF_DEAD_PSF else 0f
        val sqIn = areaSqFt * psf / s.structure.soilBearingPsf.coerceAtLeast(500f) * 144f
        return max(MIN_COOKIE_IN, ceil(sqrt(sqIn) / 2f) * 2f)
    }

    /** What holds the porch up, for the foundation layer. */
    fun foundation(porch: DrawingElement.Symbol, fp: Rect, s: BuildingSettings, inch: Float): List<Part> {
        val r = porch.boundingRect()
        val (side, hf) = sideOf(r, fp)
        // Local frame: u along the house face, v out from it.
        fun world(u1: Float, u2: Float, v1: Float, v2: Float): Rect = when (side) {
            Side.TOP -> Rect(u1, hf - v2, u2, hf - v1)
            Side.BOTTOM -> Rect(u1, hf + v1, u2, hf + v2)
            Side.LEFT -> Rect(hf - v2, u1, hf - v1, u2)
            Side.RIGHT -> Rect(hf + v1, u1, hf + v2, u2)
        }
        val alongX = side == Side.TOP || side == Side.BOTTOM
        val pu1 = if (alongX) r.left else r.top
        val pu2 = if (alongX) r.right else r.bottom
        val depth = when (side) { Side.TOP -> hf - r.top; Side.BOTTOM -> r.bottom - hf; Side.LEFT -> hf - r.left; Side.RIGHT -> r.right - hf }
        if (depth <= inch * 12f || pu2 - pu1 <= inch * 12f) return emptyList()
        // Toward the house, as the footing's INNER sign: +1 when the house is the + way across the piece.
        val towardHouse = when (side) { Side.TOP, Side.LEFT -> 1f; Side.BOTTOM, Side.RIGHT -> -1f }
        val out = mutableListOf<Part>()
        fun part(key: String, name: String, cat: String, rr: Rect, props: Map<String, Float> = emptyMap()) =
            out.add(Part(key, name, cat, rr.center, rr.size, props))
        val type = typeOf(porch)
        val roof = prop(porch, ROOF) == 1
        if (type == 2) {
            // Wood deck: posts along the outside edge (the house side is on a ledger), to frost depth.
            val depthIn = depth / inch
            val us = spots(pu1 + 3f * inch, pu2 - 3f * inch, MAX_POST_SPACING_IN * inch)
            val spacingIn = if (us.size > 1) (us[1] - us[0]) / inch else (pu2 - pu1) / inch
            val v = depth - 3f * inch
            val cookie = prop(porch, PIER) == 1
            val c = cookieIn(spacingIn, depthIn, roof, s)
            for ((i, u) in us.withIndex()) {
                val at = world(u, u, v, v).center
                if (cookie) out += Part("ck$i", Structure.PAD, Structure.FOUNDATION, at, Size(c * inch, c * inch), mapOf(Structure.THICK to 8f))
                else out += Part("pier$i", Structure.PIER, Structure.FOUNDATION, at, Size(PIER_DIA_IN * inch, PIER_DIA_IN * inch))
                out += Part("post$i", Structure.POST, Structure.FRAMING, at, Size(5.5f * inch, 5.5f * inch))
            }
            return out
        }
        // Framed or masonry: walls on three sides, a continuous footing at basement footing level.
        val wall = if (type == 1) FoundationWall.BLOCK_12 else s.foundationWall
        val t = wall.thicknessIn * inch
        val f = s.footingWidthIn * inch
        val p = (s.footingOutsideIn?.times(inch)) ?: ((f - t) / 2f)
        val houseP = Building.footingProjections(s).first * inch
        part("w-out", wall.symbol, PlanSymbols.WALLS, world(pu1, pu2, depth - t, depth))
        part("w-a", wall.symbol, PlanSymbols.WALLS, world(pu1, pu1 + t, 0f, depth - t))
        part("w-b", wall.symbol, PlanSymbols.WALLS, world(pu2 - t, pu2, 0f, depth - t))
        val thick = mapOf(Structure.THICK to s.footingThicknessIn)
        part("f-out", Structure.FOOTING, Structure.FOUNDATION, world(pu1 - p, pu2 + p, depth + p - f, depth + p),
            thick + mapOf(Structure.JOIN to (Structure.JOIN_OWN * 4).toFloat(), Structure.INNER to towardHouse))
        // The sides butt the outside one and the house footing: no lines across either joint.
        val v1 = houseP
        val v2 = depth + p - f
        if (v2 > v1) {
            // The porch's inside is +u from the first side piece, -u from the other.
            val joins = (Structure.JOIN_BUTT + 3 * Structure.JOIN_BUTT).toFloat()
            part("f-a", Structure.FOOTING, Structure.FOUNDATION, world(pu1 - p, pu1 - p + f, v1, v2), thick + mapOf(Structure.JOIN to joins, Structure.INNER to 1f))
            part("f-b", Structure.FOOTING, Structure.FOUNDATION, world(pu2 + p - f, pu2 + p, v1, v2), thick + mapOf(Structure.JOIN to joins, Structure.INNER to -1f))
        }
        return out
    }

    /**
     * The porch floor on the framing layer (round 2): framed and wood deck porches get 2x8 joists
     * at 16" o.c. running out from the house (on a ledger) to the outside wall or beam; a masonry porch
     * gets its 4" concrete cap.
     */
    fun framing(porch: DrawingElement.Symbol, fp: Rect, s: BuildingSettings, inch: Float): List<Part> {
        val r = porch.boundingRect()
        val (side, hf) = sideOf(r, fp)
        fun world(u1: Float, u2: Float, v1: Float, v2: Float): Rect = when (side) {
            Side.TOP -> Rect(u1, hf - v2, u2, hf - v1)
            Side.BOTTOM -> Rect(u1, hf + v1, u2, hf + v2)
            Side.LEFT -> Rect(hf - v2, u1, hf - v1, u2)
            Side.RIGHT -> Rect(hf + v1, u1, hf + v2, u2)
        }
        val alongX = side == Side.TOP || side == Side.BOTTOM
        val pu1 = if (alongX) r.left else r.top
        val pu2 = if (alongX) r.right else r.bottom
        val depth = when (side) { Side.TOP -> hf - r.top; Side.BOTTOM -> r.bottom - hf; Side.LEFT -> hf - r.left; Side.RIGHT -> r.right - hf }
        if (depth <= inch * 12f) return emptyList()
        val type = typeOf(porch)
        if (type == 1) {
            val rr = world(pu1, pu2, 0f, depth)
            return listOf(Part("cap", Structure.SLAB, Structure.FRAMING, rr.center, rr.size, mapOf(Structure.THICK to CAP_IN)))
        }
        val t = if (type == 0) s.foundationWall.thicknessIn * inch else 0f
        val rr = world(pu1 + t, pu2 - t, 0f, depth - t)
        // Joists square to the house: up and down the page off a north or south wall.
        return listOf(Part("joists", Structure.JOIST_BAY, Structure.FRAMING, rr.center, rr.size, mapOf(
            Structure.SPACING to 16f, Structure.JOIST to Structure.joistCodeFor(7.25f).toFloat(),
            Structure.DIRECTION to if (alongX) 1f else 0f, Structure.BEAMS to 0f, Structure.BEAM_SECTION to -1f)))
    }

    /** Where the roof's columns stand (world px): along the outside edge, corners included, no more than 8' apart. */
    fun columns(porch: DrawingElement.Symbol, fp: Rect?, inch: Float): List<Pair<Offset, Boolean>> {
        if (prop(porch, ROOF) != 1) return emptyList()
        val r = porch.boundingRect()
        val side = fp?.let { sideOf(r, it).first } ?: Side.BOTTOM
        val inset = minOf(r.width, r.height) * 0.06f
        val (a, b, fixed, alongX) = when (side) {
            Side.TOP -> Quad(r.left + inset, r.right - inset, r.top + inset, true)
            Side.BOTTOM -> Quad(r.left + inset, r.right - inset, r.bottom - inset, true)
            Side.LEFT -> Quad(r.top + inset, r.bottom - inset, r.left + inset, false)
            Side.RIGHT -> Quad(r.top + inset, r.bottom - inset, r.right - inset, false)
        }
        val us = spots(a, b, MAX_POST_SPACING_IN * inch)
        val kind = prop(porch, COLUMNS)
        return us.mapIndexed { i, u ->
            val masonry = kind == 1 || (kind == 2 && (i == 0 || i == us.lastIndex))
            (if (alongX) Offset(u, fixed) else Offset(fixed, u)) to masonry
        }
    }
    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Boolean)

    /** The roof's pitch: as set, else 4/12 for a shed and the house's for a gable or hip (6/12 without one). */
    fun pitchOf(p: DrawingElement.Symbol, s: BuildingSettings): Float =
        p.properties[PITCH] ?: if (prop(p, ROOF_STYLE) == 0) 4f else (s.roof?.pitch ?: 6f)

    /**
     * The porch roof's plan (Nov 23): shed, gable facing out or hip, tied into the house wall, out
     * past the columns by the house roof's overhang (12" without one). Lines in world px, as the
     * house roof's ([RoofPlan.Line]), and where its labels go.
     */
    data class RoofDrawing(val lines: List<RoofPlan.Line>, val pitchAt: List<Offset>, val labelAt: Offset, val shedArrow: Pair<Offset, Offset>?)

    fun roof(p: DrawingElement.Symbol, state: CanvasState): RoofDrawing? {
        if (prop(p, ROOF) != 1) return null
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull() ?: return null
        val k = state.inchesToPx(1f).takeIf { it > 0f } ?: return null
        val r = p.boundingRect()
        val (side, hf) = sideOf(r, fp)
        val alongX = side == Side.TOP || side == Side.BOTTOM
        fun page(u: Float, v: Float): Offset = when (side) {
            Side.TOP -> Offset(u, hf - v); Side.BOTTOM -> Offset(u, hf + v); Side.LEFT -> Offset(hf - v, u); Side.RIGHT -> Offset(hf + v, u)
        }
        val depth = when (side) { Side.TOP -> hf - r.top; Side.BOTTOM -> r.bottom - hf; Side.LEFT -> hf - r.left; Side.RIGHT -> r.right - hf }
        val ov = (state.building.roof?.overhangIn ?: 12f) * k
        val oc = (state.building.roof?.ocIn ?: 24f).coerceAtLeast(4f) * k
        val u1 = (if (alongX) r.left else r.top) - ov
        val u2 = (if (alongX) r.right else r.bottom) + ov
        val vOut = depth + ov
        val uc = (u1 + u2) / 2f
        val lines = mutableListOf<RoofPlan.Line>()
        fun line(a1: Float, b1: Float, a2: Float, b2: Float, kind: RoofPlan.Kind) { lines += RoofPlan.Line(page(a1, b1), page(a2, b2), kind) }
        // Heights (inches): the porch beam lines up with the first-floor plate; the eave hangs past it.
        val s = state.building
        val pitchV = pitchOf(p, s) / 12f
        val eaveAtOut = eaveTopIn(p, s)
        val vOutIn = vOut / k
        // Where it lays onto the house roof (Dan, Nov 23): v px out from the wall, negative = up the house roof.
        val house = houseRoofFacing(p, state, fp)
        val style = prop(p, ROOF_STYLE).coerceIn(0, 2)
        val half = (u2 - u1) / 2f
        val ridgeH = eaveAtOut + half / k * pitchV
        val vTie = if (style == 0) meetV(house, eaveAtOut, vOutIn, pitchV) * k else 0f
        val vEave = if (style == 0) vTie else meetV(house, eaveAtOut, 0f, 0f) * k
        val vRidge = if (style == 0) vTie else meetV(house, ridgeH, 0f, 0f) * k
        // Eave and rakes round the three open sides, in to where they meet the house (roof or wall).
        line(u1, vEave, u1, vOut, RoofPlan.Kind.EAVE); line(u1, vOut, u2, vOut, RoofPlan.Kind.EAVE); line(u2, vOut, u2, vEave, RoofPlan.Kind.EAVE)
        line(u1, 0f, u2, 0f, RoofPlan.Kind.WALL)
        val pitchAt = mutableListOf<Offset>()
        var arrow: Pair<Offset, Offset>? = null
        when (style) {
            0 -> {
                // Shed: where it lays onto the house roof (or the wall), rafters from there down to the outside eave.
                if (vTie != 0f) line(u1, vTie, u2, vTie, RoofPlan.Kind.HIP)
                var u = u1 + ov; while (u <= u2 - ov + 0.5f) { line(u, vTie, u, vOut, RoofPlan.Kind.MEMBER); u += oc }
                pitchAt += page(uc, vOut * 0.35f)
                arrow = page(uc, vOut * 0.5f) to page(uc, vOut * 0.85f)
            }
            else -> {
                // Gable or hip: the ridge out from the house, square to it; a hip stops it half the width short.
                // Where it meets a house roof, valleys run from the side eaves to the ridge's end.
                val ridgeEnd = if (style == 2) (vOut - half).coerceAtLeast(0f) else vOut
                line(uc, vRidge, uc, ridgeEnd, RoofPlan.Kind.RIDGE)
                if (vRidge != 0f || vEave != 0f) { line(u1, vEave, uc, vRidge, RoofPlan.Kind.HIP); line(u2, vEave, uc, vRidge, RoofPlan.Kind.HIP) }
                if (style == 2) { line(u1, vOut, uc, ridgeEnd, RoofPlan.Kind.HIP); line(u2, vOut, uc, ridgeEnd, RoofPlan.Kind.HIP) }
                // Rafters off the ridge to each eave; in a hip end they stop at the hip (jacks).
                var v = 0f
                while (v <= vOut + 0.5f) {
                    val reach = if (style == 2 && v > ridgeEnd) (vOut - v).coerceAtLeast(0f) else half
                    line(u1, v, u1 + reach, v, RoofPlan.Kind.MEMBER); line(u2, v, u2 - reach, v, RoofPlan.Kind.MEMBER)
                    v += oc
                }
                if (style == 2) {
                    // The hip end's jacks, square to the outside eave, out from the king common.
                    var d = 0f
                    while (d < half) {
                        val reach = (half - d).coerceAtMost(vOut - ridgeEnd)
                        for (uu in if (d == 0f) listOf(uc) else listOf(uc - d, uc + d)) line(uu, vOut, uu, vOut - reach, RoofPlan.Kind.MEMBER)
                        d += oc
                    }
                }
                pitchAt += page((u1 + uc) / 2f, ridgeEnd * 0.5f); pitchAt += page((uc + u2) / 2f, ridgeEnd * 0.5f)
            }
        }
        return RoofDrawing(lines, pitchAt, page(uc, vOut * 0.7f), arrow)
    }

    /** The porch side as the elevations name it. */
    fun viewerSide(side: Side): ViewerSide = when (side) {
        Side.TOP -> ViewerSide.ABOVE; Side.BOTTOM -> ViewerSide.BELOW; Side.LEFT -> ViewerSide.LEFT; Side.RIGHT -> ViewerSide.RIGHT
    }

    /** The porch's rim (framed and wood deck) or cap (masonry), inches deep below the deck top. */
    const val RIM_IN = 9.25f
    const val CAP_IN = 4f
    const val BEAM_IN = 11.25f
    const val SKIRT_COURSE_IN = 6f
    const val VENEER_COURSE_IN = 8f

    /**
     * The house roof's plane on the porch's side, when its eave faces the porch (a one-story house):
     * its height at the outside of the wall and its rise per inch. Null when the porch is on a gable
     * end or under an upper floor: then the porch roof ties into the wall.
     */
    fun houseRoofFacing(p: DrawingElement.Symbol, state: CanvasState, fp: Rect): Triple<Float, Float, Float>? {
        val s = state.building
        val hr = s.roof ?: return null
        if (s.upperFloors.isNotEmpty()) return null
        val g = runCatching { RoofPlan.geometry(state, hr) }.getOrNull() ?: return null
        val side = sideOf(p.boundingRect(), fp).first
        if (g.ridgeAcross != (side == Side.TOP || side == Side.BOTTOM)) return null
        return Triple(ExteriorElevation.plateIn(s) + hr.heelIn, hr.pitch / 12f, hr.overhangIn)
    }

    /**
     * Where a porch roof line meets the house roof (Dan, Nov 23: the porch roof lays onto the house
     * roof): v out from the house wall (negative = up the house roof, over the wall), for a line at
     * height [e0] [v0] out, rising toward the house [m] per inch. 0 (the wall) when it runs in under
     * the house eave instead, or there's no house roof facing it.
     */
    fun meetV(house: Triple<Float, Float, Float>?, e0: Float, v0: Float, m: Float): Float {
        val (h0, hp, hov) = house ?: return 0f
        if (hp - m <= 1e-4f) return 0f
        val v = (h0 - e0 - v0 * m) / (hp - m)
        return if (v > hov) 0f else v
    }

    /**
     * The top of the porch roof's fascia, inches: as the house roof works it out, the beam top (the
     * first-floor plate) plus the house's heel, less the overhang times the pitch. So a porch at the
     * house's pitch lines up with the house fascia in every view.
     */
    fun eaveTopIn(p: DrawingElement.Symbol, s: BuildingSettings): Float {
        val ov = s.roof?.overhangIn ?: 12f
        return s.datumIn + s.plateHeightIn + (s.roof?.heelIn ?: 4f) - ov * pitchOf(p, s) / 12f
    }

    data class ESeg(val x1: Float, val e1: Float, val x2: Float, val e2: Float, val pen: ExteriorElevation.Pen)
    data class ELabel(val text: String, val x: Float, val e: Float)

    /**
     * A porch in an exterior elevation (Nov 23, round 2), x along the face as the elevation runs it
     * (inches from its left end), e the elevation. Seen from the front when it's on this face, from the
     * side when it's on a face next to it (it sticks out past the house), not at all from behind.
     * Deck top 4" under the floor; under it the skirt (framed: siding to grade), the veneer under a
     * concrete cap (masonry) or the posts (wood deck); columns from the deck to the porch beam, its top
     * lined up with the first-floor plate; the roof over it as set (shed, gable or hip).
     */
    fun elevation(p: DrawingElement.Symbol, state: CanvasState, view: ViewerSide, fp: Rect, k: Float): Pair<List<ESeg>, List<ELabel>> {
        val segs = mutableListOf<ESeg>(); val labels = mutableListOf<ELabel>()
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, pen: ExteriorElevation.Pen) { segs += ESeg(x1, e1, x2, e2, pen) }
        fun rect(x1: Float, e1: Float, x2: Float, e2: Float, pen: ExteriorElevation.Pen) {
            line(x1, e1, x2, e1, pen); line(x2, e1, x2, e2, pen); line(x2, e2, x1, e2, pen); line(x1, e2, x1, e1, pen)
        }
        val r = p.boundingRect()
        val (side, _) = sideOf(r, fp)
        val ps = viewerSide(side)
        val opposite = (ps == ViewerSide.ABOVE && view == ViewerSide.BELOW) || (ps == ViewerSide.BELOW && view == ViewerSide.ABOVE) ||
            (ps == ViewerSide.LEFT && view == ViewerSide.RIGHT) || (ps == ViewerSide.RIGHT && view == ViewerSide.LEFT)
        if (opposite) return segs to labels
        val s = state.building
        val front = ps == view
        val xs = listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).map { Elevation.alongOf(fp, view, it) / k }
        val xa = xs.min(); val xb = xs.max()
        // Which end of the side view is the house: the end nearer the house's own run (0..wall length).
        val wallLen = (if (view == ViewerSide.BELOW || view == ViewerSide.ABOVE) fp.width else fp.height) / k
        val houseAtLeft = xa >= wallLen - 1f
        val houseX = if (front) 0f else if (houseAtLeft) xa else xb
        val outX = if (front) 0f else if (houseAtLeft) xb else xa
        val outDir = if (houseAtLeft) 1f else -1f
        val grade = ExteriorElevation.gradeIn(s)
        val deck = deckTopIn(s)
        val type = typeOf(p)
        val beamTop = s.datumIn + s.plateHeightIn
        val beamBot = beamTop - BEAM_IN
        // The deck edge and what's under it.
        val under = if (type == 1) deck - CAP_IN else deck - RIM_IN
        val capOver = if (type == 1) 1f else 0f
        rect(xa - capOver, under, xb + capOver, deck, ExteriorElevation.Pen.HEAVY)
        when (type) {
            0 -> {
                rect(xa, grade, xb, under, ExteriorElevation.Pen.MEDIUM)
                var e = grade + SKIRT_COURSE_IN; while (e < under - 0.5f) { line(xa, e, xb, e, ExteriorElevation.Pen.THIN); e += SKIRT_COURSE_IN }
                labels += ELabel("SKIRT TO MATCH HOUSE", (xa + xb) / 2f, (grade + under) / 2f)
            }
            1 -> {
                rect(xa, grade, xb, under, ExteriorElevation.Pen.MEDIUM)
                var e = grade + VENEER_COURSE_IN; while (e < under - 0.5f) { line(xa, e, xb, e, ExteriorElevation.Pen.THIN); e += VENEER_COURSE_IN }
                labels += ELabel("BRICK/STONE VENEER, CONC. CAP", (xa + xb) / 2f, (grade + under) / 2f)
            }
            else -> {
                // Posts down to grade under the outside edge (front: each one; side: the outer one).
                val postXs = if (front) {
                    val inch = k
                    columns(p.copy(properties = p.properties + (ROOF to 1f)), fp, inch).map { Elevation.alongOf(fp, view, it.first) / k }
                } else listOf(outX - outDir * 3f)
                for (x in postXs) rect(x - 2.75f, grade, x + 2.75f, under, ExteriorElevation.Pen.MEDIUM)
            }
        }
        line(min(xa, xb) - 12f, grade, max(xa, xb) + 12f, grade, ExteriorElevation.Pen.HEAVY)
        if (prop(p, ROOF) != 1) return segs to labels
        // Columns from the deck up to the beam, and the beam.
        val colKind = prop(p, COLUMNS)
        val cols = columns(p, fp, k)
        val colXs = if (front) cols.map { Elevation.alongOf(fp, view, it.first) / k to it.second } else listOf((outX - outDir * 8f) to (colKind == 1 || colKind == 2))
        for ((x, masonry) in colXs) {
            val half = if (masonry) 8f else 3f
            rect(x - half, deck, x + half, beamBot, ExteriorElevation.Pen.MEDIUM)
            if (masonry) { var e = deck + VENEER_COURSE_IN; while (e < beamBot - 0.5f) { line(x - half, e, x + half, e, ExteriorElevation.Pen.THIN); e += VENEER_COURSE_IN } }
        }
        val ov = s.roof?.overhangIn ?: 12f
        val pitch = pitchOf(p, s) / 12f
        val style = prop(p, ROOF_STYLE).coerceIn(0, 2)
        val fascia = 6f
        // Shingle courses across a roof face seen in this view (Dan, Nov 23), [poly] its outline (x, elevation).
        fun shingles(poly: List<Pair<Float, Float>>) {
            val hr = s.roof ?: return
            val pv = pitchOf(p, s)
            val exposure = (hr.coveringIn ?: hr.covering.defaultIn).coerceAtLeast(2f)
            val dz = exposure * pv / kotlin.math.sqrt(144f + pv * pv)
            if (dz < 0.5f || poly.size < 3) return
            val zLo = poly.minOf { it.second }; val zHi = poly.maxOf { it.second }
            var z = zLo + dz
            while (z < zHi - 0.5f) {
                val xs = poly.indices.mapNotNull { i ->
                    val (x1, z1) = poly[i]; val (x2, z2) = poly[(i + 1) % poly.size]
                    if (z1 == z2 || z < min(z1, z2) || z > max(z1, z2)) null else x1 + (z - z1) / (z2 - z1) * (x2 - x1)
                }
                if (xs.size >= 2) line(xs.min(), z, xs.max(), z, ExteriorElevation.Pen.THIN)
                z += dz
            }
        }
        if (front) {
            rect(xa, beamBot, xb, beamTop, ExteriorElevation.Pen.MEDIUM)
            val l = xa - ov; val rr = xb + ov
            val eaveTop = eaveTopIn(p, s)
            val eaveBot = eaveTop - fascia
            if (style == 0) {
                // Shed seen from the front: the fascia, the roof rising away to the house wall.
                val depthIn = ((if (side == Side.TOP || side == Side.BOTTOM) r.height else r.width) / k) + ov
                // Up to where it lays onto the house roof (or the wall, under the house eave).
                val house = houseRoofFacing(p, state, fp)
                val vMeet = meetV(house, eaveTop, depthIn, pitch)
                val tie = eaveTop + (depthIn - vMeet) * pitch
                rect(l, eaveBot, rr, eaveTop, ExteriorElevation.Pen.HEAVY)
                line(l, eaveTop, l, tie, ExteriorElevation.Pen.HEAVY); line(l, tie, rr, tie, ExteriorElevation.Pen.HEAVY); line(rr, tie, rr, eaveTop, ExteriorElevation.Pen.HEAVY)
                shingles(listOf(l to eaveTop, rr to eaveTop, rr to tie, l to tie))
            } else {
                val mid = (l + rr) / 2f
                val ridge = eaveTop + (rr - l) / 2f * pitch
                line(l, eaveTop, mid, ridge, ExteriorElevation.Pen.HEAVY); line(mid, ridge, rr, eaveTop, ExteriorElevation.Pen.HEAVY)
                line(l, eaveBot, rr, eaveBot, ExteriorElevation.Pen.MEDIUM); line(l, eaveBot, l, eaveTop, ExteriorElevation.Pen.HEAVY); line(rr, eaveBot, rr, eaveTop, ExteriorElevation.Pen.HEAVY)
                if (style == 1) line(xa, beamTop, xb, beamTop, ExteriorElevation.Pen.MEDIUM)      // the gable end's bottom
                else shingles(listOf(l to eaveTop, rr to eaveTop, mid to ridge))                   // the hip end's face
            }
            labels += ELabel("PORCH ${roofStyles[style].uppercase()} ROOF ${RoofPlan.pitchText(pitchOf(p, s))}", (xa + xb) / 2f, beamTop + 20f)
        } else {
            // From the side: the roof's profile, the house at one end, the eave past the columns at the other.
            val farX = outX + outDir * ov
            rect(min(houseX, outX), beamBot, max(houseX, outX), beamTop, ExteriorElevation.Pen.MEDIUM)
            val eaveAtOut = eaveTopIn(p, s)
            // The house roof on this side, if it faces the porch: the porch roof runs up onto it (Dan).
            val house = houseRoofFacing(p, state, fp)
            val vOut = abs(farX - houseX)
            fun xAt(v: Float) = houseX + outDir * v
            when (style) {
                0 -> {
                    val vm = meetV(house, eaveAtOut, vOut, pitch)
                    val top = eaveAtOut + (vOut - vm) * pitch
                    line(xAt(vm), top, farX, eaveAtOut, ExteriorElevation.Pen.HEAVY)
                    line(farX, eaveAtOut, farX, eaveAtOut - fascia, ExteriorElevation.Pen.HEAVY)
                    val wallTie = eaveAtOut + vOut * pitch
                    line(farX, eaveAtOut - fascia, houseX, wallTie - fascia, ExteriorElevation.Pen.MEDIUM)
                    if (vm < 0f) labels += ELabel("PORCH ROOF LAPS ONTO HOUSE ROOF", xAt(vm / 2f), top + 14f)
                }
                else -> {
                    val widthIn = ((if (side == Side.TOP || side == Side.BOTTOM) r.width else r.height) / k) + 2f * ov
                    val ridge = eaveAtOut + widthIn / 2f * pitch
                    // Side eave along the bottom, the ridge along the top.
                    rect(min(houseX, farX), eaveAtOut - fascia, max(houseX, farX), eaveAtOut, ExteriorElevation.Pen.HEAVY)
                    val ridgeEndX = if (style == 2) farX - outDir * (widthIn / 2f) else farX
                    // The ridge and the side eave each run in until they meet the house roof; the valley joins them.
                    val vr = meetV(house, ridge, 0f, 0f)
                    val ve = meetV(house, eaveAtOut, 0f, 0f)
                    line(xAt(vr), ridge, ridgeEndX, ridge, ExteriorElevation.Pen.HEAVY)
                    if (style == 2) line(ridgeEndX, ridge, farX, eaveAtOut, ExteriorElevation.Pen.HEAVY)
                    else line(farX, eaveAtOut, farX, ridge, ExteriorElevation.Pen.HEAVY)
                    if (house != null && (vr != 0f || ve != 0f)) line(xAt(ve), eaveAtOut, xAt(vr), ridge, ExteriorElevation.Pen.MEDIUM)
                    else line(houseX, eaveAtOut, houseX, ridge, ExteriorElevation.Pen.THIN)
                    if (ve != 0f) line(xAt(ve), eaveAtOut, houseX, eaveAtOut, ExteriorElevation.Pen.HEAVY)
                    // The side slope's face, from the valley to the far end.
                    shingles(listOf(xAt(ve) to eaveAtOut, farX to eaveAtOut, ridgeEndX to ridge, xAt(vr) to ridge))
                }
            }
        }
        return segs to labels
    }

    /** The porch on the plan: its outline, what kind it is, and the roof's columns. */
    fun DrawScope.drawPorch(e: DrawingElement.Symbol, state: CanvasState, ink: Color) {
        val inch = state.inchesToPx(1f).takeIf { it > 0f } ?: return
        val r = e.boundingRect()
        val lineW = max(1f, inch * 0.6f)
        drawRect(ink, topLeft = r.topLeft, size = r.size, style = Stroke(lineW * 1.6f))
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull()
        for ((c, masonry) in columns(e, fp, inch)) {
            val half = (if (masonry) 8f else 3f) * inch
            drawRect(ink, topLeft = Offset(c.x - half, c.y - half), size = Size(2f * half, 2f * half), style = Stroke(lineW))
            if (masonry) drawLine(ink, Offset(c.x - half, c.y - half), Offset(c.x + half, c.y + half), strokeWidth = lineW * 0.6f)
        }
        val ts = inch * 6f
        val label = when (typeOf(e)) { 1 -> "MASONRY PORCH"; 2 -> "WOOD DECK PORCH"; else -> "PORCH" }
        val w = ts * 0.6f * label.length
        if (w < r.width * 0.95f) drawPageText(label, Offset(r.center.x - w / 2f, r.center.y - ts), ts, ink, bold = true, maxWidth = r.width)
        // Its deck, 4" below the house floor (Dan).
        val deck = "T/DECK ${Building.elevationText(deckTopIn(state.building))}"
        val w2 = ts * 0.5f * deck.length
        if (w2 < r.width * 0.95f) drawPageText(deck, Offset(r.center.x - w2 / 2f, r.center.y + ts * 0.3f), ts * 0.8f, ink, bold = false, maxWidth = r.width)
    }
}
