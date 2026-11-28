package com.example.sitedraft.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.rememberTextMeasurer
import com.example.sitedraft.ui.canvas.TextDrawing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingBackground
import com.example.sitedraft.ui.canvas.DrawingScale
import com.example.sitedraft.ui.canvas.SymbolFactory
import com.example.sitedraft.ui.canvas.drawElement
import kotlin.math.max

/**
 * A library item's icon (Dan, Nov 28: "boring and uninformative"): the item itself, drawn by the same code as on the plan,
 * shrunk to fit. The drawing's scale is set so the item fills the box, so its lines keep their weight; items with a fixed
 * size (a tree, a weld mark) are scaled to fit instead.
 */
@Composable
fun SymbolThumb(name: String, category: String, modifier: Modifier = Modifier.size(44.dp)) {
    val bg = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    // Labels (a switch's "S") need the text measurer the plan canvas shares; set it here if the library opens first.
    val measurer = rememberTextMeasurer()
    if (TextDrawing.measurer == null) TextDrawing.measurer = measurer
    Canvas(modifier) { drawSymbolThumb(name, category, bg, ink) }
}

/** Draws [name] filling this scope (the library icon; also used by the contact-sheet test). */
fun DrawScope.drawSymbolThumb(name: String, category: String, bg: Color, ink: Color) {
    run {
        val base = CanvasState(backgroundColor = bg, currentColor = ink, background = DrawingBackground.PLAIN)
        val target = size.minDimension * 0.86f
        val first = runCatching { SymbolFactory.build(name, category, base, Offset.Zero) }.getOrNull() ?: return
        // A long thin item (a wall, a pipe, a duct, a window, a rail) is cut to 3 times its depth, so its detail shows.
        val long = max(first.size.width, first.size.height); val short = minOf(first.size.width, first.size.height)
        val cut = short > 0f && long > short * 3f
        val fs = if (!cut) first.size else if (first.size.width >= first.size.height) Size(short * 3f, short) else Size(short, short * 3f)
        val d1 = max(fs.width, fs.height).coerceAtLeast(1f)
        val k1 = target / d1
        val st = base.copy(scale = DrawingScale(ratio = base.scale.ratio * k1))
        val sym = runCatching {
            SymbolFactory.build(name, category, st, Offset.Zero, sizeOverride = if (cut) Size(fs.width * k1, fs.height * k1) else null)
        }.getOrNull() ?: return
        val d2 = max(sym.size.width, sym.size.height).coerceAtLeast(1f)
        val k = (target / d2).coerceIn(0.05f, 4f)
        val state = st.copy(elements = listOf(sym))
        translate(size.width / 2f, size.height / 2f) {
            scale(k, k, pivot = Offset.Zero) {
                runCatching { drawElement(sym, isSelected = false, state = state) }
            }
        }
    }
}
