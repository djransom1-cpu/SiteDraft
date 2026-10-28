package com.example.sitedraft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent

@Composable
actual fun LaserPanel(
    state: CanvasState,
    laser: Any?,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier,
    onClose: () -> Unit
) = LaserUnavailablePanel(modifier, onClose)
