package com.example.sitedraft.ui.canvas

import com.example.sitedraft.util.format
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.min

/** Which side of a wall the viewer stands on when looking at its elevation (screen directions: up = north). */
@kotlinx.serialization.Serializable
enum class ViewerSide(val label: String, val looking: String) {
    BELOW("From below", "looking north"),
    ABOVE("From above", "looking south"),
    RIGHT("From the right", "looking west"),
    LEFT("From the left", "looking east"),
}

/** Heights (inches) that the elevation of a wall is built from. The wall's length comes from the plan. */
@kotlinx.serialization.Serializable
data class ElevationSpec(
    val wallId: String,
    val side: ViewerSide,
    val wallHeightIn: Float = 96f,
    val counterHeightIn: Float = 36f,
    val upperBottomIn: Float = 54f,
    val upperHeightIn: Float = 30f,
    val sillIn: Float = 36f,
    val windowHeightIn: Float = 48f,
    val doorHeightIn: Float = 80f,
    /** Set (0 back, 1 left, 2 right, 3 front) when [wallId] is a shower: that face of it. */
    val showerFace: Int = -1,
    /** Wall tile (a backsplash, a wainscot) from [tileFromIn] to [tileToIn] up the wall; none when to <= from. */
    val tileFromIn: Float = 0f,
    val tileToIn: Float = 0f,
    /** Index into [Shower.tilePatterns]: grid, subway, herringbone, hex. */
    val tilePattern: Int = 0,
    val tileSizeIn: Float = 4f,
    /** The tile's long side, laid along the wall; 0 = square (or the pattern's own proportion). */
    val tileLengthIn: Float = 0f,
    /** Set (0 right side, 1 left side) when [wallId] is a stair: its side elevation. */
    val stairSide: Int = -1,
)

/**
 * Builds an interior elevation of one wall from the floor plan: everything standing against the
 * viewed face of the wall (cabinets, appliances, fixtures), the doors and windows in it, and the
 * walls that butt into it, drawn to the same scale as plain lines, rectangles and text that can
 * then be edited like anything else on the canvas.
 */
object Elevation {
    /** How far (inches) an item's back may stand off the wall face and still count as against it. */
    const val BACK_REACH_IN = 6f

    /** Closet units: 84" tall carcasses; rods at the standard 66" (single) and 40" / 80" (double). */
    const val CLOSET_UNIT_HEIGHT_IN = 84f
    const val SINGLE_ROD_IN = 66f
    const val DOUBLE_ROD_LOWER_IN = 40f
    const val DOUBLE_ROD_UPPER_IN = 80f

    /** How far a farmhouse sink's apron reaches down from the counter top, inches. */
    const val FARMHOUSE_APRON_IN = 10f

    enum class Kind { OPENING, FIXTURE, CUT_WALL }

    /** A plan symbol seen in the elevation, with its extent along the wall measured from the viewer's left end (px). */
    data class WallItem(val symbol: DrawingElement.Symbol, val from: Float, val to: Float, val kind: Kind)

    fun isHorizontal(wall: DrawingElement.Symbol) = wall.size.width >= wall.size.height

    fun sidesFor(wall: DrawingElement.Symbol): List<ViewerSide> =
        if (isHorizontal(wall)) listOf(ViewerSide.BELOW, ViewerSide.ABOVE) else listOf(ViewerSide.RIGHT, ViewerSide.LEFT)

    /**
     * The side with more cabinets and fixtures against it. Doors and windows show from both
     * sides, so they do not count. On a tie (a bare wall, or one with only a door) the viewer
     * stands inside the room: on the side facing the rest of the plan's walls. Before, a tie went
     * to the first side in the list, which put the viewer OUTSIDE an east or south wall.
     */
    fun defaultSide(wall: DrawingElement.Symbol, state: CanvasState): ViewerSide {
        val sides = sidesFor(wall)
        val counts = sides.associateWith { side -> itemsOnWall(wall, side, state).count { it.kind != Kind.OPENING } }
        val best = counts.values.maxOrNull() ?: 0
        val tied = sides.filter { counts[it] == best }
        if (tied.size == 1) return tied[0]
        return interiorSide(wall, state) ?: tied[0]
    }

    /** The side of [wall] that faces the middle of the plan's other walls, or null with no other walls. */
    fun interiorSide(wall: DrawingElement.Symbol, state: CanvasState): ViewerSide? {
        val others = state.elements.filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.WALLS && it.id != wall.id }
            .map { FloorPlan.bounds(it).center }
        if (others.isEmpty()) return null
        val middle = Offset(others.map { it.x }.average().toFloat(), others.map { it.y }.average().toFloat())
        val wb = FloorPlan.bounds(wall)
        return if (isHorizontal(wall)) {
            if (middle.y >= wb.center.y) ViewerSide.BELOW else ViewerSide.ABOVE
        } else {
            if (middle.x >= wb.center.x) ViewerSide.RIGHT else ViewerSide.LEFT
        }
    }

    /**
     * The point of a door or window that lies in its wall. Windows, sliding doors and cased
     * openings are centred in the wall. A hinged door is a square the size of its swing whose
     * threshold (the unrotated bottom edge) sits on the wall's centre line, so its centre is half
     * a door-width off the wall; testing the centre is why doors never showed in elevations.
     */
    fun openingAnchor(symbol: DrawingElement.Symbol): Offset {
        if (!PlanSymbols.isDoor(symbol.symbolName)) return symbol.center
        val threshold = symbol.center + Offset(0f, symbol.size.height / 2f)
        return rotateAbout(threshold, symbol.center, symbol.rotation)
    }

    fun lengthPx(wall: DrawingElement.Symbol): Float = if (isHorizontal(wall)) wall.size.width else wall.size.height

    fun sizePx(wall: DrawingElement.Symbol, spec: ElevationSpec, state: CanvasState): Size =
        if (spec.showerFace >= 0 && wall.category == PlanSymbols.SHOWERS) Shower.elevationSize(wall, spec.showerFace, state)
        else if (spec.stairSide >= 0 && wall.category == PlanSymbols.STAIRS) Stairs.elevationSize(wall, spec.stairSide, state)
        else Size(lengthPx(wall), state.inchesToPx(spec.wallHeightIn))

    /** Where a point sits along the wall, measured from the end on the viewer's LEFT. */
    fun alongOf(wall: Rect, side: ViewerSide, p: Offset): Float = when (side) {
        ViewerSide.BELOW -> p.x - wall.left
        ViewerSide.ABOVE -> wall.right - p.x
        ViewerSide.RIGHT -> wall.bottom - p.y
        ViewerSide.LEFT -> p.y - wall.top
    }

    /**
     * For a diagonal corner cabinet: is its angled door at the right-hand (to) end of its run
     * along this wall? The door sits across the front-right corner of the unrotated symbol
     * (see drawCabinet), so turn that point by the symbol's rotation and see which way it lies.
     */
    fun cornerDoorAtToEnd(symbol: DrawingElement.Symbol, wall: DrawingElement.Symbol, side: ViewerSide, state: CanvasState): Boolean {
        val wb = FloorPlan.bounds(wall)
        val d = state.inchesToPx(12f)
        val doorLocal = Offset(symbol.size.width / 2f - d / 2f, symbol.size.height / 2f - d / 2f)
        val doorWorld = rotateAbout(symbol.center + doorLocal, symbol.center, symbol.rotation)
        return alongOf(wb, side, doorWorld) > alongOf(wb, side, symbol.center)
    }

    /** Extent of a footprint along the wall, measured from the end on the viewer's LEFT. */
    fun alongExtent(wall: Rect, side: ViewerSide, b: Rect): Pair<Float, Float> = when (side) {
        ViewerSide.BELOW -> Pair(b.left - wall.left, b.right - wall.left)
        ViewerSide.ABOVE -> Pair(wall.right - b.right, wall.right - b.left)
        ViewerSide.RIGHT -> Pair(wall.bottom - b.bottom, wall.bottom - b.top)
        ViewerSide.LEFT -> Pair(b.top - wall.top, b.bottom - wall.top)
    }

    fun itemsOnWall(wall: DrawingElement.Symbol, side: ViewerSide, state: CanvasState): List<WallItem> {
        val wb = FloorPlan.bounds(wall)
        val inch = state.inchesToPx(1f)
        val length = lengthPx(wall)
        val horizontalWall = isHorizontal(wall)
        val items = mutableListOf<WallItem>()
        for (s in state.elements.filterIsInstance<DrawingElement.Symbol>()) {
            if (s.id == wall.id) continue
            val b = FloorPlan.bounds(s)
            val (from, to) = alongExtent(wb, side, b)
            if (to <= 0f || from >= length) continue
            val kind: Kind = when (s.category) {
                PlanSymbols.DOORS_WINDOWS -> if (wb.inflate(2f * inch).contains(openingAnchor(s))) Kind.OPENING else continue
                PlanSymbols.WALLS -> {
                    val perpendicular = (b.width >= b.height) != horizontalWall
                    val touches = when (side) {
                        ViewerSide.BELOW -> abs(b.top - wb.bottom) <= inch
                        ViewerSide.ABOVE -> abs(b.bottom - wb.top) <= inch
                        ViewerSide.RIGHT -> abs(b.left - wb.right) <= inch
                        ViewerSide.LEFT -> abs(b.right - wb.left) <= inch
                    }
                    if (perpendicular && touches) Kind.CUT_WALL else continue
                }
                PlanSymbols.FIXTURES, PlanSymbols.CABINETS, PlanSymbols.OUTDOOR, PlanSymbols.CLOSET, PlanSymbols.FURNITURE -> {
                    if (s.symbolName.startsWith("Island")) continue
                    // Signed gap from the viewed face to the item's back edge: positive = in front of the wall.
                    val back = when (side) {
                        ViewerSide.BELOW -> b.top - wb.bottom
                        ViewerSide.ABOVE -> wb.top - b.bottom
                        ViewerSide.RIGHT -> b.left - wb.right
                        ViewerSide.LEFT -> wb.left - b.right
                    }
                    if (back >= -3f * inch && back <= BACK_REACH_IN * inch) Kind.FIXTURE else continue
                }
                else -> continue
            }
            items += WallItem(s, from.coerceIn(0f, length), to.coerceIn(0f, length), kind)
        }
        return items.sortedBy { it.from }
    }

    /**
     * The elevation as canvas elements. [origin] is the bottom-left corner of the floor line; the
     * view runs to the right and up from there, at the drawing's scale.
     */
    /**
     * The view with an ink that shows on [background]. The drawing's default ink is white (made
     * for the Blueprint canvas), and an elevation is plain lines and boxes that keep their colour,
     * unlike plan symbols, which draw black on a light canvas. So on the White canvas an elevation
     * placed with white ink came out white on white: invisible. Ink too close to the canvas is
     * swapped for black (light canvas) or white (dark canvas); any other colour is kept.
     */
    fun withVisibleInk(view: DrawingElement.ElevationView, background: androidx.compose.ui.graphics.Color): DrawingElement.ElevationView =
        view.copy(color = visibleInk(view.color, background))

    fun visibleInk(ink: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
        val lightCanvas = background.luminance() > 0.5f
        val lightInk = ink.luminance() > 0.5f
        return if (lightCanvas != lightInk) ink
        else if (lightCanvas) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
    }

    /** What an [DrawingElement.ElevationView] shows right now: rebuilt from the plan every time it is drawn. */
    fun partsOf(view: DrawingElement.ElevationView, state: CanvasState): List<DrawingElement> {
        val wall = state.elements.find { it.id == view.spec.wallId } as? DrawingElement.Symbol
        return when {
            wall == null -> missingWall(view, state)
            view.spec.showerFace >= 0 && wall.category == PlanSymbols.SHOWERS ->
                Shower.buildElevation(wall, view.spec.showerFace, view.origin, state, view.color)
            view.spec.stairSide >= 0 && wall.category == PlanSymbols.STAIRS ->
                Stairs.buildElevation(wall, view.spec.stairSide, view.origin, state, view.color)
            else -> build(wall, view.spec, view.origin, state, view.color)
        }
    }

    /** The wall this view was made from is gone: keep the frame so the view can still be found and deleted. */
    fun missingWall(view: DrawingElement.ElevationView, state: CanvasState): List<DrawingElement> {
        val h = state.inchesToPx(view.spec.wallHeightIn)
        return listOf(
            DrawingElement.Rectangle(topLeft = Offset(view.origin.x, view.origin.y - h), size = Size(view.size.width, h), color = view.color, strokeWidth = 1f),
            DrawingElement.Text(text = "Elevation: its wall was deleted", topLeft = view.origin + Offset(0f, state.inchesToPx(2f)), fontSize = (state.inchesToPx(6f)).coerceIn(10f, 36f), color = view.color)
        )
    }

    fun build(wall: DrawingElement.Symbol, spec: ElevationSpec, origin: Offset, state: CanvasState, color: androidx.compose.ui.graphics.Color = state.currentColor): List<DrawingElement> {
        val inch = state.inchesToPx(1f)
        val length = lengthPx(wall)
        val heavy = 3f
        val medium = 2f
        val light = 1f
        val out = mutableListOf<DrawingElement>()

        fun px(xLocal: Float, hIn: Float) = Offset(origin.x + xLocal, origin.y - hIn * inch)
        fun line(x1: Float, h1: Float, x2: Float, h2: Float, sw: Float = light) {
            out += DrawingElement.Line(start = px(x1, h1), end = px(x2, h2), color = color, strokeWidth = sw)
        }
        // With wall tile on, everything standing in front of the wall hides the tile behind it.
        val tiled = spec.tileToIn > spec.tileFromIn
        val opaque = if (tiled) Fill(pattern = FillPattern.SOLID, color = state.backgroundColor) else Fill()
        fun rect(xFrom: Float, xTo: Float, hBottom: Float, hTop: Float, sw: Float = medium, fill: Fill = opaque) {
            if (xTo <= xFrom || hTop <= hBottom) return
            out += DrawingElement.Rectangle(topLeft = px(xFrom, hTop), size = Size(xTo - xFrom, (hTop - hBottom) * inch), color = color, strokeWidth = sw, fill = fill)
        }
        fun circle(xc: Float, hc: Float, rIn: Float, sw: Float = light) {
            out += DrawingElement.Circle(center = px(xc, hc), radius = rIn * inch, color = color, strokeWidth = sw)
        }
        /**
         * Door fronts across [xFrom]..[xTo] between two heights; [count] doors (two from 30"
         * when 0). Knobs sit near the top of a base door and near the BOTTOM of an upper's
         * ([knobsLow]), where a hand reaches them.
         */
        // The door style and hardware of the cabinet being drawn (set per item below).
        var doorStyle = PlanSymbols.STYLE_SLAB
        var hardware = PlanSymbols.HW_KNOBS_PULLS
        /** The style's panel inside one door or drawer face, [x1]..[x2] px by [h1]..[h2] inches. */
        fun facePanel(x1: Float, x2: Float, h1: Float, h2: Float, drawer: Boolean) {
            val rail = if (drawer) 1.25f else 2.5f
            if ((x2 - x1) <= 2f * rail * inch + inch || (h2 - h1) <= 2f * rail + 1f) return
            when (doorStyle) {
                PlanSymbols.STYLE_SHAKER -> rect(x1 + rail * inch, x2 - rail * inch, h1 + rail, h2 - rail, light)
                PlanSymbols.STYLE_RAISED -> {
                    rect(x1 + rail * inch, x2 - rail * inch, h1 + rail, h2 - rail, light)
                    if (!drawer && (x2 - x1) > 9f * inch && (h2 - h1) > 9f) rect(x1 + 4f * inch, x2 - 4f * inch, h1 + 4f, h2 - 4f, light)
                }
                PlanSymbols.STYLE_GLASS -> {
                    rect(x1 + rail * inch, x2 - rail * inch, h1 + rail, h2 - rail, light)
                    if (!drawer) {
                        // Two short glints across the glass.
                        val gx = x1 + (x2 - x1) * 0.35f
                        val gh = h2 - (h2 - h1) * 0.3f
                        line(gx, gh, gx + 3f * inch, gh - 3f)
                        line(gx + 1.5f * inch, gh - 3f, gx + 4f * inch, gh - 5.5f)
                    }
                }
                else -> Unit
            }
        }
        fun doors(xFrom: Float, xTo: Float, hBottom: Float, hTop: Float, count: Int = 0, knobsLow: Boolean = false) {
            val inset = inch
            rect(xFrom + inset, xTo - inset, hBottom + 1f, hTop - 1f, light)
            val two = if (count in 1..2) count == 2 else xTo - xFrom >= 30f * inch
            val mid = (xFrom + xTo) / 2f
            if (two) line(mid, hBottom + 1f, mid, hTop - 1f)
            if (two) { facePanel(xFrom + inset, mid, hBottom + 1f, hTop - 1f, false); facePanel(mid, xTo - inset, hBottom + 1f, hTop - 1f, false) }
            else facePanel(xFrom + inset, xTo - inset, hBottom + 1f, hTop - 1f, false)
            val knobH = if (knobsLow) hBottom + 3f else minOf(hTop - 3f, hBottom + (hTop - hBottom) * 0.8f)
            val knobXs = if (two) listOf(mid - 1.5f * inch, mid + 1.5f * inch) else listOf(xTo - 2.5f * inch)
            for (kx in knobXs) {
                if (hardware == PlanSymbols.HW_BAR_PULLS) {
                    // A 5" bar standing upright by the latch edge; on an upper it starts low.
                    val (lo, hi) = if (knobsLow) Pair(hBottom + 2f, hBottom + 7f) else Pair(knobH - 2.5f, knobH + 2.5f)
                    line(kx, lo, kx, hi, medium)
                } else circle(kx, knobH, 0.5f)
            }
        }
        /**
         * A diagonal corner unit seen from one of its walls: most of its width is the blank
         * side of the cabinet, and the angled door shows foreshortened, [doorIn] wide, at the
         * end that faces the room ([doorAtTo]).
         */
        fun cornerUnit(xFrom: Float, xTo: Float, hBottom: Float, hTop: Float, doorAtTo: Boolean, doorIn: Float, upper: Boolean) {
            val doorW = minOf(doorIn * inch, xTo - xFrom)
            val doorFrom = if (doorAtTo) xTo - doorW else xFrom
            val doorTo = if (doorAtTo) xTo else xFrom + doorW
            val edge = if (doorAtTo) doorFrom else doorTo
            line(edge, hBottom, edge, hTop, light) // where the side panel meets the angled door
            doors(doorFrom, doorTo, hBottom, hTop, 1, knobsLow = upper)
        }
        /** A stack of [n] equal drawers between two heights, pulls centred. */
        fun drawers(xFrom: Float, xTo: Float, hBottom: Float, hTop: Float, n: Int) {
            val each = (hTop - hBottom) / n
            val xc2 = (xFrom + xTo) / 2f
            for (i in 0 until n) {
                val b = hBottom + each * i
                rect(xFrom + inch, xTo - inch, b + 0.5f, b + each - 0.5f, light)
                facePanel(xFrom + inch, xTo - inch, b + 0.5f, b + each - 0.5f, true)
                if (hardware == PlanSymbols.HW_KNOBS) circle(xc2, b + each / 2f, 0.5f)
                else line(xc2 - 2f * inch, b + each / 2f, xc2 + 2f * inch, b + each / 2f, medium)
            }
        }
        /** A base cabinet's chosen fronts between the toe kick and the counter. */
        fun fronts(symbol: DrawingElement.Symbol, xFrom: Float, xTo: Float, hBottom: Float, hTop: Float) {
            val count = PlanSymbols.doorCount(symbol, (xTo - xFrom) / inch)
            when (PlanSymbols.front(symbol)) {
                PlanSymbols.FRONT_DRAWER_DOORS -> {
                    // A 6" drawer under the counter, doors below it.
                    drawers(xFrom, xTo, hTop - 7f, hTop, 1)
                    doors(xFrom, xTo, hBottom, hTop - 7f, count)
                }
                PlanSymbols.FRONT_THREE_DRAWERS -> drawers(xFrom, xTo, hBottom, hTop, 3)
                PlanSymbols.FRONT_FOUR_DRAWERS -> drawers(xFrom, xTo, hBottom, hTop, 4)
                else -> doors(xFrom, xTo, hBottom, hTop, count)
            }
        }

        /**
         * A closet rod at [rodIn] off the floor with a 3/4" shelf 2" above it. Just the shelf
         * and the rod (with its end brackets): hanging clothes made the drawing too busy.
         */
        fun shelfAndRod(xFrom: Float, xTo: Float, rodIn: Float) {
            val shelfIn = rodIn + 2f
            val p = 0.75f * inch
            rect(xFrom + p, xTo - p, shelfIn, shelfIn + 0.75f, light)
            line(xFrom + p, rodIn, xTo - p, rodIn, medium)
            // Rod brackets up to the shelf at each end.
            line(xFrom + p + inch, rodIn, xFrom + p + inch, shelfIn)
            line(xTo - p - inch, rodIn, xTo - p - inch, shelfIn)
        }
        /** The carcass every closet unit shares: a 3/4" pilaster at each end, 84" tall, with a top. */
        fun closetUnit(xFrom: Float, xTo: Float) {
            val p = 0.75f * inch
            rect(xFrom, xFrom + p, 0f, CLOSET_UNIT_HEIGHT_IN, medium)
            rect(xTo - p, xTo, 0f, CLOSET_UNIT_HEIGHT_IN, medium)
            rect(xFrom + p, xTo - p, CLOSET_UNIT_HEIGHT_IN - 0.75f, CLOSET_UNIT_HEIGHT_IN, light)
        }
        /** A table or desk top at [topIn] on legs set 2" in from the ends. */
        fun tableWithLegs(xFrom: Float, xTo: Float, topIn: Float) {
            rect(xFrom, xTo, topIn - 1.5f, topIn)
            line(xFrom + 2f * inch, 0f, xFrom + 2f * inch, topIn - 1.5f, medium)
            line(xTo - 2f * inch, 0f, xTo - 2f * inch, topIn - 1.5f, medium)
        }

        // Wall tile first, so everything in front of it is drawn over it.
        if (tiled) {
            val top = spec.tileToIn.coerceAtMost(spec.wallHeightIn)
            val pattern = Shower.tilePatterns.getOrElse(spec.tilePattern) { FillPattern.TILE }
            rect(0f, length, spec.tileFromIn, top, light, Fill(
                pattern = pattern, spacing = spec.tileSizeIn.coerceAtLeast(1f) * inch, lineWidth = 0.75f,
                length = if (spec.tileLengthIn > 0f) spec.tileLengthIn * inch else 0f
            ))
        }
        // Frame: floor, ceiling, ends.
        line(0f, 0f, length, 0f, heavy)
        line(0f, spec.wallHeightIn, length, spec.wallHeightIn, medium)
        line(0f, 0f, 0f, spec.wallHeightIn, medium)
        line(length, 0f, length, spec.wallHeightIn, medium)

        val counterTop = spec.counterHeightIn
        val counterRuns = mutableListOf<Pair<Float, Float>>()
        val items = itemsOnWall(wall, spec.side, state)
        // Vanities carry their own top height; sinks set into a vanity sit at that height
        // instead of the kitchen counter's. Vanity tops are kept apart from the counter runs.
        val vanities = items.filter { it.kind == Kind.FIXTURE && it.symbol.category == PlanSymbols.CABINETS && PlanSymbols.isVanity(it.symbol.symbolName) }
        val vanityRuns = mutableListOf<Triple<Float, Float, Float>>()
        fun overlaps(a: WallItem, from: Float, to: Float) = a.from < to - 0.5f * inch && a.to > from + 0.5f * inch
        fun vanityUnder(from: Float, to: Float) = vanities.firstOrNull { overlaps(it, from, to) }
        fun hostTop(from: Float, to: Float): Float = vanityUnder(from, to)?.let { PlanSymbols.vanityTop(it.symbol) } ?: counterTop
        val sinkItems = items.filter { it.kind == Kind.FIXTURE && it.symbol.category == PlanSymbols.FIXTURES && it.symbol.symbolName in PlanSymbols.counterSinks }
        val aprons = sinkItems.filter { it.symbol.symbolName == PlanSymbols.FARMHOUSE_SINK }
        /** A faucet standing on a top at [topIn]: spout up and over the bowl. */
        fun faucet(xc: Float, topIn: Float, tall: Float = 8f) {
            line(xc, topIn, xc, topIn + tall, medium)
            line(xc, topIn + tall, xc + 4f * inch, topIn + tall - 1.5f, medium)
        }
        for (item in items) {
            doorStyle = PlanSymbols.doorStyle(item.symbol)
            hardware = PlanSymbols.hardware(item.symbol)
            val from = item.from
            val to = item.to
            val w = to - from
            val xc = (from + to) / 2f
            val name = item.symbol.symbolName
            when (item.kind) {
                Kind.CUT_WALL -> {
                    rect(from, to, 0f, spec.wallHeightIn, medium)
                    line(from, 0f, to, spec.wallHeightIn)
                    line(from, spec.wallHeightIn, to, 0f)
                }
                // Each door or window can carry its own height (and a window its sill) in its
                // properties; the elevation's wall-wide numbers are only the fallback.
                Kind.OPENING -> when {
                    PlanSymbols.isWindow(name) -> {
                        val sill = item.symbol.properties[PlanSymbols.PROP_SILL_IN] ?: spec.sillIn
                        val top = sill + (item.symbol.properties[PlanSymbols.PROP_HEIGHT_IN] ?: spec.windowHeightIn)
                        rect(from, to, sill, top, medium)
                        line(xc, sill, xc, top)
                        line(from, (sill + top) / 2f, to, (sill + top) / 2f)
                        rect(from - inch, to + inch, sill - 1.5f, sill, light) // sill
                    }
                    PlanSymbols.isOpening(name) -> {
                        // Cased opening: the hole with its casing, nothing hung in it.
                        val doorH = item.symbol.properties[PlanSymbols.PROP_HEIGHT_IN] ?: spec.doorHeightIn
                        rect(from, to, 0f, doorH, medium)
                        rect(from - 2f * inch, to + 2f * inch, 0f, doorH + 2f, light)
                    }
                    PlanSymbols.isSlidingDoor(name) -> {
                        val doorH = item.symbol.properties[PlanSymbols.PROP_HEIGHT_IN] ?: spec.doorHeightIn
                        rect(from, to, 0f, doorH, medium)
                        line(xc, 0f, xc, doorH)
                        line(xc - 2f * inch, 30f, xc - 2f * inch, 42f)
                        line(xc + 2f * inch, 30f, xc + 2f * inch, 42f)
                    }
                    else -> {
                        val doorH = item.symbol.properties[PlanSymbols.PROP_HEIGHT_IN] ?: spec.doorHeightIn
                        rect(from, to, 0f, doorH, medium)
                        rect(from + 3f * inch, to - 3f * inch, 8f, doorH - 8f, light)
                        circle(to - 3f * inch, 36f, 1f)
                    }
                }
                Kind.FIXTURE -> when {
                    PlanSymbols.isFiller(name) -> {
                        // A blank strip at base or upper height, no door.
                        if (name.startsWith("Upper")) {
                            rect(from, to, spec.upperBottomIn, spec.upperBottomIn + spec.upperHeightIn)
                        } else {
                            rect(from, to, 0f, counterTop - 1.5f)
                            line(from, 4f, to, 4f) // toe kick
                            counterRuns += Pair(from, to)
                        }
                    }
                    name.startsWith("Base") || name.startsWith("Corner Base") -> {
                        rect(from, to, 0f, counterTop - 1.5f)
                        line(from, 4f, to, 4f) // toe kick
                        // Under a farmhouse sink the apron takes the top of the cabinet face.
                        val frontsTop = if (aprons.any { overlaps(it, from, to) }) counterTop - FARMHOUSE_APRON_IN else counterTop - 1.5f
                        if (name.startsWith("Corner")) cornerUnit(from, to, 4f, counterTop - 1.5f, cornerDoorAtToEnd(item.symbol, wall, spec.side, state), 12f, upper = false)
                        else fronts(item.symbol, from, to, 4f, frontsTop)
                        counterRuns += Pair(from, to)
                    }
                    name == PlanSymbols.LYNX_36_GRILL -> {
                        // Built into the counter, per the Lynx drawing: 11" front panel below the
                        // counter line (9" control panel inside a 1 1/8" trim), hood 14 7/8" above
                        // it closed, handle bar across the hood, thermometer in the middle.
                        val panelBottom = counterTop - PlanSymbols.LYNX_36_PANEL_H
                        val hoodTop = counterTop + PlanSymbols.LYNX_36_HOOD_ABOVE_COUNTER
                        rect(from, to, panelBottom, counterTop)
                        rect(from + 1.125f * inch, to - 1.125f * inch, panelBottom + 1f, panelBottom + 10f, light)
                        for (x in listOf(0.1f, 0.19f, 0.5f, 0.8f)) circle(from + w * x, panelBottom + 5.5f, 1.25f)
                        rect(from, to, counterTop, hoodTop)
                        line(from, counterTop + 1.75f, to, counterTop + 1.75f) // hood lip
                        line(from + 1.5f * inch, counterTop + 4f, to - 1.5f * inch, counterTop + 4f, medium) // handle bar
                        circle(xc, counterTop + 9f, 1.5f) // thermometer
                    }
                    name == "Dishwasher" -> {
                        rect(from, to, 0f, counterTop - 1.5f)
                        line(from, counterTop - 5f, to, counterTop - 5f)
                        counterRuns += Pair(from, to)
                    }
                    name == "Kitchen Sink" -> {
                        // The sink sits in the counter: show the faucet.
                        line(xc, counterTop, xc, counterTop + 10f, medium)
                        line(xc, counterTop + 10f, xc + 5f * inch, counterTop + 8f, medium)
                        counterRuns += Pair(from, to)
                    }
                    PlanSymbols.isVanity(name) -> {
                        // Cabinet to 1 1/2" under its own top, toe kick, chosen fronts, and a
                        // faucet over each sink unless a separate sink symbol stands on it.
                        val top = PlanSymbols.vanityTop(item.symbol)
                        rect(from, to, 0f, top - 1.5f)
                        line(from, 4f, to, 4f)
                        fronts(item.symbol, from, to, 4f, top - 1.5f)
                        vanityRuns += Triple(from, to, top)
                        val sinks = PlanSymbols.sinkCount(item.symbol)
                        if (sinks > 0 && sinkItems.none { overlaps(it, from, to) }) {
                            for (i in 0 until sinks) faucet(from + w * (i + 0.5f) / sinks, top)
                        }
                    }
                    PlanSymbols.isLinenTower(name) -> {
                        // Full height: toe kick, doors below a fixed shelf at 34", doors above.
                        val top = PlanSymbols.LINEN_TOWER_HEIGHT_IN
                        rect(from, to, 0f, top)
                        line(from, 4f, to, 4f)
                        val count = PlanSymbols.doorCount(item.symbol, w / inch)
                        doors(from, to, 4f, 34f, count)
                        doors(from, to, 35f, top - 1f, count, knobsLow = true)
                    }
                    name == PlanSymbols.VESSEL_SINK -> {
                        // The bowl sits on the top; a tall faucet reaches over it.
                        val top = hostTop(from, to)
                        rect(from, to, top, top + 5.5f)
                        line(xc - w * 0.35f, top, xc + w * 0.35f, top, medium)
                        faucet(to - 1f * inch, top, 13f)
                        if (vanityUnder(from, to) == null) counterRuns += Pair(from, to)
                    }
                    name in PlanSymbols.counterSinks -> {
                        // Undermount, drop-in, single bowl, bar and farmhouse sinks: set into a
                        // vanity top or the counter, so only the faucet shows; a farmhouse sink
                        // also shows its apron below the counter.
                        val top = hostTop(from, to)
                        if (name == PlanSymbols.FARMHOUSE_SINK) {
                            rect(from, to, top - FARMHOUSE_APRON_IN, top - 1.5f)
                        }
                        faucet(xc, top, if (name == PlanSymbols.BAR_SINK || name == PlanSymbols.UNDERMOUNT_SINK || name == PlanSymbols.DROP_IN_SINK) 8f else 10f)
                        if (vanityUnder(from, to) == null) counterRuns += Pair(from, to)
                    }
                    name == PlanSymbols.PEDESTAL_SINK -> {
                        // Basin rim at 34", pedestal down to a flared foot.
                        val rim = 34f
                        rect(from, to, rim - 6f, rim)
                        val colW = min(w * 0.3f, 7f * inch)
                        rect(xc - colW / 2f, xc + colW / 2f, 2f, rim - 6f, light)
                        rect(xc - colW * 0.8f, xc + colW * 0.8f, 0f, 2f, light)
                        faucet(xc, rim)
                    }
                    name == PlanSymbols.WALL_HUNG_SINK || name == PlanSymbols.CORNER_SINK -> {
                        // Basin hung on the wall, rim at 34", the trap below it.
                        val rim = 34f
                        rect(from, to, rim - 7f, rim)
                        line(xc, rim - 7f, xc, rim - 13f, light)
                        line(xc, rim - 13f, xc + 3f * inch, rim - 13f, light)
                        faucet(xc, rim)
                    }
                    name == PlanSymbols.UTILITY_SINK -> {
                        // Laundry tub on legs, rim at 34".
                        rect(from, to, 20f, 34f)
                        line(from + inch, 0f, from + inch, 20f, medium)
                        line(to - inch, 0f, to - inch, 20f, medium)
                        faucet(xc, 34f, 10f)
                    }
                    name == "Bathroom Sink" -> {
                        rect(from, to, 0f, 32f)
                        doors(from, to, 4f, 32f)
                        rect(from - 0.5f * inch, to + 0.5f * inch, 32f, 33.5f)
                        line(xc, 33.5f, xc, 41f, medium)
                        line(xc, 41f, xc + 4f * inch, 39.5f, medium)
                    }
                    name == PlanSymbols.UPPER_CORNER -> {
                        val top = spec.upperBottomIn + spec.upperHeightIn
                        rect(from, to, spec.upperBottomIn, top)
                        cornerUnit(from, to, spec.upperBottomIn, top, cornerDoorAtToEnd(item.symbol, wall, spec.side, state), 12f, upper = true)
                    }
                    name.startsWith("Upper") -> {
                        val top = spec.upperBottomIn + spec.upperHeightIn
                        rect(from, to, spec.upperBottomIn, top)
                        doors(from, to, spec.upperBottomIn, top, PlanSymbols.doorCount(item.symbol, w / inch), knobsLow = true)
                    }
                    name.startsWith("Pantry") -> {
                        val top = spec.upperBottomIn + spec.upperHeightIn
                        rect(from, to, 0f, top)
                        line(from, 4f, to, 4f)
                        doors(from, to, 4f, top, PlanSymbols.doorCount(item.symbol, w / inch))
                    }
                    name == "Refrigerator" -> {
                        rect(from, to, 0f, 70f)
                        line(from + w * 0.4f, 0f, from + w * 0.4f, 70f)
                        line(from + w * 0.4f - 2f * inch, 30f, from + w * 0.4f - 2f * inch, 50f)
                        line(from + w * 0.4f + 2f * inch, 30f, from + w * 0.4f + 2f * inch, 50f)
                    }
                    name == "Range" -> {
                        rect(from, to, 0f, 36f)
                        rect(from, to, 36f, 42f, light) // backguard
                        rect(from + 3f * inch, to - 3f * inch, 8f, 24f, light) // oven window
                        for (i in 0 until 4) circle(from + w * (0.2f + 0.2f * i), 32f, 0.75f)
                    }
                    name == "Water Heater" -> {
                        rect(from, to, 0f, 58f)
                        line(from, 52f, to, 52f)
                    }
                    name == "Washer" || name == "Dryer" -> {
                        rect(from, to, 0f, 38f)
                        line(from, 34f, to, 34f)
                        circle(xc, 18f, min(w / inch * 0.3f, 10f))
                    }
                    name == "Toilet" -> {
                        val tankW = min(w, 20f * inch)
                        rect(xc - tankW / 2f, xc + tankW / 2f, 15f, 29f)
                        val bowlW = min(w, 14f * inch)
                        rect(xc - bowlW / 2f, xc + bowlW / 2f, 0f, 15f)
                    }
                    name == "Bathtub" -> {
                        rect(from, to, 0f, 18f)
                        line(from + 6f * inch, 18f, from + 6f * inch, 30f, medium)
                        line(from + 6f * inch, 30f, from + 10f * inch, 28f, medium)
                    }
                    name == "Shower Stall" -> {
                        rect(from, to, 0f, 78f)
                        line(xc, 0f, xc, 78f)
                        line(from + 4f * inch, 6f, from + 4f * inch, 78f, light)
                    }
                    // ---- Closet parts ----
                    // Every closet unit is 84" tall with a pilaster at each end (closetUnit);
                    // rods sit at the standard heights: single hang 66", double hang 40" and 80".
                    name.startsWith("Shelf & Rod") -> {
                        closetUnit(from, to)
                        shelfAndRod(from, to, SINGLE_ROD_IN)
                    }
                    name.startsWith("Double Hang") -> {
                        closetUnit(from, to)
                        shelfAndRod(from, to, DOUBLE_ROD_UPPER_IN)
                        shelfAndRod(from, to, DOUBLE_ROD_LOWER_IN)
                    }
                    name.startsWith("Shelving") -> {
                        // 3/4" shelves every 12" between the pilasters.
                        closetUnit(from, to)
                        var hIn = 12f
                        while (hIn < CLOSET_UNIT_HEIGHT_IN - 1f) { rect(from + 0.75f * inch, to - 0.75f * inch, hIn, hIn + 0.75f, light); hIn += 12f }
                    }
                    name.startsWith("Drawers") -> {
                        // A bank of four 8" drawers on a 4" base, pulls centred, shelves above.
                        closetUnit(from, to)
                        val p = 0.75f * inch
                        rect(from + p, to - p, 36f, 36.75f, light)
                        for (i in 0 until 4) {
                            val bottom = 4f + 8f * i
                            rect(from + p + inch, to - p - inch, bottom + 0.5f, bottom + 7.5f, light)
                            line(xc - 2f * inch, bottom + 4f, xc + 2f * inch, bottom + 4f, medium)
                        }
                        for (hIn in listOf(48f, 60f, 72f)) rect(from + p, to - p, hIn, hIn + 0.75f, light)
                    }
                    name.startsWith("Shoe") -> {
                        // Shoe tiers low down, open above.
                        closetUnit(from, to)
                        for (hIn in listOf(6f, 14f, 22f, 30f)) rect(from + 0.75f * inch, to - 0.75f * inch, hIn, hIn + 0.75f, light)
                    }
                    // ---- Furniture ----
                    name.startsWith("Sofa") || name.startsWith("Loveseat") || name == "Armchair" || name == "Recliner" -> {
                        // Back to 34", arms to 24", seat at 18".
                        val armW = 6f * inch
                        rect(from, to, 2f, 34f)
                        rect(from, from + armW, 2f, 24f, light)
                        rect(to - armW, to, 2f, 24f, light)
                        line(from + armW, 18f, to - armW, 18f)
                        val seats = if (name.startsWith("Sofa")) 3 else if (name.startsWith("Loveseat")) 2 else 1
                        val seatW = (w - 2f * armW) / seats
                        for (i in 1 until seats) line(from + armW + seatW * i, 18f, from + armW + seatW * i, 30f)
                    }
                    name.startsWith("Coffee Table") || name.startsWith("End Table") -> tableWithLegs(from, to, if (name.startsWith("Coffee")) 18f else 24f)
                    name.startsWith("Kitchen Table") || name.startsWith("Round Table") || name.startsWith("Desk") -> tableWithLegs(from, to, 30f)
                    name == "Dining Chair" -> {
                        rect(from, to, 18f, 19f)
                        rect(from + inch, to - inch, 19f, 36f, light)
                        line(from + inch, 0f, from + inch, 18f); line(to - inch, 0f, to - inch, 18f)
                    }
                    name == "Office Chair" -> {
                        rect(from, to, 18f, 20f)
                        rect(from + 2f * inch, to - 2f * inch, 22f, 40f, light)
                        line(xc, 0f, xc, 18f, medium)
                        line(from + 2f * inch, 0f, to - 2f * inch, 0f, medium)
                    }
                    name.endsWith("Bed") -> {
                        // Headboard against the wall, mattress in front of it.
                        rect(from, to, 0f, 48f)
                        rect(from - inch, to + inch, 14f, 24f, light)
                    }
                    name == "Nightstand" -> {
                        rect(from, to, 0f, 24f)
                        rect(from + inch, to - inch, 14f, 22f, light)
                    }
                    name.startsWith("Dresser") -> {
                        rect(from, to, 0f, 32f)
                        for (i in 0 until 3) {
                            val bottom = 4f + 9f * i
                            rect(from + inch, to - inch, bottom, bottom + 8f, light)
                        }
                    }
                    name.startsWith("Wall Unit") -> {
                        // Full height with three bays, a TV bay in the middle, shelves in the sides.
                        rect(from, to, 0f, 84f)
                        val bay = w / 3f
                        line(from + bay, 0f, from + bay, 84f)
                        line(to - bay, 0f, to - bay, 84f)
                        for (hIn in listOf(24f, 48f, 72f)) {
                            rect(from, from + bay, hIn, hIn + 0.75f, light)
                            rect(to - bay, to, hIn, hIn + 0.75f, light)
                        }
                        rect(from + bay, to - bay, 24f, 24.75f, light)
                    }
                    name.startsWith("Bookcase") -> {
                        rect(from, to, 0f, 72f)
                        var hIn = 12f
                        while (hIn < 72f) { rect(from, to, hIn, hIn + 0.75f, light); hIn += 12f }
                    }
                    name.startsWith("TV Stand") -> {
                        rect(from, to, 0f, 24f)
                        line(from, 12f, to, 12f)
                    }
                    name.startsWith("TV") -> {
                        // A 16:9 screen, its bottom 30" up (on a stand or a wall bracket).
                        val screenH = w / inch * 9f / 16f
                        rect(from, to, 30f, 30f + screenH)
                        rect(from + inch, to - inch, 31f, 29f + screenH, light)
                    }
                    else -> rect(from, to, 0f, 30f)
                }
            }
        }

        // One countertop over each run of base cabinets, appliances and sinks.
        counterRuns.sortBy { it.first }
        val merged = mutableListOf<Pair<Float, Float>>()
        for (run in counterRuns) {
            val last = merged.lastOrNull()
            if (last != null && run.first <= last.second + inch) merged[merged.size - 1] = Pair(last.first, maxOf(last.second, run.second))
            else merged += run
        }
        for ((a, b) in merged) rect(a - 0.5f * inch, b + 0.5f * inch, counterTop - 1.5f, counterTop, medium)

        // One vanity top over each run of vanities that stand side by side at the same height.
        vanityRuns.sortBy { it.first }
        val vMerged = mutableListOf<Triple<Float, Float, Float>>()
        for (run in vanityRuns) {
            val last = vMerged.lastOrNull()
            if (last != null && run.third == last.third && run.first <= last.second + inch) vMerged[vMerged.size - 1] = Triple(last.first, maxOf(last.second, run.second), last.third)
            else vMerged += run
        }
        for ((a, b, top) in vMerged) rect(a - 0.5f * inch, b + 0.5f * inch, top - 1.5f, top, medium)

        // Title under the floor line.
        val fontSize = (inch * 6f).coerceIn(10f, 36f)
        val lengthIn = length / inch
        out += DrawingElement.Text(
            text = "Elevation, ${formatFeetInches(lengthIn)} wide x ${formatFeetInches(spec.wallHeightIn)} high, ${spec.side.looking}",
            topLeft = px(0f, -8f),
            fontSize = fontSize,
            color = color
        )
        return out
    }

    private fun formatFeetInches(inches: Float): String =
        com.example.sitedraft.ui.canvas.formatFeetInches(inches / 12f)
}
