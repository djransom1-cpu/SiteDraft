package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sitedraft.ui.canvas.DrawingEvent

@Composable
fun ZoomControls(
    currentZoom: Float,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        // Explicit: a translucent colour matches no theme colour (contentColorFor() fell back to black).
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Zoom Out
            IconButton(
                onClick = { onEvent(DrawingEvent.UpdateZoom(0.8f)) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = "Zoom Out"
                )
            }

            // Percentage Text
            Box(
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${(currentZoom * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zoom In
            IconButton(
                onClick = { onEvent(DrawingEvent.UpdateZoom(1.25f)) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Zoom In"
                )
            }

            // Divider
            VerticalDivider(
                modifier = Modifier
                    .height(24.dp)
                    .padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Fit the whole drawing in the view
            IconButton(
                onClick = { onEvent(DrawingEvent.ZoomToFit) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.FitScreen,
                    contentDescription = "Fit Drawing"
                )
            }

            // Reset Zoom
            IconButton(
                onClick = { onEvent(DrawingEvent.ResetZoom) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = "Reset Zoom"
                )
            }
        }
    }
}

@Composable
fun ZoomControlsPreviewLight() {
    MaterialTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            ZoomControls(
                currentZoom = 1.0f,
                onEvent = {}
            )
        }
    }
}

@Composable
fun ZoomControlsPreviewDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
            ZoomControls(
                currentZoom = 1.5f,
                onEvent = {}
            )
        }
    }
}
