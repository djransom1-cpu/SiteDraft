package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/**
 * The house's outline cut into boxes for the roof (Dan, Nov 28: Benjeman's two offset boxes got one square roof over both).
 * The outside of the first-floor exterior walls is filled in and cut into bands, across and then up and down; the way that
 * gives fewer boxes is kept. Slivers narrower than [MIN_IN] (a wall's thickness where two boxes overlap) join the box
 * beside them. Biggest first: the main house, then the parts that need their own roofs. World px.
 */
object RoofBoxes {
    const val MIN_IN = 36f

    fun of(state: CanvasState): List<Rect> {
        val outside = HouseOutline.planWalls(state).filter { HouseOutline.isExterior(it, state) }
        return boxes(outside.map { FloorPlan.bounds(it) }, state.inchesToPx(1f))
    }

    fun boxes(walls: List<Rect>, k: Float): List<Rect> {
        if (walls.isEmpty() || k <= 0f) return emptyList()
        val g = HouseOutline.GAP_IN * k
        val grown = walls.map { Rect(it.left - g, it.top - g, it.right + g, it.bottom + g) }
        fun cuts(vals: List<Float>): FloatArray {
            val lo = vals.min() - 4f * g; val hi = vals.max() + 4f * g
            return (vals + lo + hi).map { kotlin.math.round(it * 64f) / 64f }.distinct().sorted().toFloatArray()
        }
        val xs = cuts(grown.flatMap { listOf(it.left, it.right) })
        val ys = cuts(grown.flatMap { listOf(it.top, it.bottom) })
        val nx = xs.size - 1; val ny = ys.size - 1
        if (nx <= 0 || ny <= 0) return emptyList()
        val solid = Array(nx) { i -> BooleanArray(ny) { j -> grown.any { it.contains(Offset((xs[i] + xs[i + 1]) / 2f, (ys[j] + ys[j + 1]) / 2f)) } } }
        val out = Array(nx) { BooleanArray(ny) }
        val queue = ArrayDeque<Pair<Int, Int>>()
        fun push(i: Int, j: Int) { if (i in 0 until nx && j in 0 until ny && !solid[i][j] && !out[i][j]) { out[i][j] = true; queue.add(i to j) } }
        for (i in 0 until nx) { push(i, 0); push(i, ny - 1) }
        for (j in 0 until ny) { push(0, j); push(nx - 1, j) }
        while (queue.isNotEmpty()) { val (i, j) = queue.removeFirst(); push(i + 1, j); push(i - 1, j); push(i, j + 1); push(i, j - 1) }
        // The filled-in house, cell by cell; the grown walls' outer g stays out (it is just past the wall).
        val left = Array(nx) { i -> BooleanArray(ny) { j -> !out[i][j] } }
        val min = MIN_IN * k
        val inside = Array(nx) { i -> BooleanArray(ny) { j -> !out[i][j] } }
        // Bands one way (rows: y bands, runs along x), then the other; the way with fewer boxes wins (an L or a Z is two).
        fun bandBoxes(across: Boolean): List<Rect> {
            val nb = if (across) ny else nx; val nc = if (across) nx else ny
            val bandEdge = if (across) ys else xs; val runEdge = if (across) xs else ys
            fun cell(c: Int, b: Int) = if (across) inside[c][b] else inside[b][c]
            data class Box(var r0: Float, var r1: Float, var b0: Float, var b1: Float)
            val done = mutableListOf<Box>(); var open = mutableListOf<Box>()
            for (b in 0 until nb) {
                val runs = mutableListOf<Pair<Float, Float>>()
                var c = 0
                while (c < nc) { if (cell(c, b)) { val c0 = c; while (c < nc && cell(c, b)) c++; runs += runEdge[c0] to runEdge[c] } else c++ }
                val next = mutableListOf<Box>()
                for ((r0, r1) in runs) {
                    val same = open.firstOrNull { abs(it.r0 - r0) < 0.01f && abs(it.r1 - r1) < 0.01f }
                    if (same != null) { same.b1 = bandEdge[b + 1]; next += same } else next += Box(r0, r1, bandEdge[b], bandEdge[b + 1])
                }
                done += open.filter { it !in next }; open = next
            }
            done += open
            // A sliver band (a wall's thickness where two boxes overlap) goes to a box beside it that fits inside its run.
            val keep = done.filter { it.b1 - it.b0 >= min && it.r1 - it.r0 >= min }.toMutableList()
            for (t in done.filter { it !in keep }) {
                val n = keep.firstOrNull { (abs(it.b1 - t.b0) < 0.01f || abs(it.b0 - t.b1) < 0.01f) && it.r0 >= t.r0 - 0.01f && it.r1 <= t.r1 + 0.01f } ?: continue
                n.b0 = minOf(n.b0, t.b0); n.b1 = maxOf(n.b1, t.b1)
            }
            return keep.map { if (across) Rect(it.r0, it.b0, it.r1, it.b1) else Rect(it.b0, it.r0, it.b1, it.r1) }
        }
        val rows = bandBoxes(true); val cols = bandBoxes(false)
        val found = (if (cols.size < rows.size || (cols.size == rows.size && (cols.maxOfOrNull { it.width * it.height } ?: 0f) > (rows.maxOfOrNull { it.width * it.height } ?: 0f))) cols else rows)
            .sortedByDescending { it.width * it.height }
        // Back to the walls' outside faces: an edge within the grow of a wall face snaps to it.
        val faceX = walls.flatMap { listOf(it.left, it.right) }; val faceY = walls.flatMap { listOf(it.top, it.bottom) }
        fun snap(v: Float, faces: List<Float>) = faces.minByOrNull { abs(it - v) }?.takeIf { abs(it - v) <= 2f * g } ?: v
        return found.map { Rect(snap(it.left, faceX), snap(it.top, faceY), snap(it.right, faceX), snap(it.bottom, faceY)) }
    }
}
