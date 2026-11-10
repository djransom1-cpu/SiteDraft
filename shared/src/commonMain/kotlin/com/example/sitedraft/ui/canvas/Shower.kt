package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.example.sitedraft.util.format
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Showers built from one plan symbol (category [PlanSymbols.SHOWERS]). The symbol's footprint is
 * the shower's inside, finished wall to finished wall, with the curb inside the footprint along
 * the open side(s). Like cabinets, the back is at the top of the unrotated symbol, the left wall
 * on the left and the open (glass) side at the bottom.
 *
 * - Alcove: tiled walls back, left and right; glass across the front.
 * - Corner: tiled walls back and left; glass across the front and down the right side.
 * - Neo-angle: tiled walls back and left; the front-right corner cut at 45 degrees with a short
 *   fixed return on the front, one on the right, and the door on the diagonal.
 * - Walk-in: tiled walls back, left and right; a fixed glass panel with an open entry, no curb.
 *
 * Everything else (glass, curb, tile, valve, head, niche, bench, drain) is kept as numbers in
 * the symbol's properties, so it saves with the drawing and every view rebuilds from it. Each
 * wall gets a live elevation through [buildElevation] (an [ElevationSpec] whose
 * [ElevationSpec.showerFace] is set).
 */
object Shower {
    enum class Type { ALCOVE, CORNER, NEO_ANGLE, WALK_IN }

    val library = listOf(
        "Alcove Shower 60x32", "Alcove Shower 60x36", "Alcove Shower 48x36", "Alcove Shower 36x36",
        "Corner Shower 36x36", "Corner Shower 48x36",
        "Neo-Angle Shower 36x36", "Neo-Angle Shower 42x42",
        "Walk-In Shower 60x36", "Walk-In Shower 72x42"
    )

    /** Footprint (width, depth) in inches, read from the "WxD" at the end of the name. */
    val footprints: Map<String, Pair<Float, Float>> = library.associateWith { name ->
        val dims = name.substringAfterLast(' ').split('x')
        Pair(dims[0].toFloat(), dims[1].toFloat())
    }

    fun type(name: String): Type = when {
        name.startsWith("Corner") -> Type.CORNER
        name.startsWith("Neo") -> Type.NEO_ANGLE
        name.startsWith("Walk") -> Type.WALK_IN
        else -> Type.ALCOVE
    }

    // ---- Faces (walls of the shower), also the codes used for which wall a part is on ----
    const val BACK = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val FRONT = 3
    const val NONE = 3 // for niche/bench: "no niche", "no bench"
    val faceLabels = listOf("Back wall", "Left wall", "Right wall", "Front (glass)")
    val wallChoiceLabels = listOf("Back", "Left", "Right", "None")

    /** Which faces are tiled walls (the rest are glass). */
    fun solidFaces(t: Type): List<Int> = when (t) {
        Type.ALCOVE, Type.WALK_IN -> listOf(BACK, LEFT, RIGHT)
        Type.CORNER, Type.NEO_ANGLE -> listOf(BACK, LEFT)
    }

    /** Faces that get an elevation: the tiled walls, then the glass. */
    fun faces(t: Type): List<Int> = when (t) {
        Type.CORNER -> listOf(BACK, LEFT, RIGHT, FRONT)
        Type.NEO_ANGLE -> listOf(BACK, LEFT, FRONT)
        else -> listOf(BACK, LEFT, RIGHT, FRONT)
    }

    // ---- Property keys (inches unless noted) ----
    const val GLASS = "shGlass"         // 0 door + panel, 1 door only, 2 sliding, 3 fixed panel + opening, 4 curtain
    const val DOOR_W = "shDoorW"        // door width, or the opening beside a walk-in panel
    const val DOOR_SIDE = "shDoorSide"  // 0 door/opening at the left end, 1 at the right end
    const val CURB_H = "shCurbH"        // 0 = curbless
    const val CURB_W = "shCurbW"
    const val GLASS_H = "shGlassH"
    const val TILE_H = "shTileH"
    const val CEILING = "shCeiling"
    const val TILE_IN = "shTileIn"      // wall tile width (its height on the wall)
    const val TILE_LEN = "shTileLen"    // wall tile length, laid along the wall
    const val FLOOR_TILE_LEN = "shFloorTileLen" // floor tile length (left to right in plan)
    const val TILE_PAT = "shTilePat"    // index into tilePatterns
    const val FLOOR_TILE = "shFloorTile" // floor tile size, 0 = no floor hatch
    const val DRAIN = "shDrain"         // 0 centre drain, 1 linear drain at the back
    const val VALVE_WALL = "shValveWall"
    const val VALVE_H = "shValveH"
    const val HEAD_H = "shHeadH"
    const val NICHE_WALL = "shNicheWall"
    const val NICHE_H = "shNicheH"      // to the bottom of the niche
    const val NICHE_W = "shNicheW"
    const val NICHE_T = "shNicheT"      // niche height
    const val BENCH_WALL = "shBenchWall"
    const val BENCH_H = "shBenchH"
    const val BENCH_D = "shBenchD"

    val keys = setOf(
        GLASS, DOOR_W, DOOR_SIDE, CURB_H, CURB_W, GLASS_H, TILE_H, CEILING, TILE_IN, TILE_LEN, TILE_PAT, FLOOR_TILE, FLOOR_TILE_LEN, DRAIN,
        VALVE_WALL, VALVE_H, HEAD_H, NICHE_WALL, NICHE_H, NICHE_W, NICHE_T, BENCH_WALL, BENCH_H, BENCH_D
    )

    val glassLabels = listOf("Door + panel", "Door only", "Sliding", "Fixed panel", "Curtain")
    val tilePatterns = listOf(FillPattern.TILE, FillPattern.BRICK, FillPattern.HERRINGBONE, FillPattern.HEXAGON)
    val tilePatternLabels = listOf("Grid", "Subway", "Herringbone", "Hex")

    fun defaults(name: String): Map<String, Float> {
        val t = type(name)
        return mapOf(
            GLASS to if (t == Type.WALK_IN) 3f else 0f,
            DOOR_W to 24f,
            DOOR_SIDE to 1f,
            CURB_H to if (t == Type.WALK_IN) 0f else 4f,
            CURB_W to 4.5f,
            GLASS_H to 72f,
            TILE_H to 84f,
            CEILING to 96f,
            TILE_IN to 12f,
            TILE_LEN to 24f,
            TILE_PAT to 0f,
            FLOOR_TILE to 2f,
            FLOOR_TILE_LEN to 2f,
            DRAIN to if (t == Type.WALK_IN) 1f else 0f,
            VALVE_WALL to LEFT.toFloat(),
            VALVE_H to 48f,
            HEAD_H to 80f,
            NICHE_WALL to BACK.toFloat(),
            NICHE_H to 48f,
            NICHE_W to 12f,
            NICHE_T to 24f,
            BENCH_WALL to NONE.toFloat(),
            BENCH_H to 18f,
            BENCH_D to 15f,
        )
    }

    /** A property, falling back to the type's default (drawings saved before a key existed). */
    fun prop(s: DrawingElement.Symbol, key: String): Float = s.properties[key] ?: defaults(s.symbolName)[key] ?: 0f
    fun code(s: DrawingElement.Symbol, key: String): Int = prop(s, key).toInt()

    // ---- Glass layout ----
    enum class Pane { FIXED, DOOR, SLIDE, OPEN, CURTAIN }

    /** One piece of the glass run, [from]..[to] inches along it; [hingeAtFrom] for a door. */
    data class Panel(val kind: Pane, val from: Float, val to: Float, val hingeAtFrom: Boolean = true)

    /** How a straight glass run [lengthIn] long is split, from the shower's settings. */
    fun runLayout(s: DrawingElement.Symbol, lengthIn: Float): List<Panel> {
        val dw = prop(s, DOOR_W).coerceIn(12f, lengthIn)
        val atLeft = code(s, DOOR_SIDE) == 0
        return when (code(s, GLASS)) {
            1 -> listOf(Panel(Pane.DOOR, 0f, lengthIn, hingeAtFrom = atLeft))
            2 -> listOf(Panel(Pane.SLIDE, 0f, lengthIn * 0.55f), Panel(Pane.SLIDE, lengthIn * 0.45f, lengthIn))
            3 -> if (atLeft) listOf(Panel(Pane.OPEN, 0f, dw), Panel(Pane.FIXED, dw, lengthIn))
            else listOf(Panel(Pane.FIXED, 0f, lengthIn - dw), Panel(Pane.OPEN, lengthIn - dw, lengthIn))
            4 -> listOf(Panel(Pane.CURTAIN, 0f, lengthIn))
            // Door + panel: the door at the chosen end, hinged on the wall side.
            else -> if (dw >= lengthIn - 1f) listOf(Panel(Pane.DOOR, 0f, lengthIn, hingeAtFrom = atLeft))
            else if (atLeft) listOf(Panel(Pane.DOOR, 0f, dw, hingeAtFrom = true), Panel(Pane.FIXED, dw, lengthIn))
            else listOf(Panel(Pane.FIXED, 0f, lengthIn - dw), Panel(Pane.DOOR, lengthIn - dw, lengthIn, hingeAtFrom = false))
        }
    }

    /** Neo-angle: how far (inches) the 45-degree cut runs along each side; leaves 12" returns. */
    fun neoCut(wIn: Float, dIn: Float): Float = (min(wIn, dIn) - 12f).coerceAtLeast(12f)

    /** Neo-angle diagonal: a door centred with fixed panels either side (or the chosen glass). */
    fun diagonalLayout(s: DrawingElement.Symbol, diagIn: Float): List<Panel> = when (code(s, GLASS)) {
        0, 1 -> {
            val dw = prop(s, DOOR_W).coerceIn(12f, diagIn)
            val side = (diagIn - dw) / 2f
            listOfNotNull(
                if (side > 0.5f) Panel(Pane.FIXED, 0f, side) else null,
                Panel(Pane.DOOR, side, side + dw, hingeAtFrom = code(s, DOOR_SIDE) == 0),
                if (side > 0.5f) Panel(Pane.FIXED, side + dw, diagIn) else null
            )
        }
        else -> runLayout(s, diagIn)
    }

    // ---- Plan view ----

    /** How a plan part is inked: outline, accent (fixtures, glass leaf), faint, glass, dashed. */
    enum class Ink { LINE, THIN, ACCENT, FAINT, GLASS, DASHED, FAINT_DASHED }

    /** Plan geometry in the shower's own frame (origin at its centre, back at the top). */
    sealed class Part {
        data class Seg(val a: Offset, val b: Offset, val ink: Ink) : Part()
        data class Poly(val points: List<Offset>, val closed: Boolean, val ink: Ink) : Part()
        data class Ring(val c: Offset, val r: Float, val ink: Ink) : Part()
        data class Label(val text: String, val c: Offset, val size: Float, val ink: Ink) : Part()
    }

    /** The parts, plus the floor outline inside the curb and the floor tile size (px; 0 = none). */
    data class PlanGeometry(val parts: List<Part>, val floor: List<Offset>, val floorTile: Float, val floorTileLength: Float = floorTile)

    /**
     * Everything the plan shows, worked out once so the canvas and the DXF export draw the same
     * thing. [w]/[h] are the symbol's size in px, [inch] px per inch.
     */
    fun planGeometry(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float): PlanGeometry {
        val t = type(s.symbolName)
        val hw = w / 2f
        val hh = h / 2f
        val curbH = prop(s, CURB_H)
        val c = if (curbH > 0f) prop(s, CURB_W) * inch else 0f
        val glassInset = if (c > 0f) c / 2f else inch * 0.5f
        val parts = mutableListOf<Part>()
        fun rect(r: Rect, ink: Ink) = parts.add(Part.Poly(listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft), true, ink))

        // Outline and floor (inside the curb).
        val outline: List<Offset>
        val floor: List<Offset>
        if (t == Type.NEO_ANGLE) {
            val a = neoCut(w / inch, h / inch) * inch
            outline = listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh - a), Offset(hw - a, hh), Offset(-hw, hh))
            val k = c * sqrt(2f)
            floor = listOf(
                Offset(-hw, -hh), Offset(hw - c, -hh), Offset(hw - c, hh - a - k + c),
                Offset(hw - a - k + c, hh - c), Offset(-hw, hh - c)
            )
        } else {
            outline = listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh))
            val right = if (t == Type.CORNER) hw - c else hw
            floor = listOf(Offset(-hw, -hh), Offset(right, -hh), Offset(right, hh - c), Offset(-hw, hh - c))
        }
        parts += Part.Poly(outline, true, Ink.LINE)
        // Curb edge (the inside line of the curb).
        if (c > 0f) parts += Part.Poly(floor, true, Ink.THIN)

        // Bench along its wall, solid (below the cut plane).
        val benchWall = code(s, BENCH_WALL)
        if (benchWall != NONE && benchWall in solidFaces(t)) {
            val d = prop(s, BENCH_D) * inch
            val r = when (benchWall) {
                BACK -> Rect(-hw, -hh, if (t == Type.CORNER || t == Type.NEO_ANGLE) hw - c else hw, -hh + d)
                LEFT -> Rect(-hw, -hh, -hw + d, hh - c)
                else -> Rect(hw - d, -hh, hw, hh - c)
            }
            rect(r, Ink.THIN)
            parts += Part.Label("BENCH", r.center, (inch * 3f).coerceAtLeast(6f), Ink.LINE)
        }

        // Drain.
        if (code(s, DRAIN) == 1) {
            val right = if (t == Type.CORNER || t == Type.NEO_ANGLE) hw - c else hw
            rect(Rect(-hw + inch * 3f, -hh + inch * 3f, right - inch * 3f, -hh + inch * 5.5f), Ink.ACCENT)
        } else {
            val cx = if (t == Type.CORNER || t == Type.NEO_ANGLE) -c / 2f else 0f
            val cy = -c / 2f
            val d = inch * 2f
            rect(Rect(cx - d, cy - d, cx + d, cy + d), Ink.ACCENT)
            parts += Part.Seg(Offset(cx - d, cy - d), Offset(cx + d, cy + d), Ink.ACCENT)
            parts += Part.Seg(Offset(cx - d, cy + d), Offset(cx + d, cy - d), Ink.ACCENT)
        }

        // Valve (and head) marked on its wall.
        val valveWall = code(s, VALVE_WALL)
        if (valveWall in solidFaces(t)) {
            val p = when (valveWall) {
                BACK -> Offset(0f, -hh + inch * 2f)
                LEFT -> Offset(-hw + inch * 2f, -c / 2f)
                else -> Offset(hw - inch * 2f, -c / 2f)
            }
            parts += Part.Ring(p, inch * 2f, Ink.ACCENT)
            parts += Part.Label("V", p, (inch * 2.5f).coerceAtLeast(5f), Ink.ACCENT)
        }
        // Niche: dashed, a recess in the wall above the cut plane.
        val nicheWall = code(s, NICHE_WALL)
        if (nicheWall != NONE && nicheWall in solidFaces(t)) {
            val nw = prop(s, NICHE_W) * inch
            val nd = inch * 3.5f
            val along = nicheAlongIn(s, nicheWall, if (nicheWall == BACK) w / inch else h / inch) * inch
            val r = when (nicheWall) {
                BACK -> Rect(-hw + along - nw / 2f, -hh - nd, -hw + along + nw / 2f, -hh)
                LEFT -> Rect(-hw - nd, hh - along - nw / 2f, -hw, hh - along + nw / 2f)
                else -> Rect(hw, -hh + along - nw / 2f, hw + nd, -hh + along + nw / 2f)
            }
            rect(r, Ink.DASHED)
        }

        // Glass.
        fun run(from: Offset, to: Offset, panels: List<Panel>) {
            val dir = (to - from) / (to - from).getDistance()
            val normal = Offset(-dir.y, dir.x) // towards the outside (the bathroom)
            fun at(alongIn: Float) = from + dir * (alongIn * inch)
            for (p in panels) {
                val a = at(p.from)
                val b = at(p.to)
                when (p.kind) {
                    Pane.FIXED -> parts += Part.Seg(a, b, Ink.GLASS)
                    Pane.SLIDE -> {
                        val off = normal * (inch * 0.75f) * (if (p.from == 0f) 1f else -1f)
                        parts += Part.Seg(a + off, b + off, Ink.GLASS)
                    }
                    Pane.DOOR -> {
                        val hinge = if (p.hingeAtFrom) a else b
                        val latch = if (p.hingeAtFrom) b else a
                        val open = hinge + normal * (b - a).getDistance()
                        parts += Part.Seg(hinge, open, Ink.GLASS)
                        // Swing from the closed latch point round to the open leaf.
                        parts += Part.Poly((0..16).map { i ->
                            val ang = i / 16.0 * (PI / 2.0)
                            hinge + (latch - hinge) * cos(ang).toFloat() + (open - hinge) * sin(ang).toFloat()
                        }, false, Ink.FAINT)
                    }
                    Pane.OPEN -> Unit
                    Pane.CURTAIN -> {
                        val off = normal * (-inch)
                        parts += Part.Seg(a + off, b + off, Ink.DASHED)
                    }
                }
            }
        }
        val gy = hh - glassInset
        when (t) {
            Type.ALCOVE, Type.WALK_IN -> run(Offset(-hw, gy), Offset(hw, gy), runLayout(s, w / inch))
            Type.CORNER -> {
                val gx = hw - glassInset
                run(Offset(-hw, gy), Offset(gx, gy), runLayout(s, (w - glassInset) / inch))
                parts += Part.Seg(Offset(gx, -hh), Offset(gx, gy), Ink.GLASS)
            }
            Type.NEO_ANGLE -> {
                val a = neoCut(w / inch, h / inch) * inch
                val k = glassInset * sqrt(2f) - glassInset
                val gx = hw - glassInset
                val p1 = Offset(gx, hh - a - k)       // right return meets the diagonal
                val p2 = Offset(hw - a - k, gy)       // diagonal meets the front return
                parts += Part.Seg(Offset(gx, -hh), p1, Ink.GLASS)  // right return
                parts += Part.Seg(Offset(-hw, gy), p2, Ink.GLASS)  // front return
                run(p2, p1, diagonalLayout(s, (p1 - p2).getDistance() / inch))
            }
        }
        // Curbless: a faint dashed line where the floor meets the bathroom.
        if (c == 0f && (t == Type.ALCOVE || t == Type.WALK_IN)) parts += Part.Seg(Offset(-hw, hh), Offset(hw, hh), Ink.FAINT_DASHED)

        val floorTile = prop(s, FLOOR_TILE) * inch
        val floorLen = prop(s, FLOOR_TILE_LEN).let { if (it > 0f) it * inch else floorTile }
        return PlanGeometry(parts, floor, if (floorTile >= inch * 0.5f) floorTile else 0f, floorLen.coerceAtLeast(inch * 0.5f))
    }

    /**
     * The floor tile grid lines (before clipping to the floor) from the back-left corner: [tile]
     * front to back, [length] left to right.
     */
    fun floorGrid(floor: List<Offset>, tile: Float, length: Float = tile): List<Pair<Offset, Offset>> {
        if (tile <= 0f || floor.isEmpty()) return emptyList()
        val left = floor.minOf { it.x }
        val right = floor.maxOf { it.x }
        val top = floor.minOf { it.y }
        val bottom = floor.maxOf { it.y }
        val out = mutableListOf<Pair<Offset, Offset>>()
        val across = if (length > 0f) length else tile
        var x = left + across
        while (x < right) { out += Offset(x, top) to Offset(x, bottom); x += across }
        var y = top + tile
        while (y < bottom) { out += Offset(left, y) to Offset(right, y); y += tile }
        return out
    }

    /** Draws the shower centred on the origin (the caller has translated and rotated). */
    fun DrawScope.drawShowerPlan(
        s: DrawingElement.Symbol, w: Float, h: Float, inch: Float,
        lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke
    ) {
        val g = planGeometry(s, w, h, inch)
        val glass = Stroke(width = line.width * 1.3f, cap = StrokeCap.Butt)
        val dashed = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 2f, inch * 1.5f)))
        if (g.floorTile > 0f) {
            val floorPath = Path().apply {
                moveTo(g.floor[0].x, g.floor[0].y); g.floor.drop(1).forEach { lineTo(it.x, it.y) }; close()
            }
            clipPath(floorPath) {
                floorGrid(g.floor, g.floorTile, g.floorTileLength).forEach { (a, b) -> drawLine(faint, a, b, thin.width * 0.6f) }
            }
        }
        fun color(ink: Ink) = when (ink) {
            Ink.LINE, Ink.THIN -> lineColor
            Ink.FAINT, Ink.FAINT_DASHED -> faint
            else -> accentColor
        }
        fun stroke(ink: Ink) = when (ink) {
            Ink.LINE -> line
            Ink.GLASS -> glass
            Ink.DASHED, Ink.FAINT_DASHED -> dashed
            else -> thin
        }
        for (p in g.parts) when (p) {
            is Part.Seg -> drawPath(Path().apply { moveTo(p.a.x, p.a.y); lineTo(p.b.x, p.b.y) }, color = color(p.ink), style = stroke(p.ink))
            is Part.Poly -> drawPath(Path().apply {
                moveTo(p.points[0].x, p.points[0].y); p.points.drop(1).forEach { lineTo(it.x, it.y) }; if (p.closed) close()
            }, color = color(p.ink), style = stroke(p.ink))
            is Part.Ring -> drawCircle(color = color(p.ink), radius = p.r, center = p.c, style = stroke(p.ink))
            is Part.Label -> drawLabel(p.text, p.c, p.size, color(p.ink))
        }
    }

    /**
     * Where the niche is centred along its wall, inches from the viewer's left in that wall's
     * elevation: the middle of the wall, or 70% along when the valve shares the wall.
     */
    fun nicheAlongIn(s: DrawingElement.Symbol, wall: Int, wallLengthIn: Float): Float =
        if (code(s, VALVE_WALL) == wall) wallLengthIn * 0.7f else wallLengthIn / 2f

    // ---- Elevations ----

    /** Length (inches) of a face as drawn in its elevation; the neo-angle front is unfolded. */
    fun faceLengthIn(s: DrawingElement.Symbol, face: Int, state: CanvasState): Float {
        val inch = state.inchesToPx(1f)
        val w = s.size.width / inch
        val d = s.size.height / inch
        return when (face) {
            BACK -> w
            LEFT, RIGHT -> d
            else -> if (type(s.symbolName) == Type.NEO_ANGLE) {
                val a = neoCut(w, d)
                (w - a) + a * sqrt(2f) + (d - a)
            } else w
        }
    }

    fun elevationSize(s: DrawingElement.Symbol, face: Int, state: CanvasState): Size =
        Size(state.inchesToPx(faceLengthIn(s, face, state)), state.inchesToPx(prop(s, CEILING)))

    /**
     * One face of the shower as canvas elements, [origin] the left end of the floor line.
     * Tiled walls are seen from inside the shower; the glass from outside it.
     */
    fun buildElevation(
        s: DrawingElement.Symbol, face: Int, origin: Offset, state: CanvasState, color: Color
    ): List<DrawingElement> {
        val inch = state.inchesToPx(1f)
        val t = type(s.symbolName)
        val length = faceLengthIn(s, face, state)
        val ceiling = prop(s, CEILING)
        val tileH = prop(s, TILE_H).coerceAtMost(ceiling)
        val curbH = prop(s, CURB_H)
        val curbW = if (curbH > 0f) prop(s, CURB_W) else 0f
        val glassH = prop(s, GLASS_H)
        val background = state.backgroundColor
        val heavy = 3f
        val medium = 2f
        val light = 1f
        val out = mutableListOf<DrawingElement>()

        fun px(xIn: Float, hIn: Float) = Offset(origin.x + xIn * inch, origin.y - hIn * inch)
        fun line(x1: Float, h1: Float, x2: Float, h2: Float, sw: Float = light) {
            out += DrawingElement.Line(start = px(x1, h1), end = px(x2, h2), color = color, strokeWidth = sw)
        }
        fun rect(x1: Float, x2: Float, hBottom: Float, hTop: Float, sw: Float = medium, fill: Fill = Fill()) {
            if (x2 <= x1 || hTop <= hBottom) return
            out += DrawingElement.Rectangle(
                topLeft = px(x1, hTop), size = Size((x2 - x1) * inch, (hTop - hBottom) * inch),
                color = color, strokeWidth = sw, fill = fill
            )
        }
        fun blank() = Fill(pattern = FillPattern.SOLID, color = background)
        fun text(s: String, xIn: Float, hIn: Float, sizeIn: Float = 3f) {
            out += DrawingElement.Text(text = s, topLeft = px(xIn, hIn), fontSize = (sizeIn * inch).coerceIn(8f, 30f), color = color)
        }
        fun feet(inches: Float) = if (state.unitSystem == UnitSystem.METRIC) inches * 0.0254f else inches / 12f
        /** A dimension between two points (inches along, inches up); [offsetIn] off the measured line. */
        fun dim(x1: Float, h1: Float, x2: Float, h2: Float, offsetIn: Float) {
            val a = px(x1, h1)
            val b = px(x2, h2)
            val len = (b - a).getDistance() / inch
            if (len < 0.5f) return
            out += DrawingElement.Dimension(
                start = a, end = b, value = feet(len), unit = state.unitSystem,
                format = state.dimensionFormat, color = color, offset = offsetIn * inch
            )
        }

        val solid = face in solidFaces(t)
        // Floor line across the face.
        line(0f, 0f, length, 0f, heavy)

        if (solid) {
            // The wall: frame to the ceiling, tile hatch up to the tile height.
            rect(0f, length, 0f, ceiling, medium)
            val pattern = tilePatterns.getOrElse(code(s, TILE_PAT)) { FillPattern.TILE }
            val tileFill = Fill(
                pattern = pattern, spacing = prop(s, TILE_IN).coerceAtLeast(1f) * inch, lineWidth = 0.75f,
                length = prop(s, TILE_LEN).let { if (it > 0f) it * inch else 0f }
            )
            rect(0f, length, 0f, tileH, light, tileFill)
            if (tileH < ceiling) line(0f, tileH, length, tileH, medium) // top of tile

            // Which end of this wall is the front (the curb / glass side), as the viewer sees it.
            // Back wall: neither. Left wall seen from inside: front on the viewer's left.
            val frontAtLeft = face == LEFT
            val frontAtRight = face == RIGHT

            // Curb and glass seen end-on where this wall meets the open side.
            fun curbAndGlassEdge(atRight: Boolean) {
                val x1 = if (atRight) length - curbW else 0f
                val x2 = if (atRight) length else curbW
                if (curbW > 0f) rect(x1, x2, 0f, curbH, medium, blank())
                val gx = if (curbW > 0f) (x1 + x2) / 2f else (if (atRight) length - 0.5f else 0.5f)
                if (code(s, GLASS) != 4) rect(gx - 0.1875f, gx + 0.1875f, curbH, glassH, light, blank())
            }
            if (frontAtLeft) curbAndGlassEdge(atRight = false)
            if (frontAtRight && t != Type.CORNER) curbAndGlassEdge(atRight = true)
            // Back wall of a corner or neo-angle shower: the right side is glass, seen end-on.
            if (face == BACK && (t == Type.CORNER || t == Type.NEO_ANGLE)) {
                if (curbW > 0f) rect(length - curbW, length, 0f, curbH, medium, blank())
                val gx = if (curbW > 0f) length - curbW / 2f else length - 0.5f
                rect(gx - 0.1875f, gx + 0.1875f, curbH, glassH, light, blank())
            }

            // Bench: face-on on its own wall, in profile on the walls beside it.
            val benchWall = code(s, BENCH_WALL)
            val benchH = prop(s, BENCH_H)
            val benchD = prop(s, BENCH_D)
            if (benchWall in solidFaces(t)) {
                if (benchWall == face) {
                    val from = if (face == LEFT) curbW else 0f
                    val to = if (face == RIGHT) length - curbW else length
                    rect(from, to, 0f, benchH, medium, blank())
                    line(from, benchH - 1.5f, to, benchH - 1.5f)
                    text("BENCH", (from + to) / 2f - 6f, benchH - 4f)
                } else {
                    // Seen from the side: where is the bench's wall in this view?
                    val atLeft = when (face) {
                        BACK -> benchWall == LEFT
                        LEFT -> false            // looking left: the back wall is on the viewer's right
                        RIGHT -> benchWall == BACK
                        else -> false
                    }
                    val visible = when (face) {
                        BACK -> benchWall == LEFT || benchWall == RIGHT
                        LEFT, RIGHT -> benchWall == BACK
                        else -> false
                    }
                    if (visible) {
                        if (atLeft) rect(0f, benchD, 0f, benchH, medium, blank()) else rect(length - benchD, length, 0f, benchH, medium, blank())
                    }
                }
            }

            // Niche.
            val nicheWall = code(s, NICHE_WALL)
            if (nicheWall == face) {
                val nw = prop(s, NICHE_W)
                val nb = prop(s, NICHE_H)
                val nt = prop(s, NICHE_T)
                val xc = nicheAlongIn(s, face, length)
                rect(xc - nw / 2f, xc + nw / 2f, nb, nb + nt, medium, blank())
                rect(xc - nw / 2f + 0.75f, xc + nw / 2f - 0.75f, nb + 0.75f, nb + nt - 0.75f, light)
                text("NICHE ${fmtIn(nw)} x ${fmtIn(nt)}", xc + nw / 2f + 2f, nb + nt / 2f + 1.5f, 2.5f)
                dim(xc + nw / 2f, 0f, xc + nw / 2f, nb, 4f)
            }

            // Valve and head.
            if (code(s, VALVE_WALL) == face) {
                val xv = length / 2f
                val valveH = prop(s, VALVE_H)
                val headH = prop(s, HEAD_H)
                out += DrawingElement.Circle(center = px(xv, valveH), radius = 3.5f * inch, color = color, strokeWidth = medium, fill = blank())
                line(xv, valveH, xv + 2.5f, valveH + 2.5f, medium) // handle
                out += DrawingElement.Circle(center = px(xv, headH), radius = 1.25f * inch, color = color, strokeWidth = light, fill = blank())
                out += DrawingElement.Circle(center = px(xv, headH - 4f), radius = 3f * inch, color = color, strokeWidth = medium, fill = blank())
                line(xv, headH, xv, headH - 1f, medium)
                text("VALVE", xv + 5f, valveH + 1.5f, 2.5f)
                text("HEAD", xv + 5f, headH + 1.5f, 2.5f)
                dim(xv - 5f, 0f, xv - 5f, valveH, 0f)
                dim(xv - 10f, 0f, xv - 10f, headH, 0f)
            }

            // Overall width under the floor, tile height and ceiling up the right side.
            dim(0f, 0f, length, 0f, 10f)
            dim(length, 0f, length, tileH, 8f)
            if (tileH < ceiling) dim(length, 0f, length, ceiling, 18f)
        } else {
            // Glass seen from outside: curb, then the panels of the run.
            if (curbW > 0f) rect(0f, length, 0f, curbH, medium)
            val bottom = curbH
            fun panelsAlong(start: Float, panels: List<Panel>) {
                for (p in panels) {
                    val a = start + p.from
                    val b = start + p.to
                    when (p.kind) {
                        Pane.FIXED -> {
                            rect(a, b, bottom, glassH, medium)
                            line(a + (b - a) * 0.3f, glassH - 10f, a + (b - a) * 0.45f, glassH - 18f) // glass glint
                        }
                        Pane.DOOR -> {
                            rect(a, b, bottom, glassH, medium)
                            val hingeX = if (p.hingeAtFrom) a else b
                            val latchX = if (p.hingeAtFrom) b - 2f else a + 2f
                            for (hh in listOf(bottom + 10f, glassH - 10f)) rect(hingeX - 1f, hingeX + 1f, hh - 1.5f, hh + 1.5f, light)
                            rect(latchX - 0.5f, latchX + 0.5f, 36f, 48f, medium) // handle
                            text("DOOR", (a + b) / 2f - 4f, glassH - 4f, 2.5f)
                            dim(a, glassH, b, glassH, -6f)
                        }
                        Pane.SLIDE -> {
                            rect(a, b, bottom + 0.5f, glassH - 0.5f, medium)
                            rect(if (p.from == 0f) b - 3f else a + 2f, if (p.from == 0f) b - 2f else a + 3f, 36f, 48f, light)
                        }
                        Pane.OPEN -> {
                            text("OPEN", (a + b) / 2f - 4f, bottom + 30f, 2.5f)
                            dim(a, glassH, b, glassH, -6f)
                        }
                        Pane.CURTAIN -> {
                            line(a, 76f, b, 76f, medium)
                            var x = a + 3f
                            while (x < b - 1f) { line(x, 74f, x, bottom + 6f, light); x += 6f }
                            text("CURTAIN", (a + b) / 2f - 6f, 70f, 2.5f)
                        }
                    }
                }
                if (panels.any { it.kind == Pane.SLIDE }) rect(start, start + (panels.maxOf { it.to }), glassH, glassH + 1.5f, light) // track
            }
            if (face == RIGHT && t == Type.CORNER) {
                panelsAlong(0f, listOf(Panel(Pane.FIXED, 0f, length)))
            } else if (t == Type.NEO_ANGLE) {
                val inchW = s.size.width / inch
                val inchD = s.size.height / inch
                val a = neoCut(inchW, inchD)
                val frontRet = inchW - a
                val diag = a * sqrt(2f)
                // Seen from outside: front return on the left, the diagonal, the right return.
                panelsAlong(0f, listOf(Panel(Pane.FIXED, 0f, frontRet)))
                panelsAlong(frontRet, diagonalLayout(s, diag))
                panelsAlong(frontRet + diag, listOf(Panel(Pane.FIXED, 0f, inchD - a)))
                line(frontRet, bottom, frontRet, glassH, heavy)
                line(frontRet + diag, bottom, frontRet + diag, glassH, heavy)
                text("Glass unfolded", 0f, -14f, 2.5f)
            } else {
                panelsAlong(0f, runLayout(s, length))
            }
            dim(0f, 0f, length, 0f, 10f)
            if (code(s, GLASS) != 4) dim(length, bottom, length, glassH, 8f)
            if (curbW > 0f) dim(0f, 0f, 0f, curbH, -6f)
        }

        // Title under the dimensions.
        val title = "Shower ${faceLabels[face].lowercase()}, ${formatFeetInches(length / 12f)} wide"
        text(title, 0f, -22f, 5f)
        return out
    }

    private fun fmtIn(v: Float): String =
        (if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)) + "\""

    // ---- Walls around a shower ----

    /** 2x4 walls (outside the footprint) along the shower's tiled sides, as plan symbols. */
    fun wallsFor(s: DrawingElement.Symbol, state: CanvasState, wallName: String = "2x4 Wall"): List<DrawingElement.Symbol> {
        val thick = state.inchesToPx(PlanSymbols.footprintInches[wallName]?.second ?: 4.5f)
        val hw = s.size.width / 2f
        val hh = s.size.height / 2f
        val faces = solidFaces(type(s.symbolName))
        val hasLeft = LEFT in faces
        val hasRight = RIGHT in faces
        // Local rectangles (unrotated, centred on the shower). The back wall runs over the
        // side walls' ends so the corners close.
        val rects = buildList {
            if (BACK in faces) add(Rect(-hw - (if (hasLeft) thick else 0f), -hh - thick, hw + (if (hasRight) thick else 0f), -hh))
            if (hasLeft) add(Rect(-hw - thick, -hh, -hw, hh))
            if (hasRight) add(Rect(hw, -hh, hw + thick, hh))
        }
        val quarter = ((s.rotation % 360f + 360f) % 360f)
        val swap = (quarter in 45f..135f) || (quarter in 225f..315f)
        return rects.map { r ->
            val center = rotateAbout(s.center + r.center, s.center, s.rotation)
            val size = if (swap) Size(r.height, r.width) else Size(r.width, r.height)
            DrawingElement.Symbol(
                symbolName = wallName,
                category = PlanSymbols.WALLS,
                center = center,
                size = size,
                color = s.color,
                properties = mapOf(
                    "widthInches" to (PlanSymbols.footprintInches[wallName]?.first ?: 48f),
                    "depthInches" to (PlanSymbols.footprintInches[wallName]?.second ?: 4.5f)
                )
            )
        }
    }
}
