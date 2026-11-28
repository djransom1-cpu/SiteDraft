package com.example.sitedraft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.filled.Microwave
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material.icons.filled.Foundation
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OutdoorGrill
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.LayerTrade
import com.example.sitedraft.ui.canvas.Structure

@Composable
fun SymbolLibraryPanel(
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** The active layer's trade: its tools come first, opened (foundation parts on Foundation...). */
    activeTrade: LayerTrade? = null,
    /** The drawing, for the size pickers (Nov 27); without it doors and windows list every size. */
    state: com.example.sitedraft.ui.canvas.CanvasState? = null,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        // Explicit: a translucent colour matches no theme colour, so contentColorFor() fell back to
        // black and the panel's titles were black on blue.
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // The active layer's own tools first, opened, so a foundation or framing layer shows
            // what can be drawn on it.
            val onFoundation = activeTrade == LayerTrade.FOUNDATION
            val onFraming = activeTrade == LayerTrade.FLOOR_STRUCTURAL
            if (onFoundation) {
                Text("Drawing on the foundation layer", style = MaterialTheme.typography.labelLarge)
                Button(onClick = { onEvent(DrawingEvent.MatchFloorPlan) }, modifier = Modifier.fillMaxWidth()) { Text("Match floor plan") }
                SymbolCategory(Structure.FOUNDATION, Icons.Default.Foundation, Structure.foundationLibrary, onEvent, startExpanded = true)
                SymbolCategory("Foundation walls", Icons.Default.Fence, listOf("12\" Block Wall", "10\" Concrete Wall", "8\" Block Wall", "8\" Concrete Wall"), onEvent, startExpanded = true, category = PlanSymbols.WALLS)
            }
            if (onFraming) {
                Text("Drawing on a floor structural layer", style = MaterialTheme.typography.labelLarge)
                Button(onClick = { onEvent(DrawingEvent.MatchFloorPlan) }, modifier = Modifier.fillMaxWidth()) { Text("Match floor plan") }
                SymbolCategory(Structure.FRAMING, Icons.Default.ViewWeek, Structure.framingLibrary, onEvent, startExpanded = true)
            }
            // Garages (Nov 23): against the house (attached) or apart (detached); the foundation layer follows.
            SymbolCategory(com.example.sitedraft.ui.canvas.Garages.CATEGORY, Icons.Default.Foundation, com.example.sitedraft.ui.canvas.Garages.library, onEvent)
            // Wings (Nov 23): against a house wall, the plan becomes an L (also in the Floor plan panel).
            SymbolCategory(com.example.sitedraft.ui.canvas.Wings.CATEGORY, Icons.Default.Foundation, com.example.sitedraft.ui.canvas.Wings.library, onEvent)
            // Porches (Nov 23): drag against an exterior wall; the foundation layer follows.
            SymbolCategory(com.example.sitedraft.ui.canvas.Porches.CATEGORY, Icons.Default.Foundation, com.example.sitedraft.ui.canvas.Porches.library, onEvent)
            // Section and detail callouts (Nov 23): numbered from the sheet set.
            SymbolCategory(com.example.sitedraft.ui.canvas.Callouts.CATEGORY, Icons.Default.Layers, com.example.sitedraft.ui.canvas.Callouts.library, onEvent)
            // Electrical to code in one press, and a room's own card (ElectricalCode, Nov 27).
            Text("Electrical to code", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onEvent(DrawingEvent.ElectricalToCode()) }, modifier = Modifier.weight(1f)) { Text("Whole floor") }
                OutlinedButton(onClick = { onEvent(DrawingEvent.StartPlacement(com.example.sitedraft.ui.canvas.Placement.RoomElectrical)) }, modifier = Modifier.weight(1f)) { Text("Room card") }
            }
            Text("Plugs, switches, lights, smoke/CO and bath fans to the job's code. Room card: tap a room to change it.", style = MaterialTheme.typography.bodySmall)
            // Electrical and HVAC (Mep, Dan Nov 27): the kind, then its size.
            if (state != null) SizedCategory(com.example.sitedraft.ui.canvas.Mep.ELECTRICAL, Icons.Default.ElectricBolt, com.example.sitedraft.ui.canvas.Mep.electricalGroups, state, onEvent)
            else SymbolCategory(com.example.sitedraft.ui.canvas.Mep.ELECTRICAL, Icons.Default.ElectricBolt, com.example.sitedraft.ui.canvas.Mep.electrical, onEvent)
            if (state != null) SizedCategory(com.example.sitedraft.ui.canvas.Mep.HVAC, Icons.Default.Air, com.example.sitedraft.ui.canvas.Mep.hvacGroups, state, onEvent)
            else SymbolCategory(com.example.sitedraft.ui.canvas.Mep.HVAC, Icons.Default.Air, com.example.sitedraft.ui.canvas.Mep.hvac, onEvent)
            // Plumbing rough-in (Mep, Nov 27): pipes and drain/water points.
            if (state != null) SizedCategory(com.example.sitedraft.ui.canvas.Mep.PLUMBING, Icons.Default.WaterDrop, com.example.sitedraft.ui.canvas.Mep.plumbingGroups, state, onEvent)
            else SymbolCategory(com.example.sitedraft.ui.canvas.Mep.PLUMBING, Icons.Default.WaterDrop, com.example.sitedraft.ui.canvas.Mep.plumbing, onEvent)
            SymbolCategory(PlanSymbols.FIXTURES, Icons.Default.Bathtub, PlanSymbols.fixtures, onEvent)
            SymbolCategory(PlanSymbols.SHOWERS, Icons.Default.Shower, com.example.sitedraft.ui.canvas.Shower.library, onEvent)
            SymbolCategory(PlanSymbols.APPLIANCES, Icons.Default.Microwave, com.example.sitedraft.ui.canvas.Appliances.appliances, onEvent)
            SymbolCategory(PlanSymbols.HOODS, Icons.Default.Air, com.example.sitedraft.ui.canvas.Appliances.hoods, onEvent)
            SymbolCategory(PlanSymbols.STAIRS, Icons.Default.Stairs, com.example.sitedraft.ui.canvas.Stairs.library, onEvent)
            // Guard rails (Nov 24): stretch along a loft, landing or deck edge.
            SymbolCategory(PlanSymbols.RAILINGS, Icons.Default.Stairs, com.example.sitedraft.ui.canvas.Guards.library, onEvent)
            // Cabinets: the kind, then its size (Dan, Nov 27).
            if (state != null) SizedCategory(PlanSymbols.CABINETS, Icons.Default.Kitchen, PlanSymbols.cabinetGroups, state, onEvent)
            else SymbolCategory(PlanSymbols.CABINETS, Icons.Default.Kitchen, PlanSymbols.cabinets, onEvent)
            SymbolCategory(PlanSymbols.OUTDOOR, Icons.Default.OutdoorGrill, PlanSymbols.outdoor, onEvent)
            SymbolCategory(PlanSymbols.CLOSET, Icons.Default.Checkroom, PlanSymbols.closet, onEvent)
            SymbolCategory(PlanSymbols.FURNITURE, Icons.Default.Weekend, PlanSymbols.furniture, onEvent)
            // Doors and windows: the kind, then its size (Dan, Nov 27).
            if (state != null) SizedCategory(PlanSymbols.DOORS_WINDOWS, Icons.Default.DoorFront, PlanSymbols.doorWindowGroups, state, onEvent)
            else SymbolCategory(PlanSymbols.DOORS_WINDOWS, Icons.Default.DoorFront, PlanSymbols.doorsWindows, onEvent)
            SymbolCategory(PlanSymbols.WALLS, Icons.Default.Fence, PlanSymbols.walls, onEvent)
            if (!onFoundation) SymbolCategory(Structure.FOUNDATION, Icons.Default.Foundation, Structure.foundationLibrary, onEvent)
            if (!onFraming) SymbolCategory(Structure.FRAMING, Icons.Default.ViewWeek, Structure.framingLibrary, onEvent)
            SymbolCategory("Lumber", Icons.Default.Square, listOf("2x4", "2x6", "2x8", "2x10", "2x12", "1x4", "1x6", "1x8"), onEvent)
            SymbolCategory("Plywood", Icons.Default.Layers, listOf("Plywood 7/16\"", "Plywood 1/2\"", "Plywood 5/8\"", "Plywood 3/4\""), onEvent)
            SymbolCategory("Structural", Icons.Default.Foundation, listOf("W-Beam (W12x26)", "W-Beam (W10x49)", "W-Beam (W8x31)", "W-Beam (W6x15)", "Custom W-Beam", "L-Channel", "Column", "Footing"), onEvent)
            SymbolCategory("Welding", Icons.Default.Whatshot, listOf("Fillet Weld", "V-Groove Weld", "Bevel Weld", "Plug/Slot Weld", "Spot Weld", "Weld All Around", "Field Weld"), onEvent)
            SymbolCategory("Site Layout", Icons.Default.Park, listOf("Tree", "Bush", "Light Pole"), onEvent)
        }
    }
}

/** A library heading that opens to a [SizedPicker]: the kind first, then its size (Nov 27). */
@Composable
private fun SizedCategory(name: String, icon: ImageVector, groups: List<PlanSymbols.SizedGroup>, state: com.example.sitedraft.ui.canvas.CanvasState, onEvent: (DrawingEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        if (expanded) Column(modifier = Modifier.padding(8.dp)) { SizedPicker(groups, name, state, onEvent) }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
    }
}

@Composable
private fun SymbolCategory(
    name: String,
    icon: ImageVector,
    symbols: List<String>,
    onEvent: (DrawingEvent) -> Unit,
    startExpanded: Boolean = false,
    /** The category the picked symbols belong to, when the heading says something else. */
    category: String = name
) {
    var expanded by remember(startExpanded) { mutableStateOf(startExpanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null
            )
        }

        if (expanded) {
            // A plain two-column grid, so the panel's own scroll reaches every symbol. This was
            // a LazyVerticalGrid capped at 260dp: a category with more than six entries (the
            // cabinets) hid the rest behind a second, invisible scroll inside the box - the
            // upper cabinets looked like they did not exist.
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                symbols.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { symbol ->
                            Box(modifier = Modifier.weight(1f)) {
                                SymbolItem(symbol, category) { onEvent(DrawingEvent.SelectSymbol(symbol, category)) }
                            }
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
    }
}

@Composable
private fun SymbolItem(name: String, category: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The item itself, as drawn on the plan (Nov 28; was its first letter).
            SymbolThumb(name, category, Modifier.size(56.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = name, style = MaterialTheme.typography.bodySmall)
        }
    }
}
