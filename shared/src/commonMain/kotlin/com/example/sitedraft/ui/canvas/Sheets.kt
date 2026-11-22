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
enum class SheetViewKind { PLAN, ELEVATION,
    /** The typical wall section (WallSection), drawn from the building inputs and the plan. */
    SECTION }

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
    val yIn: Float = 0f,
    /** A plan view's layers (Building.kt): null shows every layer, as views did before layers. */
    val layerIds: List<String>? = null,
    /**
     * A cropped viewport (Dan, Nov 22): only this window of the model (world px) shows, at the
     * view's own scale, e.g. the kitchen at 1/2" beside the whole plan at 1/4". Null = the whole subject.
     */
    val crop: ViewCrop? = null
)

/** A viewport's window on the model, world px. */
@Serializable
data class ViewCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val rect: Rect get() = Rect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

    companion object {
        fun of(r: Rect) = ViewCrop(r.left, r.top, r.right, r.bottom)
    }
}

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

    /**
     * The model-space section a section view shows: the one it was made from, or (a view from
     * before 1.0.36, or one whose section was deleted and placed again) the drawing's section.
     * Null when the drawing has none: the view then draws the section straight from the inputs.
     */
    fun sectionFor(view: SheetView, state: CanvasState): DrawingElement.Symbol? {
        if (view.kind != SheetViewKind.SECTION) return null
        return (state.elements.firstOrNull { it.id == view.elementId } as? DrawingElement.Symbol)?.takeIf { WallSection.isSection(it) }
            ?: WallSection.sectionIn(state)
    }

    /** The elements a view shows. */
    fun viewElements(view: SheetView, state: CanvasState): List<DrawingElement> = when (view.kind) {
        SheetViewKind.PLAN -> {
            val elevations = elevationIds(state)
            val sections = WallSection.sectionIds(state)
            val layers = view.layerIds?.toSet()
            val of = Layers.resolver(state)
            state.elements.filter { e ->
                e !is DrawingElement.ElevationView && !(e is DrawingElement.Dimension && e.ownerId != null && e.ownerId in elevations) &&
                    e.id !in sections && (layers == null || of(e).id in layers)
            }
        }
        SheetViewKind.ELEVATION -> state.elements.filter { it.id == view.elementId || it.isOwnedBy(view.elementId) }
        SheetViewKind.SECTION -> sectionFor(view, state)?.let { sec -> listOf(sec) + WallSection.ownedBy(sec, state) } ?: emptyList()
    }

    /** The view's extent in world px (null when it shows nothing, e.g. its elevation was deleted). A cropped view is its window. */
    fun worldBounds(view: SheetView, state: CanvasState): Rect? {
        view.crop?.rect?.takeIf { it.width > 0f && it.height > 0f }?.let { return it }
        return fullWorldBounds(view, state)
    }

    /** The whole subject's extent in world px, ignoring any crop: what the crop window is picked from. */
    fun fullWorldBounds(view: SheetView, state: CanvasState): Rect? {
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
        if (view.kind == SheetViewKind.SECTION && sectionFor(view, state) == null) {
            val (w, h) = WallSection.paperSizeIn(state, view.scale.denominator ?: 16)
            return Rect(view.xIn - w / 2f, view.yIn - h / 2f, view.xIn + w / 2f, view.yIn + h / 2f)
        }
        val b = worldBounds(view, state) ?: return null
        val k = inchesPerWorldPx(view, state)
        val w = b.width * k
        val h = b.height * k
        return Rect(view.xIn - w / 2f, view.yIn - h / 2f, view.xIn + w / 2f, view.yIn + h / 2f)
    }

    /** The view with its title under it (inches): what has to fit on the sheet. */
    fun footprint(view: SheetView, state: CanvasState): Rect? =
        viewRect(view, state)?.let { Rect(it.left, it.top, it.right, it.bottom + TITLE_ROOM_IN) }

    /**
     * Plan views that can be put on a sheet, by layer: the whole model, then a plan for each level
     * that has layers (the foundation plan, each floor's framing plan, each floor plan with its
     * cabinets and furniture, the roof framing plan), then the site plan.
     */
    fun planChoices(state: CanvasState): List<Pair<String, List<String>?>> {
        val layers = state.effectiveLayers
        val out = mutableListOf<Pair<String, List<String>?>>("Floor plan (all layers)" to null)
        for (level in state.levels) {
            val on = layers.filter { it.levelId == level.id }
            if (on.isEmpty()) continue
            val title = when (level.kind) {
                LevelKind.FOUNDATION -> "Foundation plan"
                LevelKind.FLOOR_FRAMING -> "${level.name} plan"
                LevelKind.FLOOR -> "${level.name} plan"
                LevelKind.ROOF -> "Roof framing plan"
                else -> "${level.name} plan"
            }
            out += title to on.map { it.id }
        }
        layers.filter { it.levelId == null && it.trade != LayerTrade.EXTERIOR_ELEVATION }.forEach { out += "${it.name}" to listOf(it.id) }
        return out
    }

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
        if (view.kind == SheetViewKind.SECTION && Sheets.sectionFor(view, state) == null) {
            // No section in model space yet: drawn straight from the building inputs, at its scale, with its title under it.
            val rIn = Sheets.viewRect(view, state) ?: continue
            with(WallSection) { drawSection(state, Offset(view.xIn * dpi, view.yIn * dpi), dpi, view.scale.denominator ?: 16) }
            val titleTop = rIn.bottom * dpi + 0.12f * dpi
            val titleSize = 0.2f * dpi
            drawPageText(view.title.uppercase(), Offset(rIn.left * dpi, titleTop), titleSize, Color.Black, bold = true, maxWidth = max(rIn.width * dpi, 3f * dpi))
            drawLine(Color.Black, Offset(rIn.left * dpi, titleTop + titleSize * 1.3f), Offset((rIn.left + minOf(max(rIn.width, 2f), 6f)) * dpi, titleTop + titleSize * 1.3f), strokeWidth = lineW * 1.5f)
            drawPageText("SCALE: ${PrintPage.scaleText(view.scale, metric)}", Offset(rIn.left * dpi, titleTop + titleSize * 1.5f), 0.12f * dpi, Color.Black, bold = false, maxWidth = 6f * dpi)
            if (view.id == highlightId) {
                val fp = Sheets.footprint(view, state)!!
                drawRect(Color(0xFFFF8F00), topLeft = Offset(fp.left * dpi, fp.top * dpi), size = Size(fp.width * dpi, fp.height * dpi), style = Stroke(lineW * 2f))
            }
            continue
        }
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
            if (e is DrawingElement.ElevationView) Elevation.partsOf(e, state.copy(backgroundColor = Color.White)) else listOf(e)
        }
        val pageState = PrintPage.pageState(state, shown, page).copy(background = DrawingBackground.PLAIN)
        // A cropped viewport is cut exactly at its window; a whole view gets a little room for line ends and labels.
        val pad = if (view.crop != null) 0f else 0.1f * dpi
        clipRect(rIn.left * dpi - pad, rIn.top * dpi - pad, rIn.right * dpi + pad, rIn.bottom * dpi + pad) {
            withTransform({
                translate(pan.x, pan.y)
                scale(zoom, zoom, Offset.Zero)
            }) { drawDocumentContents(pageState) }
        }
        if (view.crop != null) {
            drawRect(Color.Black, topLeft = Offset(rIn.left * dpi, rIn.top * dpi), size = Size(rIn.width * dpi, rIn.height * dpi), style = Stroke(lineW * 0.8f))
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
    drawSheetTitleBlock(sheet, dpi, project, date, state.projectInfo, lineW)
}

/**
 * The strip down the right edge, filled from the project info (ProjectInfo.kt): the company
 * across the top, the revisions under it, then project, client, job address, sheet title, drawn
 * by, date started and scale, and the sheet number big at the bottom.
 */
private fun DrawScope.drawSheetTitleBlock(sheet: SheetLayout, dpi: Float, project: String, date: String, info: ProjectInfo, lineW: Float) {
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

    // The cells up from the sheet number. The address takes a line for each line typed (up to 3).
    val address = info.address.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(3).ifEmpty { listOf("") }
    val cells: List<Pair<String, List<String>>> = listOf(
        "PROJECT" to listOf(project),
        "CLIENT" to listOf(info.clientName),
        "JOB ADDRESS" to address,
        "SHEET TITLE" to listOf(sheet.title),
        "DRAWN BY" to listOf(sheet.drawnBy.ifBlank { info.drawnBy }),
        "DATE STARTED" to listOf(info.startDate.ifBlank { date }),
        "SCALE" to listOf("As noted")
    )
    val lineH = value * 1.25f
    fun cellH(lines: Int) = 0.6f * dpi + (lines - 1) * lineH
    val numberH = 1.4f * dpi
    val cellsTop = bottom - numberH - cells.sumOf { cellH(it.second.size).toDouble() }.toFloat()
    var y = cellsTop
    for ((cap, lines) in cells) {
        drawLine(Color.Black, Offset(left, y), Offset(right, y), strokeWidth = lineW)
        drawPageText(cap, Offset(left + pad, y + pad), caption, Color.DarkGray, bold = false, maxWidth = width)
        lines.forEachIndexed { i, v ->
            drawPageText(v, Offset(left + pad, y + pad + caption * 1.6f + i * lineH), value, Color.Black, bold = true, maxWidth = width)
        }
        y += cellH(lines.size)
    }
    drawLine(Color.Black, Offset(left, y), Offset(right, y), strokeWidth = lineW * 2f)
    drawPageText("SHEET", Offset(left + pad, y + pad), caption, Color.DarkGray, bold = false, maxWidth = width)
    drawPageText(sheet.number, Offset(left + pad, y + pad + caption * 1.8f), 0.6f * dpi, Color.Black, bold = true, maxWidth = width)

    // The company across the top.
    var ty = top
    if (info.company.isNotBlank()) {
        drawPageText(info.company, Offset(left + pad, ty + pad), 0.2f * dpi, Color.Black, bold = true, maxWidth = width)
        ty += 0.2f * dpi + 3f * pad
        drawLine(Color.Black, Offset(left, ty), Offset(right, ty), strokeWidth = lineW * 2f)
    }
    // Revisions: number, date and what changed, newest last, as many as fit above the cells.
    if (info.revisions.isNotEmpty()) {
        val small = 0.1f * dpi
        val rowH = 0.26f * dpi
        drawPageText("REVISIONS", Offset(left + pad, ty + pad), caption, Color.DarkGray, bold = false, maxWidth = width)
        ty += caption + 2f * pad
        val noW = 0.35f * dpi
        val dateW = 0.85f * dpi
        drawLine(Color.Black, Offset(left, ty), Offset(right, ty), strokeWidth = lineW)
        val room = ((cellsTop - pad - ty) / rowH).toInt().coerceAtLeast(0)
        val shown = info.revisions.takeLast(room)
        val tableTop = ty
        for (r in shown) {
            drawPageText(r.number, Offset(left + pad, ty + pad), small, Color.Black, bold = true, maxWidth = noW - pad)
            drawPageText(r.date, Offset(left + noW + pad, ty + pad), small, Color.Black, bold = false, maxWidth = dateW - pad)
            drawPageText(r.description, Offset(left + noW + dateW + pad, ty + pad), small, Color.Black, bold = false, maxWidth = right - left - noW - dateW - 2f * pad)
            ty += rowH
            drawLine(Color.Black, Offset(left, ty), Offset(right, ty), strokeWidth = lineW * 0.6f)
        }
        if (shown.isNotEmpty()) {
            drawLine(Color.Black, Offset(left + noW, tableTop), Offset(left + noW, ty), strokeWidth = lineW * 0.6f)
            drawLine(Color.Black, Offset(left + noW + dateW, tableTop), Offset(left + noW + dateW, ty), strokeWidth = lineW * 0.6f)
        }
    }
}
