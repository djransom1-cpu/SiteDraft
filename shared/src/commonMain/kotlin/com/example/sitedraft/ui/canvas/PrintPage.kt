package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import com.example.sitedraft.util.formatFixed
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Printing and PDF export share one page: the paper, the drawing scale on it and the title block
 * are worked out here, in paper inches and page pixels, so a 1/4" = 1'-0" print is true on paper
 * whatever screen it was made on. The platform only has to put [PageLayout.widthPx] x
 * [PageLayout.heightPx] pixels onto [PageLayout.dpi]-per-inch paper.
 */

/** Standard sheets, named the way they are bought. Sizes are the portrait short x long edge. */
enum class PaperSize(val label: String, val shortIn: Float, val longIn: Float) {
    LETTER("Letter 8½×11", 8.5f, 11f),
    LEGAL("Legal 8½×14", 8.5f, 14f),
    TABLOID("Tabloid 11×17", 11f, 17f),
    ARCH_C("Arch C 18×24", 18f, 24f),
    ARCH_D("Arch D 24×36", 24f, 36f);
}

enum class PageOrientation(val label: String) { PORTRAIT("Portrait"), LANDSCAPE("Landscape") }

/**
 * The drawing scale on paper. [denominator] is the ratio's right-hand side: 1/4" = 1'-0" means one
 * paper inch stands for 48 real inches (1:48). null = fit the subject to the sheet, not to scale.
 */
enum class PrintScale(val label: String, val denominator: Int?) {
    FIT("Fit to page", null),
    EIGHTH("1/8\" = 1'-0\"", 96),
    QUARTER("1/4\" = 1'-0\"", 48),
    HALF("1/2\" = 1'-0\"", 24),
    THREE_QUARTER("3/4\" = 1'-0\"", 16),
    ONE("1\" = 1'-0\"", 12);
}

enum class PrintSubject(val label: String) {
    WHOLE_DRAWING("Whole drawing"),
    CURRENT_VIEW("Current view"),
    SELECTED_ELEVATION("Selected elevation");
}

data class PrintSettings(
    val subject: PrintSubject = PrintSubject.WHOLE_DRAWING,
    val paper: PaperSize = PaperSize.LETTER,
    val orientation: PageOrientation = PageOrientation.LANDSCAPE,
    val scale: PrintScale = PrintScale.FIT,
    /** Blank = a default from the subject ("Floor Plan", "Elevation"). */
    val sheetTitle: String = ""
)

/** What goes in the strip along the bottom of the sheet. */
data class TitleBlock(
    val project: String,
    val sheet: String,
    val date: String,
    val scale: String
)

/**
 * A composed page: the sheet in pixels at [dpi], where the drawing sits on it and the pan/zoom
 * that puts world pixels there. [zoom] is page pixels per world pixel.
 */
data class PageLayout(
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Float,
    /** The drawing area in page px: inside the margins, above the title block. */
    val content: Rect,
    val titleBlock: Rect,
    val zoom: Float,
    val pan: Offset,
    val scaleText: String,
    /** The subject's size on paper at this scale, in inches. */
    val drawingWidthIn: Float,
    val drawingHeightIn: Float
) {
    val paperWidthIn: Float get() = widthPx / dpi
    val paperHeightIn: Float get() = heightPx / dpi
    val landscape: Boolean get() = widthPx > heightPx
    /** Whether the whole subject lands inside the drawing area. */
    val fits: Boolean get() = drawingWidthIn <= content.width / dpi + 0.001f && drawingHeightIn <= content.height / dpi + 0.001f
    /** How long [worldPx] world pixels come out on paper, in inches. */
    fun inchesOnPaper(worldPx: Float): Float = worldPx * zoom / dpi
}

object PrintPage {
    /** Page margin all round, and the height of the title block strip, in inches. */
    const val MARGIN_IN = 0.4f
    const val TITLE_BLOCK_IN = 0.7f
    /** Breathing room between the drawing area's border and a fitted drawing. */
    private const val FIT_PAD_IN = 0.15f
    private const val MAX_DPI = 150f
    /** Caps the page bitmap (Arch D at 150 dpi would be 19 Mpx; the web and desktop paths rasterise). */
    private const val MAX_PIXELS = 12_000_000f

    fun dpiFor(paper: PaperSize): Float =
        min(MAX_DPI, floor(sqrt(MAX_PIXELS / (paper.shortIn * paper.longIn))))

    /** Paper width and height in inches, the orientation applied. */
    fun paperInches(settings: PrintSettings): Pair<Float, Float> =
        if (settings.orientation == PageOrientation.LANDSCAPE) settings.paper.longIn to settings.paper.shortIn
        else settings.paper.shortIn to settings.paper.longIn

    /**
     * World pixels per real-world inch for this drawing: the project scale is "1' = N px"
     * (imperial) or "1 m = N px" (metric).
     */
    fun worldPxPerInch(state: CanvasState): Float =
        if (state.unitSystem == UnitSystem.METRIC) state.scale.ratio / 39.3701f else state.scale.ratio / 12f

    /** The scale as it reads in the title block. */
    fun scaleText(scale: PrintScale, metric: Boolean): String {
        val d = scale.denominator ?: return "NTS (fit to page)"
        return if (metric) "1:$d" else "${scale.label} (1:$d)"
    }

    /**
     * Lays the page out. [worldBounds] is the subject in world px (null for an empty drawing);
     * [worldPxPerInch] from [worldPxPerInch]. The subject is centred in the drawing area; at a
     * fixed scale it may run past the area's edges, which [PageLayout.fits] reports.
     */
    fun layout(
        settings: PrintSettings,
        worldBounds: Rect?,
        worldPxPerInch: Float,
        metric: Boolean = false,
        dpi: Float = dpiFor(settings.paper)
    ): PageLayout {
        val (wIn, hIn) = paperInches(settings)
        val widthPx = (wIn * dpi).roundToInt()
        val heightPx = (hIn * dpi).roundToInt()
        val margin = MARGIN_IN * dpi
        val titleH = TITLE_BLOCK_IN * dpi
        val titleBlock = Rect(margin, heightPx - margin - titleH, widthPx - margin, heightPx - margin)
        val content = Rect(margin, margin, widthPx - margin, titleBlock.top)

        val bounds = worldBounds?.takeIf { it.width > 0f || it.height > 0f } ?: Rect(0f, 0f, 1f, 1f)
        val denominator = settings.scale.denominator
        val zoom = if (denominator == null) {
            val pad = FIT_PAD_IN * dpi
            min(
                (content.width - 2f * pad) / max(bounds.width, 1f),
                (content.height - 2f * pad) / max(bounds.height, 1f)
            )
        } else {
            // One paper inch (dpi page px) covers `denominator` real inches, each worldPxPerInch px.
            dpi / denominator / worldPxPerInch
        }
        val pan = content.center - bounds.center * zoom
        return PageLayout(
            widthPx = widthPx,
            heightPx = heightPx,
            dpi = dpi,
            content = content,
            titleBlock = titleBlock,
            zoom = zoom,
            pan = pan,
            scaleText = scaleText(settings.scale, metric),
            drawingWidthIn = bounds.width * zoom / dpi,
            drawingHeightIn = bounds.height * zoom / dpi
        )
    }

    /** The elements the subject prints, or null when there is nothing to print (no elevation selected). */
    fun subjectElements(state: CanvasState, subject: PrintSubject): List<DrawingElement>? = when (subject) {
        PrintSubject.SELECTED_ELEVATION -> selectedElevation(state)?.let { listOf(it) }
        else -> state.elements
    }

    /** The subject's extent in world px, or null for an empty drawing. */
    fun subjectBounds(state: CanvasState, subject: PrintSubject): Rect? = when (subject) {
        PrintSubject.WHOLE_DRAWING -> union(state.elements.map { it.boundingRect() })
        PrintSubject.CURRENT_VIEW -> {
            val v = state.viewportSize
            if (v == Size.Zero) union(state.elements.map { it.boundingRect() })
            else Rect(
                -state.pan.x / state.zoom, -state.pan.y / state.zoom,
                (v.width - state.pan.x) / state.zoom, (v.height - state.pan.y) / state.zoom
            )
        }
        PrintSubject.SELECTED_ELEVATION -> selectedElevation(state)?.let { view ->
            // The frame plus whatever the live view draws outside it (dimension strings).
            union(listOf(view.frame) + Elevation.partsOf(view, state).map { it.boundingRect() })
        }
    }

    /** Dimension text height on paper, in inches (1/8", the usual lettering height on a plan). */
    const val LABEL_HEIGHT_IN = 0.125f
    /** The renderer's on-screen dimension label height, in screen px, that [LABEL_HEIGHT_IN] replaces. */
    private const val SCREEN_LABEL_PX = 36f

    /** [CanvasState.labelScale] for a page at [dpi]: 36 screen px become 1/8" of paper. */
    fun labelScale(dpi: Float): Float = (dpi * LABEL_HEIGHT_IN) / SCREEN_LABEL_PX

    /**
     * The state the page is drawn from: only the subject's [elements], the page's pan/zoom (the
     * renderer reads them for screen-constant sizes and grid extents), labels sized for paper,
     * and no interactive state (selection, in-progress element, snap and move markers). The
     * page is paper, so its background is white whatever the screen's canvas colour: the
     * renderer judges light vs dark by the background and draws every line black on white, where
     * a blueprint-blue page state came out as light cyan lines on the white sheet.
     */
    fun pageState(state: CanvasState, elements: List<DrawingElement>, layout: PageLayout): CanvasState = state.copy(
        elements = elements.map { inkForPaper(it) },
        zoom = layout.zoom,
        pan = layout.pan,
        backgroundColor = Color.White,
        labelScale = labelScale(layout.dpi),
        selectedElementId = null,
        selectedElementIds = emptySet(),
        currentDrawingElement = null,
        lastSnapPoint = null,
        boxSelectStart = null,
        boxSelectEnd = null,
        preciseMoveFromPoint = null,
        placementPreview = null
    )

    /** Colours at least this bright are white-ish pen strokes meant for a dark canvas. */
    private const val PAPER_INK_LUMINANCE = 0.6f

    /**
     * An element as it prints: a light stroke (the white pen on a blueprint canvas) becomes black,
     * since on white paper it would vanish; dark and mid colours (red-lined changes, a blue
     * highlighter) print as drawn. Symbols and labels pick their own paper colours in the renderer.
     */
    fun inkForPaper(element: DrawingElement): DrawingElement = when {
        element is DrawingElement.Freehand && element.isHighlighter -> element
        element.color.luminance() > PAPER_INK_LUMINANCE -> element.withColor(Color.Black)
        else -> element
    }

    fun selectedElevation(state: CanvasState): DrawingElement.ElevationView? =
        state.elements.firstOrNull { it.id == state.selectedElementId } as? DrawingElement.ElevationView

    fun defaultSheetTitle(subject: PrintSubject): String = when (subject) {
        PrintSubject.SELECTED_ELEVATION -> "Elevation"
        else -> "Floor Plan"
    }

    private fun union(rects: List<Rect>): Rect? = rects.reduceOrNull { acc, r ->
        Rect(minOf(acc.left, r.left), minOf(acc.top, r.top), maxOf(acc.right, r.right), maxOf(acc.bottom, r.bottom))
    }

    /** "6.0" x 4.3"" style size text for the dialog. */
    fun inchesText(value: Float): String = formatFixed(value.toDouble(), 1) + "\""
}

/**
 * Draws the whole sheet: white paper, the drawing area filled with the canvas colour and clipped
 * to its border, the document through [drawDocumentContents] at the page's pan/zoom, and the
 * title block. [pageState] must already carry [PageLayout.zoom] and [PageLayout.pan] (the
 * renderer reads them for screen-constant sizes and grid extents) and only the subject's elements.
 */
fun DrawScope.drawPrintPage(layout: PageLayout, pageState: CanvasState, titleBlock: TitleBlock) {
    val dpi = layout.dpi
    val lineW = max(1f, dpi / 100f)
    drawRect(Color.White)
    clipRect(layout.content.left, layout.content.top, layout.content.right, layout.content.bottom) {
        drawRect(pageState.backgroundColor, topLeft = layout.content.topLeft, size = layout.content.size)
        withTransform(
            transformBlock = {
                translate(layout.pan.x, layout.pan.y)
                scale(layout.zoom, layout.zoom, Offset.Zero)
            }
        ) {
            drawDocumentContents(pageState)
        }
    }
    drawRect(Color.Black, topLeft = layout.content.topLeft, size = layout.content.size, style = Stroke(lineW))
    drawTitleBlock(layout, titleBlock, lineW)
}

private fun DrawScope.drawTitleBlock(layout: PageLayout, block: TitleBlock, lineW: Float) {
    val r = layout.titleBlock
    val dpi = layout.dpi
    drawRect(Color.White, topLeft = r.topLeft, size = r.size)
    drawRect(Color.Black, topLeft = r.topLeft, size = r.size, style = Stroke(lineW))
    // Four cells: the project gets the most room, then sheet, date and scale.
    val weights = listOf(0.40f, 0.28f, 0.14f, 0.18f)
    val cells = listOf("PROJECT" to block.project, "SHEET" to block.sheet, "DATE" to block.date, "SCALE" to block.scale)
    var x = r.left
    val captionPx = 0.09f * dpi
    val valuePx = 0.16f * dpi
    val pad = 0.06f * dpi
    cells.forEachIndexed { i, (caption, value) ->
        val w = r.width * weights[i]
        if (i > 0) drawLine(Color.Black, Offset(x, r.top), Offset(x, r.bottom), strokeWidth = lineW)
        drawPageText(caption, Offset(x + pad, r.top + pad), captionPx, Color.DarkGray, bold = false, maxWidth = w - 2 * pad)
        drawPageText(value, Offset(x + pad, r.top + pad + captionPx * 1.5f), valuePx, Color.Black, bold = true, maxWidth = w - 2 * pad)
        x += w
    }
}

/** One line of page text anchored at its top-left, clipped to [maxWidth]. */
private fun DrawScope.drawPageText(text: String, topLeft: Offset, sizePx: Float, color: Color, bold: Boolean, maxWidth: Float) {
    val measurer = TextDrawing.measurer ?: return
    val style = TextStyle(color = color, fontSize = sizePx.toSp(), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    val layout = measurer.measure(AnnotatedString(text), style, maxLines = 1, softWrap = false)
    clipRect(topLeft.x, topLeft.y, topLeft.x + maxWidth, topLeft.y + layout.size.height) {
        drawText(layout, topLeft = topLeft)
    }
}
