package com.example.sitedraft.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.Building
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.FoundationType
import com.example.sitedraft.ui.canvas.LayerTrade
import com.example.sitedraft.ui.canvas.Layers
import com.example.sitedraft.ui.canvas.UpperFloor
import com.example.sitedraft.ui.canvas.activeLayer
import com.example.sitedraft.ui.canvas.effectiveLayers
import com.example.sitedraft.ui.canvas.formatInches
import com.example.sitedraft.ui.canvas.levels
import com.example.sitedraft.ui.canvas.parseFeet
import com.example.sitedraft.ui.canvas.parseInches

/**
 * Levels and layers (Building.kt): which layer new drawing goes on, which layers show, layers of
 * your own, moving the selection to a layer; the building inputs the levels are worked out from;
 * and the way into paper space (the sheets).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildingPanel(
    state: CanvasState,
    onEvent: (DrawingEvent) -> Unit,
    onOpenSheets: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val layers = state.effectiveLayers
    val active = state.activeLayer
    val levels = state.levels
    val hasSelection = state.selectedElementId != null || state.selectedElementIds.isNotEmpty()

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            // Model space is the canvas; paper space is the sheets, with views of chosen layers.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("Model") })
                FilterChip(selected = false, onClick = onOpenSheets, label = { Text("Paper (sheets)") })
            }
            Text(
                "Tap a layer to draw on it. Layers on lower levels show faint so you can trace over them. The eye shows or hides a layer.",
                style = MaterialTheme.typography.bodySmall
            )

            layers.forEach { layer ->
                val isActive = layer.id == active.id
                val count = state.elements.count { Layers.layerOf(it, state).id == layer.id }
                Row(
                    Modifier.fillMaxWidth().clickable { onEvent(DrawingEvent.SetActiveLayer(layer.id)) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isActive, onClick = { onEvent(DrawingEvent.SetActiveLayer(layer.id)) })
                    Column(Modifier.weight(1f)) {
                        Text(layer.name, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                        val faint = !isActive && layer.visible && Layers.isFaint(layer, state)
                        Text(
                            listOfNotNull("$count", if (faint) "faint" else null, if (!layer.visible) "hidden" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (layer.trade == LayerTrade.CUSTOM && count == 0) {
                        TextButton(onClick = { onEvent(DrawingEvent.RemoveLayer(layer.id)) }) { Text("Remove") }
                    }
                    IconButton(onClick = { onEvent(DrawingEvent.SetLayerVisible(layer.id, !layer.visible)) }, enabled = !isActive) {
                        Icon(if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = if (layer.visible) "Hide" else "Show")
                    }
                }
            }

            if (hasSelection) {
                OutlinedButton(onClick = { onEvent(DrawingEvent.MoveSelectionToLayer(active.id)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Move selection to ${active.name}")
                }
            }

            // --- A layer of your own ---
            HorizontalDivider()
            Text("Add a layer", style = MaterialTheme.typography.titleMedium)
            var newName by remember { mutableStateOf("") }
            var newLevel by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(newName, { newName = it }, label = { Text("Layer name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = newLevel == null, onClick = { newLevel = null }, label = { Text("Whole building") })
                levels.forEach { lv -> FilterChip(selected = newLevel == lv.id, onClick = { newLevel = lv.id }, label = { Text(lv.name) }) }
            }
            Button(onClick = { onEvent(DrawingEvent.AddLayer(newName, newLevel)); newName = "" }, enabled = newName.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Add layer")
            }

            // --- Levels and the inputs they come from ---
            HorizontalDivider()
            Text("Levels", style = MaterialTheme.typography.titleMedium)
            levels.asReversed().forEach { lv ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(lv.name)
                        if (lv.note.isNotEmpty()) Text(lv.note, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(lv.elevationIn?.let { Building.elevationText(it) } ?: "not set", fontWeight = FontWeight.Bold)
                }
            }
            BuildingInputs(state.building) { onEvent(DrawingEvent.SetBuilding(it)) }
        }
    }
}

/** The building inputs, edited as text and applied together (Apply), so a half-typed number never moves a level. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuildingInputs(settings: BuildingSettings, onApply: (BuildingSettings) -> Unit) {
    fun ft(inches: Float) = Building.elevationText(inches)
    fun inch(v: Float) = formatInches(v)
    var foundation by remember(settings) { mutableStateOf(settings.foundation) }
    var datum by remember(settings) { mutableStateOf(ft(settings.datumIn)) }
    var subfloor by remember(settings) { mutableStateOf(inch(settings.subfloorIn)) }
    var joist by remember(settings) { mutableStateOf(inch(settings.joistDepthIn)) }
    var sill by remember(settings) { mutableStateOf(inch(settings.sillIn)) }
    var courses by remember(settings) { mutableStateOf(settings.blockCourses.toString()) }
    var course by remember(settings) { mutableStateOf(inch(settings.courseIn)) }
    var crawl by remember(settings) { mutableStateOf(inch(settings.crawlClearanceIn)) }
    var footT by remember(settings) { mutableStateOf(inch(settings.footingThicknessIn)) }
    var footW by remember(settings) { mutableStateOf(inch(settings.footingWidthIn)) }
    var floorName by remember(settings) { mutableStateOf("${Building.ordinal(settings.upperFloors.size + 2)} floor") }
    var floorElev by remember(settings) { mutableStateOf("") }

    Text("Building inputs", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FoundationType.entries.forEach { t -> FilterChip(selected = foundation == t, onClick = { foundation = t }, label = { Text(t.label) }) }
    }
    @Composable
    fun field(label: String, value: String, modifier: Modifier, set: (String) -> Unit) =
        OutlinedTextField(value, set, label = { Text(label) }, singleLine = true, modifier = modifier)
    field("First floor, top of subfloor", datum, Modifier.fillMaxWidth()) { datum = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field("Subfloor", subfloor, Modifier.weight(1f)) { subfloor = it }
        field("Joist depth", joist, Modifier.weight(1f)) { joist = it }
        field("Sill", sill, Modifier.weight(1f)) { sill = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field("Block courses", courses, Modifier.weight(1f)) { courses = it }
        field("Course height", course, Modifier.weight(1f)) { course = it }
        if (foundation == FoundationType.CRAWL) field("Crawl clearance", crawl, Modifier.weight(1f)) { crawl = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field("Footing thick", footT, Modifier.weight(1f)) { footT = it }
        field("Footing wide", footW, Modifier.weight(1f)) { footW = it }
    }

    val parsed: BuildingSettings? = run {
        val d = parseFeet(datum) ?: return@run null
        settings.copy(
            datumIn = d * 12f,
            foundation = foundation,
            subfloorIn = parseInches(subfloor) ?: return@run null,
            joistDepthIn = parseInches(joist) ?: return@run null,
            sillIn = parseInches(sill) ?: return@run null,
            blockCourses = courses.trim().toIntOrNull() ?: return@run null,
            courseIn = parseInches(course) ?: return@run null,
            crawlClearanceIn = parseInches(crawl) ?: return@run null,
            footingThicknessIn = parseInches(footT) ?: return@run null,
            footingWidthIn = parseInches(footW) ?: return@run null
        )
    }
    Button(onClick = { parsed?.let(onApply) }, enabled = parsed != null && parsed != settings, modifier = Modifier.fillMaxWidth()) {
        Text(if (parsed == null) "Check the numbers" else "Apply")
    }

    // --- Upper floors ---
    Text("Upper floors", style = MaterialTheme.typography.titleMedium)
    settings.upperFloors.forEach { f ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(f.name); Text(ft(f.elevationIn), fontWeight = FontWeight.Bold)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field("Floor name", floorName, Modifier.weight(1f)) { floorName = it }
        field("Top of subfloor", floorElev, Modifier.weight(1f)) { floorElev = it }
    }
    val elev = parseFeet(floorElev)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = {
            if (elev != null) onApply(settings.copy(upperFloors = settings.upperFloors + UpperFloor(floorName.trim(), elev * 12f)))
        }, enabled = elev != null && floorName.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Add floor") }
        if (settings.upperFloors.isNotEmpty()) OutlinedButton(onClick = {
            onApply(settings.copy(upperFloors = settings.upperFloors.dropLast(1)))
        }, modifier = Modifier.weight(1f)) { Text("Remove ${settings.upperFloors.last().name}") }
    }
}
