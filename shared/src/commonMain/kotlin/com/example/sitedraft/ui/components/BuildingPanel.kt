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
import com.example.sitedraft.ui.canvas.FoundationWall
import com.example.sitedraft.ui.canvas.DrainTile
import com.example.sitedraft.ui.canvas.LayerTrade
import com.example.sitedraft.ui.canvas.Layers
import com.example.sitedraft.ui.canvas.UpperFloor
import com.example.sitedraft.ui.canvas.activeLayer
import com.example.sitedraft.ui.canvas.effectiveLayers
import com.example.sitedraft.ui.canvas.formatInches
import com.example.sitedraft.ui.canvas.levels
import com.example.sitedraft.ui.canvas.parseFeet
import com.example.sitedraft.ui.canvas.parseInches
import com.example.sitedraft.ui.canvas.DerivedLayers

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
    /** Opens the symbol library (it shows the active layer's tools first). */
    onOpenTools: () -> Unit = {},
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
                "Tap a layer to draw on it. It shows at full strength with the floor plan faint underneath; other layers stay hidden. The eye turns a layer on (faint) or off.",
                style = MaterialTheme.typography.bodySmall
            )

            val display = Layers.displayMap(state)
            layers.forEach { layer ->
                val isActive = layer.id == active.id
                val shown = (display[layer.id] ?: Layers.Display.HIDDEN) != Layers.Display.HIDDEN
                val count = state.elements.count { Layers.layerOf(it, state).id == layer.id }
                Row(
                    Modifier.fillMaxWidth().clickable { onEvent(DrawingEvent.SetActiveLayer(layer.id)) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isActive, onClick = { onEvent(DrawingEvent.SetActiveLayer(layer.id)) })
                    Column(Modifier.weight(1f)) {
                        Text(layer.name, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                        val faint = display[layer.id] == Layers.Display.FAINT
                        Text(
                            listOfNotNull("$count", if (faint) "faint" else null, if (!shown) "hidden" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (layer.trade == LayerTrade.CUSTOM && count == 0) {
                        TextButton(onClick = { onEvent(DrawingEvent.RemoveLayer(layer.id)) }) { Text("Remove") }
                    }
                    IconButton(onClick = { onEvent(DrawingEvent.ToggleLayerShown(layer.id)) }, enabled = !isActive) {
                        Icon(if (shown) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = if (shown) "Hide" else "Show")
                    }
                }
            }

            // The roof: set up and edited here when its layer is active (RoofPlan).
            if (active.trade == LayerTrade.ROOF_STRUCTURAL) {
                Text("Roof", style = MaterialTheme.typography.titleMedium)
                Text("Built over the first-floor exterior walls and kept up with them.", style = MaterialTheme.typography.bodySmall)
                RoofInputs(state) { onEvent(DrawingEvent.SetBuilding(it)) }
            }

            // The roofing: picked on the Roof plan layer (the roof itself is set on Roof framing).
            if (active.trade == LayerTrade.ROOF_PLAN) {
                Text("Roofing", style = MaterialTheme.typography.titleMedium)
                RoofCoveringInputs(state) { onEvent(DrawingEvent.SetBuilding(it)) }
            }

            // The lot from the survey: typed in here when the Site layer is active (SiteLot).
            if (active.trade == LayerTrade.SITE_PLAN) {
                Text("Site plan", style = MaterialTheme.typography.titleMedium)
                SiteInputs(state) { onEvent(DrawingEvent.SetBuilding(it)) }
            }

            // The exterior elevations: made and edited here when their layer is active (ExteriorElevation).
            if (active.trade == LayerTrade.EXTERIOR_ELEVATION) {
                Text("Exterior elevations", style = MaterialTheme.typography.titleMedium)
                ExteriorInputs(state) { onEvent(DrawingEvent.SetBuilding(it)) }
            }

            // What can be drawn on the active layer, one tap away.
            val tools = when (active.trade) {
                LayerTrade.FOUNDATION -> "Footings, pad footings, piers, slabs and 8\" block or concrete walls"
                LayerTrade.FLOOR_STRUCTURAL -> "Joist bays, beams, posts, floor openings and ladders"
                LayerTrade.FLOOR_PLAN -> "Walls, doors, windows, fixtures (Floor Plan tool and Symbols)"
                else -> null
            }
            if (tools != null) {
                Text("Draw on ${active.name}: $tools", style = MaterialTheme.typography.bodySmall)
                if (active.trade == LayerTrade.FOUNDATION || active.trade == LayerTrade.FLOOR_STRUCTURAL) {
                    Button(onClick = onOpenTools, modifier = Modifier.fillMaxWidth()) {
                        Text(if (active.trade == LayerTrade.FOUNDATION) "Foundation tools" else "Framing tools")
                    }
                    if (!state.building.autoLayers) {
                        OutlinedButton(onClick = { onEvent(DrawingEvent.MatchFloorPlan) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Match floor plan")
                        }
                    }
                }
            }

            // --- Remodel: existing, demo and new walls (Remodel.kt) ---
            HorizontalDivider()
            RemodelControls(state, onEvent)

            // --- Layers built from the floor plan (DerivedLayers) ---
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Build from the floor plan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.building.foundation == FoundationType.SLAB)
                            "Slab on grade: no foundation or floor framing layer to build yet."
                        else
                            "Foundation walls and footings under the exterior walls, joists, beams, posts and pads, and a ladder under every wall that runs with the joists. Parts you edit or delete stay that way.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = state.building.autoLayers, onCheckedChange = { onEvent(DrawingEvent.SetAutoLayers(it)) })
            }
            if (state.building.autoLayers) {
                val built = state.elements.count { DerivedLayers.isGenerated(it) }
                val edited = state.elements.count { DerivedLayers.isGenerated(it) && !DerivedLayers.isUntouched(it) }
                Text(
                    "$built parts built" + (if (edited > 0) ", $edited edited by hand" else "") +
                        (if (state.suppressedGenIds.isNotEmpty()) ", ${state.suppressedGenIds.size} deleted" else ""),
                    style = MaterialTheme.typography.bodySmall
                )
                if (edited > 0 || state.suppressedGenIds.isNotEmpty()) {
                    OutlinedButton(onClick = { onEvent(DrawingEvent.RebuildLayers) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Rebuild (drops hand edits)")
                    }
                }
            }
            HorizontalDivider()

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
            BuildingInputs(state.building, com.example.sitedraft.ui.canvas.FootingCode.houseWidthFt(state)) { onEvent(DrawingEvent.SetBuilding(it)) }

            // The typical wall section lives in model space: placed once, edited there, shown on sheets.
            HorizontalDivider()
            Text("Typical wall section", style = MaterialTheme.typography.titleMedium)
            Text(
                "Drawn true size beside the plan from these inputs and the exterior walls. Select it to change its inputs; notes you add inside its frame go onto the sheet with it.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { onEvent(DrawingEvent.PlaceWallSection) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (com.example.sitedraft.ui.canvas.WallSection.sectionIn(state) == null) "Place in the drawing" else "Select it in the drawing")
            }
        }
    }
}

/** The building inputs, edited as text and applied together (Apply), so a half-typed number never moves a level. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BuildingInputs(settings: BuildingSettings, houseWidthFt: Float? = null, onApply: (BuildingSettings) -> Unit) {
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
    var slab by remember(settings) { mutableStateOf(inch(settings.basementSlabIn)) }
    var footT by remember(settings) { mutableStateOf(inch(settings.footingThicknessIn)) }
    var footW by remember(settings) { mutableStateOf(inch(settings.footingWidthIn)) }
    // Blank = centred under the wall (the code rule, Nov 23).
    var footP by remember(settings) { mutableStateOf(settings.footingOutsideIn?.let { inch(it) } ?: "") }
    var wallType by remember(settings) { mutableStateOf(settings.foundationWall) }
    var edition by remember(settings) { mutableStateOf(settings.codeEdition) }
    var drain by remember(settings) { mutableStateOf(settings.drainTile) }
    var rebar by remember(settings) { mutableStateOf(settings.rebarSize.toString()) }
    var barsTop by remember(settings) { mutableStateOf(settings.footingBarsTop.toString()) }
    var barsBot by remember(settings) { mutableStateOf(settings.footingBarsBottom.toString()) }
    var dowelOc by remember(settings) { mutableStateOf(inch(settings.dowelSpacingIn)) }
    var boltEnd by remember(settings) { mutableStateOf(inch(settings.anchorBoltEndIn)) }
    var boltOc by remember(settings) { mutableStateOf(inch(settings.anchorBoltSpacingIn)) }
    var foam by remember(settings) { mutableStateOf(inch(settings.foundationFoamIn)) }
    var floorName by remember(settings) { mutableStateOf("${Building.ordinal(settings.upperFloors.size + 2)} floor") }
    var floorElev by remember(settings) { mutableStateOf("") }

    Text("Building inputs", style = MaterialTheme.typography.titleMedium)
    Text("Code the job is drawn to", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.example.sitedraft.ui.canvas.CodeEdition.entries.forEach { e -> FilterChip(selected = edition == e, onClick = { edition = e }, label = { Text(e.label) }) }
    }
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
        // Top of the basement slab above the top of the footing: basement stairs size to it (Nov 24).
        if (foundation == FoundationType.BASEMENT) field("Slab above footing", slab, Modifier.weight(1f)) { slab = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field("Footing thick", footT, Modifier.weight(1f)) { footT = it }
        field("Footing wide", footW, Modifier.weight(1f)) { footW = it }
        field("Outside (blank = centered)", footP, Modifier.weight(1f)) { footP = it }
    }
    // The code's footing size (2015 IRC Table R403.1(1)) for these inputs, wide enough for the wall (Nov 23).
    run {
        val draft = settings.copy(foundation = foundation, foundationWall = wallType, codeEdition = edition)
        val code = com.example.sitedraft.ui.canvas.FootingCode.minimum(draft, houseWidthFt)
        val sug = com.example.sitedraft.ui.canvas.FootingCode.suggested(draft, houseWidthFt)
        Text(
            if (code.widthIn == null) code.note
            else "Code minimum ${inch(code.widthIn)}\" x ${inch(code.thickIn ?: 6f)}\" (${code.note}). Snow load and soil are in Structure." +
                (sug?.let { if (it.first > code.widthIn) " With the ${wallType.callout.lowercase()} wall it needs ${inch(it.first)}\" wide to stick out 2\" each side." else "" } ?: ""),
            style = MaterialTheme.typography.bodySmall
        )
        val w = parseInches(footW); val t = parseInches(footT)
        if (sug != null && w != null && t != null) {
            if (w < sug.first - 0.01f || t < sug.second - 0.01f)
                Text("This footing is under the code size.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { footW = inch(sug.first); footT = inch(maxOf(sug.second, t)); footP = "" }) {
                Text("Use the code size: ${inch(sug.first)}\" x ${inch(maxOf(sug.second, t))}\", centered")
            }
        }
    }
    if (foundation != FoundationType.SLAB) {
        Text("Foundation wall", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FoundationWall.entries.forEach { t -> FilterChip(selected = wallType == t, onClick = { wallType = t }, label = { Text(t.label) }) }
        }
        if (wallType == FoundationWall.BLOCK_8 && foundation == FoundationType.BASEMENT)
            Text("8\" block is for above grade; a basement with earth against it is 12\" block.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
    }
    Text("Footing rebar and drain tile", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field("Bar size #", rebar, Modifier.weight(1f)) { rebar = it }
        field("Bars top", barsTop, Modifier.weight(1f)) { barsTop = it }
        field("Bars bottom", barsBot, Modifier.weight(1f)) { barsBot = it }
        field("Dowels o.c.", dowelOc, Modifier.weight(1f)) { dowelOc = it }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DrainTile.entries.forEach { t -> FilterChip(selected = drain == t, onClick = { drain = t }, label = { Text(t.label) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field("Bolts from corner", boltEnd, Modifier.weight(1f)) { boltEnd = it }
        field("Bolts o.c.", boltOc, Modifier.weight(1f)) { boltOc = it }
        field("Foam outside", foam, Modifier.weight(1f)) { foam = it }
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
            basementSlabIn = parseInches(slab) ?: return@run null,
            footingThicknessIn = parseInches(footT) ?: return@run null,
            footingWidthIn = parseInches(footW) ?: return@run null,
            footingOutsideIn = if (footP.isBlank()) null else parseInches(footP) ?: return@run null,
            foundationWall = wallType,
            codeEdition = edition,
            drainTile = drain,
            rebarSize = rebar.trim().toIntOrNull()?.takeIf { it in 3..8 } ?: return@run null,
            footingBarsTop = barsTop.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return@run null,
            footingBarsBottom = barsBot.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return@run null,
            dowelSpacingIn = parseInches(dowelOc)?.takeIf { it > 0f } ?: return@run null,
            anchorBoltEndIn = parseInches(boltEnd) ?: return@run null,
            anchorBoltSpacingIn = parseInches(boltOc)?.takeIf { it > 0f } ?: return@run null,
            foundationFoamIn = parseInches(foam) ?: return@run null
        )
    }
    // The footing against the code's projection rule (at least 2" each side, no more than its thickness).
    (parsed ?: settings).let { Building.footingCodeIssues(it) }.forEach {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
