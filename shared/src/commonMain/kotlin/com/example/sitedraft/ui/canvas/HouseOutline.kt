package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Which first-floor walls are outside walls, whatever they were drawn as (Dan, Nov 26 2026: the Ransom
 * House, an 1883 house measured room by room in 2x4 walls, had no 2x6 walls, so the roof and the
 * exterior elevations had nothing to build from).
 *
 * A wall is outside when open ground reaches it: the plan is cut into a grid on the wall edges, the
 * cells under walls are solid, and the outside is flooded in from the edge of the drawing. Walls are
 * thickened [GAP_IN] each way for the flood, so a small gap where two walls don't quite meet (a
 * measured house is never perfectly square) doesn't let the outside leak into the rooms.
 */
object HouseOutline {
    /** Half the widest gap between walls that still counts as closed, inches. */
    internal const val GAP_IN = 1f

    /** The first-floor plan walls the outline is made from (not generated parts). */
    fun planWalls(state: CanvasState): List<DrawingElement.Symbol> {
        val of = Layers.resolver(state)
        return state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.WALLS && of(it).id == Building.FLOOR1_PLAN && !DerivedLayers.isGenerated(it)
        }
    }

    class Result(val exterior: Set<String>, val facing: Map<String, Set<ViewerSide>>)

    private var memoKey: Any? = null
    private var memoScale = 0f
    private var memo: Result? = null

    fun of(state: CanvasState): Result {
        val k = state.inchesToPx(1f)
        if (memoKey === state.elements && memoScale == k) memo?.let { return it }
        val r = compute(planWalls(state), k)
        memoKey = state.elements; memoScale = k; memo = r
        return r
    }

    /** Is [wall] an outside wall of the first floor. */
    fun isExterior(wall: DrawingElement.Symbol, state: CanvasState): Boolean =
        wall.symbolName == FloorPlan.EXTERIOR_WALL || wall.id in of(state).exterior

    /** The ways [wall]'s outside faces look out (the elevations that see it). */
    fun facing(wall: DrawingElement.Symbol, state: CanvasState): Set<ViewerSide> = of(state).facing[wall.id].orEmpty()

    fun compute(walls: List<DrawingElement.Symbol>, k: Float): Result {
        if (walls.isEmpty() || k <= 0f) return Result(emptySet(), emptyMap())
        val g = GAP_IN * k
        val rects = walls.map { FloorPlan.bounds(it) }
        val grown = rects.map { Rect(it.left - g, it.top - g, it.right + g, it.bottom + g) }
        fun cuts(vals: List<Float>): FloatArray {
            val lo = vals.min() - 4f * g; val hi = vals.max() + 4f * g
            return (vals + lo + hi).map { (it * 64f).let { v -> kotlin.math.round(v) / 64f } }.distinct().sorted().toFloatArray()
        }
        val xs = cuts(grown.flatMap { listOf(it.left, it.right) })
        val ys = cuts(grown.flatMap { listOf(it.top, it.bottom) })
        val nx = xs.size - 1; val ny = ys.size - 1
        if (nx <= 0 || ny <= 0) return Result(emptySet(), emptyMap())
        val solid = Array(nx) { BooleanArray(ny) }
        for (i in 0 until nx) for (j in 0 until ny) {
            val c = Offset((xs[i] + xs[i + 1]) / 2f, (ys[j] + ys[j + 1]) / 2f)
            solid[i][j] = grown.any { it.contains(c) }
        }
        val out = Array(nx) { BooleanArray(ny) }
        val queue = ArrayDeque<Pair<Int, Int>>()
        fun push(i: Int, j: Int) { if (i in 0 until nx && j in 0 until ny && !solid[i][j] && !out[i][j]) { out[i][j] = true; queue.add(i to j) } }
        for (i in 0 until nx) { push(i, 0); push(i, ny - 1) }
        for (j in 0 until ny) { push(0, j); push(nx - 1, j) }
        while (queue.isNotEmpty()) {
            val (i, j) = queue.removeFirst()
            push(i + 1, j); push(i - 1, j); push(i, j + 1); push(i, j - 1)
        }
        fun cellAt(p: Offset): Pair<Int, Int>? {
            val i = xs.indexOfLast { it <= p.x }; val j = ys.indexOfLast { it <= p.y }
            return if (i in 0 until nx && j in 0 until ny) i to j else null
        }
        fun outsideAt(p: Offset): Boolean = cellAt(p)?.let { (i, j) -> out[i][j] } ?: true
        val exterior = mutableSetOf<String>()
        val facing = mutableMapOf<String, Set<ViewerSide>>()
        walls.forEachIndexed { n, w ->
            val r = grown[n]
            val step = 1.5f * g
            // Samples just past each long face of the thickened wall, along its middle 80%.
            fun probe(side: ViewerSide): Boolean {
                val horizontal = side == ViewerSide.ABOVE || side == ViewerSide.BELOW
                val a0 = if (horizontal) r.left else r.top
                val a1 = if (horizontal) r.right else r.bottom
                val pts = (1..9).map { a0 + (a1 - a0) * (0.1f + 0.8f * (it - 1) / 8f) }
                return pts.any { a ->
                    when (side) {
                        ViewerSide.ABOVE -> outsideAt(Offset(a, r.top - step))
                        ViewerSide.BELOW -> outsideAt(Offset(a, r.bottom + step))
                        ViewerSide.LEFT -> outsideAt(Offset(r.left - step, a))
                        ViewerSide.RIGHT -> outsideAt(Offset(r.right + step, a))
                    }
                }
            }
            val raw = rects[n]
            val long = if (raw.width >= raw.height) listOf(ViewerSide.ABOVE, ViewerSide.BELOW) else listOf(ViewerSide.LEFT, ViewerSide.RIGHT)
            val sides = long.filter { probe(it) }.toSet()
            if (sides.isNotEmpty()) { exterior += w.id; facing[w.id] = sides }
        }
        return Result(exterior, facing)
    }

    /**
     * The main house rectangle, world px: the outside walls' extent with what's inside a wing that uses
     * the plan's own walls taken out (the wing stands for that part). Null with no outside walls.
     */
    fun mainBody(state: CanvasState, walls: List<DrawingElement.Symbol>): Rect? {
        val cutouts = Wings.cutouts(state)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (w in walls) {
            for (piece in minus(FloorPlan.bounds(w), cutouts)) {
                if (piece.width <= 0.01f || piece.height <= 0.01f) continue
                l = min(l, piece.left); t = min(t, piece.top); r = max(r, piece.right); b = max(b, piece.bottom)
            }
        }
        return if (l < r && t < b) Rect(l, t, r, b) else null
    }

    /** [a] with each of [cuts] taken out, as rectangles. */
    fun minus(a: Rect, cuts: List<Rect>): List<Rect> {
        var pieces = listOf(a)
        for (c in cuts) pieces = pieces.flatMap { p ->
            val x0 = max(p.left, c.left); val x1 = min(p.right, c.right)
            val y0 = max(p.top, c.top); val y1 = min(p.bottom, c.bottom)
            if (x0 >= x1 || y0 >= y1) listOf(p) else listOfNotNull(
                Rect(p.left, p.top, p.right, y0).takeIf { it.height > 0.01f },
                Rect(p.left, y1, p.right, p.bottom).takeIf { it.height > 0.01f },
                Rect(p.left, y0, x0, y1).takeIf { it.width > 0.01f },
                Rect(x1, y0, p.right, y1).takeIf { it.width > 0.01f }
            )
        }
        return pieces
    }
}
