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
    SECTION,
    /** The door and window schedules (Schedules), read off the plan. */
    DOOR_SCHEDULE, WINDOW_SCHEDULE,
    /** The structural calc summary (StructuralDesign): loads, spans, members, footings, for the S sheets. */
    STRUCTURAL_CALCS }

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
    val crop: ViewCrop? = null,
    /** A schedule's size on the paper: 1 = normal (1/8" lettering), see Schedules.sizeChoices. */
    val tableSize: Float = 1f,
    /** A plan view's remodel phase (Remodel.kt): Existing, Demo or Proposed plan; All shows every wall in its own style. */
    val phase: RemodelPhase = RemodelPhase.ALL
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
    val views: List<SheetView> = emptyList(),
    /** The number was typed by hand: the set's renumbering leaves it alone (cleared = automatic again). */
    val numberManual: Boolean = false
)

object Sheets {
    const val MARGIN_IN = 0.5f
    /** The title block strip down the right edge. */
    fun stripIn(sheet: SheetLayout): Float = if (paperIn(sheet).first < 20f) 1.75f else 2.5f
    /**
     * A drawing's number on its sheet (Nov 23): 1, 2, 3 in the sheet's order, tables (schedules,
     * calcs) not counted. It goes in the bubble by the drawing's title, and callouts point to it.
     */
    fun viewNumber(sheet: SheetLayout, view: SheetView): Int? {
        if (isTable(view)) return null
        val i = sheet.views.filter { !isTable(it) }.indexOfFirst { it.id == view.id }
        return if (i < 0) null else i + 1
    }

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

    /**
     * A sheet's own notes layer (Dan, Nov 22): notes and dimensions only one viewport shows, on
     * top of the live layers (the foundation stays one layer, built from the plan). Shown only by
     * a viewport that lists it, never by an "all layers" view.
     */
    const val SHEET_NOTES_PREFIX = "sheetnotes-"
    fun isSheetNotes(layerId: String): Boolean = layerId.startsWith(SHEET_NOTES_PREFIX)

    /** The elements a view shows. */
    fun viewElements(view: SheetView, state: CanvasState): List<DrawingElement> = when (view.kind) {
        SheetViewKind.PLAN -> {
            val elevations = elevationIds(state)
            val sections = WallSection.sectionIds(state)
            val layers = view.layerIds?.toSet()
            val of = Layers.resolver(state)
            state.elements.filter { e ->
                val layerId = of(e).id
                e !is DrawingElement.ElevationView && !(e is DrawingElement.Dimension && e.ownerId != null && e.ownerId in elevations) &&
                    e.id !in sections && !ExteriorElevation.isExterior(e) && !DetailKit.isDetail(e) &&
                    Remodel.shows(e, view.phase) &&
                    // "All layers" is the building's plan: the roof plans and the site plan have their own sheets (Nov 22).
                    (if (layers == null) !isSheetNotes(layerId) && !RoofPlan.isRoof(e) && !RoofPlan.isRoofFinish(e) && !SiteLot.isLot(e) else layerId in layers)
            }
        }
        SheetViewKind.ELEVATION -> state.elements.filter { it.id == view.elementId || it.isOwnedBy(view.elementId) }
        SheetViewKind.SECTION -> sectionFor(view, state)?.let { sec -> listOf(sec) + WallSection.ownedBy(sec, state) } ?: emptyList()
        SheetViewKind.DOOR_SCHEDULE, SheetViewKind.WINDOW_SCHEDULE, SheetViewKind.STRUCTURAL_CALCS -> emptyList()
    }

    /** The schedule a view is, if it is one. */
    /** A table (a schedule or the structural calcs): its own size on paper, at no scale. */
    fun isTable(view: SheetView): Boolean = scheduleKind(view) != null || view.kind == SheetViewKind.STRUCTURAL_CALCS

    fun scheduleKind(view: SheetView): Schedules.Kind? = when (view.kind) {
        SheetViewKind.DOOR_SCHEDULE -> Schedules.Kind.DOOR
        SheetViewKind.WINDOW_SCHEDULE -> Schedules.Kind.WINDOW
        else -> null
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
            // A roof plan reaches over the garage, wing and porch roofs drawn with it.
            else if (RoofPlan.isRoof(e) || RoofPlan.isRoofFinish(e)) listOf(e.boundingRect()) + RoofPlan.addOnBounds(state)
            else listOf(e.boundingRect())
        }.filter { it.width > 0f || it.height > 0f }
        return rects.reduceOrNull { a, r -> Rect(minOf(a.left, r.left), minOf(a.top, r.top), maxOf(a.right, r.right), maxOf(a.bottom, r.bottom)) }
    }

    /** Paper inches per world px at the view's scale. */
    fun inchesPerWorldPx(view: SheetView, state: CanvasState): Float =
        1f / ((view.scale.denominator ?: 48) * PrintPage.worldPxPerInch(state))

    /** The view's drawing on the paper (inches), centred on its position. */
    fun viewRect(view: SheetView, state: CanvasState): Rect? {
        if (view.kind == SheetViewKind.STRUCTURAL_CALCS) {
            val sz = StructuralDesign.tableSizeIn(state, view.tableSize)
            return Rect(view.xIn - sz.width / 2f, view.yIn - sz.height / 2f, view.xIn + sz.width / 2f, view.yIn + sz.height / 2f)
        }
        scheduleKind(view)?.let { kind ->
            // A table: its own size on paper, whatever the scale.
            val sz = Schedules.tableSizeIn(kind, state, view.tableSize)
            return Rect(view.xIn - sz.width / 2f, view.yIn - sz.height / 2f, view.xIn + sz.width / 2f, view.yIn + sz.height / 2f)
        }
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
        val out = mutableListOf<Pair<String, List<String>?>>()
        for (level in state.levels) {
            val on = layers.filter { it.levelId == level.id && !isSheetNotes(it.id) }
            if (on.isEmpty()) continue
            if (level.kind == LevelKind.ROOF) {
                // The roof framing (S sheets) and the roof plan with its roofing (A sheets) are separate views.
                on.filter { it.trade != LayerTrade.ROOF_PLAN }.takeIf { it.isNotEmpty() }?.let { out += "Roof framing plan" to it.map { l -> l.id } }
                on.filter { it.trade == LayerTrade.ROOF_PLAN }.takeIf { it.isNotEmpty() }?.let { out += "Roof plan" to it.map { l -> l.id } }
                continue
            }
            val title = when (level.kind) {
                LevelKind.FOUNDATION -> "Foundation plan"
                LevelKind.FLOOR_FRAMING -> "${level.name} plan"
                LevelKind.FLOOR -> "${level.name} plan"
                else -> "${level.name} plan"
            }
            out += title to on.map { it.id }
        }
        layers.filter { it.levelId == null && it.trade != LayerTrade.EXTERIOR_ELEVATION && !isSheetNotes(it.id) }.forEach { out += "${it.name}" to listOf(it.id) }
        // The floor plans first (a plan sheet shouldn't pick up the foundation and framing by
        // accident, Dan Nov 22), then the rest up the stack, "all layers" last.
        val plans = out.filter { (label, _) -> label.endsWith("floor plan") && !label.contains("framing") }
        return plans + (out - plans.toSet()) + ("Floor plan (all layers)" to null)
    }

    /** The exterior elevations in model space (south, north, east, west), as views could be made of them. */
    fun exteriorChoices(state: CanvasState): List<Pair<DrawingElement.Symbol, String>> =
        ExteriorElevation.all(state).map { it to ExteriorElevation.faceOf(it).title }

    /**
     * Where a sheet goes in the set (Dan, Nov 22): its series and its place in that series.
     * Series print in [SERIES_ORDER]:
     *  - T: T-1 cover (with the legend and symbols), T-2 notes
     *  - C: C-1 site plan
     *  - S: foundation (always S-1), floor framing floor by floor, roof framing, sections, structural details
 *    (beam pockets, column connections, headers, stairs)
     *  - A: floor plans floor by floor, the roof plan, exterior elevations, wall details,
     *    interior elevations, door and window schedules, finish schedules
     *  - M, E, P: mechanical, electrical, plumbing (later)
     */
    data class Slot(val series: String, val rank: Int)

    val SERIES_ORDER = listOf("T", "C", "S", "A", "M", "E", "P")

    /** Floor number from a level id ("floor2", "floor2-framing"); 1 when it isn't a floor. */
    private fun floorNo(levelId: String?): Int =
        levelId?.removePrefix("floor")?.substringBefore("-")?.toIntOrNull() ?: 1

    /** A view's place in the set. */
    fun slotOf(view: SheetView, state: CanvasState): Slot = when (view.kind) {
        SheetViewKind.SECTION -> Slot("S", 400)
        SheetViewKind.STRUCTURAL_CALCS -> Slot("S", 450)
        SheetViewKind.DOOR_SCHEDULE -> Slot("A", 700)
        SheetViewKind.WINDOW_SCHEDULE -> Slot("A", 701)
        SheetViewKind.ELEVATION -> {
            val e = state.elements.firstOrNull { it.id == view.elementId }
            if (e is DrawingElement.Symbol && ExteriorElevation.isExterior(e)) Slot("A", 400 + ExteriorElevation.faceOf(e).ordinal)
            else if (e is DrawingElement.Symbol && DetailKit.isDetail(e)) DetailKit.kindOf(e).let { Slot(it.series, 500 + it.ordinal) }
            else Slot("A", 600)
        }
        SheetViewKind.PLAN -> {
            val layers = state.effectiveLayers.associateBy { it.id }
            val shown = view.layerIds?.filter { !isSheetNotes(it) }?.mapNotNull { layers[it] }
            when {
                shown.isNullOrEmpty() -> Slot("A", 101)
                shown.all { it.trade == LayerTrade.SITE_PLAN } -> Slot("C", 0)
                shown.all { it.trade == LayerTrade.FOUNDATION } -> Slot("S", 0)
                shown.all { it.trade == LayerTrade.FOUNDATION || it.trade == LayerTrade.FLOOR_STRUCTURAL } ->
                    Slot("S", 100 + floorNo(shown.firstOrNull { it.trade == LayerTrade.FLOOR_STRUCTURAL }?.levelId))
                shown.all { it.trade in setOf(LayerTrade.FOUNDATION, LayerTrade.FLOOR_STRUCTURAL, LayerTrade.ROOF_STRUCTURAL) } -> Slot("S", 300)
                else -> {
                    val level = shown.firstOrNull { it.trade == LayerTrade.FLOOR_PLAN || it.trade == LayerTrade.CABINETS || it.trade == LayerTrade.FURNITURE }?.levelId
                        ?: shown.first().levelId
                    if (level == Building.ROOF) Slot("A", 200) else Slot("A", 100 + floorNo(level))
                }
            }
        }
    }

    /** A sheet's place: its first view's, or for a sheet with no view yet, read from its title. */
    fun slotOf(sheet: SheetLayout, state: CanvasState): Slot {
        sheet.views.firstOrNull { v -> !(v.kind == SheetViewKind.PLAN && v.layerIds?.isNotEmpty() == true && v.layerIds.all { isSheetNotes(it) }) }
            ?.let { return slotOf(it, state) }
        val t = sheet.title.lowercase()
        return when {
            // The legend and symbols go on the cover (Dan, Nov 22).
            "cover" in t || "legend" in t || "symbol" in t -> Slot("T", 0)
            "note" in t -> Slot("T", 1)
            "site" in t -> Slot("C", 0)
            "section" in t -> Slot("S", 400)
            // Wall details are architectural (after the elevations); other details (beam pockets,
            // column connections, headers) are structural, after the sections.
            "wall detail" in t || "overhang" in t || "eave" in t -> Slot("A", 500)
            "detail" in t || "stair" in t -> Slot("S", 500)
            "interior elevation" in t -> Slot("A", 600)
            "schedule" in t && "finish" !in t -> Slot("A", 700)
            "finish" in t -> Slot("A", 800)
            "mechanical" in t || "hvac" in t -> Slot("M", 0)
            "electrical" in t -> Slot("E", 0)
            "plumbing" in t -> Slot("P", 0)
            else -> Slot("A", 900)
        }
    }

    /** The series a view's sheet goes in ("S", "A"...). */
    fun disciplineOf(view: SheetView, state: CanvasState): String = slotOf(view, state).series

    /**
     * The set in order and numbered (Dan, Nov 22): series by series, each sheet in its place, numbered
     * 1, 2, 3 in its series, so adding the foundation after the roof framing makes it S-1 and moves the
     * roof down. A number typed by hand stays as typed, and the others number around it.
     */
    fun renumbered(sheets: List<SheetLayout>, state: CanvasState): List<SheetLayout> {
        val slots = sheets.associate { it.id to slotOf(it, state) }
        fun seriesIndex(sh: SheetLayout) = SERIES_ORDER.indexOf(slots.getValue(sh.id).series).let { if (it < 0) SERIES_ORDER.size else it }
        val order = sheets.withIndex().sortedWith(
            compareBy<IndexedValue<SheetLayout>>({ seriesIndex(it.value) }, { slots.getValue(it.value.id).rank }, { it.index })
        ).map { it.value }
        val out = mutableListOf<SheetLayout>()
        for ((series, group) in order.groupBy { slots.getValue(it.id).series }) {
            val taken = group.filter { it.numberManual }.mapNotNull { numberIn(series, it.number) }.toSet()
            var n = 1
            for (sh in group) {
                if (sh.numberManual) { out += sh; continue }
                while (n in taken) n++
                out += if (sh.number == "$series-$n") sh else sh.copy(number = "$series-$n")
                n++
            }
        }
        return out
    }

    /** 3 for "S-3" in series "S"; null for another series or no number. */
    private fun numberIn(series: String, number: String): Int? =
        number.trim().takeIf { it.startsWith("$series-", ignoreCase = true) }?.substringAfter("-")?.trim()?.toIntOrNull()

    /** The next number in [prefix]'s series: one past the highest there ("S-3" after S-1 and S-2). */
    fun nextNumber(prefix: String, sheets: List<SheetLayout>): String {
        val used = sheets.mapNotNull { sh ->
            sh.number.trim().takeIf { it.startsWith("$prefix-", ignoreCase = true) }?.substringAfter("-")?.trim()?.toIntOrNull()
        }
        return "$prefix-${(used.maxOrNull() ?: 0) + 1}"
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

    // --- Working inside a viewport, as in AutoCAD's paper space (Dan, Nov 22) ---

    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** Smallest viewport a corner drag can make, paper inches. */
    const val MIN_VIEWPORT_IN = 0.5f

    /**
     * The model slid inside the viewport by [dIn] paper inches, as if dragged with the pointer:
     * the viewport stays where it is on the paper, at its scale; its window on the model moves the
     * other way. A whole view becomes a cropped one. Null when the view shows nothing.
     */
    fun slid(view: SheetView, dIn: Offset, state: CanvasState): SheetView? {
        val wr = worldBounds(view, state) ?: return null
        val k = inchesPerWorldPx(view, state)
        return view.copy(crop = ViewCrop.of(wr.translate(-dIn.x / k, -dIn.y / k)))
    }

    /** The corner of [view]'s viewport within [tolIn] paper inches of [pIn], if any. */
    fun cornerAt(view: SheetView, pIn: Offset, tolIn: Float, state: CanvasState): Corner? {
        val r = viewRect(view, state) ?: return null
        return listOf(
            Corner.TOP_LEFT to r.topLeft, Corner.TOP_RIGHT to r.topRight,
            Corner.BOTTOM_LEFT to r.bottomLeft, Corner.BOTTOM_RIGHT to r.bottomRight
        ).filter { (_, c) -> (c - pIn).getDistance() <= tolIn }.minByOrNull { (_, c) -> (c - pIn).getDistance() }?.first
    }

    /**
     * The viewport resized by dragging [corner] to [toIn] (paper inches): the opposite corner stays
     * put on the paper, the scale stays, and the window on the model grows or shrinks with it, so it
     * shows more or less of the model. Null when the view shows nothing.
     */
    fun resized(view: SheetView, corner: Corner, toIn: Offset, state: CanvasState): SheetView? {
        val pr = viewRect(view, state) ?: return null
        val wr = worldBounds(view, state) ?: return null
        val k = inchesPerWorldPx(view, state)
        val fixed = when (corner) {
            Corner.TOP_LEFT -> pr.bottomRight
            Corner.TOP_RIGHT -> pr.bottomLeft
            Corner.BOTTOM_LEFT -> pr.topRight
            Corner.BOTTOM_RIGHT -> pr.topLeft
        }
        val right = corner == Corner.TOP_RIGHT || corner == Corner.BOTTOM_RIGHT
        val bottom = corner == Corner.BOTTOM_LEFT || corner == Corner.BOTTOM_RIGHT
        val nx = if (right) maxOf(toIn.x, fixed.x + MIN_VIEWPORT_IN) else minOf(toIn.x, fixed.x - MIN_VIEWPORT_IN)
        val ny = if (bottom) maxOf(toIn.y, fixed.y + MIN_VIEWPORT_IN) else minOf(toIn.y, fixed.y - MIN_VIEWPORT_IN)
        val npr = Rect(minOf(fixed.x, nx), minOf(fixed.y, ny), maxOf(fixed.x, nx), maxOf(fixed.y, ny))
        fun toWorld(p: Offset) = Offset(wr.left + (p.x - pr.left) / k, wr.top + (p.y - pr.top) / k)
        val nwr = Rect(toWorld(npr.topLeft), toWorld(npr.bottomRight))
        return view.copy(crop = ViewCrop.of(nwr), xIn = npr.center.x, yIn = npr.center.y)
    }

    /** Viewports whose footprints overlap another's (1/8" of grace). */
    fun overlapping(sheet: SheetLayout, state: CanvasState): List<SheetView> {
        val fps = sheet.views.map { it to footprint(it, state)?.deflate(0.0625f) }
        return fps.filter { (v, f) -> f != null && fps.any { (o, g) -> o.id != v.id && g != null && f.overlaps(g) } }.map { it.first }
    }

    /** Every viewport laid out again in free spots, in order, from the top-left (Arrange). Sizes, scales and crops stay. */
    fun arranged(sheet: SheetLayout, state: CanvasState): SheetLayout {
        var out = sheet.copy(views = emptyList())
        for (v in sheet.views) out = out.copy(views = out.views + placed(out, v, state))
        return out
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
/** The drawing's number bubble left of its title (Nov 23); returns where the title text starts. */
private fun DrawScope.drawNumberBubble(sheet: SheetLayout, view: SheetView, left: Float, top: Float, dpi: Float, lineW: Float): Float {
    val n = Sheets.viewNumber(sheet, view) ?: return left
    with(Callouts) { drawTitleBubble(n, sheet.number, Offset(left, top - 0.04f * dpi), 0.42f * dpi, lineW) }
    return left + 0.52f * dpi
}

fun DrawScope.drawSheet(sheet: SheetLayout, state: CanvasState, dpi: Float, project: String, date: String, highlightId: String? = null) {
    val (wIn, hIn) = Sheets.paperIn(sheet)
    val lineW = max(1f, dpi / 100f)
    drawRect(Color.White, size = Size(wIn * dpi, hIn * dpi))
    val metric = state.unitSystem == UnitSystem.METRIC
    for (view in sheet.views) {
        if (Sheets.isTable(view)) {
            // A table (a door or window schedule, or the structural calcs): the table, its title under it (no scale: it isn't drawn to one).
            val rIn = Sheets.viewRect(view, state) ?: continue
            val kind = Sheets.scheduleKind(view)
            if (kind != null) with(Schedules) { drawTable(kind, state, Offset(rIn.left * dpi, rIn.top * dpi), dpi, view.tableSize) }
            else with(StructuralDesign) { drawCalcTable(state, Offset(rIn.left * dpi, rIn.top * dpi), dpi, view.tableSize) }
            val titleTop = rIn.bottom * dpi + 0.12f * dpi
            val titleSize = 0.2f * dpi
            drawPageText(view.title.uppercase(), Offset(rIn.left * dpi, titleTop), titleSize, Color.Black, bold = true, maxWidth = max(rIn.width * dpi, 3f * dpi))
            drawLine(Color.Black, Offset(rIn.left * dpi, titleTop + titleSize * 1.3f), Offset((rIn.left + minOf(max(rIn.width, 2f), 6f)) * dpi, titleTop + titleSize * 1.3f), strokeWidth = lineW * 1.5f)
            if (view.id == highlightId) {
                val fp = Sheets.footprint(view, state)!!
                drawRect(Color(0xFFFF8F00), topLeft = Offset(fp.left * dpi, fp.top * dpi), size = Size(fp.width * dpi, fp.height * dpi), style = Stroke(lineW * 2f))
            }
        }
        if (Sheets.isTable(view)) continue
        if (view.kind == SheetViewKind.SECTION && Sheets.sectionFor(view, state) == null) {
            // No section in model space yet: drawn straight from the building inputs, at its scale, with its title under it.
            val rIn = Sheets.viewRect(view, state) ?: continue
            with(WallSection) { drawSection(state, Offset(view.xIn * dpi, view.yIn * dpi), dpi, view.scale.denominator ?: 16) }
            val titleTop = rIn.bottom * dpi + 0.12f * dpi
            val titleSize = 0.2f * dpi
            val tx = drawNumberBubble(sheet, view, rIn.left * dpi, titleTop, dpi, lineW)
            drawPageText(view.title.uppercase(), Offset(tx, titleTop), titleSize, Color.Black, bold = true, maxWidth = max(rIn.width * dpi, 3f * dpi))
            drawLine(Color.Black, Offset(tx, titleTop + titleSize * 1.3f), Offset(tx + minOf(max(rIn.width, 2f), 6f) * dpi, titleTop + titleSize * 1.3f), strokeWidth = lineW * 1.5f)
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
        // The roof, section and exterior elevations are built from the whole drawing: drawn apart, with it.
        val live = shown.filter { isLiveSymbol(it) }.filterIsInstance<DrawingElement.Symbol>()
        val paperState = state.copy(backgroundColor = Color.White)
        val pageState = PrintPage.pageState(state, shown.filter { !isLiveSymbol(it) }, page).copy(background = DrawingBackground.PLAIN, remodelPhase = view.phase)
        // A cropped viewport is cut exactly at its window; a whole view gets a little room for line ends and labels.
        val pad = if (view.crop != null) 0f else 0.1f * dpi
        clipRect(rIn.left * dpi - pad, rIn.top * dpi - pad, rIn.right * dpi + pad, rIn.bottom * dpi + pad) {
            withTransform({
                translate(pan.x, pan.y)
                scale(zoom, zoom, Offset.Zero)
            }) {
                drawDocumentContents(pageState)
                live.forEach { drawLiveSymbol(it, paperState, Color.Black) }
            }
        }
        if (view.crop != null) {
            drawRect(Color.Black, topLeft = Offset(rIn.left * dpi, rIn.top * dpi), size = Size(rIn.width * dpi, rIn.height * dpi), style = Stroke(lineW * 0.8f))
        }
        // Title, underlined, and the scale under it.
        val titleTop = rIn.bottom * dpi + 0.12f * dpi
        val titleSize = 0.2f * dpi
        val tx = drawNumberBubble(sheet, view, rIn.left * dpi, titleTop, dpi, lineW)
        drawPageText(view.title.uppercase(), Offset(tx, titleTop), titleSize, Color.Black, bold = true, maxWidth = max(rIn.width * dpi, 3f * dpi))
        drawLine(Color.Black, Offset(tx, titleTop + titleSize * 1.3f), Offset(tx + minOf(max(rIn.width, 2f), 6f) * dpi, titleTop + titleSize * 1.3f), strokeWidth = lineW * 1.5f)
        drawPageText("SCALE: ${page.scaleText}", Offset(tx, titleTop + titleSize * 1.5f), 0.12f * dpi, Color.Black, bold = false, maxWidth = 6f * dpi)
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
