package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.LotCall
import com.example.sitedraft.ui.canvas.SiteLot
import com.example.sitedraft.ui.canvas.SitePlan

/**
 * The lot from the survey (Site layer, or Job specs > Site): its calls in order from the point of
 * beginning - bearing, distance in feet, and that line's setback - then how the lot sits on the
 * house: turned and shifted until the house is where it goes. The closure is shown as you type.
 */
@Composable
fun SiteInputs(state: CanvasState, onApply: (BuildingSettings) -> Unit) {
    val building = state.building
    val site = building.site ?: SitePlan()
    fun num(f: Float) = if (f == 0f) "" else SiteLot.fixed2(f).removeSuffix(".00")
    val rows = remember(building.site) {
        mutableStateListOf<CallRow>().apply {
            site.calls.forEach { add(CallRow(it.bearing, SiteLot.fixed2(it.distanceFt), num(it.setbackFt))) }
            if (isEmpty()) repeat(4) { add(CallRow("", "", "")) }
        }
    }
    var rotation by remember(building.site) { mutableStateOf(num(site.rotationDeg)) }
    var east by remember(building.site) { mutableStateOf(num(site.shiftEastFt)) }
    var south by remember(building.site) { mutableStateOf(num(site.shiftSouthFt)) }
    var street by remember(building.site) { mutableStateOf(site.street) }
    var viewTurn by remember(building.site) { mutableStateOf(num(site.viewRotationDeg)) }

    fun f(t: String) = if (t.isBlank()) 0f else t.trim().toFloatOrNull()
    val calls: List<LotCall>? = rows.filter { it.bearing.isNotBlank() || it.distance.isNotBlank() }.map { r ->
        val d = f(r.distance)?.takeIf { it > 0f } ?: return@map null
        if (SiteLot.azimuthOf(r.bearing) == null) return@map null
        LotCall(r.bearing.trim(), d, f(r.setback)?.coerceAtLeast(0f) ?: return@map null)
    }.let { list -> if (list.any { it == null }) null else list.filterNotNull() }
    val parsed: BuildingSettings? = run {
        val c = calls ?: return@run null
        val rot = f(rotation) ?: return@run null
        val e = f(east) ?: return@run null
        val s = f(south) ?: return@run null
        val v = f(viewTurn) ?: return@run null
        building.copy(site = if (c.isEmpty()) null else SitePlan(c, rot, e, s, street.trim(), v))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Have the surveyor's site plan? Make the Site layer active and use Import > PDF, then Calibrate. " +
                "Otherwise type the survey's calls here, from the point of beginning, in order round the lot.",
            style = MaterialTheme.typography.bodySmall
        )
        rows.forEachIndexed { i, r ->
            // Bearing on its own line, feet and setback under it: fits the narrow Layers panel too.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}")
                OutlinedTextField(r.bearing, { r.bearing = it }, label = { Text("Bearing (N 45°30'15\" E)") }, singleLine = true,
                    isError = r.bearing.isNotBlank() && SiteLot.azimuthOf(r.bearing) == null, modifier = Modifier.weight(1f))
                TextButton(onClick = { rows.removeAt(i) }) { Text("✕") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(r.distance, { r.distance = it }, label = { Text("Feet") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(r.setback, { r.setback = it }, label = { Text("Setback ft") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        OutlinedButton(onClick = { rows.add(CallRow("", "", "")) }) { Text("Add a line") }
        calls?.takeIf { it.size >= 2 }?.let { c ->
            SiteLot.closure(SitePlan(c))?.let { (miss, ratio) ->
                Text(
                    if (ratio == null) "Closes." else "Misses closing by ${SiteLot.fixed2(miss)}' (1 : $ratio). Check the calls against the survey.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ratio == null || ratio >= 10000) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
            }
        }
        Text("The house on the lot (a copy of the roof plan, starting in the middle)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(rotation, { rotation = it }, label = { Text("Turn the house (degrees, clockwise)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(east, { east = it }, label = { Text("Move it east (ft)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(south, { south = it }, label = { Text("Move it south (ft)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(viewTurn, { viewTurn = it }, label = { Text("Turn the whole site plan to fit the sheet (degrees)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("The site plan is its own drawing: drag it anywhere in model space, away from the others.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(street, { street = it }, label = { Text("Street") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { parsed?.let(onApply) }, enabled = parsed != null && parsed != building, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
        if (parsed == null) Text("Check the calls: a bearing like N 45°30'15\" E and a distance in feet on each line.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text("The house's distance to each lot line shows on the site plan, to check against the setbacks.", style = MaterialTheme.typography.bodySmall)
    }
}

/** One survey call being typed: bearing, feet, setback. */
private class CallRow(bearing: String, distance: String, setback: String) {
    var bearing by mutableStateOf(bearing)
    var distance by mutableStateOf(distance)
    var setback by mutableStateOf(setback)
}
