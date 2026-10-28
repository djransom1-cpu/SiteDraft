package com.example.sitedraft.ui.components

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.sitedraft.data.laser.GlmLaserClient
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.UnitSystem
import com.example.sitedraft.ui.canvas.WallDirection
import com.example.sitedraft.ui.canvas.splitFeetInches
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import com.google.accompanist.permissions.rememberMultiplePermissionsState

private const val FEET_PER_METER = 3.2808399f

/** A laser distance in metres converted to canvas pixels at the project's scale. */
fun metersToPx(meters: Float, state: CanvasState): Float =
    if (state.unitSystem == UnitSystem.IMPERIAL) meters * FEET_PER_METER * state.scale.ratio else meters * state.scale.ratio

/** The value the "length"/"width"/"height" element properties expect: feet (Imperial) or metres. */
private fun metersToDisplayUnits(meters: Float, state: CanvasState): Float =
    if (state.unitSystem == UnitSystem.IMPERIAL) meters * FEET_PER_METER else meters

private fun formatMeters(meters: Float, state: CanvasState): String {
    return if (state.unitSystem == UnitSystem.IMPERIAL) {
        val (ft, inches) = splitFeetInches(meters * FEET_PER_METER, 1f / 16f)
        "$ft' %.1f\"".format(inches)
    } else {
        "%.3f m".format(meters)
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalLayoutApi::class)
@Composable
fun AndroidLaserPanel(
    state: CanvasState,
    laser: GlmLaserClient,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    val laserState by laser.state.collectAsState()
    val selected = state.elements.find { it.id == state.selectedElementId }
    val wall = state.wallMode
    val reading = laserState.reading

    // Permissions: scan + connect on Android 12+, fine location (for BLE scanning) below that.
    val permissions = rememberMultiplePermissionsState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    )
    val granted = permissions.allPermissionsGranted

    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    LaunchedEffect(granted) { if (granted) devices = laser.bondedDevices() }

    // Wall mode: every NEW reading adds a wall from the on-canvas cursor in its direction.
    var lastHandledSerial by remember { mutableIntStateOf(laserState.readingSerial) }
    LaunchedEffect(laserState.readingSerial) {
        if (laserState.readingSerial != lastHandledSerial) {
            lastHandledSerial = laserState.readingSerial
            if (wall.active && wall.cursor != null && reading != null) {
                onEvent(DrawingEvent.AddWall(metersToPx(reading.meters, state)))
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxHeight().width(320.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Laser Measure", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            // --- Connection ---
            Text("Bosch GLM", style = MaterialTheme.typography.titleMedium)
            when {
                !granted -> {
                    Text("Bluetooth permission is needed to find and talk to the GLM.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { permissions.launchMultiplePermissionRequest() }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow Bluetooth") }
                }
                !laser.isBluetoothOn() -> Text("Turn on Bluetooth in the system settings.", style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text(
                        "Turn the GLM's Bluetooth on (its Bluetooth button). If it is paired with the tablet, tap it under Paired; otherwise scan for it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = { laser.startScan() }, enabled = !laserState.scanning) {
                            Text(if (laserState.scanning) "Scanning…" else "Scan for GLM")
                        }
                        if (laserState.scanning) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    if (laserState.leDevices.isNotEmpty()) {
                        Text("Found", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            laserState.leDevices.forEach { d ->
                                FilterChip(
                                    selected = laserState.deviceName == d.name && laserState.status != GlmLaserClient.Status.DISCONNECTED,
                                    onClick = { laser.connectLe(d.address) },
                                    label = { Text(d.name, maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }
                    if (devices.isNotEmpty()) {
                        Text("Paired (classic Bluetooth, e.g. GLM 100 C)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            devices.forEach { d ->
                                val label = laser.deviceLabel(d)
                                FilterChip(
                                    selected = laserState.deviceName == label && laserState.status != GlmLaserClient.Status.DISCONNECTED,
                                    onClick = { laser.connectClassic(d) },
                                    label = { Text(label, maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                val statusText = when (laserState.status) {
                    GlmLaserClient.Status.DISCONNECTED -> "Not connected"
                    GlmLaserClient.Status.CONNECTING -> "Connecting to ${laserState.deviceName}…"
                    GlmLaserClient.Status.CONNECTED -> "Connected: ${laserState.deviceName}"
                    GlmLaserClient.Status.ERROR -> "Error: ${laserState.message ?: "unknown"}"
                }
                Text(statusText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (laserState.status != GlmLaserClient.Status.DISCONNECTED) {
                    OutlinedButton(onClick = { laser.disconnect() }) { Text("Disconnect") }
                }
            }

            Spacer(Modifier.height(16.dp))

            // --- Reading ---
            Text("Reading", style = MaterialTheme.typography.titleMedium)
            Text(
                text = reading?.let { formatMeters(it.meters, state) } ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            reading?.let { Text("%.3f m".format(it.meters), style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { laser.requestMeasurement() }, enabled = laserState.status == GlmLaserClient.Status.CONNECTED) { Text("Measure") }
                Text("Auto poll", style = MaterialTheme.typography.bodySmall)
                Switch(checked = laserState.autoPoll, onCheckedChange = { laser.setAutoPoll(it) })
            }
            OutlinedButton(
                onClick = { laser.probeChecksums() },
                enabled = laserState.status == GlmLaserClient.Status.CONNECTED
            ) { Text("Probe checksum (about 1 min)") }

            Spacer(Modifier.height(16.dp))

            // --- Walls on the canvas ---
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Wall mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = wall.active, onCheckedChange = { onEvent(DrawingEvent.SetWallMode(it)) })
            }
            Text(
                text = when {
                    !wall.active -> "Turn on to build a floor plan from laser readings."
                    wall.cursor == null -> "Tap the canvas where the first wall starts."
                    else -> "Tap an arrow on the canvas to set the direction, then take a measurement: each reading adds a wall from the marker. Drag the marker to move it."
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (wall.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    WallDirection.entries.forEach { d ->
                        FilterChip(
                            selected = wall.direction == d,
                            onClick = { onEvent(DrawingEvent.SetWallDirection(d)) },
                            label = { Text(d.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(
                        onClick = { reading?.let { onEvent(DrawingEvent.AddWall(metersToPx(it.meters, state))) } },
                        enabled = reading != null && wall.cursor != null
                    ) { Text("Add wall") }
                    OutlinedButton(
                        onClick = { onEvent(DrawingEvent.ContinueWallsFromSelectedLine) },
                        enabled = selected is DrawingElement.Line
                    ) { Text("From line") }
                }
                // Typed length: walls from a tape measure, or a way to try wall mode without the GLM.
                var typedLength by remember { mutableStateOf("") }
                val typedValue = typedLength.toFloatOrNull()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = typedLength,
                        onValueChange = { typedLength = it },
                        label = { Text(if (state.unitSystem == UnitSystem.IMPERIAL) "Length (ft)" else "Length (m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            typedValue?.let { onEvent(DrawingEvent.AddWall(it * state.scale.ratio)) }
                        },
                        enabled = typedValue != null && typedValue > 0f && wall.cursor != null
                    ) { Text("Add") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(
                        onClick = { onEvent(DrawingEvent.CloseRoom) },
                        enabled = wall.cursor != null && wall.roomStart != null && wall.cursor != wall.roomStart
                    ) { Text("Close room") }
                    OutlinedButton(onClick = { onEvent(DrawingEvent.NewRoom) }, enabled = wall.cursor != null) { Text("New room") }
                }
            }

            Spacer(Modifier.height(16.dp))

            // --- Apply to selection ---
            Text("Apply to selection", style = MaterialTheme.typography.titleMedium)
            val canSetLength = selected is DrawingElement.Line || selected is DrawingElement.Rectangle ||
                selected is DrawingElement.Triangle || selected is DrawingElement.Dimension || selected is DrawingElement.Symbol
            val canSetHeight = selected is DrawingElement.Rectangle || selected is DrawingElement.Triangle || selected is DrawingElement.Symbol
            Text(
                text = if (selected == null) "Select a line or shape to set its size from the laser." else "Selected: ${selected::class.simpleName}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(
                    onClick = {
                        if (selected != null && reading != null) {
                            val key = if (selected is DrawingElement.Line || selected is DrawingElement.Dimension) "length" else "width"
                            onEvent(DrawingEvent.UpdateElementProperty(selected.id, key, metersToDisplayUnits(reading.meters, state).toString()))
                        }
                    },
                    enabled = canSetLength && reading != null
                ) { Text(if (selected is DrawingElement.Line || selected is DrawingElement.Dimension) "Set length" else "Set width") }
                Button(
                    onClick = {
                        if (selected != null && reading != null) {
                            onEvent(DrawingEvent.UpdateElementProperty(selected.id, "height", metersToDisplayUnits(reading.meters, state).toString()))
                        }
                    },
                    enabled = canSetHeight && reading != null
                ) { Text("Set height") }
            }

            Spacer(Modifier.height(16.dp))

            // --- Raw frames, for matching the protocol to this device's firmware ---
            Text("Device log", style = MaterialTheme.typography.titleMedium)
            laserState.log.takeLast(8).forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 2)
            }
        }
    }
}

@Composable
actual fun LaserPanel(
    state: CanvasState,
    laser: Any?,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier,
    onClose: () -> Unit
) {
    val client = laser as? GlmLaserClient
    if (client == null) LaserUnavailablePanel(modifier, onClose)
    else AndroidLaserPanel(state = state, laser = client, onEvent = onEvent, modifier = modifier, onClose = onClose)
}
