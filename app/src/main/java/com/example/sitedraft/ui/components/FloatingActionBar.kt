package com.example.sitedraft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingTool

@Composable
fun FloatingActionBar(
    currentTool: DrawingTool,
    canExplode: Boolean = false,
    onEvent: (DrawingEvent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.wrapContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // MOVE TOGGLE
            IconButton(
                onClick = { onEvent(DrawingEvent.SelectTool(DrawingTool.MOVE)) },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (currentTool == DrawingTool.MOVE) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
            ) {
                Icon(Icons.Default.OpenWith, contentDescription = "Move")
            }

            // PT-TO-PT TOGGLE
            IconButton(
                onClick = { onEvent(DrawingEvent.SelectTool(DrawingTool.MOVE_PRECISE)) },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (currentTool == DrawingTool.MOVE_PRECISE) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
            ) {
                Icon(Icons.Default.AdsClick, contentDescription = "Point-to-Point")
            }

            VerticalDivider(modifier = Modifier.height(24.dp))

            // COPY
            IconButton(onClick = { onEvent(DrawingEvent.DuplicateSelectedElement) }) {
                Icon(Icons.Default.CopyAll, contentDescription = "Copy")
            }

            // ROTATE a quarter turn either way - works on a box-selected group too (the whole
            // floor plan turns about its centre).
            IconButton(onClick = { onEvent(DrawingEvent.RotateSelection(-90f)) }) {
                Icon(Icons.Default.RotateLeft, contentDescription = "Rotate left 90")
            }
            IconButton(onClick = { onEvent(DrawingEvent.RotateSelection(90f)) }) {
                Icon(Icons.Default.RotateRight, contentDescription = "Rotate right 90")
            }

            // EXPLODE - only offered for shapes that actually have separable edges
            // (Rectangle/Triangle/Symbol); breaks it into individual Line pieces.
            if (canExplode) {
                IconButton(onClick = { onEvent(DrawingEvent.ExplodeSelectedElement) }) {
                    Icon(Icons.Default.CallSplit, contentDescription = "Explode")
                }
            }

            // DELETE
            IconButton(
                onClick = { onEvent(DrawingEvent.DeleteSelectedElement) },
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }

            VerticalDivider(modifier = Modifier.height(24.dp))

            // CLOSE Panel/Deselect
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Deselect")
            }
        }
    }
}
