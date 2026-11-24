package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
    /** Showers built from one symbol: see [Shower]. */
    const val SHOWERS = "Showers"
    /** Stairs built from one symbol: see [Stairs]. */
    const val STAIRS = "Stairs"
    /** Guard rails (Guards, Nov 24). */
    const val RAILINGS = "Railings"
    /** Ranges, cooktops, ovens, refrigerators and the like: see [Appliances]. */
    const val APPLIANCES = "Appliances"
    const val HOODS = "Hoods"
    val categories = setOf(FIXTURES, DOORS_WINDOWS, WALLS, CABINETS, OUTDOOR, CLOSET, FURNITURE, SHOWERS, STAIRS, RAILINGS, APPLIANCES, HOODS,
        Structure.FOUNDATION, Structure.FRAMING)

    /**
     * Closet parts, set against a closet wall with their backs at the top (like cabinets).
     * Lengths along the wall are the defaults: stretch them in Properties. Shelf-and-rod and
     * double-hang are 12" deep, shelving and drawer units 16", shoe shelves 12".
     */
    val closet = listOf(
        "Shelf & Rod 36\"", "Shelf & Rod 48\"", "Double Hang 36\"", "Double Hang 48\"", "Rod 48\"",
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
        "Pantry 24\"", "Island 3'x6'",
        // Bathroom vanities: 21" deep, top at the comfort height (36") unless changed.
        "Vanity 18\"", "Vanity 24\"", "Vanity 30\"", "Vanity 36\"", "Vanity 48\"",
        "Vanity 60\" Double", "Vanity 72\" Double", "Vanity Drawers 12\"", "Vanity Drawers 18\"",
        LINEN_TOWER,
        // Fillers close the gap between a run and a wall or appliance.
        "Filler 1 1/2\"", "Filler 3\"", "Filler 6\"", "Upper Filler 3\"", "Upper Filler 6\""
    )

    /** Filler strips, base (24" deep) or upper (12" deep, drawn dashed like the other uppers). */
    fun isFiller(name: String) = name.contains("Filler")

    /** Diagonal upper corner cabinet: 24" along each wall, 12"-deep arms, door across the corner. */
    const val UPPER_CORNER = "Upper Corner 24\""

    /** Tall bathroom storage cabinet beside a vanity: 18" wide, 21" deep, 84" tall. */
    const val LINEN_TOWER = "Linen Tower 18\""
    const val LINEN_TOWER_HEIGHT_IN = 84f
    const val VANITY_DEPTH_IN = 21f
    const val VANITY_TOP_IN = 36f

    /** Bathroom sinks, kitchen sinks and laundry tubs, beyond the original two. */
    const val PEDESTAL_SINK = "Pedestal Sink"
    const val WALL_HUNG_SINK = "Wall-Hung Sink"
    const val CORNER_SINK = "Corner Sink"
    const val VESSEL_SINK = "Vessel Sink"
    const val UNDERMOUNT_SINK = "Undermount Sink"
    const val DROP_IN_SINK = "Drop-In Sink"
    const val SINGLE_KITCHEN_SINK = "Single Bowl Sink 30\""
    const val FARMHOUSE_SINK = "Farmhouse Sink 33\""
    const val BAR_SINK = "Bar Sink 15\""
    const val UTILITY_SINK = "Utility Sink"

    /** Sinks that drop into a vanity top or counter: the elevation shows only the faucet (a vessel its bowl). */
    val counterSinks = setOf(VESSEL_SINK, UNDERMOUNT_SINK, DROP_IN_SINK, "Kitchen Sink", SINGLE_KITCHEN_SINK, FARMHOUSE_SINK, BAR_SINK)

    val fixtures = listOf(
        "Bathtub", CLAWFOOT_TUB, "Shower Stall", "Toilet", "Bathroom Sink",
        PEDESTAL_SINK, WALL_HUNG_SINK, CORNER_SINK, VESSEL_SINK, UNDERMOUNT_SINK, DROP_IN_SINK,
        "Kitchen Sink", SINGLE_KITCHEN_SINK, FARMHOUSE_SINK, BAR_SINK, UTILITY_SINK, "Water Heater",
        "Washer", "Dryer", "Refrigerator", "Range", "Dishwasher"
    )
    val doorsWindows = listOf(
        "Door 2'-6\"", "Door 2'-8\"", "Door 3'-0\"", "Sliding Door 6'-0\"",
        "Pocket Door 2'-6\"", "Pocket Door 2'-8\"", "Pocket Door 3'-0\"",
        "Opening 2'-6\"", "Opening 3'-0\"", "Opening 4'-0\"", "Opening 6'-0\"",
        "Window 3'-0\"", "Window 4'-0\"", "Window 6'-0\""
    )
    val walls = listOf(
        "2x4 Wall", "2x6 Wall", "2x4 Framing", "2x6 Framing", "12\" Block Wall", "10\" Concrete Wall", "8\" Block Wall", "8\" Concrete Wall"
    )

    /** A freestanding rolled-rim tub on four feet; floor tile runs under it. */
    const val CLAWFOOT_TUB = "Clawfoot Tub"

    const val DEFAULT_WALL_LENGTH_IN = 48f
    const val WINDOW_DEPTH_IN = 6f

    /** Footprint (width, depth) in inches at rotation 0. */
    val footprintInches: Map<String, Pair<Float, Float>> = mapOf(
        "Bathtub" to Pair(60f, 30f),
        CLAWFOOT_TUB to Pair(60f, 30f),
        "Shower Stall" to Pair(36f, 36f),
        "Toilet" to Pair(20f, 28f),
        "Bathroom Sink" to Pair(24f, 21f),
        "Kitchen Sink" to Pair(33f, 22f),
        PEDESTAL_SINK to Pair(22f, 18f),
        WALL_HUNG_SINK to Pair(20f, 17f),
        // Corner sink: the size along each wall; the back corner is the top-left.
        CORNER_SINK to Pair(17f, 17f),
        VESSEL_SINK to Pair(16f, 16f),
        UNDERMOUNT_SINK to Pair(19f, 15f),
        DROP_IN_SINK to Pair(20f, 17f),
        SINGLE_KITCHEN_SINK to Pair(30f, 22f),
        FARMHOUSE_SINK to Pair(33f, 22f),
        BAR_SINK to Pair(15f, 15f),
        UTILITY_SINK to Pair(24f, 22f),
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
        // A pocket door: the footprint is the opening; the pocket (as long again) is drawn inside the wall beside it.
        "Pocket Door 2'-6\"" to Pair(30f, WINDOW_DEPTH_IN),
        "Pocket Door 2'-8\"" to Pair(32f, WINDOW_DEPTH_IN),
        "Pocket Door 3'-0\"" to Pair(36f, WINDOW_DEPTH_IN),
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
        // Foundation walls (Dan): 12" block below grade, 10" poured; 8" block above grade only.
        "12\" Block Wall" to Pair(DEFAULT_WALL_LENGTH_IN, 11.625f),
        "10\" Concrete Wall" to Pair(DEFAULT_WALL_LENGTH_IN, 10f),
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
        "Vanity 18\"" to Pair(18f, VANITY_DEPTH_IN),
        "Vanity 24\"" to Pair(24f, VANITY_DEPTH_IN),
        "Vanity 30\"" to Pair(30f, VANITY_DEPTH_IN),
        "Vanity 36\"" to Pair(36f, VANITY_DEPTH_IN),
        "Vanity 48\"" to Pair(48f, VANITY_DEPTH_IN),
        "Vanity 60\" Double" to Pair(60f, VANITY_DEPTH_IN),
        "Vanity 72\" Double" to Pair(72f, VANITY_DEPTH_IN),
        "Vanity Drawers 12\"" to Pair(12f, VANITY_DEPTH_IN),
        "Vanity Drawers 18\"" to Pair(18f, VANITY_DEPTH_IN),
        LINEN_TOWER to Pair(18f, VANITY_DEPTH_IN),
        "Filler 1 1/2\"" to Pair(1.5f, 24f),
        "Filler 3\"" to Pair(3f, 24f),
        "Filler 6\"" to Pair(6f, 24f),
        "Upper Filler 3\"" to Pair(3f, 12f),
        "Upper Filler 6\"" to Pair(6f, 12f),
        // Closet parts.
        "Shelf & Rod 36\"" to Pair(36f, 12f),
        "Shelf & Rod 48\"" to Pair(48f, 12f),
        "Double Hang 36\"" to Pair(36f, 12f),
        "Double Hang 48\"" to Pair(48f, 12f),
        // A hanging rod alone, no shelf (Dan, Nov 23): stretch it to the closet.
        "Rod 48\"" to Pair(48f, 12f),
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
    ) + Shower.footprints + Stairs.footprints + Guards.footprints + Appliances.footprints + Structure.footprints

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
    /** Above the cut plane - upper cabinets, hoods, an over-the-range microwave: drawn dashed, last, without blanking. */
    fun isOverlay(symbol: DrawingElement.Symbol) = isUpperCabinet(symbol) || Appliances.isOverlay(symbol)

    fun isDoor(name: String) = name.startsWith("Door ")
    /** A hinged door's hinge jamb: off = the left jamb seen from the side it swings into. */
    const val PROP_HINGE_RIGHT = "hingeRight"
    fun hingeRight(symbol: DrawingElement.Symbol) = (symbol.properties[PROP_HINGE_RIGHT] ?: 0f) >= 0.5f
    fun isSlidingDoor(name: String) = name.startsWith("Sliding Door")
    /** A door that slides into a pocket in the wall; [PROP_HINGE_RIGHT] on = the pocket is on the right (as drawn). */
    fun isPocketDoor(name: String) = name.startsWith("Pocket Door")
    fun pocketRight(symbol: DrawingElement.Symbol) = hingeRight(symbol)
    fun isWindow(name: String) = name.startsWith("Window ")
    /**
     * How a window opens (Dan, Nov 22 2026), kept in its properties as an index into
     * [windowTypeLabels]; unset = double hung. A casement's hinge side is [PROP_HINGE_RIGHT]
     * (on = the +x end of the symbol as drawn). Shown in the window schedule and on elevations.
     */
    const val PROP_WINDOW_TYPE = "windowType"
    val windowTypeLabels = listOf("Double hung", "Slider", "Casement", "Awning", "Fixed (picture)")
    const val WINDOW_DOUBLE_HUNG = 0
    const val WINDOW_SLIDER = 1
    const val WINDOW_CASEMENT = 2
    const val WINDOW_AWNING = 3
    const val WINDOW_FIXED = 4
    fun windowType(symbol: DrawingElement.Symbol): Int =
        (symbol.properties[PROP_WINDOW_TYPE] ?: 0f).toInt().coerceIn(0, windowTypeLabels.lastIndex)
    /** A cased opening (doorway without a door). */
    fun isOpening(name: String) = name.startsWith("Opening ")

    /** Per-opening heights kept in [DrawingElement.Symbol.properties], in inches. */
    const val PROP_SILL_IN = "openingSillIn"
    const val PROP_HEIGHT_IN = "openingHeightIn"

    /**
     * Cabinet fronts, kept in the symbol's properties as numbers. [PROP_FRONT]: 0 doors only,
     * 1 top drawer over doors, 2 three drawers, 3 four drawers. [PROP_DOORS]: 0 by width
     * (two doors from 30"), 1 one door, 2 two doors. [PROP_OVERHANG] (islands): 0 none, 1 the
     * front, 2 front and back, 3 front and the right end; [PROP_OVERHANG_IN] how far, inches.
     */
    const val PROP_FRONT = "front"
    const val PROP_DOORS = "doors"
    const val PROP_OVERHANG = "overhang"
    const val PROP_OVERHANG_IN = "overhangIn"
    const val FRONT_DOORS = 0
    const val FRONT_DRAWER_DOORS = 1
    const val FRONT_THREE_DRAWERS = 2
    const val FRONT_FOUR_DRAWERS = 3
    val frontLabels = listOf("Doors", "Drawer + doors", "3 drawers", "4 drawers")
    val overhangLabels = listOf("None", "Front", "Front + back", "Front + end")

    /** Cabinets whose fronts can be chosen: base units (not corners), islands and vanities. */
    fun hasFrontChoice(symbol: DrawingElement.Symbol) =
        symbol.category == CABINETS && (symbol.symbolName.startsWith("Base") || symbol.symbolName.startsWith("Island") || isVanity(symbol.symbolName))
    fun hasDoorChoice(symbol: DrawingElement.Symbol) =
        symbol.category == CABINETS && !isFiller(symbol.symbolName) && !symbol.symbolName.startsWith("Corner") &&
            symbol.symbolName != UPPER_CORNER && !isVanityDrawers(symbol.symbolName)
    fun isIsland(symbol: DrawingElement.Symbol) = symbol.category == CABINETS && symbol.symbolName.startsWith("Island")

    /** An upper cabinet's own height (30", 36", 42"...), inches; unset = the elevation's "Uppers high". */
    const val PROP_UPPER_HEIGHT_IN = "upperHeightIn"
    /** A door's height (its head), inches. */
    const val DOOR_HEIGHT_IN = 80f
    /** A new window: 48" tall, its head lined up with the door head (Dan, Nov 2026), so the sill is 32". */
    const val NEW_WINDOW_HEIGHT_IN = 48f
    const val NEW_WINDOW_SILL_IN = DOOR_HEIGHT_IN - NEW_WINDOW_HEIGHT_IN

    const val DEFAULT_UPPER_HEIGHT_IN = 30f
    /** Pantries stand from the floor to the tops of the uppers: 54" + 30". */
    const val PANTRY_HEIGHT_IN = 84f
    fun upperHeightOf(symbol: DrawingElement.Symbol): Float? = symbol.properties[PROP_UPPER_HEIGHT_IN]

    /** Inches as a cabinet code writes them: 24, 30, 1-1/2 (to the nearest 1/8"). */
    fun codeInches(v: Float): String {
        val eighths = kotlin.math.round(v * 8f).toInt()
        val whole = eighths / 8
        val rest = eighths % 8
        if (rest == 0) return whole.toString()
        var num = rest
        var den = 8
        while (num % 2 == 0) { num /= 2; den /= 2 }
        return if (whole == 0) "$num/$den" else "$whole-$num/$den"
    }

    /** A sink set into this cabinet's top: its centre lies over the cabinet. */
    fun hasSinkOver(cabinet: DrawingElement.Symbol, state: CanvasState): Boolean {
        val b = FloorPlan.bounds(cabinet)
        return state.elements.any { it is DrawingElement.Symbol && it.category == FIXTURES && it.symbolName in counterSinks && b.contains(it.center) }
    }

    /**
     * The shop code for a cabinet, the way cabinet lists and plans write them: width in inches,
     * and for wall cabinets the height too. B24 base, BD18 drawer base (3 or 4 drawers), SB36
     * sink base (a sink sits in its top), LS36 corner (lazy Susan) base, W2430 wall, WDC2430
     * diagonal corner wall, P2484 pantry, V30 vanity (VD18 vanity drawers), LT18 linen tower,
     * BF3 / WF3 base and wall fillers, ISL72 island. Null for anything that is not a cabinet.
     */
    fun cabinetLabel(symbol: DrawingElement.Symbol, state: CanvasState): String? {
        if (symbol.category != CABINETS) return null
        val name = symbol.symbolName
        val inch = state.inchesToPx(1f)
        val w = codeInches(symbol.size.width / inch)
        val upperH = codeInches(upperHeightOf(symbol) ?: DEFAULT_UPPER_HEIGHT_IN)
        return when {
            isFiller(name) -> (if (name.startsWith("Upper")) "WF" else "BF") + w
            name == UPPER_CORNER -> "WDC$w$upperH"
            name.startsWith("Upper") -> "W$w$upperH"
            name.startsWith("Corner Base") -> "LS$w"
            name.startsWith("Base") -> when {
                hasSinkOver(symbol, state) -> "SB$w"
                front(symbol) == FRONT_THREE_DRAWERS || front(symbol) == FRONT_FOUR_DRAWERS -> "BD$w"
                else -> "B$w"
            }
            name.startsWith("Pantry") -> "P$w${codeInches(PANTRY_HEIGHT_IN)}"
            isLinenTower(name) -> "LT$w"
            isVanityDrawers(name) -> "VD$w"
            isVanity(name) -> "V$w"
            isIsland(symbol) -> "ISL$w"
            else -> null
        }
    }

    /** Bathroom vanities, including the drawer bases that sit in a vanity run (not the linen tower). */
    fun isVanity(name: String) = name.startsWith("Vanity")
    fun isVanityDrawers(name: String) = name.startsWith("Vanity Drawers")
    fun isLinenTower(name: String) = name == LINEN_TOWER

    /**
     * Vanity settings in the symbol's properties. [PROP_TOP_IN]: height to the top of the
     * vanity top, inches (36" comfort height by default; 32" is the older standard).
     * [PROP_SINKS]: 0 by size (two on a "Double", none on a drawer base, else one), 1 one
     * sink, 2 two sinks, 3 no sink (a separate sink symbol sits on it instead).
     */
    const val PROP_TOP_IN = "topIn"
    const val PROP_SINKS = "sinks"
    val sinkLabels = listOf("By size", "1 sink", "2 sinks", "No sink")
    fun hasSinkChoice(symbol: DrawingElement.Symbol) =
        symbol.category == CABINETS && isVanity(symbol.symbolName) && !isVanityDrawers(symbol.symbolName)
    fun vanityTop(symbol: DrawingElement.Symbol): Float = symbol.properties[PROP_TOP_IN] ?: VANITY_TOP_IN
    fun sinkCount(symbol: DrawingElement.Symbol): Int = when (symbol.properties[PROP_SINKS]?.toInt() ?: 0) {
        1 -> 1
        2 -> 2
        3 -> 0
        else -> if (isVanityDrawers(symbol.symbolName)) 0 else if (symbol.symbolName.contains("Double")) 2 else 1
    }

    /** Door and drawer face style, and hardware, kept in the cabinet's properties. */
    const val PROP_STYLE = "doorStyle"
    const val PROP_HARDWARE = "hardware"
    const val STYLE_SLAB = 0
    const val STYLE_SHAKER = 1
    const val STYLE_RAISED = 2
    const val STYLE_GLASS = 3
    val styleLabels = listOf("Slab", "Shaker", "Raised panel", "Glass")
    const val HW_KNOBS_PULLS = 0 // knobs on doors, bar pulls on drawers
    const val HW_BAR_PULLS = 1
    const val HW_KNOBS = 2
    val hardwareLabels = listOf("Knobs + drawer pulls", "Bar pulls", "Knobs")
    /** Cabinets with a door or drawer face to style (not fillers). */
    fun hasStyleChoice(symbol: DrawingElement.Symbol) = symbol.category == CABINETS && !isFiller(symbol.symbolName)
    fun doorStyle(symbol: DrawingElement.Symbol): Int = symbol.properties[PROP_STYLE]?.toInt() ?: STYLE_SLAB
    fun hardware(symbol: DrawingElement.Symbol): Int = symbol.properties[PROP_HARDWARE]?.toInt() ?: HW_KNOBS_PULLS

    fun front(symbol: DrawingElement.Symbol): Int =
        symbol.properties[PROP_FRONT]?.toInt() ?: when {
            isVanityDrawers(symbol.symbolName) -> FRONT_THREE_DRAWERS
            symbol.symbolName.startsWith("Base") || isVanity(symbol.symbolName) -> FRONT_DRAWER_DOORS
            else -> FRONT_DOORS
        }

    /** How many doors a run of [widthIn] gets: the override, else two from 30" wide. */
    fun doorCount(symbol: DrawingElement.Symbol, widthIn: Float): Int {
        val chosen = symbol.properties[PROP_DOORS]?.toInt() ?: 0
        return if (chosen in 1..2) chosen else if (widthIn >= 30f) 2 else 1
    }

    /**
     * Symbols whose renderer draws them vertically when taller than wide (walls, windows,
     * sliding doors). A right-angle turn swaps their width and height instead of setting a
     * rotation, so they stay axis-aligned for snapping and hit-testing.
     */
    fun turnsBySwappingSize(symbol: DrawingElement.Symbol) =
        symbol.category == WALLS || isWindow(symbol.symbolName) || isSlidingDoor(symbol.symbolName) || isOpening(symbol.symbolName) ||
            isPocketDoor(symbol.symbolName)
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
        // Round and shaped fixtures blank only their own shape, so a floor hatch shows right
        // up to a toilet bowl or a tub's corners rather than stopping in a rectangle around it.
        // Foundation and framing parts are drawn over each other and over the faint layers below
        // (a joist bay over its footings, a ladder over the bay), so they blank nothing.
        if (!PlanSymbols.isDoor(name) && !PlanSymbols.isOverlay(element) && element.category !in Structure.categories) {
            val shape = blankShape(element, w, h, inch)
            if (shape != null) drawPath(shape, color = state.backgroundColor)
            else drawRect(color = state.backgroundColor, topLeft = Offset(-hw, -hh), size = Size(w, h))
        }
        when {
            element.category == PlanSymbols.WALLS -> {
                // A wall taller than it is wide runs vertically: draw it sideways so the studs
                // and hatch follow its length. (The floor-plan tool never rotates walls.)
                // Remodel (Remodel.kt): existing walls are a plain outline, demo walls dashed, new walls in full.
                val status = Remodel.drawStyle(element, state.remodelPhase)
                // Studs on the house layout (FramingLayout): a wall running west-east measures from the
                // west wall, one running north-south from the north wall (drawn turned, its start at the top).
                val first = wallFirstStud(element, state, inch)
                val ros = FramingLayout.openingSpans(state)[element.id] ?: emptyList()
                val bucks = FramingLayout.bucks(state)[element.id] ?: emptyList()
                if (h > w) rotate(90f, pivot = Offset.Zero) { drawWallSection(name, h, w, inch, lineColor, accentColor, line, thin, status, first, ros, bucks) }
                else drawWallSection(name, w, h, inch, lineColor, accentColor, line, thin, status, first, ros, bucks)
                // Where a wing joins (Wings), this house wall comes out: blank the stretch and close the ends.
                if (name == FloorPlan.EXTERIOR_WALL && !DerivedLayers.isGenerated(element) && element.rotation == 0f) {
                    val fp = runCatching { RoofPlan.footprint(state) }.getOrNull()
                    val me = Rect(element.center.x - hw, element.center.y - hh, element.center.x + hw, element.center.y + hh)
                    for (wg in Wings.wings(state)) {
                        val j = Wings.joint(wg, fp, inch) ?: continue
                        val cut = me.intersect(j)
                        if (cut.width <= 0f || cut.height <= 0f) continue
                        val local = cut.translate(-element.center.x, -element.center.y)
                        // Past the faces by the line width, so no half of the outline is left across the opening.
                        val lw = line.width
                        val blank = if (w >= h) Rect(local.left, local.top - lw, local.right, local.bottom + lw) else Rect(local.left - lw, local.top, local.right + lw, local.bottom)
                        drawRect(state.backgroundColor, blank.topLeft, blank.size)
                        if (w >= h) {
                            if (cut.left > me.left + 0.5f) drawLine(lineColor, Offset(local.left, -hh), Offset(local.left, hh), line.width)
                            if (cut.right < me.right - 0.5f) drawLine(lineColor, Offset(local.right, -hh), Offset(local.right, hh), line.width)
                        } else {
                            if (cut.top > me.top + 0.5f) drawLine(lineColor, Offset(-hw, local.top), Offset(hw, local.top), line.width)
                            if (cut.bottom < me.bottom - 0.5f) drawLine(lineColor, Offset(-hw, local.bottom), Offset(hw, local.bottom), line.width)
                        }
                    }
                }
            }
            element.category in Structure.categories ->
                with(Structure) { drawStructurePlan(element, state, w, h, inch, lineColor, accentColor, faint, line, thin) }
            element.category == PlanSymbols.SHOWERS -> with(Shower) { drawShowerPlan(element, w, h, inch, lineColor, accentColor, faint, line, thin) }
            element.category == PlanSymbols.STAIRS -> with(Stairs) { drawStairPlan(element, w, h, inch, lineColor, accentColor, faint, line, thin) }
            element.category == PlanSymbols.RAILINGS -> with(Guards) { drawGuardPlan(element, w, h, inch, lineColor, line, thin) }
            element.category == PlanSymbols.APPLIANCES || element.category == PlanSymbols.HOODS ->
                with(Appliances) { drawAppliancePlan(element, w, h, inch, lineColor, accentColor, faint, line, thin) }
            element.category == PlanSymbols.CABINETS -> drawCabinet(element, w, h, inch, lineColor, accentColor, faint, line, thin)
            element.category == PlanSymbols.CLOSET -> drawClosetPart(name, w, h, inch, lineColor, accentColor, faint, line, thin)
            element.category == PlanSymbols.FURNITURE -> drawFurniture(name, w, h, inch, lineColor, accentColor, faint, line, thin)
            PlanSymbols.isDoor(name) -> {
                // Hinge at the bottom-left corner, leaf standing open along the left edge, swing
                // arc sweeping to the latch at the bottom-right corner. The threshold (bottom
                // edge) is where the wall is: blank a strip there so the door cuts an opening.
                val leaf = inch * 1.75f
                // Cut the wall the door sits in, and only the wall: a fixed 8" strip reached past
                // a 4 1/2" wall into the room and wiped out a band of floor hatch at the door.
                val (cutTop, cutDepth) = doorWallCut(element, state, inch)
                drawRect(color = state.backgroundColor, topLeft = Offset(-hw, cutTop), size = Size(w, cutDepth))
                // The jambs take the wall's thickness (Dan, Nov 24: a door in a 2x6 wall looked set for a 2x4): a
                // jamb line across the wall at each side, and the leaf hinged at the face of the wall on the side
                // it swings into. Off a wall, the threshold stays the hinge line.
                val inWall = cutDepth != DOOR_CUT_FALLBACK_IN * inch
                val margin = inch * 0.25f
                val face = if (inWall) cutTop + margin else hh
                val back = if (inWall) cutTop + cutDepth - margin else hh
                if (inWall) for (x in listOf(-hw, hw)) drawLine(color = lineColor, start = Offset(x, face), end = Offset(x, back), strokeWidth = thin.width)
                drawLine(color = faint, start = Offset(-hw, (face + back) / 2f), end = Offset(hw, (face + back) / 2f), strokeWidth = thin.width)
                // Hinged on the right jamb: the same door mirrored, leaf up the right edge.
                if (PlanSymbols.hingeRight(element)) {
                    drawRect(color = lineColor, topLeft = Offset(hw - leaf, face - w), size = Size(leaf, w), style = line)
                    drawArc(
                        color = accentColor, startAngle = -90f, sweepAngle = -90f, useCenter = false,
                        topLeft = Offset(hw - w, face - w), size = Size(2f * w, 2f * w), style = thin
                    )
                } else {
                    drawRect(color = lineColor, topLeft = Offset(-hw, face - w), size = Size(leaf, w), style = line)
                    drawArc(
                        color = accentColor, startAngle = -90f, sweepAngle = 90f, useCenter = false,
                        topLeft = Offset(-hw - w, face - w), size = Size(2f * w, 2f * w), style = thin
                    )
                }
            }
            PlanSymbols.isSlidingDoor(name) || PlanSymbols.isWindow(name) || PlanSymbols.isOpening(name) || PlanSymbols.isPocketDoor(name) -> {
                // Taller than wide = sitting in a vertical wall: draw it turned, like walls.
                val pocketRight = PlanSymbols.pocketRight(element)
                if (h > w) rotate(90f, pivot = Offset.Zero) { drawOpening(name, h, w, inch, state.backgroundColor, lineColor, accentColor, faint, line, thin, pocketRight) }
                else drawOpening(name, w, h, inch, state.backgroundColor, lineColor, accentColor, faint, line, thin, pocketRight)
            }
            name == PlanSymbols.CLAWFOOT_TUB -> {
                // Rolled rim: rounded ends, the bowl inside, four feet, drain and wall-mount filler at the left end.
                drawRoundRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), cornerRadius = CornerRadius(hh, hh), style = line)
                val rim = inch * 2.5f
                drawRoundRect(
                    color = accentColor, topLeft = Offset(-hw + rim, -hh + rim), size = Size(w - 2 * rim, h - 2 * rim),
                    cornerRadius = CornerRadius(hh - rim, hh - rim), style = thin
                )
                for (fx in listOf(-hw + w * 0.18f, hw - w * 0.18f)) for (fy in listOf(-hh, hh)) {
                    drawCircle(color = faint, radius = inch * 1.5f, center = Offset(fx, fy * 0.92f), style = thin)
                }
                drawCircle(color = accentColor, radius = inch * 1.5f, center = Offset(-hw + inch * 9f, 0f), style = thin)
                drawRect(color = lineColor, topLeft = Offset(-hw - inch * 1f, -inch * 3f), size = Size(inch * 2.5f, inch * 6f), style = thin)
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
            name == PlanSymbols.PEDESTAL_SINK || name == PlanSymbols.WALL_HUNG_SINK -> {
                // No cabinet: a basin with a straight back against the wall and a rounded front.
                val outline = Path().apply {
                    moveTo(-hw, -hh); lineTo(hw, -hh); lineTo(hw, -hh + h * 0.3f)
                    cubicTo(hw, hh, -hw, hh, -hw, -hh + h * 0.3f); close()
                }
                drawPath(outline, color = lineColor, style = line)
                val bw = w * 0.62f
                val bh = h * 0.55f
                drawOval(color = accentColor, topLeft = Offset(-bw / 2f, -hh + h * 0.3f), size = Size(bw, bh), style = thin)
                drawCircle(color = lineColor, radius = inch, center = Offset(0f, -hh + inch * 2f), style = thin)
                if (name == PlanSymbols.WALL_HUNG_SINK) {
                    // Wall brackets, dashed under the basin.
                    val dashed = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 1.5f, inch)))
                    for (x in listOf(-hw + inch * 3f, hw - inch * 3f)) {
                        drawPath(Path().apply { moveTo(x, -hh); lineTo(x, -hh + h * 0.45f) }, color = faint, style = dashed)
                    }
                }
            }
            name == PlanSymbols.CORNER_SINK -> {
                // Back corner at the top-left, the two backs along the top and left edges, a
                // curved front across the room side.
                val outline = Path().apply {
                    moveTo(-hw, -hh); lineTo(hw, -hh)
                    cubicTo(hw, hh * 0.2f, hw * 0.2f, hh, -hw, hh); close()
                }
                drawPath(outline, color = lineColor, style = line)
                val bowl = Path().apply {
                    moveTo(-hw + inch * 2.5f, -hh + inch * 2.5f); lineTo(hw - inch * 5f, -hh + inch * 2.5f)
                    cubicTo(hw - inch * 5f, hh * 0.05f, hw * 0.05f, hh - inch * 5f, -hw + inch * 2.5f, hh - inch * 5f); close()
                }
                drawPath(bowl, color = accentColor, style = thin)
                drawCircle(color = lineColor, radius = inch, center = Offset(-hw + inch * 2f, -hh + inch * 2f), style = thin)
            }
            name == PlanSymbols.VESSEL_SINK -> {
                // A bowl sitting on the top; the faucet stands behind it.
                val r = min(hw, hh)
                drawCircle(color = lineColor, radius = r, center = Offset.Zero, style = line)
                drawCircle(color = accentColor, radius = r * 0.8f, center = Offset.Zero, style = thin)
                drawCircle(color = accentColor, radius = inch, center = Offset.Zero, style = thin)
            }
            name == PlanSymbols.UNDERMOUNT_SINK -> {
                // The bowl under the top: no rim, just the bowl edge and the drain.
                drawOval(color = accentColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                drawCircle(color = accentColor, radius = inch, center = Offset.Zero, style = thin)
            }
            name == PlanSymbols.DROP_IN_SINK -> {
                // Rim on the top, bowl inside it, faucet holes in the back of the rim.
                val r = CornerRadius(inch * 2f, inch * 2f)
                drawRoundRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), cornerRadius = r, style = line)
                val bw = w - inch * 4f
                val bh = h - inch * 6f
                drawOval(color = accentColor, topLeft = Offset(-bw / 2f, -hh + inch * 4f), size = Size(bw, bh), style = thin)
                drawCircle(color = lineColor, radius = inch * 0.75f, center = Offset(0f, -hh + inch * 2f), style = thin)
            }
            name == PlanSymbols.SINGLE_KITCHEN_SINK || name == PlanSymbols.BAR_SINK || name == PlanSymbols.FARMHOUSE_SINK -> {
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                val gap = inch * 1.5f
                val r = CornerRadius(inch, inch)
                val top = -hh + inch * 4f
                val bottom = if (name == PlanSymbols.FARMHOUSE_SINK) hh - inch * 1.5f else hh - gap
                drawRoundRect(color = accentColor, topLeft = Offset(-hw + gap, top), size = Size(w - 2f * gap, bottom - top), cornerRadius = r, style = thin)
                drawCircle(color = lineColor, radius = inch, center = Offset(0f, -hh + inch * 2f), style = thin)
                if (name == PlanSymbols.FARMHOUSE_SINK) {
                    // The apron: the sink's own front, standing proud of the cabinets.
                    drawLine(color = lineColor, start = Offset(-hw, hh - inch * 0.75f), end = Offset(hw, hh - inch * 0.75f), strokeWidth = line.width)
                }
            }
            name == PlanSymbols.UTILITY_SINK -> {
                drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
                val ins = inch * 2f
                drawRoundRect(color = accentColor, topLeft = Offset(-hw + ins, -hh + inch * 4f), size = Size(w - 2f * ins, h - inch * 4f - ins), cornerRadius = CornerRadius(inch * 1.5f, inch * 1.5f), style = thin)
                drawCircle(color = lineColor, radius = inch, center = Offset(0f, -hh + inch * 2f), style = thin)
                label("UT", lineColor, min(hw, hh) * 0.4f, Offset(0f, inch * 2f))
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
        // The cabinet's code (W2430, BD18, SB36...): in the front of a base (its back is under the
        // uppers), in the middle of anything else, and never upside down. Fillers too narrow to
        // read get none on the plan (they are still in the cabinet list).
        if (state.showCabinetLabels && element.category == PlanSymbols.CABINETS && w >= inch * 8f) {
            PlanSymbols.cabinetLabel(element, state)?.let { text ->
                val frontZone = !PlanSymbols.isUpperCabinet(element) && !PlanSymbols.isIsland(element) &&
                    !name.startsWith("Corner") && h >= inch * 20f
                val at = if (frontZone) Offset(0f, hh - inch * 6f) else Offset.Zero
                val r = ((element.rotation % 360f) + 360f) % 360f
                val flip = r > 90.5f && r <= 270.5f
                val size = minOf(inch * 4f, w * 1.6f / text.length, h * 0.4f)
                drawLabel(text, at, size, lineColor, rotationDegrees = if (flip) 180f else 0f)
            }
        }
    }
}

/** A window or sliding door of width [w] in a wall [h] thick, centred on the origin, running left to right. */
private fun DrawScope.drawOpening(
    name: String, w: Float, h: Float, inch: Float, background: Color,
    lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke, pocketRight: Boolean = false
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
    } else if (PlanSymbols.isPocketDoor(name)) {
        // The opening with a jamb each side; the pocket runs inside the wall beside it (dashed), the
        // door slab mostly in the pocket with a few inches showing, so the pull is visible.
        drawLine(color = lineColor, start = Offset(-hw, -blank / 2f), end = Offset(-hw, blank / 2f), strokeWidth = line.width)
        drawLine(color = lineColor, start = Offset(hw, -blank / 2f), end = Offset(hw, blank / 2f), strokeWidth = line.width)
        val s = if (pocketRight) 1f else -1f
        val cavity = inch * 1f
        val dashed = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 2f, inch * 1.5f)))
        val pocketNear = s * hw
        val pocketFar = s * (hw + w)
        drawPath(Path().apply {
            moveTo(pocketNear, -cavity); lineTo(pocketFar, -cavity); lineTo(pocketFar, cavity); lineTo(pocketNear, cavity)
        }, color = faint, style = dashed)
        val slab = inch * 1.375f
        val showing = inch * 4f
        val a = pocketNear - s * showing
        val b = pocketNear + s * (w - showing)
        drawRect(color = lineColor, topLeft = Offset(min(a, b), -slab / 2f), size = Size(kotlin.math.abs(b - a), slab), style = line)
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
    lineColor: Color, accentColor: Color, line: Stroke, thin: Stroke,
    status: WallStatus = WallStatus.NEW,
    /** From the wall's start to the first stud on the house layout (FramingLayout), px; null = from the wall's own end. */
    firstStud: Float? = null,
    /** Rough openings in the wall, px from its start (FramingLayout.openingSpans): no layout studs, a jack and a king each side. */
    openings: List<Pair<Float, Float>> = emptyList(),
    /** Wall bucks where other walls meet this one (FramingLayout.bucks). */
    bucks: List<FramingLayout.Buck> = emptyList()
) {
    val hw = w / 2f
    val hh = h / 2f
    if (status == WallStatus.DEMO) {
        // Coming out: a dashed outline, nothing inside, the usual way demo shows on a plan.
        val dashed = Stroke(width = line.width, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 6f, inch * 4f)))
        drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = dashed)
        return
    }
    drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
    // Existing: just the outline, so the new work (studs, block, concrete) stands out against it.
    if (status == WallStatus.EXISTING) return
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
                for (x in wallStudXs(hw, inch, firstStud, openings, bucks, studH)) {
                    drawRect(color = accentColor, topLeft = Offset(x, studTop), size = Size(studW, studH), style = thin)
                    drawLine(color = accentColor, start = Offset(x, studTop), end = Offset(x + studW, studTop + studH), strokeWidth = thin.width)
                    drawLine(color = accentColor, start = Offset(x, studTop + studH), end = Offset(x + studW, studTop), strokeWidth = thin.width)
                }
                // Each buck's flat stud against the face the other wall comes in on.
                for (b in bucks) {
                    val x = buckFlatX(hw, inch, b, studH)
                    val y = if (b.side > 0) studTop + studH - studW else studTop
                    drawRect(color = accentColor, topLeft = Offset(x, y), size = Size(studH, studW), style = thin)
                    drawLine(color = accentColor, start = Offset(x, y), end = Offset(x + studH, y + studW), strokeWidth = thin.width)
                    drawLine(color = accentColor, start = Offset(x, y + studW), end = Offset(x + studH, y), strokeWidth = thin.width)
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
    element: DrawingElement.Symbol, w: Float, h: Float, inch: Float,
    lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke
) {
    val name = element.symbolName
    val hw = w / 2f
    val hh = h / 2f
    val face = inch * 1.5f
    val twoDoors = PlanSymbols.doorCount(element, w / inch) == 2
    when {
        PlanSymbols.isFiller(name) -> {
            // A plain strip with a diagonal through it; upper fillers dashed like the uppers.
            val upper = name.startsWith("Upper")
            val style = if (upper) Stroke(
                width = line.width, cap = StrokeCap.Butt,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 3f, inch * 2f))
            ) else line
            drawRect(color = if (upper) accentColor else lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = style)
            drawLine(color = faint, start = Offset(-hw, hh), end = Offset(hw, -hh), strokeWidth = thin.width)
        }
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
        PlanSymbols.isVanity(name) -> {
            // The vanity top with its bowl(s), the door/drawer face along the front.
            drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
            drawLine(color = accentColor, start = Offset(-hw, hh - face), end = Offset(hw, hh - face), strokeWidth = line.width)
            val front = PlanSymbols.front(element)
            if (front == PlanSymbols.FRONT_THREE_DRAWERS || front == PlanSymbols.FRONT_FOUR_DRAWERS) {
                drawLine(color = accentColor, start = Offset(-hw, hh - face * 0.5f), end = Offset(hw, hh - face * 0.5f), strokeWidth = thin.width)
            } else if (twoDoors) {
                drawLine(color = accentColor, start = Offset(0f, hh - face), end = Offset(0f, hh), strokeWidth = line.width)
            }
            val sinks = PlanSymbols.sinkCount(element)
            if (sinks > 0) {
                val slot = w / sinks
                val bw = min(slot * 0.65f, inch * 17f)
                val bh = min((h - face) * 0.6f, inch * 13f)
                for (i in 0 until sinks) {
                    val cx = -hw + slot * (i + 0.5f)
                    val cy = -hh + (h - face) * 0.55f
                    drawOval(color = accentColor, topLeft = Offset(cx - bw / 2f, cy - bh / 2f), size = Size(bw, bh), style = thin)
                    drawCircle(color = lineColor, radius = inch, center = Offset(cx, cy - bh / 2f - inch * 1.5f), style = thin)
                }
            } else if (PlanSymbols.isVanityDrawers(name)) {
                label("DRW", accentColor, (inch * 3.5f).coerceAtLeast(6f))
            }
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
            // Seating overhang: the countertop reaches past the cabinet on the chosen sides.
            val over = element.properties[PlanSymbols.PROP_OVERHANG]?.toInt() ?: 0
            if (over > 0) {
                val d = inch * (element.properties[PlanSymbols.PROP_OVERHANG_IN] ?: 12f)
                val left = -hw
                val right = if (over == 3) hw + d else hw
                val top = if (over == 2) -hh - d else -hh
                val bottom = hh + d
                drawRect(color = accentColor, topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = thin)
                // Stool marks along the overhang(s).
                var sx = -hw + inch * 12f
                while (sx < hw - inch * 6f) {
                    drawCircle(color = faint, radius = inch * 6f, center = Offset(sx, hh + d - inch * 6f), style = thin)
                    if (over == 2) drawCircle(color = faint, radius = inch * 6f, center = Offset(sx, -hh - d + inch * 6f), style = thin)
                    sx += inch * 24f
                }
                if (over == 3) drawCircle(color = faint, radius = inch * 6f, center = Offset(hw + d - inch * 6f, 0f), style = thin)
            }
        }
        else -> {
            // Base and pantry: outline, door face along the front, split doors when chosen or
            // wide enough; drawer banks get the front line doubled.
            drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
            drawLine(color = accentColor, start = Offset(-hw, hh - face), end = Offset(hw, hh - face), strokeWidth = line.width)
            val front = PlanSymbols.front(element)
            if (front == PlanSymbols.FRONT_THREE_DRAWERS || front == PlanSymbols.FRONT_FOUR_DRAWERS) {
                drawLine(color = accentColor, start = Offset(-hw, hh - face * 0.5f), end = Offset(hw, hh - face * 0.5f), strokeWidth = thin.width)
            } else if (twoDoors) {
                drawLine(color = accentColor, start = Offset(0f, hh - face), end = Offset(0f, hh), strokeWidth = line.width)
            }
            if (name.startsWith("Pantry") || PlanSymbols.isLinenTower(name)) {
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
    // A rod alone has no shelf, so no outline: just the rod.
    if (!name.startsWith("Rod ")) drawRect(color = lineColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = line)
    when {
        name.startsWith("Shelf & Rod") || name.startsWith("Double Hang") || name.startsWith("Rod ") -> {
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

/**
 * Where (local y of the top, depth) a door cuts its wall: the thickness of the wall under its
 * threshold, centred on that wall, plus 1/4" each side; an 8" strip on the threshold when no
 * wall is found there.
 */
/**
 * Left edges of a wall's studs (1 1/2"), the wall centred on 0 and [hw] half its length, px: one at each end and the
 * layout between, on the house layout from [firstStud] (FramingLayout) or 16" from the left end; in a rough opening
 * ([openings], px from the wall's start) no layout studs, a jack against each side and a king outside it (Dan).
 * Cripples stay on layout (not cut here). Shared by the plan and the DXF export.
 */
internal fun wallStudXs(hw: Float, inch: Float, firstStud: Float?, openings: List<Pair<Float, Float>>,
                        bucks: List<FramingLayout.Buck> = emptyList(), depthPx: Float = 0f): List<Float> {
    val studW = inch * 1.5f
    val xs = if (firstStud != null) {
        val sp = inch * FramingLayout.SPACING_IN
        val mids = generateSequence(-hw + firstStud) { it + sp }.takeWhile { it + studW / 2f <= hw - studW }
            .filter { it - studW / 2f >= -hw + studW }.map { it - studW / 2f }.toList()
        (listOf(-hw) + mids).toMutableList()
    } else generateSequence(-hw) { it + inch * 16f }.takeWhile { it + studW <= hw - studW * 0.5f }.toMutableList()
    xs += hw - studW
    // Wall bucks (Dan, Nov 24): the pair of studs, the layout studs the buck covers taken out (the flat third: [buckFlats]).
    for (b in bucks) {
        val p0 = -hw + b.centerPx - (2f * studW + depthPx) / 2f
        xs.removeAll { x -> x + studW > p0 + 0.01f && x < p0 + 2f * studW + depthPx - 0.01f }
        xs += p0; xs += p0 + studW
    }
    if (openings.isNotEmpty()) {
        val ros = openings.map { (a, b) -> (-hw + a) to (-hw + b) }
        xs.removeAll { x -> ros.any { (a, b) -> x + studW > a - 2f * studW && x < b + 2f * studW } }
        for ((a, b) in ros) {
            for (x in listOf(a - studW, a - 2f * studW, b, b + studW)) if (x >= -hw - 0.01f && x + studW <= hw + 0.01f) xs += x
        }
    }
    return xs
}

/** A wall buck's flat stud: its left edge along the wall (wall centred on 0, px), as long as the studs are deep ([depthPx]). */
internal fun buckFlatX(hw: Float, inch: Float, b: FramingLayout.Buck, depthPx: Float): Float =
    -hw + b.centerPx - (3f * inch + depthPx) / 2f + 3f * inch

/** The house layout's first stud for a plan wall, px from its start (west or north end); null when it isn't square. */
internal fun wallFirstStud(element: DrawingElement.Symbol, state: CanvasState, inch: Float): Float? {
    if (element.rotation % 360f != 0f) return null
    val o = FramingLayout.origin(state) ?: return null
    val sp = inch * FramingLayout.SPACING_IN
    val hw = element.size.width / 2f
    val hh = element.size.height / 2f
    return if (element.size.height > element.size.width) FramingLayout.firstMark(element.center.y - hh, o.y, sp) else FramingLayout.firstMark(element.center.x - hw, o.x, sp)
}

/** The cut a door makes when it isn't sitting in a wall, inches. */
internal const val DOOR_CUT_FALLBACK_IN = 8f

internal fun doorWallCut(door: DrawingElement.Symbol, state: CanvasState, inch: Float): Pair<Float, Float> {
    val hh = door.size.height / 2f
    val threshold = rotateAbout(door.center + Offset(0f, hh), door.center, door.rotation)
    val wall = Layers.onLevelOf(door, state).asSequence()
        .filterIsInstance<DrawingElement.Symbol>()
        .filter { it.category == PlanSymbols.WALLS }
        .map { FloorPlan.bounds(it) }
        .firstOrNull { it.inflate(inch).contains(threshold) }
        ?: return Pair(hh - inch * DOOR_CUT_FALLBACK_IN / 2f, inch * DOOR_CUT_FALLBACK_IN)
    val thickness = min(wall.width, wall.height)
    // The door's own "down" (towards the threshold) in world space; how far along it the wall's
    // centre line lies from the threshold.
    val down = rotateAbout(Offset(0f, 1f), Offset.Zero, door.rotation)
    val toCentre = wall.center - threshold
    val along = toCentre.x * down.x + toCentre.y * down.y
    val margin = inch * 0.25f
    return Pair(hh + along - thickness / 2f - margin, thickness + 2f * margin)
}

/** The outline a plan symbol blanks under itself, or null for its whole footprint rectangle. */
private fun blankShape(element: DrawingElement.Symbol, w: Float, h: Float, inch: Float): Path? {
    val hw = w / 2f
    val hh = h / 2f
    val name = element.symbolName
    fun oval(r: androidx.compose.ui.geometry.Rect) = Path().apply { addOval(r) }
    return when {
        name == "Toilet" -> {
            val tankD = min(h * 0.3f, inch * 8f)
            val tankW = min(w, inch * 20f)
            val bowlW = min(w, inch * 14f)
            val bowlTop = -hh + tankD
            Path().apply {
                addRect(androidx.compose.ui.geometry.Rect(-tankW / 2f, -hh, tankW / 2f, -hh + tankD))
                addOval(androidx.compose.ui.geometry.Rect(-bowlW / 2f, bowlTop - inch, bowlW / 2f, hh))
            }
        }
        name == "Bathtub" -> {
            val r = min(w, h) * 0.15f
            Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(-hw, -hh, hw, hh, CornerRadius(r, r))) }
        }
        name == PlanSymbols.CLAWFOOT_TUB -> Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(-hw, -hh, hw, hh, CornerRadius(hh, hh))) }
        name == "Water Heater" || name == PlanSymbols.VESSEL_SINK || name.startsWith("Round Table") -> {
            val r = min(hw, hh)
            oval(androidx.compose.ui.geometry.Rect(-r, -r, r, r))
        }
        name == PlanSymbols.UNDERMOUNT_SINK || name == "Office Chair" -> oval(androidx.compose.ui.geometry.Rect(-hw, -hh, hw, hh))
        name == PlanSymbols.PEDESTAL_SINK || name == PlanSymbols.WALL_HUNG_SINK -> Path().apply {
            moveTo(-hw, -hh); lineTo(hw, -hh); lineTo(hw, -hh + h * 0.3f)
            cubicTo(hw, hh, -hw, hh, -hw, -hh + h * 0.3f); close()
        }
        name == PlanSymbols.CORNER_SINK -> Path().apply {
            moveTo(-hw, -hh); lineTo(hw, -hh)
            cubicTo(hw, hh * 0.2f, hw * 0.2f, hh, -hw, hh); close()
        }
        element.category == PlanSymbols.STAIRS -> Stairs.outline(element, w, h, inch)?.let { pts ->
            Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        }
        element.category == PlanSymbols.SHOWERS && Shower.type(name) == Shower.Type.NEO_ANGLE -> {
            val a = Shower.neoCut(w / inch, h / inch) * inch
            Path().apply { moveTo(-hw, -hh); lineTo(hw, -hh); lineTo(hw, hh - a); lineTo(hw - a, hh); lineTo(-hw, hh); close() }
        }
        else -> null
    }
}
