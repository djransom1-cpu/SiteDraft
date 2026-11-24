package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.RemodelPhase
import com.example.sitedraft.ui.canvas.Remodel
import com.example.sitedraft.ui.canvas.WallStatus
import com.example.sitedraft.ui.canvas.parseFeet

/**
 * Remodel controls (Remodel.kt) for the Layers panel: which plan the walls show for, and what
 * new walls are drawn as. Draw the house as Existing, mark what comes out as Demo, then switch
 * to New for the additions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemodelControls(state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Remodel", style = MaterialTheme.typography.titleMedium)
        Text("Show the plan as", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RemodelPhase.entries.forEach { p ->
                FilterChip(selected = state.remodelPhase == p, onClick = { onEvent(DrawingEvent.SetRemodelPhase(p)) }, label = { Text(p.label) })
            }
        }
        Text("Draw new walls as", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WallStatus.entries.forEach { st ->
                FilterChip(selected = state.wallDrawStatus == st, onClick = { onEvent(DrawingEvent.SetWallDrawStatus(st)) }, label = { Text(st.label) })
            }
        }
        // Existing house framing: off unless wanted (Dan, Nov 24: framing plans are for new work;
        // an existing house is laid out its own way).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show existing framing", style = MaterialTheme.typography.labelLarge)
                Text("The existing house's floor as an existing-conditions plan shows it: joist direction and size, existing beam and posts.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.building.showExistingFraming, onCheckedChange = {
                onEvent(DrawingEvent.SetBuilding(state.building.copy(showExistingFraming = it)))
            })
        }
        Text(
            "Measuring a house: draw as Existing. Then select the walls coming out and mark them Demo (or demo part of a wall), and switch to New for the additions. Existing walls show as an outline, demo dashed, new in full.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** The phase chips for the quick layer bar along the top of the canvas, once the drawing is a remodel. */
@Composable
fun RemodelPhaseChips(state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    RemodelPhase.entries.forEach { p ->
        FilterChip(selected = state.remodelPhase == p, onClick = { onEvent(DrawingEvent.SetRemodelPhase(p)) }, label = { Text(p.label, maxLines = 1) })
    }
}

/** A selected wall's remodel status, and demo part of it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemodelWallSection(wall: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    val status = Remodel.status(wall)
    val horizontal = wall.size.width >= wall.size.height
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Remodel", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WallStatus.entries.forEach { st ->
                FilterChip(selected = status == st, onClick = { onEvent(DrawingEvent.SetWallStatus(st)) }, label = { Text(st.label) })
            }
        }
        if (wall.rotation % 90f == 0f) {
            Text("Demo part of this wall", style = MaterialTheme.typography.labelLarge)
            var fromText by remember(wall.id) { mutableStateOf("") }
            var lengthText by remember(wall.id) { mutableStateOf("") }
            var fromStart by remember(wall.id) { mutableStateOf(true) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = fromStart, onClick = { fromStart = true }, label = { Text(if (horizontal) "From left end" else "From top end") })
                FilterChip(selected = !fromStart, onClick = { fromStart = false }, label = { Text(if (horizontal) "From right end" else "From bottom end") })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(fromText, { fromText = it }, label = { Text("Starts at (ft' in\")") }, singleLine = true, modifier = Modifier.width(150.dp))
                OutlinedTextField(lengthText, { lengthText = it }, label = { Text("Length") }, singleLine = true, modifier = Modifier.width(120.dp))
            }
            val fromFt = parseFeet(fromText.ifBlank { "0" })
            val lengthFt = parseFeet(lengthText)
            Button(
                enabled = fromFt != null && lengthFt != null && lengthFt > 0f,
                onClick = { onEvent(DrawingEvent.DemoWallPart(wall.id, fromFt!! * 12f, lengthFt!! * 12f, fromStart)) }
            ) { Text("Demo this part") }
            Text("Splits the wall: the piece coming out shows dashed, the rest stays ${if (status == WallStatus.DEMO) "Existing" else status.label}.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Framing (joist bays, beams, posts, ladders): New, or Existing drawn like an existing-conditions plan. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemodelFramingSection(symbol: DrawingElement.Symbol, onEvent: (DrawingEvent) -> Unit) {
    val status = Remodel.statusOf(symbol)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Remodel", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(WallStatus.NEW, WallStatus.EXISTING).forEach { st ->
                FilterChip(selected = status == st, onClick = { onEvent(DrawingEvent.SetWallStatus(st)) }, label = { Text(st.label) })
            }
        }
        Text("Existing joists show as the way they run with their size, like an existing-conditions plan.", style = MaterialTheme.typography.bodySmall)
    }
}
