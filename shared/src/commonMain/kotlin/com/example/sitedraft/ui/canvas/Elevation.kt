package com.example.sitedraft.ui.canvas

import com.example.sitedraft.util.format
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

    enum class Kind { OPENING, FIXTURE, CUT_WALL }

    /** A plan symbol seen in the elevation, with its extent along the wall measured from the viewer's left end (px). */
    data class WallItem(val symbol: DrawingElement.Symbol, val from: Float, val to: Float, val kind: Kind)

    fun isHorizontal(wall: DrawingElement.Symbol) = wall.size.width >= wall.size.height

    fun sidesFor(wall: DrawingElement.Symbol): List<ViewerSide> =
        if (isHorizontal(wall)) listOf(ViewerSide.BELOW, ViewerSide.ABOVE) else listOf(ViewerSide.RIGHT, ViewerSide.LEFT)

    /** The side with more on it; ties go to the first. */
    fun defaultSide(wall: DrawingElement.Symbol, state: CanvasState): ViewerSide =
        sidesFor(wall).maxByOrNull { itemsOnWall(wall, it, state).size } ?: ViewerSide.BELOW

    fun lengthPx(wall: DrawingElement.Symbol): Float = if (isHorizontal(wall)) wall.size.width else wall.size.height

    fun sizePx(wall: DrawingElement.Symbol, spec: ElevationSpec, state: CanvasState): Size =
        Size(lengthPx(wall), state.inchesToPx(spec.wallHeightIn))

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
                PlanSymbols.DOORS_WINDOWS -> if (wb.inflate(2f * inch).contains(b.center)) Kind.OPENING else continue
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
    /** What an [DrawingElement.ElevationView] shows right now: rebuilt from the plan every time it is drawn. */
    fun partsOf(view: DrawingElement.ElevationView, state: CanvasState): List<DrawingElement> {
        val wall = state.elements.find { it.id == view.spec.wallId } as? DrawingElement.Symbol
        return if (wall != null) build(wall, view.spec, view.origin, state, view.color) else missingWall(view, state)
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
        fun rect(xFrom: Float, xTo: Float, hBottom: Float, hTop: Float, sw: Float = medium) {
            if (xTo <= xFrom || hTop <= hBottom) return
            out += DrawingElement.Rectangle(topLeft = px(xFrom, hTop), size = Size(xTo - xFrom, (hTop - hBottom) * inch), color = color, strokeWidth = sw)
        }
        fun circle(xc: Float, hc: Float, rIn: Float, sw: Float = light) {
            out += DrawingElement.Circle(center = px(xc, hc), radius = rIn * inch, color = color, strokeWidth = sw)
        }
        /** Door fronts across [xFrom]..[xTo] between two heights, split in two when wide enough. */
        fun doors(xFrom: Float, xTo: Float, hBottom: Float, hTop: Float) {
            val inset = inch
            rect(xFrom + inset, xTo - inset, hBottom + 1f, hTop - 1f, light)
            if (xTo - xFrom >= 30f * inch) line((xFrom + xTo) / 2f, hBottom + 1f, (xFrom + xTo) / 2f, hTop - 1f)
        }

        /** A 3/4" shelf at [shelfIn] with a closet rod 2" below it and a few hangers. */
        fun shelfAndRod(xFrom: Float, xTo: Float, shelfIn: Float) {
            rect(xFrom, xTo, shelfIn, shelfIn + 0.75f, light)
            line(xFrom, shelfIn - 2f, xTo, shelfIn - 2f, medium)
            var x = xFrom + 4f * inch
            while (x < xTo - 2f * inch) {
                line(x, shelfIn - 2f, x, shelfIn - 6f)
                rect(x - 7f * inch, x + 7f * inch, shelfIn - 38f, shelfIn - 6f, light)
                x += 9f * inch
            }
        }
        /** A table or desk top at [topIn] on legs set 2" in from the ends. */
        fun tableWithLegs(xFrom: Float, xTo: Float, topIn: Float) {
            rect(xFrom, xTo, topIn - 1.5f, topIn)
            line(xFrom + 2f * inch, 0f, xFrom + 2f * inch, topIn - 1.5f, medium)
            line(xTo - 2f * inch, 0f, xTo - 2f * inch, topIn - 1.5f, medium)
        }

        // Frame: floor, ceiling, ends.
        line(0f, 0f, length, 0f, heavy)
        line(0f, spec.wallHeightIn, length, spec.wallHeightIn, medium)
        line(0f, 0f, 0f, spec.wallHeightIn, medium)
        line(length, 0f, length, spec.wallHeightIn, medium)

        val counterTop = spec.counterHeightIn
        val counterRuns = mutableListOf<Pair<Float, Float>>()
        val items = itemsOnWall(wall, spec.side, state)
        for (item in items) {
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
                Kind.OPENING -> when {
                    PlanSymbols.isWindow(name) -> {
                        val top = spec.sillIn + spec.windowHeightIn
                        rect(from, to, spec.sillIn, top, medium)
                        line(xc, spec.sillIn, xc, top)
                        line(from, (spec.sillIn + top) / 2f, to, (spec.sillIn + top) / 2f)
                        rect(from - inch, to + inch, spec.sillIn - 1.5f, spec.sillIn, light) // sill
                    }
                    PlanSymbols.isOpening(name) -> {
                        // Cased opening: the hole with its casing, nothing hung in it.
                        rect(from, to, 0f, spec.doorHeightIn, medium)
                        rect(from - 2f * inch, to + 2f * inch, 0f, spec.doorHeightIn + 2f, light)
                    }
                    PlanSymbols.isSlidingDoor(name) -> {
                        rect(from, to, 0f, spec.doorHeightIn, medium)
                        line(xc, 0f, xc, spec.doorHeightIn)
                        line(xc - 2f * inch, 30f, xc - 2f * inch, 42f)
                        line(xc + 2f * inch, 30f, xc + 2f * inch, 42f)
                    }
                    else -> {
                        rect(from, to, 0f, spec.doorHeightIn, medium)
                        rect(from + 3f * inch, to - 3f * inch, 8f, spec.doorHeightIn - 8f, light)
                        circle(to - 3f * inch, 36f, 1f)
                    }
                }
                Kind.FIXTURE -> when {
                    name.startsWith("Base") || name.startsWith("Corner Base") -> {
                        rect(from, to, 0f, counterTop - 1.5f)
                        line(from, 4f, to, 4f) // toe kick
                        doors(from, to, 4f, counterTop - 1.5f)
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
                    name == "Bathroom Sink" -> {
                        rect(from, to, 0f, 32f)
                        doors(from, to, 4f, 32f)
                        rect(from - 0.5f * inch, to + 0.5f * inch, 32f, 33.5f)
                        line(xc, 33.5f, xc, 41f, medium)
                        line(xc, 41f, xc + 4f * inch, 39.5f, medium)
                    }
                    name.startsWith("Upper") -> {
                        val top = spec.upperBottomIn + spec.upperHeightIn
                        rect(from, to, spec.upperBottomIn, top)
                        doors(from, to, spec.upperBottomIn, top)
                    }
                    name.startsWith("Pantry") -> {
                        val top = spec.upperBottomIn + spec.upperHeightIn
                        rect(from, to, 0f, top)
                        line(from, 4f, to, 4f)
                        doors(from, to, 4f, top)
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
                    name.startsWith("Shelf & Rod") -> {
                        // Single hang: shelf at 66", rod 2" below it, a few hangers under the rod.
                        shelfAndRod(from, to, 66f)
                    }
                    name.startsWith("Double Hang") -> {
                        shelfAndRod(from, to, 84f)
                        shelfAndRod(from, to, 42f)
                    }
                    name.startsWith("Shelving") -> {
                        // Uprights to 84" with 3/4" shelves every 12".
                        line(from, 0f, from, 84f, medium)
                        line(to, 0f, to, 84f, medium)
                        var hIn = 12f
                        while (hIn <= 84f) { rect(from, to, hIn, hIn + 0.75f, light); hIn += 12f }
                    }
                    name.startsWith("Drawers") -> {
                        // A bank of four 8" drawers on a 4" base, pulls centred.
                        rect(from, to, 0f, 36f)
                        for (i in 0 until 4) {
                            val bottom = 4f + 8f * i
                            rect(from + inch, to - inch, bottom + 0.5f, bottom + 7.5f, light)
                            line(xc - 2f * inch, bottom + 4f, xc + 2f * inch, bottom + 4f, medium)
                        }
                    }
                    name.startsWith("Shoe") -> {
                        line(from, 0f, from, 24f, medium)
                        line(to, 0f, to, 24f, medium)
                        for (hIn in listOf(6f, 14f, 22f)) rect(from, to, hIn, hIn + 0.75f, light)
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
