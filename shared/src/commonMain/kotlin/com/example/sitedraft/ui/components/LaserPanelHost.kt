package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent

/**
 * The Bosch GLM laser panel. Bluetooth is Android-only today; other platforms show
 * [LaserUnavailablePanel]. [laser] is the platform's client object (a GlmLaserClient on Android).
 */
@Composable
expect fun LaserPanel(
    state: CanvasState,
    laser: Any?,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
)

@Composable
fun LaserUnavailablePanel(modifier: Modifier = Modifier, onClose: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Laser", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            Text(
                "Connecting a Bosch GLM laser over Bluetooth is available in the Android app. Typed wall lengths still work from the Plan panel.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
