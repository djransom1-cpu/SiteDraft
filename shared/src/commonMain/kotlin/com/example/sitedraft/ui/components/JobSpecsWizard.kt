package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent

/**
 * The job specs (Dan, Nov 22): a setup wizard when a job starts, and the same pages afterwards to
 * change anything. Every page edits the same settings the layers do (Roof framing, Roof plan,
 * Exterior...), so a change made here or there shows in both. Each page's Apply saves it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobSpecsWizard(state: CanvasState, onEvent: (DrawingEvent) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, newJob: Boolean = false) {
    val steps = listOf("Project info", "Foundation and floors", "Structure", "Roof framing", "Roofing", "Exterior finish", "Grade", "Site")
    var step by remember { mutableIntStateOf(0) }
    val apply: (com.example.sitedraft.ui.canvas.BuildingSettings) -> Unit = { onEvent(DrawingEvent.SetBuilding(it)) }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (newJob) "Set up the job" else "Job specs", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Step ${step + 1} of ${steps.size}: ${steps[step]}. Tap Apply on a page to save it; jump to any page with the buttons. " +
                            "Open this again any time from Projects > Job specs.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, label -> FilterChip(selected = step == i, onClick = { step = i }, label = { Text("${i + 1}. $label") }) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (step) {
                    0 -> ProjectInfoCard(info = state.projectInfo, onChange = { onEvent(DrawingEvent.UpdateProjectInfo(it)) })
                    1 -> BuildingInputs(state.building, com.example.sitedraft.ui.canvas.FootingCode.houseWidthFt(state), apply)
                    2 -> StructureInputs(state, apply)
                    3 -> {
                        Text("The roof is built over the first-floor exterior walls and kept up with them.", style = MaterialTheme.typography.bodySmall)
                        RoofInputs(state, apply)
                    }
                    4 -> RoofCoveringInputs(state, apply)
                    5 -> ExteriorFinishInputs(state, apply)
                    6 -> ExteriorInputs(state, apply)
                    7 -> SiteInputs(state, apply)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { step-- }, enabled = step > 0) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (step < steps.size - 1) Button(onClick = { step++ }) { Text("Next: ${steps[step + 1]}") }
                else Button(onClick = onClose) { Text("Done") }
            }
        }
    }
}
