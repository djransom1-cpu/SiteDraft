package com.example.sitedraft

import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.CoverSheet
import com.example.sitedraft.ui.canvas.ProjectInfo
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The T sheets (Nov 25, for the architect demo): sheet index, project data, legend, general notes. */
class CoverSheetTest {
    private val state = CanvasState(
        projectInfo = ProjectInfo(clientName = "Martin", address = "Bear Lake, MI", company = "Ransom Carpentry"),
        sheets = listOf(
            SheetLayout(number = "", title = "Foundation Plan"),
            SheetLayout(number = "", title = "Cover Sheet, Legend and Symbols", views = listOf(SheetView(kind = SheetViewKind.SHEET_INDEX, title = "Sheet Index"))),
            SheetLayout(number = "", title = "General Notes", views = listOf(SheetView(kind = SheetViewKind.GENERAL_NOTES, title = "General Notes"))),
        ))

    @Test
    fun `the cover and notes sheets go first in the set`() {
        val set = Sheets.renumbered(state.sheets, state)
        assertEquals("T-1", set.first { it.title.startsWith("Cover") }.number)
        assertEquals("T-2", set.first { it.title == "General Notes" }.number)
        val index = CoverSheet.sheetIndex(state)
        assertEquals(3, index.size)
        assertEquals("T-1", index.first().label)
    }

    @Test
    fun `notes carry this job's numbers and the code`() {
        val notes = CoverSheet.generalNotes(state)
        assertTrue(notes.size >= 10)
        assertTrue(notes.first().text.contains(state.building.codeEdition.label))
        assertTrue(notes.any { it.text.contains("#${state.building.rebarSize}") })
        assertEquals("1.", notes.first().label)
    }

    @Test
    fun `project data and legend fill in, and every table has a size on paper`() {
        val data = CoverSheet.projectData(state)
        assertTrue(data.any { it.label == "Client" && it.text == "Martin" })
        assertTrue(CoverSheet.legend().count { it.symbol != null } >= 5)
        // The cover title: the project's name big, then the address, then client and drawings by.
        val title = CoverSheet.titleLines(state.copy(projectName = "Martin Cottage"))
        assertEquals("MARTIN COTTAGE", title[0])
        assertEquals("Bear Lake, MI", title[1])
        assertTrue(title[2].contains("Client: Martin") && title[2].contains("Ransom Carpentry"))
        for (k in listOf(SheetViewKind.SHEET_INDEX, SheetViewKind.PROJECT_DATA, SheetViewKind.LEGEND, SheetViewKind.GENERAL_NOTES, SheetViewKind.COVER_TITLE)) {
            val sz = CoverSheet.tableSizeIn(k, state)
            assertTrue("$k", sz.width > 3f && sz.height > 0.5f)
            assertTrue(Sheets.isTable(SheetView(kind = k, title = "x")))
        }
    }
}
