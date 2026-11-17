package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.example.sitedraft.platform.randomId
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Large-format sheets made of views: the floor plan at one scale, elevations at another, each
 * placed where the user drags it, with a title block down the right edge. Sheets are saved with
 * the project; printing or exporting one goes through the same page path as a plain print.
 */
@Serializable
enum class SheetViewKind { PLAN, ELEVATION }

/** One view on a sheet. [xIn], [yIn]: the view's centre on the paper, inches from the sheet's top-left corner. */
@Serializable
data class SheetView(
    val id: String = randomId(),
    val kind: SheetViewKind,
    /** The elevation shown, for [SheetViewKind.ELEVATION]. */
    val elementId: String? = null,
    val title: String,
    val scale: PrintScale = PrintScale.QUARTER,
    val xIn: Float = 0f,
    val yIn: Float = 0f
)

@Serializable
data class SheetLayout(
    val id: String = randomId(),
    val number: String = "A-1",
    val title: String = "Floor Plan",
    val paper: PaperSize = PaperSize.ARCH_D,
    val orientation: PageOrientation = PageOrientation.LANDSCAPE,
    val drawnBy: String = "",
    val views: List<SheetView> = emptyList()
)

object Sheets {
    const val MARGIN_IN = 0.5f
    /** The title block strip down the right edge. */
    fun stripIn(sheet: SheetLayout): Float = if (paperIn(sheet).first < 20f) 1.75f else 2.5f
    /** Room under each view for its title and scale. */
    const val TITLE_ROOM_IN = 0.55f
    /** Scales offered for a view (not "fit": a view on a sheet is always to scale). */
    val viewScales = PrintScale.entries.filter { it.denominator != null }

    fun paperIn(sheet: SheetLayout): Pair<Float, Float> =
        if (sheet.orientation == PageOrientation.LANDSCAPE) sheet.paper.longIn to sheet.paper.shortIn
        else sheet.paper.shortIn to sheet.paper.longIn

    /** Where views may go: inside the margins, left of the title strip (inches). */
    fun drawingArea(sheet: SheetLayout): Rect {
        val (w, h) = paperIn(sheet)
        return Rect(MARGIN_IN, MARGIN_IN, w - MARGIN_IN - stripIn(sheet), h - MARGIN_IN)
    }

    /** The elevations other elements belong to (their dimensions), so the plan view leaves them out. */
    private fun elevationIds(state: CanvasState) = state.elements.filterIsInstance<DrawingElement.ElevationView>().map { it.id }.toSet()

    /** The elements a view shows. */
    fun viewElements(view: SheetView, state: CanvasState): List<DrawingElement> = when (view.kind) {
        SheetViewKind.PLAN -> {
            val elevations = elevationIds(state)
            state.elements.filter { e ->
                e !is DrawingElement.ElevationView && !(e is DrawingElement.Dimension && e.ownerId != null && e.ownerId in elevations)
            }
        }
        SheetViewKind.ELEVATION -> state.elements.filter { it.id == view.elementId || it.isOwnedBy(view.elementId) }
    }

    /** The view's extent in world px (null when it shows nothing, e.g. its elevation was deleted). */
    fun worldBounds(view: SheetView, state: CanvasState): Rect? {
        val rects = viewElements(view, state).flatMap { e ->
            if (e is DrawingElement.ElevationView) listOf(e.frame) + Elevation.partsOf(e, state).map { it.boundingRect() }
            else listOf(e.boundingRect())
        }.filter { it.width > 0f || it.height > 0f }
        return rects.reduceOrNull { a, r -> Rect(minOf(a.left, r.left), minOf(a.top, r.top), maxOf(a.right, r.right), maxOf(a.bottom, r.bottom)) }
    }

    /** Paper inches per world px at the view's scale. */
    fun inchesPerWorldPx(view: SheetView, state: CanvasState): Float =
        1f / ((view.scale.denominator ?: 48) * PrintPage.worldPxPerInch(state))

    /** The view's drawing on the paper (inches), centred on its position. */
    fun viewRect(view: SheetView, state: CanvasState): Rect? {
        val b = worldBounds(view, state) ?: return null
        val k = inchesPerWorldPx(view, state)
        val w = b.width * k
        val h = b.height * k
        return Rect(view.xIn - w / 2f, view.yIn - h / 2f, view.xIn + w / 2f, view.yIn + h / 2f)
    }

    /** The view with its title under it (inches): what has to fit on the sheet. */
    fun footprint(view: SheetView, state: CanvasState): Rect? =
        viewRect(view, state)?.let { Rect(it.left, it.top, it.right, it.bottom + TITLE_ROOM_IN) }

    /** The elevations in the drawing, numbered in placement order, as views could be made of them. */
    fun elevationChoices(state: CanvasState): List<Pair<DrawingElement.ElevationView, String>> =
        state.elements.filterIsInstance<DrawingElement.ElevationView>().mapIndexed { i, v ->
            val what = when {
                v.spec.islandFace >= 0 -> "Island ${Elevation.islandFaceLabels.getOrElse(v.spec.islandFace) { "" }.lowercase()}"
                v.spec.showerFace >= 0 -> "Shower"
                v.spec.stairSide >= 0 -> "Stair"
                else -> v.spec.side.looking.replaceFirstChar { it.uppercase() }
            }
            v to "Elevation ${i + 1} ($what)"
        }

    /**
     * [view] placed in the first free spot on [sheet]: rows from the top-left of the drawing
     * area, left to right, a 1/2" gap between views. Falls back to the middle when nothing fits.
     */
    fun placed(sheet: SheetLayout, view: SheetView, state: CanvasState): SheetView {
        val area = drawingArea(sheet)
        val size = footprint(view.copy(xIn = 0f, yIn = 0f), state) ?: return view.copy(xIn = area.center.x, yIn = area.center.y)
        val w = size.width
        val h = size.height
        val taken = sheet.views.mapNotNull { footprint(it, state)?.inflate(0.25f) }
        val step = 0.25f
        var y = area.top
        while (y + h <= area.bottom + 0.001f) {
            var x = area.left
            while (x + w <= area.right + 0.001f) {
                val r = Rect(x, y, x + w, y + h)
                if (taken.none { it.overlaps(r) }) {
                    // The footprint's top-left is (x, y); the view's centre sits half a drawing down from it.
                    val drawH = h - TITLE_ROOM_IN
                    return view.copy(xIn = x + w / 2f, yIn = y + drawH / 2f)
                }
                x += step
            }
            y += step
        }
        return view.copy(xIn = area.center.x, yIn = area.center.y)
    }

    /** Snaps a dragged position to 1/8". */
    fun snap(v: Float): Float = (v * 8f).roundToInt() / 8f

    /** The view under [pIn] (paper inches), topmost first. */
    fun viewAt(sheet: SheetLayout, pIn: Offset, state: CanvasState): SheetView? =
        sheet.views.lastOrNull { v -> footprint(v, state)?.contains(pIn) == true }
}

/**
 * Draws [sheet] at [dpi] page pixels per paper inch: white paper, each view to its own scale
 * clipped to its own extent, its title and scale under it, the border and the title block.
 */
fun DrawScope.drawSheet(sheet: SheetLayout, state: CanvasState, dpi: Float, project: String, date: String, highlightId: String? = null) {
    val (wIn, hIn) = Sheets.paperIn(sheet)
    val lineW = max(1f, dpi / 100f)
    drawRect(Color.White, size = Size(wIn * dpi, hIn * dpi))
    val metric = state.unitSystem == UnitSystem.METRIC
    for (view in sheet.views) {
        val bounds = Sheets.worldBounds(view, state) ?: continue
        val rIn = Sheets.viewRect(view, state) ?: continue
        val zoom = dpi * Sheets.inchesPerWorldPx(view, state)
        val centre = Offset(view.xIn * dpi, view.yIn * dpi)
        val pan = centre - bounds.center * zoom
        val page = PageLayout(
            widthPx = (wIn * dpi).roundToInt(), heightPx = (hIn * dpi).roundToInt(), dpi = dpi,
            content = Rect(rIn.left * dpi, rIn.top * dpi, rIn.right * dpi, rIn.bottom * dpi),
            titleBlock = Rect.Zero, zoom = zoom, pan = pan,
            scaleText = PrintPage.scaleText(view.scale, metric),
            drawingWidthIn = rIn.width, drawingHeightIn = rIn.height
        )
        // An elevation is drawn from the plan, which is not in the view: hand the page its
        // finished parts instead. No canvas grid on a sheet.
        val shown = Sheets.viewElements(view, state).flatMap { e ->
            if (e is DrawingElement.ElevationView) Elevation.partsOf(e, state) else listOf(e)
        }
        val pageState = PrintPage.pageState(state, shown, page).copy(background = DrawingBackground.PLAIN)
        val pad = 0.1f * dpi
        clipRect(rIn.left * dpi - pad, rIn.top * dpi - pad, rIn.right * dpi + pad, rIn.bottom * dpi + pad) {
            withTransform({
                translate(pan.x, pan.y)
                scale(zoom, zoom, Offset.Zero)
            }) { drawDocumentContents(pageState) }
        }
        // Title, underlined, and the scale under it.
        val titleTop = rIn.bottom * dpi + 0.12f * dpi
        val titleSize = 0.2f * dpi
        drawPageText(view.title.uppercase(), Offset(rIn.left * dpi, titleTop), titleSize, Color.Black, bold = true, maxWidth = max(rIn.width * dpi, 3f * dpi))
        drawLine(Color.Black, Offset(rIn.left * dpi, titleTop + titleSize * 1.3f), Offset((rIn.left + minOf(max(rIn.width, 2f), 6f)) * dpi, titleTop + titleSize * 1.3f), strokeWidth = lineW * 1.5f)
        drawPageText("SCALE: ${page.scaleText}", Offset(rIn.left * dpi, titleTop + titleSize * 1.5f), 0.12f * dpi, Color.Black, bold = false, maxWidth = 6f * dpi)
        if (view.id == highlightId) {
            val fp = Sheets.footprint(view, state)!!
            drawRect(Color(0xFFFF8F00), topLeft = Offset(fp.left * dpi, fp.top * dpi), size = Size(fp.width * dpi, fp.height * dpi), style = Stroke(lineW * 2f))
        }
    }
    // Border round the drawing area and the strip.
    val m = Sheets.MARGIN_IN * dpi
    drawRect(Color.Black, topLeft = Offset(m, m), size = Size(wIn * dpi - 2f * m, hIn * dpi - 2f * m), style = Stroke(lineW * 2f))
    drawSheetTitleBlock(sheet, dpi, project, date, lineW)
}

/** The strip down the right edge: project, sheet title, drawn by, date, scale, and the sheet number big at the bottom. */
private fun DrawScope.drawSheetTitleBlock(sheet: SheetLayout, dpi: Float, project: String, date: String, lineW: Float) {
    val (wIn, hIn) = Sheets.paperIn(sheet)
    val strip = Sheets.stripIn(sheet)
    val m = Sheets.MARGIN_IN
    val left = (wIn - m - strip) * dpi
    val right = (wIn - m) * dpi
    val top = m * dpi
    val bottom = (hIn - m) * dpi
    drawLine(Color.Black, Offset(left, top), Offset(left, bottom), strokeWidth = lineW * 2f)
    val pad = 0.08f * dpi
    val caption = 0.09f * dpi
    val value = 0.16f * dpi
    val width = right - left - 2f * pad
    val cells = listOf(
        "PROJECT" to project,
        "SHEET TITLE" to sheet.title,
        "DRAWN BY" to sheet.drawnBy,
        "DATE" to date,
        "SCALE" to "As noted"
    )
    val cellH = 0.6f * dpi
    val numberH = 1.4f * dpi
    var y = bottom - numberH - cells.size * cellH
    for ((cap, v) in cells) {
        drawLine(Color.Black, Offset(left, y), Offset(right, y), strokeWidth = lineW)
        drawPageText(cap, Offset(left + pad, y + pad), caption, Color.DarkGray, bold = false, maxWidth = width)
        drawPageText(v, Offset(left + pad, y + pad + caption * 1.6f), value, Color.Black, bold = true, maxWidth = width)
        y += cellH
    }
    drawLine(Color.Black, Offset(left, y), Offset(right, y), strokeWidth = lineW * 2f)
    drawPageText("SHEET", Offset(left + pad, y + pad), caption, Color.DarkGray, bold = false, maxWidth = width)
    drawPageText(sheet.number, Offset(left + pad, y + pad + caption * 1.8f), 0.6f * dpi, Color.Black, bold = true, maxWidth = width)
}
