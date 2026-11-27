@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sitedraft.ui.components

import com.example.sitedraft.util.format
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import com.example.sitedraft.data.rememberRecentColors
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenWith
import kotlin.math.atan2
import kotlin.math.floor

@Composable
fun PropertiesPanel(
    state: CanvasState,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val selectedElement = state.elements.find { it.id == state.selectedElementId }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        // Explicit: a translucent colour matches no theme colour, so contentColorFor() fell back to
        // black and the panel's titles were black on blue.
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Properties",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            if (selectedElement != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    // Explicit: a translucent colour matches no theme colour (contentColorFor() fell back to black).
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        PropertyRow("Type", selectedElement::class.simpleName ?: "Unknown")
                        // The typical wall section (model space): what it is drawn from, edited right here.
                        if (com.example.sitedraft.ui.canvas.RoofPlan.isRoof(selectedElement)) {
                            Text("Roof", style = MaterialTheme.typography.titleSmall)
                            Text("Built over the first-floor exterior walls from these inputs.", style = MaterialTheme.typography.bodySmall)
                            RoofInputs(state) { onEvent(DrawingEvent.SetBuilding(it)) }
                        }
                        if (com.example.sitedraft.ui.canvas.RoofPlan.isRoofFinish(selectedElement)) {
                            Text("Roof plan", style = MaterialTheme.typography.titleSmall)
                            Text("The roof from the Roof framing layer, with its roofing. Pick the roofing here.", style = MaterialTheme.typography.bodySmall)
                            RoofCoveringInputs(state) { onEvent(DrawingEvent.SetBuilding(it)) }
                        }
                        if (com.example.sitedraft.ui.canvas.Garages.isGarage(selectedElement)) {
                            val ga = com.example.sitedraft.ui.canvas.Garages
                            val sym = selectedElement as com.example.sitedraft.ui.canvas.DrawingElement.Symbol
                            val attached = ga.attachment(sym, com.example.sitedraft.ui.canvas.RoofPlan.footprint(state), state.inchesToPx(1f)) != null
                            Text(if (attached) "Attached garage" else "Detached garage", style = MaterialTheme.typography.titleSmall)
                            Text("Attached when it's against an exterior wall, detached otherwise. Size it with the handles or Width/Depth. " +
                                "The foundation layer gets 12\" block frost walls on a footing at 42\" frost depth" +
                                (if (attached) ", butting the house footing (stepping down to a basement footing with lead walls)" else "") +
                                "; the framing layer gets the 4\" slab.", style = MaterialTheme.typography.bodySmall)
                            // Where it goes: a house wall, the distance from its corner, the gap (0 = attached).
                            val fpG = com.example.sitedraft.ui.canvas.RoofPlan.footprint(state)
                            val inchG = state.inchesToPx(1f)
                            val now = ga.placement(sym, fpG, inchG)
                            if (now != null) {
                                Text("Location", style = MaterialTheme.typography.labelLarge)
                                var wall by remember(sym.center, sym.size) { mutableStateOf(now.first) }
                                var from by remember(sym.center, sym.size) { mutableStateOf(com.example.sitedraft.ui.canvas.Building.elevationText(now.second.coerceAtLeast(0f))) }
                                var gap by remember(sym.center, sym.size) { mutableStateOf(com.example.sitedraft.ui.canvas.Building.elevationText(now.third.coerceAtLeast(0f))) }
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                    listOf("North wall", "South wall", "West wall", "East wall").forEachIndexed { i, l -> FilterChip(selected = wall == i, onClick = { wall = i }, label = { Text(l) }) }
                                }
                                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(from, { from = it }, singleLine = true, modifier = Modifier.weight(1f),
                                        label = { Text(if (wall <= 1) "From the west corner" else "From the north corner") })
                                    OutlinedTextField(gap, { gap = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Gap from house (0 = attached)") })
                                }
                                val fromIn = com.example.sitedraft.ui.canvas.parseFeet(from)?.times(12f)
                                val gapIn = com.example.sitedraft.ui.canvas.parseFeet(gap)?.times(12f)
                                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, ga.PLACE, "$wall,$fromIn,$gapIn")) },
                                        enabled = fromIn != null && gapIn != null) { Text("Move it there") }
                                    OutlinedButton(onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, ga.CENTER, "1")) }) { Text("Center on the house wall") }
                                }
                            }
                            Text("Overhead door wall", style = MaterialTheme.typography.labelLarge)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                ga.doorSides.forEachIndexed { i, l -> FilterChip(selected = ga.prop(sym, ga.DOOR_SIDE) == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, ga.DOOR_SIDE, "$i")) }, label = { Text(l) }) }
                            }
                            Text("Doors", style = MaterialTheme.typography.labelLarge)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                ga.doorChoices.forEachIndexed { i, l -> FilterChip(selected = ga.prop(sym, ga.DOOR) == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, ga.DOOR, "$i")) }, label = { Text(l) }) }
                            }
                            val hdr = ga.header(sym, state)
                            val dh = ga.doorHeightIn(sym, state)
                            Text("Doors ${if (dh >= ga.TALL_DOOR_IN) "8'" else "7'"} high" + (if (dh < ga.TALL_DOOR_IN) " (8' doesn't fit under the header and plate)" else "") +
                                ". Header: ${hdr.name}, sized for ${com.example.sitedraft.ui.canvas.formatFeetInches(hdr.spanFt)} span, ${hdr.wPlf.toInt()} plf (snow and roof dead).",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Roof", style = MaterialTheme.typography.labelLarge)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                ga.roofStyles.forEachIndexed { i, l -> FilterChip(selected = ga.prop(sym, ga.ROOF_STYLE) == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, ga.ROOF_STYLE, "$i")) }, label = { Text(l) }) }
                            }
                        }
                        if (com.example.sitedraft.ui.canvas.Wings.isWing(selectedElement)) {
                            val wg = com.example.sitedraft.ui.canvas.Wings
                            val ga = com.example.sitedraft.ui.canvas.Garages
                            val sym = selectedElement as com.example.sitedraft.ui.canvas.DrawingElement.Symbol
                            val fpW = com.example.sitedraft.ui.canvas.RoofPlan.footprint(state)
                            val inchW = state.inchesToPx(1f)
                            Text("Wing", style = MaterialTheme.typography.titleSmall)
                            Text("Against a house wall the plan becomes an L: the house wall at the joint comes out, the wing's walls, " +
                                "foundation, floor, roof and elevations follow. Size it with the handles or Width/Depth.", style = MaterialTheme.typography.bodySmall)
                            val now = ga.placement(sym, fpW, inchW)
                            if (now != null) {
                                Text("Location", style = MaterialTheme.typography.labelLarge)
                                var wall by remember(sym.center, sym.size) { mutableStateOf(now.first) }
                                var from by remember(sym.center, sym.size) { mutableStateOf(com.example.sitedraft.ui.canvas.Building.elevationText(now.second.coerceAtLeast(0f))) }
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                    listOf("North wall", "South wall", "West wall", "East wall").forEachIndexed { i, l -> FilterChip(selected = wall == i, onClick = { wall = i }, label = { Text(l) }) }
                                }
                                OutlinedTextField(from, { from = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    label = { Text(if (wall <= 1) "From the west corner" else "From the north corner") })
                                val fromIn = com.example.sitedraft.ui.canvas.parseFeet(from)?.times(12f)
                                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, ga.PLACE, "$wall,$fromIn,0")) }, enabled = fromIn != null) { Text("Move it there") }
                                    OutlinedButton(onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, ga.CENTER, "1")) }) { Text("Center on the house wall") }
                                }
                            }
                            @Composable
                            fun wingChoice(title: String, key: String, labels: List<String>) {
                                Text(title, style = MaterialTheme.typography.labelLarge)
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                    labels.forEachIndexed { i, l -> FilterChip(selected = wg.prop(sym, key) == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, key, "$i")) }, label = { Text(l) }) }
                                }
                            }
                            wingChoice("Foundation", wg.FOUND, wg.foundations)
                            Text(when (wg.foundationOf(sym, state.building)) {
                                com.example.sitedraft.ui.canvas.FoundationType.BASEMENT -> "Basement: the house basement wall turns and follows the wing."
                                else -> "Footings at ${ga.FROST_IN.toInt()}\" frost depth; the house basement wall stays" +
                                    (if (state.building.foundation == com.example.sitedraft.ui.canvas.FoundationType.BASEMENT) ", the side footings step down to it with 4' lead walls." else ".")
                            }, style = MaterialTheme.typography.bodySmall)
                            wingChoice("Floor joists", wg.JOISTS, wg.joistChoices)
                            wingChoice("Roof, far end", wg.END, wg.ends)
                            // Dan, Nov 26 (the Ransom House): a house as it stands, its parts already drawn wall by wall.
                            wingChoice("Walls", wg.WALLS, wg.wallChoices)
                            wingChoice("Height", wg.FULL, wg.heightChoices)
                            if (!wg.fullHeight(sym)) {
                                var wallH by remember(sym.id, sym.properties[wg.WALL_H]) {
                                    mutableStateOf((sym.properties[wg.WALL_H] ?: 0f).takeIf { it > 0f }?.let { com.example.sitedraft.ui.canvas.formatInches(it) } ?: "")
                                }
                                OutlinedTextField(wallH, { v ->
                                    wallH = v
                                    val inches = if (v.isBlank()) 0f else com.example.sitedraft.ui.canvas.parseInches(v)
                                    if (inches != null) onEvent(DrawingEvent.UpdateElementProperty(sym.id, wg.WALL_H, "$inches"))
                                }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Wall height, inches (blank = the house's)") })
                            }
                            var pitch by remember(sym.id, sym.properties[wg.PITCH]) {
                                mutableStateOf((sym.properties[wg.PITCH] ?: 0f).takeIf { it > 0f }?.let { com.example.sitedraft.ui.canvas.formatInches(it) } ?: "")
                            }
                            OutlinedTextField(pitch, { v ->
                                pitch = v
                                val p = if (v.isBlank()) 0f else com.example.sitedraft.ui.canvas.parseInches(v.substringBefore('/'))
                                if (p != null && p >= 0f && p <= 24f) onEvent(DrawingEvent.UpdateElementProperty(sym.id, wg.PITCH, "$p"))
                            }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                label = { Text("Roof pitch, rise per 12 (blank = the house's)") })
                        }
                        if (com.example.sitedraft.ui.canvas.Porches.isPorch(selectedElement)) {
                            val pc = com.example.sitedraft.ui.canvas.Porches
                            val sym = selectedElement as com.example.sitedraft.ui.canvas.DrawingElement.Symbol
                            Text("Porch", style = MaterialTheme.typography.titleSmall)
                            Text("Put it against an exterior wall; the foundation layer builds what holds it up (with Build from the floor plan on). " +
                                "Framed and masonry porches get a continuous footing at basement footing level, tied into the house footing; " +
                                "wood deck posts go to frost depth (${pc.FROST_IN.toInt()}\").", style = MaterialTheme.typography.bodySmall)
                            @Composable
                            fun choice(title: String, key: String, labels: List<String>) {
                                Text(title, style = MaterialTheme.typography.labelLarge)
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                    labels.forEachIndexed { i, l ->
                                        FilterChip(selected = pc.prop(sym, key) == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, key, "$i")) }, label = { Text(l) })
                                    }
                                }
                            }
                            choice("Kind", pc.TYPE, pc.types)
                            choice("Roof", pc.ROOF, listOf("No roof", "Roof on columns"))
                            if (pc.prop(sym, pc.ROOF) == 1) {
                                choice("Roof style", pc.ROOF_STYLE, pc.roofStyles)
                                val pitch = pc.pitchOf(sym, state.building)
                                Text("Pitch", style = MaterialTheme.typography.labelLarge)
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                    listOf(2f, 3f, 4f, 5f, 6f, 8f, 10f, 12f).forEach { pv ->
                                        FilterChip(selected = pitch == pv, onClick = { onEvent(DrawingEvent.UpdateElementProperty(sym.id, pc.PITCH, "$pv")) },
                                            label = { Text("${pv.toInt()}/12") })
                                    }
                                }
                                Text("The roof shows on the Roof framing and Roof plan layers, tied into the house wall, past the columns by the house roof's overhang.",
                                    style = MaterialTheme.typography.bodySmall)
                                choice("Columns", pc.COLUMNS, pc.columnTypes)
                            }
                            if (pc.typeOf(sym) == 2) choice("Posts", pc.PIER, pc.pierTypes)
                        }
                        if (com.example.sitedraft.ui.canvas.Callouts.isCallout(selectedElement)) {
                            val co = com.example.sitedraft.ui.canvas.Callouts
                            val sym = selectedElement as com.example.sitedraft.ui.canvas.DrawingElement.Symbol
                            val on = co.targets(sym).toSet()
                            Text(sym.symbolName, style = MaterialTheme.typography.titleSmall)
                            Text("Points to (pick one or more). The bubble reads the drawing numbers over the sheet, from the sheet set: " +
                                co.bubbles(sym, state).joinToString("  ") { "${it.first} / ${it.second}" } +
                                (if (sym.symbolName == co.SECTION) ". Turn it to point the triangle the way the section looks." else "."),
                                style = MaterialTheme.typography.bodySmall)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                com.example.sitedraft.ui.canvas.Callouts.Target.entries.forEach { t ->
                                    FilterChip(selected = t in on, onClick = {
                                        val next = if (t in on) on - t else on + t
                                        onEvent(DrawingEvent.UpdateElementProperty(sym.id, co.TARGETS, next.sumOf { it.bit }.toString()))
                                    }, label = { Text(t.label) })
                                }
                            }
                        }
                        if (com.example.sitedraft.ui.canvas.WallSection.isSection(selectedElement)) {
                            Text("Typical wall section", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Drawn from the building inputs below and the first-floor exterior walls (${com.example.sitedraft.ui.canvas.WallSection.wallLabel(state)}). " +
                                    "Change them here: the section, the levels and the foundation all follow. Text and dimensions you add inside its frame print with it on the sheet.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            BuildingInputs(state.building, com.example.sitedraft.ui.canvas.FootingCode.houseWidthFt(state)) { onEvent(DrawingEvent.SetBuilding(it)) }
                        }
                        
                        if (selectedElement !is DrawingElement.Image && selectedElement !is DrawingElement.Pdf) {
                            Text(text = "Stroke Width", style = MaterialTheme.typography.labelSmall)
                            Slider(
                                value = selectedElement.strokeWidth,
                                onValueChange = { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "strokeWidth", it.toString())) },
                                valueRange = 0.1f..20f
                            )
                            Text(text = "%.1f px".format(selectedElement.strokeWidth), style = MaterialTheme.typography.bodySmall)
                        }
                        if (selectedElement !is DrawingElement.Image && selectedElement !is DrawingElement.Pdf && selectedElement !is DrawingElement.Dxf) {
                            // Colour of the selected element. Applied on commit only (swatch tap,
                            // valid hex, drag or slider release): updateElementProperty snapshots
                            // undo on every call, so applying each drag frame would bury the undo
                            // stack under hundreds of near-identical entries.
                            var showColorPicker by remember(selectedElement.id) { mutableStateOf(false) }
                            val recent = rememberRecentColors()
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showColorPicker = !showColorPicker },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Color", style = MaterialTheme.typography.labelSmall)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(text = selectedElement.color.toHexString(), style = MaterialTheme.typography.bodySmall)
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(selectedElement.color)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    )
                                    Text(
                                        text = if (showColorPicker) "Hide" else "Change",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (showColorPicker) {
                                Spacer(modifier = Modifier.height(8.dp))
                                ColorPicker(
                                    color = selectedElement.color,
                                    onColorChange = { /* live preview stays inside the picker - see note above */ },
                                    onColorCommitted = {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "color", it.toHexString()))
                                        recent.add(it)
                                    },
                                    recentColors = recent.colors
                                )
                            }
                        }
                        if (selectedElement is DrawingElement.Pdf) {
                            PdfPlanControls(selectedElement, onEvent)
                        }
                        selectedElement.fillOrNull()?.let { fill ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "Fill", style = MaterialTheme.typography.labelSmall)
                            FillPatternChips(selected = fill.pattern) { p ->
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fillPattern", p.name))
                            }
                            if (fill.pattern != FillPattern.NONE) {
                                var showFillPicker by remember(selectedElement.id) { mutableStateOf(false) }
                                val fillColor = fill.color ?: selectedElement.color
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { showFillPicker = !showFillPicker },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "Fill Color", style = MaterialTheme.typography.labelSmall)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = if (fill.color == null) "Same as line" else fillColor.toHexString(),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(fillColor)
                                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                        )
                                        Text(
                                            text = if (showFillPicker) "Hide" else "Change",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (showFillPicker) {
                                    val fillRecent = rememberRecentColors()
                                    if (fill.color != null) {
                                        TextButton(onClick = { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fillColor", "auto")) }) {
                                            Text("Use line color")
                                        }
                                    }
                                    ColorPicker(
                                        color = fillColor,
                                        onColorChange = { /* applied on commit - see the Color row above */ },
                                        onColorCommitted = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fillColor", it.toHexString()))
                                            fillRecent.add(it)
                                        },
                                        recentColors = fillRecent.colors
                                    )
                                }
                                // Both sliders commit on release, not per frame: updateElementProperty
                                // snapshots undo on every call.
                                var fillSpacing by remember(selectedElement.id, fill.spacing) { mutableStateOf(fill.spacing) }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Pattern size ${formatWorldLength(fillSpacing, state)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Slider(
                                    value = fillSpacing,
                                    onValueChange = { fillSpacing = it },
                                    onValueChangeFinished = {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fillSpacing", fillSpacing.toString()))
                                    },
                                    valueRange = 2f..120f
                                )
                                // Tile patterns: the tile's real width and length, typed in inches.
                                if (fill.pattern in listOf(FillPattern.TILE, FillPattern.BRICK, FillPattern.PLANKS, FillPattern.HERRINGBONE)) {
                                    val pxPerIn = state.inchesToPx(1f)
                                    EditableProperty("Tile width (in)", formatInches(fill.spacing / pxPerIn)) {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fillWidthIn", it))
                                    }
                                    EditableProperty("Tile length (in, 0 = auto)", formatInches(fill.length / pxPerIn)) {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fillLengthIn", it))
                                    }
                                }
                                var fillLineWidth by remember(selectedElement.id, fill.lineWidth) { mutableStateOf(fill.lineWidth) }
                                Text(text = "Line weight %.1f px".format(fillLineWidth), style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = fillLineWidth,
                                    onValueChange = { fillLineWidth = it },
                                    onValueChangeFinished = {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fillLineWidth", fillLineWidth.toString()))
                                    },
                                    valueRange = 0.5f..6f
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                when (selectedElement) {
                    is DrawingElement.Line -> {
                        val length = (selectedElement.end - selectedElement.start).getDistance() / state.scale.ratio
                        if (state.unitSystem == UnitSystem.IMPERIAL) {
                            FeetInchesProperty("Length", length) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "length", it.toString()))
                            }
                        } else {
                            EditableProperty("Length", "%.2f".format(length)) { 
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "length", it))
                            }
                        }
                    }
                    is DrawingElement.Rectangle -> {
                        val width = selectedElement.size.width / state.scale.ratio
                        val height = selectedElement.size.height / state.scale.ratio
                        if (state.unitSystem == UnitSystem.IMPERIAL) {
                            FeetInchesProperty("Width", width) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it.toString()))
                            }
                            FeetInchesProperty("Height", height) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it.toString()))
                            }
                        } else {
                            EditableProperty("Width", "%.2f".format(width)) {
                                 onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it))
                            }
                            EditableProperty("Height", "%.2f".format(height)) {
                                 onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it))
                            }
                        }
                    }
                    is DrawingElement.Triangle -> {
                        val width = selectedElement.size.width / state.scale.ratio
                        val height = selectedElement.size.height / state.scale.ratio
                        if (state.unitSystem == UnitSystem.IMPERIAL) {
                            FeetInchesProperty("Width", width) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it.toString()))
                            }
                            FeetInchesProperty("Height", height) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it.toString()))
                            }
                        } else {
                            EditableProperty("Width", "%.2f".format(width)) {
                                 onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it))
                            }
                            EditableProperty("Height", "%.2f".format(height)) {
                                 onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it))
                            }
                        }

                        var expanded by remember { mutableStateOf(false) }
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(text = "Triangle Type", style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { expanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(selectedElement.triangleType)
                                        Text("▼", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    modifier = Modifier.width(260.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Isosceles") },
                                        onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "triangleType", "Isosceles"))
                                            expanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Right-Angled") },
                                        onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "triangleType", "Right-Angled"))
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    is DrawingElement.Dimension -> {
                        if (state.unitSystem == UnitSystem.IMPERIAL) {
                            FeetInchesProperty("Value", selectedElement.value) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "length", it.toString()))
                            }
                        } else {
                            EditableProperty("Value", "%.2f".format(selectedElement.value)) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "length", it))
                            }
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Format", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            SegmentedButton(selectedElement.format == DimensionFormat.ARCHITECTURAL, { onEvent(DrawingEvent.SelectDimensionFormat(DimensionFormat.ARCHITECTURAL)) }, "Arch")
                            SegmentedButton(selectedElement.format == DimensionFormat.ENGINEERING, { onEvent(DrawingEvent.SelectDimensionFormat(DimensionFormat.ENGINEERING)) }, "Eng")
                        }
                    }
                    is DrawingElement.Text -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "Text Content", style = MaterialTheme.typography.labelSmall)
                            OutlinedTextField(
                                value = selectedElement.text,
                                onValueChange = { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "text", it)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                            
                            EditableProperty("Font Size", selectedElement.fontSize.toString()) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fontSize", it))
                            }
                            
                            Text(text = "Font Family", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                SegmentedButton(selectedElement.fontFamily == "SansSerif", { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fontFamily", "SansSerif")) }, "Sans")
                                SegmentedButton(selectedElement.fontFamily == "Serif", { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fontFamily", "Serif")) }, "Serif")
                                SegmentedButton(selectedElement.fontFamily == "Monospace", { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fontFamily", "Monospace")) }, "Mono")
                            }
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconToggleButton(
                                    checked = selectedElement.isBold,
                                    onCheckedChange = { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "isBold", it.toString())) }
                                ) {
                                    Icon(Icons.Default.FormatBold, contentDescription = "Bold")
                                }
                                IconToggleButton(
                                    checked = selectedElement.isItalic,
                                    onCheckedChange = { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "isItalic", it.toString())) }
                                ) {
                                    Icon(Icons.Default.FormatItalic, contentDescription = "Italic")
                                }
                            }
                            
                            Text(text = "Alignment & List", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                SegmentedButton(selectedElement.textAlign == "Left", { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "textAlign", "Left")) }, "L")
                                SegmentedButton(selectedElement.textAlign == "Center", { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "textAlign", "Center")) }, "C")
                                SegmentedButton(selectedElement.textAlign == "Right", { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "textAlign", "Right")) }, "R")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                SegmentedButton(selectedElement.listType == TextListType.NONE, { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "listType", "NONE")) }, "None")
                                SegmentedButton(selectedElement.listType == TextListType.BULLET, { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "listType", "BULLET")) }, "•")
                                SegmentedButton(selectedElement.listType == TextListType.NUMBER, { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "listType", "NUMBER")) }, "1.")
                            }
                        }
                    }
                    is DrawingElement.Circle -> {
                        val radius = selectedElement.radius / state.scale.ratio
                        val diameter = (selectedElement.radius * 2f) / state.scale.ratio
                        if (state.unitSystem == UnitSystem.IMPERIAL) {
                            FeetInchesProperty("Radius", radius) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "radius", it.toString()))
                            }
                            FeetInchesProperty("Diameter", diameter) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "diameter", it.toString()))
                            }
                        } else {
                            EditableProperty("Radius", "%.2f".format(radius)) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "radius", it))
                            }
                            EditableProperty("Diameter", "%.2f".format(diameter)) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "diameter", it))
                            }
                        }
                    }
                    is DrawingElement.Symbol -> {
                        // Move by a typed distance (Nov 27).
                        MoveBox(selectedElement, state, onEvent)
                        // A cabinet's real size and kind (Nov 27), not the library entry it was placed from.
                        PlanSymbols.cabinetName(selectedElement, state)?.let { named ->
                            Text("$named  (${PlanSymbols.cabinetLabel(selectedElement, state)})", style = MaterialTheme.typography.titleSmall)
                        }
                        // An appliance or plumbing fixture: its mark and what's typed for its schedule (Nov 26).
                        if (ItemSchedules.kindOf(selectedElement) != null) ItemSpecFields(selectedElement, state, onEvent)
                        if (selectedElement.category == "Lumber") {
                            val w = selectedElement.size.width / state.scale.ratio
                            val h = selectedElement.size.height / state.scale.ratio
                            if (state.unitSystem == UnitSystem.IMPERIAL) {
                                FeetInchesProperty("Nominal Width (Actual)", w) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it.toString()))
                                }
                                FeetInchesProperty("Nominal Height (Actual)", h) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it.toString()))
                                }
                            } else {
                                EditableProperty("Width", "%.2f".format(w)) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it))
                                }
                                EditableProperty("Height", "%.2f".format(h)) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it))
                                }
                            }
                        } else if (selectedElement.category == "Plywood") {
                            val thickness = selectedElement.size.width / state.scale.ratio
                            val length = selectedElement.size.height / state.scale.ratio
                            if (state.unitSystem == UnitSystem.IMPERIAL) {
                                FeetInchesProperty("Thickness", thickness) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it.toString()))
                                }
                                FeetInchesProperty("Length", length) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it.toString()))
                                }
                            } else {
                                EditableProperty("Thickness", "%.2f".format(thickness)) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it))
                                }
                                EditableProperty("Length", "%.2f".format(length)) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it))
                                }
                            }
                        } else if (selectedElement.symbolName == "I-Beam" || selectedElement.symbolName.contains("W-Beam")) {
                            val properties = selectedElement.properties.ifEmpty {
                                when (selectedElement.symbolName) {
                                    "W-Beam (W12x26)" -> mapOf("flangeWidth" to 6.49f, "webHeight" to 12.2f, "flangeThickness" to 0.38f, "webThickness" to 0.23f)
                                    "W-Beam (W10x49)" -> mapOf("flangeWidth" to 10.0f, "webHeight" to 10.0f, "flangeThickness" to 0.56f, "webThickness" to 0.34f)
                                    "W-Beam (W8x31)" -> mapOf("flangeWidth" to 8.0f, "webHeight" to 8.0f, "flangeThickness" to 0.435f, "webThickness" to 0.285f)
                                    "W-Beam (W6x15)" -> mapOf("flangeWidth" to 6.0f, "webHeight" to 6.0f, "flangeThickness" to 0.26f, "webThickness" to 0.23f)
                                    else -> mapOf("flangeWidth" to 6.0f, "webHeight" to 6.0f, "flangeThickness" to 0.38f, "webThickness" to 0.25f)
                                }
                            }
                            val displayUnit = if (state.unitSystem == com.example.sitedraft.ui.canvas.UnitSystem.METRIC) "mm" else "in"
                            
                            val fWidth = properties["flangeWidth"] ?: 6.0f
                            val wHeight = properties["webHeight"] ?: 6.0f
                            val fThickness = properties["flangeThickness"] ?: 0.38f
                            val wThickness = properties["webThickness"] ?: 0.25f
                            
                            val displayWidth = if (state.unitSystem == com.example.sitedraft.ui.canvas.UnitSystem.METRIC) fWidth * 25.4f else fWidth
                            val displayHeight = if (state.unitSystem == com.example.sitedraft.ui.canvas.UnitSystem.METRIC) wHeight * 25.4f else wHeight
                            val displayFThickness = if (state.unitSystem == com.example.sitedraft.ui.canvas.UnitSystem.METRIC) fThickness * 25.4f else fThickness
                            val displayWThickness = if (state.unitSystem == com.example.sitedraft.ui.canvas.UnitSystem.METRIC) wThickness * 25.4f else wThickness
                            
                            EditableProperty("Flange Width ($displayUnit)", "%.2f".format(displayWidth)) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "flangeWidth", it))
                            }
                            EditableProperty("Beam Height ($displayUnit)", "%.2f".format(displayHeight)) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "webHeight", it))
                            }
                            EditableProperty("Flange Thickness ($displayUnit)", "%.2f".format(displayFThickness)) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "flangeThickness", it))
                            }
                            EditableProperty("Web Thickness ($displayUnit)", "%.2f".format(displayWThickness)) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "webThickness", it))
                            }
                        } else {
                            val width = selectedElement.size.width / state.scale.ratio
                            val height = selectedElement.size.height / state.scale.ratio
                            val (wLabel, hLabel) = when (selectedElement.category) {
                                PlanSymbols.WALLS ->
                                    if (selectedElement.size.height > selectedElement.size.width) "Thickness" to "Length"
                                    else "Length" to "Thickness"
                                in PlanSymbols.categories -> "Width" to "Depth"
                                else -> "Width" to "Height"
                            }
                            if (state.unitSystem == UnitSystem.IMPERIAL) {
                                FeetInchesProperty(wLabel, width) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it.toString()))
                                }
                                FeetInchesProperty(hLabel, height) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it.toString()))
                                }
                            } else {
                                EditableProperty(wLabel, "%.2f".format(width)) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "width", it))
                                }
                                EditableProperty(hLabel, "%.2f".format(height)) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "height", it))
                                }
                            }
                            // The cabinet's shop code, and an upper's own height (it goes into the code).
                            PlanSymbols.cabinetLabel(selectedElement, state)?.let { code ->
                                PropertyRow("Cabinet code", code)
                            }
                            if (PlanSymbols.isUpperCabinet(selectedElement)) {
                                EditableProperty(
                                    "Upper height (in)",
                                    formatInches(PlanSymbols.upperHeightOf(selectedElement) ?: PlanSymbols.DEFAULT_UPPER_HEIGHT_IN)
                                ) { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_UPPER_HEIGHT_IN, it)) }
                            }
                            // Cabinets: what the front is (doors, a drawer over doors, a drawer
                            // bank), how many doors, and an island's seating overhang.
                            if (PlanSymbols.hasFrontChoice(selectedElement)) {
                                Text(text = "Fronts", style = MaterialTheme.typography.labelLarge)
                                val front = PlanSymbols.front(selectedElement)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PlanSymbols.frontLabels.forEachIndexed { i, label ->
                                        FilterChip(selected = front == i, onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_FRONT, i.toString()))
                                        }, label = { Text(label) })
                                    }
                                }
                            }
                            if (PlanSymbols.hasDoorChoice(selectedElement)) {
                                Text(text = "Doors", style = MaterialTheme.typography.labelLarge)
                                val chosen = selectedElement.properties[PlanSymbols.PROP_DOORS]?.toInt() ?: 0
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("By width", "1 door", "2 doors").forEachIndexed { i, label ->
                                        FilterChip(selected = chosen == i, onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_DOORS, i.toString()))
                                        }, label = { Text(label) })
                                    }
                                }
                            }
                            // Door style and hardware, for this cabinet or all of them.
                            if (PlanSymbols.hasStyleChoice(selectedElement)) {
                                Text(text = "Door style", style = MaterialTheme.typography.labelLarge)
                                val style = PlanSymbols.doorStyle(selectedElement)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PlanSymbols.styleLabels.forEachIndexed { i, label ->
                                        FilterChip(selected = style == i, onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_STYLE, i.toString()))
                                        }, label = { Text(label) })
                                    }
                                }
                                Text(text = "Hardware", style = MaterialTheme.typography.labelLarge)
                                val hw = PlanSymbols.hardware(selectedElement)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PlanSymbols.hardwareLabels.forEachIndexed { i, label ->
                                        FilterChip(selected = hw == i, onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_HARDWARE, i.toString()))
                                        }, label = { Text(label) })
                                    }
                                }
                                TextButton(onClick = { onEvent(DrawingEvent.ApplyCabinetStyle(style, hw)) }) {
                                    Text("Use this style and hardware on all cabinets")
                                }
                            }
                            // Vanities: how many sinks, and how high the top is.
                            if (PlanSymbols.hasSinkChoice(selectedElement)) {
                                Text(text = "Sinks", style = MaterialTheme.typography.labelLarge)
                                val chosen = selectedElement.properties[PlanSymbols.PROP_SINKS]?.toInt() ?: 0
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PlanSymbols.sinkLabels.forEachIndexed { i, label ->
                                        FilterChip(selected = chosen == i, onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_SINKS, i.toString()))
                                        }, label = { Text(label) })
                                    }
                                }
                            }
                            if (selectedElement.category == PlanSymbols.CABINETS && PlanSymbols.isVanity(selectedElement.symbolName)) {
                                EditableProperty("Vanity height (in)", formatInches(PlanSymbols.vanityTop(selectedElement))) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_TOP_IN, it))
                                }
                            }
                            if (selectedElement.category == PlanSymbols.SHOWERS) {
                                ShowerSection(selectedElement, onEvent)
                            }
                            // An alcove tub as a tub/shower: surround tile, valve end, heights (TubShower, Nov 25).
                            if (TubShower.isAlcoveTub(selectedElement)) {
                                val tub = selectedElement
                                Text(text = "Tub / shower", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("Tub only", "Tub / shower").forEachIndexed { i, label ->
                                        FilterChip(selected = (if (TubShower.isOn(tub)) 1 else 0) == i, onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(tub.id, TubShower.ON, i.toString()))
                                        }, label = { Text(label) })
                                    }
                                }
                                if (TubShower.isOn(tub)) {
                                    Text(text = "Valve at (seen from the front)", style = MaterialTheme.typography.bodySmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("Left end", "Right end").forEachIndexed { i, label ->
                                            FilterChip(selected = TubShower.prop(tub, TubShower.VALVE_END).toInt() == i, onClick = {
                                                onEvent(DrawingEvent.UpdateElementProperty(tub.id, TubShower.VALVE_END, i.toString()))
                                            }, label = { Text(label) })
                                        }
                                    }
                                    listOf("Tile height (in)" to TubShower.TILE_H, "Valve height (in)" to TubShower.VALVE_H, "Spout height (in)" to TubShower.SPOUT_H, "Showerhead height (in)" to TubShower.HEAD_H).forEach { (label, key) ->
                                        EditableProperty(label, formatInches(TubShower.prop(tub, key))) {
                                            onEvent(DrawingEvent.UpdateElementProperty(tub.id, key, it))
                                        }
                                    }
                                }
                            }
                            if (selectedElement.category == PlanSymbols.STAIRS) {
                                StairSection(selectedElement, onEvent)
                            }
                            if (selectedElement.category == PlanSymbols.RAILINGS) {
                                GuardSection(selectedElement, onEvent)
                            }
                            if (selectedElement.category in Structure.categories) {
                                StructureSection(selectedElement, state, onEvent)
                                RemodelFramingSection(selectedElement, onEvent)
                            }
                            // Remodel: the wall's status (Existing / Demo / New) and demo part of it.
                            if (selectedElement.category == PlanSymbols.WALLS && !DerivedLayers.isGenerated(selectedElement)) {
                                RemodelWallSection(selectedElement, state, onEvent)
                            }
                            // Framing elevation and stud count (WallFraming, Nov 24).
                            if (selectedElement is DrawingElement.Symbol && WallFraming.isFramed(selectedElement) && selectedElement.rotation % 90f == 0f) {
                                val f = WallFraming.frame(selectedElement, state)
                                Text(text = "Framing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                                Text(text = WallFraming.countText(f), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = {
                                    val side = if (Elevation.isHorizontal(selectedElement)) ViewerSide.BELOW else ViewerSide.LEFT
                                    onEvent(DrawingEvent.StartPlacement(Placement.Elevation(ElevationSpec(wallId = selectedElement.id, side = side, framing = true), "framing elevation")))
                                }) { Text("Framing elevation (tap, then tap an empty spot)") }
                            }
                            // A wall on a floor plan can be marked bearing: the layer builder carries it down to a footing.
                            if (selectedElement.category == PlanSymbols.WALLS && Layers.layerOf(selectedElement, state).trade == LayerTrade.FLOOR_PLAN) {
                                val bearing = (selectedElement.properties[Structure.BEARING] ?: 0f) >= 0.5f
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Bearing wall", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "Running with the joists: a ladder (doubled joists, R502.2). Across them: carried by a beam, or a beam of its own on posts and pads.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(checked = bearing, onCheckedChange = {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, Structure.BEARING, if (it) "1" else "0"))
                                    })
                                }
                            }
                            if (selectedElement.category == PlanSymbols.HOODS) {
                                Text(text = "Hood", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                                EditableProperty("Bottom of hood (in)", formatInches(Appliances.prop(selectedElement, Appliances.HOOD_BOTTOM))) {
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, Appliances.HOOD_BOTTOM, it))
                                }
                                Text("30\" over a 36\" cooking surface is 66\"; check the hood maker's range.", style = MaterialTheme.typography.bodySmall)
                                if (Appliances.hoodKind(selectedElement.symbolName) == 3) {
                                    @Composable
                                    fun hoodChips(title: String, key: String, labels: List<String>) {
                                        Text(text = title, style = MaterialTheme.typography.labelLarge)
                                        val chosen = Appliances.prop(selectedElement, key).toInt()
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            labels.forEachIndexed { i, label ->
                                                FilterChip(selected = chosen == i, onClick = {
                                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, key, i.toString()))
                                                }, label = { Text(label) })
                                            }
                                        }
                                    }
                                    hoodChips("Shape", Appliances.HOOD_STYLE, Appliances.woodStyleLabels)
                                    hoodChips("Goes up to", Appliances.HOOD_TO_CEILING, listOf("Top of the uppers", "Ceiling"))
                                    hoodChips("Corbels", Appliances.HOOD_CORBELS, listOf("No", "Yes"))
                                }
                            }
                            if (PlanSymbols.isIsland(selectedElement)) {
                                Text(text = "Seating overhang", style = MaterialTheme.typography.labelLarge)
                                val over = selectedElement.properties[PlanSymbols.PROP_OVERHANG]?.toInt() ?: 0
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PlanSymbols.overhangLabels.forEachIndexed { i, label ->
                                        FilterChip(selected = over == i, onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_OVERHANG, i.toString()))
                                        }, label = { Text(label) })
                                    }
                                }
                                if (over > 0) {
                                    EditableProperty("Overhang (in)", formatInches(selectedElement.properties[PlanSymbols.PROP_OVERHANG_IN] ?: 12f)) {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_OVERHANG_IN, it))
                                    }
                                }
                            }
                            // Doors and windows: their own heights, in inches, used by elevations.
                            if (PlanSymbols.isDoor(selectedElement.symbolName)) {
                                Text(text = "Swing", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = "Hinged on the ${if (PlanSymbols.hingeRight(selectedElement)) "right" else "left"} jamb, seen from the side it swings into.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { onEvent(DrawingEvent.SwapDoorHinge(selectedElement.id)) }) { Text("Swap hinge side") }
                                    OutlinedButton(onClick = { onEvent(DrawingEvent.SwingDoorOtherSide(selectedElement.id)) }) { Text("Swing to other side") }
                                }
                            }
                            // Barn doors: the track, and what the open panel would hit (Dan, Nov 26 card).
                            if (PlanSymbols.isBarnDoor(selectedElement.symbolName)) {
                                Text(text = "Barn door", style = MaterialTheme.typography.labelLarge)
                                Text(BarnDoors.trackText(selectedElement, state) + " (the panel's travel plus the panel)", style = MaterialTheme.typography.bodySmall)
                                val clashes = BarnDoors.warnings(selectedElement, state)
                                if (clashes.isEmpty()) Text("Slides clear.", style = MaterialTheme.typography.bodySmall)
                                else clashes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                            if (PlanSymbols.isPocketDoor(selectedElement.symbolName)) {
                                Text(text = "Pocket", style = MaterialTheme.typography.labelLarge)
                                val right = PlanSymbols.pocketRight(selectedElement)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = !right, onClick = {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_HINGE_RIGHT, "0"))
                                    }, label = { Text("Pocket left") })
                                    FilterChip(selected = right, onClick = {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_HINGE_RIGHT, "1"))
                                    }, label = { Text("Pocket right") })
                                }
                            }
                            if (selectedElement.category == PlanSymbols.DOORS_WINDOWS) {
                                // Where it sits along its wall, measured the way it is laid out on site.
                                val host = OpeningPosition.hostWall(selectedElement, state)
                                if (host != null) {
                                    val ref = OpeningPosition.ref(selectedElement)
                                    val horizontal = Elevation.isHorizontal(host)
                                    fun position(fromEnd: Boolean = ref.fromEnd, toCenter: Boolean = ref.toCenter, outside: Boolean = ref.outside, distance: String? = null) =
                                        onEvent(DrawingEvent.PositionOpening(selectedElement.id, fromEnd, toCenter, outside, distance))
                                    Text(text = "Position on the wall", style = MaterialTheme.typography.labelLarge)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(selected = !ref.fromEnd, onClick = { position(fromEnd = false) }, label = { Text(if (horizontal) "From left" else "From top") })
                                        FilterChip(selected = ref.fromEnd, onClick = { position(fromEnd = true) }, label = { Text(if (horizontal) "From right" else "From bottom") })
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(selected = !ref.toCenter, onClick = { position(toCenter = false) }, label = { Text("To near edge") })
                                        FilterChip(selected = ref.toCenter, onClick = { position(toCenter = true) }, label = { Text("To centre") })
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(selected = !ref.outside, onClick = { position(outside = false) }, label = { Text("Inside corner") })
                                        FilterChip(selected = ref.outside, onClick = { position(outside = true) }, label = { Text("Outside corner") })
                                    }
                                    val d = OpeningPosition.distance(selectedElement, state, ref)
                                    EditableProperty(
                                        if (state.unitSystem == UnitSystem.IMPERIAL) "Distance (12'-6\")" else "Distance (m)",
                                        d?.let { formatLength(it / state.scale.ratio, state.unitSystem) } ?: ""
                                    ) { position(distance = it) }
                                }
                                val isWindow = PlanSymbols.isWindow(selectedElement.symbolName)
                                if (isWindow) {
                                    // How it opens: shown in the window schedule and on elevations.
                                    Text(text = "Window type", style = MaterialTheme.typography.labelLarge)
                                    val wType = PlanSymbols.windowType(selectedElement)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        PlanSymbols.windowTypeLabels.forEachIndexed { i, label ->
                                            FilterChip(selected = wType == i, onClick = {
                                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_WINDOW_TYPE, i.toString()))
                                            }, label = { Text(label) })
                                        }
                                    }
                                    if (wType == PlanSymbols.WINDOW_CASEMENT) {
                                        Text(text = "Hinge side (as drawn on the plan)", style = MaterialTheme.typography.labelLarge)
                                        val right = PlanSymbols.hingeRight(selectedElement)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("Left", "Right").forEachIndexed { i, label ->
                                                FilterChip(selected = right == (i == 1), onClick = {
                                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_HINGE_RIGHT, i.toString()))
                                                }, label = { Text(label) })
                                            }
                                        }
                                    }
                                    val sill = selectedElement.properties[PlanSymbols.PROP_SILL_IN] ?: 36f
                                    EditableProperty("Sill height (in)", formatInches(sill)) {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_SILL_IN, it))
                                    }
                                }
                                val h = selectedElement.properties[PlanSymbols.PROP_HEIGHT_IN] ?: (if (isWindow) 48f else 80f)
                                EditableProperty(if (isWindow) "Window height (in)" else "Opening height (in)", formatInches(h)) {
                                    // A window keeps its head where it is (lined up with the door heads, Dan's rule):
                                    // a taller window drops its sill instead of running up to the ceiling (Nov 25).
                                    val newH = it.trim().toFloatOrNull()
                                    if (isWindow && newH != null && newH > 0f) {
                                        val sillNow = selectedElement.properties[PlanSymbols.PROP_SILL_IN] ?: 36f
                                        val newSill = (sillNow + h - newH).coerceAtLeast(0f)
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_SILL_IN, newSill.toString()))
                                    }
                                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, PlanSymbols.PROP_HEIGHT_IN, it))
                                }
                                // The schedule's mark and rough opening (Schedules): Dan's rule unless the supplier's is entered.
                                if (com.example.sitedraft.ui.canvas.Schedules.kindOf(selectedElement) != null) {
                                    com.example.sitedraft.ui.canvas.Schedules.mark(selectedElement)?.let { PropertyRow("Schedule mark", it) }
                                    Text("Rough opening from the supplier, inches (blank = your rule):", style = MaterialTheme.typography.bodySmall)
                                    EditableProperty("R.O. width", selectedElement.properties[com.example.sitedraft.ui.canvas.Schedules.PROP_RO_W]?.let { formatInches(it) } ?: "") {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, com.example.sitedraft.ui.canvas.Schedules.PROP_RO_W, it))
                                    }
                                    EditableProperty("R.O. height", selectedElement.properties[com.example.sitedraft.ui.canvas.Schedules.PROP_RO_H]?.let { formatInches(it) } ?: "") {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, com.example.sitedraft.ui.canvas.Schedules.PROP_RO_H, it))
                                    }
                                }
                            }
                        }
                    }
                    is DrawingElement.ElevationView -> {
                        val spec = selectedElement.spec
                        val wall = state.elements.find { it.id == spec.wallId } as? DrawingElement.Symbol
                        if (spec.stairSide >= 0) {
                            Text(
                                if (wall != null) "Live stair elevation: follows the stair. Change the rise, treads and rails on the stair in the plan."
                                else "Live stair elevation: its stair has been deleted.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (wall != null && Stairs.type(wall.symbolName) != Stairs.Type.SPIRAL) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Stairs.sideLabelsFor(wall).forEachIndexed { i, label ->
                                        FilterChip(selected = spec.stairSide == i, onClick = {
                                            onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "stairSide", i.toString()))
                                        }, label = { Text(label) })
                                    }
                                }
                            }
                        } else if (spec.showerFace >= 0) {
                            // A shower elevation: pick the face; heights live on the shower itself.
                            Text(
                                if (wall != null) "Live shower elevation: follows the shower. Change heights, tile and glass on the shower in the plan."
                                else "Live shower elevation: its shower has been deleted.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (wall != null) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Shower.faces(Shower.type(wall.symbolName)).forEach { f ->
                                        FilterChip(
                                            selected = spec.showerFace == f,
                                            onClick = { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "showerFace", f.toString())) },
                                            label = { Text(Shower.faceLabels[f]) }
                                        )
                                    }
                                }
                            }
                        } else if (spec.islandFace >= 0) {
                            // An island: pick the face; fronts, style and overhang live on the island.
                            Text(
                                if (wall != null) "Live island elevation: follows the island. Change its fronts, door style and overhang on the island in the plan."
                                else "Live island elevation: its island has been deleted.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Elevation.islandFaceLabels.forEachIndexed { i, label ->
                                    FilterChip(selected = spec.islandFace == i, onClick = {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "islandFace", i.toString()))
                                    }, label = { Text(label) })
                                }
                            }
                        } else {
                        Text(
                            if (wall != null) "Live elevation: follows the plan. Explode it (floating bar) to edit by hand."
                            else "Live elevation: its wall has been deleted.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (wall != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Elevation.sidesFor(wall).forEach { s ->
                                    FilterChip(
                                        selected = spec.side == s,
                                        onClick = { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "viewerSide", s.name)) },
                                        label = { Text(s.looking) }
                                    )
                                }
                            }
                        }
                        listOf(
                            "Wall height (in)" to ("wallHeightIn" to spec.wallHeightIn),
                            "Counter (in)" to ("counterHeightIn" to spec.counterHeightIn),
                            "Uppers from (in)" to ("upperBottomIn" to spec.upperBottomIn),
                            "Uppers high (in)" to ("upperHeightIn" to spec.upperHeightIn),
                            "Window sill (in)" to ("sillIn" to spec.sillIn),
                            "Window high (in)" to ("windowHeightIn" to spec.windowHeightIn),
                            "Door height (in)" to ("doorHeightIn" to spec.doorHeightIn)
                        ).forEach { (label, keyValue) ->
                            EditableProperty(label, "%.1f".format(keyValue.second).trimEnd('0').trimEnd('.')) {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, keyValue.first, it))
                            }
                        }
                        // Wall tile: a backsplash, a wainscot, or any band up the wall.
                        Text(text = "Wall tile", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val id = selectedElement.id
                            FilterChip(selected = spec.tileToIn <= spec.tileFromIn, onClick = {
                                onEvent(DrawingEvent.UpdateElementProperty(id, "tileToIn", "0"))
                                onEvent(DrawingEvent.UpdateElementProperty(id, "tileFromIn", "0"))
                            }, label = { Text("None") })
                            FilterChip(
                                selected = spec.tileToIn > spec.tileFromIn && spec.tileFromIn == spec.counterHeightIn && spec.tileToIn == spec.upperBottomIn,
                                onClick = {
                                    onEvent(DrawingEvent.UpdateElementProperty(id, "tileFromIn", spec.counterHeightIn.toString()))
                                    onEvent(DrawingEvent.UpdateElementProperty(id, "tileToIn", spec.upperBottomIn.toString()))
                                    onEvent(DrawingEvent.UpdateElementProperty(id, "tileExtent", "1"))
                                },
                                label = { Text("Backsplash") }
                            )
                            FilterChip(
                                selected = spec.tileToIn > spec.tileFromIn && spec.tileFromIn == 0f && spec.tileToIn >= spec.wallHeightIn,
                                onClick = {
                                    onEvent(DrawingEvent.UpdateElementProperty(id, "tileFromIn", "0"))
                                    onEvent(DrawingEvent.UpdateElementProperty(id, "tileToIn", spec.wallHeightIn.toString()))
                                    onEvent(DrawingEvent.UpdateElementProperty(id, "tileExtent", "0"))
                                },
                                label = { Text("Full wall") }
                            )
                        }
                        if (spec.tileToIn > spec.tileFromIn) {
                            Text(text = "Tile runs", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Whole wall", "Over the cabinet run only").forEachIndexed { i, label ->
                                    FilterChip(selected = spec.tileExtent == i, onClick = {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "tileExtent", i.toString()))
                                    }, label = { Text(label) })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Shower.tilePatternLabels.forEachIndexed { i, label ->
                                    FilterChip(selected = spec.tilePattern == i, onClick = {
                                        onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "tilePattern", i.toString()))
                                    }, label = { Text(label) })
                                }
                            }
                            EditableProperty("Tile from (in)", formatInches(spec.tileFromIn)) { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "tileFromIn", it)) }
                            EditableProperty("Tile to (in)", formatInches(spec.tileToIn)) { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "tileToIn", it)) }
                            EditableProperty("Tile height (in)", formatInches(spec.tileSizeIn)) { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "tileSizeIn", it)) }
                            EditableProperty("Tile length (in, 0 = square)", formatInches(spec.tileLengthIn)) { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "tileLengthIn", it)) }
                        }
                        // Just a length of the wall (Nov 25): inches along it from the viewer's left end; 0 and 0 = the whole wall.
                        if (spec.showerFace < 0 && spec.islandFace < 0 && spec.stairSide < 0 && !spec.framing) {
                            Text(text = "Length of wall shown (in from the left end; 0 to 0 = whole wall)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                            EditableProperty("Show from (in)", formatInches(spec.fromIn)) { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "fromIn", it)) }
                            EditableProperty("Show to (in)", formatInches(spec.toIn)) { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "toIn", it)) }
                        }
                        // Wainscot on this face of the wall (Wainscot, Nov 25). Kept on the wall, so every elevation of
                        // this face shows it; set wall by wall.
                        val wsWall = state.elements.firstOrNull { it.id == spec.wallId } as? DrawingElement.Symbol
                        if (wsWall != null && wsWall.category == PlanSymbols.WALLS && spec.showerFace < 0 && spec.islandFace < 0 && spec.stairSide < 0 && !spec.framing) {
                            val ws = com.example.sitedraft.ui.canvas.Wainscot
                            val cur = ws.specOf(wsWall, spec.side)
                            Text(text = "Wainscot", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ws.styleLabels.forEachIndexed { i, label ->
                                    FilterChip(selected = (cur?.style ?: 0) == i, onClick = {
                                        onEvent(DrawingEvent.UpdateElementProperty(wsWall.id, ws.styleKey(spec.side), i.toString()))
                                    }, label = { Text(label) })
                                }
                            }
                            if (cur != null) {
                                EditableProperty("Height to top of cap (in)", formatInches(cur.heightIn)) {
                                    onEvent(DrawingEvent.UpdateElementProperty(wsWall.id, ws.heightKey(spec.side), it))
                                }
                                // The rooms along this wall (between the walls that butt in): tick where it goes.
                                val rooms = ws.roomSpans(wsWall, spec.side, state)
                                if (rooms.size > 1) {
                                    Text(text = "Rooms on this wall (left to right as seen)", style = MaterialTheme.typography.bodySmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        rooms.forEachIndexed { i, (a, b) ->
                                            FilterChip(selected = cur.inRoom(i), onClick = {
                                                val all = (1 shl rooms.size) - 1
                                                val now = if (cur.rooms == 0) all else cur.rooms
                                                val next = (now xor (1 shl i)).let { if (it == all) 0 else it }
                                                // Unticking the last room takes the wainscot off this face.
                                                if (next == 0 && now == (1 shl i)) onEvent(DrawingEvent.UpdateElementProperty(wsWall.id, ws.styleKey(spec.side), "0"))
                                                else onEvent(DrawingEvent.UpdateElementProperty(wsWall.id, ws.roomsKey(spec.side), next.toString()))
                                            }, label = { Text("${formatInches(a)}–${formatInches(b)}") })
                                        }
                                    }
                                }
                                if (cur.style == ws.FLAT_PANEL || cur.style == ws.RAISED_PANEL || cur.style == ws.SHADOW_BOXES) {
                                    EditableProperty(if (cur.style == ws.SHADOW_BOXES) "Boxes on this wall (0 = auto)" else "Panels on this wall (0 = auto)", cur.count.toString()) {
                                        onEvent(DrawingEvent.UpdateElementProperty(wsWall.id, ws.countKey(spec.side), it))
                                    }
                                }
                            }
                        }
                        // Art and wallpaper on this face of the wall (WallDecor, Nov 25): kept on the wall, like the wainscot.
                        if (wsWall != null && wsWall.category == PlanSymbols.WALLS && spec.showerFace < 0 && spec.islandFace < 0 && spec.stairSide < 0 && !spec.framing) {
                            val importer = com.example.sitedraft.platform.rememberFileImporter()
                            Text(text = "Art & wallpaper", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { importer.pickImage { onEvent(DrawingEvent.AddWallArt(selectedElement.id, it)) } }) { Text("Add art") }
                                OutlinedButton(onClick = { importer.pickImage { onEvent(DrawingEvent.AddWallpaper(selectedElement.id, it)) } }) { Text("Wallpaper") }
                            }
                            Text("Pick a picture: art hangs centred on this view at 60\" (30\" wide, its own shape); a wallpaper swatch repeats across the wall above any wainscot, with doors, windows and cabinets cutting through.",
                                style = MaterialTheme.typography.bodySmall)
                            if (state.pictures.isNotEmpty()) {
                                Text("Hang a picture again:", style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    state.pictures.forEachIndexed { i, p ->
                                        FilterChip(selected = false, onClick = { onEvent(DrawingEvent.UsePicture(selectedElement.id, p.id, 0)) },
                                            label = { Text(p.name.ifBlank { "Picture ${i + 1}" }) })
                                    }
                                }
                            }
                            WallDecorations.on(state, wsWall.id, spec.side).filter { it.kind != WallDecor.PAINT }.forEach { d ->
                                if (d.kind == WallDecor.WALLPAPER) {
                                    Text("Wallpaper", style = MaterialTheme.typography.bodySmall)
                                    EditableProperty("Repeat width (in)", formatInches(d.repeatIn)) { onEvent(DrawingEvent.UpdateWallDecor(d.id, "repeatIn", it)) }
                                } else {
                                    Text("Art", style = MaterialTheme.typography.bodySmall)
                                    EditableProperty("Width (in)", formatInches(d.widthIn)) { onEvent(DrawingEvent.UpdateWallDecor(d.id, "widthIn", it)) }
                                    EditableProperty("Height (in)", formatInches(d.heightIn)) { onEvent(DrawingEvent.UpdateWallDecor(d.id, "heightIn", it)) }
                                    EditableProperty("Left edge from the wall's left end (in)", formatInches(d.xIn)) { onEvent(DrawingEvent.UpdateWallDecor(d.id, "xIn", it)) }
                                    EditableProperty("Bottom off the floor (in)", formatInches(d.bottomIn)) { onEvent(DrawingEvent.UpdateWallDecor(d.id, "bottomIn", it)) }
                                    EditableProperty("Frame (in, 0 = none)", formatInches(d.frameIn)) { onEvent(DrawingEvent.UpdateWallDecor(d.id, "frameIn", it)) }
                                }
                                androidx.compose.material3.TextButton(onClick = { onEvent(DrawingEvent.RemoveWallDecor(d.id)) }) { Text("Remove") }
                            }
                        }
                        // Paint on this wall face (Nov 26): Sherwin-Williams colours by number or name, or any hex.
                        if (wsWall != null && wsWall.category == PlanSymbols.WALLS && !spec.framing) {
                            PaintPicker(WallDecorations.on(state, wsWall.id, spec.side).firstOrNull { it.kind == WallDecor.PAINT },
                                onPick = { hex, code, name -> onEvent(DrawingEvent.SetWallPaint(selectedElement.id, hex, code, name)) },
                                onRemove = { onEvent(DrawingEvent.RemoveWallDecor(it)) })
                        }
                        // Crown on the uppers, and a valance over the sink.
                        Text(text = "Crown molding", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = spec.crownIn <= 0f, onClick = {
                                onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "crownIn", "0"))
                            }, label = { Text("None") })
                            FilterChip(selected = spec.crownIn > 0f, onClick = {
                                if (spec.crownIn <= 0f) onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "crownIn", "3.5"))
                            }, label = { Text("Crown") })
                        }
                        if (spec.crownIn > 0f) {
                            EditableProperty("Crown height (in)", formatInches(spec.crownIn)) { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "crownIn", it)) }
                        }
                        // Door and window trim, and the base.
                        Text(text = "Door & window trim", style = MaterialTheme.typography.labelLarge)
                        fun trimSet(key: String, v: String) = onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, key, v))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = !spec.trimOn, onClick = { trimSet("trimOn", "0") }, label = { Text("None") })
                            FilterChip(selected = spec.trimOn, onClick = { trimSet("trimOn", "1") }, label = { Text("Casing") })
                        }
                        if (spec.trimOn) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Trim.casingChoices.forEach { c ->
                                    FilterChip(selected = kotlin.math.abs(spec.casingIn - c) < 0.01f, onClick = { trimSet("casingIn", c.toString()) }, label = { Text("${formatInches(c)}\" casing") })
                                }
                            }
                            EditableProperty("Casing width (in)", formatInches(spec.casingIn)) { trimSet("casingIn", it) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Trim.headLabels.forEachIndexed { i, label ->
                                    FilterChip(selected = spec.headStyle == i, onClick = { trimSet("headStyle", i.toString()) }, label = { Text(label) })
                                }
                            }
                            if (spec.headStyle == Trim.HEAD_CORNICE) {
                                EditableProperty("Frieze height (in, 5 1/2 min)", formatInches(spec.friezeIn)) { trimSet("friezeIn", it) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Trim.windowLabels.forEachIndexed { i, label ->
                                    FilterChip(selected = spec.windowTrim == i, onClick = { trimSet("windowTrim", i.toString()) }, label = { Text("Windows: $label") })
                                }
                            }
                        }
                        Text(text = "Base", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = spec.baseIn <= 0f, onClick = { trimSet("baseIn", "0") }, label = { Text("None") })
                            Trim.baseChoices.forEach { b ->
                                FilterChip(selected = kotlin.math.abs(spec.baseIn - b) < 0.01f, onClick = { trimSet("baseIn", b.toString()) }, label = { Text("${formatInches(b)}\"") })
                            }
                        }
                        if (spec.baseIn > 0f) {
                            EditableProperty("Base height (in)", formatInches(spec.baseIn)) { trimSet("baseIn", it) }
                        }
                        Text(text = "Valance over the sink", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = !spec.valance, onClick = { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "valance", "0")) }, label = { Text("No") })
                            FilterChip(selected = spec.valance, onClick = { onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "valance", "1")) }, label = { Text("Yes") })
                        }
                        Text(
                            "Crown runs along the tops of the uppers and pantries. The valance spans the gap between two uppers with a sink under it. To raise one upper (over a range or sink), select it on the plan and set its Upper height: shorter ones keep the top line.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        }
                        // Dimensions in one tap; ordinary dimensions afterwards, so each can be edited.
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(text = "Dimensions", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Widths across the top, heights down the right side, and each window's sill and head. Drag, retype or delete any of them afterwards. They move and delete with the elevation; tap again after changing the plan to redo them.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        val hasElevDims = state.elements.any { it.isOwnedBy(selectedElement.id) }
                        Button(onClick = { onEvent(DrawingEvent.DimensionElevation(selectedElement.id)) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (hasElevDims) "Redo its dimensions" else "Dimension this elevation")
                        }
                        if (hasElevDims) {
                            TextButton(onClick = { onEvent(DrawingEvent.RemoveOwnedDimensions(selectedElement.id)) }) { Text("Remove its dimensions") }
                        }
                    }
                    else -> {}
                }

                val displayRotation = if (selectedElement is DrawingElement.Line) {
                    val dir = selectedElement.end - selectedElement.start
                    val baseAngle = com.example.sitedraft.util.toDegrees(atan2(dir.y.toDouble(), dir.x.toDouble())).toFloat()
                    var total = (baseAngle + selectedElement.rotation) % 360f
                    if (total < 0) total += 360f
                    total
                } else {
                    var rot = selectedElement.rotation % 360f
                    if (rot < 0) rot += 360f
                    rot
                }

                EditableProperty("Total Angle", "%.1f".format(displayRotation)) {
                    onEvent(DrawingEvent.UpdateElementProperty(selectedElement.id, "rotation", it))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                Button(
                    onClick = { onEvent(DrawingEvent.SelectTool(DrawingTool.MOVE)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.currentTool == DrawingTool.MOVE) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.OpenWith, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.currentTool == DrawingTool.MOVE) "Move Mode: ON" else "Enable Move Tool")
                }
                
                Button(
                    onClick = { onEvent(DrawingEvent.SelectTool(DrawingTool.MOVE_PRECISE)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.currentTool == DrawingTool.MOVE_PRECISE) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.AdsClick, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.currentTool == DrawingTool.MOVE_PRECISE) "Point-to-Point: ON" else "Enable Precise Move")
                }

                Text(text = "Offset Tool", style = MaterialTheme.typography.titleSmall)
                var offsetFeet by remember { mutableStateOf("1") }
                var offsetInches by remember { mutableStateOf("0") }
                var offsetValueMetric by remember { mutableStateOf("1.0") }
                var offsetDirection by remember { mutableStateOf(1) } // 1 or -1
                
                if (state.unitSystem == UnitSystem.IMPERIAL) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = offsetFeet,
                            onValueChange = { offsetFeet = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Ft") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = offsetInches,
                            onValueChange = { offsetInches = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("In") },
                            singleLine = true
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = offsetValueMetric,
                        onValueChange = { offsetValueMetric = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Distance (m)") },
                        singleLine = true
                    )
                }
                
                // Direction Selection
                val isClosedShape = selectedElement is DrawingElement.Rectangle || 
                                   selectedElement is DrawingElement.Circle || 
                                   selectedElement is DrawingElement.Oval || 
                                   selectedElement is DrawingElement.Triangle
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SegmentedButton(
                        isSelected = offsetDirection == 1,
                        onClick = { offsetDirection = 1 },
                        label = if (isClosedShape) "Outside" else "Side A",
                        modifier = Modifier.weight(1f)
                    )
                    SegmentedButton(
                        isSelected = offsetDirection == -1,
                        onClick = { offsetDirection = -1 },
                        label = if (isClosedShape) "Inside" else "Side B",
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Button(
                    onClick = {
                        val actualDist = if (state.unitSystem == UnitSystem.IMPERIAL) {
                            val f = offsetFeet.toFloatOrNull() ?: 0f
                            val i = offsetInches.toFloatOrNull() ?: 0f
                            f + (i / 12f)
                        } else {
                            offsetValueMetric.toFloatOrNull() ?: 1.0f
                        }
                        onEvent(DrawingEvent.OffsetSelectedElement(actualDist, offsetDirection))
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Apply Offset")
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onEvent(DrawingEvent.DuplicateSelectedElement) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CopyAll, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy")
                    }
                    Button(
                        onClick = { onEvent(DrawingEvent.DeleteSelectedElement) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete")
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            } else {
                Text(
                    text = "Project Overview",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                PropertyRow("Unit System", state.unitSystem.name)
                PropertyRow("Current Scale", state.scale.label)
                
                Text(text = "Background Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorButton(Color(0xFF003366), state.backgroundColor == Color(0xFF003366)) { onEvent(DrawingEvent.SelectBackgroundColor(Color(0xFF003366))) }
                    ColorButton(Color.White, state.backgroundColor == Color.White) { onEvent(DrawingEvent.SelectBackgroundColor(Color.White)) }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                PropertyRow("Elements Count", state.elements.size.toString())
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(text = "Quick Tools", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onEvent(DrawingEvent.Clear) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear Canvas")
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedButton(isSelected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.padding(2.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ColorButton(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(color, MaterialTheme.shapes.small)
            .let { if (isSelected) it.padding(2.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small).padding(2.dp).background(color, MaterialTheme.shapes.small) else it }
            .clickable(onClick = onClick)
    )
}

@Composable
private fun FeetInchesProperty(label: String, valueInFeet: Float, onUpdate: (Float) -> Unit) {
    // Round to the nearest 1/16" BEFORE splitting - see splitFeetInches. Inches are shown and
    // typed as fractions ("6 1/2"), the way they are read off a tape; decimals still parse.
    val (initialFeet, initialInches) = splitFeetInches(valueInFeet, 1f / INCH_DENOMINATOR)

    var feetText by remember(valueInFeet) { mutableStateOf(initialFeet.toString()) }
    var inchesText by remember(valueInFeet) { mutableStateOf(formatInches(initialInches)) }
    val inchesValid = inchesText.isBlank() || parseInches(inchesText) != null

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = feetText,
                onValueChange = { feetText = it },
                modifier = Modifier.weight(1f),
                label = { Text("Ft") },
                singleLine = true
            )
            OutlinedTextField(
                value = inchesText,
                onValueChange = { inchesText = it },
                modifier = Modifier.weight(1f),
                label = { Text("In") },
                placeholder = { Text("6 1/2") },
                isError = !inchesValid,
                singleLine = true
            )
            TextButton(onClick = {
                val f = feetText.toFloatOrNull() ?: 0f
                val i = parseInches(inchesText) ?: 0f
                onUpdate(f + (i / 12f))
            }) {
                Text("Set")
            }
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EditableProperty(label: String, value: String, onUpdate: (String) -> Unit) {
    var textValue by remember(value) { mutableStateOf(value) }
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            modifier = Modifier.width(120.dp),
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { onUpdate(textValue) }) {
                    Text("Set")
                }
            }
        )
    }
}

/**
 * An appliance's or plumbing fixture's lines on its schedule (TradeSchedules, Nov 26): its mark (A1, P2...) and the
 * make, model, finish and notes, one line each, Enter or Set saves.
 */
@Composable
private fun ItemSpecFields(sym: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    val kind = ItemSchedules.kindOf(sym) ?: return
    val mark = ItemSchedules.markOf(sym, state)
    val what = if (kind == ItemSchedules.Kind.APPLIANCE) "Appliance schedule" else "Plumbing fixture schedule"
    Text(if (mark != null) "$what: $mark" else "$what (a duplicate at this spot: not listed)", style = MaterialTheme.typography.titleSmall)
    val sp = ItemSchedules.spec(state, sym.id)
    val values = listOf(sp.make, sp.model, sp.finish, sp.notes)
    ItemSchedules.fields.zip(ItemSchedules.labels).zip(values).forEach { (fl, value) ->
        val (field, label) = fl
        var text by remember(sym.id, field, value) { mutableStateOf(value) }
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onEvent(DrawingEvent.UpdateItemSpec(sym.id, field, text)) }),
            trailingIcon = { TextButton(onClick = { onEvent(DrawingEvent.UpdateItemSpec(sym.id, field, text)) }) { Text("Set") } })
    }
}

// Wrapping chip row: pattern names vary in width (and the panel is only 300dp), so a fixed
// N-per-row grid either clips or squeezes a label into a vertical column of letters.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FillPatternChips(selected: FillPattern, onSelect: (FillPattern) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FillPattern.entries.forEach { p ->
            FilterChip(
                selected = selected == p,
                onClick = { onSelect(p) },
                label = { Text(p.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

// A world-space length (px) in the project's real units, for the fill size label.
private fun formatWorldLength(px: Float, state: CanvasState): String {
    return if (state.unitSystem == UnitSystem.IMPERIAL) {
        "%.1f in".format(px / state.scale.ratio * 12f)
    } else {
        "%.0f cm".format(px / state.scale.ratio * 100f)
    }
}

/** Page, scale calibration, opacity and lock for a selected PDF plan. */
@Composable
private fun PdfPlanControls(pdf: DrawingElement.Pdf, onEvent: (DrawingEvent) -> Unit) {
    if (pdf.pages.size > 1) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Page", style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                enabled = pdf.currentPage > 0,
                onClick = { onEvent(DrawingEvent.UpdateElementProperty(pdf.id, "currentPage", (pdf.currentPage - 1).toString())) }
            ) { Text("Prev") }
            Text(text = "${pdf.currentPage + 1} of ${pdf.pages.size}", style = MaterialTheme.typography.bodyMedium)
            TextButton(
                enabled = pdf.currentPage < pdf.pages.size - 1,
                onClick = { onEvent(DrawingEvent.UpdateElementProperty(pdf.id, "currentPage", (pdf.currentPage + 1).toString())) }
            ) { Text("Next") }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text(text = "Plan scale", style = MaterialTheme.typography.labelSmall)
    Text(text = pdf.calibration ?: "Not calibrated", style = MaterialTheme.typography.bodySmall)
    Spacer(modifier = Modifier.height(6.dp))
    OutlinedButton(
        onClick = { onEvent(DrawingEvent.StartCalibration(pdf.id)) },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (pdf.calibration == null) "Calibrate scale" else "Recalibrate scale") }
    Text(
        text = "Pick both ends of a dimension printed on the plan, then type its real length.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Opacity is applied on release only: every property edit takes an undo snapshot.
    Spacer(modifier = Modifier.height(12.dp))
    var opacity by remember(pdf.id, pdf.opacity) { mutableStateOf(pdf.opacity) }
    Text(text = "Opacity", style = MaterialTheme.typography.labelSmall)
    Slider(
        value = opacity,
        onValueChange = { opacity = it },
        onValueChangeFinished = { onEvent(DrawingEvent.UpdateElementProperty(pdf.id, "opacity", opacity.toString())) },
        valueRange = 0.1f..1f
    )
    Text(text = "${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Lock in place", style = MaterialTheme.typography.labelSmall)
            Text(
                text = "Can't be picked or moved while you draw over it. Unlock from the Import menu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = pdf.locked,
            onCheckedChange = { onEvent(DrawingEvent.UpdateElementProperty(pdf.id, "locked", it.toString())) }
        )
    }
}

/**
 * A shower's settings: glass, curb, heights, tile, valve, niche and bench, a button to wall it
 * in, and one button per face to place that face's live elevation.
 */
@Composable
private fun ShowerSection(shower: DrawingElement.Symbol, onEvent: (DrawingEvent) -> Unit) {
    val type = Shower.type(shower.symbolName)
    fun set(key: String, v: String) = onEvent(DrawingEvent.UpdateElementProperty(shower.id, key, v))
    @Composable
    fun chips(title: String, key: String, labels: List<String>, allowed: List<Int> = labels.indices.toList()) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        val chosen = Shower.code(shower, key)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, label ->
                if (i in allowed) FilterChip(selected = chosen == i, onClick = { set(key, i.toString()) }, label = { Text(label) })
            }
        }
    }
    @Composable
    fun inches(label: String, key: String) {
        EditableProperty("$label (in)", formatInches(Shower.prop(shower, key))) { set(key, it) }
    }
    val walls = Shower.solidFaces(type)

    Text(text = "Shower", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    chips("Glass", Shower.GLASS, Shower.glassLabels)
    val glass = Shower.code(shower, Shower.GLASS)
    if (glass == 0 || glass == 1 || glass == 3) {
        chips(if (glass == 3) "Opening at" else "Door at", Shower.DOOR_SIDE, listOf("Left", "Right"))
        inches(if (glass == 3) "Opening width" else "Door width", Shower.DOOR_W)
    }
    // Door + panel: the door can swing off the fixed panel and latch at the wall (Dan, Nov 25).
    if (glass == 0) chips("Door hinged on", Shower.HINGE_ON, listOf("Wall", "Panel"))
    inches("Glass height", Shower.GLASS_H)
    inches("Curb height (0 = curbless)", Shower.CURB_H)
    inches("Curb width", Shower.CURB_W)
    inches("Tile height", Shower.TILE_H)
    inches("Ceiling", Shower.CEILING)
    chips("Wall tile", Shower.TILE_PAT, Shower.tilePatternLabels)
    inches("Wall tile height", Shower.TILE_IN)
    inches("Wall tile length (along wall)", Shower.TILE_LEN)
    inches("Floor tile width (0 = none)", Shower.FLOOR_TILE)
    inches("Floor tile length", Shower.FLOOR_TILE_LEN)
    chips("Drain", Shower.DRAIN, listOf("Centre", "Linear"))
    chips("Valve and head on", Shower.VALVE_WALL, Shower.wallChoiceLabels.take(3), walls)
    inches("Valve height", Shower.VALVE_H)
    inches("Head height", Shower.HEAD_H)
    chips("Niche on", Shower.NICHE_WALL, Shower.wallChoiceLabels, walls + Shower.NONE)
    if (Shower.code(shower, Shower.NICHE_WALL) != Shower.NONE) {
        inches("Niche bottom", Shower.NICHE_H)
        inches("Niche width", Shower.NICHE_W)
        inches("Niche height", Shower.NICHE_T)
    }
    chips("Bench on", Shower.BENCH_WALL, Shower.wallChoiceLabels, walls + Shower.NONE)
    if (Shower.code(shower, Shower.BENCH_WALL) != Shower.NONE) {
        inches("Bench height", Shower.BENCH_H)
        inches("Bench depth", Shower.BENCH_D)
    }
    OutlinedButton(onClick = { onEvent(DrawingEvent.AddShowerWalls(shower.id)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Add 2x4 walls around it")
    }
    Text(text = "Elevations (tap one, then tap an empty spot on the canvas)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Shower.faces(type).forEach { f ->
            OutlinedButton(onClick = {
                val spec = ElevationSpec(
                    wallId = shower.id, side = ViewerSide.BELOW,
                    wallHeightIn = Shower.prop(shower, Shower.CEILING), showerFace = f
                )
                onEvent(DrawingEvent.StartPlacement(Placement.Elevation(spec, "shower ${Shower.faceLabels[f].lowercase()} elevation")))
            }) { Text(Shower.faceLabels[f]) }
        }
    }
}

/** A stair's settings: rise, treads, width, landing split, rails, and its side elevations. */
@Composable
private fun StairSection(stair: DrawingElement.Symbol, onEvent: (DrawingEvent) -> Unit) {
    val type = Stairs.type(stair.symbolName)
    fun set(key: String, v: String) = onEvent(DrawingEvent.UpdateElementProperty(stair.id, key, v))
    @Composable
    fun inches(label: String, key: String) {
        EditableProperty("$label (in)", formatInches(Stairs.prop(stair, key))) { set(key, it) }
    }
    @Composable
    fun chips(title: String, key: String, labels: List<String>) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        val chosen = Stairs.code(stair, key)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, label -> FilterChip(selected = chosen == i, onClick = { set(key, i.toString()) }, label = { Text(label) }) }
        }
    }
    Text(text = "Stair", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(text = Stairs.summary(stair), style = MaterialTheme.typography.bodySmall)
    Text(
        text = "Risers aim for 7 1/2\", never under 6 1/4\" or over 8 1/4\".",
        style = MaterialTheme.typography.bodySmall
    )
    // Up or down from this floor, and the rise from the building unless typed (Nov 24).
    chips("Goes", Stairs.GOES, Stairs.goesLabels)
    if (type != Stairs.Type.DECK) {
        val follows = Stairs.code(stair, Stairs.RISE_SET) == 0
        Text(
            text = if (follows) "Rise follows the building (floor heights, basement slab)." else "Rise typed by hand.",
            style = MaterialTheme.typography.bodySmall
        )
        if (!follows) TextButton(onClick = { set(Stairs.RISE_SET, "0") }) { Text("Follow the building") }
    }
    inches("Total rise, floor to floor", Stairs.RISE)
    // Finish floor at each end (Nov 24): blank/automatic = 3/4" on a framed floor, 0 on a slab.
    if (type != Stairs.Type.DECK) {
        val auto = Stairs.prop(stair, Stairs.FIN_TOP) < 0f && Stairs.prop(stair, Stairs.FIN_BOT) < 0f
        EditableProperty("Finish floor at the top (in)", if (Stairs.prop(stair, Stairs.FIN_TOP) < 0f) "auto" else formatInches(Stairs.prop(stair, Stairs.FIN_TOP))) { set(Stairs.FIN_TOP, it) }
        EditableProperty("Finish floor at the bottom (in)", if (Stairs.prop(stair, Stairs.FIN_BOT) < 0f) "auto" else formatInches(Stairs.prop(stair, Stairs.FIN_BOT))) { set(Stairs.FIN_BOT, it) }
        Text(text = "Rise is finish to finish: auto = 3/4\" on a framed floor, 0 on the basement slab.", style = MaterialTheme.typography.bodySmall)
        if (!auto) TextButton(onClick = { set(Stairs.FIN_TOP, "-1"); set(Stairs.FIN_BOT, "-1") }) { Text("Automatic finish") }
    }
    chips("Riser count on the plan", Stairs.SHOW_R, listOf("Off", "On"))
    inches("Tread depth", Stairs.TREAD)
    inches("Nosing", Stairs.NOSING)
    if (type == Stairs.Type.CURVED) {
        inches("Inside (open) radius", Stairs.INNER_R)
        inches("Outside (wall) radius", Stairs.OUTER_R)
        inches("Walkline, in from the inside", Stairs.WALKLINE)
        chips("Turns", Stairs.TURN, listOf("Right", "Left"))
        val c = Stairs.curve(stair)
        if (c.treadAt(c.ri) < 6f) Text(
            text = "The inside tread is only ${formatInches(c.treadAt(c.ri))}\": IRC wants at least 6\" at the narrow end.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
        )
    } else {
        inches(if (type == Stairs.Type.SPIRAL) "Diameter" else "Width", Stairs.WIDTH)
    }
    if (type == Stairs.Type.L_LANDING || type == Stairs.Type.U_LANDING) {
        val l = Stairs.layout(stair)
        EditableProperty("Risers before the landing", l.flight1.toString()) { set(Stairs.FLIGHT1, it) }
    }
    if (type == Stairs.Type.L_LANDING) {
        chips("Turns", Stairs.TURN, listOf("Right", "Left"))
        chips("Corner", Stairs.WINDERS, Stairs.winderLabels)
    }
    if (type == Stairs.Type.SPIRAL) {
        EditableProperty("Treads per full turn", Stairs.code(stair, Stairs.PER_TURN).toString()) { set(Stairs.PER_TURN, it) }
        chips("Outer rail", Stairs.RAIL_RIGHT, Stairs.railLabels)
    } else if (type == Stairs.Type.CURVED) {
        chips("Inside (open) side", Stairs.RAIL_LEFT, Stairs.railLabels)
        chips("Outside (wall) side", Stairs.RAIL_RIGHT, Stairs.railLabels)
    } else {
        chips("Left side", Stairs.RAIL_LEFT, Stairs.railLabels)
        chips(if (type == Stairs.Type.U_LANDING) "Between the flights" else "Right side", Stairs.RAIL_RIGHT, Stairs.railLabels)
    }
    inches("Handrail height", Stairs.RAIL_H)
    inches("Guard height", Stairs.GUARD_H)
    if (type != Stairs.Type.DECK) {
        chips("Guard around the floor opening", Stairs.GUARD_AUTO, listOf("Off", "On"))
        Text(text = "With Build from the floor plan on: a guard on each open side of the stair's floor opening (not where a wall runs, or the stair has its own guard).",
            style = MaterialTheme.typography.bodySmall)
    }
    Text(text = "Elevation (tap one, then tap an empty spot on the canvas)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val sides = if (type == Stairs.Type.SPIRAL) listOf(0) else listOf(0, 1)
        sides.forEach { side ->
            OutlinedButton(onClick = {
                val spec = ElevationSpec(wallId = stair.id, side = ViewerSide.BELOW, stairSide = side)
                onEvent(DrawingEvent.StartPlacement(Placement.Elevation(spec, "stair elevation")))
            }) { Text(if (type == Stairs.Type.SPIRAL) "Side elevation" else Stairs.sideLabelsFor(stair)[side]) }
        }
    }
}

/** A guard rail (Guards.kt): its height and whether the plan notes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuardSection(guard: DrawingElement.Symbol, onEvent: (DrawingEvent) -> Unit) {
    val g = com.example.sitedraft.ui.canvas.Guards
    fun set(key: String, v: String) = onEvent(DrawingEvent.UpdateElementProperty(guard.id, key, v))
    Text(text = "Guard rail", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(text = "Stretch it along the edge like a wall. 36\" where the drop is over 30\" (IRC R312); balusters under 4\" apart.",
        style = MaterialTheme.typography.bodySmall)
    if (DerivedLayers.isGenerated(guard)) Text(text = "Made from the stair's floor opening: change it on the stair.", style = MaterialTheme.typography.bodySmall)
    EditableProperty("Guard height (in)", formatInches(g.prop(guard, g.HEIGHT))) { set(g.HEIGHT, it) }
    Text(text = "Note on the plan", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Off", "On").forEachIndexed { i, label ->
            FilterChip(selected = (g.prop(guard, g.NOTE) >= 0.5f) == (i == 1), onClick = { set(g.NOTE, i.toString()) }, label = { Text(label) })
        }
    }
}

/** Foundation and framing settings (Structure.kt): thickness, joist layout, ladder blocks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StructureSection(s: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    fun set(key: String, v: String) = onEvent(DrawingEvent.UpdateElementProperty(s.id, key, v))
    @Composable
    fun inches(label: String, key: String) {
        EditableProperty("$label (in)", formatInches(Structure.prop(s, key))) { set(key, it) }
    }
    @Composable
    fun chips(title: String, key: String, labels: List<String>) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        val chosen = Structure.code(s, key)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, label -> FilterChip(selected = chosen == i, onClick = { set(key, i.toString()) }, label = { Text(label) }) }
        }
    }
    Text(text = s.symbolName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    val summary = Structure.summary(s, state)
    if (summary.isNotEmpty()) Text(text = summary, style = MaterialTheme.typography.bodySmall)
    if (DerivedLayers.isGenerated(s)) {
        Text(
            if (DerivedLayers.isUntouched(s)) "Built from the floor plan; it follows the plan until you change it."
            else "Built from the floor plan, then changed by hand: it no longer follows the plan.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    when (s.symbolName) {
        Structure.FOOTING, Structure.PAD, Structure.SLAB -> inches("Thickness", Structure.THICK)
        Structure.JOIST_BAY -> {
            inches("Joist spacing, on center", Structure.SPACING)
            chips("Joist", Structure.JOIST, Structure.joistSizes.map { it.first })
            chips("Joists run", Structure.DIRECTION, Structure.directionLabels)
            EditableProperty("Beams across the joists", Structure.code(s, Structure.BEAMS).toString()) { set(Structure.BEAMS, it) }
            Text("Openings: place a Floor Opening over the bay; its joists are cut, the trimmers doubled, and the headers framed (doubled over 4', hangers over 6').", style = MaterialTheme.typography.bodySmall)
        }
        Structure.LADDER -> {
            inches("Blocks, on center", Structure.BLOCKS)
            Text(
                "Laid out from the end of the wall so the blocks land under the studs. Code max 48\" o.c. (R502.2).",
                style = MaterialTheme.typography.bodySmall
            )
            if (Structure.prop(s, Structure.BLOCKS) > Structure.MAX_LADDER_BLOCKS_IN) {
                Text("Over the 48\" code maximum.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


/**
 * Paint for the wall face an elevation shows (Nov 26): the colour on it now with a swatch, a search of the
 * Sherwin-Williams colours by number or name, and any colour by hex with a name of your own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaintPicker(current: WallDecor?, onPick: (hex: String, code: String, name: String) -> Unit, onRemove: (String) -> Unit) {
    Text(text = "Paint", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    if (current != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or current.rgb)).border(1.dp, Color.Gray, CircleShape))
            Text(current.paintLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemove(current.id) }) { Text("Remove") }
        }
    }
    var query by remember { mutableStateOf("") }
    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Sherwin-Williams number or name") })
    val found = remember(query) { PaintColors.search(query) }
    if (query.isNotBlank() && found.isEmpty()) {
        Text(if (PaintColors.all.isEmpty()) "The colour list isn't loaded yet: type a hex below." else "No colour matches.", style = MaterialTheme.typography.bodySmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        found.forEach { c ->
            FilterChip(selected = current?.colorCode == c.code, onClick = { onPick(c.hex, c.code, c.name) },
                leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or c.rgb)).border(1.dp, Color.Gray, CircleShape)) },
                label = { Text("${c.code} ${c.name}", style = MaterialTheme.typography.labelSmall, maxLines = 1) })
        }
    }
    var hex by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Text("Or any colour:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = hex, onValueChange = { hex = it }, singleLine = true, modifier = Modifier.width(110.dp), label = { Text("Hex") })
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Name") })
    }
    TextButton(enabled = WallDecorations.parseHex(hex) != null, onClick = { onPick(hex, "", name) }) { Text("Use this colour") }
}
