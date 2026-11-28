package com.example.sitedraft.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DetailKit
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.PageOrientation
import com.example.sitedraft.ui.canvas.PaperSize
import com.example.sitedraft.ui.canvas.PrintScale
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetLayoutRules
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.ViewCrop
import com.example.sitedraft.ui.canvas.effectiveLayers

/** The sheet screen's tool cards (Dan, Nov 28). */
private enum class SheetTool(val label: String) { SET("Sheets"), ADD("Add view"), SETTINGS("Sheet"), ARRANGE("Arrange") }

/**
 * Paper space, the finished product (Dan, Nov 28): the sheet fills the screen; a rail of tools down the left opens a
 * card (the sheet list, Add view grouped, the sheet's settings, Arrange) that closes when done; tapping a viewport
 * opens its card beside it, never on it, and the card belongs to the viewport tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetsWorkspace(
    state: CanvasState,
    current: SheetLayout?,
    onSelect: (String) -> Unit,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheets = state.sheets
    fun setSheets(list: List<SheetLayout>) = onEvent(DrawingEvent.UpdateSheets(list))
    fun save(updated: SheetLayout) = setSheets(sheets.map { if (it.id == updated.id) updated else it })
    var tool by remember { mutableStateOf<SheetTool?>(if (current == null) SheetTool.ADD else null) }
    var pickedId by remember(current?.id) { mutableStateOf<String?>(null) }
    var cropping by remember { mutableStateOf<SheetView?>(null) }
    var built by remember { mutableStateOf<String?>(null) }
    val picked = current?.views?.firstOrNull { it.id == pickedId }

    Row(modifier.fillMaxSize()) {
        NavigationRail(Modifier.fillMaxHeight()) {
            listOf(SheetTool.SET to Icons.Default.Description, SheetTool.ADD to Icons.Default.Add,
                SheetTool.SETTINGS to Icons.Default.Settings, SheetTool.ARRANGE to Icons.Default.Dashboard).forEach { (t, icon) ->
                NavigationRailItem(selected = tool == t, onClick = { tool = if (tool == t) null else t },
                    icon = { Icon(icon, contentDescription = t.label) }, label = { Text(t.label) })
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (current != null) {
                SheetPreview(current, state, fill = true, onPicked = { pickedId = it }, modifier = Modifier.fillMaxSize().padding(8.dp)) { moved ->
                    save(current.copy(views = current.views.map { if (it.id == moved.id) moved else it }))
                }
            } else {
                Text("No sheet yet: pick its first view in Add view.", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyLarge)
            }

            // The open tool's card, over the left side of the sheet.
            tool?.let { t ->
                ToolCard(t.label, onClose = { tool = null }, modifier = Modifier.align(Alignment.TopStart)) {
                    when (t) {
                        SheetTool.SET -> {
                            sheets.forEach { s ->
                                val on = s.id == current?.id
                                Surface(
                                    color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().clickable { onSelect(s.id) }
                                ) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text("${s.number}  ${s.title}", fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                                        Text("${s.views.size} viewport" + (if (s.views.size == 1) "" else "s") + " · ${s.paper.label}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            OutlinedButton(onClick = { onSelect(NEW_SHEET); tool = SheetTool.ADD }, modifier = Modifier.fillMaxWidth()) { Text("New sheet") }
                            // Who the job is for, and the sheets it gets (Dan, Nov 28): a preset per type, editable per project.
                            Text("Sheet set for", style = MaterialTheme.typography.labelLarge)
                            val type = com.example.sitedraft.ui.canvas.SheetSetBuilder.typeOf(state)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                com.example.sitedraft.ui.canvas.SheetSetBuilder.ProjectType.entries.forEach { t ->
                                    FilterChip(selected = type == t, onClick = { onEvent(DrawingEvent.SetSheetSetChoice(t.name, null)) }, label = { Text(t.label) })
                                }
                            }
                            val on = com.example.sitedraft.ui.canvas.SheetSetBuilder.groupsOf(state)
                            com.example.sitedraft.ui.canvas.SheetSetBuilder.Group.entries.forEach { g ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                    val next = if (g in on) on - g else on + g
                                    onEvent(DrawingEvent.SetSheetSetChoice(state.projectType, com.example.sitedraft.ui.canvas.SheetSetBuilder.Group.entries.filter { it in next }.map { it.name }))
                                }) {
                                    androidx.compose.material3.Checkbox(checked = g in on, onCheckedChange = null)
                                    Text(g.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            // The whole set from the drawing, in Dan's order (Nov 28): sheets already there are kept.
                            OutlinedButton(onClick = {
                                val r = com.example.sitedraft.ui.canvas.SheetSetBuilder.build(state)
                                setSheets(r.sheets)
                                onEvent(DrawingEvent.ShowOpeningMarks(true))
                                built = (if (r.added.isEmpty()) "Nothing new to add." else "Added ${r.added.size}: ${r.added.joinToString()}.") +
                                    (if (r.notMade.isEmpty()) "" else " Not made yet: ${r.notMade.joinToString()}.")
                                r.sheets.firstOrNull { s -> r.added.firstOrNull() == s.title }?.let { onSelect(it.id) }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Build the sheet set") }
                            built?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            // Every job starts with two schedule sheets, grouped by trade (Dan, Nov 28); delete what isn't needed.
                            if (SheetLayoutRules.scheduleSheets.any { (title, _) -> sheets.none { it.title == title } }) {
                                OutlinedButton(onClick = { setSheets(SheetLayoutRules.withScheduleSheets(sheets, state)); onEvent(DrawingEvent.ShowOpeningMarks(true)) },
                                    modifier = Modifier.fillMaxWidth()) { Text("Add the 2 schedule sheets") }
                                Text("Door, window and finish schedules; cabinet, appliance and fixture schedules. Rearrange or delete what the job doesn't need.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        SheetTool.ADD -> AddViewGroups(state, current, sheets, ::setSheets, onSelect, onEvent)
                        SheetTool.SETTINGS -> if (current == null) Text("No sheet open.") else {
                            OutlinedTextField(current.number, { save(current.copy(number = it, numberManual = it.isNotBlank())) },
                                label = { Text(if (current.numberManual) "Sheet number (typed)" else "Sheet number (auto)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(current.title, { save(current.copy(title = it)) }, label = { Text("Sheet title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(current.drawnBy, { save(current.copy(drawnBy = it)) }, label = { Text("Drawn by (blank = project's)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("Paper", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(PaperSize.TABLOID, PaperSize.ARCH_D, PaperSize.ARCH_E1, PaperSize.ARCH_C).forEach { p ->
                                    FilterChip(selected = current.paper == p, onClick = { save(current.copy(paper = p)) }, label = { Text(p.label) })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PageOrientation.entries.forEach { o ->
                                    FilterChip(selected = current.orientation == o, onClick = { save(current.copy(orientation = o)) }, label = { Text(o.label) })
                                }
                            }
                            Text("The title block fills in from Projects / Jobs > Project info.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                val rest = sheets.filter { it.id != current.id }
                                setSheets(rest)
                                onSelect(rest.firstOrNull()?.id ?: NEW_SHEET)
                            }) { Text("Delete this sheet", color = MaterialTheme.colorScheme.error) }
                        }
                        SheetTool.ARRANGE -> if (current == null) Text("No sheet open.") else {
                            Text("Puts the viewports in order: schedules and plans in rows, details in a numbered grid under them " +
                                "(footings first, the roof last). Anything that doesn't fit goes on a new sheet marked (cont.).",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { setSheets(SheetLayoutRules.arrangedSet(current, sheets, state)) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Arrange this sheet")
                            }
                            val area = Sheets.drawingArea(current)
                            val off = current.views.filter { v -> Sheets.footprint(v, state)?.let { f -> f.left < area.left - 0.01f || f.top < area.top - 0.01f || f.right > area.right + 0.01f || f.bottom > area.bottom + 0.01f } ?: false }
                            val overlaps = Sheets.overlapping(current, state)
                            if (off.isNotEmpty()) Text("Runs off the sheet: ${off.joinToString { it.title }}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if (overlaps.isNotEmpty()) Text("Overlapping: ${overlaps.joinToString { it.title }}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Text("On the sheet: drag a viewport to move it; tap it for its card; drag a picked viewport's corner to resize it; " +
                                "double-click it to go inside and slide the model. Ctrl + wheel zooms.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // A warning strip when something runs off or overlaps (the details are in Arrange).
            if (current != null && tool == null) {
                val area = Sheets.drawingArea(current)
                val bad = Sheets.overlapping(current, state).isNotEmpty() || current.views.any { v ->
                    Sheets.footprint(v, state)?.let { f -> f.left < area.left - 0.01f || f.top < area.top - 0.01f || f.right > area.right + 0.01f || f.bottom > area.bottom + 0.01f } ?: false
                }
                if (bad) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clickable { tool = SheetTool.ARRANGE }) {
                    Text("Viewports overlap or run off the sheet: tap to arrange", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
                }
            }

            // The tapped viewport's card: on the side of the sheet away from it, so it never covers it.
            if (current != null && picked != null) {
                val (wIn, _) = Sheets.paperIn(current)
                val onLeft = (Sheets.viewRect(picked, state)?.center?.x ?: 0f) > wIn / 2f
                ToolCard(picked.title.ifBlank { "Viewport" }, onClose = { pickedId = null },
                    modifier = Modifier.align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)) {
                    ViewportFields(current, picked, state, ::save, onEvent) { cropping = it }
                }
            }
        }
    }

    cropping?.let { v ->
        if (current != null) CropDialog(v, state, onDismiss = { cropping = null }) { crop: ViewCrop ->
            save(current.copy(views = current.views.map { if (it.id == v.id) it.copy(crop = crop) else it }))
            cropping = null
        }
    }
}

/** A tool card: a titled panel over the sheet, scrolling, with a close button. */
@Composable
private fun ToolCard(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.padding(8.dp).width(360.dp).heightIn(max = 2000.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            content()
        }
    }
}

/**
 * Add view, in groups that open like a dropdown (Dan, Nov 28): Plans, Elevations, Schedules, Details, Notes & cover.
 * Views already on this sheet show ticked and can't be added twice. With no sheet open, the first view picked makes one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddViewGroups(
    state: CanvasState,
    sheet: SheetLayout?,
    sheets: List<SheetLayout>,
    setSheets: (List<SheetLayout>) -> Unit,
    onSelect: (String) -> Unit,
    onEvent: (DrawingEvent) -> Unit
) {
    var open by remember { mutableStateOf<String?>("Plans") }
    fun onSheet(test: (SheetView) -> Boolean) = sheet?.views?.any(test) == true

    fun add(view: SheetView, isSection: Boolean = false) {
        if (Sheets.scheduleKind(view) != null) onEvent(DrawingEvent.ShowOpeningMarks(true))
        if (sheet == null) {
            val blank = SheetLayout(number = Sheets.nextNumber(Sheets.disciplineOf(view, state), sheets), title = if (isSection) "Wall Section" else view.title)
            if (isSection) { setSheets(sheets + blank); onEvent(DrawingEvent.AddWallSectionToSheet(blank.id)) }
            else setSheets(sheets + blank.copy(views = listOf(Sheets.placed(blank, view, state))))
            onSelect(blank.id)
            return
        }
        if (isSection) onEvent(DrawingEvent.AddWallSectionToSheet(sheet.id))
        else setSheets(SheetLayoutRules.added(sheet, view, sheets, state))
    }
    fun addDetail(kind: DetailKit.Detail) {
        if (sheet == null) {
            val blank = SheetLayout(number = Sheets.nextNumber(kind.series, sheets), title = kind.title)
            setSheets(sheets + blank)
            onEvent(DrawingEvent.AddDetailToSheet(blank.id, kind.ordinal))
            onSelect(blank.id)
        } else onEvent(DrawingEvent.AddDetailToSheet(sheet.id, kind.ordinal))
    }

    @Composable
    fun group(name: String, items: @Composable () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().clickable { open = if (open == name) null else name }) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(if (open == name) "▲" else "▼")
            }
        }
        if (open == name) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { items() }
    }
    @Composable
    fun choice(label: String, there: Boolean, onClick: () -> Unit) {
        FilterChip(selected = there, enabled = !there, onClick = onClick, label = { Text(if (there) "✓ $label" else label) })
    }

    if (sheet == null) Text("Pick the first view: the sheet is made with it and named after it.", style = MaterialTheme.typography.bodySmall)

    group("Plans") {
        Sheets.planChoices(state).forEach { (label, layerIds) ->
            val title = if (layerIds == null) "Floor Plan" else label
            choice(label, onSheet { it.kind == SheetViewKind.PLAN && it.layerIds == layerIds }) {
                val site = layerIds != null && layerIds.isNotEmpty() && layerIds.all { id -> state.effectiveLayers.firstOrNull { it.id == id }?.trade == com.example.sitedraft.ui.canvas.LayerTrade.SITE_PLAN }
                add(SheetView(kind = SheetViewKind.PLAN, title = title, layerIds = layerIds, scale = if (site) PrintScale.ENG_20 else PrintScale.QUARTER))
            }
        }
    }
    group("Elevations") {
        Sheets.exteriorChoices(state).forEach { (symbol, label) ->
            choice(label, onSheet { it.elementId == symbol.id }) { add(SheetView(kind = SheetViewKind.ELEVATION, elementId = symbol.id, title = label, scale = PrintScale.QUARTER)) }
        }
        Sheets.elevationChoices(state).forEach { (view, label) ->
            choice(label, onSheet { it.elementId == view.id }) { add(SheetView(kind = SheetViewKind.ELEVATION, elementId = view.id, title = label.substringBefore(" ("), scale = PrintScale.HALF)) }
        }
    }
    group("Schedules") {
        listOf(SheetViewKind.DOOR_SCHEDULE to "Door schedule", SheetViewKind.WINDOW_SCHEDULE to "Window schedule",
            SheetViewKind.FINISH_SCHEDULE to "Finish schedule", SheetViewKind.PAINT_SCHEDULE to "Paint schedule", SheetViewKind.TRIM_SCHEDULE to "Trim schedule",
            SheetViewKind.CABINET_SCHEDULE to "Cabinet & countertop schedule", SheetViewKind.APPLIANCE_SCHEDULE to "Appliance schedule",
            SheetViewKind.PLUMBING_SCHEDULE to "Plumbing fixture schedule").forEach { (kind, label) ->
            choice(label, onSheet { it.kind == kind }) { add(SheetView(kind = kind, title = label)) }
        }
    }
    group("Details") {
        choice("Typical wall section", onSheet { it.kind == SheetViewKind.SECTION }) {
            add(SheetView(kind = SheetViewKind.SECTION, title = "Typical Wall Section", scale = PrintScale.THREE_QUARTER), isSection = true)
        }
        DetailKit.Detail.entries.sortedBy { SheetLayoutRules.buildingOrder(it) }.forEach { kind ->
            val there = DetailKit.detailIn(state, kind)
            choice(kind.title, there != null && onSheet { it.elementId == there.id }) { addDetail(kind) }
        }
        choice("Structural calculations", onSheet { it.kind == SheetViewKind.STRUCTURAL_CALCS }) { add(SheetView(kind = SheetViewKind.STRUCTURAL_CALCS, title = "Structural calculations")) }
    }
    group("Notes & cover") {
        listOf(SheetViewKind.COVER_TITLE to "Cover title", SheetViewKind.SHEET_INDEX to "Sheet index", SheetViewKind.PROJECT_DATA to "Project data",
            SheetViewKind.LEGEND to "Legend and symbols", SheetViewKind.GENERAL_NOTES to "General notes").forEach { (kind, label) ->
            choice(label, onSheet { it.kind == kind }) { add(SheetView(kind = kind, title = label)) }
        }
    }
}
