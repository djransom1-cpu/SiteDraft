package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.max

/**
 * How views are laid out on a sheet (decided with Dan, Nov 28):
 *  - a fixed order: schedules door, window, finish, paint, trim, cabinets, appliances, plumbing; details in building
 *    order (footings and foundation, floor framing, walls, stairs, roof, the rest); the order is editable by moving
 *    views, Arrange only puts them back in this order
 *  - schedules, plans and elevations in packed rows: left to right, tops lined up, each its own size
 *  - details in a numbered grid below them (4 across by 3 down on a 24x36; a detail too wide for a block takes 2)
 *  - what doesn't fit goes on a new sheet titled "(cont.)", never over another view
 */
object SheetLayoutRules {
    const val GAP_IN = 0.5f

    /** Schedules in Dan's trade order. */
    private val tableOrder = listOf(
        SheetViewKind.COVER_TITLE, SheetViewKind.SHEET_INDEX, SheetViewKind.PROJECT_DATA, SheetViewKind.LEGEND, SheetViewKind.GENERAL_NOTES,
        SheetViewKind.DOOR_SCHEDULE, SheetViewKind.WINDOW_SCHEDULE, SheetViewKind.FINISH_SCHEDULE, SheetViewKind.PAINT_SCHEDULE,
        SheetViewKind.TRIM_SCHEDULE, SheetViewKind.CABINET_SCHEDULE, SheetViewKind.APPLIANCE_SCHEDULE, SheetViewKind.PLUMBING_SCHEDULE,
        SheetViewKind.STRUCTURAL_CALCS
    )

    /** A detail's place in building order: footings first, the roof last. */
    fun buildingOrder(kind: DetailKit.Detail): Int = when (kind) {
        DetailKit.Detail.FOOTING, DetailKit.Detail.GARAGE_FROST, DetailKit.Detail.PORCH_POST -> 0
        DetailKit.Detail.BEAM_POCKET, DetailKit.Detail.COLUMN -> 1
        DetailKit.Detail.HOUSE_WALL, DetailKit.Detail.GARAGE_WALL -> 2
        DetailKit.Detail.STAIR -> 3
        DetailKit.Detail.OVERHANG -> 4
        DetailKit.Detail.BUILDING_SECTION -> 5
    }

    /** The detail a view shows, if it is one (the typical wall section counts as a wall detail). */
    fun detailRank(view: SheetView, state: CanvasState): Int? {
        if (view.kind == SheetViewKind.SECTION) return 2 * 100
        if (view.kind != SheetViewKind.ELEVATION) return null
        val e = state.elements.find { it.id == view.elementId } as? DrawingElement.Symbol ?: return null
        if (!DetailKit.isDetail(e)) return null
        val k = DetailKit.kindOf(e)
        return buildingOrder(k) * 100 + k.ordinal
    }

    /** Where a non-detail view sorts: tables in trade order, then plans, then elevations as they were. */
    private fun rank(view: SheetView): Int = when {
        view.kind in tableOrder -> tableOrder.indexOf(view.kind)
        view.kind == SheetViewKind.PLAN -> 100
        else -> 200
    }

    /** The result of laying a sheet out: the sheet with what fit, and the views that need another sheet. */
    data class Laid(val sheet: SheetLayout, val overflow: List<SheetView>)

    fun laidOut(sheet: SheetLayout, state: CanvasState): Laid {
        val area = Sheets.drawingArea(sheet)
        val details = sheet.views.mapNotNull { v -> detailRank(v, state)?.let { v to it } }.sortedBy { it.second }.map { it.first }
        val others = sheet.views.filter { v -> detailRank(v, state) == null }.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
        val placed = mutableListOf<SheetView>()
        val overflow = mutableListOf<SheetView>()

        // Rows.
        var x = area.left
        var y = area.top
        var rowH = 0f
        for (v in others) {
            val f = Sheets.footprint(v.copy(xIn = 0f, yIn = 0f), state)
            if (f == null) { placed += v; continue }
            val w = f.width
            val h = f.height
            if (x > area.left + 0.01f && x + w > area.right + 0.01f) { x = area.left; y += rowH + GAP_IN; rowH = 0f }
            if (y + h > area.bottom + 0.01f || w > area.width + 0.01f) { overflow += v; continue }
            placed += v.copy(xIn = Sheets.snap(x + w / 2f), yIn = Sheets.snap(y + (h - Sheets.TITLE_ROOM_IN) / 2f))
            x += w + GAP_IN
            rowH = max(rowH, h)
        }
        val rowsBottom = if (rowH > 0f) y + rowH + GAP_IN else area.top

        // The detail grid under the rows: 4 x 3 blocks over the whole drawing area, starting at the first free row of blocks.
        if (details.isNotEmpty()) {
            val cols = if (area.width < 18f) 2 else 4
            val rows = if (area.height < 14f) 2 else 3
            val cw = area.width / cols
            val ch = area.height / rows
            val startRow = ceil((rowsBottom - area.top - 0.01f) / ch).toInt().coerceAtLeast(0)
            val taken = Array(rows) { BooleanArray(cols) }
            for (rr in 0 until minOf(startRow, rows)) taken[rr].fill(true)
            for (v in details) {
                val f = Sheets.footprint(v.copy(xIn = 0f, yIn = 0f), state)
                if (f == null) { placed += v; continue }
                // A detail too big for one block takes two across, or two down.
                val spanC = if (f.width > cw + 0.01f) 2 else 1
                val spanR = if (f.height > ch + 0.01f) 2 else 1
                if (f.width > cw * spanC + 0.01f || f.height > ch * spanR + 0.01f) { overflow += v; continue }
                var spot: Pair<Int, Int>? = null
                search@ for (rr in 0..rows - spanR) for (cc in 0..cols - spanC) {
                    var free = true
                    for (a in rr until rr + spanR) for (b in cc until cc + spanC) if (taken[a][b]) free = false
                    if (free) { spot = rr to cc; break@search }
                }
                if (spot == null) { overflow += v; continue }
                val (rr, cc) = spot
                for (a in rr until rr + spanR) for (b in cc until cc + spanC) taken[a][b] = true
                val cell = Rect(area.left + cc * cw, area.top + rr * ch, area.left + (cc + spanC) * cw, area.top + (rr + spanR) * ch)
                placed += v.copy(xIn = Sheets.snap(cell.center.x), yIn = Sheets.snap(cell.top + GAP_IN / 2f + (f.height - Sheets.TITLE_ROOM_IN) / 2f))
            }
        }
        return Laid(sheet.copy(views = placed), overflow)
    }

    /** The continuation sheet for [overflow] after [sheet]: same series and paper, titled "(cont.)". */
    fun continued(sheet: SheetLayout, overflow: List<SheetView>, sheets: List<SheetLayout>, state: CanvasState): List<SheetLayout> {
        if (overflow.isEmpty()) return emptyList()
        val title = if (sheet.title.endsWith("(cont.)")) sheet.title else sheet.title + " (cont.)"
        val series = sheet.number.takeWhile { it.isLetter() }.ifBlank { "A" }
        val next = SheetLayout(number = Sheets.nextNumber(series, sheets), title = title, paper = sheet.paper, orientation = sheet.orientation,
            drawnBy = sheet.drawnBy, views = overflow)
        var laid = laidOut(next, state)
        // A view too big for any sheet still goes on one (centred, to be cropped or rescaled), so this always ends.
        if (laid.sheet.views.isEmpty()) {
            val first = overflow.first()
            laid = Laid(next.copy(views = listOf(Sheets.placed(next.copy(views = emptyList()), first, state))), overflow.drop(1))
        }
        return listOf(laid.sheet) + continued(laid.sheet, laid.overflow, sheets + laid.sheet, state)
    }

    /** Arrange (Nov 28): [sheet] laid out by the rules, with any "(cont.)" sheets it needs right after it in the set. */
    fun arrangedSet(sheet: SheetLayout, sheets: List<SheetLayout>, state: CanvasState): List<SheetLayout> {
        val laid = laidOut(sheet, state)
        val extra = continued(laid.sheet, laid.overflow, sheets, state)
        val i = sheets.indexOfFirst { it.id == sheet.id }
        val out = sheets.toMutableList()
        if (i < 0) return out + laid.sheet + extra
        out[i] = laid.sheet
        out.addAll(i + 1, extra)
        return out
    }

    /**
     * A view added to [sheet] (Nov 28): in the first free spot as before; with no room, it starts a "(cont.)" sheet
     * after it instead of landing on top of the others.
     */
    fun added(sheet: SheetLayout, view: SheetView, sheets: List<SheetLayout>, state: CanvasState): List<SheetLayout> {
        val spot = Sheets.placedOrNull(sheet, view, state)
        if (spot != null) return sheets.map { if (it.id == sheet.id) it.copy(views = it.views + spot) else it }
        val extra = continued(sheet, listOf(view), sheets, state)
        val i = sheets.indexOfFirst { it.id == sheet.id }
        val out = sheets.toMutableList()
        out.addAll(if (i < 0) out.size else i + 1, extra)
        return out
    }

    /** The two schedule sheets every job starts with (Dan, Nov 28): grouped by trade, editable afterwards. */
    val scheduleSheets: List<Pair<String, List<Pair<SheetViewKind, String>>>> = listOf(
        "Door, window and finish schedules" to listOf(
            SheetViewKind.DOOR_SCHEDULE to "Door schedule", SheetViewKind.WINDOW_SCHEDULE to "Window schedule",
            SheetViewKind.FINISH_SCHEDULE to "Finish schedule", SheetViewKind.PAINT_SCHEDULE to "Paint schedule",
            SheetViewKind.TRIM_SCHEDULE to "Trim schedule"),
        "Cabinet, appliance and fixture schedules" to listOf(
            SheetViewKind.CABINET_SCHEDULE to "Cabinet & countertop schedule", SheetViewKind.APPLIANCE_SCHEDULE to "Appliance schedule",
            SheetViewKind.PLUMBING_SCHEDULE to "Plumbing fixture schedule")
    )

    /** The schedule sheets added to the set: those not already there, each laid out, overflowing to "(cont.)". */
    fun withScheduleSheets(sheets: List<SheetLayout>, state: CanvasState, only: List<String> = scheduleSheets.map { it.first }): List<SheetLayout> {
        var out = sheets
        for ((title, kinds) in scheduleSheets) {
            if (title !in only || out.any { it.title == title }) continue
            val views = kinds.filter { (k, _) -> out.none { s -> s.views.any { it.kind == k } } }.map { (k, label) -> SheetView(kind = k, title = label) }
            if (views.isEmpty()) continue
            val blank = SheetLayout(number = Sheets.nextNumber("A", out), title = title, views = views)
            out = arrangedSet(blank, out + blank, state)
        }
        return out
    }
}
