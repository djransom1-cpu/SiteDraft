package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.OpeningPosition
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.UnitSystem
import com.example.sitedraft.ui.canvas.formatFeetInches
import com.example.sitedraft.ui.canvas.parseFeet

/** A typed distance in inches: feet-inches (2'-4", 28", 1' 6 1/2") or metres on a metric drawing. */
fun typedInches(text: String, state: CanvasState): Float? =
    if (state.unitSystem == UnitSystem.METRIC) text.trim().removeSuffix("m").trim().toFloatOrNull()?.let { it / 0.0254f }
    else parseFeet(text)?.let { it * 12f }

/**
 * Move by a typed distance (Dan, Nov 27): a wall moves square to itself (the walls meeting it follow, its doors and windows
 * go with it); a door or window slides along its wall; anything else moves north, south, east or west.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoveBox(element: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    var text by remember(element.id) { mutableStateOf("") }
    var bad by remember(element.id) { mutableStateOf(false) }
    val b = FloorPlan.bounds(element)
    val isWall = element.category == PlanSymbols.WALLS
    val host = if (element.category == PlanSymbols.DOORS_WINDOWS) OpeningPosition.hostWall(element, state) else null
    // Which ways it may go: (label, east, south).
    val ways: List<Triple<String, Float, Float>> = when {
        isWall && b.width >= b.height -> listOf(Triple("North", 0f, -1f), Triple("South", 0f, 1f))
        isWall -> listOf(Triple("West", -1f, 0f), Triple("East", 1f, 0f))
        host != null && FloorPlan.bounds(host).let { it.width >= it.height } -> listOf(Triple("Slide west", -1f, 0f), Triple("Slide east", 1f, 0f))
        host != null -> listOf(Triple("Slide north", 0f, -1f), Triple("Slide south", 0f, 1f))
        else -> listOf(Triple("North", 0f, -1f), Triple("South", 0f, 1f), Triple("West", -1f, 0f), Triple("East", 1f, 0f))
    }
    Column(modifier = Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Move", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = text, onValueChange = { text = it; bad = false },
            label = { Text(if (state.unitSystem == UnitSystem.METRIC) "Distance (m)" else "Distance (1'-6 1/2\")") },
            isError = bad, singleLine = true, modifier = Modifier.width(220.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, e, s) in ways) OutlinedButton(onClick = {
                val d = typedInches(text, state)
                if (d == null || d <= 0f) bad = true else onEvent(DrawingEvent.MoveSelectedBy(e * d, s * d))
            }) { Text(label) }
        }
        if (bad) Text("Type a distance like 6\" or 1'-6 1/2\"", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (isWall) Text("The walls that meet it stretch to follow; its doors and windows go with it.", style = MaterialTheme.typography.bodySmall)
    }
}

/** After a Move drag: how far it went, and a box to make it exactly a typed distance the same way (Dan, Nov 27). */
@Composable
fun LastMoveBar(state: CanvasState, onEvent: (DrawingEvent) -> Unit, modifier: Modifier = Modifier) {
    val dir = state.lastMoveDir ?: return
    val way = when { dir.x > 0f -> "east"; dir.x < 0f -> "west"; dir.y > 0f -> "south"; else -> "north" }
    var text by remember(state.lastMoveDistanceIn, dir) { mutableStateOf("") }
    var bad by remember(dir) { mutableStateOf(false) }
    fun go() {
        val d = typedInches(text, state)
        if (d == null || d <= 0f) bad = true else onEvent(DrawingEvent.MakeLastMoveExactly(d))
    }
    Card(modifier = modifier) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Moved ${formatFeetInches(state.lastMoveDistanceIn / 12f)} $way. Make it exactly", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = text, onValueChange = { text = it; bad = false }, isError = bad, singleLine = true,
                placeholder = { Text("1'-6\"") }, modifier = Modifier.width(130.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { go() })
            )
            Button(onClick = { go() }) { Text("Set") }
            TextButton(onClick = { onEvent(DrawingEvent.DismissLastMove) }) { Text("Close") }
        }
    }
}
