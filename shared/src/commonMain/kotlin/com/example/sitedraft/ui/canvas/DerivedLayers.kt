package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.example.sitedraft.platform.randomId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Builds the foundation and first-floor framing layers from the first-floor plan (Nov 19 2026),
 * so drawing the plan once draws the layers under it.
 *
 * From the plan's walls (symbols in [PlanSymbols.WALLS] on the first-floor plan layer, square to
 * the page):
 *  - Foundation: an 8" block foundation wall under every exterior wall ([FloorPlan.EXTERIOR_WALL]),
 *    outside faces flush, with a continuous footing 6" past its outside face all the way round
 *    (projection, width and thickness from the building inputs).
 *  - First-floor framing: one joist bay over the footprint, joists run the short way at 16" o.c.,
 *    sized from the building's joist depth, with evenly spaced beams so no span is over 14'
 *    (ProCalc's floor layout). Posts on pad footings carry each beam at 8' o.c. at most.
 *  - Ladders: every interior wall that runs with the joists gets a ladder (the two joists either
 *    side of it, blocked at 16" o.c. from the wall's end so the blocks land under the studs;
 *    the code maximum is 48", R502.2). For a bearing wall that is the doubled, separated
 *    joists the code asks for.
 *  - Bearing walls ([Structure.BEARING]) across the joists that don't sit over a beam get a beam
 *    under them on posts and pad footings, so every bearing point comes down to a footing.
 *
 * Live until edited: every generated element has an id starting "gen:" and carries a hash of
 * itself as generated ([GEN_HASH]). Each sync rebuilds the generated set; an element whose hash
 * no longer matches was edited by hand and is left alone from then on, and one the user deleted
 * ([CanvasState.generatedIds] has it, the drawing doesn't) stays deleted. [rebuild] starts over.
 *
 * Only rectangular footprints frame correctly for now: the joist bay covers the outline of the
 * exterior walls. Slab-on-grade projects get no foundation or floor-framing layer, so nothing is
 * built for them.
 */
object DerivedLayers {
    const val GEN_PREFIX = "gen:"
    const val GEN_HASH = "genHash"
    /** Longest joist span before another beam goes in, inches (14'). */
    const val MAX_SPAN_IN = 168f
    /** Longest beam span between posts, inches (8'). */
    const val MAX_POST_SPACING_IN = 96f
    const val FOUNDATION_WALL = "8\" Block Wall"
    const val PAD_IN = 24f
    /** A bearing wall within this of a beam line is carried by that beam. */
    const val ON_BEAM_IN = 12f

    fun isGenerated(e: DrawingElement) = e.id.startsWith(GEN_PREFIX)

    /**
     * Anchor bolt positions along a sill [lengthIn] long, inches from one end: [endIn] in from
     * each end, then evenly spaced between no more than [spacingIn] apart.
     */
    fun anchorBolts(lengthIn: Float, endIn: Float, spacingIn: Float): List<Float> {
        if (lengthIn <= 0f || spacingIn <= 0f) return emptyList()
        if (lengthIn <= 2f * endIn) return listOf(lengthIn / 2f)
        val run = lengthIn - 2f * endIn
        val gaps = ceil(run / spacingIn - 1e-4f).toInt().coerceIn(1, 1000)
        return (0..gaps).map { endIn + run * it / gaps }
    }

    private fun hashOf(s: DrawingElement.Symbol): Float {
        fun r(v: Float) = (v * 100f).roundToInt()
        val props = s.properties.filterKeys { it != GEN_HASH }.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${r(it.value)}" }
        return listOf(s.symbolName, s.category, s.layerId, r(s.center.x), r(s.center.y), r(s.size.width), r(s.size.height), r(s.rotation), props)
            .joinToString("|").hashCode().toFloat()
    }

    private fun stamped(s: DrawingElement.Symbol) = s.copy(properties = s.properties + (GEN_HASH to hashOf(s)))

    /** True when [e] is generated and still exactly as it was generated. */
    fun isUntouched(e: DrawingElement): Boolean =
        e is DrawingElement.Symbol && isGenerated(e) && e.properties[GEN_HASH] == hashOf(e)

    /** Brings the generated layers in line with the plan. Returns [state] itself when nothing changes. */
    fun sync(state: CanvasState): CanvasState {
        // The floors above (UpperFloors, Nov 27): their outside walls and the stair coming up, whether or not the
        // layers under the plan are built from it.
        val upper = UpperFloors.parts(state)
        if (!state.building.autoLayers && upper.isEmpty() && state.elements.none { it.id.startsWith(GEN_PREFIX + "up") }) return state
        // Stairs open the floor framing at their top (Stairs.floorOpening, Nov 24).
        // Section and detail markers go where their drawings belong once the drawings are in the model (CalloutsAuto, Nov 24).
        val desired = if (!state.building.autoLayers) (upper + Guards.forStairs(state)).map { stamped(it) } else {
            // The upper floors' walls as they'll be, so their joists are framed in the same pass (Nov 27).
            val framedOn = state.copy(elements = state.elements.filter { !it.id.startsWith(GEN_PREFIX + "up") } + upper)
            val parts = planParts(framedOn)
            (parts + Stairs.floorOpenings(state) + Guards.forStairs(state) + CalloutsAuto.forPlan(state, parts) + upper).map { stamped(it) }
        }
        val current = state.elements.filter { isGenerated(it) }
        val currentIds = current.map { it.id }.toSet()
        val suppressed = state.suppressedGenIds + (state.generatedIds - currentIds)
        // Dan (Nov 22): a change that leaves hand-edited structure short updates it too. A beam set by
        // hand that no longer passes takes the designed shape; a pad or post smaller than designed grows
        // to the designed size. Everything else about the edit stays (it remains an edit).
        val byId = desired.associateBy { it.id }
        val edited = current.filter { !isUntouched(it) }.map { e ->
            val want = byId[e.id]
            if (e !is DrawingElement.Symbol || want == null) return@map e
            when (e.symbolName) {
                Structure.JOIST_BAY -> {
                    val mine = SteelEngine.AISC_DATABASE.getOrNull((e.properties[Structure.BEAM_SECTION] ?: -1f).toInt())
                    val designed = want.properties[Structure.BEAM_SECTION] ?: -1f
                    if (mine != null && designed >= 0f && !StructuralDesign.beamPasses(state, mine))
                        e.copy(properties = e.properties + (Structure.BEAM_SECTION to designed)) else e
                }
                Structure.PAD, Structure.POST ->
                    if (e.size.width < want.size.width - 0.5f || e.size.height < want.size.height - 0.5f)
                        e.copy(size = androidx.compose.ui.geometry.Size(maxOf(e.size.width, want.size.width), maxOf(e.size.height, want.size.height)),
                            properties = e.properties + (want.properties[Structure.THICK]?.let { t -> mapOf(Structure.THICK to maxOf(t, e.properties[Structure.THICK] ?: 0f)) } ?: emptyMap()))
                    else e
                else -> e
            }
        }
        val editedIds = edited.map { it.id }.toSet()
        val fresh = desired.filter { it.id !in suppressed && it.id !in editedIds }
        // Generated parts draw first, under the plan.
        val elements = edited + fresh + state.elements.filter { !isGenerated(it) }
        val generatedIds = editedIds + fresh.map { it.id }
        if (elements == state.elements && generatedIds == state.generatedIds && suppressed == state.suppressedGenIds) return state
        return state.copy(elements = elements, generatedIds = generatedIds, suppressedGenIds = suppressed)
    }

    /**
     * Match floor plan: the parts for [layerId] (the foundation, or a floor's framing) drawn once
     * as ordinary elements - not live, edit them like anything you drew. Empty when the plan has
     * no exterior walls or the layer isn't one the plan builds.
     */
    fun matchLayer(state: CanvasState, layerId: String): List<DrawingElement.Symbol> =
        (planParts(state) + Stairs.floorOpenings(state) + Guards.forStairs(state)).filter { it.layerId == layerId }.map { it.copy(id = randomId()) }

    /** Id prefix (after [GEN_PREFIX]) of the parts built for the new area of a remodel. */
    const val NEW_AREA = "na:"

    /** The exterior walls that count for a remodel: hand-drawn 2x6 walls not coming out. */
    private fun remodelExterior(state: CanvasState) = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
        it.category == PlanSymbols.WALLS && it.symbolName == FloorPlan.EXTERIOR_WALL && !isGenerated(it) && Remodel.status(it) != WallStatus.DEMO
    }

    /**
     * What the plan builds. A remodel with both Existing and New exterior walls (Dan, Nov 24: new walls added to an
     * existing house) is built twice: the existing house on its own (its foundation, drawn Existing, as [asExisting]),
     * and the new area on its own (footings and foundation walls under the new walls, its own joist bay, beam, posts
     * and pads, sized to it), so the existing house isn't framed as new. The new area's parts carry [NEW_AREA] in
     * their ids; garages, porches and wings go with the existing house. Otherwise everything as one building.
     */
    fun planParts(state: CanvasState): List<DrawingElement.Symbol> {
        val ext = remodelExterior(state)
        val existing = ext.filter { Remodel.status(it) == WallStatus.EXISTING }
        val fresh = ext.filter { Remodel.status(it) != WallStatus.EXISTING }
        if (existing.isEmpty() || fresh.isEmpty()) return asExisting(build(state), state)
        val freshIds = fresh.map { it.id }.toSet()
        val existingIds = existing.map { it.id }.toSet()
        val house = asExisting(build(state.copy(elements = state.elements.filter { it.id !in freshIds })), state, onlyExisting = true)
        val additions = listOf("ga", "pc", "wg", "rim:wg")
        val newArea = build(state.copy(elements = state.elements.filter { it.id !in existingIds && !Wings.isWing(it) && !Garages.isGarage(it) && !Porches.isPorch(it) }))
            .filter { p -> additions.none { p.id.removePrefix(GEN_PREFIX).startsWith(it) } }
            .map { it.copy(id = GEN_PREFIX + NEW_AREA + it.id.removePrefix(GEN_PREFIX)) }
        return house + newArea
    }

    /**
     * Remodel (Dan, Nov 24): when every exterior wall is Existing, the existing house gets no floor
     * framing (framing plans are for new remodeled areas and new work): its joists, beams, posts,
     * pads, ladders and anchor bolts are left out. Its foundation walls and footings stay, drawn
     * Existing, for new work to tie into. Wings, garages and porches (additions) are built in full, New.
     */
    private fun asExisting(parts: List<DrawingElement.Symbol>, state: CanvasState, onlyExisting: Boolean = false): List<DrawingElement.Symbol> {
        val ext = remodelExterior(state)
        if (!onlyExisting && (ext.isEmpty() || ext.any { Remodel.status(it) != WallStatus.EXISTING })) return parts
        val additions = listOf("ga", "pc", "wg", "rim:wg")
        // Kept and drawn Existing: the foundation always; with "Show existing framing" (Dan: an option,
        // existing houses are laid out their own way) the joist bay, beams, posts and pads too.
        val keptExisting = listOf("fw:", "ftg:") +
            if (state.building.showExistingFraming) listOf("bay:", "bbeam", "jbeam", "post", "pad") else emptyList()
        return parts.mapNotNull { p ->
            val key = p.id.removePrefix(GEN_PREFIX)
            when {
                additions.any { key.startsWith(it) } -> p
                keptExisting.any { key.startsWith(it) } -> p.copy(properties = p.properties + (Remodel.PROP_STATUS to WallStatus.EXISTING.code))
                else -> null
            }
        }
    }

    /** Throws away every generated part, edited or deleted ones included, and builds them again. */
    fun rebuild(state: CanvasState): CanvasState =
        sync(state.copy(elements = state.elements.filter { !isGenerated(it) }, generatedIds = emptySet(), suppressedGenIds = emptySet()))

    /** Removes every generated part that is still as generated (switching the builder off). */
    fun clearUntouched(state: CanvasState): CanvasState =
        state.copy(elements = state.elements.filter { !isUntouched(it) }, generatedIds = emptySet(), suppressedGenIds = emptySet())

    private fun isSquare(rotation: Float): Boolean {
        val r = ((rotation % 90f) + 90f) % 90f
        return r < 0.5f || r > 89.5f
    }

    /** A wall's rectangle on the page (walls taller than wide run up-down). */
    private fun wallRect(w: DrawingElement.Symbol): Rect {
        val turned = abs((((w.rotation % 180f) + 180f) % 180f) - 90f) < 1f
        val ww = if (turned) w.size.height else w.size.width
        val hh = if (turned) w.size.width else w.size.height
        return Rect(w.center.x - ww / 2f, w.center.y - hh / 2f, w.center.x + ww / 2f, w.center.y + hh / 2f)
    }

    /** Everything the plan calls for, before stamping. */
    fun build(state: CanvasState): List<DrawingElement.Symbol> {
        val s = state.building
        val wingWalls = wingPlanWalls(state)
        if (s.foundation == FoundationType.SLAB) return wingWalls
        val layers = state.effectiveLayers.map { it.id }.toSet()
        val foundationLayer = Building.FOUNDATION.takeIf { it in layers }
        val structuralLayer = "${Building.FLOOR1}-structural".takeIf { it in layers }
        val inch = state.inchesToPx(1f)
        if (inch <= 0f) return wingWalls

        val walls = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.WALLS && !isGenerated(it) && isSquare(it.rotation) &&
                Layers.layerOf(it, state).id == Building.FLOOR1_PLAN &&
                // A wall coming out (Remodel) gets no ladder, footing or framing of its own.
                Remodel.status(it) != WallStatus.DEMO
        }
        // Exterior walls belong on the first-floor plan; ones moved to another layer by mistake
        // (Nov 19 2026: a plan's 2x6 walls ended up on Foundation) still count when the plan has none.
        val exterior = walls.filter { it.symbolName == FloorPlan.EXTERIOR_WALL }.ifEmpty {
            state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
                it.category == PlanSymbols.WALLS && it.symbolName == FloorPlan.EXTERIOR_WALL && !isGenerated(it) && isSquare(it.rotation)
            }
        }
        if (exterior.isEmpty()) return wingWalls
        val footprint = exterior.map { wallRect(it) }.reduce { a, r ->
            Rect(minOf(a.left, r.left), minOf(a.top, r.top), maxOf(a.right, r.right), maxOf(a.bottom, r.bottom))
        }
        val out = mutableListOf<DrawingElement.Symbol>()
        out += wingWalls
        val color = exterior.first().color

        fun symbol(id: String, name: String, category: String, center: Offset, size: Size, layer: String?, props: Map<String, Float> = emptyMap()) =
            DrawingElement.Symbol(
                id = GEN_PREFIX + id, symbolName = name, category = category, center = center, size = size, color = color,
                properties = mapOf("widthInches" to size.width / inch, "depthInches" to size.height / inch) + props, layerId = layer
            )

        // ---- Foundation walls and footings under the exterior walls ----
        if (foundationLayer != null) {
            val t = Building.foundationWallIn(s) * inch
            val f = s.footingWidthIn * inch
            // The footing runs past the wall's outside face all the way round, corners included:
            // centred under the wall unless set (Nov 23, the code rule), [footingWidthIn] wide.
            val p = Building.footingProjections(s).first * inch
            for (ew in exterior) {
                val r = wallRect(ew)
                val vertical = r.height > r.width
                val center: Offset
                val wallSize: Size
                val footCenter: Offset
                val footSize: Size
                // One continuous footing round the house (Dan, Nov 22): the long sides run to the outside
                // corners and own them; the short sides stop against them, no overlaps. An end away from a
                // corner (a side built of several walls) just meets the next piece.
                val tol = t + 2f * inch
                val joinStart: Int
                val joinEnd: Int
                val innerSign: Float
                if (!vertical) {
                    val outsideTop = r.center.y < footprint.center.y
                    val face = if (outsideTop) r.top else r.bottom
                    val inward = if (outsideTop) 1f else -1f
                    center = Offset(r.center.x, face + inward * t / 2f)
                    wallSize = Size(r.width, t)
                    val atStart = abs(r.left - footprint.left) < tol
                    val atEnd = abs(r.right - footprint.right) < tol
                    val x1 = if (atStart) footprint.left - p else r.left
                    val x2 = if (atEnd) footprint.right + p else r.right
                    footCenter = Offset((x1 + x2) / 2f, face + inward * (f / 2f - p))
                    footSize = Size(x2 - x1, f)
                    joinStart = if (atStart) Structure.JOIN_OWN else 0
                    joinEnd = if (atEnd) Structure.JOIN_OWN else 0
                    innerSign = inward
                } else {
                    val outsideLeft = r.center.x < footprint.center.x
                    val face = if (outsideLeft) r.left else r.right
                    val inward = if (outsideLeft) 1f else -1f
                    center = Offset(face + inward * t / 2f, r.center.y)
                    wallSize = Size(t, r.height)
                    val atStart = abs(r.top - footprint.top) < tol
                    val atEnd = abs(r.bottom - footprint.bottom) < tol
                    val y1 = if (atStart) footprint.top - p + f else r.top
                    val y2 = if (atEnd) footprint.bottom + p - f else r.bottom
                    footCenter = Offset(face + inward * (f / 2f - p), (y1 + y2) / 2f)
                    footSize = Size(f, (y2 - y1).coerceAtLeast(inch))
                    joinStart = if (atStart) Structure.JOIN_BUTT else 0
                    joinEnd = if (atEnd) Structure.JOIN_BUTT else 0
                    innerSign = inward
                }
                out += symbol("fw:${ew.id}", s.foundationWall.symbol, PlanSymbols.WALLS, center, wallSize, foundationLayer)
                // Anchor bolts down the middle of the wall: [anchorBoltEndIn] from each end, then
                // evenly spaced no more than [anchorBoltSpacingIn] apart (Dan: 12", 32").
                val len = if (vertical) wallSize.height else wallSize.width
                for ((i, a) in anchorBolts(len / inch, s.anchorBoltEndIn, s.anchorBoltSpacingIn).withIndex()) {
                    val along = a * inch - len / 2f
                    val at = if (vertical) Offset(center.x, center.y + along) else Offset(center.x + along, center.y)
                    out += symbol("ab:${ew.id}:$i", Structure.ANCHOR_BOLT, Structure.FOUNDATION, at, Size(3f * inch, 3f * inch), foundationLayer)
                }
                out += symbol("ftg:${ew.id}", Structure.FOOTING, Structure.FOUNDATION, footCenter, footSize, foundationLayer,
                    mapOf(Structure.THICK to s.footingThicknessIn,
                        Structure.JOIN to (joinStart + 3 * joinEnd).toFloat(), Structure.INNER to innerSign,
                        // Drain tile on basement footings (Dan): Form-A-Drain unless picked otherwise.
                        Structure.DRAIN to if (s.foundation != FoundationType.BASEMENT) 0f else if (s.drainTile == DrainTile.FORM_A_DRAIN) 1f else 2f))
            }
            // Porches on the first-floor plan (Porches, Nov 23): their footings, walls, piers and posts.
            val porches = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
                Porches.isPorch(it) && !isGenerated(it) && Layers.layerOf(it, state).id == Building.FLOOR1_PLAN
            }
            for (pc in porches) for (part in Porches.foundation(pc, footprint, s, inch)) {
                out += symbol("pc:${pc.id}:${part.key}", part.name, part.category, part.center, part.size, foundationLayer, part.props)
            }
            // Garages (Garages, Nov 23): frost walls and footings, lead walls down to a basement footing.
            for (ga in state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
                Garages.isGarage(it) && !isGenerated(it) && Layers.layerOf(it, state).id == Building.FLOOR1_PLAN
            }) for (part in Garages.foundation(ga, footprint, s, inch)) {
                out += symbol("ga:${ga.id}:${part.key}", part.name, part.category, part.center, part.size, foundationLayer, part.props)
            }
            // Wings (Wings, Nov 23): their foundation; a basement wing takes the house basement wall and footing out
            // where it joins (Dan: the basement wall turns and follows the wing). Other kinds leave the house's as is.
            for (wg in Wings.wings(state)) {
                for (part in Wings.foundation(wg, footprint, s, inch)) {
                    out += symbol("${Wings.genKey(wg)}fd:${part.key}", part.name, part.category, part.center, part.size, foundationLayer, part.props)
                }
                val cut = Wings.houseCut(wg, footprint, s, inch) ?: continue
                val keep = out.filter { e -> !(e.id.startsWith(GEN_PREFIX + "fw:") || e.id.startsWith(GEN_PREFIX + "ftg:") || e.id.startsWith(GEN_PREFIX + "ab:")) }
                val changed = out.filter { it !in keep }.flatMap { e ->
                    val r = Rect(e.center.x - e.size.width / 2f, e.center.y - e.size.height / 2f, e.center.x + e.size.width / 2f, e.center.y + e.size.height / 2f)
                    val c = if (e.id.startsWith(GEN_PREFIX + "ftg:")) cut.second else cut.first
                    if (!r.overlaps(c)) return@flatMap listOf(e)
                    if (e.id.startsWith(GEN_PREFIX + "ab:")) return@flatMap emptyList()
                    cutOut(e, r, c, inch)
                }
                out.clear(); out += keep + changed
            }
        }

        // ---- The floors above: their joists (Dan, Nov 27: "2nd floor structural isn't putting joists in") ----
        out += upperFloorFraming(state, color)

        // ---- First-floor framing ----
        if (structuralLayer == null) return out
        val joistsUpDown = footprint.width >= footprint.height // joists span the short way
        val spanIn = (if (joistsUpDown) footprint.height else footprint.width) / inch
        // Dan's rule (Nov 22): as few beams as keep each joist span within his limit (15'): one
        // center beam up to 30' deep. Sized, with the posts and pads, by StructuralDesign.
        val beams = StructuralDesign.beamCount(spanIn, s.structure.maxJoistSpanFt)
        val design = StructuralDesign.design(state)
        val main = design?.beams?.firstOrNull()
        val sectionIndex = main?.shape?.let { sh -> SteelEngine.AISC_DATABASE.indexOf(sh) } ?: -1
        val bay = symbol("bay:floor1", Structure.JOIST_BAY, Structure.FRAMING, footprint.center, footprint.size, structuralLayer,
            mapOf(
                Structure.SPACING to 16f,
                Structure.JOIST to Structure.joistCodeFor(s.joistDepthIn).toFloat(),
                Structure.DIRECTION to if (joistsUpDown) 1f else 0f,
                Structure.BEAMS to beams.toFloat(),
                Structure.BEAM_SECTION to sectionIndex.toFloat()
            ))
        out += bay
        val layout = Structure.bayLayout(bay, bay.size.width, bay.size.height, inch)
        // Bay frame -> page: along is x when the joists run across, y when they run up and down.
        fun page(along: Float, cross: Float) =
            if (layout.alongX) footprint.center + Offset(along, cross) else footprint.center + Offset(cross, along)

        /**
         * Posts on pads along a line across the joists at [along], from [c1] to [c2] (cross, bay frame).
         * [spans] given: that many equal spans (the main beam, Dan's 12'-apart rule), pads and posts as
         * designed; otherwise the old 8'-apart rule with 24" pads (a beam under a bearing wall).
         */
        fun postsUnder(key: String, along: Float, c1: Float, c2: Float, spans: Int? = null) {
            if (foundationLayer == null) return
            val lenIn = (c2 - c1) / inch
            val n = if (spans != null) spans - 1 else (ceil(lenIn / MAX_POST_SPACING_IN).toInt() - 1).coerceAtLeast(0)
            val padIn = if (spans != null) main?.padIn ?: PAD_IN else PAD_IN
            val thickIn = if (spans != null) main?.padThickIn ?: 12f else 12f
            val postIn = if (spans != null) main?.post?.d?.toFloat() ?: 5.5f else 5.5f
            for (j in 1..n) {
                val c = c1 + (c2 - c1) * j / (n + 1)
                val at = page(along, c)
                out += symbol("pad:$key:$j", Structure.PAD, Structure.FOUNDATION, at, Size(padIn * inch, padIn * inch), foundationLayer,
                    mapOf(Structure.THICK to thickIn))
                out += symbol("post:$key:$j", Structure.POST, Structure.FRAMING, at, Size(postIn * inch, postIn * inch), foundationLayer)
            }
        }
        // The main beams run between the foundation walls (inside faces): the posts as designed, evenly spaced, and
        // a pad under every post on the line, sized for its own load (posts moved or added by hand included).
        val wallIn = Building.foundationWallIn(s) * inch
        layout.beams.forEachIndexed { i, b ->
            val db = design?.beams?.getOrNull(i)
            if (db == null) {
                postsUnder("b$i", b, -layout.width / 2f + wallIn, layout.width / 2f - wallIn,
                    spans = StructuralDesign.postSpans((layout.width - 2f * wallIn) / inch / 12f, StructuralDesign.spacingFt(s.structure)))
                return@forEachIndexed
            }
            if (foundationLayer == null) return@forEachIndexed
            val c1 = -layout.width / 2f + wallIn
            val c2 = layout.width / 2f - wallIn
            val postIn = db.post?.d?.toFloat() ?: 5.5f
            for (j in 1 until db.designedSpans) {
                val at = page(b, c1 + (c2 - c1) * j / db.designedSpans)
                out += symbol("post:b$i:$j", Structure.POST, Structure.FRAMING, at, Size(postIn * inch, postIn * inch), foundationLayer)
            }
            for (spot in db.posts) {
                val padId = if (spot.id.startsWith(GEN_PREFIX + "post:")) "pad:" + spot.id.removePrefix(GEN_PREFIX + "post:") else "pad:hand:${spot.id}"
                out += symbol(padId, Structure.PAD, Structure.FOUNDATION, spot.center, Size(spot.padIn * inch, spot.padIn * inch), foundationLayer,
                    mapOf(Structure.THICK to spot.padThickIn))
            }
        }

        // Blocking between the last joist and the rim along the outside walls that run with the joists (Dan, Nov 23):
        // 16" o.c., matching the stud layout above.
        out += rimLadders("house", footprint.center, layout, inch, structuralLayer, color, skipEdge = null)
        // Joist lines (cross positions in the bay frame), edges included.
        val joistLines = (listOf(-layout.width / 2f) + layout.joists + listOf(layout.width / 2f)).sorted()
        for (w in walls) {
            if (w.symbolName == FloorPlan.EXTERIOR_WALL) continue
            val r = wallRect(w)
            if (!footprint.overlaps(r)) continue
            val wallUpDown = r.height > r.width
            val parallel = wallUpDown == !layout.alongX
            val bearing = (w.properties[Structure.BEARING] ?: 0f) >= 0.5f
            // Wall centre and extent in the bay frame.
            val d = r.center - footprint.center
            val wallAlong = if (layout.alongX) d.x else d.y
            val wallCross = if (layout.alongX) d.y else d.x
            val wallLen = if (wallUpDown) r.height else r.width
            if (parallel) {
                // Ladder: the joist at or just before the wall's centre line and the next one.
                val i = joistLines.indexOfLast { it <= wallCross + 0.01f }.coerceIn(0, joistLines.size - 2)
                val a = joistLines[i]
                val b = joistLines[i + 1]
                val crossMid = (a + b) / 2f
                val size = if (layout.alongX) Size(wallLen, b - a) else Size(b - a, wallLen)
                out += symbol("ladder:${w.id}", Structure.LADDER, Structure.FRAMING, page(wallAlong, crossMid), size, structuralLayer,
                    mapOf(Structure.BLOCKS to Structure.DEFAULT_LADDER_BLOCKS_IN))
            } else if (bearing) {
                // Across the joists: carried by a beam if it sits over one, otherwise a beam of its own.
                val onBeam = layout.beams.any { abs(it - wallAlong) <= ON_BEAM_IN * inch }
                if (!onBeam) {
                    val size = if (layout.alongX) Size(4.5f * inch, wallLen) else Size(wallLen, 4.5f * inch)
                    out += symbol("bbeam:${w.id}", Structure.BEAM, Structure.FRAMING, page(wallAlong, wallCross), size, structuralLayer)
                    postsUnder("w${w.id}", wallAlong, wallCross - wallLen / 2f, wallCross + wallLen / 2f)
                    // Posts at both ends too, unless an end lands on the foundation wall.
                    if (foundationLayer != null) for ((k, c) in listOf(wallCross - wallLen / 2f, wallCross + wallLen / 2f).withIndex()) {
                        if (abs(abs(c) - layout.width / 2f) < 12f * inch) continue
                        val at = page(wallAlong, c)
                        out += symbol("pad:w${w.id}:e$k", Structure.PAD, Structure.FOUNDATION, at, Size(PAD_IN * inch, PAD_IN * inch), foundationLayer,
                            mapOf(Structure.THICK to 12f))
                        out += symbol("post:w${w.id}:e$k", Structure.POST, Structure.FRAMING, at, Size(5.5f * inch, 5.5f * inch), foundationLayer)
                    }
                }
            }
        }
        // The porches' floors (Porches.framing, Nov 23): joists off the house, or a masonry porch's cap.
        for (pc in state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            Porches.isPorch(it) && !isGenerated(it) && Layers.layerOf(it, state).id == Building.FLOOR1_PLAN
        }) for (part in Porches.framing(pc, footprint, s, inch)) {
            out += symbol("pcf:${pc.id}:${part.key}", part.name, part.category, part.center, part.size, structuralLayer, part.props)
        }
        for (ga in state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            Garages.isGarage(it) && !isGenerated(it) && Layers.layerOf(it, state).id == Building.FLOOR1_PLAN
        }) Garages.slab(ga, inch, Garages.sharedWall(ga, state)).let { part ->
            out += symbol("gaf:${ga.id}:${part.key}", part.name, part.category, part.center, part.size, structuralLayer, part.props)
        }
        // Wings' floors (Wings.framing): a joist bay inside the foundation walls with its rim blocking and any beam
        // on posts and pads (8' apart), or a slab.
        for (wg in Wings.wings(state)) for (part in Wings.framing(wg, footprint, s, inch, joistsUpDown)) {
            val sym = symbol("wgf:${wg.id}:${part.key}", part.name, part.category, part.center, part.size, structuralLayer, part.props)
            out += sym
            if (part.name != Structure.JOIST_BAY) continue
            val wl = Structure.bayLayout(sym, sym.size.width, sym.size.height, inch)
            val fr = Wings.frame(wg, footprint, inch)
            // The edge on the house wall line has no wall above it now: no blocking there.
            val houseEdge = fr?.let { f ->
                val c = sym.center
                val crossIsY = wl.alongX
                val houseCoord = f.hf
                val lo = if (crossIsY) c.y - wl.width / 2f else c.x - wl.width / 2f
                val hi = if (crossIsY) c.y + wl.width / 2f else c.x + wl.width / 2f
                when {
                    crossIsY != (f.side == Porches.Side.TOP || f.side == Porches.Side.BOTTOM) -> null
                    abs(lo - houseCoord) < abs(hi - houseCoord) -> -1
                    else -> 1
                }
            }
            out += rimLadders("wg${wg.id}", sym.center, wl, inch, structuralLayer, color, houseEdge)
            // A basement wing: the beam across the opening where the house basement wall came out (Dan, Nov 23).
            run {
                val houseSpanFt = StructuralDesign.design(state)?.joistSpanFt ?: (spanIn / 12f / (beams + 1))
                // The wing's joists land on it when they run out from the house (square to the opening).
                val wingRunsOut = fr != null && (wl.alongX != (fr.side == Porches.Side.TOP || fr.side == Porches.Side.BOTTOM))
                val wingSpanFt = if (wingRunsOut) wl.length / inch / 12f / (wl.beams.size + 1) else 0f
                val jb = Wings.jointBeam(wg, state, houseSpanFt, wingSpanFt) ?: return@run
                val idx = jb.shape?.let { SteelEngine.AISC_DATABASE.indexOf(it) } ?: -1
                out += symbol("jbeam:${wg.id}", Structure.BEAM, Structure.FRAMING, jb.rect.center, jb.rect.size, structuralLayer,
                    mapOf(Structure.BEAM_SECTION to idx.toFloat()))
                if (foundationLayer != null) jb.posts.forEachIndexed { i, at ->
                    out += symbol("pad:jb${wg.id}:$i", Structure.PAD, Structure.FOUNDATION, at, Size(PAD_IN * inch, PAD_IN * inch), foundationLayer, mapOf(Structure.THICK to 12f))
                    out += symbol("post:jb${wg.id}:$i", Structure.POST, Structure.FRAMING, at, Size(5.5f * inch, 5.5f * inch), foundationLayer)
                }
            }
            if (foundationLayer != null) wl.beams.forEachIndexed { i, b ->
                val c1 = -wl.width / 2f; val c2 = wl.width / 2f
                val n = (ceil((c2 - c1) / inch / MAX_POST_SPACING_IN).toInt() - 1).coerceAtLeast(0)
                for (j in 1..n) {
                    val cc = c1 + (c2 - c1) * j / (n + 1)
                    val at = if (wl.alongX) sym.center + Offset(b, cc) else sym.center + Offset(cc, b)
                    out += symbol("pad:wg${wg.id}:b$i:$j", Structure.PAD, Structure.FOUNDATION, at, Size(PAD_IN * inch, PAD_IN * inch), foundationLayer, mapOf(Structure.THICK to 12f))
                    out += symbol("post:wg${wg.id}:b$i:$j", Structure.POST, Structure.FRAMING, at, Size(5.5f * inch, 5.5f * inch), foundationLayer)
                }
            }
        }
        return out
    }

    /**
     * Ladders of blocking between each rim and the joist next to it, on the bay's edges that run with the joists
     * ([skipEdge] -1 or 1: not that edge). Blocks at 16" o.c. to land under the studs.
     */
    /**
     * Each upper floor's joists (Nov 27): one joist bay per rectangle of that floor's outline (its outside walls, the ones
     * carried up from the floor below included), so an L-shaped floor or one over a one-story wing gets joists only where
     * the floor is. Joists run the short way of the whole floor at 16" o.c., at the building's joist size. No beams are
     * drawn: an upper floor bears on the walls below, picked by hand for now.
     */
    private fun upperFloorFraming(state: CanvasState, color: androidx.compose.ui.graphics.Color): List<DrawingElement.Symbol> {
        val s = state.building
        if (s.upperFloors.isEmpty()) return emptyList()
        val inch = state.inchesToPx(1f)
        if (inch <= 0f) return emptyList()
        val layers = state.effectiveLayers.map { it.id }.toSet()
        val of = Layers.resolver(state)
        val out = mutableListOf<DrawingElement.Symbol>()
        for (n in 2..(s.upperFloors.size + 1)) {
            val plan = "${Building.floorId(n)}-plan"
            val framing = "${Building.floorId(n)}-structural"
            if (plan !in layers || framing !in layers) continue
            val ws = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
                it.category == PlanSymbols.WALLS && isSquare(it.rotation) && of(it).id == plan && Remodel.status(it) != WallStatus.DEMO
            }
            // The outside walls: carried up from below, or drawn as 2x6 exterior walls on this floor.
            val outside = ws.filter { it.id.startsWith(GEN_PREFIX + "up") || it.symbolName == FloorPlan.EXTERIOR_WALL }.map { wallRect(it) }
            if (outside.size < 3) continue
            val floor = s.upperFloors[n - 2]
            // The walls of the floor below, where the joists can bear (Dan, Nov 27: second-floor beams).
            val below = if (n == 2) Building.FLOOR1_PLAN else "${Building.floorId(n - 1)}-plan"
            val belowWalls = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
                it.category == PlanSymbols.WALLS && isSquare(it.rotation) && of(it).id == below && Remodel.status(it) != WallStatus.DEMO
            }.map { wallRect(it) }
            for ((k, r) in floorRects(outside, inch).withIndex()) {
                val all = outside.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
                val joistsUpDown = all.width >= all.height
                val beams = upperBeams(r, joistsUpDown, belowWalls, s.structure.maxJoistSpanFt, inch)
                out += DrawingElement.Symbol(
                    id = GEN_PREFIX + "bay:floor$n:$k", symbolName = Structure.JOIST_BAY, category = Structure.FRAMING,
                    center = r.center, size = r.size, color = color, layerId = framing,
                    properties = mapOf(
                        Structure.SPACING to 16f,
                        Structure.JOIST to Structure.joistCodeFor(floor.joistDepthIn ?: s.joistDepthIn).toFloat(),
                        Structure.DIRECTION to if (joistsUpDown) 1f else 0f,
                        Structure.BEAMS to beams.toFloat(),
                        Structure.BEAM_SECTION to -1f,
                        "widthInches" to r.width / inch, "depthInches" to r.height / inch
                    )
                )
            }
        }
        return out
    }

    /**
     * Beams an upper floor's joist bay [r] needs (Dan, Nov 27): its joists span [r] the short way of the floor; walls of the
     * floor below running across the joists, under most of the bay's width and not at its edges, carry them. When the
     * longest stretch between those supports is within the joist span limit ([maxSpanFt], Dan's 15'), none; otherwise as
     * few evenly spaced beams as bring the whole span within it, as on the first floor. Not sized here.
     */
    internal fun upperBeams(r: Rect, joistsUpDown: Boolean, belowWalls: List<Rect>, maxSpanFt: Float, inch: Float): Int {
        val (a1, a2) = if (joistsUpDown) r.top to r.bottom else r.left to r.right // along the joists
        val (c1, c2) = if (joistsUpDown) r.left to r.right else r.top to r.bottom // across them
        val supports = belowWalls.mapNotNull { w ->
            val across = if (joistsUpDown) w.width >= w.height else w.height > w.width // a wall running across the joists
            if (!across) return@mapNotNull null
            val at = if (joistsUpDown) w.center.y else w.center.x
            if (at <= a1 + 12f * inch || at >= a2 - 12f * inch) return@mapNotNull null
            val w1 = if (joistsUpDown) w.left else w.top
            val w2 = if (joistsUpDown) w.right else w.bottom
            val cover = (minOf(w2, c2) - maxOf(w1, c1)).coerceAtLeast(0f)
            if (cover < 0.6f * (c2 - c1)) null else at
        }.sorted()
        val stops = listOf(a1) + supports + listOf(a2)
        val longest = stops.zipWithNext { p, q -> q - p }.maxOrNull() ?: 0f
        if (longest / inch <= maxSpanFt * 12f + 0.5f) return 0
        return StructuralDesign.beamCount((a2 - a1) / inch, maxSpanFt)
    }

    /**
     * A floor's outline from its outside walls, as rectangles: the walls' edges cut the plan into cells (edges within 2" of
     * each other taken as one); a cell is in the floor when it's under a wall or walls close it in on all four sides; the
     * cells are joined into strips across, then strips of the same width down. Rectangles under 2' are dropped.
     */
    private fun floorRects(walls: List<Rect>, inch: Float): List<Rect> {
        fun cluster(vs: List<Float>): List<Float> {
            val sorted = vs.sorted()
            val out = mutableListOf<MutableList<Float>>()
            for (v in sorted) if (out.isNotEmpty() && v - out.last().last() <= 2f * inch) out.last() += v else out += mutableListOf(v)
            return out.map { g -> g.average().toFloat() }
        }
        val xs = cluster(walls.flatMap { listOf(it.left, it.right) })
        val ys = cluster(walls.flatMap { listOf(it.top, it.bottom) })
        if (xs.size < 2 || ys.size < 2) return emptyList()
        fun inside(x: Float, y: Float): Boolean {
            if (walls.any { it.contains(Offset(x, y)) }) return true
            return walls.any { it.left > x && it.top <= y && it.bottom >= y } && walls.any { it.right < x && it.top <= y && it.bottom >= y } &&
                walls.any { it.top > y && it.left <= x && it.right >= x } && walls.any { it.bottom < y && it.left <= x && it.right >= x }
        }
        // Strips across each band of cells.
        data class Strip(val x1: Float, val x2: Float, var y1: Float, var y2: Float)
        val done = mutableListOf<Strip>()
        var open = mutableListOf<Strip>()
        for (j in 0 until ys.size - 1) {
            val y1 = ys[j]; val y2 = ys[j + 1]
            val runs = mutableListOf<Pair<Float, Float>>()
            var start: Float? = null
            for (i in 0 until xs.size - 1) {
                val inCell = inside((xs[i] + xs[i + 1]) / 2f, (y1 + y2) / 2f)
                if (inCell && start == null) start = xs[i]
                if (!inCell && start != null) { runs += start to xs[i]; start = null }
            }
            if (start != null) runs += start to xs.last()
            val next = mutableListOf<Strip>()
            for ((a, b) in runs) {
                val cont = open.firstOrNull { kotlin.math.abs(it.x1 - a) < 0.5f && kotlin.math.abs(it.x2 - b) < 0.5f && kotlin.math.abs(it.y2 - y1) < 0.5f }
                if (cont != null) { cont.y2 = y2; next += cont; open.remove(cont) } else next += Strip(a, b, y1, y2)
            }
            done += open
            open = next
        }
        done += open
        val min = 24f * inch
        return done.map { Rect(it.x1, it.y1, it.x2, it.y2) }.filter { it.width >= min && it.height >= min }
    }

    private fun rimLadders(key: String, center: Offset, layout: Structure.BayLayout, inch: Float, layer: String, color: androidx.compose.ui.graphics.Color, skipEdge: Int?): List<DrawingElement.Symbol> {
        if (layout.joists.isEmpty()) return emptyList()
        val out = mutableListOf<DrawingElement.Symbol>()
        for ((sign, edge, next) in listOf(Triple(-1, -layout.width / 2f, layout.joists.first()), Triple(1, layout.width / 2f, layout.joists.last()))) {
            if (sign == skipEdge) continue
            val gap = abs(next - edge)
            if (gap < inch) continue
            val cross = (edge + next) / 2f
            val at = if (layout.alongX) center + Offset(0f, cross) else center + Offset(cross, 0f)
            val size = if (layout.alongX) Size(layout.length, gap) else Size(gap, layout.length)
            out += DrawingElement.Symbol(id = GEN_PREFIX + "rim:$key:${if (sign < 0) "a" else "b"}", symbolName = Structure.LADDER, category = Structure.FRAMING,
                center = at, size = size, color = color, layerId = layer,
                properties = mapOf("widthInches" to size.width / inch, "depthInches" to size.height / inch, Structure.BLOCKS to Structure.DEFAULT_LADDER_BLOCKS_IN))
        }
        return out
    }

    /** [e] (rect [r]) with the stretch across [c] taken out along its length: the pieces either side. */
    private fun cutOut(e: DrawingElement.Symbol, r: Rect, c: Rect, inch: Float): List<DrawingElement.Symbol> {
        val horizontal = r.width >= r.height
        val pieces = if (horizontal) listOf(r.left to minOf(c.left, r.right), maxOf(c.right, r.left) to r.right)
            else listOf(r.top to minOf(c.top, r.bottom), maxOf(c.bottom, r.top) to r.bottom)
        return pieces.withIndex().filter { (_, ab) -> ab.second - ab.first > 0.5f }.map { (i, ab) ->
            val (a, b) = ab
            val nr = if (horizontal) Rect(a, r.top, b, r.bottom) else Rect(r.left, a, r.right, b)
            val endFlags = (e.properties[Structure.JOIN] ?: 0f).toInt()
            // The cut ends just meet the wing's footings: no corner of their own.
            val join = if (i == 0) endFlags % 3 else 3 * (endFlags / 3)
            e.copy(id = e.id + ":" + (if (i == 0) "a" else "b"), center = nr.center, size = nr.size,
                properties = e.properties + mapOf(Structure.JOIN to join.toFloat(), "widthInches" to nr.width / inch, "depthInches" to nr.height / inch))
        }
    }

    /** The wings' plan walls (first-floor plan, generated so windows and doors can go in them). */
    private fun wingPlanWalls(state: CanvasState): List<DrawingElement.Symbol> {
        val inch = state.inchesToPx(1f)
        if (inch <= 0f) return emptyList()
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull() ?: return emptyList()
        val color = state.elements.filterIsInstance<DrawingElement.Symbol>().firstOrNull { it.symbolName == FloorPlan.EXTERIOR_WALL }?.color ?: state.currentColor
        return Wings.wings(state).flatMap { wg ->
            Wings.planWalls(wg, fp, inch).map { part ->
                DrawingElement.Symbol(id = GEN_PREFIX + Wings.genKey(wg) + part.key, symbolName = part.name, category = part.category,
                    center = part.center, size = part.size, color = color, layerId = wg.layerId,
                    properties = mapOf("widthInches" to part.size.width / inch, "depthInches" to part.size.height / inch))
            }
        }
    }
}
