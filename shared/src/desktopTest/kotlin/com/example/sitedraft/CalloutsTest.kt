package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.Callouts
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DetailKit
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import org.junit.Assert.assertEquals
import org.junit.Test

/** Section and detail callouts numbered from the sheet set (Nov 23 2026). */
class CalloutsTest {
    private val base = CanvasState()
    private val footing = DetailKit.newSymbol(base, DetailKit.Detail.FOOTING, Offset(5000f, 0f))
    private val pocket = DetailKit.newSymbol(base, DetailKit.Detail.BEAM_POCKET, Offset(9000f, 0f))
    private val s4 = SheetLayout(number = "S-4", title = "Wall Section", views = listOf(
        SheetView(kind = SheetViewKind.SECTION, title = "Typical Wall Section"),
        SheetView(kind = SheetViewKind.DOOR_SCHEDULE, title = "Door schedule"),     // a table: not numbered
        SheetView(kind = SheetViewKind.PLAN, elementId = footing.id, title = "Footing Detail"),
        SheetView(kind = SheetViewKind.PLAN, elementId = pocket.id, title = "Beam Pocket Detail")
    ))
    private val state = base.copy(elements = listOf(footing, pocket), sheets = listOf(s4))

    private fun callout(name: String, vararg t: Callouts.Target) = DrawingElement.Symbol(
        symbolName = name, category = Callouts.CATEGORY, center = Offset.Zero, size = Size(400f, 100f), color = Color.White,
        properties = mapOf(Callouts.TARGETS to t.sumOf { it.bit }.toFloat()))

    @Test
    fun `drawings on a sheet are numbered in order, tables skipped`() {
        assertEquals(1, Sheets.viewNumber(s4, s4.views[0]))
        assertEquals(null, Sheets.viewNumber(s4, s4.views[1]))
        assertEquals(2, Sheets.viewNumber(s4, s4.views[2]))
        assertEquals(3, Sheets.viewNumber(s4, s4.views[3]))
    }

    @Test
    fun `a callout reads its numbers off the sheet set, several on one sheet share a bubble`() {
        assertEquals(listOf("1" to "S-4"), Callouts.bubbles(callout(Callouts.SECTION, Callouts.Target.WALL_SECTION), state))
        assertEquals(listOf("2,3" to "S-4"), Callouts.bubbles(callout(Callouts.DETAIL, Callouts.Target.FOOTING, Callouts.Target.BEAM_POCKET), state))
        // A detail on no sheet yet shows "?".
        assertEquals(listOf("?" to "—"), Callouts.bubbles(callout(Callouts.DETAIL, Callouts.Target.COLUMN), state))
        // Renumber the sheet: the callout follows.
        val moved = state.copy(sheets = listOf(s4.copy(number = "S-6")))
        assertEquals(listOf("2,3" to "S-6"), Callouts.bubbles(callout(Callouts.DETAIL, Callouts.Target.FOOTING, Callouts.Target.BEAM_POCKET), moved))
    }
}
