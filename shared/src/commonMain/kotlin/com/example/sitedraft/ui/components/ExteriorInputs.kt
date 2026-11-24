package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.Building
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.ExteriorElevation
import com.example.sitedraft.ui.canvas.FoundationType
import com.example.sitedraft.ui.canvas.RoofPlan
import com.example.sitedraft.ui.canvas.formatInches
import com.example.sitedraft.ui.canvas.parseInches

/**
 * The exterior elevations (Exterior elevation layer). Made by setting the grade exposure for this
 * job (Dan: set per job); after that the four elevations follow the plan and the roof, and the
 * exposure can be changed or the elevations taken away here.
 */
@Composable
fun ExteriorInputs(state: CanvasState, onApply: (BuildingSettings) -> Unit) {
    val building = state.building
    val slab = building.foundation == FoundationType.SLAB
    val current = building.gradeExposureIn
    var exposure by remember(current) { mutableStateOf(formatInches(current ?: 8f)) }
    val parsed = parseInches(exposure)?.takeIf { it >= 0f && it <= 120f }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (RoofPlan.footprint(state) == null) {
            // Set now (the setup wizard asks before anything is drawn); they appear once the walls are.
            Text("The elevations are built from the first-floor exterior (2x6) walls: they appear once those are drawn.", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (current == null) "No exterior elevations yet. How much ${if (slab) "slab" else "foundation"} shows above grade on this job?"
            else "South, north, east and west, kept up with the plan and the roof. Put them on sheets from Sheets.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                exposure, { exposure = it }, label = { Text("Above grade (in)") }, singleLine = true,
                isError = parsed == null, modifier = Modifier.weight(1f)
            )
            Button(onClick = { parsed?.let { onApply(building.copy(gradeExposureIn = it)) } }, enabled = parsed != null && parsed != current) {
                Text(if (current == null) "Make the elevations" else "Apply")
            }
        }
        if (current != null) {
            Text("Grade at ${Building.elevationText(ExteriorElevation.gradeIn(building))}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onApply(building.copy(gradeExposureIn = null)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove the exterior elevations")
            }
        }
    }
}
