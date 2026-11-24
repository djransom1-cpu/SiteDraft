package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The basement beam, its posts and their pad footings, sized from the drawing (Dan, Nov 22 2026)
 * with his Structural Engineering Suite's steel engine ([SteelEngine]) and his rules:
 *  - joists span the short way; beams across them so no joist span is over [StructureSpecs.maxJoistSpanFt]
 *    (15'): one center beam on a house up to 30' deep
 *  - posts no more than the set spacing apart (12', or the standard 8'), evenly spaced between the foundation walls
 *  - total deflection no more than 1/4" (Dan: a more solid floor), besides the code's L/360 and L/240
 *  - the beam a steel W, W8x15 the smallest (the lightest W8 that passes, then deeper shapes), each
 *    span checked as a simple span between posts (conservative for a continuous beam)
 *  - load: the floor's live + dead psf over the beam's tributary width (half the joist span each
 *    side), once for the first floor and again for each floor above (their bearing walls stack)
 *  - posts: lally (steel pipe) or HSS tube per job, the lightest that carries an interior post's load
 *    (a full post spacing of beam) at the basement's height, on a 6"x6" base plate 3/8", 1/2" or
 *    3/4" thick (the thinnest that passes), no anchor bolts
 *  - pads: the post load over the soil's allowable bearing, rounded up to 2", 24" square the least,
 *    12" thick (a third of the width on bigger pads)
 * Only the rectangular footprint the framing is built for, like the rest of the derived layers.
 */
object StructuralDesign {
    /** Old 8" block default; the wall now comes from the building ([Building.foundationWallIn]). */
    const val FOUNDATION_WALL_IN = 7.625f
    const val MIN_PAD_IN = 24f
    /** Base plate under a column: square, this wide (Dan: 6"x6", Nov 22), the thinnest of these that passes. */
    const val PLATE_IN = 6.0
    val PLATE_THICKNESSES_IN = listOf(0.375, 0.5, 0.75)

    /** A post on a beam line: the element it is (or will be), where it stands, its load and its pad. */
    data class PostSpot(
        val id: String, val center: Offset, val alongFt: Float,
        val loadKips: Double, val padIn: Float, val padThickIn: Float, val padBearingPsf: Double
    )

    data class Beam(
        val index: Int,
        /** Joist span each side of the beam, and the tributary width it carries, feet. */
        val joistSpanFt: Float, val tribFt: Float,
        val floors: Int, val lengthFt: Float,
        /** Spans between posts, the count and the longest (the beam is sized for that one). */
        val spans: Int, val spanFt: Float, val spansFt: List<Float>,
        val shape: SteelEngine.Shape?, val result: SteelEngine.BeamResult?,
        /** The heaviest post load and the pad under it. */
        val postLoadKips: Double, val postHeightFt: Double,
        val post: SteelEngine.Shape?, val postResult: SteelEngine.ColumnResult?,
        val padIn: Float, val padThickIn: Float, val padBearingPsf: Double,
        val posts: List<PostSpot>,
        /** Posts as designed (evenly spaced): how many spans, for the posts the framing builds. */
        val designedSpans: Int,
        /** Posts placed or moved by hand: the spans come from where they stand. */
        val postsByHand: Boolean,
        /** The beam picked in Job specs (true), or by the engine; [note] when the pick couldn't carry it. */
        val beamPicked: Boolean, val note: String?
    )

    data class Design(val beams: List<Beam>, val joistSpanFt: Float, val specs: StructureSpecs)

    /** Beams across the joists for a joist span of [spanIn]: as few as keep each span within the limit. */
    fun beamCount(spanIn: Float, maxSpanFt: Float): Int = (ceil(spanIn / (maxSpanFt * 12f)).toInt() - 1).coerceAtLeast(0)

    /** Spans of beam between the foundation walls with posts no more than [maxFt] apart, evenly spaced. */
    fun postSpans(lengthFt: Float, maxFt: Float): Int = ceil(lengthFt / maxFt.coerceAtLeast(1f) - 1e-4f).toInt().coerceAtLeast(1)

    /** The post spacing to use: the standard 8', or the one set. */
    fun spacingFt(sp: StructureSpecs): Float = if (sp.standardPosts) 8f else sp.postSpacingFt

    /** W8s only: as picked, or by the basement's height when not picked (under 8'-0"). */
    fun w8Only(s: BuildingSettings): Boolean = s.structure.w8Only ?: (basementHeightIn(s) < s.structure.w10HeightIn)

    const val W8X21_PLF = 21.0

    /**
     * The beam candidates from 15 plf (W8x15 Dan's smallest), lightest first: W8s only (deeper shapes
     * after them only if no W8 passes), or the best option, the lightest W of any depth (weight is the cost).
     */
    fun beamCandidates(bestOption: Boolean): List<SteelEngine.Shape> {
        val w = SteelEngine.AISC_DATABASE.filter { it.type == "W" && it.weight >= 15.0 }
        if (bestOption) return w.sortedBy { it.weight }
        val w8 = w.filter { it.name.startsWith("W8x") }.sortedBy { it.weight }
        return w8 + (w - w8.toSet()).sortedBy { it.weight }
    }

    /** The basement's height: the floor (top of the footing) to the bottom of the first-floor joists (top of the sill). */
    fun basementHeightIn(s: BuildingSettings): Float = Building.sillTopIn(s) - Building.footingTopIn(s)

    /** Lally columns start at 3-1/2" (Pipe 3 Std); HSS from the smallest square tube. Lightest first. */
    fun postCandidates(type: PostType): List<SteelEngine.Shape> =
        SteelEngine.AISC_DATABASE.filter { if (type == PostType.LALLY) it.type == "Pipe" && it.d >= 3.5 else it.type == "HSS" }.sortedBy { it.weight }

    /** A pad for [kips]: the load over the soil's bearing, rounded up to 2", 24" the least; 12" thick, a third of the width on bigger ones. */
    fun pad(kips: Double, soilPsf: Float): Triple<Float, Float, Double> {
        val areaSqIn = kips * 1000.0 / soilPsf.coerceAtLeast(500f) * 144.0
        val side = max(MIN_PAD_IN, (ceil(sqrt(areaSqIn) / 2.0) * 2.0).toFloat())
        val thick = max(12f, (ceil(side / 3f / 2f) * 2f))
        return Triple(side, thick, kips * 1000.0 / (side * side / 144.0))
    }

    fun design(state: CanvasState): Design? {
        val s = state.building
        if (s.foundation == FoundationType.SLAB) return null
        val fp = RoofPlan.footprint(state) ?: return null
        val k = state.inchesToPx(1f)
        if (k <= 0f) return null
        val sp = s.structure
        val joistsUpDown = fp.width >= fp.height
        val depthIn = (if (joistsUpDown) fp.height else fp.width) / k
        val lengthIn = (if (joistsUpDown) fp.width else fp.height) / k
        val n = beamCount(depthIn, sp.maxJoistSpanFt)
        val joistSpanFt = depthIn / (n + 1) / 12f
        if (n == 0) return Design(emptyList(), joistSpanFt, sp)
        val floors = 1 + s.upperFloors.size
        val beamLenFt = (lengthIn - 2f * Building.foundationWallIn(s)) / 12f
        val trib = joistSpanFt // half a joist span each side
        val dl = sp.deadLoadPsf.toDouble() * floors
        val ll = sp.liveLoadPsf.toDouble() * floors
        val candidates = beamCandidates(!w8Only(s))
        val limit = sp.maxTotalDeflectionIn.toDouble()
        fun analyze(sh: SteelEngine.Shape, spanFt: Float) =
            SteelEngine.analyzeSteelBeam(sh, spanFt.toDouble(), tribLeft_ft = trib / 2.0, tribRight_ft = trib / 2.0, dl_psf = dl, ll_psf = ll)
        fun ok(r: SteelEngine.BeamResult) = r.isPass && (limit <= 0.0 || r.delta_total_in <= limit)
        fun engine(spanFt: Float) = candidates.asSequence().map { it to analyze(it, spanFt) }.firstOrNull { ok(it.second) }
        val picked = sp.beamShape?.let { SteelEngine.shape(it) }
        // Dan (Nov 22): a few questions - code or his deflection, the column spacing, W8 or best - and the engine does
        // the rest. Hand changes are inputs too: posts placed or moved by hand set the spans, a beam picked in Job specs
        // sets the spacing (as far apart as it carries, within the spacing choice); whatever a change leaves short is brought up.
        val spacing = spacingFt(sp)
        val n0 = postSpans(beamLenFt, spacing)
        val baseSpans = if (picked == null) n0
            else (n0..postSpans(beamLenFt, 4f)).firstOrNull { ok(analyze(picked, beamLenFt / it)) } ?: n0
        val wallIn = Building.foundationWallIn(s) * k
        val reach = 12f * k
        val posts = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == Structure.FRAMING && it.symbolName == Structure.POST }
        val beams = (1..n).map { i ->
            // The beam line on the page: across the joists, from the inside of one foundation wall to the other.
            val cross = if (joistsUpDown) fp.top + fp.height * i / (n + 1) else fp.left + fp.width * i / (n + 1)
            val start = (if (joistsUpDown) fp.left else fp.top) + wallIn
            val end = (if (joistsUpDown) fp.right else fp.bottom) - wallIn
            fun page(along: Float) = if (joistsUpDown) Offset(along, cross) else Offset(cross, along)
            val onLine = posts.filter { p ->
                val c = if (joistsUpDown) p.center.y else p.center.x
                val a = if (joistsUpDown) p.center.x else p.center.y
                abs(c - cross) <= reach && a > start - reach && a < end + reach
            }
            val key = "${DerivedLayers.GEN_PREFIX}post:b${i - 1}:"
            val byHand = onLine.any { !DerivedLayers.isUntouched(it) } || state.suppressedGenIds.any { it.startsWith(key) }
            // Where the posts stand: as found (by hand), or evenly spaced.
            val spots: List<Pair<String, Float>> = if (byHand)
                onLine.map { p -> p.id to ((if (joistsUpDown) p.center.x else p.center.y) - start) / k / 12f }.sortedBy { it.second }
            else (1 until baseSpans).map { j -> "$key$j" to beamLenFt * j / baseSpans }
            val stops = listOf(0f) + spots.map { it.second.coerceIn(0f, beamLenFt) } + listOf(beamLenFt)
            val spansFt = stops.zipWithNext { a, b -> b - a }.filter { it > 0.01f }.ifEmpty { listOf(beamLenFt) }
            val longest = spansFt.max()
            var note: String? = null
            val beam = if (picked != null && ok(analyze(picked, longest))) picked to analyze(picked, longest)
                else engine(longest).also { if (picked != null) note = "${picked.name} can't carry ${ft(longest.toDouble())}: brought up to ${it?.first?.name ?: "a heavier beam"}" }
            val wPlf = beam?.second?.let { it.w_dl_plf + it.w_ll_plf } ?: ((dl + ll) * trib)
            // Each post carries half of each span beside it.
            val loads = spots.indices.map { j -> wPlf * (stops[j + 2] - stops[j]) / 2.0 / 1000.0 }
            val maxKips = loads.maxOrNull() ?: 0.0
            // Top of the beam flush with the bottom of the sill plate; a 2x on it for joist nailing (Dan, Nov 22).
            val postHeightFt = ((Building.sillTopIn(s) - 1.5f - (beam?.first?.d?.toFloat() ?: 8f) - Building.footingTopIn(s)) / 12f).toDouble().coerceAtLeast(1.0)
            // Base plates (Dan, Nov 22): 3/8" or 1/2" on a basement column, 3/4" heavy duty; the thinnest that
            // passes, no anchor bolts (the slab locks the column in).
            var post: Pair<SteelEngine.Shape, SteelEngine.ColumnResult>? = null
            loop@ for (sh in postCandidates(sp.post)) for (t in PLATE_THICKNESSES_IN) {
                val r = SteelEngine.analyzeSteelColumn(sh, postHeightFt, maxKips, bp_width = PLATE_IN, bp_length = PLATE_IN, bp_thick = t)
                if (r.isPass) { post = sh to r; break@loop }
            }
            val spotList = spots.mapIndexed { j, (id, along) ->
                val (side, thick, bearing) = pad(loads[j], sp.soilBearingPsf)
                // By hand: under the post as it stands. As designed: worked out the same way every time, so syncing again changes nothing.
                val c = (if (byHand) onLine.firstOrNull { it.id == id }?.center else null) ?: page(start + along * 12f * k)
                PostSpot(id, c, along, loads[j], side, thick, bearing)
            }
            val worst = spotList.maxByOrNull { it.loadKips }
            val (wSide, wThick, wBearing) = worst?.let { Triple(it.padIn, it.padThickIn, it.padBearingPsf) } ?: pad(0.0, sp.soilBearingPsf)
            Beam(i - 1, joistSpanFt, trib, floors, beamLenFt, spansFt.size, longest, spansFt, beam?.first, beam?.second,
                maxKips, postHeightFt, post?.first, post?.second, wSide, wThick, wBearing, spotList, baseSpans, byHand,
                beam != null && beam.first == picked, note)
        }
        return Design(beams, joistSpanFt, sp)
    }

    /** Whether [shape] carries the main beam as the drawing stands now (the suite's checks and the deflection choice). */
    fun beamPasses(state: CanvasState, shape: SteelEngine.Shape): Boolean {
        val d = design(state) ?: return true
        val b = d.beams.firstOrNull() ?: return true
        val floors = b.floors.toDouble()
        val r = SteelEngine.analyzeSteelBeam(shape, b.spanFt.toDouble(), tribLeft_ft = b.tribFt / 2.0, tribRight_ft = b.tribFt / 2.0,
            dl_psf = d.specs.deadLoadPsf * floors, ll_psf = d.specs.liveLoadPsf * floors)
        val limit = d.specs.maxTotalDeflectionIn.toDouble()
        return r.isPass && (limit <= 0.0 || r.delta_total_in <= limit)
    }

    private fun ft(v: Double) = Building.elevationText((v * 12.0).toFloat()).removeSuffix("\"").let { "$it\"" }
    private fun f2(v: Double) = ((v * 100).toLong() / 100.0).let { if (it == it.toLong().toDouble()) "${it.toLong()}" else "$it" }
    private fun pct(r: Double) = "${(r * 100).toInt()}%"
    private fun plateText(t: Double) = when (t) { 0.375 -> "3/8"; 0.5 -> "1/2"; 0.75 -> "3/4"; else -> f2(t) }

    /** The calc summary's rows (for the S-sheet table): label, then the line an engineer checks. */
    fun rows(state: CanvasState): List<Pair<String, String>> {
        val d = design(state) ?: return listOf("Structure" to "Draw the first-floor exterior walls (basement or crawl space) to size the beam.")
        val sp = d.specs
        val out = mutableListOf<Pair<String, String>>()
        out += "Floor loads" to "${sp.liveLoadPsf.toInt()} psf live, ${sp.deadLoadPsf.toInt()} psf dead per floor; soil ${sp.soilBearingPsf.toInt()} psf allowable"
        out += "Joists" to "span ${ft(d.joistSpanFt.toDouble())} (limit ${sp.maxJoistSpanFt.toInt()}'-0\")"
        if (d.beams.isEmpty()) { out += "Beam" to "none: the joists clear-span between the foundation walls"; return out }
        val b = d.beams.first()
        val count = if (d.beams.size == 1) "1 beam (center)" else "${d.beams.size} beams"
        val h = basementHeightIn(state.building)
        out += "Choices" to "basement ${ft(h / 12.0)} floor to joists; " + (if (w8Only(state.building)) "W8s only" else "best option (lightest W, any depth)") +
            "; posts no more than ${spacingFt(sp).toInt()}' apart" + (if (sp.standardPosts) " (standard)" else "") +
            "; " + (if (sp.maxTotalDeflectionIn > 0f) "total deflection ${f2(sp.maxTotalDeflectionIn.toDouble())}\" max" else "code deflection limits")
        out += "Beam layout" to "$count, ${ft(b.lengthFt.toDouble())} long, ${b.spans} span(s) between posts" +
            (if (b.postsByHand) " as placed: ${b.spansFt.joinToString(", ") { ft(it.toDouble()) }}" else " of ${ft(b.spanFt.toDouble())}") +
            (if (b.postsByHand) " (posts by hand)" else "")
        out += "Beam load" to "tributary ${ft(b.tribFt.toDouble())} x ${b.floors} floor(s): " +
            (b.result?.let { "${it.w_dl_plf.toInt()} plf dead (self weight in), ${it.w_ll_plf.toInt()} plf live" } ?: "${((sp.liveLoadPsf + sp.deadLoadPsf) * b.tribFt * b.floors).toInt()} plf")
        val r = b.result
        out += "Beam" to if (b.shape != null && r != null) "${b.shape.name}" + (if (b.beamPicked) " (picked)" else "") + (b.note?.let { " ($it)" } ?: "") + ", Fy 50 ksi, ASD, simple span ${ft(r.L_ft)}: bending ${pct(r.stressRatio)}, shear ${pct(r.shearRatio)}, " +
            "live defl. ${f2(r.delta_live_in)}\" (L/360 = ${f2(r.L360_in)}\"), total ${f2(r.delta_total_in)}\" (L/240 = ${f2(r.L240_in)}\"" +
            (if (sp.maxTotalDeflectionIn > 0f) "; Dan's limit ${f2(sp.maxTotalDeflectionIn.toDouble())}\")" else ")")
        else "no W shape in the table passes: shorten the post spacing or add a beam line"
        val pr = b.postResult
        out += "Posts" to if (b.post != null && pr != null) "${b.post.name} (${sp.post.label}), ${ft(b.postHeightFt)} tall: ${f2(b.postLoadKips)} kips on an interior post, " +
            "${f2(pr.P_allowable_kips)} kips allowed (${pct(pr.capacityRatio)}), KL/r ${pr.KLr_max.toInt()}; " +
            "${PLATE_IN.toInt()}\"x${PLATE_IN.toInt()}\"x${plateText(pr.tp_provided)}\" base plate (needs ${f2(pr.tp_req_in)}\"), no anchor bolts (locked in by the slab)"
        else "no ${sp.post.label} in the table carries ${f2(b.postLoadKips)} kips"
        out += "Pad footings" to (if (b.posts.map { it.padIn }.distinct().size > 1)
            b.posts.joinToString("; ") { "${it.padIn.toInt()}\"x${it.padIn.toInt()}\"x${it.padThickIn.toInt()}\" (${f2(it.loadKips)} k, ${it.padBearingPsf.toInt()} psf)" }
        else "${b.padIn.toInt()}\" x ${b.padIn.toInt()}\" x ${b.padThickIn.toInt()}\": ${b.padBearingPsf.toInt()} psf on the soil") + " (${sp.soilBearingPsf.toInt()} psf allowed)"
        out += "Method" to "AISC 360-16 (Dan's Structural Engineering Suite steel engine). Starting sizes from the drawing; have an engineer check where a seal is required."
        return out
    }

    // ---- The table on the S sheets ----

    private const val LABEL_COL_IN = 1.6f
    private const val TEXT_COL_IN = 9.5f
    private const val ROW_IN = 0.3f
    private const val HEAD_IN = 0.34f

    fun tableSizeIn(state: CanvasState, size: Float = 1f): Size = Size((LABEL_COL_IN + TEXT_COL_IN) * size, (HEAD_IN + rows(state).size * ROW_IN) * size)

    fun DrawScope.drawCalcTable(state: CanvasState, topLeft: Offset, dpi: Float, size: Float = 1f) {
        val rows = rows(state)
        val k = dpi * size
        val black = Color.Black
        val line = max(1f, dpi / 120f)
        val w = (LABEL_COL_IN + TEXT_COL_IN) * k
        val h = (HEAD_IN + rows.size * ROW_IN) * k
        drawRect(black, topLeft, Size(w, h), style = Stroke(line * 1.5f))
        val text = 0.11f * k
        drawPageText("STRUCTURAL CALCULATIONS", Offset(topLeft.x + 0.08f * k, topLeft.y + (HEAD_IN * k - text * 1.2f) / 2f), text * 1.05f, black, bold = true, maxWidth = w)
        var y = topLeft.y + HEAD_IN * k
        drawLine(black, Offset(topLeft.x, y), Offset(topLeft.x + w, y), strokeWidth = line * 1.5f)
        for ((label, value) in rows) {
            drawPageText(label.uppercase(), Offset(topLeft.x + 0.08f * k, y + (ROW_IN * k - text * 1.2f) / 2f), text * 0.9f, black, bold = true, maxWidth = LABEL_COL_IN * k)
            drawPageText(value, Offset(topLeft.x + (LABEL_COL_IN + 0.08f) * k, y + (ROW_IN * k - text * 1.2f) / 2f), text * 0.9f, black, bold = false, maxWidth = TEXT_COL_IN * k - 0.1f * k)
            y += ROW_IN * k
            drawLine(black, Offset(topLeft.x, y), Offset(topLeft.x + w, y), strokeWidth = line)
        }
        drawLine(black, Offset(topLeft.x + LABEL_COL_IN * k, topLeft.y + HEAD_IN * k), Offset(topLeft.x + LABEL_COL_IN * k, topLeft.y + h), strokeWidth = line)
    }
}
