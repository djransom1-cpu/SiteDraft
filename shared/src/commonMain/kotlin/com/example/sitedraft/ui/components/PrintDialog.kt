package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.example.sitedraft.platform.LocalPlatform
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.PageOrientation
import com.example.sitedraft.ui.canvas.PaperSize
import com.example.sitedraft.ui.canvas.PrintPage
import com.example.sitedraft.ui.canvas.PrintScale
import com.example.sitedraft.ui.canvas.PrintSettings
import com.example.sitedraft.ui.canvas.PrintSubject
import com.example.sitedraft.ui.canvas.UnitSystem

/** Which toolbar button opened the dialog; it gets the filled button, the other stays a text button. */
enum class PrintAction { PRINT, EXPORT_PDF }

private const val PREF_PAPER = "print.paper"
private const val PREF_ORIENTATION = "print.orientation"
private const val PREF_SCALE = "print.scale"

/**
 * What to print, on what paper, at what scale. The paper, orientation and scale are remembered
 * on the device: a contractor who always prints 11x17 at 1/4" should not have to pick it every
 * time. The size line tells the user whether the subject fits the sheet at that scale before
 * anything is sent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintDialog(
    state: CanvasState,
    primary: PrintAction,
    onPrint: (PrintSettings) -> Unit,
    onExportPdf: (PrintSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val prefs = LocalPlatform.current.prefs
    val hasElevation = PrintPage.selectedElevation(state) != null
    var settings by remember {
        mutableStateOf(
            PrintSettings(
                subject = if (hasElevation) PrintSubject.SELECTED_ELEVATION else PrintSubject.WHOLE_DRAWING,
                paper = prefs.getString(PREF_PAPER)?.let { v -> PaperSize.entries.firstOrNull { it.name == v } } ?: PaperSize.LETTER,
                orientation = prefs.getString(PREF_ORIENTATION)?.let { v -> PageOrientation.entries.firstOrNull { it.name == v } } ?: PageOrientation.LANDSCAPE,
                scale = prefs.getString(PREF_SCALE)?.let { v -> PrintScale.entries.firstOrNull { it.name == v } } ?: PrintScale.FIT
            )
        )
    }
    val metric = state.unitSystem == UnitSystem.METRIC
    val layout = remember(settings, state) {
        PrintPage.layout(settings, PrintPage.subjectBounds(state, settings.subject), PrintPage.worldPxPerInch(state), metric)
    }
    val empty = PrintPage.subjectBounds(state, settings.subject) == null

    fun commit(action: (PrintSettings) -> Unit) {
        prefs.putString(PREF_PAPER, settings.paper.name)
        prefs.putString(PREF_ORIENTATION, settings.orientation.name)
        prefs.putString(PREF_SCALE, settings.scale.name)
        action(settings)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Match the Settings/Properties panels: the default light dialog surface renders the
        // Blueprint theme's white text nearly invisible.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(if (primary == PrintAction.PRINT) "Print" else "Export PDF") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (primary == PrintAction.PRINT) {
                    TextButton(onClick = { commit(onExportPdf) }) { Text("Export PDF") }
                    Button(onClick = { commit(onPrint) }) { Text("Print") }
                } else {
                    TextButton(onClick = { commit(onPrint) }) { Text("Print") }
                    Button(onClick = { commit(onExportPdf) }) { Text("Export PDF") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("What to print", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrintSubject.entries.forEach { subject ->
                        FilterChip(
                            selected = settings.subject == subject,
                            enabled = subject != PrintSubject.SELECTED_ELEVATION || hasElevation,
                            onClick = { settings = settings.copy(subject = subject) },
                            label = { Text(subject.label) }
                        )
                    }
                }

                Text("Paper", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaperSize.entries.forEach { paper ->
                        FilterChip(
                            selected = settings.paper == paper,
                            onClick = { settings = settings.copy(paper = paper) },
                            label = { Text(paper.label) }
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PageOrientation.entries.forEach { orientation ->
                        FilterChip(
                            selected = settings.orientation == orientation,
                            onClick = { settings = settings.copy(orientation = orientation) },
                            label = { Text(orientation.label) }
                        )
                    }
                }

                Text("Scale", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrintScale.entries.forEach { scale ->
                        FilterChip(
                            selected = settings.scale == scale,
                            onClick = { settings = settings.copy(scale = scale) },
                            label = { Text(if (metric && scale.denominator != null) "1:${scale.denominator}" else scale.label) }
                        )
                    }
                }

                OutlinedTextField(
                    value = settings.sheetTitle,
                    onValueChange = { settings = settings.copy(sheetTitle = it) },
                    label = { Text("Sheet title") },
                    placeholder = { Text(PrintPage.defaultSheetTitle(settings.subject)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val paperText = "${PrintPage.inchesText(layout.paperWidthIn)} × ${PrintPage.inchesText(layout.paperHeightIn)}"
                when {
                    empty -> Text("Nothing to print yet.", style = MaterialTheme.typography.bodySmall)
                    settings.scale == PrintScale.FIT -> Text(
                        "Fills the $paperText sheet, not to scale.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    layout.fits -> Text(
                        "Drawing is ${PrintPage.inchesText(layout.drawingWidthIn)} × ${PrintPage.inchesText(layout.drawingHeightIn)} on the $paperText sheet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    else -> Text(
                        "${PrintPage.inchesText(layout.drawingWidthIn)} × ${PrintPage.inchesText(layout.drawingHeightIn)} at this scale: too big for the $paperText sheet, edges will be cut off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}
