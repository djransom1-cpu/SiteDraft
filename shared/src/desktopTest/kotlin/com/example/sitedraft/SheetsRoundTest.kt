package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.platform.PdfWriter
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.PrintScale
import com.example.sitedraft.ui.canvas.ProjectInfo
import com.example.sitedraft.ui.canvas.Revision
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.ViewCrop
import com.example.sitedraft.ui.canvas.WallSection
import com.example.sitedraft.ui.canvas.boundingRect
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Nov 22 sheets round: project info, the wall section in model space, cropped viewports, sheet sets. 1' = 50px. */
class SheetsRoundTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val inch = 50f / 12f
    private val wall = DrawingElement.Symbol(symbolName = "2x6 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset(240f * inch, 2.75f * inch), size = Size(480f * inch, 5.5f * inch))
    private val kitchenWall = DrawingElement.Symbol(symbolName = "2x4 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset(60f * inch, 120f * inch), size = Size(120f * inch, 4.5f * inch))

    @Test
    fun `project info and revisions are saved with the project`() {
        val info = ProjectInfo(clientName = "Martin", address = "123 Lake Rd\nCharlevoix, MI", startDate = "11/22/2026",
            drawnBy = "Dan Ransom", company = "Ransom Carpentry", revisions = listOf(Revision("1", "11/30/2026", "Moved kitchen window")))
        val s = CanvasState(projectInfo = info)
        val back = json.decodeFromString(CanvasState.serializer(), json.encodeToString(CanvasState.serializer(), s))
        assertEquals(info, back.projectInfo)
        assertEquals("2", info.nextRevisionNumber())
        assertEquals("1", ProjectInfo().nextRevisionNumber())
        // An older project, saved before project info, opens with it blank.
        val old = json.decodeFromString(CanvasState.serializer(), """{"elements":[]}""")
        assertEquals(ProjectInfo(), old.projectInfo)
    }

    @Test
    fun `the wall section is placed in model space, right of the drawing, true size`() {
        val vm = DrawingManager()
        vm.onEvent(DrawingEvent.UpdateSheets(emptyList()))
        val s0 = vm.state.value.copy(elements = listOf(wall))
        val section = WallSection.newSymbol(s0, WallSection.spotFor(s0))
        assertTrue(WallSection.isSection(section))
        assertTrue(section.center.x - section.size.width / 2f > wall.boundingRect().right)
        // Its frame is its real extent at the drawing scale.
        val extent = WallSection.geometry(s0).extent
        assertEquals(extent.width * inch, section.size.width, 0.5f)
        assertEquals(WallSection.DEFAULT_DENOMINATOR, WallSection.denominatorOf(section))
    }

    @Test
    fun `a sheet's section view comes from the model-space section and its notes, and plan views leave it out`() {
        val base = CanvasState(elements = listOf(wall))
        val section = WallSection.newSymbol(base, WallSection.spotFor(base))
        val note = DrawingElement.Text(text = "SEE NOTE 4", topLeft = section.center, fontSize = 12f, color = Color.White)
        val s = base.copy(elements = listOf(wall, section, note))
        val view = SheetView(kind = SheetViewKind.SECTION, elementId = section.id, title = "Typical Wall Section", scale = PrintScale.THREE_QUARTER)
        assertEquals(section.id, Sheets.sectionFor(view, s)?.id)
        assertEquals(setOf(section.id, note.id), Sheets.viewElements(view, s).map { it.id }.toSet())
        val plan = Sheets.viewElements(SheetView(kind = SheetViewKind.PLAN, title = "Floor Plan"), s)
        assertEquals(listOf(wall.id), plan.map { it.id })
        // Without a model-space section the view still draws, straight from the inputs.
        assertNull(Sheets.sectionFor(view, base))
        assertNotNull(Sheets.viewRect(view, base))
    }

    @Test
    fun `adding the section to a sheet places it in model space first`() {
        val vm = DrawingManager()
        val sheet = SheetLayout(number = "A-3", title = "Wall Section")
        vm.onEvent(DrawingEvent.UpdateSheets(listOf(sheet)))
        vm.onEvent(DrawingEvent.AddWallSectionToSheet(sheet.id))
        val s = vm.state.value
        val section = WallSection.sectionIn(s)
        assertNotNull(section)
        val v = s.sheets.single().views.single()
        assertEquals(SheetViewKind.SECTION, v.kind)
        assertEquals(section!!.id, v.elementId)
        // Placing it again selects the one there, it doesn't make a second.
        vm.onEvent(DrawingEvent.PlaceWallSection)
        assertEquals(1, vm.state.value.elements.count { WallSection.isSection(it) })
        assertEquals(section.id, vm.state.value.selectedElementId)
    }

    @Test
    fun `a cropped viewport shows just its window at its own scale`() {
        val s = CanvasState(elements = listOf(wall, kitchenWall))
        val whole = SheetView(kind = SheetViewKind.PLAN, title = "Floor Plan", scale = PrintScale.QUARTER)
        // The 40' wall at 1/4" is 10" on paper.
        assertEquals(10f, Sheets.viewRect(whole, s)!!.width, 0.05f)
        // A 12' x 10' window round the kitchen at 1/2" is 6" x 5".
        val window = Rect(0f, 70f * inch, 144f * inch, 190f * inch)
        val kitchen = whole.copy(title = "Kitchen", scale = PrintScale.HALF, crop = ViewCrop.of(window))
        val r = Sheets.viewRect(kitchen, s)!!
        assertEquals(6f, r.width, 0.05f)
        assertEquals(5f, r.height, 0.05f)
        // The crop is saved with the sheet.
        val sheet = SheetLayout(views = listOf(whole, kitchen))
        val back = json.decodeFromString(CanvasState.serializer(), json.encodeToString(CanvasState.serializer(), s.copy(sheets = listOf(sheet))))
        assertEquals(ViewCrop.of(window), back.sheets.single().views[1].crop)
        assertFalse(back.sheets.single().views[0].crop != null)
    }

    @Test
    fun `a sheet set is one PDF with a page per sheet`() {
        val page = PdfWriter.ImagePage(2, 1, ByteArray(6), flate = false, pageWidthPt = 2592f, pageHeightPt = 1728f)
        val pdf = PdfWriter.imagePages(listOf(page, page, page)).decodeToString()
        assertTrue(pdf.startsWith("%PDF-1.4"))
        assertTrue(pdf.contains("/Count 3"))
        assertTrue(pdf.contains("/Kids [3 0 R 6 0 R 9 0 R]"))
        assertTrue(pdf.trimEnd().endsWith("%%EOF"))
    }
}
