package com.example.sitedraft.ui.components

import com.example.sitedraft.util.format
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.sitedraft.data.rememberRecentColors
import com.example.sitedraft.ui.canvas.DrawingBackground

@Composable
fun DrawingPropertiesDialog(
    currentColor: Color,
    currentWeight: Float,
    currentBackground: DrawingBackground,
    currentCanvasColor: Color,
    onColorSelect: (Color) -> Unit,
    onWeightSelect: (Float) -> Unit,
    onBackgroundSelect: (DrawingBackground) -> Unit,
    onCanvasColorSelect: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val recent = rememberRecentColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        // Match the Settings/Properties panels: the default light dialog surface renders the
        // Blueprint theme's white text nearly invisible.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        title = { Text("Drawing Properties") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Pen Color", style = MaterialTheme.typography.labelLarge)
                // Was a fixed row of seven colours; the picker gives a rendering swatch grid,
                // hue/saturation/value, opacity, hex entry and the user's recent colours.
                ColorPicker(
                    color = currentColor,
                    onColorChange = onColorSelect,
                    onColorCommitted = { onColorSelect(it); recent.add(it) },
                    recentColors = recent.colors
                )

                Text("Canvas Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedCard(
                        onClick = { onCanvasColorSelect(Color(0xFF003366)) },
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (currentCanvasColor == Color(0xFF003366)) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Text("Blueprint", modifier = Modifier.padding(8.dp))
                    }
                    OutlinedCard(
                        onClick = { onCanvasColorSelect(Color.White) },
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (currentCanvasColor == Color.White) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Text("White", modifier = Modifier.padding(8.dp))
                    }
                }

                Text("Pen Weight", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = currentWeight,
                    onValueChange = onWeightSelect,
                    valueRange = 0.1f..20f
                )
                Text("%.1f px".format(currentWeight))

                Text("Grid Overlay", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DrawingBackground.entries.forEach { bg ->
                        FilterChip(
                            selected = currentBackground == bg,
                            onClick = { onBackgroundSelect(bg) },
                            label = { Text(bg.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }
        }
    )
}
