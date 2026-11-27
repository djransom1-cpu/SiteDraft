package com.example.sitedraft.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.ElectricalCode
import com.example.sitedraft.ui.canvas.RoomElectrical

/**
 * One room's electrical card (Dan, Nov 27): what the room is (it drives the code rules), its light, which door gets
 * the switch and 3-ways, plugs beyond code, and Redo. Every change lays the room out again; anything moved or placed
 * by hand stays.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomElectricalCard(at: Offset, state: CanvasState, onEvent: (DrawingEvent) -> Unit, modifier: Modifier = Modifier, onDrag: (Offset) -> Unit = {}) {
    val drag by rememberUpdatedState(onDrag)
    val room = ElectricalCode.roomAt(at, state) ?: return
    val opts = room.opts ?: RoomElectrical(x = room.rect.center.x, y = room.rect.center.y)
    val doors = ElectricalCode.doorsOf(room.rect, state)
    fun set(o: RoomElectrical) = onEvent(DrawingEvent.SetRoomElectrical(room.rect.center.x, room.rect.center.y, o))
    Surface(modifier = modifier.width(300.dp).heightIn(max = 560.dp), shape = MaterialTheme.shapes.large, shadowElevation = 10.dp,
        tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); drag(amount) } }) {
                Icon(Icons.Default.DragIndicator, contentDescription = "Drag to move the card", modifier = Modifier.padding(end = 4.dp))
                Column(Modifier.weight(1f)) {
                    Text("Electrical: ${room.name}", style = MaterialTheme.typography.titleMedium)
                    Text("To ${state.building.codeEdition.short}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { onEvent(DrawingEvent.CloseRoomElectrical) }) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            HorizontalDivider()

            Text("Room type", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in ElectricalCode.RoomType.entries) FilterChip(selected = room.type == t,
                    onClick = { set(opts.copy(type = t.name, light = "")) }, label = { Text(t.label) })
            }

            Text("Light", style = MaterialTheme.typography.labelLarge)
            val light = ElectricalCode.lightOf(room, state)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in ElectricalCode.LightKind.entries) FilterChip(selected = light == k,
                    onClick = { set(opts.copy(light = k.name)) }, label = { Text(k.label) })
            }
            if (light == ElectricalCode.LightKind.CANS || light == ElectricalCode.LightKind.PENDANTS) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cans: ${if (opts.cans > 0) opts.cans.toString() else "spaced to fit"}", Modifier.weight(1f))
                    OutlinedButton(onClick = { set(opts.copy(cans = (opts.cans.takeIf { it > 0 } ?: 4) - 1).let { if (it.cans < 1) it.copy(cans = 1) else it }) }) { Text("-") }
                    OutlinedButton(onClick = { set(opts.copy(cans = (opts.cans.takeIf { it > 0 } ?: 4) + 1)) }) { Text("+") }
                    if (opts.cans > 0) OutlinedButton(onClick = { set(opts.copy(cans = 0)) }) { Text("Auto") }
                }
            }

            Text("Switch", style = MaterialTheme.typography.labelLarge)
            if (doors.isEmpty()) Text("No door into this room: the switch goes at the start of its longest wall.", style = MaterialTheme.typography.bodySmall)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = opts.switchDoor < 0, onClick = { set(opts.copy(switchDoor = -1)) }, label = { Text("Auto") })
                doors.forEachIndexed { i, d ->
                    val label = when {
                        d.exterior -> "Outside door"
                        d.otherSide != null -> "Door to ${d.otherSide.label.lowercase()}"
                        else -> "Door ${i + 1}"
                    }
                    FilterChip(selected = opts.switchDoor == i, onClick = { set(opts.copy(switchDoor = i)) }, label = { Text(label) })
                }
            }
            if (doors.size >= 2) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("3-way switches at two doors", Modifier.weight(1f))
                val on = when (opts.threeWay) { 1 -> true; 0 -> false; else -> null }
                FilterChip(selected = on == null, onClick = { set(opts.copy(threeWay = -1)) }, label = { Text("Auto") })
                Switch(checked = on ?: false, onCheckedChange = { set(opts.copy(threeWay = if (it) 1 else 0)) }, modifier = Modifier.padding(start = 6.dp))
            }

            Text("Extra plugs", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Beyond code: ${opts.extraPlugs}", Modifier.weight(1f))
                OutlinedButton(onClick = { set(opts.copy(extraPlugs = (opts.extraPlugs - 1).coerceAtLeast(0))) }) { Text("-") }
                OutlinedButton(onClick = { set(opts.copy(extraPlugs = opts.extraPlugs + 1)) }) { Text("+") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Floor plug", Modifier.weight(1f))
                Switch(checked = opts.floorPlug, onCheckedChange = { set(opts.copy(floorPlug = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TV plug (60\" up)", Modifier.weight(1f))
                Switch(checked = opts.tvPlug, onCheckedChange = { set(opts.copy(tvPlug = it)) })
            }
            HorizontalDivider()
            OutlinedButton(onClick = { onEvent(DrawingEvent.ElectricalToCode(room.rect.center.x, room.rect.center.y)) }) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("  Redo this room")
            }
            Text("Redo clears what the button placed here and lays it out again. Anything you moved or placed yourself stays.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
