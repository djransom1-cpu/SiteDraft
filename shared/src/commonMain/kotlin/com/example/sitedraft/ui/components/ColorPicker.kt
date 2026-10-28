package com.example.sitedraft.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.parseHexColor
import com.example.sitedraft.ui.canvas.toHexString
import kotlin.math.roundToInt

// Rendering-oriented swatches, 8 per row, grouped by material so the rows read as:
// neutrals / wood & earth / stone, concrete & brick / metals & roofing / water & sky /
// vegetation / accents (the last is the app's cyan used for symbol icons).
val RenderingSwatches: List<Color> = listOf(
    0xFFFFFFFF, 0xFFE6E6E6, 0xFFBFBFBF, 0xFF8C8C8C, 0xFF595959, 0xFF333333, 0xFF1A1A1A, 0xFF000000,
    0xFFF5DEB3, 0xFFDEB887, 0xFFC19A6B, 0xFFA0522D, 0xFF8B5A2B, 0xFF6B4423, 0xFF4A2C17, 0xFF3B2410,
    0xFFE0C9A6, 0xFFD9D4C7, 0xFFBEB9AE, 0xFFA39E93, 0xFF7D7A72, 0xFFC97B5A, 0xFFB7410E, 0xFF9C2F1C,
    0xFFC0C0C0, 0xFFA8A9AD, 0xFF708090, 0xFF4F5B66, 0xFF2F4F4F, 0xFFB87333, 0xFFCD7F32, 0xFFD4AF37,
    0xFFE0F7FA, 0xFFB3E5FC, 0xFF80DEEA, 0xFF4FC3F7, 0xFF0288D1, 0xFF01579B, 0xFF26A69A, 0xFF1A237E,
    0xFFDCEDC8, 0xFFAED581, 0xFF7CB342, 0xFF558B2F, 0xFF33691E, 0xFF2E7D32, 0xFFA1887F, 0xFF8D6E63,
    0xFFFF0000, 0xFFFF6F00, 0xFFFFEB3B, 0xFF00E676, 0xFF2979FF, 0xFFD500F9, 0xFFFF4081, 0xFF80E0FF,
).map { Color(it) }

private const val SWATCHES_PER_ROW = 8
private val SWATCH_SPACING = 4.dp

private data class Hsv(val h: Float, val s: Float, val v: Float)

private fun Color.toHsv(): Hsv {
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else d / max
    return Hsv(h, s, max)
}

private fun hsvToColor(hsv: Hsv, alpha: Float): Color {
    val c = hsv.v * hsv.s
    val hh = (hsv.h % 360f) / 60f
    val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
    val (r1, g1, b1) = when {
        hh < 1f -> Triple(c, x, 0f)
        hh < 2f -> Triple(x, c, 0f)
        hh < 3f -> Triple(0f, c, x)
        hh < 4f -> Triple(0f, x, c)
        hh < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = hsv.v - c
    return Color(r1 + m, g1 + m, b1 + m, alpha)
}

/**
 * Full colour picker: current-colour preview with hex entry, recent colours, the rendering
 * swatch grid, and a custom section (saturation/value square, hue bar, opacity).
 *
 * [onColorChange] fires continuously (every drag frame) so the drawing can preview live;
 * [onColorCommitted] fires once per deliberate pick (swatch tap, valid hex, drag or slider
 * release) and is where callers record "recent" colours.
 */
@Composable
fun ColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    onColorCommitted: (Color) -> Unit = onColorChange,
    recentColors: List<Color> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // Hue/saturation/value are kept locally rather than re-derived from `color` on every
    // recomposition: greys, black and white have no defined hue, so re-deriving would snap the
    // hue bar back to red the moment the user dragged into a grey - losing the hue they had
    // lined up. `lastEmitted` tells an external colour change apart from our own echo.
    var hsv by remember { mutableStateOf(color.toHsv()) }
    var alpha by remember { mutableStateOf(color.alpha) }
    var lastEmitted by remember { mutableStateOf(color) }
    LaunchedEffect(color) {
        if (color != lastEmitted) {
            hsv = color.toHsv()
            alpha = color.alpha
            lastEmitted = color
        }
    }
    val current = hsvToColor(hsv, alpha)

    fun emit(commit: Boolean) {
        val c = hsvToColor(hsv, alpha)
        lastEmitted = c
        onColorChange(c)
        if (commit) onColorCommitted(c)
    }

    fun pick(c: Color) {
        hsv = c.toHsv()
        alpha = c.alpha
        lastEmitted = c
        onColorChange(c)
        onColorCommitted(c)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorPreview(current, Modifier.size(48.dp))
            HexField(current, onValid = { pick(it) }, modifier = Modifier.weight(1f))
        }

        // Swatch size adapts to the host: 28dp in the dialog, smaller in the 300dp Properties
        // panel, so a full row of eight always fits instead of clipping the last column.
        BoxWithConstraints {
            val swatchSize = ((maxWidth - SWATCH_SPACING * (SWATCHES_PER_ROW - 1)) / SWATCHES_PER_ROW)
                .coerceAtMost(28.dp)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (recentColors.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(SWATCH_SPACING)) {
                        recentColors.take(SWATCHES_PER_ROW).forEach { c ->
                            Swatch(c, selected = c == current, size = swatchSize) { pick(c) }
                        }
                    }
                }

                Text("Swatches", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(SWATCH_SPACING)) {
                    RenderingSwatches.chunked(SWATCHES_PER_ROW).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(SWATCH_SPACING)) {
                            row.forEach { c -> Swatch(c, selected = c == current, size = swatchSize) { pick(c) } }
                        }
                    }
                }
            }
        }

        Text("Custom", style = MaterialTheme.typography.labelLarge)
        SaturationValueSquare(
            hsv = hsv,
            onChange = { s, v -> hsv = hsv.copy(s = s, v = v); emit(commit = false) },
            onFinished = { emit(commit = true) }
        )
        HueBar(
            hue = hsv.h,
            onChange = { hsv = hsv.copy(h = it); emit(commit = false) },
            onFinished = { emit(commit = true) }
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Opacity ${(alpha * 100f).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = alpha,
                onValueChange = { alpha = it; emit(commit = false) },
                onValueChangeFinished = { emit(commit = true) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) primary else outline.copy(alpha = 0.6f), CircleShape)
            .clickable(onClick = onClick)
    )
}

/** Colour over a checkerboard so translucent colours read as translucent. */
@Composable
private fun ColorPreview(color: Color, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, outline, RoundedCornerShape(8.dp))) {
        val cell = 8.dp.toPx()
        var y = 0f
        var rowIndex = 0
        while (y < size.height) {
            var x = 0f
            var colIndex = 0
            while (x < size.width) {
                val light = (rowIndex + colIndex) % 2 == 0
                drawRect(if (light) Color(0xFFE0E0E0) else Color(0xFFA0A0A0), Offset(x, y), Size(cell, cell))
                x += cell; colIndex++
            }
            y += cell; rowIndex++
        }
        drawRect(color)
    }
}

@Composable
private fun HexField(color: Color, onValid: (Color) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(color.toHexString()) }
    var editing by remember { mutableStateOf(false) }
    // Follow external changes (swatch taps, drags) unless the user is typing in the field.
    LaunchedEffect(color, editing) { if (!editing) text = color.toHexString() }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            parseHexColor(typed)?.let { parsed -> if (parsed != color) onValid(parsed) }
        },
        label = { Text("Hex") },
        singleLine = true,
        isError = parseHexColor(text) == null,
        modifier = modifier.onFocusChanged { editing = it.isFocused }
    )
}

@Composable
private fun SaturationValueSquare(hsv: Hsv, onChange: (Float, Float) -> Unit, onFinished: () -> Unit) {
    val latestOnChange by rememberUpdatedState(onChange)
    val latestOnFinished by rememberUpdatedState(onFinished)
    val hueColor = hsvToColor(Hsv(hsv.h, 1f, 1f), 1f)

    fun report(p: Offset, size: IntSize) {
        val s = (p.x / size.width).coerceIn(0f, 1f)
        val v = 1f - (p.y / size.height).coerceIn(0f, 1f)
        latestOnChange(s, v)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures { p -> report(p, size); latestOnFinished() }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p -> report(p, size) },
                    onDragEnd = { latestOnFinished() },
                    onDragCancel = { latestOnFinished() }
                ) { change, _ -> change.consume(); report(change.position, size) }
            }
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val marker = Offset(hsv.s * size.width, (1f - hsv.v) * size.height)
        drawCircle(Color.Black, radius = 9.dp.toPx(), center = marker, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White, radius = 8.dp.toPx(), center = marker, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val latestOnChange by rememberUpdatedState(onChange)
    val latestOnFinished by rememberUpdatedState(onFinished)

    fun report(p: Offset, size: IntSize) {
        latestOnChange((p.x / size.width).coerceIn(0f, 1f) * 360f)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures { p -> report(p, size); latestOnFinished() }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p -> report(p, size) },
                    onDragEnd = { latestOnFinished() },
                    onDragCancel = { latestOnFinished() }
                ) { change, _ -> change.consume(); report(change.position, size) }
            }
    ) {
        val stops = (0..6).map { hsvToColor(Hsv(it * 60f, 1f, 1f), 1f) }
        drawRect(Brush.horizontalGradient(stops))
        val x = hue / 360f * size.width
        drawRect(Color.Black, Offset(x - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height))
        drawRect(Color.White, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
    }
}
