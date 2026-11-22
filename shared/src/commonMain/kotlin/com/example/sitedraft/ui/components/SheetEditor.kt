package com.example.sitedraft.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
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
        AddViewChoices(state, existing = null) { view, isSection ->
            val blank = SheetLayout(number = "A-${sheets.size + 1}", title = if (isSection) "Wall Section" else view.title)
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

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(sheet.number, { save(sheet.copy(number = it)) }, label = { Text("Sheet number") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(sheet.title, { save(sheet.copy(title = it)) }, label = { Text("Sheet title") }, singleLine = true, modifier = Modifier.weight(2f))
        OutlinedTextField(sheet.drawnBy, { save(sheet.copy(drawnBy = it)) }, label = { Text("Drawn by (blank = project's)") }, singleLine = true, modifier = Modifier.weight(1.5f))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(PaperSize.TABLOID, PaperSize.ARCH_D, PaperSize.ARCH_E1, PaperSize.ARCH_C).forEach { p ->
            FilterChip(selected = sheet.paper == p, onClick = { save(sheet.copy(paper = p)) }, label = { Text(p.label) })
        }
        PageOrientation.entries.forEach { o ->
            FilterChip(selected = sheet.orientation == o, onClick = { save(sheet.copy(orientation = o)) }, label = { Text(o.label) })
        }
    }

    Text("Drag a view to where it should print. The title block fills in from Projects / Jobs > Project info.", style = MaterialTheme.typography.bodySmall)
    SheetPreview(sheet, state) { moved -> save(sheet.copy(views = sheet.views.map { if (it.id == moved.id) moved else it })) }

    // Views that run off the drawing area.
    val area = Sheets.drawingArea(sheet)
    val off = sheet.views.filter { v -> Sheets.footprint(v, state)?.let { f -> f.left < area.left - 0.01f || f.top < area.top - 0.01f || f.right > area.right + 0.01f || f.bottom > area.bottom + 0.01f } ?: false }
    if (off.isNotEmpty()) {
        Text(
            "Runs off the sheet: ${off.joinToString { it.title }}. Drag it in, crop it, pick a smaller scale, or a bigger sheet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
        )
    }

    Text("Viewports", style = MaterialTheme.typography.labelLarge)
    sheet.views.forEach { v ->
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
            // Each viewport its own scale.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sheets.viewScales.forEach { sc ->
                    FilterChip(selected = v.scale == sc, onClick = { update { it.copy(scale = sc) } },
                        label = { Text(if (metric) "1:${sc.denominator}" else sc.label) })
                }
            }
            // Cropped viewport: a window on the model.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { cropping = v }) { Text(if (v.crop == null) "Crop to a window" else "Change window") }
                if (v.crop != null) TextButton(onClick = { update { it.copy(crop = null) } }) { Text("Show the whole view") }
            }
            if (v.kind == SheetViewKind.PLAN) {
                // The layers this viewport shows; all of them when none is picked.
                val all = state.effectiveLayers
                val on = v.layerIds?.toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    all.filter { it.trade != com.example.sitedraft.ui.canvas.LayerTrade.EXTERIOR_ELEVATION }.forEach { layer ->
                        FilterChip(selected = on == null || layer.id in on, onClick = {
                            val current = on ?: all.map { it.id }.toSet()
                            val next = if (layer.id in current) current - layer.id else current + layer.id
                            val ids = if (next.size == all.size) null else all.map { it.id }.filter { it in next }
                            update { it.copy(layerIds = ids) }
                        }, label = { Text(layer.name) })
                    }
                }
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
    Text("Add a viewport", style = MaterialTheme.typography.labelLarge)
    AddViewChoices(state, existing = sheet) { view, isSection ->
        if (isSection) onEvent(DrawingEvent.AddWallSectionToSheet(sheet.id))
        else save(sheet.copy(views = sheet.views + Sheets.placed(sheet, view, state)))
    }
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
private fun AddViewChoices(state: CanvasState, existing: SheetLayout?, onPick: (SheetView, Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sheets.planChoices(state).forEach { (label, layerIds) ->
            OutlinedButton(onClick = {
                val title = if (layerIds == null) "Floor Plan" else label
                onPick(SheetView(kind = SheetViewKind.PLAN, title = title, layerIds = layerIds), false)
            }) { Text(label) }
        }
        if (existing == null || existing.views.none { it.kind == SheetViewKind.SECTION }) {
            OutlinedButton(onClick = {
                onPick(SheetView(kind = SheetViewKind.SECTION, title = "Typical Wall Section", scale = PrintScale.THREE_QUARTER), true)
            }) { Text("Typical wall section") }
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
private fun CropDialog(view: SheetView, state: CanvasState, onDismiss: () -> Unit, onApply: (ViewCrop) -> Unit) {
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
                            detectDragGestures(
                                onDragStart = { p -> dragStart = toWorld(p); window = Rect(toWorld(p), Size.Zero) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val a = dragStart ?: return@detectDragGestures
                                    val b = toWorld(change.position)
                                    window = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                                },
                                onDragEnd = { dragStart = null },
                                onDragCancel = { dragStart = null }
                            )
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

/** The sheet as it will print, scaled to the screen; a view is picked up and moved with a drag. */
@Composable
private fun SheetPreview(sheet: SheetLayout, state: CanvasState, onMoved: (SheetView) -> Unit) {
    val (wIn, hIn) = Sheets.paperIn(sheet)
    var dragId by remember(sheet.id) { mutableStateOf<String?>(null) }
    var dragAt by remember(sheet.id) { mutableStateOf(Offset.Zero) }
    var picked by remember(sheet.id) { mutableStateOf<String?>(null) }
    val shown = dragId?.let { id -> sheet.copy(views = sheet.views.map { if (it.id == id) it.copy(xIn = dragAt.x, yIn = dragAt.y) else it }) } ?: sheet
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(wIn / hIn)
            .border(1.dp, Color.Gray)
            .pointerInput(sheet, state.elements) {
                detectTapGestures { p ->
                    val dpi = size.width / wIn
                    picked = Sheets.viewAt(sheet, p / dpi, state)?.id
                }
            }
            .pointerInput(sheet, state.elements) {
                detectDragGestures(
                    onDragStart = { p ->
                        val dpi = size.width / wIn
                        val v = Sheets.viewAt(sheet, p / dpi, state)
                        dragId = v?.id
                        picked = v?.id
                        if (v != null) dragAt = Offset(v.xIn, v.yIn)
                    },
                    onDrag = { change, amount ->
                        if (dragId != null) {
                            change.consume()
                            dragAt += amount / (size.width / wIn)
                        }
                    },
                    onDragEnd = {
                        val id = dragId
                        val v = sheet.views.firstOrNull { it.id == id }
                        if (v != null) onMoved(v.copy(xIn = Sheets.snap(dragAt.x), yIn = Sheets.snap(dragAt.y)))
                        dragId = null
                    },
                    onDragCancel = { dragId = null }
                )
            }
    ) {
        drawSheet(shown, state, size.width / wIn, state.projectName, "", highlightId = dragId ?: picked)
    }
}
