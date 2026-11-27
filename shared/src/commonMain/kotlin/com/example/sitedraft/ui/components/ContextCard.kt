package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.canvas.BarnDoors
import com.example.sitedraft.ui.canvas.CabinetList
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.Wainscot
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingTool
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Trim
import com.example.sitedraft.ui.canvas.formatInches
import com.example.sitedraft.ui.canvas.inchesToPx
import com.example.sitedraft.ui.canvas.parseInches
import kotlin.math.abs

/**
 * The context card (Dan, Nov 27: "tap a door, a little window pops up with just the items for the door, not all the other
 * clutter"). Every card has the same top row: Move, Rotate, Delete, All settings (the full properties panel). Below it,
 * only what that kind of item needs. Doors and windows first; other items show the top row and their name for now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextCard(
    element: DrawingElement.Symbol,
    state: CanvasState,
    onEvent: (DrawingEvent) -> Unit,
    onAllSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Dragging the title moves the card (Dan, Nov 27: it covered the item and the action bar). */
    onDrag: (Offset) -> Unit = {},
) {
    val drag by androidx.compose.runtime.rememberUpdatedState(onDrag)
    Surface(
        modifier = modifier.width(300.dp).heightIn(max = 520.dp),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 10.dp,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); drag(amount) } }) {
                Icon(Icons.Default.DragIndicator, contentDescription = "Drag to move the card", modifier = Modifier.padding(end = 4.dp))
                Column(Modifier.weight(1f)) {
                    Text(title(element, state), style = MaterialTheme.typography.titleMedium)
                    subtitle(element, state)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                IconButton(onClick = { onEvent(DrawingEvent.DeselectElement) }) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            // The same on every card: the action bar's buttons, which the card replaces while it's open.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(modifier = Modifier.size(38.dp), onClick = { onEvent(DrawingEvent.SelectTool(DrawingTool.MOVE)) }) { Icon(Icons.Default.OpenWith, contentDescription = "Move") }
                IconButton(modifier = Modifier.size(38.dp), onClick = { onEvent(DrawingEvent.SelectTool(DrawingTool.MOVE_PRECISE)) }) { Icon(Icons.Default.AdsClick, contentDescription = "Point-to-point move") }
                IconButton(modifier = Modifier.size(38.dp), onClick = { onEvent(DrawingEvent.DuplicateSelectedElement) }) { Icon(Icons.Default.CopyAll, contentDescription = "Copy") }
                IconButton(modifier = Modifier.size(38.dp), onClick = { onEvent(DrawingEvent.RotateSelection(-90f)) }) { Icon(Icons.Default.RotateLeft, contentDescription = "Rotate left 90") }
                IconButton(modifier = Modifier.size(38.dp), onClick = { onEvent(DrawingEvent.RotateSelection(90f)) }) { Icon(Icons.Default.RotateRight, contentDescription = "Rotate right 90") }
                IconButton(modifier = Modifier.size(38.dp), onClick = { onEvent(DrawingEvent.ExplodeSelectedElement) }) { Icon(Icons.Default.CallSplit, contentDescription = "Explode") }
                IconButton(modifier = Modifier.size(38.dp), onClick = { onEvent(DrawingEvent.DeleteSelectedElement) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
            when {
                element.category == PlanSymbols.DOORS_WINDOWS && PlanSymbols.isWindow(element.symbolName) -> WindowSection(element, state, onEvent)
                element.category == PlanSymbols.DOORS_WINDOWS -> DoorSection(element, state, onEvent)
                element.category == PlanSymbols.WALLS && element.rotation % 90f == 0f -> WallSection(element, state, onEvent)
                element.category == PlanSymbols.CABINETS -> CabinetSection(element, state, onEvent)
                com.example.sitedraft.ui.canvas.Mep.isMep(element) -> MepSection(element, state, onEvent)
                else -> Text("More of its settings are in All settings.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAllSettings, modifier = Modifier.align(Alignment.End)) { Text("All settings") }
        }
    }
}

private fun inchOf(state: CanvasState) = state.inchesToPx(1f)

private fun title(e: DrawingElement.Symbol, state: CanvasState): String {
    PlanSymbols.cabinetName(e, state)?.let { return it }
    val n = e.symbolName
    return when {
        PlanSymbols.isWindow(n) -> "Window"
        PlanSymbols.isBiParting(n) -> "Barn door, bi-parting"
        PlanSymbols.isBarnDoor(n) -> "Barn door"
        PlanSymbols.isBifold(n) -> "Bifold door"
        PlanSymbols.isDoubleDoor(n) -> "Double door"
        PlanSymbols.isPocketDoor(n) -> "Pocket door"
        PlanSymbols.isSlidingDoor(n) -> "Sliding door"
        PlanSymbols.isOpening(n) -> "Cased opening"
        PlanSymbols.isDoor(n) -> "Door"
        else -> n
    }
}

private fun subtitle(e: DrawingElement.Symbol, state: CanvasState): String? {
    if (e.category != PlanSymbols.DOORS_WINDOWS) return null
    val w = e.size.width / inchOf(state)
    val h = e.properties[PlanSymbols.PROP_HEIGHT_IN] ?: if (PlanSymbols.isWindow(e.symbolName)) 48f else state.building.doorHeadIn
    return "${feetInches(w)} wide x ${feetInches(h)} high"
}

private fun feetInches(inches: Float): String {
    val r = kotlin.math.round(inches * 16f) / 16f
    val ft = (r / 12f).toInt()
    val inch = r - ft * 12f
    return "$ft'-${formatInches(inch)}\""
}

/** The door kinds, in the picker's groups (PlanSymbols.doorWindowGroups), without windows. */
private val doorGroups get() = PlanSymbols.doorWindowGroups.filter { g -> g.sizes.none { PlanSymbols.isWindow(it) } }

private fun groupOf(name: String) = PlanSymbols.doorWindowGroups.firstOrNull { name in it.sizes }

/** Kind chips, then that kind's stock sizes and an Other width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindAndSize(e: DrawingElement.Symbol, groups: List<PlanSymbols.SizedGroup>, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    val wIn = e.size.width / inchOf(state)
    val mine = groupOf(e.symbolName)
    if (groups.size > 1) {
        Text("Kind", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (g in groups) FilterChip(selected = g == mine, onClick = {
                if (g == mine) return@FilterChip
                // The stock size nearest its width now; its width stays if that kind comes within 6" of it.
                val nearest = g.sizes.minByOrNull { abs((PlanSymbols.footprintInches[it]?.first ?: 0f) - wIn) } ?: return@FilterChip
                val stock = PlanSymbols.footprintInches[nearest]?.first ?: wIn
                onEvent(DrawingEvent.SetOpeningKind(e.id, nearest, if (abs(stock - wIn) <= 6f) wIn else stock))
            }, label = { Text(g.label) })
        }
    }
    val g = mine ?: return
    Text("Size", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (name in g.sizes) {
            val stock = PlanSymbols.footprintInches[name]?.first ?: continue
            FilterChip(selected = abs(stock - wIn) < 0.1f, onClick = { onEvent(DrawingEvent.SetOpeningKind(e.id, name, stock)) },
                label = { Text(PlanSymbols.sizeLabel(name)) })
        }
    }
    var other by remember(e.id) { mutableStateOf("") }
    var bad by remember(e.id) { mutableStateOf(false) }
    fun go() {
        val v = typedInches(other, state)
        if (v == null || v < 12f || v > 240f) { bad = true; return }
        bad = false
        val nearest = g.sizes.minByOrNull { abs((PlanSymbols.footprintInches[it]?.first ?: 0f) - v) } ?: return
        onEvent(DrawingEvent.SetOpeningKind(e.id, nearest, v))
        other = ""
    }
    OutlinedTextField(other, { other = it; bad = false }, singleLine = true, isError = bad, modifier = Modifier.fillMaxWidth(),
        label = { Text("Other width (2'-4\")") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { go() }))
}

/** A number in inches, applied on Done. */
@Composable
private fun InchField(label: String, value: Float, modifier: Modifier = Modifier, onSet: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(formatInches(value)) }
    OutlinedTextField(text, { text = it }, singleLine = true, modifier = modifier, label = { Text(label) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { parseInches(text)?.let(onSet) }))
}

/** The opening's own trim: "As room" follows the room's elevation; otherwise its own casing and head (Trim.specFor). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrimSection(e: DrawingElement.Symbol, window: Boolean, onEvent: (DrawingEvent) -> Unit) {
    val casing = e.properties[Trim.PROP_CASING_IN]
    val head = e.properties[Trim.PROP_HEAD]?.toInt()
    Text("Trim", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = casing == null && head == null && e.properties[Trim.PROP_WINDOW_TRIM] == null, onClick = {
            listOf(Trim.PROP_CASING_IN, Trim.PROP_HEAD, Trim.PROP_WINDOW_TRIM).forEach { onEvent(DrawingEvent.UpdateElementProperty(e.id, it, "")) }
        }, label = { Text("As the room") })
        for (c in Trim.casingChoices) FilterChip(selected = casing == c, onClick = {
            onEvent(DrawingEvent.UpdateElementProperty(e.id, Trim.PROP_CASING_IN, c.toString()))
        }, label = { Text("${formatInches(c)}\" casing") })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Trim.headLabels.forEachIndexed { i, l ->
            FilterChip(selected = head == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(e.id, Trim.PROP_HEAD, i.toString())) }, label = { Text(l) })
        }
    }
    if (window) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val wt = e.properties[Trim.PROP_WINDOW_TRIM]?.toInt()
        Trim.windowLabels.forEachIndexed { i, l ->
            FilterChip(selected = wt == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(e.id, Trim.PROP_WINDOW_TRIM, i.toString())) }, label = { Text(l) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoorSection(e: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    val n = e.symbolName
    KindAndSize(e, doorGroups, state, onEvent)
    when {
        PlanSymbols.isBarnDoor(n) -> {
            if (!PlanSymbols.isBiParting(n)) {
                Text("Slides", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Left", "Right").forEachIndexed { i, l ->
                        FilterChip(selected = PlanSymbols.hingeRight(e) == (i == 1), onClick = {
                            onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_HINGE_RIGHT, i.toString()))
                        }, label = { Text(l) })
                    }
                }
            }
            OutlinedButton(onClick = { onEvent(DrawingEvent.SwingDoorOtherSide(e.id)) }) { Text("Hang on the other face") }
            Text(BarnDoors.trackText(e, state), style = MaterialTheme.typography.bodySmall)
            BarnDoors.warnings(e, state).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        PlanSymbols.isPocketDoor(n) -> {
            Text("Pocket side", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Left", "Right").forEachIndexed { i, l ->
                    FilterChip(selected = PlanSymbols.pocketRight(e) == (i == 1), onClick = {
                        onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_HINGE_RIGHT, i.toString()))
                    }, label = { Text(l) })
                }
            }
        }
        PlanSymbols.isOpening(n) || PlanSymbols.isSlidingDoor(n) -> Unit
        else -> {
            Text("Swing", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!PlanSymbols.isDoubleDoor(n)) OutlinedButton(onClick = { onEvent(DrawingEvent.SwapDoorHinge(e.id)) }) { Text("Swap hinge side") }
                OutlinedButton(onClick = { onEvent(DrawingEvent.SwingDoorOtherSide(e.id)) }) { Text("Swing other way") }
            }
        }
    }
    InchField("Height (in)", e.properties[PlanSymbols.PROP_HEIGHT_IN] ?: state.building.doorHeadIn) {
        onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_HEIGHT_IN, it.toString()))
    }
    TrimSection(e, window = false, onEvent = onEvent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowSection(e: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    KindAndSize(e, PlanSymbols.doorWindowGroups.filter { g -> g.sizes.any { PlanSymbols.isWindow(it) } }, state, onEvent)
    Text("Type", style = MaterialTheme.typography.labelLarge)
    val wType = PlanSymbols.windowType(e)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlanSymbols.windowTypeLabels.forEachIndexed { i, l ->
            FilterChip(selected = wType == i, onClick = {
                onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_WINDOW_TYPE, i.toString()))
            }, label = { Text(l) })
        }
    }
    // Heads line up with the doors (Dan's rule): the head is set here, the sill follows; a head above the door height warns.
    val sill = e.properties[PlanSymbols.PROP_SILL_IN] ?: 36f
    val h = e.properties[PlanSymbols.PROP_HEIGHT_IN] ?: 48f
    val head = sill + h
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InchField("Head height (in)", head, Modifier.weight(1f)) { newHead ->
            onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_SILL_IN, (newHead - h).coerceAtLeast(0f).toString()))
        }
        InchField("Window height (in)", h, Modifier.weight(1f)) { newH ->
            // A taller window keeps its head and drops its sill.
            onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_SILL_IN, (head - newH).coerceAtLeast(0f).toString()))
            onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_HEIGHT_IN, newH.toString()))
        }
    }
    if (head > state.building.doorHeadIn + 0.1f)
        Text("The head is above the door height (${formatInches(state.building.doorHeadIn)}\"): it won't line up with the door heads.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    TrimSection(e, window = true, onEvent = onEvent)
}

/** Wall card (Nov 27): its type (thickness), its length, and the finish on each side, each side named by its room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WallSection(e: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    val inch = inchOf(state)
    val b = FloorPlan.bounds(e)
    val horizontal = b.width >= b.height
    Text("Wall type", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (name in PlanSymbols.walls) {
            val t = PlanSymbols.footprintInches[name]?.second ?: continue
            FilterChip(selected = e.symbolName == name, onClick = { onEvent(DrawingEvent.SetWallKind(e.id, name)) },
                label = { Text("${name.removeSuffix(" Wall")} (${formatInches(t)}\")") })
        }
    }
    val len = (if (horizontal) b.width else b.height) / inch
    var text by remember(e.id, len) { mutableStateOf(feetInches(len)) }
    OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(if (horizontal) "Length (west end stays)" else "Length (north end stays)") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { typedInches(text, state)?.let { onEvent(DrawingEvent.SetWallLength(e.id, it)) } }))
    // Each side: the finish over the drywall (Wainscot styles, tile included), named by the room it faces.
    val sides = if (horizontal) listOf(ViewerSide.ABOVE to "North side", ViewerSide.BELOW to "South side")
        else listOf(ViewerSide.LEFT to "West side", ViewerSide.RIGHT to "East side")
    for ((side, label) in sides) {
        val probe = when (side) {
            ViewerSide.ABOVE -> Offset(b.center.x, b.top - 6f * inch)
            ViewerSide.BELOW -> Offset(b.center.x, b.bottom + 6f * inch)
            ViewerSide.LEFT -> Offset(b.left - 6f * inch, b.center.y)
            ViewerSide.RIGHT -> Offset(b.right + 6f * inch, b.center.y)
        }
        val room = CabinetList.roomAt(probe, state)?.second
        Text(if (room != null) "$label: $room" else label, style = MaterialTheme.typography.labelLarge)
        val style = (e.properties[Wainscot.styleKey(side)] ?: 0f).toInt()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Wainscot.styleLabels.forEachIndexed { i, l ->
                FilterChip(selected = style == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(e.id, Wainscot.styleKey(side), i.toString())) },
                    label = { Text(if (i == 0) "Drywall" else l) })
            }
        }
        if (style != 0) InchField("Height (in)", e.properties[Wainscot.heightKey(side)]?.takeIf { it > 0f } ?: Wainscot.defaultHeight(style)) {
            onEvent(DrawingEvent.UpdateElementProperty(e.id, Wainscot.heightKey(side), it.toString()))
        }
    }
}

/** Cabinet card (Nov 27): kind and size, its height where it has one, door style, hardware, front, apply to its room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CabinetSection(e: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    val inch = inchOf(state)
    val wIn = e.size.width / inch
    val mine = PlanSymbols.cabinetGroups.firstOrNull { e.symbolName in it.sizes }
    if (mine != null) {
        Text("Size", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in mine.sizes) {
                val stock = PlanSymbols.footprintInches[name]?.first ?: continue
                FilterChip(selected = abs(stock - wIn) < 0.1f, onClick = { onEvent(DrawingEvent.SetCabinetKind(e.id, name, stock)) },
                    label = { Text(PlanSymbols.sizeLabel(name)) })
            }
        }
        var other by remember(e.id) { mutableStateOf("") }
        OutlinedTextField(other, { other = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Other width (in)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val v = parseInches(other) ?: return@KeyboardActions
                if (v < 3f || v > 120f) return@KeyboardActions
                val nearest = mine.sizes.minByOrNull { abs((PlanSymbols.footprintInches[it]?.first ?: 0f) - v) } ?: return@KeyboardActions
                onEvent(DrawingEvent.SetCabinetKind(e.id, nearest, v)); other = ""
            }))
    }
    when {
        PlanSymbols.isUpperCabinet(e) -> InchField("Height (in)", PlanSymbols.upperHeightOf(e) ?: PlanSymbols.DEFAULT_UPPER_HEIGHT_IN) {
            onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_UPPER_HEIGHT_IN, it.toString()))
        }
        PlanSymbols.isVanity(e.symbolName) -> InchField("Top height (in)", PlanSymbols.vanityTop(e)) {
            onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_TOP_IN, it.toString()))
        }
    }
    if (!PlanSymbols.hasStyleChoice(e)) return
    if (e.symbolName.startsWith("Base") || PlanSymbols.isVanity(e.symbolName)) {
        Text("Front", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlanSymbols.frontLabels.forEachIndexed { i, l ->
                FilterChip(selected = PlanSymbols.front(e) == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_FRONT, i.toString())) },
                    label = { Text(l) })
            }
        }
    }
    Text("Door style", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlanSymbols.styleLabels.forEachIndexed { i, l ->
            FilterChip(selected = PlanSymbols.doorStyle(e) == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_STYLE, i.toString())) },
                label = { Text(l) })
        }
    }
    Text("Hardware", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlanSymbols.hardwareLabels.forEachIndexed { i, l ->
            FilterChip(selected = PlanSymbols.hardware(e) == i, onClick = { onEvent(DrawingEvent.UpdateElementProperty(e.id, PlanSymbols.PROP_HARDWARE, i.toString())) },
                label = { Text(l) })
        }
    }
    val room = CabinetList.roomAt(e.center, state)?.second
    OutlinedButton(onClick = { onEvent(DrawingEvent.ApplyCabinetStyleInRoom(e.id)) }) {
        Text(if (room != null) "Same style and hardware for all of $room" else "Same style and hardware for this room")
    }
}

/**
 * Electrical, HVAC and plumbing card (Nov 27): the kind's other sizes; a wall device's height; a duct's airflow and the
 * size it needs; an electrical device's circuit; which lights and fans a switch controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MepSection(e: DrawingElement.Symbol, state: CanvasState, onEvent: (DrawingEvent) -> Unit) {
    val mep = com.example.sitedraft.ui.canvas.Mep
    val inch = inchOf(state)
    val group = (mep.electricalGroups + mep.hvacGroups + mep.plumbingGroups).firstOrNull { e.symbolName in it.sizes }
    if (group != null && group.sizes.size > 1) {
        Text(group.label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in group.sizes) FilterChip(selected = name == e.symbolName, onClick = {
                if (name == e.symbolName) return@FilterChip
                // Same spot, the new kind's size (a duct or pipe keeps its length).
                val fp = PlanSymbols.footprintInches[name] ?: return@FilterChip
                val keepLength = (mep.isDuct(name) && mep.isDuct(e.symbolName)) || (mep.isPipe(name) && mep.isPipe(e.symbolName))
                onEvent(DrawingEvent.ReplaceSymbol(e.id, name, if (keepLength) e.size.width / inch else fp.first, fp.second))
            }, label = { Text(PlanSymbols.sizeLabel(name)) })
        }
    }
    if (mep.isWallDevice(e.symbolName)) {
        InchField("Height to the bottom (in)", mep.mountIn(e)) { onEvent(DrawingEvent.UpdateElementProperty(e.id, mep.MOUNT_H, it.toString())) }
        Text("Shows on this wall's room elevations at that height.", style = MaterialTheme.typography.bodySmall)
    }
    // Airflow on registers and ducts; a duct gets the size that carries it (equal friction, Mep.roundFor).
    val airy = mep.isDuct(e.symbolName) || e.symbolName.contains("Register") || e.symbolName.startsWith("Return Grille")
    if (airy) {
        val cfm = e.properties[mep.CFM] ?: 0f
        NumberField("Airflow (CFM)", cfm) { onEvent(DrawingEvent.UpdateElementProperty(e.id, mep.CFM, it.toString())) }
        if (mep.isDuct(e.symbolName)) {
            val own = mep.ductRound(e.symbolName) ?: 0f
            Text("This duct carries about ${mep.cfmFor(own).toInt()} CFM at ${mep.DEFAULT_FRICTION}\" per 100 ft.", style = MaterialTheme.typography.bodySmall)
            if (cfm > 0f) {
                val need = mep.roundFor(cfm)
                val pick = mep.sizeFor(e.symbolName, cfm)
                Text("$cfm CFM needs ${formatInches(kotlin.math.round(need * 10f) / 10f)}\" round (or the same as a rectangle).", style = MaterialTheme.typography.bodySmall)
                if (pick == null) Text("Bigger than this list goes: split the run or go to a larger trunk.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else if (pick != e.symbolName) OutlinedButton(onClick = {
                    val fp = PlanSymbols.footprintInches[pick] ?: return@OutlinedButton
                    onEvent(DrawingEvent.ReplaceSymbol(e.id, pick, e.size.width / inch, fp.second))
                }) { Text("Use ${pick.removePrefix("Duct Trunk ").removePrefix("Round Duct ")}") }
                else Text("This size is right.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (mep.isDuct(e.symbolName) || mep.isPipe(e.symbolName)) Text("Stretch it along the run with its handles.", style = MaterialTheme.typography.bodySmall)
    if (e.category == mep.ELECTRICAL) {
        NumberField("Circuit #", e.properties[mep.CIRCUIT] ?: 0f) { onEvent(DrawingEvent.UpdateElementProperty(e.id, mep.CIRCUIT, it.toString())) }
    }
    // A switch: the lights and fans in its room it can control (switch legs).
    if (mep.isSwitch(e.symbolName)) {
        val room = CabinetList.roomAt(e.center, state)
        val near = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { d ->
            mep.isSwitched(d.symbolName) && (room?.first?.contains(d.center) ?: ((d.center - e.center).getDistance() < 240f * inch))
        }
        Text("Controls", style = MaterialTheme.typography.labelLarge)
        if (near.isEmpty()) Text("No lights or fans in this room yet.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in near) FilterChip(selected = state.switchLegs.any { it.switchId == e.id && it.deviceId == d.id },
                onClick = { onEvent(DrawingEvent.ToggleSwitchLeg(e.id, d.id)) }, label = { Text(d.symbolName) })
        }
    }
}

/** A plain number, applied on Done. */
@Composable
private fun NumberField(label: String, value: Float, modifier: Modifier = Modifier, onSet: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0f) "" else (if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString())) }
    OutlinedTextField(text, { text = it }, singleLine = true, modifier = modifier.fillMaxWidth(), label = { Text(label) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { (text.trim().toFloatOrNull() ?: if (text.isBlank()) 0f else null)?.let(onSet) }))
}
