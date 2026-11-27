package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import kotlin.math.ceil
import kotlin.math.max

/**
 * Door and window schedules read straight off the floor plan, the way the cabinet list is
 * (Dan, Nov 22 2026). Every door and window gets a mark (D1, D2... W1, W2...) that stays with it
 * once given: it is kept in the symbol's properties, so moving or resizing an opening keeps its
 * mark, a new one gets the next number, and a deleted one leaves its number unused (as on a
 * real set of drawings). The marks show on the plan; the schedule is a viewport on a sheet, and
 * changes on the plan show in it at once. Cased openings have no door and are not scheduled.
 */
object Schedules {
    enum class Kind(val prefix: String, val title: String) {
        DOOR("D", "Door Schedule"),
        WINDOW("W", "Window Schedule")
    }

    /** The mark's number, kept in the opening's properties. */
    const val PROP_MARK = "scheduleMark"
    /** The supplier's rough opening for this unit, inches; unset = Dan's rule ([roughOpening]). */
    const val PROP_RO_W = "roughOpeningWIn"
    const val PROP_RO_H = "roughOpeningHIn"

    /** Window height and sill on windows placed before heights were kept on each one (the elevation defaults). */
    private const val OLD_WINDOW_HEIGHT_IN = 48f
    private const val OLD_WINDOW_SILL_IN = 36f

    fun kindOf(e: DrawingElement): Kind? {
        if (e !is DrawingElement.Symbol || e.category != PlanSymbols.DOORS_WINDOWS) return null
        val n = e.symbolName
        return when {
            PlanSymbols.isWindow(n) -> Kind.WINDOW
            PlanSymbols.isDoor(n) || PlanSymbols.isPocketDoor(n) || PlanSymbols.isSlidingDoor(n) -> Kind.DOOR
            else -> null
        }
    }

    fun openings(state: CanvasState, kind: Kind): List<DrawingElement.Symbol> =
        state.elements.filterIsInstance<DrawingElement.Symbol>().filter { kindOf(it) == kind }

    fun markNo(s: DrawingElement.Symbol): Int? = s.properties[PROP_MARK]?.toInt()?.takeIf { it > 0 }

    fun mark(s: DrawingElement.Symbol): String? {
        val k = kindOf(s) ?: return null
        return markNo(s)?.let { "${k.prefix}$it" }
    }

    /** Whether the drawing uses marks: shown on the plan, or a schedule is on a sheet. */
    fun inUse(state: CanvasState): Boolean =
        state.showOpeningMarks || state.sheets.any { sh -> sh.views.any { it.kind == SheetViewKind.DOOR_SCHEDULE || it.kind == SheetViewKind.WINDOW_SCHEDULE } }

    /**
     * Gives every door and window without a mark the next number: top to bottom, then left to
     * right on the plan. A copy that came with another's mark is renumbered (the first keeps it).
     * Returns [state] itself when nothing changes.
     */
    fun assignMarks(state: CanvasState): CanvasState {
        val newMarks = HashMap<String, Int>()
        for (k in Kind.entries) {
            val all = openings(state, k)
            var next = (all.mapNotNull { markNo(it) }.maxOrNull() ?: 0) + 1
            val seen = HashSet<Int>()
            val needs = all.filter { s -> val n = markNo(s); n == null || !seen.add(n) }
            for (s in needs.sortedWith(compareBy({ it.center.y }, { it.center.x }))) newMarks[s.id] = next++
        }
        if (newMarks.isEmpty()) return state
        return state.copy(elements = state.elements.map { e ->
            val n = newMarks[e.id]
            if (n != null && e is DrawingElement.Symbol) e.copy(properties = e.properties + (PROP_MARK to n.toFloat())) else e
        })
    }

    /** One line of a schedule. Sizes are inches. */
    data class Row(
        val mark: String,
        val markNo: Int,
        val widthIn: Float,
        val heightIn: Float,
        val sillIn: Float?,
        val type: String,
        val operation: String,
        val location: String,
        val room: String,
        val header: String,
        val roughOpening: String,
        /** Doors: the jamb width the wall takes (Dan, Nov 24), "—" when it isn't in a stud wall. */
        val jamb: String = "—"
    )

    /** Stud depth of a framed wall, inches, from its type (2x4, 2x6, 2x8); null for block, concrete and the like. */
    fun studDepthIn(wallName: String): Float? = when {
        wallName.startsWith("2x4") -> 3.5f
        wallName.startsWith("2x6") -> 5.5f
        wallName.startsWith("2x8") -> 7.25f
        else -> null
    }

    /**
     * A door's jamb width (Dan, Nov 24): the stud plus 1/2" drywall each side plus 1/16", so 4 9/16" in a 2x4 wall
     * and 6 9/16" in a 2x6 wall. Null when the door isn't in a stud wall.
     */
    fun jambWidthIn(door: DrawingElement.Symbol, state: CanvasState): Float? {
        val wall = OpeningPosition.hostWall(door, state) ?: return null
        return studDepthIn(wall.symbolName)?.let { it + 1f + 1f / 16f }
    }

    /**
     * Header by Dan's rule of thumb: the opening width in feet plus 2 is the depth (8' takes a
     * 2x10, 9' and 10' a 2x12), never less than a 2x10 (it fills to the plate over an 80" head),
     * and an LVL over 10'.
     */
    fun header(widthIn: Float): String {
        val feet = ceil(widthIn / 12f - 0.001f)
        return when {
            feet > 10f -> "LVL (size for span)"
            feet + 2f <= 10f -> "2-ply 2x10"
            else -> "2-ply 2x12"
        }
    }

    /**
     * The rough opening by Dan's rules (Nov 22 2026): doors add 2" to the door size (a 3-0 x 6-8
     * is 38 x 82), exterior doors (sliders included) 2 1/2" on the height; windows 3/4" overall
     * (3/8" each side). A pocket door needs its frame's opening (twice the door plus 1", 4 1/2" over the
     * door): verify with the frame. Width x height, inches.
     */
    fun ruleRoughOpening(s: DrawingElement.Symbol, widthIn: Float, heightIn: Float, exterior: Boolean): Pair<Float, Float> = when {
        PlanSymbols.isWindow(s.symbolName) -> (widthIn + 0.75f) to (heightIn + 0.75f)
        PlanSymbols.isPocketDoor(s.symbolName) -> (2f * widthIn + 1f) to (heightIn + 4.5f)
        exterior || PlanSymbols.isSlidingDoor(s.symbolName) -> (widthIn + 2f) to (heightIn + 2.5f)
        else -> (widthIn + 2f) to (heightIn + 2f)
    }

    /** The rough opening as the schedule shows it: the supplier's where entered on the unit, else the rule. */
    fun roughOpeningText(s: DrawingElement.Symbol, widthIn: Float, heightIn: Float, exterior: Boolean): String {
        val (rw, rh) = ruleRoughOpening(s, widthIn, heightIn, exterior)
        val sw = s.properties[PROP_RO_W]
        val sh = s.properties[PROP_RO_H]
        val text = sizeText(sw ?: rw, sh ?: rh)
        return when {
            sw != null || sh != null -> "$text (supplier)"
            PlanSymbols.isPocketDoor(s.symbolName) -> "$text (verify)"
            else -> text
        }
    }

    /** Feet and inches as plans write them: 3'-0", 6'-8", 2'-8 1/2". */
    fun feetInches(inches: Float): String = Building.elevationText(inches)

    fun sizeText(w: Float, h: Float): String = "${feetInches(w)} x ${feetInches(h)}"

    /**
     * A window or slider in a wall running up the page is stored stood on end (its size swapped,
     * not turned), so which side is along the wall is read from its proportions.
     */
    private fun standsOnEnd(s: DrawingElement.Symbol): Boolean = !PlanSymbols.isDoor(s.symbolName) && s.size.height > s.size.width

    /** The opening's width along its wall, world px: a hinged door's leaf, else the longer side of its footprint. */
    fun widthAlongPx(s: DrawingElement.Symbol): Float = if (standsOnEnd(s)) s.size.height else s.size.width

    /** A point [reachPx] out from the middle of the opening, square to its wall, on side [side] (+1 / -1). */
    private fun outFromWall(s: DrawingElement.Symbol, side: Float, extraPx: Float): Offset {
        val local = if (standsOnEnd(s)) Offset(side * (s.size.width / 2f + extraPx), 0f) else Offset(0f, side * (s.size.height / 2f + extraPx))
        return rotateAbout(s.center + local, s.center, s.rotation)
    }

    /** The room an opening serves: a hinged door's swing is in it; a window or slider is checked on both sides of its wall. */
    private fun roomOf(s: DrawingElement.Symbol, state: CanvasState): String {
        // Rooms are found on the active layer's level: look from the opening's own floor, so the
        // schedule reads the same whichever layer is being worked on (the roof, say).
        val st = state.copy(activeLayerId = Layers.layerOf(s, state).id)
        if (PlanSymbols.isDoor(s.symbolName)) CabinetList.roomAt(s.center, st)?.let { return it.second }
        for (side in listOf(1f, -1f)) {
            CabinetList.roomAt(outFromWall(s, side, st.inchesToPx(12f)), st)?.let { return it.second }
        }
        return "—"
    }

    private fun locationOf(s: DrawingElement.Symbol, state: CanvasState): String {
        val wall = OpeningPosition.hostWall(s, state) ?: return "—"
        return if (wall.symbolName == FloorPlan.EXTERIOR_WALL) "Exterior" else "Interior"
    }

    fun rows(state: CanvasState, kind: Kind): List<Row> {
        val inch = state.inchesToPx(1f)
        return openings(state, kind).map { s ->
            val w = widthAlongPx(s) / inch
            val window = kind == Kind.WINDOW
            val h = s.properties[PlanSymbols.PROP_HEIGHT_IN] ?: if (window) OLD_WINDOW_HEIGHT_IN else state.building.doorHeadIn
            val sill = if (window) s.properties[PlanSymbols.PROP_SILL_IN] ?: OLD_WINDOW_SILL_IN else null
            val (type, operation) = when {
                window -> PlanSymbols.windowTypeLabels[PlanSymbols.windowType(s)] to when (PlanSymbols.windowType(s)) {
                    PlanSymbols.WINDOW_CASEMENT -> if (PlanSymbols.hingeRight(s)) "Hinge right" else "Hinge left"
                    PlanSymbols.WINDOW_AWNING -> "Hinge top"
                    PlanSymbols.WINDOW_SLIDER -> "Slides"
                    PlanSymbols.WINDOW_FIXED -> "Fixed"
                    else -> "Sash up/down"
                }
                PlanSymbols.isPocketDoor(s.symbolName) -> "Pocket" to (if (PlanSymbols.pocketRight(s)) "Pocket right" else "Pocket left")
                // The track length with it (Dan's card: barn door schedule shows the track).
                PlanSymbols.isBiParting(s.symbolName) -> "Barn, bi-parting" to "Slides both ways, ${BarnDoors.trackText(s, state).lowercase()}"
                PlanSymbols.isBarnDoor(s.symbolName) -> "Barn" to "${if (PlanSymbols.hingeRight(s)) "Slides right" else "Slides left"}, ${BarnDoors.trackText(s, state).lowercase()}"
                PlanSymbols.isBifold(s.symbolName) -> (if (PlanSymbols.isBifoldPair(s.symbolName, w)) "Bifold pair" else "Bifold") to
                    (if (PlanSymbols.isBifoldPair(s.symbolName, w)) "Folds both ways" else if (PlanSymbols.hingeRight(s)) "Folds right" else "Folds left")
                PlanSymbols.isDoubleDoor(s.symbolName) -> "Double (pair)" to "Both leaves"
                PlanSymbols.isSlidingDoor(s.symbolName) -> "Sliding" to "Sliding"
                else -> "Hinged" to (if (PlanSymbols.hingeRight(s)) "Hinge right" else "Hinge left")
            }
            val location = locationOf(s, state)
            Row(
                mark = mark(s) ?: "—", markNo = markNo(s) ?: Int.MAX_VALUE,
                widthIn = w, heightIn = h, sillIn = sill, type = type, operation = operation,
                location = location, room = roomOf(s, state),
                header = header(w), roughOpening = roughOpeningText(s, w, h, exterior = location == "Exterior"),
                jamb = if (window) "—" else jambWidthIn(s, state)?.let { formatInches(it) + "\"" } ?: "—"
            )
        }.sortedWith(compareBy({ it.markNo }, { it.room }))
    }

    // --- The table on a sheet ---

    private class Column(val title: String, val widthIn: Float, val value: (Row) -> String)

    private fun columns(kind: Kind): List<Column> = when (kind) {
        Kind.DOOR -> listOf(
            Column("MARK", 0.6f) { it.mark },
            Column("SIZE (W x H)", 1.3f) { sizeText(it.widthIn, it.heightIn) },
            Column("TYPE", 0.8f) { it.type },
            Column("OPERATION", 1.0f) { it.operation },
            Column("LOCATION", 0.85f) { it.location },
            Column("ROOM", 1.4f) { it.room },
            Column("HEADER", 1.3f) { it.header },
            Column("ROUGH OPENING", 1.7f) { it.roughOpening },
            Column("JAMB", 0.75f) { it.jamb }
        )
        Kind.WINDOW -> listOf(
            Column("MARK", 0.6f) { it.mark },
            Column("SIZE (W x H)", 1.3f) { sizeText(it.widthIn, it.heightIn) },
            Column("TYPE", 1.1f) { it.type },
            Column("OPERATION", 1.0f) { it.operation },
            Column("SILL", 0.75f) { it.sillIn?.let(::feetInches) ?: "—" },
            Column("HEAD", 0.75f) { feetInches((it.sillIn ?: 0f) + it.heightIn) },
            Column("LOCATION", 0.85f) { it.location },
            Column("ROOM", 1.4f) { it.room },
            Column("HEADER", 1.3f) { it.header },
            Column("ROUGH OPENING", 1.7f) { it.roughOpening }
        )
    }

    private const val HEADER_ROW_IN = 0.32f
    private const val ROW_IN = 0.25f

    /**
     * Normal size: the column widths and rows above are laid out for 0.1" type; this brings the
     * letters to about 1/8" tall on paper, the usual size for schedule text (Dan, Nov 22: the
     * first version was too small to read on a 24x36). A viewport's own size multiplies it.
     */
    private const val NORMAL = 1.6f

    /** The size choices for a schedule viewport (SheetView.tableSize). */
    val sizeChoices = listOf("Small" to 0.75f, "Normal" to 1f, "Large" to 1.3f)

    /** The table's size on paper, inches (at least one row, for "none yet"), at [size] (1 = normal). */
    fun tableSizeIn(kind: Kind, state: CanvasState, size: Float = 1f): Size {
        val k = NORMAL * size
        val w = columns(kind).sumOf { it.widthIn.toDouble() }.toFloat()
        val n = max(1, rows(state, kind).size)
        return Size(w * k, (HEADER_ROW_IN + n * ROW_IN) * k)
    }

    /** Draws the schedule with its top-left at [topLeftPx], [pageDpi] page px per paper inch, at [scale] (1 = normal). */
    fun DrawScope.drawTable(kind: Kind, state: CanvasState, topLeftPx: Offset, pageDpi: Float, scale: Float = 1f) {
        // Everything below is laid out at 0.1" type: drawn with the inch stretched, the whole table grows evenly.
        val dpi = pageDpi * NORMAL * scale
        val cols = columns(kind)
        val rows = rows(state, kind)
        val size = tableSizeIn(kind, state, 1f / NORMAL)
        val lineW = max(1f, dpi / 100f)
        val black = Color.Black
        val left = topLeftPx.x
        val top = topLeftPx.y
        val right = left + size.width * dpi
        val bottom = top + size.height * dpi
        drawRect(black, topLeft = topLeftPx, size = Size(size.width * dpi, size.height * dpi), style = Stroke(lineW * 1.5f))
        val text = 0.1f * dpi
        val pad = 0.06f * dpi
        // Header row.
        var x = left
        for (c in cols) {
            drawPageText(c.title, Offset(x + pad, top + (HEADER_ROW_IN * dpi - text * 1.2f) / 2f), text * 0.95f, black, bold = true, maxWidth = c.widthIn * dpi - 2f * pad)
            x += c.widthIn * dpi
        }
        val headBottom = top + HEADER_ROW_IN * dpi
        drawLine(black, Offset(left, headBottom), Offset(right, headBottom), strokeWidth = lineW * 1.5f)
        // Rows.
        var y = headBottom
        if (rows.isEmpty()) {
            drawPageText("None in the plan yet", Offset(left + pad, y + (ROW_IN * dpi - text * 1.2f) / 2f), text, black, bold = false, maxWidth = size.width * dpi)
        }
        for (r in rows) {
            x = left
            for (c in cols) {
                drawPageText(c.value(r), Offset(x + pad, y + (ROW_IN * dpi - text * 1.2f) / 2f), text, black, bold = c.title == "MARK", maxWidth = c.widthIn * dpi - 2f * pad)
                x += c.widthIn * dpi
            }
            y += ROW_IN * dpi
            if (y < bottom - 1f) drawLine(black, Offset(left, y), Offset(right, y), strokeWidth = lineW * 0.6f)
        }
        // Column lines.
        x = left
        for (c in cols.dropLast(1)) {
            x += c.widthIn * dpi
            drawLine(black, Offset(x, top), Offset(x, bottom), strokeWidth = lineW * 0.6f)
        }
    }

    // --- The marks on the plan ---

    /**
     * Each door's and window's mark in a small circle (doors) or square (windows), upright
     * whatever the opening's rotation: a hinged door's in its swing, a window's or slider's just
     * off its wall. Sized in model inches, like the cabinet codes, so it prints to scale.
     */
    fun DrawScope.drawMarks(state: CanvasState, shown: List<DrawingElement>) {
        val inch = state.inchesToPx(1f)
        val ink = if (state.backgroundColor.luminance() > 0.5f) Color.Black else Color.White
        val r = inch * 7f
        val text = inch * 6f
        for (e in shown) {
            val s = e as? DrawingElement.Symbol ?: continue
            val kind = kindOf(s) ?: continue
            val m = mark(s) ?: continue
            val at = if (PlanSymbols.isDoor(s.symbolName)) s.center else outFromWall(s, 1f, inch * 10f)
            val stroke = Stroke(max(inch * 0.6f, 1f))
            if (kind == Kind.DOOR) {
                drawCircle(state.backgroundColor, radius = r, center = at)
                drawCircle(ink, radius = r, center = at, style = stroke)
            } else {
                drawRect(state.backgroundColor, topLeft = at - Offset(r, r), size = Size(2 * r, 2 * r))
                drawRect(ink, topLeft = at - Offset(r, r), size = Size(2 * r, 2 * r), style = stroke)
            }
            val w = text * 0.62f * m.length
            drawPageText(m, Offset(at.x - w / 2f, at.y - text * 0.62f), text, ink, bold = true, maxWidth = w + text)
        }
    }
}
