package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Section and detail markers placed for you (Dan, Nov 24 2026): once a drawing is in the model (a detail or the
 * typical wall section, which building the sheets puts there), its callout goes on the plan where it belongs,
 * generated with the rest ("gen:co:<key>"), so it moves when the plan does and its bubble numbers come off the
 * sheet set. Delete one and it stays deleted, like any generated part.
 *
 * - Wall section / exterior wall section: a section cut through the middle of the longest clear stretch of the
 *   south (front) wall, on the first floor plan.
 * - Garage wall section and frost wall: a section cut through the garage's outside wall.
 * - Stair section: a detail callout from the first straight stair.
 * - Footing: on the foundation plan, at the south foundation wall. Beam pocket: at the beam's end. Column: at a post.
 * - Porch post footing: at the deck porch's outside corner post. Overhang: at the south eave on the roof plan.
 */
object CalloutsAuto {
    private const val CUT_LEN_IN = 96f
    private const val LEADER_IN = 48f

    private fun has(state: CanvasState, d: DetailKit.Detail) = DetailKit.detailIn(state, d) != null
    private fun bits(vararg t: Callouts.Target) = t.fold(0) { a, x -> a or x.bit }.toFloat()

    fun forPlan(state: CanvasState, parts: List<DrawingElement.Symbol>): List<DrawingElement.Symbol> {
        val inch = state.inchesToPx(1f)
        val layers = state.effectiveLayers.map { it.id }.toSet()
        val out = mutableListOf<DrawingElement.Symbol>()
        fun callout(key: String, name: String, center: Offset, lengthIn: Float, rotation: Float, targets: Float, layer: String?) {
            if (layer == null || layer !in layers || targets <= 0f) return
            out += DrawingElement.Symbol(
                id = DerivedLayers.GEN_PREFIX + "co:" + key, symbolName = name, category = Callouts.CATEGORY, center = center,
                size = Size(lengthIn * inch, 24f * inch), rotation = rotation, color = Color.White,
                properties = mapOf(Callouts.TARGETS to targets, "widthInches" to lengthIn, "depthInches" to 24f), layerId = layer)
        }
        var middleX: Float? = null
        /** A detail callout whose leader starts at [spot] and runs toward the middle of the house, so the bubble stays on the sheet. */
        fun detail(key: String, spot: Offset, targets: Float, layer: String?) {
            val left = middleX?.let { spot.x > it } ?: false
            val half = LEADER_IN / 2f * inch
            callout(key, Callouts.DETAIL, spot + Offset(if (left) -half else half, 0f), LEADER_IN, if (left) 180f else 0f, targets, layer)
        }

        val of = Layers.resolver(state)
        val syms = state.elements.filterIsInstance<DrawingElement.Symbol>()
        val ext = syms.filter {
            it.category == PlanSymbols.WALLS && it.symbolName == FloorPlan.EXTERIOR_WALL && !DerivedLayers.isGenerated(it) &&
                of(it).id == Building.FLOOR1_PLAN && Remodel.status(it) != WallStatus.DEMO && it.rotation % 90f == 0f
        }

        // ---- The wall section, through the south wall ----
        val wallTargets = (if (syms.any { WallSection.isSection(it) }) Callouts.Target.WALL_SECTION.bit else 0) or
            (if (has(state, DetailKit.Detail.HOUSE_WALL)) Callouts.Target.HOUSE_WALL.bit else 0)
        val south = ext.filter { Elevation.isHorizontal(it) }.maxByOrNull { FloorPlan.bounds(it).bottom }
        if (south != null && wallTargets != 0) {
            val b = FloorPlan.bounds(south)
            // The longest stretch clear of openings (and a foot back from them).
            val spans = (FramingLayout.openingSpans(state)[south.id] ?: emptyList()).map { (a, c) -> b.left + a - 12f * inch to b.left + c + 12f * inch }.sortedBy { it.first }
            var best = b.left to b.right
            var start = b.left + 24f * inch
            var longest = 0f
            for ((a, c) in spans + listOf(b.right - 24f * inch to b.right)) {
                if (a - start > longest) { longest = a - start; best = start to a }
                start = maxOf(start, c)
            }
            val x = (best.first + best.second) / 2f
            callout("wall", Callouts.SECTION, Offset(x, b.center.y), CUT_LEN_IN, 90f, wallTargets.toFloat(), Building.FLOOR1_PLAN)
        }

        // ---- The garage wall: through its outside wall, the side away from the house ----
        val gTargets = (if (has(state, DetailKit.Detail.GARAGE_WALL)) Callouts.Target.GARAGE_WALL.bit else 0) or
            (if (has(state, DetailKit.Detail.GARAGE_FROST)) Callouts.Target.GARAGE_FROST.bit else 0)
        val garage = syms.firstOrNull { Garages.isGarage(it) && !DerivedLayers.isGenerated(it) }
        val house0 = ext.map { FloorPlan.bounds(it) }.reduceOrNull { a, r -> Rect(minOf(a.left, r.left), minOf(a.top, r.top), maxOf(a.right, r.right), maxOf(a.bottom, r.bottom)) }
        val house = house0
        middleX = house?.center?.x
        if (garage != null && gTargets != 0) {
            val g = garage.boundingRect()
            val hc = house?.center ?: g.center
            val dx = g.center.x - hc.x
            val dy = g.center.y - hc.y
            // Turned so the bubble lands outside the garage, past the wall (the bubbles sit off the line's +x end).
            val (at, rot) = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy))
                Offset(if (dx > 0f) g.right else g.left, g.center.y) to (if (dx > 0f) 0f else 180f)
            else Offset(g.center.x, if (dy > 0f) g.bottom else g.top) to (if (dy > 0f) 90f else 270f)
            callout("garage", Callouts.SECTION, at, CUT_LEN_IN, rot, gTargets.toFloat(), of(garage).id)
        }

        // ---- The stair ----
        if (has(state, DetailKit.Detail.STAIR)) StairDetail.pick(state)?.let { st ->
            detail("stair", st.center, bits(Callouts.Target.STAIR), of(st).id)
        }

        // ---- Foundation: the footing at the south foundation wall, the beam pocket, a column ----
        val fnd = Building.FOUNDATION
        if (has(state, DetailKit.Detail.FOOTING) && south != null) {
            val fw = parts.firstOrNull { it.id == DerivedLayers.GEN_PREFIX + "fw:" + south.id }
            if (fw != null) {
                val b = fw.boundingRect()
                detail("footing", Offset(b.left + b.width * 0.25f, b.center.y), bits(Callouts.Target.FOOTING), fw.layerId)
            }
        }
        if (has(state, DetailKit.Detail.BEAM_POCKET)) {
            // The main beam runs across the joist bay at its beam lines; the pocket is where it meets the foundation wall.
            val bay = parts.firstOrNull { it.symbolName == Structure.JOIST_BAY }
            val l = bay?.let { Structure.bayLayout(it, it.size.width, it.size.height, inch) }
            val along = l?.beams?.firstOrNull()
            if (bay != null && l != null && along != null) {
                val cross = l.width / 2f - 6f * inch
                val spot = if (l.alongX) bay.center + Offset(along, cross) else bay.center + Offset(cross, along)
                detail("pocket", spot, bits(Callouts.Target.BEAM_POCKET), fnd)
            } else parts.firstOrNull { it.symbolName == Structure.BEAM }?.let { bm ->
                val r = bm.boundingRect()
                detail("pocket", if (r.width >= r.height) Offset(r.right - 6f * inch, r.center.y) else Offset(r.center.x, r.bottom - 6f * inch),
                    bits(Callouts.Target.BEAM_POCKET), fnd)
            }
        }
        if (has(state, DetailKit.Detail.COLUMN)) {
            parts.firstOrNull { it.symbolName == Structure.POST && it.id.startsWith(DerivedLayers.GEN_PREFIX + "post:b") }
                ?.let { detail("column", it.center, bits(Callouts.Target.COLUMN), it.layerId) }
        }

        // ---- The deck porch's post ----
        if (has(state, DetailKit.Detail.PORCH_POST)) {
            val (pc, _, _) = PorchPostDetail.pick(state)
            if (pc != null) {
                val r = pc.boundingRect()
                val hc = house?.center ?: r.center
                val corner = Offset(if (r.center.x >= hc.x) r.right - 4f * inch else r.left + 4f * inch, if (r.center.y >= hc.y) r.bottom - 4f * inch else r.top + 4f * inch)
                detail("porchpost", corner, bits(Callouts.Target.PORCH_POST), of(pc).id)
            }
        }

        // ---- The overhang, at the south eave on the roof plan ----
        if (has(state, DetailKit.Detail.OVERHANG)) {
            val roof = syms.firstOrNull { RoofPlan.isRoof(it) }
            val g = state.building.roof?.let { runCatching { RoofPlan.geometry(state, it) }.getOrNull() }
            if (roof != null && g != null) {
                val e = g.eave
                detail("overhang", Offset(e.left + e.width * 0.3f, e.bottom - 6f * inch), bits(Callouts.Target.OVERHANG), of(roof).id)
            }
        }
        return out
    }
}
