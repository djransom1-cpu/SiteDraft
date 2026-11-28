package com.example.sitedraft.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.PrintSettings
import com.example.sitedraft.ui.canvas.PrintSubject

private const val PREF_SEPARATE = "sheets.exportSeparate"

/**
 * Paper space (Dan, Nov 20/22): the sheets on their own screen, not inside Print. The sheet list
 * down the left (a new sheet is made by picking its first view), the sheet being worked on in the
 * middle with its viewports, and Print / Export PDF for this sheet or the whole set along the top.
 * Model is one tap away: drawing changes show on the sheets as soon as they're made.
 */
@Composable
fun SheetsScreen(
    state: CanvasState,
    onEvent: (DrawingEvent) -> Unit,
    density: Float,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The sheet to open on (a tab tapped on the drawing); null = the first, or a new one when there are none. */
    initialSelected: String? = null
) {
    var selected by remember(initialSelected) { mutableStateOf(initialSelected ?: state.sheets.firstOrNull()?.id ?: NEW_SHEET) }
    val prefs = com.example.sitedraft.platform.LocalPlatform.current.prefs
    var separate by remember { mutableStateOf(prefs.getString(PREF_SEPARATE) == "true") }
    val current = state.sheets.firstOrNull { it.id == selected }
        ?: if (selected == NEW_SHEET) null else state.sheets.firstOrNull()
    val printable = current != null && current.views.isNotEmpty()
    val setCount = state.sheets.count { it.views.isNotEmpty() }
    fun settings() = PrintSettings(subject = PrintSubject.SHEET, sheetId = current?.id)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sheets", style = MaterialTheme.typography.headlineSmall)
                FilterChip(selected = false, onClick = onClose, label = { Text("Model") })
                FilterChip(selected = true, onClick = {}, label = { Text("Paper") })
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { onEvent(DrawingEvent.ExportPdf(settings(), density)) }, enabled = printable) { Text("Export PDF") }
                Button(onClick = { onEvent(DrawingEvent.Print(settings(), density)) }, enabled = printable) { Text("Print sheet") }
                // The whole set: one PDF, or a PDF per sheet (remembered on this device).
                FilterChip(selected = !separate, onClick = { separate = false; prefs.putString(PREF_SEPARATE, "false") }, label = { Text("One PDF") })
                FilterChip(selected = separate, onClick = { separate = true; prefs.putString(PREF_SEPARATE, "true") }, label = { Text("A PDF per sheet") })
                OutlinedButton(onClick = { onEvent(DrawingEvent.ExportSheetSet(density, separate)) }, enabled = setCount > 0) {
                    Text("Export set ($setCount)")
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Back to the drawing") }
            }
            // The sheet fills the screen; tools on a rail down the left open as cards (Dan, Nov 28).
            SheetsWorkspace(state = state, current = current, onSelect = { selected = it }, onEvent = onEvent,
                modifier = Modifier.fillMaxWidth().weight(1f))
            // Model and sheet tabs, as on the drawing: Model goes back to it.
            SpaceTabs(
                sheets = state.sheets,
                current = if (current == null) NEW_SHEET else current.id,
                onModel = onClose,
                onSheet = { selected = it },
                onNewSheet = { selected = NEW_SHEET }
            )
        }
    }
}
