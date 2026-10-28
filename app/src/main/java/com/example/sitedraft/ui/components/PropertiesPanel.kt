package com.example.sitedraft.ui.components

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
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        PropertyRow("Type", selectedElement::class.simpleName ?: "Unknown")
                        
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
                        }
                    }
                    else -> {}
                }

                val displayRotation = if (selectedElement is DrawingElement.Line) {
                    val dir = selectedElement.end - selectedElement.start
                    val baseAngle = Math.toDegrees(atan2(dir.y.toDouble(), dir.x.toDouble())).toFloat()
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
    // Round to hundredths of an inch BEFORE splitting - see splitFeetInches.
    val (initialFeet, initialInches) = splitFeetInches(valueInFeet, 0.01f)
    
    var feetText by remember(valueInFeet) { mutableStateOf(initialFeet.toString()) }
    var inchesText by remember(valueInFeet) { mutableStateOf("%.2f".format(initialInches)) }
    
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
                singleLine = true
            )
            TextButton(onClick = {
                val f = feetText.toFloatOrNull() ?: 0f
                val i = inchesText.toFloatOrNull() ?: 0f
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
