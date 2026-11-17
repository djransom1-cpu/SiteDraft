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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.PageOrientation
import com.example.sitedraft.ui.canvas.PaperSize
import com.example.sitedraft.ui.canvas.PrintScale
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.UnitSystem
import com.example.sitedraft.ui.canvas.drawSheet

/**
 * Builds a large-format sheet: its number, title, paper and drawn-by; the views on it (the floor
 * plan, elevations), each at its own scale; and a preview of the sheet where a view is dragged to
 * where it should print. Every change is saved with the project through [onSheetsChange].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetEditor(
    state: CanvasState,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onSheetsChange: (List<SheetLayout>) -> Unit
) {
    val sheets = state.sheets
    val sheet = sheets.firstOrNull { it.id == selectedId } ?: sheets.firstOrNull()
    val metric = state.unitSystem == UnitSystem.METRIC
    fun save(updated: SheetLayout) = onSheetsChange(sheets.map { if (it.id == updated.id) updated else it })

    Text("Sheet", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sheets.forEach { s ->
            FilterChip(selected = s.id == sheet?.id, onClick = { onSelect(s.id) }, label = { Text("${s.number} ${s.title}") })
        }
        OutlinedButton(onClick = {
            val blank = SheetLayout(number = "A-${sheets.size + 1}", title = if (sheets.isEmpty()) "Floor Plan" else "Elevations")
            // A first sheet starts with the floor plan on it.
            val withPlan = if (sheets.isEmpty()) blank.copy(views = listOf(Sheets.placed(blank, SheetView(kind = SheetViewKind.PLAN, title = "Floor Plan"), state))) else blank
            onSheetsChange(sheets + withPlan)
            onSelect(withPlan.id)
        }) { Text("New sheet") }
    }
    if (sheet == null) {
        Text("No sheets yet. Tap New sheet: it starts with the floor plan at 1/4\" = 1'-0\".", style = MaterialTheme.typography.bodySmall)
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(sheet.number, { save(sheet.copy(number = it)) }, label = { Text("Sheet number") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(sheet.title, { save(sheet.copy(title = it)) }, label = { Text("Sheet title") }, singleLine = true, modifier = Modifier.weight(2f))
        OutlinedTextField(sheet.drawnBy, { save(sheet.copy(drawnBy = it)) }, label = { Text("Drawn by") }, singleLine = true, modifier = Modifier.weight(1.5f))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(PaperSize.TABLOID, PaperSize.ARCH_D, PaperSize.ARCH_E1, PaperSize.ARCH_C).forEach { p ->
            FilterChip(selected = sheet.paper == p, onClick = { save(sheet.copy(paper = p)) }, label = { Text(p.label) })
        }
        PageOrientation.entries.forEach { o ->
            FilterChip(selected = sheet.orientation == o, onClick = { save(sheet.copy(orientation = o)) }, label = { Text(o.label) })
        }
    }

    Text("Drag a view to where it should print.", style = MaterialTheme.typography.bodySmall)
    SheetPreview(sheet, state) { moved -> save(sheet.copy(views = sheet.views.map { if (it.id == moved.id) moved else it })) }

    // Views that run off the drawing area.
    val area = Sheets.drawingArea(sheet)
    val off = sheet.views.filter { v -> Sheets.footprint(v, state)?.let { f -> f.left < area.left - 0.01f || f.top < area.top - 0.01f || f.right > area.right + 0.01f || f.bottom > area.bottom + 0.01f } ?: false }
    if (off.isNotEmpty()) {
        Text(
            "Runs off the sheet: ${off.joinToString { it.title }}. Drag it in, pick a smaller scale, or a bigger sheet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
        )
    }

    Text("Views", style = MaterialTheme.typography.labelLarge)
    sheet.views.forEach { v ->
        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(v.title, { t -> save(sheet.copy(views = sheet.views.map { if (it.id == v.id) it.copy(title = t) else it })) },
                    label = { Text("View title") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { save(sheet.copy(views = sheet.views.filter { it.id != v.id })) }) { Text("Remove") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sheets.viewScales.forEach { sc ->
                    FilterChip(selected = v.scale == sc, onClick = {
                        save(sheet.copy(views = sheet.views.map { if (it.id == v.id) it.copy(scale = sc) else it }))
                    }, label = { Text(if (metric) "1:${sc.denominator}" else sc.label) })
                }
            }
            val size = Sheets.footprint(v, state)
            Text(
                if (size == null) "Shows nothing (its elevation was deleted)."
                else "${sheetInches(size.width)} x ${sheetInches(size.height)} on paper with its title.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    Text("Add a view", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            save(sheet.copy(views = sheet.views + Sheets.placed(sheet, SheetView(kind = SheetViewKind.PLAN, title = "Floor Plan"), state)))
        }) { Text("Floor plan") }
        Sheets.elevationChoices(state).forEach { (view, label) ->
            if (sheet.views.none { it.elementId == view.id }) {
                OutlinedButton(onClick = {
                    val add = SheetView(kind = SheetViewKind.ELEVATION, elementId = view.id, title = label.substringBefore(" ("), scale = PrintScale.HALF)
                    save(sheet.copy(views = sheet.views + Sheets.placed(sheet, add, state)))
                }) { Text(label) }
            }
        }
    }
    TextButton(onClick = {
        val rest = sheets.filter { it.id != sheet.id }
        onSheetsChange(rest)
        rest.firstOrNull()?.let { onSelect(it.id) }
    }) { Text("Delete this sheet", color = MaterialTheme.colorScheme.error) }
}

private fun sheetInches(v: Float): String = com.example.sitedraft.ui.canvas.PrintPage.inchesText(v)

/** The sheet as it will print, scaled to the dialog; a view is picked up and moved with a drag. */
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
