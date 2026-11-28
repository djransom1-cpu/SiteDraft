package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.max

/**
 * A wing off the house (Dan, Nov 23 2026): the house becomes an L. Put against an exterior wall of the
 * first-floor plan, it gets
 *  - its own three 2x6 exterior walls (generated on the first-floor plan, so windows and doors go in them);
 *    the house wall where it joins comes out: the outside walls follow the outside of the foundation, and
 *    a wall there is a layout choice, drawn as an interior wall
 *  - its foundation: match the house, a crawl space or a slab. A basement wing's walls turn and follow it
 *    (the house basement wall comes out there); any other kind leaves the house basement wall as it is, and
 *    the wing's footings are at frost depth, stepping down to a basement footing with 4' lead walls
 *  - its floor: joists the short way or the same way as the house, blocking at 16" o.c. between the last
 *    joist and the rim along the walls that run with the joists (Dan, Nov 23); a slab wing gets a 4" slab
 *  - its roof: the ridge square to the house wall, tied into the house roof with valleys (Garages.roof3dWith),
 *    the far end a gable or a hip
 *  - its walls, foundation and roof on the exterior elevations.
 * Floor and plate line up with the house's (one story).
 */
object Wings {
    const val CATEGORY = "Wing"
    const val NAME = "Wing"
    val library = listOf(NAME)

    const val FOUND = "wgFound"     // 0 match the house, 1 crawl space, 2 slab
    const val END = "wgEnd"         // 0 match the house (a hip if the house has hip ends), 1 gable, 2 hip
    const val JOISTS = "wgJoists"   // 0 the short way, 1 the same way as the house
    /** Dan, Nov 26 (the Ransom House): 0 build its walls, 1 use the walls already drawn on the plan (a house as it stands). */
    const val WALLS = "wgWalls"
    /** 0 one story, 1 full height (the house's top plate, e.g. a two-story bump-out with its own gable). */
    const val FULL = "wgFull"
    /** One story's wall height, top of subfloor to top of plate, inches; 0 = the house's wall height. */
    const val WALL_H = "wgWallH"
    /** Its roof pitch, rise per 12; 0 = the house's. */
    const val PITCH = "wgPitch"
    val keys = setOf(FOUND, END, JOISTS, WALLS, FULL, WALL_H, PITCH)
    val foundations = listOf("Match the house", "Crawl space", "Slab")
    val ends = listOf("Match the house", "Gable", "Hip")
    val joistChoices = listOf("The short way", "Same way as the house")

    /** Plan wall thickness, as the house's exterior walls (2x6 and drywall). */
    const val WALL_IN = 6.5f
    const val SLAB_IN = 4f

    fun isWing(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY
    fun defaults(): Map<String, Float> = mapOf(FOUND to 0f, END to 0f, JOISTS to 0f)
    val wallChoices = listOf("Build its walls", "Walls drawn on the plan")
    val heightChoices = listOf("One story", "Full height")

    /** Does it use the walls already drawn on the plan (a house as it stands) instead of building its own. */
    fun drawnWalls(g: DrawingElement.Symbol): Boolean = prop(g, WALLS) == 1
    fun fullHeight(g: DrawingElement.Symbol): Boolean = prop(g, FULL) == 1

    /** The wings that stand for part of the drawn plan (the main house rectangle leaves them out). */
    fun drawnWallWings(state: CanvasState): List<DrawingElement.Symbol> = wings(state).filter { drawnWalls(it) }

    /** Top of the plate its roof bears on: the house's top plate when full height, else one story up. */
    fun plateIn(g: DrawingElement.Symbol, s: BuildingSettings): Float =
        if (fullHeight(g)) ExteriorElevation.plateIn(s)
        else s.datumIn + ((g.properties[WALL_H] ?: 0f).takeIf { it > 0f } ?: s.plateHeightIn)

    /** Its roof pitch, rise per 12. */
    fun pitchOf(g: DrawingElement.Symbol, s: BuildingSettings): Float = (g.properties[PITCH] ?: 0f).takeIf { it > 0f } ?: (s.roof?.pitch ?: 6f)
    fun prop(s: DrawingElement.Symbol, k: String): Int = (s.properties[k] ?: defaults()[k] ?: 0f).toInt()

    /** The id start of a wing's generated parts. */
    fun genKey(g: DrawingElement.Symbol) = "wg:${g.id}:"
    fun isWingPart(e: DrawingElement) = e.id.startsWith(DerivedLayers.GEN_PREFIX + "wg:")

    /** The wing's foundation kind (match = the house's). */
    fun foundationOf(g: DrawingElement.Symbol, s: BuildingSettings): FoundationType = when (prop(g, FOUND)) {
        1 -> FoundationType.CRAWL
        2 -> FoundationType.SLAB
        else -> s.foundation
    }

    fun wings(state: CanvasState): List<DrawingElement.Symbol> {
        val of = Layers.resolver(state)
        val placed = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { isWing(it) && !DerivedLayers.isGenerated(it) && of(it).id == Building.FLOOR1_PLAN }
        return placed.ifEmpty { implied(state) }
    }

    /** The id start of a wing worked out from the outline (never saved). */
    const val AUTO_PREFIX = "auto-wing:"
    fun isAuto(e: DrawingElement) = e.id.startsWith(AUTO_PREFIX)

    private var autoKey: Any? = null
    private var autoScale = 0f
    private var autoRoof: Any? = null
    private var auto: List<DrawingElement.Symbol> = emptyList()
    private var autoBoxes: List<Rect> = emptyList()

    /** What the main house leaves out for the wings on the plan's own walls: an automatic wing's whole box (its walls overlap the house's at a jog). */
    fun cutouts(state: CanvasState): List<Rect> {
        val d = drawnWallWings(state)
        return if (d.isNotEmpty() && d.all { isAuto(it) }) autoBoxes else d.map { it.boundingRect() }
    }

    /**
     * Wings worked out from the plan (Dan, Nov 28: Benjeman's roof came out one square over two offset boxes): a house
     * drawn wall by wall, with no 2x6 shell and no wings placed, whose outline is more than one box ([RoofBoxes]). The
     * biggest box is the main house; each other box is a wing on the walls already drawn, full height, its far end
     * matching the house (gable or hip). Worked out each time, never saved, so moving a wall moves the roof.
     */
    fun implied(state: CanvasState): List<DrawingElement.Symbol> {
        val s = state.building
        if (s.roof == null && s.gradeExposureIn == null) return emptyList()
        val k = state.inchesToPx(1f)
        if (autoKey === state.elements && autoScale == k && autoRoof === s.roof) return auto
        val of = Layers.resolver(state)
        val shell = state.elements.any { it is DrawingElement.Symbol && it.category == PlanSymbols.WALLS && it.symbolName == FloorPlan.EXTERIOR_WALL &&
            of(it).id == Building.FLOOR1_PLAN && !DerivedLayers.isGenerated(it) }
        // The main house as the roof will see it: the outside walls with the wing boxes taken out (as HouseOutline.mainBody);
        // each wing is then cut back to its face, so a wall's thickness of overlap at a jog doesn't keep it off the house.
        val boxes = if (shell) emptyList() else RoofBoxes.of(state)
        val extra = boxes.drop(1)
        val outside = HouseOutline.planWalls(state).filter { HouseOutline.isExterior(it, state) }
        val pieces = outside.flatMap { HouseOutline.minus(FloorPlan.bounds(it), extra) }.filter { it.width > 0.01f && it.height > 0.01f }
        val main = if (pieces.isEmpty()) null else Rect(pieces.minOf { it.left }, pieces.minOf { it.top }, pieces.maxOf { it.right }, pieces.maxOf { it.bottom })
        fun cut(b: Rect): Rect {
            main ?: return b
            val (side, _) = Porches.sideOf(b, main)
            return when (side) {
                Porches.Side.TOP -> if (b.bottom > main.top) Rect(b.left, b.top, b.right, main.top) else b
                Porches.Side.BOTTOM -> if (b.top < main.bottom) Rect(b.left, main.bottom, b.right, b.bottom) else b
                Porches.Side.LEFT -> if (b.right > main.left) Rect(b.left, b.top, main.left, b.bottom) else b
                Porches.Side.RIGHT -> if (b.left < main.right) Rect(main.right, b.top, b.right, b.bottom) else b
            }
        }
        val out = extra.map { cut(it) }.filter { it.width > 0f && it.height > 0f }.mapIndexed { i, b ->
            DrawingElement.Symbol(id = "$AUTO_PREFIX$i", symbolName = NAME, category = CATEGORY, center = b.center, size = b.size,
                color = state.currentColor, layerId = Building.FLOOR1_PLAN,
                properties = defaults() + mapOf(WALLS to 1f, FULL to 1f, "widthInches" to b.width / k, "depthInches" to b.height / k))
        }
        autoKey = state.elements; autoScale = k; autoRoof = s.roof; auto = out; autoBoxes = extra
        return out
    }

    /** The wing in its own frame: u along the house wall (pu1..pu2), v out from the house face (0..depth). */
    class Frame(val side: Porches.Side, val hf: Float, val pu1: Float, val pu2: Float, val depth: Float) {
        val alongX get() = side == Porches.Side.TOP || side == Porches.Side.BOTTOM
        fun world(u1: Float, u2: Float, v1: Float, v2: Float): Rect = when (side) {
            Porches.Side.TOP -> Rect(u1, hf - v2, u2, hf - v1)
            Porches.Side.BOTTOM -> Rect(u1, hf + v1, u2, hf + v2)
            Porches.Side.LEFT -> Rect(hf - v2, u1, hf - v1, u2)
            Porches.Side.RIGHT -> Rect(hf + v1, u1, hf + v2, u2)
        }
        /** A plan point's (u, v) in this frame. */
        fun uOf(p: Offset) = if (alongX) p.x else p.y
        fun vOf(p: Offset) = when (side) { Porches.Side.TOP -> hf - p.y; Porches.Side.BOTTOM -> p.y - hf; Porches.Side.LEFT -> hf - p.x; Porches.Side.RIGHT -> p.x - hf }
        /** The viewer looking at the wing's far end. */
        val endView get() = Porches.viewerSide(side)
    }

    fun frame(g: DrawingElement.Symbol, fp: Rect?, inch: Float): Frame? {
        val att = Garages.attachment(g, fp, inch) ?: return null
        val r = g.boundingRect()
        val (side, hf) = att
        val alongX = side == Porches.Side.TOP || side == Porches.Side.BOTTOM
        val depth = when (side) { Porches.Side.TOP -> hf - r.top; Porches.Side.BOTTOM -> r.bottom - hf; Porches.Side.LEFT -> hf - r.left; Porches.Side.RIGHT -> r.right - hf }
        return Frame(side, hf, if (alongX) r.left else r.top, if (alongX) r.right else r.bottom, depth)
    }

    /** The wing's three exterior walls on the first-floor plan: the far one owns the corners. */
    fun planWalls(g: DrawingElement.Symbol, fp: Rect?, inch: Float): List<Porches.Part> {
        if (drawnWalls(g)) return emptyList()
        val fr = frame(g, fp, inch) ?: return emptyList()
        val t = WALL_IN * inch
        if (fr.depth <= 2f * t || fr.pu2 - fr.pu1 <= 2f * t) return emptyList()
        fun part(key: String, rr: Rect) = Porches.Part(key, FloorPlan.EXTERIOR_WALL, PlanSymbols.WALLS, rr.center, rr.size)
        return listOf(
            part("w-out", fr.world(fr.pu1, fr.pu2, fr.depth - t, fr.depth)),
            part("w-a", fr.world(fr.pu1, fr.pu1 + t, 0f, fr.depth - t)),
            part("w-b", fr.world(fr.pu2 - t, fr.pu2, 0f, fr.depth - t))
        )
    }

    /** The stretch of house wall that comes out (world rect, the house wall's thickness), between the wing's inside faces. */
    fun joint(g: DrawingElement.Symbol, fp: Rect?, inch: Float): Rect? {
        if (drawnWalls(g)) return null
        val fr = frame(g, fp, inch) ?: return null
        val t = WALL_IN * inch
        return fr.world(fr.pu1 + t, fr.pu2 - t, -t, 0f)
    }

    /**
     * The wing on the plan: the house wall at the joint blanked (it comes out), the wing's wall faces (they're also
     * generated as walls, so this shows them with the builder off), its name, size and foundation.
     */
    fun DrawScope.drawWing(e: DrawingElement.Symbol, state: CanvasState, ink: Color) {
        val inch = state.inchesToPx(1f).takeIf { it > 0f } ?: return
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull()
        val r = e.boundingRect()
        val lineW = max(1f, inch * 0.6f)
        val ts = inch * 9f
        val fr = frame(e, fp, inch)
        if (fr == null) {
            drawRect(ink, r.topLeft, r.size, style = androidx.compose.ui.graphics.drawscope.Stroke(lineW,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(inch * 12f, inch * 6f))))
            val t = "WING: PUT IT AGAINST A HOUSE WALL"
            drawPageText(t, Offset(r.center.x - ts * 0.6f * t.length * 0.4f, r.center.y), ts * 0.8f, ink, bold = false, maxWidth = r.width)
            return
        }
        if (drawnWalls(e)) {
            // The walls are the plan's own: just say what this part of the house is.
            val txt = if (fullHeight(e)) "WING (FULL HEIGHT)" else "WING (ONE STORY)"
            val c = fr.world(fr.pu1, fr.pu2, 0f, fr.depth).center
            val size = ts * 0.7f
            val tw = size * 0.6f * txt.length
            if (tw < r.width * 0.95f) drawPageText(txt, Offset(c.x - tw / 2f, c.y + ts * 2f), size, ink, bold = false, maxWidth = r.width)
            return
        }
        val t = WALL_IN * inch
        joint(e, fp, inch)?.let { j -> drawRect(state.backgroundColor, j.topLeft, j.size) }
        fun seg(a: Offset, b: Offset) = drawLine(ink, a, b, strokeWidth = lineW)
        fun uv(u: Float, v: Float): Offset { val q = fr.world(u, u, v, v); return Offset(q.left, q.top) }
        // Outside faces, then inside faces (the side walls' inside faces run on to the house wall's inside face).
        seg(uv(fr.pu1, 0f), uv(fr.pu1, fr.depth)); seg(uv(fr.pu1, fr.depth), uv(fr.pu2, fr.depth)); seg(uv(fr.pu2, fr.depth), uv(fr.pu2, 0f))
        seg(uv(fr.pu1 + t, -t), uv(fr.pu1 + t, fr.depth - t)); seg(uv(fr.pu1 + t, fr.depth - t), uv(fr.pu2 - t, fr.depth - t)); seg(uv(fr.pu2 - t, fr.depth - t), uv(fr.pu2 - t, -t))
        val s = state.building
        val wFt = formatFeetInches((fr.pu2 - fr.pu1) / inch / 12f)
        val dFt = formatFeetInches(fr.depth / inch / 12f)
        val found = when (foundationOf(e, s)) {
            FoundationType.BASEMENT -> "BASEMENT (HOUSE BASEMENT WALL TURNS AND FOLLOWS THE WING)"
            FoundationType.CRAWL -> "CRAWL SPACE, FTGS AT ${Garages.FROST_IN.toInt()}\" FROST DEPTH"
            FoundationType.SLAB -> "SLAB ON GRADE, FTGS AT ${Garages.FROST_IN.toInt()}\" FROST DEPTH"
        }
        val lines = listOf("WING", "$wFt x $dFt", found)
        val c = fr.world(fr.pu1, fr.pu2, 0f, fr.depth).center
        lines.forEachIndexed { i, txt ->
            val size = if (i == 0) ts else ts * 0.7f
            val tw = size * 0.6f * txt.length
            if (tw < r.width * 0.95f) drawPageText(txt, Offset(c.x - tw / 2f, c.y - ts * 2f + i * ts * 1.3f), size, ink, bold = i == 0, maxWidth = r.width)
        }
    }

    /**
     * What holds it up. The job's foundation wall and footing all round the open sides; the far footing owns the corners.
     * Basement: at the basement footing, the side footings running in to the house footing's inside edge (the house
     * wall and footing between them come out: [houseCut]). Crawl or slab: at frost depth, the sides butting the house
     * footing, with lead walls stepping down where the house has a basement.
     */
    fun foundation(g: DrawingElement.Symbol, fp: Rect?, s: BuildingSettings, inch: Float): List<Porches.Part> {
        val fr = frame(g, fp, inch) ?: return emptyList()
        val type = foundationOf(g, s)
        val wall = s.foundationWall
        val t = wall.thicknessIn * inch
        val f = s.footingWidthIn * inch
        val p = Building.footingProjections(s).first * inch
        val out = mutableListOf<Porches.Part>()
        val thick = mapOf(Structure.THICK to s.footingThicknessIn)
        val drain = if (type != FoundationType.BASEMENT) 0f else if (s.drainTile == DrainTile.FORM_A_DRAIN) 1f else 2f
        fun part(key: String, name: String, cat: String, rr: Rect, props: Map<String, Float> = emptyMap()) =
            out.add(Porches.Part(key, name, cat, rr.center, rr.size, props))
        val basement = type == FoundationType.BASEMENT && s.foundation == FoundationType.BASEMENT
        val towardHouse = when (fr.side) { Porches.Side.TOP, Porches.Side.LEFT -> 1f; else -> -1f }
        val sideV1 = if (basement) -t else 0f
        part("w-out", wall.symbol, PlanSymbols.WALLS, fr.world(fr.pu1, fr.pu2, fr.depth - t, fr.depth))
        part("w-a", wall.symbol, PlanSymbols.WALLS, fr.world(fr.pu1, fr.pu1 + t, sideV1, fr.depth - t))
        part("w-b", wall.symbol, PlanSymbols.WALLS, fr.world(fr.pu2 - t, fr.pu2, sideV1, fr.depth - t))
        part("f-out", Structure.FOOTING, Structure.FOUNDATION, fr.world(fr.pu1 - p, fr.pu2 + p, fr.depth + p - f, fr.depth + p),
            thick + mapOf(Structure.JOIN to (Structure.JOIN_OWN * 4).toFloat(), Structure.INNER to towardHouse, Structure.DRAIN to drain))
        val v2 = fr.depth + p - f
        val joins = (Structure.JOIN_BUTT * 4).toFloat()
        val houseEnd = when (fr.side) { Porches.Side.TOP, Porches.Side.LEFT -> 2f; else -> 1f }
        val sideA = mapOf(Structure.JOIN to joins, Structure.INNER to 1f, Structure.DRAIN to drain)
        val sideB = mapOf(Structure.JOIN to joins, Structure.INNER to -1f, Structure.DRAIN to drain)
        fun sides(prefix: String, v1: Float, vEnd: Float, extra: Map<String, Float>) {
            part("$prefix-a", Structure.FOOTING, Structure.FOUNDATION, fr.world(fr.pu1 - p, fr.pu1 - p + f, v1, vEnd), thick + extra + sideA)
            part("$prefix-b", Structure.FOOTING, Structure.FOUNDATION, fr.world(fr.pu2 + p - f, fr.pu2 + p, v1, vEnd), thick + extra + sideB)
        }
        if (basement) {
            sides("f", p - f, v2, emptyMap())
        } else if (v2 > p) {
            if (s.foundation == FoundationType.BASEMENT && v2 - p > Garages.LEAD_WALL_IN * inch) {
                val vl = p + Garages.LEAD_WALL_IN * inch
                sides("lw", p, vl, mapOf(Structure.STEP to houseEnd))
                sides("f", vl, v2, emptyMap())
            } else sides("f", p, v2, emptyMap())
        }
        return out
    }

    /**
     * Basement wing on a basement house: the house foundation wall and footing that come out (world rects on the
     * house's wall line, between the wing's foundation walls / side footings), so the basement wall turns and follows.
     */
    fun houseCut(g: DrawingElement.Symbol, fp: Rect?, s: BuildingSettings, inch: Float): Pair<Rect, Rect>? {
        val fr = frame(g, fp, inch) ?: return null
        if (!(foundationOf(g, s) == FoundationType.BASEMENT && s.foundation == FoundationType.BASEMENT)) return null
        val t = s.foundationWall.thicknessIn * inch
        val f = s.footingWidthIn * inch
        val p = Building.footingProjections(s).first * inch
        val big = 1000f * inch
        return fr.world(fr.pu1 + t, fr.pu2 - t, -big, big) to fr.world(fr.pu1 - p + f, fr.pu2 + p - f, -big, big)
    }

    /**
     * The wing's floor: a 4" slab on a slab wing; else a joist bay inside its foundation walls (from the house
     * wall line), joists the short way or the house's way, with beams when the span is over Dan's limit.
     */
    fun framing(g: DrawingElement.Symbol, fp: Rect?, s: BuildingSettings, inch: Float, houseJoistsUpDown: Boolean): List<Porches.Part> {
        val fr = frame(g, fp, inch) ?: return emptyList()
        val type = foundationOf(g, s)
        if (type == FoundationType.SLAB) {
            val rr = fr.world(fr.pu1 + WALL_IN * inch, fr.pu2 - WALL_IN * inch, 0f, fr.depth - WALL_IN * inch)
            return listOf(Porches.Part("slab", Structure.SLAB, Structure.FRAMING, rr.center, rr.size, mapOf(Structure.THICK to SLAB_IN)))
        }
        val t = s.foundationWall.thicknessIn * inch
        val rr = fr.world(fr.pu1 + t, fr.pu2 - t, 0f, fr.depth - t)
        // Joists up and down the page (DIRECTION 1) or across it.
        val upDown = if (prop(g, JOISTS) == 1) houseJoistsUpDown else rr.height <= rr.width
        val spanIn = (if (upDown) rr.height else rr.width) / inch
        val beams = StructuralDesign.beamCount(spanIn, s.structure.maxJoistSpanFt)
        return listOf(Porches.Part("joists", Structure.JOIST_BAY, Structure.FRAMING, rr.center, rr.size, mapOf(
            Structure.SPACING to 16f, Structure.JOIST to Structure.joistCodeFor(s.joistDepthIn).toFloat(),
            Structure.DIRECTION to if (upDown) 1f else 0f, Structure.BEAMS to beams.toFloat(), Structure.BEAM_SECTION to -1f)))
    }

    /** A beam across the opening where the house basement wall comes out (Dan, Nov 23): its plan rect, section and posts. */
    data class JointBeam(val rect: Rect, val shape: SteelEngine.Shape?, val spanFt: Float, val posts: List<Offset>, val tribFt: Float)

    /** Bearing in each beam pocket, inches. */
    const val POCKET_IN = 4f

    /**
     * A basement wing on a basement house: the house floor joists that sat on the basement wall at the joint land on a
     * beam across the opening instead, bearing in pockets in the wing's side foundation walls, on the old wall line.
     * Sized like the main beam (StructuralDesign's candidates and deflection limit) for the floor over half the house
     * joist span, and half the wing joist span when the wing's joists run onto it. A post at mid-span only when nothing
     * makes it clear.
     */
    fun jointBeam(g: DrawingElement.Symbol, state: CanvasState, houseJoistSpanFt: Float, wingJoistSpanFt: Float): JointBeam? {
        val s = state.building
        val k = state.inchesToPx(1f).takeIf { it > 0f } ?: return null
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull()
        val fr = frame(g, fp, k) ?: return null
        if (!(foundationOf(g, s) == FoundationType.BASEMENT && s.foundation == FoundationType.BASEMENT)) return null
        val t = s.foundationWall.thicknessIn * k
        val clearIn = (fr.pu2 - fr.pu1 - 2f * t) / k
        if (clearIn <= 0f) return null
        val spanFt = (clearIn + POCKET_IN) / 12f
        val sp = s.structure
        val floors = 1 + s.upperFloors.size
        val trib = houseJoistSpanFt / 2f + wingJoistSpanFt / 2f
        val dl = sp.deadLoadPsf.toDouble() * floors
        val ll = sp.liveLoadPsf.toDouble() * floors
        val limit = sp.maxTotalDeflectionIn.toDouble()
        val candidates = StructuralDesign.beamCandidates(!StructuralDesign.w8Only(s))
        fun pick(span: Float) = candidates.firstOrNull { sh ->
            val r = SteelEngine.analyzeSteelBeam(sh, span.toDouble(), tribLeft_ft = trib / 2.0, tribRight_ft = trib / 2.0, dl_psf = dl, ll_psf = ll)
            r.isPass && (limit <= 0.0 || r.delta_total_in <= limit)
        }
        var shape = pick(spanFt)
        val posts = mutableListOf<Offset>()
        fun uv(u: Float, v: Float): Offset { val q = fr.world(u, u, v, v); return Offset(q.left, q.top) }
        if (shape == null) {
            shape = pick(spanFt / 2f)
            posts += uv((fr.pu1 + fr.pu2) / 2f, -t / 2f)
        }
        val bf = ((shape?.bf ?: 5.25).toFloat()) * k
        val u1 = fr.pu1 + t - POCKET_IN * k; val u2 = fr.pu2 - t + POCKET_IN * k
        val rect = fr.world(u1, u2, -t / 2f - bf / 2f, -t / 2f + bf / 2f)
        return JointBeam(rect, shape, spanFt, posts, trib)
    }

    /** Is the far end a hip: picked, or "match the house" on a house with a hip end. */
    fun hip(g: DrawingElement.Symbol, s: BuildingSettings): Boolean = when (prop(g, END)) {
        1 -> false
        2 -> true
        else -> s.roof?.let { it.endA == RoofEnd.HIP || it.endB == RoofEnd.HIP } ?: false
    }

    /** The wing roof's 3D lines: the ridge square to the house wall, into the house roof with valleys. */
    fun roof3d(g: DrawingElement.Symbol, state: CanvasState): List<Garages.Seg3> {
        val inch = state.inchesToPx(1f).takeIf { it > 0f } ?: return emptyList()
        val fr = frame(g, runCatching { RoofPlan.footprint(state) }.getOrNull(), inch) ?: return emptyList()
        return Garages.roof3dWith(g, state, !fr.alongX, hip(g, state.building))
    }

    /**
     * The part of the house roof the wing's roof covers, in the plan: from the house eave where each of its eaves
     * meets it (a side in line with a hip end reaches the eave corner) up to where its ridge meets the house roof,
     * half its width in. Null when the house roof doesn't slope toward it (a gable end).
     */
    fun houseRoofCover(g: DrawingElement.Symbol, state: CanvasState): List<Offset>? {
        val hr = state.building.roof ?: return null
        val k = state.inchesToPx(1f).takeIf { it > 0f } ?: return null
        val fr = frame(g, runCatching { RoofPlan.footprint(state) }.getOrNull(), k) ?: return null
        val across = runCatching { RoofPlan.geometry(state, hr) }.getOrNull()?.ridgeAcross ?: return null
        val eaveSide = across == fr.alongX
        val end = when (fr.side) { Porches.Side.LEFT, Porches.Side.TOP -> hr.endA; else -> hr.endB }
        if (!eaveSide && end != RoofEnd.HIP) return null
        val ov = hr.overhangIn * k
        val half = (fr.pu2 - fr.pu1) / 2f
        fun uv(u: Float, v: Float): Offset { val q = fr.world(u, u, v, v); return Offset(q.left, q.top) }
        // A side in line with a hip end (Dan, Nov 24): that slope is the hip end's plane, framed by this roof's members
        // out to the hip end's eave, so the house's hip-end jacks come out there too, down to where the ridge ends
        // (just inside the eave, which stays).
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull()
        val endsAlong = if (fr.alongX) fp?.let { it.left to it.right } else fp?.let { it.top to it.bottom }
        val flushA = eaveSide && hr.endA == RoofEnd.HIP && endsAlong != null && kotlin.math.abs(fr.pu1 - endsAlong.first) < k
        val flushB = eaveSide && hr.endB == RoofEnd.HIP && endsAlong != null && kotlin.math.abs(fr.pu2 - endsAlong.second) < k
        val inside = 0.75f * k
        return listOfNotNull(uv(fr.pu1 - ov + (if (flushA) inside else 0f), ov), uv(fr.pu2 + ov - (if (flushB) inside else 0f), ov), uv((fr.pu1 + fr.pu2) / 2f, -half),
            if (flushA) uv(fr.pu1 - ov + inside, -half) else null, if (flushB) uv(fr.pu2 + ov - inside, -half) else null)
    }

    /** The house roof's plan lines with what the wings' roofs cover taken out (eave, hip, rafters under them). */
    fun trimHouseRoof(lines: List<RoofPlan.Line>, state: CanvasState): List<RoofPlan.Line> {
        val k = state.inchesToPx(1f)
        var out = lines
        val garagesIn = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { Garages.isGarage(it) && !DerivedLayers.isGenerated(it) }
            .filter { ga ->
                val fr = frame(ga, runCatching { RoofPlan.footprint(state) }.getOrNull(), k) ?: return@filter false
                Garages.ridgeAlongX(ga, state) != fr.alongX   // its ridge runs into the house
            }
        for (wg in wings(state) + garagesIn) {
            val tri = houseRoofCover(wg, state) ?: continue
            // To a y-up frame for the counter-clockwise hull.
            val poly = convexHull(tri.map { it.x to -it.y }).map { Offset(it.first, it.second) }
            out = out.flatMap { l ->
                outside(Offset(l.a.x, -l.a.y), Offset(l.b.x, -l.b.y), poly).map { (a, b) -> RoofPlan.Line(Offset(a.x, -a.y), Offset(b.x, -b.y), l.kind) }
            }.filter { (it.b - it.a).getDistance() > k }   // no slivers left along the cover's edges

        }
        return out
    }

    /**
     * Is [o] (in [host]) in this wing's walls, for a wing on the plan's own walls: its middle inside the wing's
     * outline (a wall's thickness of slack), not on the house side, and [host] one of the plan's outside walls.
     */
    fun ownsOpening(g: DrawingElement.Symbol, o: DrawingElement.Symbol, host: DrawingElement.Symbol, state: CanvasState, k: Float, fr: Frame): Boolean {
        if (!HouseOutline.isExterior(host, state)) return false
        val a = Elevation.openingAnchor(o)
        val slack = 8f * k
        val r = g.boundingRect()
        if (a.x < r.left - slack || a.x > r.right + slack || a.y < r.top - slack || a.y > r.bottom + slack) return false
        // Not in the house wall it stands against.
        return fr.vOf(a) > slack
    }

    /** The wing on the plan's own walls that [o] belongs to, if any. */
    fun drawnWingOf(o: DrawingElement.Symbol, host: DrawingElement.Symbol, state: CanvasState, fp: Rect?, k: Float): DrawingElement.Symbol? =
        drawnWallWings(state).firstOrNull { g -> frame(g, fp, k)?.let { ownsOpening(g, o, host, state, k, it) } ?: false }

    /** Which elevation looks at each wall's outside face. */
    private fun faceOf(key: String, fr: Frame): ViewerSide = when (key) {
        "w-out" -> fr.endView
        "w-a" -> if (fr.alongX) ViewerSide.LEFT else ViewerSide.ABOVE
        else -> if (fr.alongX) ViewerSide.RIGHT else ViewerSide.BELOW
    }

    /**
     * The wing in an exterior elevation: the wall face on the foundation (siding, its windows and doors), the
     * foundation above grade, the rest and the footing dashed below, the roof. Not drawn from the far side of the
     * house. Returns the segments, labels and, in the view looking at its far end, the outline it hides the house behind.
     */
    fun elevation(g: DrawingElement.Symbol, state: CanvasState, view: ViewerSide, fp: Rect, k: Float): Triple<List<Porches.ESeg>, List<Porches.ELabel>, List<List<Pair<Float, Float>>>?> {
        val segs = mutableListOf<Porches.ESeg>(); val labels = mutableListOf<Porches.ELabel>()
        val fr = frame(g, fp, k) ?: return Triple(segs, labels, null)
        val gs = fr.endView
        val opposite = (gs == ViewerSide.ABOVE && view == ViewerSide.BELOW) || (gs == ViewerSide.BELOW && view == ViewerSide.ABOVE) ||
            (gs == ViewerSide.LEFT && view == ViewerSide.RIGHT) || (gs == ViewerSide.RIGHT && view == ViewerSide.LEFT)
        if (opposite) return Triple(segs, labels, null)
        val s = state.building
        val hr = s.roof
        fun line(x1: Float, e1: Float, x2: Float, e2: Float, p: ExteriorElevation.Pen = ExteriorElevation.Pen.MEDIUM) { segs += Porches.ESeg(x1, e1, x2, e2, p) }
        val r = g.boundingRect()
        val xs = listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).map { Elevation.alongOf(fp, view, it) / k }
        val xa = xs.min(); val xb = xs.max()
        val type = foundationOf(g, s)
        val fTop = if (type == FoundationType.SLAB) s.datumIn else Building.foundationTopIn(s)
        val grade = ExteriorElevation.gradeIn(s)
        val plate = plateIn(g, s)
        val pitch = pitchOf(g, s) / 12f
        val ov = hr?.overhangIn ?: 12f
        val eaveZ = plate + (hr?.heelIn ?: 4f) - ov * pitch
        val soffit = eaveZ - 6f
        val endOn = view == gs
        val gableEnd = endOn && !hip(g, s)
        val half = (fr.pu2 - fr.pu1) / k / 2f
        val mid = (xa + xb) / 2f
        val ridgeZ = plate + (hr?.heelIn ?: 4f) + half * pitch
        // The wall's top at x: the soffit, or up into the gable under the rake trim.
        fun topAt(x: Float): Float = if (gableEnd) minOf(ridgeZ - 6f - kotlin.math.abs(x - mid) * pitch, ridgeZ - 6f).coerceAtLeast(soffit) else minOf(plate, soffit)
        // Openings in the wing's own walls facing this view: (x1, e1, x2, e2) with casing.
        val holes = mutableListOf<Rect>()
        val key = DerivedLayers.GEN_PREFIX + genKey(g)
        val of = Layers.resolver(state)
        val levelById = state.levels.associateBy { it.id }
        for (o in state.elements.filterIsInstance<DrawingElement.Symbol>()) {
            val kind = Schedules.kindOf(o) ?: continue
            val host = OpeningPosition.hostWall(o, state) ?: continue
            if (drawnWalls(g)) {
                // The plan's own outside walls in the wing, facing this view.
                if (!ownsOpening(g, o, host, state, k, fr) || view !in HouseOutline.facing(host, state)) continue
            } else {
                if (!host.id.startsWith(key)) continue
                if (faceOf(host.id.removePrefix(key), fr) != view) continue
            }
            val c = Elevation.alongOf(fp, view, Elevation.openingAnchor(o)) / k
            val w = Schedules.widthAlongPx(o) / k
            val x1 = c - w / 2f; val x2 = c + w / 2f
            val base = levelById[of(o).levelId]?.elevationIn ?: s.datumIn
            if (kind == Schedules.Kind.WINDOW) {
                val sill = base + (o.properties[PlanSymbols.PROP_SILL_IN] ?: 36f)
                val head = sill + (o.properties[PlanSymbols.PROP_HEIGHT_IN] ?: 48f)
                line(x1, sill, x2, sill); line(x2, sill, x2, head); line(x2, head, x1, head); line(x1, head, x1, sill)
                line(x1, (sill + head) / 2f, x2, (sill + head) / 2f, ExteriorElevation.Pen.THIN)
                holes += Rect(x1 - 3.5f, sill - 1.5f, x2 + 3.5f, head + 3.5f)
            } else {
                val head = base + (o.properties[PlanSymbols.PROP_HEIGHT_IN] ?: state.building.doorHeadIn)
                line(x1, base, x2, base); line(x2, base, x2, head); line(x2, head, x1, head); line(x1, head, x1, base)
                holes += Rect(x1 - 3.5f, base, x2 + 3.5f, head + 3.5f)
            }
            val h = holes.last()
            line(h.left, h.top, h.right, h.top, ExteriorElevation.Pen.THIN); line(h.right, h.top, h.right, h.bottom, ExteriorElevation.Pen.THIN)
            line(h.right, h.bottom, h.left, h.bottom, ExteriorElevation.Pen.THIN); line(h.left, h.bottom, h.left, h.top, ExteriorElevation.Pen.THIN)
        }
        // The wall face.
        line(xa, fTop, xb, fTop, ExteriorElevation.Pen.MEDIUM)
        line(xa, fTop, xa, topAt(xa), ExteriorElevation.Pen.HEAVY); line(xb, fTop, xb, topAt(xb), ExteriorElevation.Pen.HEAVY)
        if (!gableEnd) line(xa, topAt(xa), xb, topAt(xb), ExteriorElevation.Pen.HEAVY)
        // The house's exterior finish (Dan, Nov 23: match the house): wainscot, main, the gable's own up a gable end.
        val fin = s.exterior ?: ExteriorFinish(main = WallFinish.LAP, mainIn = 6f, cornerTrimIn = 0f)
        ExteriorElevation.finishWall(fin, xa, xb, fTop, if (gableEnd) soffit + ov * pitch else topAt(mid), holes,
            if (gableEnd) ridgeZ - 6f else null, ::line)
        // The foundation: above grade, then dashed below to the footing.
        if (fTop > grade) { line(xa, grade, xa, fTop); line(xb, grade, xb, fTop) }
        val wallT = s.foundationWall.thicknessIn
        val p = (s.footingWidthIn - wallT) / 2f
        if (type == FoundationType.BASEMENT && s.foundation == FoundationType.BASEMENT) {
            val fgTop = Building.footingTopIn(s); val fgBot = Building.footingBottomIn(s)
            line(xa, minOf(grade, fTop), xa, fgTop, ExteriorElevation.Pen.HIDDEN); line(xb, minOf(grade, fTop), xb, fgTop, ExteriorElevation.Pen.HIDDEN)
            line(xa - p, fgBot, xb + p, fgBot, ExteriorElevation.Pen.HIDDEN); line(xb + p, fgBot, xb + p, fgTop, ExteriorElevation.Pen.HIDDEN)
            line(xb + p, fgTop, xa - p, fgTop, ExteriorElevation.Pen.HIDDEN); line(xa - p, fgTop, xa - p, fgBot, ExteriorElevation.Pen.HIDDEN)
        } else {
            val ftgTop = grade - Garages.FROST_IN + s.footingThicknessIn
            line(xa, minOf(grade, fTop), xa, ftgTop, ExteriorElevation.Pen.HIDDEN); line(xb, minOf(grade, fTop), xb, ftgTop, ExteriorElevation.Pen.HIDDEN)
            val houseX = if (!endOn && s.foundation == FoundationType.BASEMENT) {
                val hx = listOf(fp.topLeft, fp.bottomRight).map { Elevation.alongOf(fp, view, it) / k }
                if (xa in (hx.min() - 0.5f)..(hx.max() + 0.5f)) xa else xb
            } else null
            Garages.footingOnElevation(xa, xb, p, grade, s, houseX, ::line, labels)
        }
        line(xa - 24f, grade, xb + 24f, grade, ExteriorElevation.Pen.HEAVY)
        val roof = roof3d(g, state)
        // Seen from the side, the valley on the far side of the ridge is behind the roof (Dan's flush hip end: only that
        // one, so no valley line shows there at all).
        val farU = if (view == ViewerSide.LEFT || view == ViewerSide.ABOVE) fr.pu2 else fr.pu1
        val shown = if (endOn) roof else roof.filterNot { l ->
            l.kind == RoofPlan.Kind.HIP && listOf(l.a, l.b).any { p -> fr.vOf(p) < fr.depth / 2f && kotlin.math.abs(fr.uOf(p) - farU) < (ov + 1f) * k }
        }
        // A side in line with the house face: its eave runs on into the house's, no fascia end there.
        val nearU0 = if (view == ViewerSide.LEFT || view == ViewerSide.ABOVE) fr.pu1 else fr.pu2
        val houseEdge0 = if (fr.alongX) (if (nearU0 == fr.pu1) fp.left else fp.right) else (if (nearU0 == fr.pu1) fp.top else fp.bottom)
        val openEnds = if (!endOn && kotlin.math.abs(nearU0 - houseEdge0) < k)
            roof.filter { it.kind == RoofPlan.Kind.EAVE }.flatMap { listOf(it.a, it.b) }.filter { fr.vOf(it) < fr.depth / 2f }.map { Elevation.alongOf(fp, view, it) / k }
        else emptyList()
        Garages.roofOnElevation(shown, !fr.alongX, s, view, fp, k, ::line, roof, openEnds)
        if (!drawnWalls(g)) labels += Porches.ELabel("WING", mid, fTop + 18f)
        // What it hides the house behind, looking at its far end: the wall face and the roof outline above grade.
        // A side wall in line with the house face this view looks at: the wall face and the roof plane run on from the
        // house's, so the house's corner, eave end and hip behind the wing's outline are taken out too.
        val nearU = if (view == ViewerSide.LEFT || view == ViewerSide.ABOVE) fr.pu1 else fr.pu2
        val houseEdge = if (fr.alongX) (if (nearU == fr.pu1) fp.left else fp.right) else (if (nearU == fr.pu1) fp.top else fp.bottom)
        val flushNear = !endOn && kotlin.math.abs(nearU - houseEdge) < k
        // Two outlines, each convex: the wall face, and the roof with its fascia (together they're an L seen from the side).
        val hull = if (!endOn && !flushNear) null else {
            val wallPts = listOf(xa to grade, xb to grade, xa to topAt(xa), xb to topAt(xb))
            val roofPts = mutableListOf<Pair<Float, Float>>()
            roof.filter { it.kind != RoofPlan.Kind.MEMBER && it.kind != RoofPlan.Kind.WALL }.forEach { l ->
                roofPts += (Elevation.alongOf(fp, view, l.a) / k to l.za); roofPts += (Elevation.alongOf(fp, view, l.b) / k to l.zb)
                roofPts += (Elevation.alongOf(fp, view, l.a) / k to l.za - 6f); roofPts += (Elevation.alongOf(fp, view, l.b) / k to l.zb - 6f)
            }
            listOf(convexHull(wallPts), convexHull(roofPts)).filter { it.size >= 3 }
        }
        return Triple(segs, labels, hull)
    }

    /** Convex hull, counter-clockwise (monotone chain). */
    fun convexHull(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val pts = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (pts.size < 3) return pts
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = mutableListOf<Pair<Float, Float>>()
        for (p in pts) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0f) lower.removeAt(lower.size - 1); lower += p }
        val upper = mutableListOf<Pair<Float, Float>>()
        for (p in pts.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0f) upper.removeAt(upper.size - 1); upper += p }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /**
     * The parts of the segment a-b outside the convex polygon [poly] (counter-clockwise): what of the house still
     * shows beside a wing in front of it (Cyrus-Beck).
     */
    fun outside(a: Offset, b: Offset, poly: List<Offset>): List<Pair<Offset, Offset>> {
        if (poly.size < 3) return listOf(a to b)
        var t0 = 0f; var t1 = 1f
        val d = b - a
        for (i in poly.indices) {
            val p = poly[i]; val q = poly[(i + 1) % poly.size]
            val edge = q - p
            // Inward normal of a CCW polygon in a y-up frame; the caller keeps the orientation consistent.
            val n = Offset(-edge.y, edge.x)
            // A hair of slack, so a line lying along an edge (a hip on the cover's side) counts as covered.
            val nLen = n.getDistance().coerceAtLeast(1e-6f)
            val num = n.x * (a.x - p.x) + n.y * (a.y - p.y) + 0.5f * nLen
            val den = n.x * d.x + n.y * d.y
            if (kotlin.math.abs(den) < 1e-4f * nLen * d.getDistance().coerceAtLeast(1e-6f)) { if (num < 0f) return listOf(a to b); continue }
            val t = -num / den
            if (den > 0f) t0 = maxOf(t0, t) else t1 = minOf(t1, t)
            if (t0 >= t1) return listOf(a to b)
        }
        val out = mutableListOf<Pair<Offset, Offset>>()
        if (t0 > 1e-4f) out += a to (a + d * t0)
        if (t1 < 1f - 1e-4f) out += (a + d * t1) to b
        return out
    }
}
