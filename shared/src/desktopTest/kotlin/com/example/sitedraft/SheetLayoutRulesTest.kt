package com.example.sitedraft

import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetLayoutRules
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Sheet rules (Dan, Nov 28): the two schedule sheets, rows that never overlap, and "(cont.)" when a sheet is full. */
class SheetLayoutRulesTest {
    private val st: CanvasState = Json { ignoreUnknownKeys = true }
        .decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/practice_house.json").readText())

    private fun checkClean(sheet: SheetLayout, state: CanvasState) {
        val area = Sheets.drawingArea(sheet)
        assertTrue("${sheet.title}: overlapping ${Sheets.overlapping(sheet, state).map { it.title }}", Sheets.overlapping(sheet, state).isEmpty())
        for (v in sheet.views) {
            val f = Sheets.footprint(v, state) ?: continue
            assertTrue("${v.title} off ${sheet.title}: $f in $area", f.left >= area.left - 0.1f && f.right <= area.right + 0.1f && f.top >= area.top - 0.1f && f.bottom <= area.bottom + 0.1f)
        }
    }

    @Test
    fun `every job gets the two schedule sheets, by trade, laid out clean`() {
        val sheets = SheetLayoutRules.withScheduleSheets(emptyList(), st)
        val titles = sheets.map { it.title }
        assertTrue(titles.toString(), "Door, window and finish schedules" in titles)
        assertTrue(titles.toString(), "Cabinet, appliance and fixture schedules" in titles)
        val first = sheets.first { it.title == "Door, window and finish schedules" }
        assertEquals(listOf(SheetViewKind.DOOR_SCHEDULE, SheetViewKind.WINDOW_SCHEDULE, SheetViewKind.FINISH_SCHEDULE, SheetViewKind.PAINT_SCHEDULE, SheetViewKind.TRIM_SCHEDULE),
            sheets.filter { it.title.startsWith("Door, window and finish schedules") }.flatMap { it.views }.map { it.kind })
        val state = st.copy(sheets = sheets)
        sheets.forEach { checkClean(it, state) }
        // Adding them again changes nothing.
        assertEquals(sheets.size, SheetLayoutRules.withScheduleSheets(sheets, state).size)
        assertTrue(first.views.isNotEmpty())
    }

    @Test
    fun `a full sheet carries on to a cont sheet instead of overlapping`() {
        // Every schedule at 1 1/2 size on one 24x36: more than fits.
        val kinds = listOf(SheetViewKind.DOOR_SCHEDULE, SheetViewKind.WINDOW_SCHEDULE, SheetViewKind.FINISH_SCHEDULE, SheetViewKind.PAINT_SCHEDULE,
            SheetViewKind.TRIM_SCHEDULE, SheetViewKind.CABINET_SCHEDULE, SheetViewKind.APPLIANCE_SCHEDULE, SheetViewKind.PLUMBING_SCHEDULE, SheetViewKind.GENERAL_NOTES)
        val small = SheetLayout(number = "A-1", title = "Schedules", paper = com.example.sitedraft.ui.canvas.PaperSize.ARCH_D,
            views = kinds.map { SheetView(kind = it, title = it.name, tableSize = 1.5f) })
        val set = SheetLayoutRules.arrangedSet(small, listOf(small), st)
        val state = st.copy(sheets = set)
        set.forEach { checkClean(it, state) }
        assertEquals("every view kept", kinds.size, set.sumOf { it.views.size })
        if (set.size > 1) assertTrue(set[1].title.endsWith("(cont.)"))
    }

    @Test
    fun `the app builds the whole set in order, nothing overlapping`() {
        val r = com.example.sitedraft.ui.canvas.SheetSetBuilder.build(st.copy(sheets = emptyList()))
        val titles = r.sheets.map { it.title }
        assertTrue(titles.toString(), titles.first().startsWith("Cover"))
        assertTrue(titles.toString(), "Door, window and finish schedules" in titles && "Cabinet, appliance and fixture schedules" in titles)
        val state = st.copy(sheets = r.sheets)
        r.sheets.forEach { sh -> assertTrue("${sh.title}: ${Sheets.overlapping(sh, state).map { it.title }}", Sheets.overlapping(sh, state).isEmpty()) }
        // Building again adds nothing.
        assertTrue(com.example.sitedraft.ui.canvas.SheetSetBuilder.build(state).added.isEmpty())
    }

    @Test
    fun `a designer's set leaves out the structural sheets`() {
        val designer = st.copy(sheets = emptyList(), projectType = com.example.sitedraft.ui.canvas.SheetSetBuilder.ProjectType.DESIGNER.name)
        val titles = com.example.sitedraft.ui.canvas.SheetSetBuilder.build(designer).sheets.map { it.title }
        assertTrue(titles.toString(), titles.none { it.contains("Foundation") || it.contains("Framing") || it.contains("Wall Section") })
        assertTrue(titles.toString(), "Cabinet, appliance and fixture schedules" in titles)
    }
}
