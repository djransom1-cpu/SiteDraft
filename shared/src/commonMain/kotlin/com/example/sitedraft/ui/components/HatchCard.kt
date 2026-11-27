package com.example.sitedraft.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CabinetList
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.FillPattern
import com.example.sitedraft.ui.canvas.formatInches
import com.example.sitedraft.ui.canvas.inchesToPx

/**
 * The floor hatch's own card (Dan, Nov 27: "maybe a separate card for hatches"; a hatch couldn't be changed or deleted
 * while the floor plan panel was open). The pattern, the tile or plank size, and Delete.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HatchCard(hatch: DrawingElement.Rectangle, state: CanvasState, onEvent: (DrawingEvent) -> Unit, modifier: Modifier = Modifier, onDrag: (Offset) -> Unit = {}) {
    val drag by rememberUpdatedState(onDrag)
    val inch = state.inchesToPx(1f)
    val fill = hatch.fill
    val room = CabinetList.roomAt(hatch.topLeft + Offset(hatch.size.width / 2f, hatch.size.height / 2f), state)?.second
    Surface(modifier = modifier.width(300.dp).heightIn(max = 520.dp), shape = MaterialTheme.shapes.large, shadowElevation = 10.dp,
        tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); drag(amount) } }) {
                Icon(Icons.Default.DragIndicator, contentDescription = "Drag to move the card", modifier = Modifier.padding(end = 4.dp))
                Column(Modifier.weight(1f)) {
                    Text("Floor: ${fill.pattern.label}", style = MaterialTheme.typography.titleMedium)
                    Text(room ?: "Floor hatch", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { onEvent(DrawingEvent.DeselectElement) }) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            HorizontalDivider()
            Text("Pattern", style = MaterialTheme.typography.labelLarge)
            // The floor ones first; the rest are section and site hatches.
            val floors = listOf(FillPattern.TILE, FillPattern.BRICK, FillPattern.PLANKS, FillPattern.HERRINGBONE, FillPattern.HEXAGON,
                FillPattern.DOTS, FillPattern.CONCRETE, FillPattern.SOLID)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in floors) FilterChip(selected = fill.pattern == p, onClick = { onEvent(DrawingEvent.UpdateElementProperty(hatch.id, "fillPattern", p.name)) },
                    label = { Text(if (p == FillPattern.BRICK) "Subway / brick" else if (p == FillPattern.DOTS) "Carpet" else p.label) })
            }
            if (inch > 0f && fill.pattern in listOf(FillPattern.TILE, FillPattern.BRICK, FillPattern.PLANKS, FillPattern.HERRINGBONE, FillPattern.HEXAGON)) {
                val two = fill.pattern != FillPattern.HEXAGON
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InchBox(if (fill.pattern == FillPattern.PLANKS) "Board width (in)" else "Tile width (in)", fill.spacing / inch, Modifier.weight(1f)) {
                        if (it > 0f) onEvent(DrawingEvent.UpdateElementProperty(hatch.id, "fillSpacing", (it * inch).toString()))
                    }
                    if (two) InchBox("Length (in)", fill.length / inch, Modifier.weight(1f)) {
                        onEvent(DrawingEvent.UpdateElementProperty(hatch.id, "fillLength", (it * inch).toString()))
                    }
                }
                Text("Width runs up the page, length across it. Length 0 = the pattern's usual proportion.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { onEvent(DrawingEvent.DeleteSelectedElement) }) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text("  Delete this hatch")
            }
        }
    }
}

/** A number in inches, applied on Done (shared by the cards). */
@Composable
internal fun InchBox(label: String, value: Float, modifier: Modifier = Modifier, onSet: (Float) -> Unit) {
    var text by androidx.compose.runtime.remember(value) { androidx.compose.runtime.mutableStateOf(formatInches(value)) }
    androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true, modifier = modifier, label = { Text(label) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { com.example.sitedraft.ui.canvas.parseInches(text)?.let(onSet) }))
}
