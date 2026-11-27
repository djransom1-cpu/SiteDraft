package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * The schedules broken up by trade (Dan, Nov 26: "this helps break it up by trade"): finishes (FinishSchedule),
 * trim, cabinets and countertops, appliances, plumbing fixtures. One table look for all of them: a title, a header
 * row, rows of cells, and full-width lines (a "Countertop: ..." under a room) that print only when filled.
 */
object ScheduleTable {
    /** A row of cells, or (with [line]) one line of text across every column but the first. */
    class Row(val cells: List<String> = emptyList(), val line: String? = null, val bold: Boolean = false)

    const val TITLE_IN = 0.36f
    const val HEAD_IN = 0.3f
    const val ROW_IN = 0.3f

    fun sizeIn(cols: List<Pair<String, Float>>, rowCount: Int, size: Float): Size =
        Size(cols.sumOf { it.second.toDouble() }.toFloat() * size, (TITLE_IN + HEAD_IN + max(1, rowCount) * ROW_IN) * size)

    fun DrawScope.drawTable(title: String, cols: List<Pair<String, Float>>, rows: List<Row>, empty: String, topLeft: Offset, dpi: Float, size: Float) {
        val k = dpi * size
        val black = Color.Black
        val line = max(1f, dpi / 120f)
        val sz = sizeIn(cols, rows.size, size)
        val w = sz.width * dpi
        val h = sz.height * dpi
        drawRect(black, topLeft, Size(w, h), style = Stroke(line * 1.5f))
        val text = 0.1f * k
        fun cell(t: String, x: Float, y: Float, rowH: Float, colW: Float, bold: Boolean) =
            drawPageText(t, Offset(x + 0.06f * k, y + (rowH - text * 1.2f) / 2f), text, black, bold = bold, maxWidth = colW - 0.1f * k)
        cell(title, topLeft.x, topLeft.y, TITLE_IN * k, w, true)
        var y = topLeft.y + TITLE_IN * k
        drawLine(black, Offset(topLeft.x, y), Offset(topLeft.x + w, y), strokeWidth = line * 1.5f)
        var x = topLeft.x
        for ((name, cw) in cols) { cell(name, x, y, HEAD_IN * k, cw * k, true); x += cw * k }
        y += HEAD_IN * k
        drawLine(black, Offset(topLeft.x, y), Offset(topLeft.x + w, y), strokeWidth = line * 1.5f)
        val firstW = cols.first().second * k
        // Column lines through the header; below it, row by row (a full-width line has only the first one).
        x = topLeft.x
        for ((_, cw) in cols.dropLast(1)) { x += cw * k; drawLine(black, Offset(x, topLeft.y + TITLE_IN * k), Offset(x, y), strokeWidth = line) }
        if (rows.isEmpty()) cell(empty, topLeft.x, y, ROW_IN * k, w, false)
        for ((ri, r) in rows.withIndex()) {
            val rowH = ROW_IN * k
            if (r.line != null) {
                cell(r.cells.firstOrNull().orEmpty(), topLeft.x, y, rowH, firstW, true)
                cell(r.line, topLeft.x + firstW, y, rowH, w - firstW, r.bold)
                drawLine(black, Offset(topLeft.x + firstW, y), Offset(topLeft.x + firstW, y + rowH), strokeWidth = line)
            } else {
                x = topLeft.x
                cols.forEachIndexed { i, (_, cw) -> cell(r.cells.getOrElse(i) { "" }, x, y, rowH, cw * k, i == 0 || r.bold); x += cw * k }
                x = topLeft.x
                for ((_, cw) in cols.dropLast(1)) { x += cw * k; drawLine(black, Offset(x, y), Offset(x, y + rowH), strokeWidth = line) }
            }
            y += rowH
            // A full-width line belongs to the room above it: no rule between them.
            val nextIsLine = rows.getOrNull(ri + 1)?.line != null
            if (y < topLeft.y + h - 1f) drawLine(black, Offset(if (nextIsLine) topLeft.x + firstW else topLeft.x, y), Offset(topLeft.x + w, y), strokeWidth = line)
        }
    }

    /** The room a symbol is in, by the room labels (as the cabinet list and door schedule name rooms); "—" outside a room. */
    fun roomOf(s: DrawingElement.Symbol, state: CanvasState): String =
        CabinetList.roomAt(s.center, state.copy(activeLayerId = Layers.layerOf(s, state).id))?.second ?: "—"
}

// ---------------------------------------------------------------- Trim

/**
 * The trim schedule (Dan, Nov 26: "Trim on another schedule, then carpenters and Painters know that as well"): by the
 * finish schedule's rooms, base, door casing, windows, crown, wainscot / chair rail, paint or stain, notes. Base,
 * casing and windows fill in from the trim set on the room's elevations, wainscot from the walls; typing overrides.
 */
object TrimSchedule {
    val fields = listOf("trimBase", "trimCasing", "trimWindows", "trimCrown", "trimWainscot", "trim", "trimNotes")
    val labels = listOf("Base", "Door casing", "Windows", "Crown", "Wainscot / chair rail", "Paint or stain", "Trim notes")

    private val COLS = listOf("ROOM" to 1.7f, "BASE" to 1.9f, "DOOR CASING" to 2.8f, "WINDOWS" to 3.0f, "CROWN" to 1.9f,
        "WAINSCOT / CHAIR RAIL" to 2.8f, "PAINT / STAIN" to 2.8f, "NOTES" to 2.6f)

    private fun elevationsOf(f: RoomFinish, state: CanvasState): List<ElevationSpec> {
        val faces = RoomElevations.faces(FinishSchedule.point(f), state)
        return state.elements.filterIsInstance<DrawingElement.ElevationView>().map { it.spec }
            .filter { sp -> faces.any { it.wall.id == sp.wallId && it.side == sp.side } }
    }

    private fun joined(values: List<String>) = values.filter { it.isNotBlank() }.distinct().joinToString(" / ")

    fun baseFromDrawing(f: RoomFinish, state: CanvasState) =
        joined(elevationsOf(f, state).filter { it.baseIn > 0f }.map { "${formatInches(it.baseIn)}\" base" })

    fun casingFromDrawing(f: RoomFinish, state: CanvasState) = joined(elevationsOf(f, state).filter { it.trimOn }.map {
        "${formatInches(it.casingIn)}\" " + Trim.headLabels.getOrElse(it.headStyle) { "" }.lowercase() +
            (if (it.headStyle == Trim.HEAD_CORNICE) " (${formatInches(it.friezeIn)}\" frieze)" else "")
    })

    fun windowsFromDrawing(f: RoomFinish, state: CanvasState) =
        joined(elevationsOf(f, state).filter { it.trimOn }.map { "${formatInches(it.casingIn)}\" casing, " + Trim.windowLabels.getOrElse(it.windowTrim) { "" }.lowercase() })

    fun wainscotFromDrawing(f: RoomFinish, state: CanvasState): String = joined(RoomElevations.faces(FinishSchedule.point(f), state).mapNotNull { face ->
        Wainscot.specOf(face.wall, face.side)?.let { "${Wainscot.styleLabels.getOrElse(it.style) { "Wainscot" }} ${formatInches(it.heightIn)}\"" }
    })

    /** What the drawing fills in for a field ("" for the typed-only ones). */
    fun fromDrawing(field: String, f: RoomFinish, state: CanvasState): String = when (field) {
        "trimBase" -> baseFromDrawing(f, state)
        "trimCasing" -> casingFromDrawing(f, state)
        "trimWindows" -> windowsFromDrawing(f, state)
        "trimWainscot" -> wainscotFromDrawing(f, state)
        else -> ""
    }

    fun rows(state: CanvasState): List<List<String>> = state.roomFinishes.mapIndexed { i, f ->
        listOf(f.name.ifBlank { "Room ${i + 1}" },
            f.trimBase.ifBlank { baseFromDrawing(f, state) }, f.trimCasing.ifBlank { casingFromDrawing(f, state) },
            f.trimWindows.ifBlank { windowsFromDrawing(f, state) }, f.trimCrown,
            f.trimWainscot.ifBlank { wainscotFromDrawing(f, state) }, f.trim, f.trimNotes)
    }

    fun tableSizeIn(state: CanvasState, size: Float = 1f) = ScheduleTable.sizeIn(COLS, state.roomFinishes.size, size)

    fun DrawScope.drawTrimTable(state: CanvasState, topLeft: Offset, dpi: Float, size: Float = 1f) = with(ScheduleTable) {
        drawTable("TRIM SCHEDULE", COLS, rows(state).map { ScheduleTable.Row(it) },
            "Add rooms: Floor plan > Finish schedule, then tap inside each room", topLeft, dpi, size)
    }
}

// ---------------------------------------------------------------- Cabinets and countertops

/** What's typed for one room's cabinets and countertops, keyed by the room's name as the cabinet list names it. */
@Serializable
data class CabinetSpec(
    val room: String,
    val cabinets: String = "",
    val hardware: String = "",
    val countertop: String = "",
    val notes: String = "",
)

/**
 * The cabinet and countertop schedule (Dan, Nov 26): room by room, the cabinets counted off the plan by shop code
 * (CabinetList), then the room's typed lines, printed when filled: cabinets (line, door style, finish), hardware,
 * countertop (material, edge, thickness; vanity tops too), notes.
 */
object CabinetSchedule {
    val fields = listOf("cabinets", "hardware", "countertop", "notes")
    val labels = listOf("Cabinets (line, door style, finish)", "Hardware", "Countertop (material, edge, thickness)", "Notes")

    private val COLS = listOf("ROOM" to 1.7f, "QTY" to 0.6f, "CODE" to 1.3f, "DESCRIPTION" to 7.4f)

    fun spec(state: CanvasState, room: String) = state.cabinetSpecs.firstOrNull { it.room == room } ?: CabinetSpec(room)

    fun withField(s: CabinetSpec, field: String, value: String): CabinetSpec? = when (field) {
        "cabinets" -> s.copy(cabinets = value.trim())
        "hardware" -> s.copy(hardware = value.trim())
        "countertop" -> s.copy(countertop = value.trim())
        "notes" -> s.copy(notes = value.trim())
        else -> null
    }

    /** The rooms with cabinets, in the cabinet list's order. */
    fun rooms(state: CanvasState): List<String> = CabinetList.rows(state).map { it.room }.distinct()

    fun rows(state: CanvasState): List<ScheduleTable.Row> {
        val out = mutableListOf<ScheduleTable.Row>()
        for ((room, list) in CabinetList.rows(state).groupBy { it.room }) {
            list.forEachIndexed { i, r -> out += ScheduleTable.Row(listOf(if (i == 0) room else "", r.qty.toString(), r.code, r.description)) }
            val sp = spec(state, room)
            listOf("CABINETS" to sp.cabinets, "HARDWARE" to sp.hardware, "COUNTERTOP" to sp.countertop, "NOTES" to sp.notes)
                .filter { it.second.isNotBlank() }
                .forEach { (k, v) -> out += ScheduleTable.Row(line = "$k: $v") }
        }
        return out
    }

    fun tableSizeIn(state: CanvasState, size: Float = 1f) = ScheduleTable.sizeIn(COLS, rows(state).size, size)

    fun DrawScope.drawCabinetTable(state: CanvasState, topLeft: Offset, dpi: Float, size: Float = 1f) = with(ScheduleTable) {
        drawTable("CABINET AND COUNTERTOP SCHEDULE", COLS, rows(state), "No cabinets on the plan yet", topLeft, dpi, size)
    }
}

// ---------------------------------------------------------------- Appliances and plumbing fixtures

/** What's typed for one appliance or plumbing fixture in the plan: make, model, finish, notes. */
@Serializable
data class ItemSpec(
    val elementId: String,
    val make: String = "",
    val model: String = "",
    val finish: String = "",
    val notes: String = "",
)

/**
 * The appliance schedule and the plumbing fixture schedule (Dan, Nov 26), read off the plan like the door and window
 * schedules: every appliance or fixture drawn, marked A1, A2... / P1, P2..., with its room and size; make, model,
 * finish and notes typed per item (Properties, with the symbol selected).
 */
object ItemSchedules {
    enum class Kind(val title: String, val prefix: String, val empty: String) {
        APPLIANCE("APPLIANCE SCHEDULE", "A", "No appliances on the plan yet"),
        PLUMBING("PLUMBING FIXTURE SCHEDULE", "P", "No plumbing fixtures on the plan yet")
    }

    /** Appliances that sit in the Fixtures list (the laundry pair, the older fridge, range and dishwasher). */
    private val applianceNames = listOf("Washer", "Dryer", "Refrigerator", "Range", "Dishwasher")
    val fields = listOf("make", "model", "finish", "notes")
    val labels = listOf("Make", "Model", "Finish", "Notes")

    fun kindOf(s: DrawingElement.Symbol): Kind? = when {
        s.category == PlanSymbols.APPLIANCES || s.category == PlanSymbols.HOODS -> Kind.APPLIANCE
        s.category == PlanSymbols.FIXTURES && applianceNames.any { s.symbolName.startsWith(it) } -> Kind.APPLIANCE
        s.category == PlanSymbols.SHOWERS -> Kind.PLUMBING
        s.category == PlanSymbols.FIXTURES -> Kind.PLUMBING
        // A vanity brings its lavatory and faucet.
        s.category == PlanSymbols.CABINETS && PlanSymbols.isVanity(s.symbolName) && !PlanSymbols.isVanityDrawers(s.symbolName) -> Kind.PLUMBING
        else -> null
    }

    private fun isShower(s: DrawingElement.Symbol) = s.category == PlanSymbols.SHOWERS || s.symbolName.startsWith("Shower")

    /** The items of a kind on the plan, one per spot (a shower drawn twice over itself counts once), north to south, west to east. */
    fun items(state: CanvasState, kind: Kind): List<DrawingElement.Symbol> {
        val inch = state.inchesToPx(1f)
        val all = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { !DerivedLayers.isGenerated(it) && kindOf(it) == kind }
        val kept = mutableListOf<DrawingElement.Symbol>()
        // Keep the shower with the shower settings (Showers) over a plain stall at the same spot.
        for (s in all.sortedByDescending { if (it.category == PlanSymbols.SHOWERS) 1 else 0 }) {
            if (isShower(s) && kept.any { isShower(it) && (it.center - s.center).getDistance() < 12f * inch }) continue
            kept += s
        }
        return kept.sortedWith(compareBy({ (it.center.y / (24f * inch)).toInt() }, { it.center.x }))
    }

    fun spec(state: CanvasState, id: String): ItemSpec = state.itemSpecs.firstOrNull { it.elementId == id } ?: ItemSpec(id)

    fun withField(spec: ItemSpec, field: String, value: String): ItemSpec? = when (field) {
        "make" -> spec.copy(make = value.trim())
        "model" -> spec.copy(model = value.trim())
        "finish" -> spec.copy(finish = value.trim())
        "notes" -> spec.copy(notes = value.trim())
        else -> null
    }

    private fun itemName(s: DrawingElement.Symbol): String =
        if (s.category == PlanSymbols.CABINETS) (if (s.symbolName.contains("Double")) "Lavatories (2) and faucets" else "Lavatory and faucet") + " in ${s.symbolName}"
        else s.symbolName

    private fun sizeOf(s: DrawingElement.Symbol, state: CanvasState): String {
        val inch = state.inchesToPx(1f)
        return "${formatInches(s.size.width / inch)}\" x ${formatInches(s.size.height / inch)}\""
    }

    private val COLS = listOf("MARK" to 0.7f, "ROOM" to 1.7f, "ITEM" to 3.8f, "SIZE" to 1.6f, "MAKE / MODEL" to 3.4f, "FINISH" to 2.0f, "NOTES" to 3.0f)

    /** MARK, ROOM, ITEM, SIZE, MAKE / MODEL, FINISH, NOTES. */
    fun rows(state: CanvasState, kind: Kind): List<List<String>> = items(state, kind).mapIndexed { i, s ->
        val sp = spec(state, s.id)
        listOf("${kind.prefix}${i + 1}", ScheduleTable.roomOf(s, state), itemName(s), sizeOf(s, state),
            listOf(sp.make, sp.model).filter { it.isNotBlank() }.joinToString(" "), sp.finish, sp.notes)
    }

    /** An item's mark ("A2", "P1"), for the Properties panel; null when it isn't on a schedule. */
    fun markOf(s: DrawingElement.Symbol, state: CanvasState): String? {
        val kind = kindOf(s) ?: return null
        val i = items(state, kind).indexOfFirst { it.id == s.id }
        return if (i < 0) null else "${kind.prefix}${i + 1}"
    }

    fun tableSizeIn(kind: Kind, state: CanvasState, size: Float = 1f) = ScheduleTable.sizeIn(COLS, items(state, kind).size, size)

    fun DrawScope.drawItemTable(kind: Kind, state: CanvasState, topLeft: Offset, dpi: Float, size: Float = 1f) = with(ScheduleTable) {
        drawTable(kind.title, COLS, rows(state, kind).map { ScheduleTable.Row(it) }, kind.empty, topLeft, dpi, size)
    }
}

/** The trade schedules as sheet views: their size on paper and their drawing (null / false for other views). */
object TradeSchedules {
    val kinds = listOf(SheetViewKind.TRIM_SCHEDULE, SheetViewKind.CABINET_SCHEDULE, SheetViewKind.APPLIANCE_SCHEDULE, SheetViewKind.PLUMBING_SCHEDULE)

    fun isTrade(kind: SheetViewKind) = kind in kinds

    fun sizeIn(kind: SheetViewKind, state: CanvasState, size: Float): Size? = when (kind) {
        SheetViewKind.TRIM_SCHEDULE -> TrimSchedule.tableSizeIn(state, size)
        SheetViewKind.CABINET_SCHEDULE -> CabinetSchedule.tableSizeIn(state, size)
        SheetViewKind.APPLIANCE_SCHEDULE -> ItemSchedules.tableSizeIn(ItemSchedules.Kind.APPLIANCE, state, size)
        SheetViewKind.PLUMBING_SCHEDULE -> ItemSchedules.tableSizeIn(ItemSchedules.Kind.PLUMBING, state, size)
        else -> null
    }

    fun DrawScope.drawTrade(kind: SheetViewKind, state: CanvasState, topLeft: Offset, dpi: Float, size: Float) {
        when (kind) {
            SheetViewKind.TRIM_SCHEDULE -> with(TrimSchedule) { drawTrimTable(state, topLeft, dpi, size) }
            SheetViewKind.CABINET_SCHEDULE -> with(CabinetSchedule) { drawCabinetTable(state, topLeft, dpi, size) }
            SheetViewKind.APPLIANCE_SCHEDULE -> with(ItemSchedules) { drawItemTable(ItemSchedules.Kind.APPLIANCE, state, topLeft, dpi, size) }
            SheetViewKind.PLUMBING_SCHEDULE -> with(ItemSchedules) { drawItemTable(ItemSchedules.Kind.PLUMBING, state, topLeft, dpi, size) }
            else -> {}
        }
    }
}
