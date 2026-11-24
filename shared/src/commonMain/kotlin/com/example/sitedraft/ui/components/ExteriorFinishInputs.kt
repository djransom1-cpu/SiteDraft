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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.ExteriorFinish
import com.example.sitedraft.ui.canvas.WallFinish
import com.example.sitedraft.ui.canvas.formatInches
import com.example.sitedraft.ui.canvas.parseInches

/**
 * The exterior wall finish (Dan, Nov 22): the main finish and its spacing, an optional wainscot
 * (brick or stone, say) and its height, an optional different finish in the gables, and the corner
 * boards. Drawn on the exterior elevations; applied together.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExteriorFinishInputs(state: CanvasState, onApply: (BuildingSettings) -> Unit) {
    val building = state.building
    val cur = building.exterior ?: ExteriorFinish()
    var main by remember(building.exterior) { mutableStateOf(cur.main) }
    var mainIn by remember(building.exterior) { mutableStateOf(formatInches(cur.mainIn ?: cur.main.defaultIn)) }
    var wainscot by remember(building.exterior) { mutableStateOf(cur.wainscot) }
    var wainscotIn by remember(building.exterior) { mutableStateOf(formatInches(cur.wainscotIn ?: (cur.wainscot ?: WallFinish.STONE).defaultIn)) }
    var wainscotH by remember(building.exterior) { mutableStateOf(formatInches(cur.wainscotHeightIn)) }
    var sill by remember(building.exterior) { mutableStateOf(formatInches(cur.wainscotSillIn)) }
    var gable by remember(building.exterior) { mutableStateOf(cur.gable) }
    var gableIn by remember(building.exterior) { mutableStateOf(formatInches(cur.gableIn ?: (cur.gable ?: WallFinish.SHAKE).defaultIn)) }
    var trim by remember(building.exterior) { mutableStateOf(formatInches(cur.cornerTrimIn)) }

    fun sp(text: String) = parseInches(text)?.takeIf { it >= 1f && it <= 48f }
    val parsed: BuildingSettings? = run {
        val m = sp(mainIn) ?: return@run null
        val w = if (wainscot != null) sp(wainscotIn) ?: return@run null else null
        val wh = parseInches(wainscotH)?.takeIf { it > 0f && it <= 240f } ?: return@run null
        val sl = parseInches(sill)?.takeIf { it >= 0f && it <= 12f } ?: return@run null
        val g = if (gable != null) sp(gableIn) ?: return@run null else null
        val t = parseInches(trim)?.takeIf { it >= 0f && it <= 12f } ?: return@run null
        building.copy(exterior = ExteriorFinish(
            main = main, mainIn = m.takeIf { it != main.defaultIn },
            wainscot = wainscot, wainscotIn = w?.takeIf { wainscot != null && it != wainscot!!.defaultIn },
            wainscotHeightIn = wh,
            wainscotSillIn = sl,
            gable = gable, gableIn = g?.takeIf { gable != null && it != gable!!.defaultIn },
            cornerTrimIn = t
        ))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Main finish", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WallFinish.entries.forEach { f ->
                FilterChip(selected = main == f, onClick = { if (f != main) mainIn = formatInches(f.defaultIn); main = f }, label = { Text(f.label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(mainIn, { mainIn = it }, label = { Text("${main.spacingLabel} (in)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(trim, { trim = it }, label = { Text("Corner boards (in, 0 = none)") }, singleLine = true, modifier = Modifier.weight(1f))
        }

        Text("Wainscot (from the top of the foundation up)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = wainscot == null, onClick = { wainscot = null }, label = { Text("None") })
            WallFinish.entries.filter { it != main }.forEach { f ->
                FilterChip(selected = wainscot == f, onClick = { if (f != wainscot) wainscotIn = formatInches(f.defaultIn); wainscot = f }, label = { Text(f.label) })
            }
        }
        wainscot?.let { w ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(wainscotH, { wainscotH = it }, label = { Text("Height (in)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(wainscotIn, { wainscotIn = it }, label = { Text("${w.spacingLabel} (in)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(sill, { sill = it }, label = { Text("Stone sill on top (in tall, 0 = none)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Text("Gables", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = gable == null, onClick = { gable = null }, label = { Text("Same as the main") })
            WallFinish.entries.filter { it != main }.forEach { f ->
                FilterChip(selected = gable == f, onClick = { if (f != gable) gableIn = formatInches(f.defaultIn); gable = f }, label = { Text(f.label) })
            }
        }
        gable?.let { g ->
            OutlinedTextField(gableIn, { gableIn = it }, label = { Text("${g.spacingLabel} (in)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Button(onClick = { parsed?.let(onApply) }, enabled = parsed != null && parsed != building, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
        if (parsed == null) Text("Check the numbers: spacings 1\" to 48\".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (building.exterior != null) {
            TextButton(onClick = { onApply(building.copy(exterior = null)) }) { Text("Clear the exterior finish") }
        }
    }
}
