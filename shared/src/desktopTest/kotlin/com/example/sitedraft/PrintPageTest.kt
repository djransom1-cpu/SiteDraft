package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingScale
import com.example.sitedraft.ui.canvas.PageOrientation
import com.example.sitedraft.ui.canvas.PaperSize
import com.example.sitedraft.ui.canvas.PrintPage
import com.example.sitedraft.ui.canvas.PrintScale
import com.example.sitedraft.ui.canvas.PrintSettings
import com.example.sitedraft.ui.canvas.PrintSubject
import com.example.sitedraft.ui.canvas.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The project scale is 1' = 50 px, so one real inch is 50/12 world px. */
class PrintPageTest {
    private val pxPerFoot = 50f
    private val pxPerInch = pxPerFoot / 12f
    private val state = CanvasState(scale = DrawingScale(pxPerFoot, "1' = 50px"))

    private fun wall(lengthFt: Float) = Rect(0f, 0f, lengthFt * pxPerFoot, 0.375f * pxPerFoot)

    @Test
    fun `a 24 foot wall at quarter inch scale is 6 inches on Letter landscape`() {
        val settings = PrintSettings(paper = PaperSize.LETTER, orientation = PageOrientation.LANDSCAPE, scale = PrintScale.QUARTER)
        val layout = PrintPage.layout(settings, wall(24f), pxPerInch)
        assertEquals(6f, layout.inchesOnPaper(24f * pxPerFoot), 0.001f)
        assertEquals(6f, layout.drawingWidthIn, 0.001f)
        assertTrue(layout.fits)
    }

    @Test
    fun `scale is true whatever the dpi`() {
        val settings = PrintSettings(paper = PaperSize.TABLOID, orientation = PageOrientation.LANDSCAPE, scale = PrintScale.QUARTER)
        for (dpi in listOf(72f, 100f, 150f, 300f)) {
            val layout = PrintPage.layout(settings, wall(24f), pxPerInch, dpi = dpi)
            assertEquals("at $dpi dpi", 6f, layout.inchesOnPaper(24f * pxPerFoot), 0.001f)
        }
    }

    @Test
    fun `each named scale puts a foot at its paper length`() {
        val expected = mapOf(
            PrintScale.EIGHTH to 0.125f,
            PrintScale.QUARTER to 0.25f,
            PrintScale.HALF to 0.5f,
            PrintScale.THREE_QUARTER to 0.75f,
            PrintScale.ONE to 1f
        )
        for ((scale, inchesPerFoot) in expected) {
            val layout = PrintPage.layout(PrintSettings(paper = PaperSize.ARCH_D, scale = scale), wall(10f), pxPerInch)
            assertEquals(scale.label, inchesPerFoot, layout.inchesOnPaper(pxPerFoot), 0.0001f)
        }
    }

    @Test
    fun `page pixels are the paper size at the dpi, turned by the orientation`() {
        val landscape = PrintPage.layout(PrintSettings(paper = PaperSize.LETTER, orientation = PageOrientation.LANDSCAPE), wall(1f), pxPerInch, dpi = 150f)
        assertEquals(1650, landscape.widthPx)
        assertEquals(1275, landscape.heightPx)
        assertEquals(11f, landscape.paperWidthIn, 0.001f)
        assertEquals(8.5f, landscape.paperHeightIn, 0.001f)
        val portrait = PrintPage.layout(PrintSettings(paper = PaperSize.TABLOID, orientation = PageOrientation.PORTRAIT), wall(1f), pxPerInch, dpi = 100f)
        assertEquals(1100, portrait.widthPx)
        assertEquals(1700, portrait.heightPx)
    }

    @Test
    fun `the drawing area sits inside the margins above the title block`() {
        val layout = PrintPage.layout(PrintSettings(paper = PaperSize.LETTER, orientation = PageOrientation.LANDSCAPE), wall(1f), pxPerInch, dpi = 100f)
        val margin = PrintPage.MARGIN_IN * 100f
        assertEquals(margin, layout.content.left, 0.001f)
        assertEquals(margin, layout.content.top, 0.001f)
        assertEquals(1100f - margin, layout.content.right, 0.001f)
        assertEquals(layout.titleBlock.top, layout.content.bottom, 0.001f)
        assertEquals(850f - margin, layout.titleBlock.bottom, 0.001f)
        assertEquals(PrintPage.TITLE_BLOCK_IN * 100f, layout.titleBlock.height, 0.001f)
    }

    @Test
    fun `the subject is centred in the drawing area`() {
        val bounds = Rect(400f, -200f, 400f + 24f * pxPerFoot, -200f + 10f * pxPerFoot)
        val layout = PrintPage.layout(PrintSettings(paper = PaperSize.LETTER, orientation = PageOrientation.LANDSCAPE, scale = PrintScale.QUARTER), bounds, pxPerInch)
        val centreOnPage = bounds.center * layout.zoom + layout.pan
        assertEquals(layout.content.center.x, centreOnPage.x, 0.01f)
        assertEquals(layout.content.center.y, centreOnPage.y, 0.01f)
    }

    @Test
    fun `a wall too long for the sheet at this scale is reported as not fitting`() {
        // 60' at 1/4" = 15" wide: more than Letter's 11" (minus margins).
        val layout = PrintPage.layout(PrintSettings(paper = PaperSize.LETTER, orientation = PageOrientation.LANDSCAPE, scale = PrintScale.QUARTER), wall(60f), pxPerInch)
        assertEquals(15f, layout.drawingWidthIn, 0.001f)
        assertFalse(layout.fits)
        // The same wall at 1/8" is 7.5" and fits; on Arch D at 1/4" it fits too.
        assertTrue(PrintPage.layout(PrintSettings(paper = PaperSize.LETTER, orientation = PageOrientation.LANDSCAPE, scale = PrintScale.EIGHTH), wall(60f), pxPerInch).fits)
        assertTrue(PrintPage.layout(PrintSettings(paper = PaperSize.ARCH_D, orientation = PageOrientation.LANDSCAPE, scale = PrintScale.QUARTER), wall(60f), pxPerInch).fits)
    }

    @Test
    fun `fit to page fills the drawing area without running past it`() {
        val bounds = Rect(0f, 0f, 3000f, 1000f)
        val layout = PrintPage.layout(PrintSettings(paper = PaperSize.LETTER, orientation = PageOrientation.LANDSCAPE, scale = PrintScale.FIT), bounds, pxPerInch)
        assertTrue(layout.fits)
        // Wide drawing: the width is the limiting edge, so it comes close to the area's width.
        assertTrue(layout.drawingWidthIn > 0.9f * layout.content.width / layout.dpi)
        assertEquals("NTS (fit to page)", layout.scaleText)
    }

    @Test
    fun `scale text reads as a construction scale, or a ratio for metric`() {
        assertEquals("1/4\" = 1'-0\" (1:48)", PrintPage.scaleText(PrintScale.QUARTER, metric = false))
        assertEquals("1:48", PrintPage.scaleText(PrintScale.QUARTER, metric = true))
        assertEquals("1/8\" = 1'-0\" (1:96)", PrintPage.scaleText(PrintScale.EIGHTH, metric = false))
    }

    @Test
    fun `world px per inch follows the unit system`() {
        assertEquals(pxPerInch, PrintPage.worldPxPerInch(state), 0.0001f)
        val metric = CanvasState(unitSystem = UnitSystem.METRIC, scale = DrawingScale(100f, "1m = 100px"))
        // 100 px per metre = 2.54 px per inch.
        assertEquals(2.54f, PrintPage.worldPxPerInch(metric), 0.001f)
        // A 1:48 print of a 10 m wall is 10 m / 48 = 8.2".
        val layout = PrintPage.layout(PrintSettings(paper = PaperSize.ARCH_C, scale = PrintScale.QUARTER), Rect(0f, 0f, 1000f, 10f), PrintPage.worldPxPerInch(metric), metric = true)
        assertEquals(1000f / 2.54f / 48f, layout.inchesOnPaper(1000f), 0.01f)
    }

    @Test
    fun `larger sheets drop the dpi to keep the page bitmap bounded`() {
        assertEquals(150f, PrintPage.dpiFor(PaperSize.LETTER), 0f)
        assertEquals(150f, PrintPage.dpiFor(PaperSize.TABLOID), 0f)
        assertTrue(PrintPage.dpiFor(PaperSize.ARCH_D) < 150f)
        val d = PrintPage.dpiFor(PaperSize.ARCH_D)
        assertTrue(24f * d * 36f * d <= 12_000_000f)
    }

    @Test
    fun `current view bounds are the visible world rectangle`() {
        val s = state.copy(zoom = 2f, pan = Offset(-100f, -50f), viewportSize = Size(800f, 600f))
        val view = PrintPage.subjectBounds(s, PrintSubject.CURRENT_VIEW)!!
        assertEquals(50f, view.left, 0.001f)
        assertEquals(25f, view.top, 0.001f)
        assertEquals(450f, view.right, 0.001f)
        assertEquals(325f, view.bottom, 0.001f)
    }

    @Test
    fun `whole drawing bounds cover every element and an empty drawing has none`() {
        assertNull(PrintPage.subjectBounds(state, PrintSubject.WHOLE_DRAWING))
        val s = state.copy(
            elements = listOf(
                DrawingElement.Line(start = Offset(0f, 0f), end = Offset(1200f, 0f), color = Color.White, strokeWidth = 2f),
                DrawingElement.Rectangle(topLeft = Offset(-100f, 300f), size = Size(50f, 50f), color = Color.White, strokeWidth = 2f)
            )
        )
        val b = PrintPage.subjectBounds(s, PrintSubject.WHOLE_DRAWING)!!
        assertEquals(-100f, b.left, 0.001f)
        assertEquals(1200f, b.right, 0.001f)
        assertEquals(350f, b.bottom, 0.001f)
    }

    @Test
    fun `the page state is white paper whatever the canvas colour, with nothing interactive`() {
        val blueprint = state.copy(
            backgroundColor = Color(0xFF003366),
            elements = listOf(DrawingElement.Line(start = Offset.Zero, end = Offset(600f, 0f), color = Color.White, strokeWidth = 2f)),
            selectedElementId = "x",
            lastSnapPoint = Offset(1f, 1f)
        )
        val layout = PrintPage.layout(PrintSettings(scale = PrintScale.QUARTER), wall(12f), pxPerInch)
        val page = PrintPage.pageState(blueprint, blueprint.elements, layout)
        // The renderer draws every line black when the background is light (luminance > 0.5).
        assertTrue(page.backgroundColor.luminance() > 0.5f)
        assertEquals(Color.White, page.backgroundColor)
        assertNull(page.selectedElementId)
        assertNull(page.lastSnapPoint)
        assertEquals(layout.zoom, page.zoom, 0f)
        assertEquals(layout.pan, page.pan)
        // The screen keeps its own colour: pageState copies, it does not mutate.
        assertEquals(Color(0xFF003366), blueprint.backgroundColor)
    }

    @Test
    fun `white pen strokes print black, darker colours and highlighter print as drawn`() {
        val white = DrawingElement.Line(start = Offset.Zero, end = Offset(100f, 0f), color = Color.White, strokeWidth = 2f)
        val cream = DrawingElement.Rectangle(topLeft = Offset.Zero, size = Size(10f, 10f), color = Color(0xFFF5F0E0), strokeWidth = 2f)
        val red = DrawingElement.Line(start = Offset.Zero, end = Offset(100f, 0f), color = Color.Red, strokeWidth = 2f)
        val highlighter = DrawingElement.Freehand(points = emptyList(), color = Color.Yellow, strokeWidth = 12f, isHighlighter = true)
        val layout = PrintPage.layout(PrintSettings(), wall(12f), pxPerInch)
        val page = PrintPage.pageState(state, listOf(white, cream, red, highlighter), layout)
        assertEquals(Color.Black, page.elements[0].color)
        assertEquals(Color.Black, page.elements[1].color)
        assertEquals(Color.Red, page.elements[2].color)
        assertEquals(Color.Yellow, page.elements[3].color)
        // Ids survive, so a recoloured elevation view still finds its wall.
        assertEquals(white.id, page.elements[0].id)
    }

    @Test
    fun `dimension labels come out an eighth of an inch tall on paper`() {
        // At 150 dpi a 36 px screen label must become 150 / 8 = 18.75 page px.
        assertEquals(18.75f, 36f * PrintPage.labelScale(150f), 0.001f)
        val layout = PrintPage.layout(PrintSettings(scale = PrintScale.QUARTER), wall(12f), pxPerInch, dpi = 150f)
        val page = PrintPage.pageState(state, emptyList(), layout)
        // The renderer draws the label at 36 * labelScale / zoom world px; through the page zoom
        // that is 36 * labelScale page px, i.e. 1/8" whatever the drawing scale.
        val worldPx = 36f * page.labelScale / page.zoom
        assertEquals(PrintPage.LABEL_HEIGHT_IN, worldPx * page.zoom / layout.dpi, 0.0001f)
        val eighth = PrintPage.layout(PrintSettings(scale = PrintScale.EIGHTH), wall(12f), pxPerInch, dpi = 100f)
        val p2 = PrintPage.pageState(state, emptyList(), eighth)
        assertEquals(PrintPage.LABEL_HEIGHT_IN, (36f * p2.labelScale / p2.zoom) * p2.zoom / eighth.dpi, 0.0001f)
    }

    @Test
    fun `selected elevation needs an elevation view selected`() {
        assertNull(PrintPage.subjectElements(state, PrintSubject.SELECTED_ELEVATION))
        assertNull(PrintPage.subjectBounds(state, PrintSubject.SELECTED_ELEVATION))
    }
}
