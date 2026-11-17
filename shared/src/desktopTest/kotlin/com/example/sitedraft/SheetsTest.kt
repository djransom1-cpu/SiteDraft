package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.AutoDimension
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.PaperSize
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.PrintScale
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.ViewerSide
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Large-format sheets of views. 1' = 50px. */
class SheetsTest {
    private val inch = 50f / 12f
    private val wall = DrawingElement.Symbol(symbolName = "2x4 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset(120f * inch, 2.25f * inch), size = Size(240f * inch, 4.5f * inch))
    private val base = DrawingElement.Symbol(symbolName = "Base 36\"", category = PlanSymbols.CABINETS, color = Color.White,
        center = Offset(60f * inch, (4.5f + 12f) * inch), size = Size(36f * inch, 24f * inch))
    private val spec = ElevationSpec(wallId = wall.id, side = ViewerSide.BELOW)
    private val elevation = DrawingElement.ElevationView(spec = spec, origin = Offset(0f, 3000f),
        size = Elevation.sizePx(wall, spec, CanvasState(elements = listOf(wall, base))), color = Color.White)

    private fun state(): CanvasState {
        val s = CanvasState(elements = listOf(wall, base, elevation))
        return s.copy(elements = s.elements + AutoDimension.elevation(elevation, s))
    }

    @Test
    fun `a view prints at its own scale`() {
        val s = state()
        val plan = SheetView(kind = SheetViewKind.PLAN, title = "Floor Plan", scale = PrintScale.QUARTER)
        // The 20' wall at 1/4" = 1'-0" is 5" on paper.
        assertEquals(5f, Sheets.viewRect(plan, s)!!.width, 0.01f)
        val elev = SheetView(kind = SheetViewKind.ELEVATION, elementId = elevation.id, title = "Elevation 1", scale = PrintScale.HALF)
        assertTrue(Sheets.viewRect(elev, s)!!.width >= 10f - 0.01f)
    }

    @Test
    fun `the plan view leaves out the elevations and their dimensions, an elevation view keeps its own`() {
        val s = state()
        val plan = Sheets.viewElements(SheetView(kind = SheetViewKind.PLAN, title = "P"), s)
        assertFalse(plan.any { it is DrawingElement.ElevationView || it.isOwnedByAny(elevation.id) })
        val elev = Sheets.viewElements(SheetView(kind = SheetViewKind.ELEVATION, elementId = elevation.id, title = "E"), s)
        assertTrue(elev.any { it is DrawingElement.ElevationView })
        assertTrue(elev.count { it is DrawingElement.Dimension } > 2)
    }

    private fun DrawingElement.isOwnedByAny(id: String) = this is DrawingElement.Dimension && ownerId == id

    @Test
    fun `new views are placed in free spots inside the drawing area`() {
        val s = state()
        var sheet = SheetLayout(paper = PaperSize.ARCH_D)
        sheet = sheet.copy(views = listOf(Sheets.placed(sheet, SheetView(kind = SheetViewKind.PLAN, title = "Floor Plan"), s)))
        sheet = sheet.copy(views = sheet.views + Sheets.placed(sheet, SheetView(kind = SheetViewKind.ELEVATION, elementId = elevation.id, title = "Elevation 1", scale = PrintScale.HALF), s))
        val area = Sheets.drawingArea(sheet)
        val a = Sheets.footprint(sheet.views[0], s)!!
        val b = Sheets.footprint(sheet.views[1], s)!!
        assertFalse(a.overlaps(b))
        for (f in listOf(a, b)) assertTrue(f.left >= area.left - 0.01f && f.right <= area.right + 0.01f && f.top >= area.top - 0.01f && f.bottom <= area.bottom + 0.01f)
        // The first one goes in the top-left corner of the drawing area.
        assertEquals(area.left, a.left, 0.01f)
        assertEquals(area.top, a.top, 0.01f)
        assertEquals(sheet.views[1].id, Sheets.viewAt(sheet, b.center, s)?.id)
    }

    @Test
    fun `printing just an elevation draws it from the plan`() {
        val s = state()
        val layout = com.example.sitedraft.ui.canvas.PrintPage.layout(com.example.sitedraft.ui.canvas.PrintSettings(), elevation.frame, com.example.sitedraft.ui.canvas.PrintPage.worldPxPerInch(s))
        val page = com.example.sitedraft.ui.canvas.PrintPage.pageState(s, listOf(elevation), layout)
        assertFalse(page.elements.any { it is DrawingElement.ElevationView })
        // The base cabinet is drawn, not the "its wall was deleted" frame.
        assertTrue(page.elements.size > 6)
        assertFalse(page.elements.any { it is DrawingElement.Text && it.text.contains("deleted") })
    }

    @Test
    fun `sheets are saved with the project`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val sheet = SheetLayout(number = "A-2", title = "Kitchen", paper = PaperSize.ARCH_E1,
            views = listOf(SheetView(kind = SheetViewKind.PLAN, title = "Floor Plan", scale = PrintScale.THREE_EIGHTHS, xIn = 8f, yIn = 6f)))
        val text = json.encodeToString(CanvasState.serializer(), CanvasState(sheets = listOf(sheet)))
        val back = json.decodeFromString(CanvasState.serializer(), text)
        assertEquals(listOf(sheet), back.sheets)
        // Older project files without sheets still open.
        assertTrue(json.decodeFromString(CanvasState.serializer(), "{}").sheets.isEmpty())
    }
}
