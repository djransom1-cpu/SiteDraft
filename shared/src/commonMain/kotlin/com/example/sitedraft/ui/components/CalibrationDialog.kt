package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.PendingCalibration
import com.example.sitedraft.ui.canvas.UnitSystem
import com.example.sitedraft.ui.canvas.formatLength
import com.example.sitedraft.ui.canvas.parseFeet

/**
 * Asks for the real length between the two points picked on a PDF plan. The length is in the
 * project's units: feet and inches when imperial (12'6", 12-6, 12.5, 150"), meters when metric.
 */
@Composable
fun CalibrationLengthDialog(
    pending: PendingCalibration,
    state: CanvasState,
    onApply: (Float) -> Unit,
    onPickAgain: () -> Unit,
    onCancel: () -> Unit
) {
    val imperial = state.unitSystem == UnitSystem.IMPERIAL
    var text by remember(pending) { mutableStateOf("") }
    val parsed = if (imperial) parseFeet(text) else text.trim().toFloatOrNull()
    val valid = parsed != null && parsed > 0f
    val currently = formatLength(pending.pixelLength / state.scale.ratio, state.unitSystem)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Calibrate plan scale") },
        text = {
            Column {
                Text("What is the real length between the two points?")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "At the drawing's scale it measures $currently now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(if (imperial) "Real length (e.g. 12'6\")" else "Real length (m)") },
                    isError = text.isNotBlank() && !valid,
                    supportingText = {
                        if (text.isNotBlank() && !valid) Text("Not a length")
                        else if (valid) Text("= ${formatLength(parsed!!, state.unitSystem)}")
                    }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { parsed?.let(onApply) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onPickAgain) { Text("Pick again") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
