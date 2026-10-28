package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.Placement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.UnitSystem

/**
 * Floor-plan tool: a building outline by outside size, rooms by inside size, and quick access
 * to doors and windows. Everything is placed with a tap on the canvas, like library symbols.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FloorPlanPanel(
    state: CanvasState,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val unit = if (state.unitSystem == UnitSystem.IMPERIAL) "ft" else "m"
    val metric = state.unitSystem == UnitSystem.METRIC
    val wallChoices = listOf(FloorPlan.EXTERIOR_WALL, FloorPlan.INTERIOR_WALL, "8\" Block Wall", "8\" Concrete Wall")

    var shellWidth by rememberSaveable { mutableStateOf(if (metric) "9" else "30") }
    var shellDepth by rememberSaveable { mutableStateOf(if (metric) "18" else "60") }
    var shellWall by rememberSaveable { mutableStateOf(FloorPlan.EXTERIOR_WALL) }

    var roomName by rememberSaveable { mutableStateOf("Bedroom") }
    var roomWidth by rememberSaveable { mutableStateOf(if (metric) "3.6" else "12") }
    var roomDepth by rememberSaveable { mutableStateOf(if (metric) "3.6" else "12") }
    var roomWall by rememberSaveable { mutableStateOf(FloorPlan.INTERIOR_WALL) }

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Floor Plan", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            // --- Building outline ---
            Text("Building outline", style = MaterialTheme.typography.titleMedium)
            Text(
                "Outside dimensions. Then tap the canvas where the top-left corner goes.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Width ($unit)", shellWidth, Modifier.weight(1f)) { shellWidth = it }
                NumberField("Depth ($unit)", shellDepth, Modifier.weight(1f)) { shellDepth = it }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                wallChoices.forEach { w ->
                    FilterChip(selected = shellWall == w, onClick = { shellWall = w }, label = { Text(w) })
                }
            }
            Spacer(Modifier.height(4.dp))
            val sw = shellWidth.toFloatOrNull()
            val sd = shellDepth.toFloatOrNull()
            Button(
                onClick = {
                    if (sw != null && sd != null) onEvent(
                        DrawingEvent.StartPlacement(
                            Placement.Shell(sw, sd, shellWall, FloorPlan.shellLabel(sw, sd, state.unitSystem))
                        )
                    )
                },
                enabled = sw != null && sd != null && sw > 0f && sd > 0f,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Place outline") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // --- Rooms ---
            Text("Rooms", style = MaterialTheme.typography.titleMedium)
            Text(
                "Inside dimensions. Tap the room's top-left inside corner; a wall that is already there is not doubled.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FloorPlan.roomPresets.forEach { (name, size) ->
                    FilterChip(
                        selected = roomName == name,
                        onClick = {
                            roomName = name
                            roomWidth = fmt(if (metric) size.first * 0.3048f else size.first)
                            roomDepth = fmt(if (metric) size.second * 0.3048f else size.second)
                        },
                        label = { Text(name) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = roomName,
                onValueChange = { roomName = it },
                label = { Text("Room name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Width ($unit)", roomWidth, Modifier.weight(1f)) { roomWidth = it }
                NumberField("Depth ($unit)", roomDepth, Modifier.weight(1f)) { roomDepth = it }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(FloorPlan.INTERIOR_WALL, FloorPlan.EXTERIOR_WALL).forEach { w ->
                    FilterChip(selected = roomWall == w, onClick = { roomWall = w }, label = { Text(w) })
                }
            }
            Spacer(Modifier.height(4.dp))
            val rw = roomWidth.toFloatOrNull()
            val rd = roomDepth.toFloatOrNull()
            Button(
                onClick = {
                    if (rw != null && rd != null) onEvent(
                        DrawingEvent.StartPlacement(
                            Placement.Room(roomName.trim(), rw, rd, roomWall, FloorPlan.roomPlacementLabel(roomName.trim(), rw, rd, state.unitSystem))
                        )
                    )
                },
                enabled = rw != null && rd != null && rw > 0f && rd > 0f,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Place room") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // --- Doors & windows ---
            Text("Doors & windows", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick one, then tap the wall. The hinge is the bottom-left corner of the swing; rotate it afterwards if needed.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlanSymbols.doorsWindows.forEach { name ->
                    FilterChip(
                        selected = (state.pendingPlacement as? Placement.Symbol)?.name == name,
                        onClick = { onEvent(DrawingEvent.SelectSymbol(name, PlanSymbols.DOORS_WINDOWS)) },
                        label = { Text(name) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Closets, tubs, cabinets and the rest are in the Symbol Library; they place the same way.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}
