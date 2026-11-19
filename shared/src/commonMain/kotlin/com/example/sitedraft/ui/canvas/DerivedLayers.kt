package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
 *    outside faces flush, with a continuous footing centred under it (width and thickness from
 *    the building inputs).
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
        if (!state.building.autoLayers) return state
        val desired = build(state).map { stamped(it) }
        val current = state.elements.filter { isGenerated(it) }
        val currentIds = current.map { it.id }.toSet()
        val suppressed = state.suppressedGenIds + (state.generatedIds - currentIds)
        val edited = current.filter { !isUntouched(it) }
        val editedIds = edited.map { it.id }.toSet()
        val fresh = desired.filter { it.id !in suppressed && it.id !in editedIds }
        // Generated parts draw first, under the plan.
        val elements = edited + fresh + state.elements.filter { !isGenerated(it) }
        val generatedIds = editedIds + fresh.map { it.id }
        if (elements == state.elements && generatedIds == state.generatedIds && suppressed == state.suppressedGenIds) return state
        return state.copy(elements = elements, generatedIds = generatedIds, suppressedGenIds = suppressed)
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
        if (s.foundation == FoundationType.SLAB) return emptyList()
        val layers = state.effectiveLayers.map { it.id }.toSet()
        val foundationLayer = Building.FOUNDATION.takeIf { it in layers }
        val structuralLayer = "${Building.FLOOR1}-structural".takeIf { it in layers }
        val inch = state.inchesToPx(1f)
        if (inch <= 0f) return emptyList()

        val walls = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.WALLS && !isGenerated(it) && isSquare(it.rotation) &&
                Layers.layerOf(it, state).id == Building.FLOOR1_PLAN
        }
        val exterior = walls.filter { it.symbolName == FloorPlan.EXTERIOR_WALL }
        if (exterior.isEmpty()) return emptyList()
        val footprint = exterior.map { wallRect(it) }.reduce { a, r ->
            Rect(minOf(a.left, r.left), minOf(a.top, r.top), maxOf(a.right, r.right), maxOf(a.bottom, r.bottom))
        }
        val out = mutableListOf<DrawingElement.Symbol>()
        val color = exterior.first().color

        fun symbol(id: String, name: String, category: String, center: Offset, size: Size, layer: String?, props: Map<String, Float> = emptyMap()) =
            DrawingElement.Symbol(
                id = GEN_PREFIX + id, symbolName = name, category = category, center = center, size = size, color = color,
                properties = mapOf("widthInches" to size.width / inch, "depthInches" to size.height / inch) + props, layerId = layer
            )

        // ---- Foundation walls and footings under the exterior walls ----
        if (foundationLayer != null) {
            val t = 7.625f * inch
            val f = s.footingWidthIn * inch
            for (ew in exterior) {
                val r = wallRect(ew)
                val vertical = r.height > r.width
                val center: Offset
                val wallSize: Size
                val footSize: Size
                if (!vertical) {
                    val outsideTop = r.center.y < footprint.center.y
                    val cy = if (outsideTop) r.top + t / 2f else r.bottom - t / 2f
                    center = Offset(r.center.x, cy)
                    wallSize = Size(r.width, t)
                    footSize = Size(r.width + (f - t), f)
                } else {
                    val outsideLeft = r.center.x < footprint.center.x
                    val cx = if (outsideLeft) r.left + t / 2f else r.right - t / 2f
                    center = Offset(cx, r.center.y)
                    wallSize = Size(t, r.height)
                    footSize = Size(f, r.height + (f - t))
                }
                out += symbol("fw:${ew.id}", FOUNDATION_WALL, PlanSymbols.WALLS, center, wallSize, foundationLayer)
                out += symbol("ftg:${ew.id}", Structure.FOOTING, Structure.FOUNDATION, center, footSize, foundationLayer,
                    mapOf(Structure.THICK to s.footingThicknessIn))
            }
        }

        // ---- First-floor framing ----
        if (structuralLayer == null) return out
        val joistsUpDown = footprint.width >= footprint.height // joists span the short way
        val spanIn = (if (joistsUpDown) footprint.height else footprint.width) / inch
        val beams = (ceil(spanIn / MAX_SPAN_IN).toInt() - 1).coerceAtLeast(0)
        val bay = symbol("bay:floor1", Structure.JOIST_BAY, Structure.FRAMING, footprint.center, footprint.size, structuralLayer,
            mapOf(
                Structure.SPACING to 16f,
                Structure.JOIST to Structure.joistCodeFor(s.joistDepthIn).toFloat(),
                Structure.DIRECTION to if (joistsUpDown) 1f else 0f,
                Structure.BEAMS to beams.toFloat()
            ))
        out += bay
        val layout = Structure.bayLayout(bay, bay.size.width, bay.size.height, inch)
        // Bay frame -> page: along is x when the joists run across, y when they run up and down.
        fun page(along: Float, cross: Float) =
            if (layout.alongX) footprint.center + Offset(along, cross) else footprint.center + Offset(cross, along)

        /** Posts on pads along a line across the joists at [along], from [c1] to [c2] (cross, bay frame). */
        fun postsUnder(key: String, along: Float, c1: Float, c2: Float) {
            if (foundationLayer == null) return
            val lenIn = (c2 - c1) / inch
            val n = (ceil(lenIn / MAX_POST_SPACING_IN).toInt() - 1).coerceAtLeast(0)
            for (j in 1..n) {
                val c = c1 + (c2 - c1) * j / (n + 1)
                val at = page(along, c)
                out += symbol("pad:$key:$j", Structure.PAD, Structure.FOUNDATION, at, Size(PAD_IN * inch, PAD_IN * inch), foundationLayer,
                    mapOf(Structure.THICK to 12f))
                out += symbol("post:$key:$j", Structure.POST, Structure.FRAMING, at, Size(5.5f * inch, 5.5f * inch), foundationLayer)
            }
        }
        layout.beams.forEachIndexed { i, b -> postsUnder("b$i", b, -layout.width / 2f, layout.width / 2f) }

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
        return out
    }
}
