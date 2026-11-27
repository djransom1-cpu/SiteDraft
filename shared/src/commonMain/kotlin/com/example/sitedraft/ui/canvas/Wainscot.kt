package com.example.sitedraft.ui.canvas

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wainscot on a wall face (Dan, Nov 25). Kept on the wall itself, one setting per face (the side the
 * elevation looks from), so every elevation of that face shows it and it survives the elevation being
 * rebuilt from the plan. Dan's rules:
 *
 * - Height is to the top of the cap: 39" (chair rail height) by default, tile 48" with a bullnose cap.
 * - It stops at the door casing each side; under a window whose trim comes below the wainscot height it
 *   runs under the window (its top drops to the bottom of the window trim).
 * - Set wall by wall: no fixed panel width, the panels or boxes are sized evenly to each stretch.
 * - Panel wainscot: a 1x6 between the panels and at the top, about a 1x12 at the bottom with the base over it;
 *   raised panel, or flat panel with an offset molding. Say how many, or leave it on auto.
 * - Shadow boxes: 6" up from the base and 6" down from the chair rail, 5-1/2" to 6" between boxes and at the
 *   ends, longer than they are tall.
 */
object Wainscot {
    const val NONE = 0
    const val BEADBOARD = 1
    const val BOARD_BATTEN = 2
    const val FLAT_PANEL = 3
    const val RAISED_PANEL = 4
    const val SHADOW_BOXES = 5
    const val TILE = 6
    val styleLabels = listOf("None", "Beadboard", "Board & batten", "Flat panel", "Raised panel", "Shadow boxes", "Tile")

    const val DEFAULT_HEIGHT_IN = 39f
    const val TILE_HEIGHT_IN = 48f

    const val CAP_IN = 1.5f          // cap molding over beadboard, board and batten and panels
    const val CHAIR_RAIL_IN = 2.5f   // chair rail over shadow boxes
    const val BEAD_IN = 2f           // beadboard bead spacing
    const val BATTEN_IN = 2.5f
    const val BATTEN_OC_IN = 16f
    const val RAIL_1X4_IN = 3.5f
    const val STILE_IN = 5.5f        // 1x6 between panels and at the top
    const val BOTTOM_RAIL_IN = 11.25f // about a 1x12 at the bottom, the base over it
    const val PANEL_TARGET_IN = 24f  // auto: panels about this wide
    const val BOX_GAP_IN = 5.75f     // 5-1/2" to 6" between boxes and at the ends
    const val BOX_OFF_IN = 6f        // up from the base, down from the chair rail
    const val BOX_MOLDING_IN = 1.25f
    const val ASSUMED_BASE_IN = 5.5f // shadow boxes when the elevation draws no base
    const val TILE_COURSE_IN = 3f
    const val TILE_LENGTH_IN = 6f

    fun styleKey(side: ViewerSide) = "ws.${side.name}.style"
    fun heightKey(side: ViewerSide) = "ws.${side.name}.h"
    fun countKey(side: ViewerSide) = "ws.${side.name}.n"
    /** Which rooms along the wall get it: bit i = the i-th stretch between the walls that butt in, left to right; 0 = all. */
    fun roomsKey(side: ViewerSide) = "ws.${side.name}.rooms"
    val allKeys: Set<String> = ViewerSide.entries.flatMap { listOf(styleKey(it), heightKey(it), countKey(it), roomsKey(it)) }.toSet()

    /** A wall face's wainscot, or null when it has none. [rooms]: the bit mask of rooms that get it (0 = all). */
    data class Spec(val style: Int, val heightIn: Float, val count: Int, val rooms: Int = 0) {
        fun inRoom(i: Int) = rooms == 0 || (rooms shr i) and 1 == 1
    }

    fun specOf(wall: DrawingElement.Symbol, side: ViewerSide): Spec? {
        val style = (wall.properties[styleKey(side)] ?: 0f).toInt()
        if (style !in 1 until styleLabels.size) return null
        val h = wall.properties[heightKey(side)]?.takeIf { it > 0f } ?: defaultHeight(style)
        val n = (wall.properties[countKey(side)] ?: 0f).toInt().coerceAtLeast(0)
        val rooms = (wall.properties[roomsKey(side)] ?: 0f).toInt().coerceAtLeast(0)
        return Spec(style, h, n, rooms)
    }

    /**
     * The rooms along a wall face (inches along it, as the elevation runs): the stretches between the walls that
     * butt into this face, left to right.
     */
    fun roomSpans(lengthIn: Float, cuts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        var start = 0f
        for ((a, b) in cuts.sortedBy { it.first }) {
            if (a > start + 1f) out += start to a
            start = max(start, b)
        }
        if (lengthIn > start + 1f) out += start to lengthIn
        return out
    }

    fun roomSpans(wall: DrawingElement.Symbol, side: ViewerSide, state: CanvasState): List<Pair<Float, Float>> {
        val inch = state.inchesToPx(1f)
        val cuts = Elevation.itemsOnWall(wall, side, state).filter { it.kind == Elevation.Kind.CUT_WALL }.map { it.from / inch to it.to / inch }
        return roomSpans(Elevation.lengthPx(wall) / inch, cuts)
    }

    /**
     * What the wainscot stops behind besides doors (Dan, Nov 25): base cabinets, counters, tall units and appliances
     * standing on the floor. A vanity stops the wood styles; tile runs on behind it, as a bath backsplash does.
     */
    fun stopsBehind(s: DrawingElement.Symbol, style: Int): Boolean {
        if (!Elevation.standsOnFloor(s)) return false
        return when {
            s.category == PlanSymbols.CABINETS && PlanSymbols.isVanity(s.symbolName) -> style != TILE
            s.category == PlanSymbols.CABINETS || s.category == PlanSymbols.APPLIANCES -> true
            s.symbolName.startsWith("Dishwasher") -> true
            else -> false
        }
    }

    fun defaultHeight(style: Int) = if (style == TILE) TILE_HEIGHT_IN else DEFAULT_HEIGHT_IN

    // ------------------------------------------------------------------ layout (inches along the wall, up from the floor)

    /** One stretch of wainscot: [from]..[to] along the wall, its cap top at [top]. */
    data class Stretch(val from: Float, val to: Float, val top: Float)

    sealed class Mark {
        /** [fill] true: tile pattern. [heavy]: a cap or rail edge. */
        data class Box(val x1: Float, val x2: Float, val h1: Float, val h2: Float, val tile: Boolean = false, val heavy: Boolean = false) : Mark()
        data class Seg(val x1: Float, val h1: Float, val x2: Float, val h2: Float) : Mark()
    }

    /** A window in the wall: [from]..[to] along it (its trim's outer edges) and the bottom of its trim. */
    data class Window(val from: Float, val to: Float, val trimBottomIn: Float)

    /**
     * Where the wainscot runs on a wall [lengthIn] long: everywhere but the [doors] (their trim's outer edges),
     * dropping under any window whose trim comes below the top.
     */
    fun stretches(lengthIn: Float, top: Float, doors: List<Pair<Float, Float>>, windows: List<Window>): List<Stretch> {
        // Runs between the doors.
        val runs = mutableListOf<Pair<Float, Float>>()
        var start = 0f
        for ((a, b) in doors.map { max(0f, it.first) to min(lengthIn, it.second) }.filter { it.second > it.first }.sortedBy { it.first }) {
            if (a > start + 0.25f) runs += start to a
            start = max(start, b)
        }
        if (lengthIn > start + 0.25f) runs += start to lengthIn
        // Split each run at the low windows.
        val low = windows.filter { it.trimBottomIn < top }.sortedBy { it.from }
        val out = mutableListOf<Stretch>()
        for ((a, b) in runs) {
            var x = a
            for (w in low) {
                val wa = max(a, w.from); val wb = min(b, w.to)
                if (wb <= wa) continue
                if (wa > x + 0.25f) out += Stretch(x, wa, top)
                if (w.trimBottomIn > 1f) out += Stretch(wa, wb, w.trimBottomIn)
                x = max(x, wb)
            }
            if (b > x + 0.25f) out += Stretch(x, b, top)
        }
        return out
    }

    /** How many panels or boxes go on each stretch: [count] shared out by length, or auto. */
    private fun shares(stretches: List<Stretch>, count: Int): List<Int> {
        if (count <= 0) return stretches.map { 0 }
        val full = stretches.filter { it.to - it.from > 0f }
        val total = full.sumOf { (it.to - it.from).toDouble() }.toFloat().coerceAtLeast(1f)
        return stretches.map { s -> if (s.to - s.from <= 0f) 0 else max(1, (count * (s.to - s.from) / total).roundToInt()) }
    }

    fun marks(spec: Spec, stretches: List<Stretch>, baseIn: Float): List<Mark> {
        val out = mutableListOf<Mark>()
        val counts = shares(stretches, spec.count)
        stretches.forEachIndexed { i, s ->
            val len = s.to - s.from
            if (len <= 0.5f || s.top <= 1f) return@forEachIndexed
            when (spec.style) {
                BEADBOARD -> {
                    val capB = s.top - CAP_IN
                    out += Mark.Box(s.from, s.to, capB, s.top, heavy = true)
                    var x = s.from + BEAD_IN
                    while (x < s.to - 0.5f) { out += Mark.Seg(x, baseIn, x, capB); x += BEAD_IN }
                }
                BOARD_BATTEN -> {
                    val capB = s.top - CAP_IN
                    val railB = capB - RAIL_1X4_IN
                    out += Mark.Box(s.from, s.to, capB, s.top, heavy = true)
                    if (railB > baseIn + 2f) {
                        out += Mark.Box(s.from, s.to, railB, capB)
                        val n = max(1, floor(len / BATTEN_OC_IN).toInt())
                        val step = len / n
                        for (k in 0..n) {
                            val c = (s.from + k * step).coerceIn(s.from + BATTEN_IN / 2f, s.to - BATTEN_IN / 2f)
                            out += Mark.Box(c - BATTEN_IN / 2f, c + BATTEN_IN / 2f, baseIn, railB)
                        }
                    }
                }
                FLAT_PANEL, RAISED_PANEL -> {
                    val capB = s.top - CAP_IN
                    out += Mark.Box(s.from, s.to, capB, s.top, heavy = true)
                    val pTop = capB - STILE_IN
                    val pBot = BOTTOM_RAIL_IN
                    out += Mark.Seg(s.from, pTop, s.to, pTop)
                    out += Mark.Seg(s.from, pBot, s.to, pBot)
                    if (pTop - pBot < 4f || len < 2 * STILE_IN + 4f) return@forEachIndexed
                    val n = if (counts[i] > 0) counts[i] else max(1, ((len - STILE_IN) / (PANEL_TARGET_IN + STILE_IN)).roundToInt())
                    val w = (len - (n + 1) * STILE_IN) / n
                    if (w < 4f) return@forEachIndexed
                    for (k in 0 until n) {
                        val a = s.from + STILE_IN + k * (w + STILE_IN)
                        out += Mark.Box(a, a + w, pBot, pTop)
                        if (spec.style == FLAT_PANEL) {
                            // The offset molding, an inch in.
                            out += Mark.Box(a + 1f, a + w - 1f, pBot + 1f, pTop - 1f)
                        } else {
                            // The raised field and its bevels to the corners.
                            val inset = min(2f, min(w, pTop - pBot) / 4f)
                            out += Mark.Box(a + inset, a + w - inset, pBot + inset, pTop - inset)
                            out += Mark.Seg(a, pBot, a + inset, pBot + inset)
                            out += Mark.Seg(a + w, pBot, a + w - inset, pBot + inset)
                            out += Mark.Seg(a, pTop, a + inset, pTop - inset)
                            out += Mark.Seg(a + w, pTop, a + w - inset, pTop - inset)
                        }
                    }
                }
                SHADOW_BOXES -> {
                    val railB = s.top - CHAIR_RAIL_IN
                    out += Mark.Box(s.from, s.to, railB, s.top, heavy = true)
                    val bTop = railB - BOX_OFF_IN
                    val bBot = (if (baseIn > 0f) baseIn else ASSUMED_BASE_IN) + BOX_OFF_IN
                    val bh = bTop - bBot
                    if (bh < 4f) return@forEachIndexed
                    // Longer than they are tall: as many as fit with the gap between and at the ends.
                    val n = if (counts[i] > 0) counts[i] else max(1, floor((len - BOX_GAP_IN) / (bh + BOX_GAP_IN)).toInt())
                    val bw = (len - (n + 1) * BOX_GAP_IN) / n
                    if (bw < 4f) return@forEachIndexed
                    for (k in 0 until n) {
                        val a = s.from + BOX_GAP_IN + k * (bw + BOX_GAP_IN)
                        out += Mark.Box(a, a + bw, bBot, bTop)
                        out += Mark.Box(a + BOX_MOLDING_IN, a + bw - BOX_MOLDING_IN, bBot + BOX_MOLDING_IN, bTop - BOX_MOLDING_IN)
                    }
                }
                TILE -> {
                    out += Mark.Box(s.from, s.to, 0f, s.top, tile = true)
                    // The bullnose course at the top.
                    out += Mark.Seg(s.from, s.top - TILE_COURSE_IN, s.to, s.top - TILE_COURSE_IN)
                    out += Mark.Box(s.from, s.to, s.top - 0.5f, s.top, heavy = true)
                }
            }
        }
        return out
    }
}
