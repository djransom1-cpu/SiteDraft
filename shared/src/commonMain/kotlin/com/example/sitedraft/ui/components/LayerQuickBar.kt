package com.example.sitedraft.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.Layer
import com.example.sitedraft.ui.canvas.LayerTrade
import com.example.sitedraft.ui.canvas.Layers
import com.example.sitedraft.ui.canvas.Remodel
import com.example.sitedraft.ui.canvas.RemodelPhase
import com.example.sitedraft.ui.canvas.activeLayer
import com.example.sitedraft.ui.canvas.effectiveLayers

/**
 * Quick layer on/off along the top of the canvas (Dan, Nov 20), foundation first up to the roof.
 * A lit chip is on screen. The active layer (pencil) can't be switched off; tapping any other
 * chip does what the eye in the Layers panel does (LayerEdits.toggleShown).
 */
@Composable
fun LayerQuickBar(state: CanvasState, onEvent: (DrawingEvent) -> Unit, modifier: Modifier = Modifier) {
    val layers = state.effectiveLayers
    val active = state.activeLayer
    val display = Layers.displayMap(state)
    val floors = layers.count { it.trade == LayerTrade.FLOOR_PLAN }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            layers.forEach { layer ->
                val isActive = layer.id == active.id
                val shown = (display[layer.id] ?: Layers.Display.HIDDEN) != Layers.Display.HIDDEN
                FilterChip(
                    selected = shown,
                    onClick = { if (!isActive) onEvent(DrawingEvent.ToggleLayerShown(layer.id)) },
                    label = {
                        Text(
                            shortName(layer, floors),
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    },
                    leadingIcon = if (isActive) {
                        { Icon(Icons.Filled.Edit, contentDescription = "Drawing on this layer", modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }
            // Remodel phase (Remodel.kt), once the drawing has existing or demo walls.
            if (Remodel.isRemodel(state) || state.remodelPhase != RemodelPhase.ALL) {
                Text("|", color = MaterialTheme.colorScheme.outline)
                RemodelPhaseChips(state, onEvent)
            }
        }
    }
}

/** A short chip label: "Foundation", "Framing", "Plan"..., with the floor number once there is more than one floor. */
private fun shortName(layer: Layer, floors: Int): String {
    val floor = layer.levelId?.removePrefix("floor")?.substringBefore("-")?.toIntOrNull()
    val prefix = if (floors > 1 && floor != null) "$floor: " else ""
    return when (layer.trade) {
        LayerTrade.FOUNDATION -> "Foundation"
        LayerTrade.FLOOR_STRUCTURAL -> prefix + "Framing"
        LayerTrade.FLOOR_PLAN -> prefix + "Plan"
        LayerTrade.CABINETS -> prefix + "Cabinets"
        LayerTrade.FURNITURE -> prefix + "Furniture"
        LayerTrade.ELECTRICAL -> prefix + "Electrical"
        LayerTrade.MECHANICAL -> prefix + "HVAC"
        LayerTrade.PLUMBING -> prefix + "Plumbing"
        LayerTrade.ROOF_STRUCTURAL -> "Roof framing"
        LayerTrade.ROOF_PLAN -> "Roof plan"
        LayerTrade.EXTERIOR_ELEVATION -> "Exterior"
        LayerTrade.SITE_PLAN -> "Site"
        LayerTrade.CUSTOM -> layer.name
    }
}
