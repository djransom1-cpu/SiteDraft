package com.example.sitedraft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Bathtub
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

@Composable
fun SymbolLibraryPanel(
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
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
                    text = "Symbol Library",
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            SymbolCategory("Electrical", Icons.Default.ElectricBolt, listOf("Outlet", "Switch", "Light Fixture", "Junction Box"), onEvent)
            SymbolCategory("Plumbing", Icons.Default.WaterDrop, listOf("Valve", "Faucet"), onEvent)
            SymbolCategory(PlanSymbols.FIXTURES, Icons.Default.Bathtub, PlanSymbols.fixtures, onEvent)
            SymbolCategory(PlanSymbols.CABINETS, Icons.Default.Kitchen, PlanSymbols.cabinets, onEvent)
            SymbolCategory(PlanSymbols.OUTDOOR, Icons.Default.OutdoorGrill, PlanSymbols.outdoor, onEvent)
            SymbolCategory(PlanSymbols.CLOSET, Icons.Default.Checkroom, PlanSymbols.closet, onEvent)
            SymbolCategory(PlanSymbols.FURNITURE, Icons.Default.Weekend, PlanSymbols.furniture, onEvent)
            SymbolCategory(PlanSymbols.DOORS_WINDOWS, Icons.Default.DoorFront, PlanSymbols.doorsWindows, onEvent)
            SymbolCategory(PlanSymbols.WALLS, Icons.Default.Fence, PlanSymbols.walls, onEvent)
            SymbolCategory("HVAC", Icons.Default.ElectricBolt, listOf("Vent", "AC Unit", "Thermostat"), onEvent)
            SymbolCategory("Lumber", Icons.Default.Square, listOf("2x4", "2x6", "2x8", "2x10", "2x12", "1x4", "1x6", "1x8"), onEvent)
            SymbolCategory("Plywood", Icons.Default.Layers, listOf("Plywood 7/16\"", "Plywood 1/2\"", "Plywood 5/8\"", "Plywood 3/4\""), onEvent)
            SymbolCategory("Structural", Icons.Default.Foundation, listOf("W-Beam (W12x26)", "W-Beam (W10x49)", "W-Beam (W8x31)", "W-Beam (W6x15)", "Custom W-Beam", "L-Channel", "Column", "Footing"), onEvent)
            SymbolCategory("Welding", Icons.Default.Whatshot, listOf("Fillet Weld", "V-Groove Weld", "Bevel Weld", "Plug/Slot Weld", "Spot Weld", "Weld All Around", "Field Weld"), onEvent)
            SymbolCategory("Site Layout", Icons.Default.Park, listOf("Tree", "Bush", "Light Pole"), onEvent)
        }
    }
}

@Composable
private fun SymbolCategory(
    name: String,
    icon: ImageVector,
    symbols: List<String>,
    onEvent: (DrawingEvent) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

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
                                SymbolItem(symbol) { onEvent(DrawingEvent.SelectSymbol(symbol, name)) }
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
private fun SymbolItem(name: String, onClick: () -> Unit) {
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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.LightGray.copy(alpha = 0.3f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                // Placeholder for symbol icon
                Text(text = name.take(1), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = name, style = MaterialTheme.typography.bodySmall)
        }
    }
}
