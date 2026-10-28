package com.example.sitedraft.ui.components

import com.example.sitedraft.util.format
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.sitedraft.data.PalmRejectionMode
import com.example.sitedraft.data.rememberPalmRejection
import com.example.sitedraft.ui.canvas.*

@Composable
fun SettingsPanel(
    state: CanvasState,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Project Settings",
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Text(
                text = "Unit System",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Column(Modifier.selectableGroup()) {
                UnitOption(
                    label = "Metric (m, cm, mm)",
                    selected = state.unitSystem == UnitSystem.METRIC,
                    onClick = { onEvent(DrawingEvent.SelectUnitSystem(UnitSystem.METRIC)) }
                )
                UnitOption(
                    label = "Imperial (ft, in)",
                    selected = state.unitSystem == UnitSystem.IMPERIAL,
                    onClick = { onEvent(DrawingEvent.SelectUnitSystem(UnitSystem.IMPERIAL)) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Drawing Scale",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            val scales = if (state.unitSystem == UnitSystem.METRIC) {
                listOf(
                    "1m = 20px" to 20f,
                    "1m = 50px" to 50f,
                    "1m = 100px" to 100f,
                    "1m = 200px" to 200f
                )
            } else {
                listOf(
                    "1' = 20px" to 20f,
                    "1' = 50px" to 50f,
                    "1' = 100px" to 100f,
                    "1' = 200px" to 200f
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                scales.forEach { (label, ratio) ->
                    val isSelected = state.scale.label == label
                    OutlinedButton(
                        onClick = { onEvent(DrawingEvent.UpdateScale(ratio, label)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) else ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text(label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Default Line Weight",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Slider(
                value = state.currentStrokeWidth,
                onValueChange = { onEvent(DrawingEvent.SelectStrokeWidth(it)) },
                valueRange = 0.1f..20f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "%.1f px".format(state.currentStrokeWidth),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.End)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Canvas Color",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CanvasColorChip(
                    label = "Blueprint",
                    color = BlueprintCanvasColor,
                    selected = state.backgroundColor == BlueprintCanvasColor,
                    onClick = { onEvent(DrawingEvent.SelectBackgroundColor(BlueprintCanvasColor)) }
                )
                CanvasColorChip(
                    label = "White",
                    color = Color.White,
                    selected = state.backgroundColor == Color.White,
                    onClick = { onEvent(DrawingEvent.SelectBackgroundColor(Color.White)) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Background Style",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawingBackground.entries.forEach { bg ->
                    FilterChip(
                        selected = state.background == bg,
                        onClick = { onEvent(DrawingEvent.SelectBackground(bg)) },
                        label = { Text(bg.name) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Input Control",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // Device-level (DataStore), not part of the project - see rememberPalmRejection.
            val palm = rememberPalmRejection()
            Text(
                text = "Palm Rejection",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PalmRejectionMode.entries.forEach { m ->
                    FilterChip(
                        selected = palm.mode == m,
                        onClick = { palm.set(m) },
                        label = { Text(m.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
            Text(
                text = palm.mode.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Snap to Objects",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.snapEnabled,
                    onCheckedChange = { onEvent(DrawingEvent.ToggleSnap(it)) }
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Snap to Grid",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.snapToGrid,
                    onCheckedChange = { onEvent(DrawingEvent.ToggleGridSnap(it)) }
                )
            }
            

            Spacer(modifier = Modifier.weight(1f))
            
            Text(
                text = if (state.backgroundColor == Color.White) "White Canvas: Active" else "Blueprint Mode: Active",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// Matches the CanvasState.backgroundColor default (Blueprint Blue).
private val BlueprintCanvasColor = Color(0xFF003366)

// Selected state uses primaryContainer (same highlight as the Drawing Scale buttons) because
// the default FilterChip selected fill is almost identical to the panel's own blue.
@Composable
private fun CanvasColorChip(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { CanvasColorSwatch(color) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun CanvasColorSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .background(color, MaterialTheme.shapes.extraSmall)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
    )
}

@Composable
private fun UnitOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
