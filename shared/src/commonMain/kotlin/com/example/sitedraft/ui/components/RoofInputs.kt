package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Garages
import com.example.sitedraft.ui.canvas.Wings
import com.example.sitedraft.ui.canvas.RoofEnd
import com.example.sitedraft.ui.canvas.RoofFraming
import com.example.sitedraft.ui.canvas.RoofPlan
import com.example.sitedraft.ui.canvas.RoofSettings
import com.example.sitedraft.ui.canvas.formatInches
import com.example.sitedraft.ui.canvas.parseInches

/**
 * The roof's inputs (Roof structural layer, or the roof selected on the canvas). Set up starts by
 * asking trusses or rafters (Dan picks per job); then pitch, overhangs, heel, spacing, member size,
 * each end gable or hip, and the wall height it sits on. Applied together, so a half-typed number
 * never moves the roof. ProCalc's numbers for it are listed under.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoofInputs(state: CanvasState, onApply: (BuildingSettings) -> Unit) {
    val building = state.building
    val roof = building.roof
    if (roof == null) {
        Text("No roof yet. Trusses or rafters on this job?", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onApply(building.copy(roof = RoofSettings(framing = RoofFraming.TRUSSES, ocIn = 24f))) }) { Text("Trusses") }
            Button(onClick = { onApply(building.copy(roof = RoofSettings(framing = RoofFraming.RAFTERS, ocIn = 16f))) }) { Text("Rafters") }
        }
        return
    }
    var framing by remember(roof) { mutableStateOf(roof.framing) }
    var pitch by remember(roof) { mutableStateOf(formatInches(roof.pitch)) }
    var overhang by remember(roof) { mutableStateOf(formatInches(roof.overhangIn)) }
    var rake by remember(roof) { mutableStateOf(formatInches(roof.rakeIn)) }
    var heel by remember(roof) { mutableStateOf(formatInches(roof.heelIn)) }
    var oc by remember(roof) { mutableStateOf(formatInches(roof.ocIn)) }
    var size by remember(roof) { mutableStateOf(roof.rafterSize) }
    var endA by remember(roof) { mutableStateOf(roof.endA) }
    var endB by remember(roof) { mutableStateOf(roof.endB) }
    var turn by remember(roof) { mutableStateOf(roof.turnRidge) }
    var plate by remember(building) { mutableStateOf(formatInches(building.plateHeightIn)) }
    var girder by remember(roof) { mutableStateOf(formatInches(roof.girderHeightIn / 12f)) }
    var addOn by remember(roof) { mutableStateOf(formatInches(roof.addOnOcIn)) }

    val parsed: BuildingSettings? = run {
        val p = parseInches(pitch)?.takeIf { it > 0f && it <= 24f } ?: return@run null
        val o = parseInches(overhang)?.takeIf { it >= 0f } ?: return@run null
        val rk = parseInches(rake)?.takeIf { it >= 0f } ?: return@run null
        val h = parseInches(heel)?.takeIf { it >= 0f } ?: return@run null
        val s = parseInches(oc)?.takeIf { it >= 4f } ?: return@run null
        val pl = parseInches(plate)?.takeIf { it > 0f } ?: return@run null
        val gh = parseInches(girder)?.takeIf { it > 0f && it <= 20f } ?: return@run null
        val ao = parseInches(addOn)?.takeIf { it >= 4f } ?: return@run null
        building.copy(plateHeightIn = pl, roof = roof.copy(framing = framing, pitch = p, overhangIn = o, rakeIn = rk, heelIn = h, ocIn = s,
            rafterSize = size, endA = endA, endB = endB, turnRidge = turn, girderHeightIn = gh * 12f, addOnOcIn = ao))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Switching changes the spacing to that framing's usual (Dan: trusses 24", rafters 16") if it was the other's.
            RoofFraming.entries.forEach { f -> FilterChip(selected = framing == f, onClick = {
                if (f != framing) {
                    if (f == RoofFraming.TRUSSES && parseInches(oc) == 16f) oc = "24"
                    if (f == RoofFraming.RAFTERS && parseInches(oc) == 24f) oc = "16"
                }
                framing = f
            }, label = { Text(f.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(pitch, { pitch = it }, label = { Text("Pitch (rise in 12)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(oc, { oc = it }, label = { Text("Spacing o.c. (in)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(overhang, { overhang = it }, label = { Text("Eave overhang (in)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(rake, { rake = it }, label = { Text("Rake overhang (in)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(heel, { heel = it }, label = { Text("Heel (in)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(plate, { plate = it }, label = { Text("Wall height to top plate (in)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (framing == RoofFraming.TRUSSES && (endA == RoofEnd.HIP || endB == RoofEnd.HIP)) {
            OutlinedTextField(girder, { girder = it }, label = { Text("Hip girder where the truss is this tall (ft)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (state.elements.any { it is DrawingElement.Symbol && (Garages.isGarage(it) || Wings.isWing(it)) }) {
            OutlinedTextField(addOn, { addOn = it }, label = { Text("Garage and wing trusses o.c. (in)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (framing == RoofFraming.RAFTERS) {
            Text("Rafter size", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("2x6", "2x8", "2x10", "2x12").forEach { z -> FilterChip(selected = size == z, onClick = { size = z }, label = { Text(z) }) }
            }
        }
        Text("Ends (left / top end, then right / bottom end)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoofEnd.entries.forEach { e -> FilterChip(selected = endA == e, onClick = { endA = e }, label = { Text("First end: ${e.label}") }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoofEnd.entries.forEach { e -> FilterChip(selected = endB == e, onClick = { endB = e }, label = { Text("Second end: ${e.label}") }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Turn the ridge the other way", modifier = Modifier.weight(1f))
            Switch(checked = turn, onCheckedChange = { turn = it })
        }
        Button(onClick = { parsed?.let(onApply) }, enabled = parsed != null && parsed != building, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
        if (parsed == null) Text("Check the numbers: pitch up to 24, spacing at least 4\".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        // ProCalc's numbers for the roof as it stands.
        RoofPlan.summary(state, roof).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { onApply(building.copy(roof = null)) }) { Text("Remove the roof", color = MaterialTheme.colorScheme.error) }
    }
}
