package com.example.sitedraft.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import kotlin.math.pow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingBackground
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.PageOrientation
import com.example.sitedraft.ui.canvas.PaperSize
import com.example.sitedraft.ui.canvas.PrintPage
import com.example.sitedraft.ui.canvas.PrintScale
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.UnitSystem
import com.example.sitedraft.ui.canvas.ViewCrop
import com.example.sitedraft.ui.canvas.drawDocumentContents
import com.example.sitedraft.ui.canvas.drawSheet
import com.example.sitedraft.ui.canvas.effectiveLayers

/** The sheet list's "new sheet" entry: picking a first view makes the sheet. */
const val NEW_SHEET = "__new_sheet__"

/**
 * Builds a large-format sheet: its number, title, paper and drawn-by; the views on it (plans,
 * elevations, the wall section), each a viewport at its own scale, optionally cropped to a
 * window of the model; and a preview of the sheet where a view is dragged to where it should
 * print. Every change is saved with the project through [onSheetsChange]. A new sheet is made by
 * picking its first view ([NEW_SHEET]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetEditor(
    state: CanvasState,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onSheetsChange: (List<SheetLayout>) -> Unit,
    onEvent: (DrawingEvent) -> Unit = {}
) {
    val sheets = state.sheets
    val sheet = if (selectedId == NEW_SHEET) null else sheets.firstOrNull { it.id == selectedId } ?: sheets.firstOrNull()
    val metric = state.unitSystem == UnitSystem.METRIC
    fun save(updated: SheetLayout) = onSheetsChange(sheets.map { if (it.id == updated.id) updated else it })
    var cropping by remember { mutableStateOf<SheetView?>(null) }

    if (sheet == null) {
        // First view picked makes the sheet (Dan, Nov 20): no empty sheet to fill in first.
        Text("New sheet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick the first view. The sheet is made with it and named after it; add more views after.",
            style = MaterialTheme.typography.bodySmall
        )
        AddViewChoices(state, existing = null, onDetail = { kind ->
            // A detail makes its sheet in its series (the overhang on the A sheets with the wall details).
            val blank = SheetLayout(number = Sheets.nextNumber(kind.series, sheets), title = kind.title)
            onSheetsChange(sheets + blank)
            onEvent(DrawingEvent.AddDetailToSheet(blank.id, kind.ordinal))
            onSelect(blank.id)
        }) { view, isSection ->
            if (Sheets.scheduleKind(view) != null) onEvent(DrawingEvent.ShowOpeningMarks(true))
            // Numbered in its discipline's series (Dan, Nov 22): structural on S pages, the rest on A pages.
            val number = Sheets.nextNumber(Sheets.disciplineOf(view, state), sheets)
            val blank = SheetLayout(number = number, title = if (isSection) "Wall Section" else view.title)
            if (isSection) {
                onSheetsChange(sheets + blank)
                onEvent(DrawingEvent.AddWallSectionToSheet(blank.id))
            } else {
                onSheetsChange(sheets + blank.copy(views = listOf(Sheets.placed(blank, view, state))))
            }
            onSelect(blank.id)
        }
        return
    }

    // A bigger paper-space view (Dan, Nov 22: zooming was clumsy in a small box): the sheet's fields fold away.
    var big by remember { mutableStateOf(false) }
    if (!big) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Numbered by the set's order; a number typed here stays, and clearing it goes back to automatic.
        OutlinedTextField(sheet.number, { save(sheet.copy(number = it, numberManual = it.isNotBlank())) },
            label = { Text(if (sheet.numberManual) "Sheet number (typed)" else "Sheet number (auto)") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(sheet.title, { save(sheet.copy(title = it)) }, label = { Text("Sheet title") }, singleLine = true, modifier = Modifier.weight(2f))
        OutlinedTextField(sheet.drawnBy, { save(sheet.copy(drawnBy = it)) }, label = { Text("Drawn by (blank = project's)") }, singleLine = true, modifier = Modifier.weight(1.5f))
    }
    if (!big) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(PaperSize.TABLOID, PaperSize.ARCH_D, PaperSize.ARCH_E1, PaperSize.ARCH_C).forEach { p ->
            FilterChip(selected = sheet.paper == p, onClick = { save(sheet.copy(paper = p)) }, label = { Text(p.label) })
        }
        PageOrientation.entries.forEach { o ->
            FilterChip(selected = sheet.orientation == o, onClick = { save(sheet.copy(orientation = o)) }, label = { Text(o.label) })
        }
    }

    if (!big) Text("Drag a view to where it should print. The title block fills in from Projects / Jobs > Project info.", style = MaterialTheme.typography.bodySmall)
    // Above the paper (Dan, Nov 23): under it, the wheel over the paper zooms it and the page never scrolls down to here.
    if (!big) {
        Text("Add a viewport", style = MaterialTheme.typography.labelLarge)
        AddViewChoices(state, existing = sheet, onDetail = { kind -> onEvent(DrawingEvent.AddDetailToSheet(sheet.id, kind.ordinal)) }) { view, isSection ->
            if (Sheets.scheduleKind(view) != null) onEvent(DrawingEvent.ShowOpeningMarks(true))
            if (isSection) onEvent(DrawingEvent.AddWallSectionToSheet(sheet.id))
            else save(sheet.copy(views = sheet.views + Sheets.placed(sheet, view, state)))
        }
    }
    SheetPreview(sheet, state, big = big, onBig = { big = it }) { moved -> save(sheet.copy(views = sheet.views.map { if (it.id == moved.id) moved else it })) }

    // Views that run off the drawing area.
    val area = Sheets.drawingArea(sheet)
    val off = sheet.views.filter { v -> Sheets.footprint(v, state)?.let { f -> f.left < area.left - 0.01f || f.top < area.top - 0.01f || f.right > area.right + 0.01f || f.bottom > area.bottom + 0.01f } ?: false }
    val overlaps = Sheets.overlapping(sheet, state)
    if (off.isNotEmpty()) {
        Text(
            "Runs off the sheet: ${off.joinToString { it.title }}. Drag it in, crop it, pick a smaller scale, or a bigger sheet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
        )
    }
    if (overlaps.isNotEmpty()) {
        Text("Overlapping: ${overlaps.joinToString { it.title }}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (off.isNotEmpty() || overlaps.isNotEmpty()) {
        OutlinedButton(onClick = { save(Sheets.arranged(sheet, state)) }) { Text("Arrange the viewports") }
    }

    Text("Viewports", style = MaterialTheme.typography.labelLarge)
    sheet.views.forEach { v -> ViewportFields(sheet, v, state, ::save, onEvent) { cropping = it } }
    TextButton(onClick = {
        val rest = sheets.filter { it.id != sheet.id }
        onSheetsChange(rest)
        onSelect(rest.firstOrNull()?.id ?: NEW_SHEET)
    }) { Text("Delete this sheet", color = MaterialTheme.colorScheme.error) }

    cropping?.let { v ->
        CropDialog(v, state, onDismiss = { cropping = null }) { crop ->
            save(sheet.copy(views = sheet.views.map { if (it.id == v.id) it.copy(crop = crop) else it }))
            cropping = null
        }
    }
}

/**
 * The views that can be added: a plan by layers, the typical wall section (once per sheet), and
 * each elevation not already on the sheet. [onPick] gets the view and whether it is the section
 * (which the manager adds from the model-space section).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddViewChoices(state: CanvasState, existing: SheetLayout?, onDetail: (com.example.sitedraft.ui.canvas.DetailKit.Detail) -> Unit = {}, onPick: (SheetView, Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sheets.planChoices(state).forEach { (label, layerIds) ->
            OutlinedButton(onClick = {
                val title = if (layerIds == null) "Floor Plan" else label
                // A site plan starts at 1" = 20' (the lot doesn't fit at 1/4").
                val site = layerIds != null && layerIds.isNotEmpty() && layerIds.all { id -> state.effectiveLayers.firstOrNull { it.id == id }?.trade == com.example.sitedraft.ui.canvas.LayerTrade.SITE_PLAN }
                onPick(SheetView(kind = SheetViewKind.PLAN, title = title, layerIds = layerIds, scale = if (site) PrintScale.ENG_20 else PrintScale.QUARTER), false)
            }) { Text(label) }
        }
        if (existing == null || existing.views.none { it.kind == SheetViewKind.SECTION }) {
            OutlinedButton(onClick = {
                onPick(SheetView(kind = SheetViewKind.SECTION, title = "Typical Wall Section", scale = PrintScale.THREE_QUARTER), true)
            }) { Text("Typical wall section") }
        }
        // Construction details, live from the drawing (DetailKit): once each per sheet.
        com.example.sitedraft.ui.canvas.DetailKit.Detail.entries.forEach { kind ->
            val there = com.example.sitedraft.ui.canvas.DetailKit.detailIn(state, kind)
            if (existing == null || there == null || existing.views.none { it.elementId == there.id }) {
                OutlinedButton(onClick = { onDetail(kind) }) { Text(kind.title) }
            }
        }
        // The door and window schedules, read off the plan (once each per sheet).
        listOf(SheetViewKind.DOOR_SCHEDULE to "Door schedule", SheetViewKind.WINDOW_SCHEDULE to "Window schedule",
            SheetViewKind.STRUCTURAL_CALCS to "Structural calculations",
            // The T sheets (Nov 25): the cover's index, project data and legend; the general notes.
            SheetViewKind.SHEET_INDEX to "Sheet index", SheetViewKind.PROJECT_DATA to "Project data",
            SheetViewKind.LEGEND to "Legend and symbols", SheetViewKind.GENERAL_NOTES to "General notes",
            SheetViewKind.COVER_TITLE to "Cover title", SheetViewKind.PAINT_SCHEDULE to "Paint schedule",
            SheetViewKind.FINISH_SCHEDULE to "Finish schedule", SheetViewKind.TRIM_SCHEDULE to "Trim schedule",
            SheetViewKind.CABINET_SCHEDULE to "Cabinet & countertop schedule", SheetViewKind.APPLIANCE_SCHEDULE to "Appliance schedule",
            SheetViewKind.PLUMBING_SCHEDULE to "Plumbing fixture schedule").forEach { (kind, label) ->
            if (existing == null || existing.views.none { it.kind == kind }) {
                OutlinedButton(onClick = { onPick(SheetView(kind = kind, title = label), false) }) { Text(label) }
            }
        }
        // The exterior elevations in model space, at 1/4" (once each per sheet).
        Sheets.exteriorChoices(state).forEach { (symbol, label) ->
            if (existing == null || existing.views.none { it.elementId == symbol.id }) {
                OutlinedButton(onClick = {
                    onPick(SheetView(kind = SheetViewKind.ELEVATION, elementId = symbol.id, title = label, scale = PrintScale.QUARTER), false)
                }) { Text(label) }
            }
        }
        Sheets.elevationChoices(state).forEach { (view, label) ->
            if (existing == null || existing.views.none { it.elementId == view.id }) {
                OutlinedButton(onClick = {
                    onPick(SheetView(kind = SheetViewKind.ELEVATION, elementId = view.id, title = label.substringBefore(" ("), scale = PrintScale.HALF), false)
                }) { Text(label) }
            }
        }
    }
}

/**
 * Picks a cropped viewport's window: the view's whole subject drawn small, a window dragged
 * over the part to show (the kitchen, a closet). The window is kept in model units, so the
 * viewport shows exactly that area at whatever scale it is set to.
 */
@Composable
internal fun CropDialog(view: SheetView, state: CanvasState, onDismiss: () -> Unit, onApply: (ViewCrop) -> Unit) {
    val whole = view.copy(crop = null)
    val full = Sheets.fullWorldBounds(whole, state)
    var window by remember(view.id) { mutableStateOf(view.crop?.rect) }
    var dragStart by remember(view.id) { mutableStateOf<Offset?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop ${view.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (full == null || full.width <= 0f || full.height <= 0f) {
                    Text("This view shows nothing to crop.")
                    return@Column
                }
                Text("Drag a window around the part to show.", style = MaterialTheme.typography.bodySmall)
                // The subject, on white, as it prints.
                val shown = Sheets.viewElements(whole, state).flatMap { e ->
                    if (e is DrawingElement.ElevationView) Elevation.partsOf(e, state.copy(backgroundColor = Color.White)) else listOf(e)
                }.map { PrintPage.inkForPaper(it) }
                val ratio = (full.width / full.height).coerceIn(0.4f, 3f)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .border(1.dp, Color.Gray)
                        .pointerInput(full) {
                            fun toWorld(p: Offset): Offset {
                                val pad = 8f
                                val z = minOf((size.width - 2 * pad) / full.width, (size.height - 2 * pad) / full.height)
                                val pan = Offset(size.width / 2f, size.height / 2f) - full.center * z
                                return (p - pan) / z
                            }
                            // From the exact point pressed: detectDragGestures reports its start only
                            // once the pointer has moved past the touch slop, which shifted the window.
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val a = toWorld(down.position)
                                dragStart = a
                                window = Rect(a, Size.Zero)
                                drag(down.id) { change ->
                                    change.consume()
                                    val b = toWorld(change.position)
                                    window = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                                }
                                dragStart = null
                            }
                        }
                ) {
                    val pad = 8f
                    val z = minOf((size.width - 2 * pad) / full.width, (size.height - 2 * pad) / full.height)
                    val pan = Offset(size.width / 2f, size.height / 2f) - full.center * z
                    drawRect(Color.White)
                    val pageState = state.copy(
                        elements = shown, zoom = z, pan = pan, backgroundColor = Color.White,
                        background = DrawingBackground.PLAIN, layerDisplay = false, labelScale = 1f,
                        selectedElementId = null, selectedElementIds = emptySet(), currentDrawingElement = null,
                        lastSnapPoint = null, boxSelectStart = null, boxSelectEnd = null, preciseMoveFromPoint = null,
                        placementPreview = null
                    )
                    withTransform({
                        translate(pan.x, pan.y)
                        scale(z, z, Offset.Zero)
                    }) { drawDocumentContents(pageState) }
                    window?.let { w ->
                        val tl = pan + w.topLeft * z
                        drawRect(Color(0x33FF8F00), topLeft = tl, size = Size(w.width * z, w.height * z))
                        drawRect(Color(0xFFFF8F00), topLeft = tl, size = Size(w.width * z, w.height * z), style = Stroke(2f))
                    }
                }
                window?.let { w ->
                    val k = Sheets.inchesPerWorldPx(view, state)
                    Text(
                        "${sheetInches(w.width * k)} x ${sheetInches(w.height * k)} on paper at ${view.scale.label}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = window?.let { it.width > 1f && it.height > 1f } == true,
                onClick = { window?.let { onApply(ViewCrop.of(it)) } }
            ) { Text("Use this window") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun sheetInches(v: Float): String = PrintPage.inchesText(v)

/** Two taps closer together than this are a double-click. */
private const val DOUBLE_CLICK_MS = 400L

/** What a drag on the sheet preview is doing. */
private sealed class SheetDrag {
    abstract val view: SheetView
    /** Moving the viewport on the paper. */
    data class Move(override val view: SheetView) : SheetDrag()
    /** Inside the viewport: sliding the model in its window. */
    data class Slide(override val view: SheetView) : SheetDrag()
    /** Dragging a corner of the viewport's frame. */
    data class Resize(override val view: SheetView, val corner: Sheets.Corner) : SheetDrag()
}

/**
 * The sheet as it will print, scaled to the screen, worked like AutoCAD's paper space:
 *  - drag a viewport to move it on the paper; tap it to pick it (its corners get handles)
 *  - drag a picked viewport's corner to resize it: it shows more or less of the model, same scale
 *  - double-click a viewport to go inside it, then drag to slide the model in its window;
 *    Done (or a double-click outside it) comes back out
 */
@Composable
internal fun SheetPreview(sheet: SheetLayout, state: CanvasState, big: Boolean = false, onBig: (Boolean) -> Unit = {},
    /** Nov 28: the sheet fills the space it is given (the sheet screen), with compact zoom buttons over it. */
    fill: Boolean = false,
    /** Nov 28: told which viewport is picked (null = none), so its card can open beside it. */
    onPicked: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
    onMoved: (SheetView) -> Unit) {
    val (wIn, hIn) = Sheets.paperIn(sheet)
    var picked by remember(sheet.id) { mutableStateOf<String?>(null) }
    // Every pick goes through here, so the sheet screen can open the picked viewport's card.
    fun pick(id: String?) { if (id != picked) { picked = id; onPicked(id) } }
    var insideId by remember(sheet.id) { mutableStateOf<String?>(null) }
    // The view as it is mid-drag (moved, slid or resized); null when nothing is being dragged.
    var dragged by remember(sheet.id) { mutableStateOf<SheetView?>(null) }
    // The last tap, for telling a double-click (kept out here: the gesture handler restarts between taps).
    var lastTapAt by remember(sheet.id) { mutableStateOf(0L) }
    var lastTapId by remember(sheet.id) { mutableStateOf<String?>(null) }
    val shown = dragged?.let { d -> sheet.copy(views = sheet.views.map { if (it.id == d.id) d else it }) } ?: sheet
    val inside = sheet.views.firstOrNull { it.id == insideId }
    // Paper-space zoom (Dan, Nov 22): 1 = the whole sheet across the screen; [pan] shifts it, screen px.
    var zoom by remember(sheet.id) { mutableStateOf(1f) }
    var pan by remember(sheet.id) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(Size.Zero) }
    /** Zooms by [factor] keeping the paper under [focus] (screen px) where it is. */
    // The whole sheet fitted in the box, centred; [pan] moves it from there.
    fun fitOf(w: Float, h: Float) = minOf(w / wIn, h / hIn)
    fun baseOf(w: Float, h: Float): Offset { val k = fitOf(w, h); return Offset((w - wIn * k) / 2f, (h - hIn * k) / 2f) }
    fun zoomBy(factor: Float, focus: Offset) {
        val z = (zoom * factor).coerceIn(1f, 12f)
        val f = z / zoom
        val base = baseOf(boxSize.width, boxSize.height)
        pan = if (z <= 1.0001f) Offset.Zero else focus - base - (focus - base - pan) * f
        zoom = z
    }

    Column(modifier = if (fill) modifier.fillMaxSize() else modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (inside != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Inside ${inside.title}: drag to slide the model in the window (scale stays ${inside.scale.label}).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { insideId = null }) { Text("Done") }
            }
        } else if (!big && !fill) {
            Text(
                "Drag a viewport to move it. Tap it, then drag a corner to resize it. Double-click it to go inside and slide the model.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (!fill) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val centre = Offset(boxSize.width / 2f, boxSize.height / 2f)
            OutlinedButton(onClick = { zoomBy(1f / 1.5f, centre) }, enabled = zoom > 1f) { Text("−") }
            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { zoomBy(1.5f, centre) }) { Text("+") }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }, enabled = zoom > 1f) { Text("Fit") }
            TextButton(onClick = { onBig(!big) }) { Text(if (big) "Show the sheet's settings" else "Bigger view") }
            Text("Ctrl + wheel zooms where you point. Drag bare paper, or drag with the wheel or right button anywhere, to move around.", style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.foundation.layout.Box(if (fill) Modifier.weight(1f) else Modifier) {
        Canvas(
            (if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(wIn / hIn))
                // Nothing on the sheet draws outside it (a view that runs off is cut at the paper's edge).
                .clipToBounds()
                .border(1.dp, Color.Gray)
                .onSizeChanged { boxSize = Size(it.width.toFloat(), it.height.toFloat()) }
                // The mouse wheel zooms toward the pointer.
                .pointerInput(sheet.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Scroll) {
                                val c = e.changes.firstOrNull() ?: continue
                                val dy = c.scrollDelta.y
                                // Ctrl + wheel zooms; the plain wheel scrolls the screen (Dan, Nov 22: wheeling back
                                // up the page over the sheet zoomed in instead of scrolling).
                                if (e.keyboardModifiers.isCtrlPressed && (dy < 0f || (dy > 0f && zoom > 1f))) {
                                    // As many steps as the wheel turned (a fast spin comes as one event with a bigger delta).
                                    zoomBy(1.15f.pow((-dy).coerceIn(-10f, 10f)), c.position)
                                    c.consume()
                                }
                            }
                        }
                    }
                }
                // Not keyed on zoom or pan: they change during a pan drag, and restarting the handler then
                // cancelled the drag after its first move (Dan, Nov 22: panning a zoomed sheet did nothing).
                // They are state, read fresh inside the handler.
                .pointerInput(sheet, state.elements, insideId, picked) {
                    // From the exact point pressed (see CropDialog).
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val dpi = fitOf(size.width.toFloat(), size.height.toFloat()) * zoom
                        val base = baseOf(size.width.toFloat(), size.height.toFloat())
                        // The wheel (middle) or right button drags the paper around wherever it is pressed,
                        // over a viewport too, as in AutoCAD: nothing on the sheet moves.
                        val buttons = currentEvent.buttons
                        if (buttons.isTertiaryPressed || buttons.isSecondaryPressed) {
                            val startPan = pan
                            drag(down.id) { change ->
                                change.consume()
                                if (zoom > 1f) pan = startPan + (change.position - down.position)
                            }
                            return@awaitEachGesture
                        }
                        fun paper(p: Offset) = (p - base - pan) / dpi
                        val start = paper(down.position)
                        val pickedView = sheet.views.firstOrNull { it.id == picked }
                        val corner = pickedView?.let { Sheets.cornerAt(it, start, 10f / dpi, state) }
                        val insideView = sheet.views.firstOrNull { it.id == insideId }
                        val mode: SheetDrag? = when {
                            pickedView != null && corner != null && Sheets.worldBounds(pickedView, state) != null -> SheetDrag.Resize(pickedView, corner)
                            insideView != null && Sheets.viewRect(insideView, state)?.contains(start) == true -> SheetDrag.Slide(insideView)
                            else -> Sheets.viewAt(sheet, start, state)?.let { SheetDrag.Move(it) }
                        }
                        var moved = false
                        if (mode == null) {
                            // A press on bare paper: dragged, it moves around a zoomed sheet; a tap unpicks;
                            // a double-click comes out of a viewport.
                            var panned = false
                            val startPan = pan
                            drag(down.id) { change ->
                                val d = change.position - down.position
                                if (!panned && d.getDistance() < viewConfiguration.touchSlop) return@drag
                                panned = true
                                change.consume()
                                if (zoom > 1f) pan = startPan + d
                            }
                            if (!panned) {
                                val double = down.uptimeMillis - lastTapAt < DOUBLE_CLICK_MS
                                lastTapAt = if (double) 0L else down.uptimeMillis
                                if (double) insideId = null
                                pick(null)
                            }
                            return@awaitEachGesture
                        }
                        drag(down.id) { change ->
                            val now = paper(change.position)
                            if (!moved && (now - start).getDistance() * dpi < viewConfiguration.touchSlop) return@drag
                            moved = true
                            change.consume()
                            val v = mode.view
                            dragged = when (mode) {
                                is SheetDrag.Move -> v.copy(xIn = v.xIn + (now.x - start.x), yIn = v.yIn + (now.y - start.y))
                                is SheetDrag.Slide -> Sheets.slid(v, now - start, state) ?: v
                                is SheetDrag.Resize -> Sheets.resized(v, mode.corner, now, state) ?: v
                            }
                        }
                        val result = dragged
                        if (moved && result != null) {
                            onMoved(if (mode is SheetDrag.Move) result.copy(xIn = Sheets.snap(result.xIn), yIn = Sheets.snap(result.yIn)) else result)
                            pick(result.id)
                            lastTapAt = 0L
                        } else if (!moved) {
                            // A tap picks the viewport; a second tap on it soon after is a double-click:
                            // into the viewport, or back out if already inside it.
                            val v = mode.view
                            val double = down.uptimeMillis - lastTapAt < DOUBLE_CLICK_MS && lastTapId == v.id
                            if (double) {
                                insideId = if (insideId == v.id || Sheets.worldBounds(v, state) == null) null else v.id
                                lastTapAt = 0L
                            } else {
                                lastTapAt = down.uptimeMillis
                                lastTapId = v.id
                            }
                            pick(v.id)
                        }
                        dragged = null
                    }
                }
        ) {
            val dpi = fitOf(size.width, size.height) * zoom
            val base = baseOf(size.width, size.height)
            translate(base.x + pan.x, base.y + pan.y) {
            drawSheet(shown, state, dpi, state.projectName, "", highlightId = if (insideId != null) null else picked)
            // The viewport being worked inside: a heavy blue frame, as AutoCAD's active viewport.
            shown.views.firstOrNull { it.id == insideId }?.let { v ->
                Sheets.viewRect(v, state)?.let { r ->
                    drawRect(Color(0xFF1565C0), topLeft = Offset(r.left * dpi, r.top * dpi), size = Size(r.width * dpi, r.height * dpi), style = Stroke(4f))
                }
            }
            // Handles on the picked viewport's corners, for resizing.
            if (insideId == null) shown.views.firstOrNull { it.id == picked }?.let { v ->
                if (Sheets.worldBounds(v, state) != null) Sheets.viewRect(v, state)?.let { r ->
                    val h = 7f
                    listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { c ->
                        val p = Offset(c.x * dpi, c.y * dpi)
                        drawRect(Color.White, topLeft = p - Offset(h, h), size = Size(2 * h, 2 * h))
                        drawRect(Color(0xFFFF8F00), topLeft = p - Offset(h, h), size = Size(2 * h, 2 * h), style = Stroke(2f))
                    }
                }
            }
            }
        }
        // Compact zoom over the sheet's corner (Nov 28: the sheet screen keeps the paper clear).
        if (fill) Row(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val centre = Offset(boxSize.width / 2f, boxSize.height / 2f)
            androidx.compose.material3.FilledTonalButton(onClick = { zoomBy(1f / 1.5f, centre) }, enabled = zoom > 1f) { Text("−") }
            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            androidx.compose.material3.FilledTonalButton(onClick = { zoomBy(1.5f, centre) }) { Text("+") }
            androidx.compose.material3.FilledTonalButton(onClick = { zoom = 1f; pan = Offset.Zero }, enabled = zoom > 1f) { Text("Fit") }
        }
        }
    }
}

/** One viewport's settings (Nov 28: also the card that opens beside a tapped viewport on the sheet screen). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ViewportFields(sheet: SheetLayout, v: SheetView, state: CanvasState, save: (SheetLayout) -> Unit, onEvent: (DrawingEvent) -> Unit, onCrop: (SheetView) -> Unit) {
    val metric = state.unitSystem == UnitSystem.METRIC
        fun update(change: (SheetView) -> SheetView) = save(sheet.copy(views = sheet.views.map { if (it.id == v.id) change(it) else it }))
        Column(
            Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(v.title, { t -> update { it.copy(title = t) } },
                    label = { Text("View title") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { save(sheet.copy(views = sheet.views.filter { it.id != v.id })) }) { Text("Remove") }
            }
            val isTable = Sheets.isTable(v)
            if (isTable) {
                Text(
                    if (v.kind == SheetViewKind.STRUCTURAL_CALCS) "Worked out from the drawing and Job specs (loads, soil, posts): it keeps up as they change."
                    else "Read off the floor plan: it keeps up with the doors and windows as they change. The marks show on the plan.",
                    style = MaterialTheme.typography.bodySmall
                )
                // The table's size on the sheet (normal = 1/8" lettering).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.example.sitedraft.ui.canvas.Schedules.sizeChoices.forEach { (label, k) ->
                        FilterChip(selected = kotlin.math.abs(v.tableSize - k) < 0.01f, onClick = { update { it.copy(tableSize = k) } }, label = { Text(label) })
                    }
                }
            }
            // Each viewport its own scale.
            if (!isTable) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sheets.viewScales.forEach { sc ->
                    FilterChip(selected = v.scale == sc, onClick = { update { it.copy(scale = sc) } },
                        label = { Text(if (metric) "1:${sc.denominator}" else sc.label) })
                }
            }
            // Cropped viewport: a window on the model.
            if (!isTable) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onCrop(v) }) { Text(if (v.crop == null) "Crop to a window" else "Change window") }
                if (v.crop != null) TextButton(onClick = { update { it.copy(crop = null) } }) { Text("Show the whole view") }
            }
            if (v.kind == SheetViewKind.PLAN) {
                // The layers this viewport shows; every regular layer when none is picked. A sheet's
                // notes layer shows only where it is picked.
                val all = state.effectiveLayers
                val regular = all.filter { !Sheets.isSheetNotes(it.id) }.map { it.id }.toSet()
                val on = v.layerIds?.toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    all.filter { it.trade != com.example.sitedraft.ui.canvas.LayerTrade.EXTERIOR_ELEVATION }.forEach { layer ->
                        FilterChip(selected = if (on == null) layer.id in regular else layer.id in on, onClick = {
                            val current = on ?: regular
                            val next = if (layer.id in current) current - layer.id else current + layer.id
                            val ids = if (next == regular) null else all.map { it.id }.filter { it in next }
                            update { it.copy(layerIds = ids) }
                        }, label = { Text(layer.name) })
                    }
                }
                Text(
                    "Shows: " + (if (on == null) "all layers" else all.filter { it.id in on }.joinToString { it.name }),
                    style = MaterialTheme.typography.bodySmall
                )
                // Remodel (Remodel.kt): which plan this view is, so one drawing gives the existing, demo and proposed sheets.
                if (com.example.sitedraft.ui.canvas.Remodel.isRemodel(state)) {
                    Text("Remodel plan", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.example.sitedraft.ui.canvas.RemodelPhase.entries.forEach { p ->
                            FilterChip(selected = v.phase == p, onClick = { update { it.copy(phase = p) } }, label = { Text(p.label) })
                        }
                    }
                }
                OutlinedButton(onClick = { onEvent(DrawingEvent.AddSheetNotesLayer(sheet.id, v.id)) }) { Text("New notes layer for this sheet") }
                Text(
                    "A layer only this viewport shows, made the drawing layer: text and dimensions you add in the drawing print only here.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (v.kind == SheetViewKind.SECTION) {
                Text(
                    if (Sheets.sectionFor(v, state) != null) "From the section in model space: edit it there (select it on the canvas)."
                    else "Drawn from the building inputs. Place it in model space (Layers > Typical wall section) to edit it there.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            val size = Sheets.footprint(v, state)
            Text(
                if (size == null) "Shows nothing (its subject was deleted)."
                else "${sheetInches(size.width)} x ${sheetInches(size.height)} on paper with its title" + (if (v.crop != null) ", cropped." else "."),
                style = MaterialTheme.typography.bodySmall
            )
        }
}
