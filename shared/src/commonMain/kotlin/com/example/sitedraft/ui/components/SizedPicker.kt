package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.Placement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.UnitSystem
import com.example.sitedraft.ui.canvas.parseFeet
import kotlin.math.abs

/**
 * Pick the kind first, then the size (Dan, Nov 27: the doors list got too long): one button per kind; tapping it shows its
 * stock sizes and an Other box for any width. Picking a size starts placing it, as tapping a library symbol did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SizedPicker(groups: List<PlanSymbols.SizedGroup>, category: String, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    val pending = state.pendingPlacement as? Placement.Symbol
    var open by remember { mutableStateOf<String?>(groups.firstOrNull { g -> pending != null && pending.name in g.sizes }?.label) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (g in groups) FilterChip(
                selected = open == g.label,
                onClick = { open = if (open == g.label) null else g.label },
                label = { Text(g.label) }
            )
        }
        val g = groups.firstOrNull { it.label == open } ?: return@Column
        Text("${g.label}: pick a size, then tap where it goes", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in g.sizes) FilterChip(
                selected = pending?.name == name && pending.widthIn == null,
                onClick = { onEvent(DrawingEvent.SelectSymbol(name, category)) },
                label = { Text(PlanSymbols.sizeLabel(name)) }
            )
        }
        // Any width: the nearest stock size, set to it.
        var other by remember(g.label) { mutableStateOf("") }
        var bad by remember(g.label) { mutableStateOf(false) }
        val metric = state.unitSystem == UnitSystem.METRIC
        fun go() {
            val inches = if (metric) other.trim().removeSuffix("m").trim().toFloatOrNull()?.let { it / 0.0254f }
                else parseFeet(other)?.let { it * 12f }
            if (inches == null || inches < 6f || inches > 240f) { bad = true; return }
            bad = false
            val nearest = g.sizes.minByOrNull { abs((PlanSymbols.footprintInches[it]?.first ?: 0f) - inches) } ?: return
            onEvent(DrawingEvent.SelectSymbol(nearest, category, inches))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = other, onValueChange = { other = it; bad = false },
                label = { Text(if (metric) "Other width (m)" else "Other width (2'-4\")") },
                isError = bad, singleLine = true, modifier = Modifier.width(180.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { go() })
            )
            TextButton(onClick = { go() }, modifier = Modifier.padding(start = 4.dp)) { Text("Use") }
        }
        if (bad) Text(if (metric) "Type a width like 0.71" else "Type a width like 2'-4\" or 28\"", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (pending != null && pending.widthIn != null && pending.name in g.sizes)
            AssistChip(onClick = {}, label = { Text("Placing: ${pending.label}") })
    }
}
