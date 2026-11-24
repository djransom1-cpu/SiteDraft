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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.PostType
import com.example.sitedraft.ui.canvas.StructuralDesign

/**
 * The structure's inputs (Job specs > Structure, Dan Nov 22): floor loads (his usual 40/20 and
 * the common others), the soil's allowable bearing (from the soils report when there is one),
 * the post type for this job, and his layout limits. The beam, posts and pads they give are listed
 * under, as the calc table on the S sheets shows them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StructureInputs(state: CanvasState, onApply: (BuildingSettings) -> Unit) {
    val building = state.building
    val sp = building.structure
    fun n(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    var live by remember(sp) { mutableStateOf(n(sp.liveLoadPsf)) }
    var dead by remember(sp) { mutableStateOf(n(sp.deadLoadPsf)) }
    var soil by remember(sp) { mutableStateOf(n(sp.soilBearingPsf)) }
    var snow by remember(sp) { mutableStateOf(n(sp.roofSnowPsf)) }
    var post by remember(sp) { mutableStateOf(sp.post) }
    var joist by remember(sp) { mutableStateOf(n(sp.maxJoistSpanFt)) }
    var posts by remember(sp) { mutableStateOf(n(sp.postSpacingFt)) }
    var standard by remember(sp) { mutableStateOf(sp.standardPosts) }
    var w8 by remember(sp) { mutableStateOf(StructuralDesign.w8Only(building)) }
    var pick by remember(sp) { mutableStateOf(sp.beamShape ?: "") }
    var defl by remember(sp) { mutableStateOf(n(sp.maxTotalDeflectionIn)) }
    var codeOnly by remember(sp) { mutableStateOf(sp.maxTotalDeflectionIn <= 0f) }
    val parsed: BuildingSettings? = run {
        val l = live.trim().toFloatOrNull()?.takeIf { it in 10f..200f } ?: return@run null
        val d = dead.trim().toFloatOrNull()?.takeIf { it in 5f..200f } ?: return@run null
        val so = soil.trim().toFloatOrNull()?.takeIf { it in 500f..12000f } ?: return@run null
        val sn = snow.trim().toFloatOrNull()?.takeIf { it in 0f..200f } ?: return@run null
        val j = joist.trim().toFloatOrNull()?.takeIf { it in 6f..30f } ?: return@run null
        val p = posts.trim().toFloatOrNull()?.takeIf { it in 4f..30f } ?: return@run null
        val df = if (codeOnly) 0f else defl.trim().toFloatOrNull()?.takeIf { it > 0f && it <= 2f } ?: return@run null
        val bp = pick.trim().uppercase().replace("X", "x").takeIf { it.isNotEmpty() }
        if (bp != null && com.example.sitedraft.ui.canvas.SteelEngine.shape(bp)?.type != "W") return@run null
        building.copy(structure = sp.copy(liveLoadPsf = l, deadLoadPsf = d, soilBearingPsf = so, roofSnowPsf = sn, post = post, maxJoistSpanFt = j, postSpacingFt = p,
            standardPosts = standard, w8Only = w8, maxTotalDeflectionIn = df, beamShape = bp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Floor loads (psf, per floor)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(40 to 20, 40 to 10, 40 to 15, 50 to 20).forEach { (l, d) ->
                FilterChip(selected = live == "$l" && dead == "$d", onClick = { live = "$l"; dead = "$d" },
                    label = { Text("$l live / $d dead" + if (l == 40 && d == 20) " (usual)" else "") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(live, { live = it }, label = { Text("Live (psf)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(dead, { dead = it }, label = { Text("Dead (psf)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(soil, { soil = it }, label = { Text("Soil allowable bearing (psf; from the soils report, else 2,000)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(snow, { snow = it }, label = { Text("Roof snow load (psf; sizes the footings from the code table)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Posts under the beam", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PostType.entries.forEach { t -> FilterChip(selected = post == t, onClick = { post = t }, label = { Text(t.label) }) }
        }
        OutlinedTextField(joist, { joist = it }, label = { Text("Joist span limit (ft)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Column spacing", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = standard, onClick = { standard = true }, label = { Text("Standard 8'") })
            FilterChip(selected = !standard, onClick = { standard = false }, label = { Text("Set the spacing") })
        }
        if (!standard) OutlinedTextField(posts, { posts = it }, label = { Text("Columns no more than (ft apart)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Beam", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = w8, onClick = { w8 = true }, label = { Text("W8 only") })
            FilterChip(selected = !w8, onClick = { w8 = false }, label = { Text("Best option (lightest, any depth)") })
        }
        OutlinedTextField(pick, { pick = it }, label = { Text("Or use this beam (W8x21; blank = the engine picks)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Your beam sets the column spacing: as far apart as it carries, within the spacing above. Moving or adding posts on the plan sets the spans.") })
        Text("Basement ${com.example.sitedraft.ui.canvas.Building.elevationText(StructuralDesign.basementHeightIn(building))} floor to joists" +
            (if (sp.w8Only == null) ": W8 only under 8'-0\" until you pick." else "."), style = MaterialTheme.typography.bodySmall)
        Text("Beam deflection", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = codeOnly, onClick = { codeOnly = true }, label = { Text("Code limits (L/360 live, L/240 total)") })
            FilterChip(selected = !codeOnly, onClick = { codeOnly = false; if (defl == "0") defl = "0.25" }, label = { Text("My limit on total deflection") })
        }
        if (!codeOnly) OutlinedTextField(defl, { defl = it }, label = { Text("Total deflection no more than (in)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { parsed?.let(onApply) }, enabled = parsed != null && parsed != building, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
        if (parsed == null) Text("Check the numbers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text("What it sizes now", style = MaterialTheme.typography.labelLarge)
        StructuralDesign.rows(state).forEach { (label, value) -> Text("$label: $value", style = MaterialTheme.typography.bodySmall) }
    }
}
