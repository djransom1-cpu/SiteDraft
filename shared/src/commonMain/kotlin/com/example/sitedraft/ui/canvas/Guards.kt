package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Guards (Dan, Nov 24 2026): a guard rail on the plan, drawn as its 3 1/2" rail between square posts with its height
 * noted ("36\" GUARD"). Two ways in:
 *
 * Posts: 4x4, max 8' o.c., each run divided into equal spaces (Dan's railing rule, from the Deck Designer).
 *
 * - The Guard Rail symbol (Symbols > Railings): drag it out like a wall and stretch it, for a loft, a landing or a
 *   deck edge. Its length runs along its width; it is always 3 1/2" deep.
 * - Around a stair's floor opening, automatically (generated, with Build from the floor plan): on the floor plan the
 *   opening is in, every side but the one you step onto the stair from, unless a wall runs along that side or the
 *   stair already has its own guard there. A stair can turn it off ([Stairs.GUARD_AUTO]).
 */
object Guards {
    const val GUARD = "Guard Rail"
    val library = listOf(GUARD)
    /** Guard height, inches (IRC R312: 36" where the drop is over 30"). */
    const val HEIGHT = "grHeight"
    /** 1: note the height on the plan. */
    const val NOTE = "grNote"
    val keys = setOf(HEIGHT, NOTE)
    const val DEPTH_IN = 3.5f
    const val DEFAULT_LENGTH_IN = 48f

    fun isGuard(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == PlanSymbols.RAILINGS
    fun defaults(name: String): Map<String, Float> = mapOf(HEIGHT to 36f, NOTE to 1f)
    fun prop(s: DrawingElement.Symbol, key: String): Float = s.properties[key] ?: defaults(s.symbolName)[key] ?: 0f
    val footprints: Map<String, Pair<Float, Float>> = mapOf(GUARD to Pair(DEFAULT_LENGTH_IN, DEPTH_IN))

    /** "36\" GUARD". */
    fun note(s: DrawingElement.Symbol): String = "${formatInches((prop(s, HEIGHT) * 4f).roundToInt() / 4f)}\" GUARD"

    /** The guard in its own frame (centred, length along x): the rail's two faces, a post at each end, the note. */
    fun planParts(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float): List<Shower.Part> {
        val hw = w / 2f
        val hh = h / 2f
        val post = minOf(hh, DEPTH_IN / 2f * inch).coerceAtLeast(0.5f)
        val parts = mutableListOf<Shower.Part>()
        parts += Shower.Part.Seg(Offset(-hw, -hh), Offset(hw, -hh), Shower.Ink.LINE)
        parts += Shower.Part.Seg(Offset(-hw, hh), Offset(hw, hh), Shower.Ink.LINE)
        for (x in postXs(w, inch, post)) {
            parts += Shower.Part.Poly(listOf(Offset(x - post, -post), Offset(x + post, -post), Offset(x + post, post), Offset(x - post, post)), true, Shower.Ink.LINE)
        }
        if (prop(s, NOTE) >= 0.5f && w > inch * 24f) {
            val size = (inch * 3.5f).coerceAtLeast(6f)
            val t = note(s)
            parts += Shower.Part.Label(t, Offset(-t.length * size * 0.3f, -hh - size * 1.4f), size, Shower.Ink.LINE)
        }
        return parts
    }

    /** Dan's railing rule (from the Deck Designer): 4x4 posts, max 8' o.c., the run always divided into equal spaces. */
    const val MAX_POST_SPACING_IN = 96f

    /** Number of equal spaces between posts for a run of [lengthIn]. */
    fun postSpaces(lengthIn: Float): Int = kotlin.math.ceil((lengthIn / MAX_POST_SPACING_IN).toDouble()).toInt().coerceAtLeast(1)

    /** Post centres along the guard (frame centred, length along x), end posts flush with the ends. */
    fun postXs(w: Float, inch: Float, post: Float): List<Float> {
        val n = postSpaces(w / inch)
        val a = -w / 2f + post
        val b = w / 2f - post
        return (0..n).map { a + (b - a) * it / n }
    }

    /** Draws the guard centred on the origin (the caller has translated and rotated). */
    fun DrawScope.drawGuardPlan(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float, lineColor: Color, line: Stroke, thin: Stroke) {
        for (p in planParts(s, w, h, inch)) when (p) {
            is Shower.Part.Seg -> drawLine(lineColor, p.a, p.b, line.width)
            is Shower.Part.Poly -> {
                val path = androidx.compose.ui.graphics.Path()
                p.points.forEachIndexed { i, q -> if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
                if (p.closed) path.close()
                drawPath(path, lineColor, style = thin)
            }
            is Shower.Part.Label -> drawPageText(p.text, p.c, p.size, lineColor, bold = false, maxWidth = p.size * p.text.length)
            is Shower.Part.Ring -> drawCircle(lineColor, p.r, p.c, style = thin)
        }
    }

    // ---- Around a stair's floor opening ----

    private fun world(s: DrawingElement.Symbol, local: Offset): Offset {
        val r = s.rotation * PI.toFloat() / 180f
        return s.center + Offset(local.x * cos(r) - local.y * sin(r), local.x * sin(r) + local.y * cos(r))
    }

    /** The floor plan a stair's opening is on: its own floor going down, the floor above going up. */
    private fun openingFloor(stair: DrawingElement.Symbol, state: CanvasState): Int? {
        val layer = Layers.layerOf(stair, state)
        if (layer.trade != LayerTrade.FLOOR_PLAN) return null
        val n = layer.levelId?.removePrefix("floor")?.toIntOrNull() ?: return null
        return if (Stairs.goesDown(stair, state)) n else n + 1
    }

    /**
     * The guards around a stair's floor opening (generated ids "gen:guard:<stair>:<side>"), on the opening's floor
     * plan: the two sides and the far end, each skipped where a wall runs along it or (on the stair's own floor) the
     * stair has a guard on that side. Empty for a deck stair, with the stair's guards turned off, or when that floor
     * has no plan layer.
     */
    fun forStair(stair: DrawingElement.Symbol, state: CanvasState): List<DrawingElement.Symbol> {
        if (Stairs.type(stair.symbolName) == Stairs.Type.DECK || Stairs.code(stair, Stairs.GUARD_AUTO) == 0) return emptyList()
        val opening = Stairs.floorOpening(stair, state) ?: return emptyList()
        val floor = openingFloor(stair, state) ?: return emptyList()
        val layerId = "${Building.floorId(floor)}-plan"
        if (state.effectiveLayers.none { it.id == layerId }) return emptyList()
        val sameFloor = Stairs.goesDown(stair, state)
        val inch = state.inchesToPx(1f)
        val d = DEPTH_IN * inch
        val w = stair.size.width
        val hh = stair.size.height / 2f
        val len = opening.size.height
        val top = -hh                       // the top of the stair: where you step on (down) or off (up)
        val bottom = top + len              // the opening's far edge, over the stair below
        val of = Layers.resolver(state)
        val walls = state.elements.filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.WALLS && of(it).id == layerId }
            .map { FloorPlan.bounds(it).inflate(3f * inch) }
        fun walled(a: Offset, b: Offset): Boolean {
            val pts = listOf(0.25f, 0.5f, 0.75f).map { t -> world(stair, a + (b - a) * t) }
            return walls.any { r -> pts.count { r.contains(it) } >= 2 }
        }
        val out = mutableListOf<DrawingElement.Symbol>()
        fun guard(key: String, centre: Offset, lengthPx: Float, turn: Float, noted: Boolean) {
            out += DrawingElement.Symbol(
                id = DerivedLayers.GEN_PREFIX + "guard:" + stair.id + ":" + key, symbolName = GUARD, category = PlanSymbols.RAILINGS,
                center = world(stair, centre), size = Size(lengthPx, d), rotation = stair.rotation + turn, color = stair.color,
                properties = mapOf(HEIGHT to Stairs.prop(stair, Stairs.GUARD_H), NOTE to if (noted) 1f else 0f,
                    "widthInches" to lengthPx / inch, "depthInches" to DEPTH_IN),
                layerId = layerId
            )
        }
        val rails = listOf(Stairs.RAIL_LEFT, Stairs.RAIL_RIGHT).map { Stairs.code(stair, it) }
        var noted = false
        // The far end first (it carries the note), then the two sides, outside the opening's edges.
        if (!walled(Offset(-w / 2f, bottom), Offset(w / 2f, bottom))) {
            guard("end", Offset(0f, bottom + d / 2f), w + 2f * d, 0f, true); noted = true
        }
        for ((i, sx) in listOf(-1f, 1f).withIndex()) {
            val x = sx * w / 2f
            if (sameFloor && rails[i] == Stairs.RAIL_GUARD) continue
            if (walled(Offset(x, top), Offset(x, bottom))) continue
            guard(if (sx < 0f) "left" else "right", Offset(x + sx * d / 2f, (top + bottom) / 2f), len, 90f, !noted)
            noted = true
        }
        return out
    }

    /** Every stair's opening guards. */
    fun forStairs(state: CanvasState): List<DrawingElement.Symbol> =
        state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.STAIRS && !DerivedLayers.isGenerated(it) }
            .flatMap { forStair(it, state) }
}
