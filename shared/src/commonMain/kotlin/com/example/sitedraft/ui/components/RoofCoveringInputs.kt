package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.RoofCovering
import com.example.sitedraft.ui.canvas.RoofPlan
import com.example.sitedraft.ui.canvas.formatInches
import com.example.sitedraft.ui.canvas.parseInches

/**
 * The roofing (Roof plan layer, Dan Nov 22): what the roof is covered with and the spacing its
 * pattern is drawn at. The roof itself (pitch, ends, overhangs) is set on the Roof framing layer;
 * this only picks what goes on it, shown on the roof plan and the exterior elevations.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoofCoveringInputs(state: CanvasState, onApply: (BuildingSettings) -> Unit) {
    val building = state.building
    val roof = building.roof
    if (roof == null) {
        Text("No roof yet: set it up on the Roof framing layer first (trusses or rafters, pitch, ends).", style = MaterialTheme.typography.bodySmall)
        return
    }
    var covering by remember(roof) { mutableStateOf(roof.covering) }
    var spacing by remember(roof) { mutableStateOf(formatInches(roof.coveringIn ?: roof.covering.defaultIn)) }
    var soffit by remember(roof) { mutableStateOf(roof.soffit) }
    var attic by remember(roof) { mutableStateOf(roof.attic) }
    var rValue by remember(roof) { mutableStateOf(roof.atticR.toString()) }
    var ice by remember(roof) { mutableStateOf(formatInches(roof.iceWaterFt)) }
    val parsed = parseInches(spacing)?.takeIf { it >= 1f && it <= 48f }?.let { cv ->
        val rv = rValue.trim().toIntOrNull()?.takeIf { it in 5..100 } ?: return@let null
        val iw = ice.trim().toFloatOrNull()?.takeIf { it in 0f..60f } ?: return@let null
        building.copy(roof = roof.copy(covering = covering, coveringIn = cv.takeIf { it != covering.defaultIn },
            soffit = soffit, attic = attic, atticR = rv, iceWaterFt = iw))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoofCovering.entries.forEach { c ->
                FilterChip(selected = covering == c, onClick = {
                    // A new roofing starts at its own spacing.
                    if (c != covering) spacing = formatInches(c.defaultIn)
                    covering = c
                }, label = { Text(c.label) })
            }
        }
        OutlinedTextField(spacing, { spacing = it }, label = { Text("${covering.spacingLabel} (in)") }, singleLine = true,
            isError = parsed == null, modifier = Modifier.fillMaxWidth())
        // The eave, for the overhang detail (Dan: per job).
        Text("Eave", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.example.sitedraft.ui.canvas.SoffitType.entries.forEach { t -> FilterChip(selected = soffit == t, onClick = { soffit = t }, label = { Text("Soffit: ${t.label}") }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.example.sitedraft.ui.canvas.AtticInsulation.entries.forEach { t -> FilterChip(selected = attic == t, onClick = { attic = t }, label = { Text(t.label) }) }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(rValue, { rValue = it }, label = { Text("Attic R-value (per the energy code)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(ice, { ice = it }, label = { Text("Ice & water shield up from the eave (ft)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = { parsed?.let(onApply) }, enabled = parsed != null && parsed != building, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
        RoofPlan.summary(state, roof).lastOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
