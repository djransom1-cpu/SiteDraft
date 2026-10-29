package com.example.sitedraft.ui.components

import com.example.sitedraft.util.format
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
import androidx.compose.runtime.remember
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.inchesToPx
import com.example.sitedraft.ui.canvas.formatFeetInches
import com.example.sitedraft.ui.canvas.parseFeet
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
    var roomOutside by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        // Explicit: a translucent colour matches no theme colour, so contentColorFor() fell back to
        // black and the panel's titles were black on blue.
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                NumberField("Width ($unit)", shellWidth, Modifier.weight(1f), feetInches = !metric) { shellWidth = it }
                NumberField("Depth ($unit)", shellDepth, Modifier.weight(1f), feetInches = !metric) { shellDepth = it }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                wallChoices.forEach { w ->
                    FilterChip(selected = shellWall == w, onClick = { shellWall = w }, label = { Text(w) })
                }
            }
            Spacer(Modifier.height(4.dp))
            // Imperial sizes take feet and inches too: 30'-6", 30' 6 1/2", 30-6 or plain 30.5.
            val sw = if (metric) shellWidth.toFloatOrNull() else parseFeet(shellWidth)
            val sd = if (metric) shellDepth.toFloatOrNull() else parseFeet(shellDepth)
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
                "Inside or outside dimensions. Tap the room's top-left corner (inside or outside to match); a wall that is already there is not doubled.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FloorPlan.roomPresets.forEach { (name, size) ->
                    FilterChip(
                        selected = roomName == name,
                        onClick = {
                            roomName = name
                            roomWidth = if (metric) fmt(size.first * 0.3048f) else formatFeetInches(size.first)
                            roomDepth = if (metric) fmt(size.second * 0.3048f) else formatFeetInches(size.second)
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
            // Inside (clear room size, tap the inside corner) or outside (wall-to-wall, tap the
            // outside corner) - whichever the measurement at hand is.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !roomOutside, onClick = { roomOutside = false }, label = { Text("Inside dims") })
                FilterChip(selected = roomOutside, onClick = { roomOutside = true }, label = { Text("Outside dims") })
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(FloorPlan.INTERIOR_WALL, FloorPlan.EXTERIOR_WALL).forEach { w ->
                    FilterChip(selected = roomWall == w, onClick = { roomWall = w }, label = { Text(w) })
                }
            }
            Spacer(Modifier.height(4.dp))
            val rw = if (metric) roomWidth.toFloatOrNull() else parseFeet(roomWidth)
            val rd = if (metric) roomDepth.toFloatOrNull() else parseFeet(roomDepth)
            // An outside-dimensioned room must still have some inside once two walls are taken off.
            val minOutside = 2f * (PlanSymbols.footprintInches[roomWall]?.second ?: 4.5f) / (if (metric) 39.37f else 12f)
            val sizeOk = rw != null && rd != null && rw > 0f && rd > 0f && (!roomOutside || (rw > minOutside && rd > minOutside))
            Button(
                onClick = {
                    if (rw != null && rd != null) onEvent(
                        DrawingEvent.StartPlacement(
                            Placement.Room(
                                roomName.trim(), rw, rd, roomWall,
                                FloorPlan.roomPlacementLabel(roomName.trim(), rw, rd, state.unitSystem, roomOutside),
                                outside = roomOutside
                            )
                        )
                    )
                },
                enabled = sizeOk,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Place room") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // --- Elevation of a wall ---
            Text("Elevation", style = MaterialTheme.typography.titleMedium)
            val selectedWall = state.elements.find { it.id == state.selectedElementId } as? DrawingElement.Symbol
            if (selectedWall == null || selectedWall.category != PlanSymbols.WALLS) {
                Text(
                    "Select a wall on the plan with the Select tool. Everything against that wall (cabinets, appliances, fixtures, doors, windows) is drawn as an elevation at the plan's scale.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                ElevationControls(selectedWall, state, onEvent)
            }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ElevationControls(wall: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    val metric = state.unitSystem == UnitSystem.METRIC
    val sides = Elevation.sidesFor(wall)
    var side by remember(wall.id) { mutableStateOf(Elevation.defaultSide(wall, state)) }
    if (side !in sides) side = sides.first()
    var wallHeight by rememberSaveable { mutableStateOf(if (metric) "2.4" else "8") }
    var counter by rememberSaveable { mutableStateOf("36") }
    var upperBottom by rememberSaveable { mutableStateOf("54") }
    var upperHeight by rememberSaveable { mutableStateOf("30") }
    var sill by rememberSaveable { mutableStateOf("36") }
    var windowHeight by rememberSaveable { mutableStateOf("48") }
    var doorHeight by rememberSaveable { mutableStateOf("80") }

    val lengthIn = Elevation.lengthPx(wall) / state.inchesToPx(1f)
    val items = Elevation.itemsOnWall(wall, side, state)
    val lengthLabel = if (metric) "%.2f m".format(lengthIn * 0.0254f) else formatFeetInches(lengthIn / 12f)
    Text(
        "Wall $lengthLabel long, ${items.size} item${if (items.size == 1) "" else "s"} on this side.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sides.forEach { s ->
            FilterChip(selected = side == s, onClick = { side = s }, label = { Text("${s.label} (${s.looking})") })
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Wall height (${if (metric) "m" else "ft"})", wallHeight, Modifier.weight(1f)) { wallHeight = it }
        NumberField("Counter (in)", counter, Modifier.weight(1f)) { counter = it }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Uppers from (in)", upperBottom, Modifier.weight(1f)) { upperBottom = it }
        NumberField("Uppers high (in)", upperHeight, Modifier.weight(1f)) { upperHeight = it }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Window sill (in)", sill, Modifier.weight(1f)) { sill = it }
        NumberField("Window high (in)", windowHeight, Modifier.weight(1f)) { windowHeight = it }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Door height (in)", doorHeight, Modifier.weight(1f)) { doorHeight = it }
        Spacer(Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    val wh = wallHeight.toFloatOrNull()?.let { if (metric) it / 0.0254f else it * 12f }
    val values = listOf(counter, upperBottom, upperHeight, sill, windowHeight, doorHeight).map { it.toFloatOrNull() }
    val ready = wh != null && wh > 0f && values.all { it != null && it >= 0f }
    Button(
        onClick = {
            if (ready) {
                val spec = ElevationSpec(
                    wallId = wall.id, side = side, wallHeightIn = wh!!,
                    counterHeightIn = values[0]!!, upperBottomIn = values[1]!!, upperHeightIn = values[2]!!,
                    sillIn = values[3]!!, windowHeightIn = values[4]!!, doorHeightIn = values[5]!!
                )
                onEvent(DrawingEvent.StartPlacement(Placement.Elevation(spec, "elevation (${side.looking})")))
            }
        },
        enabled = ready,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Place elevation") }
    Text(
        "Then tap an empty spot on the canvas: that is the bottom-left corner of the floor line. The elevation is placed selected, so it can be moved as a group.",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

/** [feetInches]: the field takes 12'-6" / 12' 6 1/2" as well as 12.5, so it needs the full keyboard. */
@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, feetInches: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = value.isNotBlank() && (if (feetInches) parseFeet(value) else value.toFloatOrNull()) == null,
        keyboardOptions = KeyboardOptions(keyboardType = if (feetInches) KeyboardType.Text else KeyboardType.Decimal),
        modifier = modifier
    )
}
