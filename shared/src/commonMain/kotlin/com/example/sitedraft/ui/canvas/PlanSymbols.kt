package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.max
import kotlin.math.min

/**
 * Plan-view symbols drawn at real-world size: fixtures, doors, windows and wall sections.
 *
 * Unlike the schematic icons (outlets, valves, ...) which are a fixed 100x100 world units, these
 * are sized in inches through [CanvasState.inchesToPx], so a 60" tub is 5 ft long on the drawing
 * and a 2x4 wall is 4 1/2" thick at whatever scale the document uses. Width is the symbol's
 * extent across the page at rotation 0, depth the extent down the page; for walls the pair is
 * (length, thickness).
 */
object PlanSymbols {
    const val FIXTURES = "Fixtures"
    const val DOORS_WINDOWS = "Doors & Windows"
    const val WALLS = "Walls"
    const val CABINETS = "Cabinets"
    const val OUTDOOR = "Outdoor Kitchen"
    const val CLOSET = "Closet"
    const val FURNITURE = "Furniture"
    val categories = setOf(FIXTURES, DOORS_WINDOWS, WALLS, CABINETS, OUTDOOR, CLOSET, FURNITURE)

    /**
     * Closet parts, set against a closet wall with their backs at the top (like cabinets).
     * Lengths along the wall are the defaults: stretch them in Properties. Shelf-and-rod and
     * double-hang are 12" deep, shelving and drawer units 16", shoe shelves 12".
     */
    val closet = listOf(
        "Shelf & Rod 36\"", "Shelf & Rod 48\"", "Double Hang 36\"", "Double Hang 48\"",
        "Shelving 24\"", "Shelving 36\"", "Drawers 24\"", "Drawers 30\"", "Shoe Shelves 24\""
    )

    /** Furniture, real size, backs at the top so the piece can be set against a wall. */
    val furniture = listOf(
        "Sofa 84\"", "Loveseat 60\"", "Armchair", "Recliner",
        "Coffee Table 48x24", "End Table 24\"", "Kitchen Table 36x60", "Round Table 48\"", "Dining Chair",
        "Queen Bed", "King Bed", "Twin Bed", "Nightstand", "Dresser 60\"",
        "Desk 60x30", "Office Chair", "Wall Unit 72\"", "Bookcase 36\"", "TV Stand 60\"", "TV 65\""
    )

    /** Outdoor kitchen equipment, by manufacturer spec where known. */
    const val LYNX_36_GRILL = "Lynx 36\" Grill"
    val outdoor = listOf(LYNX_36_GRILL)

    /** Base cabinets 24" deep, uppers 12" deep (drawn dashed: they are above the cut plane). */
    val cabinets = listOf(
        "Base 12\"", "Base 18\"", "Base 24\"", "Base 30\"", "Base 36\"", "Corner Base 36\"",
        "Upper 12\"", "Upper 18\"", "Upper 24\"", "Upper 30\"", "Upper 36\"", UPPER_CORNER,
        "Pantry 24\"", "Island 3'x6'"
    )

    /** Diagonal upper corner cabinet: 24" along each wall, 12"-deep arms, door across the corner. */
    const val UPPER_CORNER = "Upper Corner 24\""

    val fixtures = listOf(
        "Bathtub", "Shower Stall", "Toilet", "Bathroom Sink", "Kitchen Sink", "Water Heater",
        "Washer", "Dryer", "Refrigerator", "Range", "Dishwasher"
    )
    val doorsWindows = listOf(
        "Door 2'-6\"", "Door 2'-8\"", "Door 3'-0\"", "Sliding Door 6'-0\"",
        "Opening 2'-6\"", "Opening 3'-0\"", "Opening 4'-0\"", "Opening 6'-0\"",
        "Window 3'-0\"", "Window 4'-0\"", "Window 6'-0\""
    )
    val walls = listOf(
        "2x4 Wall", "2x6 Wall", "2x4 Framing", "2x6 Framing", "8\" Block Wall", "8\" Concrete Wall"
    )

    const val DEFAULT_WALL_LENGTH_IN = 48f
    const val WINDOW_DEPTH_IN = 6f

    /** Footprint (width, depth) in inches at rotation 0. */
    val footprintInches: Map<String, Pair<Float, Float>> = mapOf(
        "Bathtub" to Pair(60f, 30f),
        "Shower Stall" to Pair(36f, 36f),
        "Toilet" to Pair(20f, 28f),
        "Bathroom Sink" to Pair(24f, 21f),
        "Kitchen Sink" to Pair(33f, 22f),
        "Water Heater" to Pair(24f, 24f),
        "Washer" to Pair(27f, 27f),
        "Dryer" to Pair(27f, 27f),
        "Refrigerator" to Pair(36f, 30f),
        "Range" to Pair(30f, 26f),
        "Dishwasher" to Pair(24f, 24f),
        // A hinged door's footprint is its swing: a square the size of the leaf.
        "Door 2'-6\"" to Pair(30f, 30f),
        "Door 2'-8\"" to Pair(32f, 32f),
        "Door 3'-0\"" to Pair(36f, 36f),
        "Sliding Door 6'-0\"" to Pair(72f, WINDOW_DEPTH_IN),
        // A cased opening: a doorway with no door. Sits in the wall like a window does.
        "Opening 2'-6\"" to Pair(30f, WINDOW_DEPTH_IN),
        "Opening 3'-0\"" to Pair(36f, WINDOW_DEPTH_IN),
        "Opening 4'-0\"" to Pair(48f, WINDOW_DEPTH_IN),
        "Opening 6'-0\"" to Pair(72f, WINDOW_DEPTH_IN),
        "Window 3'-0\"" to Pair(36f, WINDOW_DEPTH_IN),
        "Window 4'-0\"" to Pair(48f, WINDOW_DEPTH_IN),
        "Window 6'-0\"" to Pair(72f, WINDOW_DEPTH_IN),
        // Walls: studs plus 1/2" drywall each face; "Framing" is the bare studs.
        "2x4 Wall" to Pair(DEFAULT_WALL_LENGTH_IN, 4.5f),
        "2x6 Wall" to Pair(DEFAULT_WALL_LENGTH_IN, 6.5f),
        "2x4 Framing" to Pair(DEFAULT_WALL_LENGTH_IN, 3.5f),
        "2x6 Framing" to Pair(DEFAULT_WALL_LENGTH_IN, 5.5f),
        "8\" Block Wall" to Pair(DEFAULT_WALL_LENGTH_IN, 7.625f),
        "8\" Concrete Wall" to Pair(DEFAULT_WALL_LENGTH_IN, 8f),
        "Base 12\"" to Pair(12f, 24f),
        "Base 18\"" to Pair(18f, 24f),
        "Base 24\"" to Pair(24f, 24f),
        "Base 30\"" to Pair(30f, 24f),
        "Base 36\"" to Pair(36f, 24f),
        "Corner Base 36\"" to Pair(36f, 36f),
        "Upper 12\"" to Pair(12f, 12f),
        "Upper 18\"" to Pair(18f, 12f),
        "Upper 24\"" to Pair(24f, 12f),
        "Upper 30\"" to Pair(30f, 12f),
        "Upper 36\"" to Pair(36f, 12f),
        UPPER_CORNER to Pair(24f, 24f),
        "Pantry 24\"" to Pair(24f, 24f),
        "Island 3'x6'" to Pair(72f, 36f),
        // Closet parts.
        "Shelf & Rod 36\"" to Pair(36f, 12f),
        "Shelf & Rod 48\"" to Pair(48f, 12f),
        "Double Hang 36\"" to Pair(36f, 12f),
        "Double Hang 48\"" to Pair(48f, 12f),
        "Shelving 24\"" to Pair(24f, 16f),
        "Shelving 36\"" to Pair(36f, 16f),
        "Drawers 24\"" to Pair(24f, 16f),
        "Drawers 30\"" to Pair(30f, 16f),
        "Shoe Shelves 24\"" to Pair(24f, 12f),
        // Furniture (width along the wall, depth out from it).
        "Sofa 84\"" to Pair(84f, 36f),
        "Loveseat 60\"" to Pair(60f, 36f),
        "Armchair" to Pair(36f, 36f),
        "Recliner" to Pair(36f, 40f),
        "Coffee Table 48x24" to Pair(48f, 24f),
        "End Table 24\"" to Pair(24f, 24f),
        "Kitchen Table 36x60" to Pair(60f, 36f),
        "Round Table 48\"" to Pair(48f, 48f),
        "Dining Chair" to Pair(18f, 20f),
        "Queen Bed" to Pair(60f, 80f),
        "King Bed" to Pair(76f, 80f),
        "Twin Bed" to Pair(38f, 75f),
        "Nightstand" to Pair(24f, 18f),
        "Dresser 60\"" to Pair(60f, 20f),
        "Desk 60x30" to Pair(60f, 30f),
        "Office Chair" to Pair(24f, 24f),
        "Wall Unit 72\"" to Pair(72f, 20f),
        "Bookcase 36\"" to Pair(36f, 12f),
        "TV Stand 60\"" to Pair(60f, 18f),
        "TV 65\"" to Pair(57f, 3f),
        // Lynx built-in 36" (Lynx "Built-In 36 w/ dimensions" drawing, 1/20/10): 36" wide, 26 3/8"
        // deep overall with the front 1 3/4" overhanging the island face; island cutout 35" x 22",
        // 10 1/8" deep; front panel 11" below the counter; hood 14 7/8" above it closed, 23 1/2" open.
        LYNX_36_GRILL to Pair(36f, LYNX_36_DEPTH)
    )

    const val LYNX_36_DEPTH = 26.375f
    const val LYNX_36_FRONT_LIP = 1.75f
    const val LYNX_36_CUTOUT_W = 35f
    const val LYNX_36_CUTOUT_D = 22f
    const val LYNX_36_CUTOUT_H = 10.125f
    const val LYNX_36_PANEL_H = 11f
    const val LYNX_36_HOOD_ABOVE_COUNTER = 14.875f
    const val LYNX_36_HOOD_OPEN_ABOVE_COUNTER = 23.5f
    /** 2" supply hole in the cutout floor: 2" from the right side, 2 1/4" from the front. */
    const val LYNX_36_HOLE_DIA = 2f

    /** Upper (wall) cabinets: above the cut plane, drawn dashed as an overlay on whatever is below. */
    fun isUpperCabinet(symbol: DrawingElement.Symbol) = symbol.category == CABINETS && symbol.symbolName.startsWith("Upper")

    fun isDoor(name: String) = name.startsWith("Door ")
    fun isSlidingDoor(name: String) = name.startsWith("Sliding Door")
    fun isWindow(name: String) = name.startsWith("Window ")
    /** A cased opening (doorway without a door). */
    fun isOpening(name: String) = name.startsWith("Opening ")

    /**
     * Symbols whose renderer draws them vertically when taller than wide (walls, windows,
     * sliding doors). A right-angle turn swaps their width and height instead of setting a
     * rotation, so they stay axis-aligned for snapping and hit-testing.
     */
    fun turnsBySwappingSize(symbol: DrawingElement.Symbol) =
        symbol.category == WALLS || isWindow(symbol.symbolName) || isSlidingDoor(symbol.symbolName) || isOpening(symbol.symbolName)
}

/** World units for a length in inches at this document's scale, in either unit system. */
fun CanvasState.inchesToPx(inches: Float): Float =
    if (unitSystem == UnitSystem.METRIC) inches * 0.0254f * scale.ratio else inches / 12f * scale.ratio

/**
 * Draws one of the [PlanSymbols] centred on [DrawingElement.Symbol.center] at its current size.
 * Rotation is applied by the caller. [lineColor] is the outline colour that reads on the current
 * background, [accentColor] the secondary colour used for basins, swings and glass.
 */
fun DrawScope.drawPlanSymbol(
    element: DrawingElement.Symbol,
    state: CanvasState,
    lineColor: Color,
    accentColor: Color
) {
    val w = element.size.width
    val h = element.size.height
    val hw = w / 2f
    val hh = h / 2f
    val inch = state.inchesToPx(1f)
    val line = Stroke(width = element.strokeWidth, cap = StrokeCap.Round)
    val thin = Stroke(width = element.strokeWidth * 0.5f, cap = StrokeCap.Round)
    val faint = lineColor.copy(alpha = 0.35f)
    val name = element.symbolName

    translate(element.center.x, element.center.y) {
        // Fixtures, windows and walls sit on top of whatever is under them (a hatch, a wall
        // line), so blank their footprint first. A door's footprint is its swing, which stays
        // open, and an upper cabinet is an overlay above the cut plane: the base cabinet or
        // counter under it must still show through its dashed outline.
        if (!PlanSymbols.isDoor(name) && !PlanSymbols.isUpperCabinet(element)) {
            drawRect(color = state.backgroundColor, topLeft = Offset(-hw, -hh), size = Size(w, h))
        }
        when {
            element.category == PlanSymbols.WALLS -> {
                // A wall taller than it is wide runs vertically: draw it sideways so the studs
                // and hatch follow its length. (The floor-plan tool never rotates walls.)
                if (h > w) rotate(90f, pivot = Offset.Zero) { drawWallSection(name, h, w, inch, lineColor, accentColor, line, thin) }
                else drawWallSection(name, w, h, inch, lineColor, accentColor, line, thin)
            }
            element.category == PlanSymbols.CABINETS -> drawCabinet(name, w, h, inch, lineColor, accentColor, faint, line, thin)
            element.category == PlanSymbols.CLOSET -> drawClosetPart(name, w, h, inch, lineColor, accentColor, faint, line, thin)
            element.category == PlanSymbols.FURNITURE -> drawFurniture(name, w, h, inch, lineColor, accentColor, faint, line, thin)
            PlanSymbols.isDoor(name) -> {
                // Hinge at the bottom-left corner, leaf standing open along the left edge, swing
                // arc sweeping to the latch at the bottom-right corner. The threshold (bottom
                // edge) is where the wall is: blank a strip there so the door cuts an opening.
                val leaf = inch * 1.75f
                drawRect(color = state.backgroundColor, topLeft = Offset(-hw, hh - inch * 4f), size = Size(w, inch * 8f))
                drawLine(color = faint, start = Offset(-hw, hh), end = Offset(hw, hh), strokeWidth = thin.width)
                drawRect(color = lineColor, topLeft = Offset(-hw, hh - w), size = Size(leaf, w), style = line)
                drawArc(
                    color = accentColor, startAngle = -90f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(-hw - w, hh - w), size = Size(2f * w, 2f * w), style = thin
                )
            }
            PlanSymbols.isSlidingDoor(name) || PlanSymbols.isWindow(name) || PlanSymbols.isOpening(name) -> {
                // Taller than wide = sitting in a vertical wall: draw it turned, like walls.
                if (h > w) rotate(90f, pivot = Offset.Zero) { drawOpening(name, h, w, inch, state.backgroundColor, lineColor, accentColor, faint, line, thin) }
                else drawOpening(name, w, h, inch, state.backgroundColor, lineColor, accentColor, faint, line, thin)
            }
            name == "Bathtub" -> {
                val r = min(w, h) * 0.15f
                val ins = inch * 2.5f
                drawRoundRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), cornerRadius = CornerRadius(r, r), style = line)
                drawRoundRect(
                    color = accentColor, topLeft = Offset(-hw + ins, -hh + ins), size = Size(w - 2 * ins, h - 2 * ins),
                    cornerRadius = CornerRadius(r * 1.6f, r * 1.6f), style = thin
                )
                // Drain and spout at the left end; rotate the symbol to put them at the other end.
                drawCircle(color = accentColor, radius = inch * 1.5f, center = Offset(-hw + inch * 8f, 0f), style = thin)
                drawRect(color = lineColor, topLeft = Offset(-hw + inch * 0.5f, -inch * 2f), size = Size(inch * 2f, inch * 4f), style = thin)
            }
            name == "Shower Stall" -> {
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                val d = inch * 2f
                // Pan slopes drawn as faint lines from the corners to the drain.
                for (corner in listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh))) {
                    drawLine(color = faint, start = corner, end = Offset.Zero, strokeWidth = thin.width)
                }
                drawCircle(color = state.backgroundColor, radius = d * 1.3f, center = Offset.Zero)
                drawCircle(color = accentColor, radius = d, center = Offset.Zero, style = thin)
                drawLine(color = accentColor, start = Offset(-d * 0.7f, -d * 0.7f), end = Offset(d * 0.7f, d * 0.7f), strokeWidth = thin.width)
                drawLine(color = accentColor, start = Offset(-d * 0.7f, d * 0.7f), end = Offset(d * 0.7f, -d * 0.7f), strokeWidth = thin.width)
                // Glass door along the front edge.
                drawLine(color = accentColor, start = Offset(-hw + inch * 2f, hh - inch), end = Offset(hw - inch * 2f, hh - inch), strokeWidth = line.width)
            }
            name == "Toilet" -> {
                val tankD = min(h * 0.3f, inch * 8f)
                val tankW = min(w, inch * 20f)
                drawRect(color = lineColor, topLeft = Offset(-tankW / 2f, -hh), size = Size(tankW, tankD), style = line)
                val bowlW = min(w, inch * 14f)
                val bowlTop = -hh + tankD
                val bowlH = hh - bowlTop
                drawOval(color = lineColor, topLeft = Offset(-bowlW / 2f, bowlTop), size = Size(bowlW, bowlH), style = line)
                val ins = inch * 2f
                drawOval(color = accentColor, topLeft = Offset(-bowlW / 2f + ins, bowlTop + ins), size = Size(bowlW - 2 * ins, bowlH - 2 * ins), style = thin)
            }
            name == "Bathroom Sink" -> {
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                val bw = min(w * 0.65f, inch * 17f)
                val bh = min(h * 0.6f, inch * 13f)
                drawOval(color = accentColor, topLeft = Offset(-bw / 2f, -bh / 2f + inch), size = Size(bw, bh), style = thin)
                drawCircle(color = lineColor, radius = inch, center = Offset(0f, -bh / 2f - inch * 0.5f), style = thin)
            }
            name == "Kitchen Sink" -> {
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                val gap = inch * 1.5f
                val bw = hw - gap * 1.5f
                val bh = h - inch * 6f
                val r = CornerRadius(inch, inch)
                drawRoundRect(color = accentColor, topLeft = Offset(-hw + gap, -bh / 2f + inch), size = Size(bw, bh), cornerRadius = r, style = thin)
                drawRoundRect(color = accentColor, topLeft = Offset(gap / 2f, -bh / 2f + inch), size = Size(bw, bh), cornerRadius = r, style = thin)
                drawCircle(color = lineColor, radius = inch, center = Offset(0f, -hh + inch * 2f), style = thin)
            }
            name == "Water Heater" -> {
                val r = min(hw, hh)
                drawCircle(color = lineColor, radius = r, center = Offset.Zero, style = line)
                drawCircle(color = accentColor, radius = r * 0.8f, center = Offset.Zero, style = thin)
                label("WH", lineColor, r * 0.55f)
            }
            name == "Washer" || name == "Dryer" -> {
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                drawLine(color = faint, start = Offset(-hw, -hh + inch * 3f), end = Offset(hw, -hh + inch * 3f), strokeWidth = thin.width)
                drawCircle(color = accentColor, radius = min(hw, hh) * 0.55f, center = Offset(0f, inch * 1.5f), style = thin)
                label(if (name == "Washer") "W" else "D", lineColor, min(hw, hh) * 0.45f, Offset(0f, inch * 1.5f))
            }
            name == "Refrigerator" -> {
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                // Doors open from the front (bottom) edge.
                drawLine(color = faint, start = Offset(-hw, hh - inch * 2f), end = Offset(hw, hh - inch * 2f), strokeWidth = thin.width)
                drawLine(color = faint, start = Offset(0f, hh - inch * 2f), end = Offset(0f, hh), strokeWidth = thin.width)
                label("REF", lineColor, min(hw, hh) * 0.4f)
            }
            name == "Range" -> {
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                drawLine(color = faint, start = Offset(-hw, -hh + inch * 3f), end = Offset(hw, -hh + inch * 3f), strokeWidth = thin.width)
                val br = min(inch * 3.5f, min(w, h) * 0.15f)
                val cy = inch * 1.5f
                for (x in listOf(-w * 0.25f, w * 0.25f)) for (y in listOf(-h * 0.2f + cy, h * 0.22f + cy)) {
                    drawCircle(color = accentColor, radius = br, center = Offset(x, y), style = thin)
                }
            }
            name == PlanSymbols.LYNX_36_GRILL -> {
                // Body with the hood hinge line along the back, cooking grates, the control panel
                // with four knobs along the front, and the island cutout dashed inside it: 35" x
                // 22" whose front edge sits 1 3/4" behind the grill's front lip, with the 2" supply
                // hole 2" from the right side and 2 1/4" from the front of the cutout.
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                drawLine(color = accentColor, start = Offset(-hw, -hh + inch * 3f), end = Offset(hw, -hh + inch * 3f), strokeWidth = line.width)
                val grateTop = -hh + inch * 4f
                val grateBottom = hh - inch * 5f
                var gx = -hw + inch * 2f
                while (gx < hw - inch) {
                    drawLine(color = faint, start = Offset(gx, grateTop), end = Offset(gx, grateBottom), strokeWidth = thin.width)
                    gx += inch * 1.5f
                }
                drawLine(color = lineColor, start = Offset(-hw, grateBottom), end = Offset(hw, grateBottom), strokeWidth = thin.width)
                for (i in 0 until 4) {
                    drawCircle(color = accentColor, radius = inch * 0.9f, center = Offset(-hw + w * (0.2f + 0.2f * i), hh - inch * 2.5f), style = thin)
                }
                val cutW = inch * PlanSymbols.LYNX_36_CUTOUT_W
                val cutD = inch * PlanSymbols.LYNX_36_CUTOUT_D
                val cutFront = hh - inch * PlanSymbols.LYNX_36_FRONT_LIP
                val dashed = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 2f, inch)))
                drawRect(color = accentColor, topLeft = Offset(-cutW / 2f, cutFront - cutD), size = Size(cutW, cutD), style = dashed)
                drawCircle(
                    color = accentColor, radius = inch * PlanSymbols.LYNX_36_HOLE_DIA / 2f,
                    center = Offset(cutW / 2f - inch * 2f, cutFront - inch * 2.25f), style = thin
                )
            }
            name == "Dishwasher" -> {
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                drawLine(color = faint, start = Offset(-hw, hh - inch * 2f), end = Offset(hw, hh - inch * 2f), strokeWidth = thin.width)
                label("DW", lineColor, min(hw, hh) * 0.45f)
            }
            else -> drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
        }
    }
}

/** A window or sliding door of width [w] in a wall [h] thick, centred on the origin, running left to right. */
private fun DrawScope.drawOpening(
    name: String, w: Float, h: Float, inch: Float, background: Color,
    lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke
) {
    val hw = w / 2f
    val hh = h / 2f
    // Blank the wall under the opening even when the wall is thicker than the symbol.
    val blank = max(h, inch * 8f)
    drawRect(color = background, topLeft = Offset(-hw, -blank / 2f), size = Size(w, blank))
    if (PlanSymbols.isOpening(name)) {
        // Cased opening: the wall stops at a jamb each side, a faint dashed header line between.
        drawLine(color = lineColor, start = Offset(-hw, -blank / 2f), end = Offset(-hw, blank / 2f), strokeWidth = line.width)
        drawLine(color = lineColor, start = Offset(hw, -blank / 2f), end = Offset(hw, blank / 2f), strokeWidth = line.width)
        val dashed = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 2f, inch * 1.5f)))
        drawPath(Path().apply { moveTo(-hw, 0f); lineTo(hw, 0f) }, color = faint, style = dashed)
    } else if (PlanSymbols.isSlidingDoor(name)) {
        val panel = inch * 1.5f
        val overlap = inch * 2f
        drawRect(color = faint, topLeft = Offset(-hw, -hh), size = Size(w, h), style = thin)
        drawRect(color = lineColor, topLeft = Offset(-hw, -panel), size = Size(hw + overlap, panel), style = line)
        drawRect(color = lineColor, topLeft = Offset(-overlap, 0f), size = Size(hw + overlap, panel), style = line)
    } else {
        drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
        drawLine(color = accentColor, start = Offset(-hw, 0f), end = Offset(hw, 0f), strokeWidth = line.width)
    }
}

/** A wall section of length [w] and thickness [h], centred on the origin, running left to right. */
private fun DrawScope.drawWallSection(
    name: String, w: Float, h: Float, inch: Float,
    lineColor: Color, accentColor: Color, line: Stroke, thin: Stroke
) {
    val hw = w / 2f
    val hh = h / 2f
    drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
    when {
        name.contains("Wall") && (name.startsWith("2x")) || name.contains("Framing") -> {
            val drywall = if (name.contains("Framing")) 0f else inch * 0.5f
            if (drywall > 0f) {
                drawLine(color = accentColor, start = Offset(-hw, -hh + drywall), end = Offset(hw, -hh + drywall), strokeWidth = thin.width)
                drawLine(color = accentColor, start = Offset(-hw, hh - drywall), end = Offset(hw, hh - drywall), strokeWidth = thin.width)
            }
            // Studs at 16" on centre from the left end, plus one closing the right end.
            val studW = inch * 1.5f
            val studTop = -hh + drywall
            val studH = h - 2f * drywall
            if (studW > 0f && studH > 0f) {
                val xs = generateSequence(-hw) { it + inch * 16f }.takeWhile { it + studW <= hw - studW * 0.5f }.toMutableList()
                xs += hw - studW
                for (x in xs) {
                    drawRect(color = accentColor, topLeft = Offset(x, studTop), size = Size(studW, studH), style = thin)
                    drawLine(color = accentColor, start = Offset(x, studTop), end = Offset(x + studW, studTop + studH), strokeWidth = thin.width)
                    drawLine(color = accentColor, start = Offset(x, studTop + studH), end = Offset(x + studW, studTop), strokeWidth = thin.width)
                }
            }
        }
        name.contains("Block") -> hatch(hw, hh, spacing = inch * 2.5f, color = accentColor, strokeWidth = thin.width)
        name.contains("Concrete") -> {
            hatch(hw, hh, spacing = inch * 5f, color = accentColor, strokeWidth = thin.width)
            // Aggregate dots between the hatch lines.
            var x = -hw + inch * 2f
            var row = 0
            while (x < hw - inch) {
                val y = if (row % 2 == 0) -hh * 0.5f else hh * 0.5f
                drawCircle(color = accentColor, radius = thin.width * 0.9f, center = Offset(x, y))
                x += inch * 4f
                row++
            }
        }
    }
}

/**
 * A cabinet centred on the origin, back edge at the top, doors facing down the page. Base and
 * tall units are solid outlines with a door-face line; uppers are dashed because they sit above
 * the floor-plan cut plane.
 */
private fun DrawScope.drawCabinet(
    name: String, w: Float, h: Float, inch: Float,
    lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke
) {
    val hw = w / 2f
    val hh = h / 2f
    val face = inch * 1.5f
    when {
        name == PlanSymbols.UPPER_CORNER -> {
            // Diagonal upper corner unit, dashed like the other uppers: 12"-deep arms along the
            // back and left walls, the door across the 45-degree front-right face.
            val dashed = Stroke(
                width = line.width, cap = StrokeCap.Butt,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 3f, inch * 2f))
            )
            val d = inch * 12f
            val outline = Path().apply {
                moveTo(-hw, -hh); lineTo(hw, -hh); lineTo(hw, hh - d); lineTo(hw - d, hh); lineTo(-hw, hh); close()
            }
            drawPath(path = outline, color = accentColor, style = dashed)
            // Inside corner of the two arms, and the door line just inside the diagonal face.
            drawLine(color = accentColor.copy(alpha = 0.5f), start = Offset(hw - d, -hh + d), end = Offset(hw - d, hh), strokeWidth = thin.width)
            drawLine(color = accentColor.copy(alpha = 0.5f), start = Offset(-hw, -hh + d), end = Offset(hw - d, -hh + d), strokeWidth = thin.width)
            drawLine(color = accentColor, start = Offset(hw - face, hh - d + face * 0.4f), end = Offset(hw - d + face * 0.4f, hh - face), strokeWidth = thin.width)
        }
        name.startsWith("Upper") -> {
            val dashed = Stroke(
                width = line.width, cap = StrokeCap.Butt,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 3f, inch * 2f))
            )
            drawRect(color = accentColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = dashed)
            if (w > inch * 24f) {
                drawLine(color = accentColor.copy(alpha = 0.5f), start = Offset(0f, -hh), end = Offset(0f, hh), strokeWidth = thin.width)
            }
        }
        name.startsWith("Corner") -> {
            // Lazy-susan corner unit: walls along the back and left, a 45-degree door across the
            // front-right corner, turntable inside.
            val d = inch * 12f
            val outline = Path().apply {
                moveTo(-hw, -hh); lineTo(hw, -hh); lineTo(hw, hh - d); lineTo(hw - d, hh); lineTo(-hw, hh); close()
            }
            drawPath(path = outline, color = lineColor, style = line)
            drawLine(color = accentColor, start = Offset(hw - face, hh - d + face * 0.4f), end = Offset(hw - d + face * 0.4f, hh - face), strokeWidth = line.width)
            drawCircle(color = accentColor, radius = min(hw, hh) * 0.75f, center = Offset(-inch * 2f, -inch * 2f), style = thin)
        }
        name.startsWith("Island") -> {
            drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
            // Doors on both long sides.
            drawLine(color = accentColor, start = Offset(-hw, hh - face), end = Offset(hw, hh - face), strokeWidth = line.width)
            drawLine(color = accentColor, start = Offset(-hw, -hh + face), end = Offset(hw, -hh + face), strokeWidth = line.width)
            var x = -hw + inch * 24f
            while (x < hw - inch) {
                drawLine(color = accentColor, start = Offset(x, -hh + face), end = Offset(x, hh - face), strokeWidth = thin.width)
                x += inch * 24f
            }
        }
        else -> {
            // Base and pantry: outline, door face along the front, split doors when wide enough.
            drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
            drawLine(color = accentColor, start = Offset(-hw, hh - face), end = Offset(hw, hh - face), strokeWidth = line.width)
            if (w >= inch * 30f) {
                drawLine(color = accentColor, start = Offset(0f, hh - face), end = Offset(0f, hh), strokeWidth = line.width)
            }
            if (name.startsWith("Pantry")) {
                drawLine(color = faint, start = Offset(-hw, -hh), end = Offset(hw, hh - face), strokeWidth = thin.width)
                drawLine(color = faint, start = Offset(-hw, hh - face), end = Offset(hw, -hh), strokeWidth = thin.width)
            }
        }
    }
}

/**
 * Closet parts centred on the origin, back (wall side) at the top. A shelf-and-rod is the shelf
 * outline with the rod as a dashed line near the front; double hang is the same marked DBL;
 * shelving shows its shelf edge and a label; drawer units show their drawer front; shoe shelves
 * the slanted shelf edges.
 */
private fun DrawScope.drawClosetPart(
    name: String, w: Float, h: Float, inch: Float,
    lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke
) {
    val hw = w / 2f
    val hh = h / 2f
    val dashed = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 2f, inch * 1.5f)))
    val textSize = (inch * 3.5f).coerceAtLeast(6f)
    drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
    when {
        name.startsWith("Shelf & Rod") || name.startsWith("Double Hang") -> {
            // Rod 10 1/2" out from the wall, drawn as a dashed line; rod ends as short ticks.
            val rodY = -hh + inch * 10.5f
            drawPath(Path().apply { moveTo(-hw + inch, rodY); lineTo(hw - inch, rodY) }, color = accentColor, style = dashed)
            drawLine(color = accentColor, start = Offset(-hw + inch, rodY - inch), end = Offset(-hw + inch, rodY + inch), strokeWidth = thin.width)
            drawLine(color = accentColor, start = Offset(hw - inch, rodY - inch), end = Offset(hw - inch, rodY + inch), strokeWidth = thin.width)
            if (name.startsWith("Double")) label("DBL", accentColor, textSize, Offset(0f, -hh + inch * 5f))
        }
        name.startsWith("Shelving") -> {
            drawLine(color = accentColor, start = Offset(-hw, hh - inch), end = Offset(hw, hh - inch), strokeWidth = thin.width)
            label("SHELVES", accentColor, textSize)
        }
        name.startsWith("Drawers") -> {
            drawLine(color = accentColor, start = Offset(-hw, hh - inch * 1.5f), end = Offset(hw, hh - inch * 1.5f), strokeWidth = line.width)
            // Pull.
            drawLine(color = accentColor, start = Offset(-inch * 2f, hh - inch * 0.75f), end = Offset(inch * 2f, hh - inch * 0.75f), strokeWidth = thin.width)
            label("DRAWERS", accentColor, textSize)
        }
        name.startsWith("Shoe") -> {
            drawPath(Path().apply { moveTo(-hw, hh - inch * 2f); lineTo(hw, hh - inch * 2f) }, color = faint, style = dashed)
            label("SHOES", accentColor, textSize)
        }
    }
}

/** Furniture centred on the origin, back at the top. Simple, recognisable plan outlines. */
private fun DrawScope.drawFurniture(
    name: String, w: Float, h: Float, inch: Float,
    lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke
) {
    val hw = w / 2f
    val hh = h / 2f
    val textSize = (inch * 3.5f).coerceAtLeast(6f)
    val r = CornerRadius(inch * 2f, inch * 2f)
    fun seating(seats: Int, arms: Boolean) {
        val back = inch * 8f
        val arm = if (arms) inch * 6f else 0f
        drawRoundRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), cornerRadius = r, style = line)
        // Back cushion strip along the wall side, arms down each end.
        drawLine(color = accentColor, start = Offset(-hw + arm, -hh + back), end = Offset(hw - arm, -hh + back), strokeWidth = thin.width)
        if (arms) {
            drawLine(color = accentColor, start = Offset(-hw + arm, -hh + back), end = Offset(-hw + arm, hh), strokeWidth = thin.width)
            drawLine(color = accentColor, start = Offset(hw - arm, -hh + back), end = Offset(hw - arm, hh), strokeWidth = thin.width)
        }
        val seatW = (w - 2f * arm) / seats
        for (i in 1 until seats) {
            val x = -hw + arm + seatW * i
            drawLine(color = accentColor, start = Offset(x, -hh + back), end = Offset(x, hh), strokeWidth = thin.width)
        }
    }
    fun bed() {
        drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
        // Headboard line, pillows, and the blanket turn-down a third of the way down.
        drawLine(color = accentColor, start = Offset(-hw, -hh + inch * 3f), end = Offset(hw, -hh + inch * 3f), strokeWidth = thin.width)
        val pillowW = if (w > inch * 45f) (w - inch * 9f) / 2f else w - inch * 6f
        val pr = CornerRadius(inch * 3f, inch * 3f)
        if (w > inch * 45f) {
            drawRoundRect(color = accentColor, topLeft = Offset(-hw + inch * 3f, -hh + inch * 5f), size = Size(pillowW, inch * 18f), cornerRadius = pr, style = thin)
            drawRoundRect(color = accentColor, topLeft = Offset(inch * 1.5f, -hh + inch * 5f), size = Size(pillowW, inch * 18f), cornerRadius = pr, style = thin)
        } else {
            drawRoundRect(color = accentColor, topLeft = Offset(-hw + inch * 3f, -hh + inch * 5f), size = Size(pillowW, inch * 18f), cornerRadius = pr, style = thin)
        }
        val fold = -hh + h / 3f
        drawLine(color = accentColor, start = Offset(-hw, fold), end = Offset(hw, fold), strokeWidth = thin.width)
        drawLine(color = faint, start = Offset(-hw, fold + inch * 2f), end = Offset(hw, fold + inch * 2f), strokeWidth = thin.width)
    }
    fun box(text: String? = null, frontLine: Boolean = false) {
        drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
        if (frontLine) drawLine(color = accentColor, start = Offset(-hw, hh - inch * 1.5f), end = Offset(hw, hh - inch * 1.5f), strokeWidth = thin.width)
        if (text != null) label(text, accentColor, textSize)
    }
    when {
        name.startsWith("Sofa") -> seating(3, true)
        name.startsWith("Loveseat") -> seating(2, true)
        name == "Armchair" -> seating(1, true)
        name == "Recliner" -> {
            seating(1, true)
            // Footrest line across the front.
            drawLine(color = faint, start = Offset(-hw + inch * 6f, hh - inch * 10f), end = Offset(hw - inch * 6f, hh - inch * 10f), strokeWidth = thin.width)
        }
        name.startsWith("Round Table") -> {
            drawCircle(color = lineColor, radius = min(hw, hh), center = Offset.Zero, style = line)
            drawCircle(color = faint, radius = min(hw, hh) * 0.3f, center = Offset.Zero, style = thin)
        }
        name.startsWith("Coffee Table") || name.startsWith("End Table") || name.startsWith("Kitchen Table") -> {
            drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
            drawRect(color = faint, topLeft = Offset(-hw + inch * 2f, -hh + inch * 2f), size = Size(w - inch * 4f, h - inch * 4f), style = thin)
        }
        name == "Dining Chair" -> {
            drawRoundRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), cornerRadius = r, style = line)
            drawLine(color = accentColor, start = Offset(-hw, -hh + inch * 3f), end = Offset(hw, -hh + inch * 3f), strokeWidth = line.width)
        }
        name == "Office Chair" -> {
            drawCircle(color = lineColor, radius = min(hw, hh) * 0.8f, center = Offset(0f, inch * 2f), style = line)
            drawArc(color = accentColor, startAngle = 200f, sweepAngle = 140f, useCenter = false, topLeft = Offset(-hw, -hh), size = Size(w, h * 0.8f), style = line)
        }
        name.endsWith("Bed") -> bed()
        name == "Nightstand" -> box("NS", frontLine = true)
        name.startsWith("Dresser") -> box("DRESSER", frontLine = true)
        name.startsWith("Desk") -> {
            box("DESK")
            drawRect(color = faint, topLeft = Offset(-inch * 9f, hh - inch * 10f), size = Size(inch * 18f, inch * 6f), style = thin)
        }
        name.startsWith("Wall Unit") -> {
            box("WALL UNIT")
            drawLine(color = accentColor, start = Offset(-hw / 3f, -hh), end = Offset(-hw / 3f, hh), strokeWidth = thin.width)
            drawLine(color = accentColor, start = Offset(hw / 3f, -hh), end = Offset(hw / 3f, hh), strokeWidth = thin.width)
        }
        name.startsWith("Bookcase") -> box("BOOKS")
        name.startsWith("TV Stand") -> box("TV STAND", frontLine = true)
        name.startsWith("TV") -> {
            drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
            label("TV", accentColor, textSize, Offset(0f, hh + inch * 3f))
        }
        else -> box(name)
    }
}

/** 45-degree hatch clipped to the rectangle (-hw,-hh)-(hw,hh). */
private fun DrawScope.hatch(hw: Float, hh: Float, spacing: Float, color: Color, strokeWidth: Float) {
    if (spacing <= 0f) return
    clipRect(-hw, -hh, hw, hh) {
        var x = -hw - 2f * hh
        while (x < hw) {
            drawLine(color = color, start = Offset(x, hh), end = Offset(x + 2f * hh, -hh), strokeWidth = strokeWidth)
            x += spacing
        }
    }
}

private fun DrawScope.label(text: String, color: Color, sizePx: Float, center: Offset = Offset.Zero) {
    drawLabel(text, center, sizePx, color)
}
