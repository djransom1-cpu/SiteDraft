package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * LINE entities of a DXF file as canvas lines (the same minimal reader the Android import used).
 *
 * With a [state], the file is read at real size: its $INSUNITS (inches when it has none and the
 * project is imperial, millimetres when metric) is converted to the project's scale and Y is
 * flipped, so a drawing from AutoCAD, or one exported by [DxfExport], comes in upright and the
 * right size. Without one, DXF units are taken as canvas units, as before.
 */
fun parseDxf(text: String, state: CanvasState? = null): List<DrawingElement> {
    val lines = text.lines()
    val pxPerUnit = state?.let { pxPerDxfUnit(insUnits(lines), it) } ?: 1f
    val ySign = if (state != null) -1f else 1f
    val elements = mutableListOf<DrawingElement>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].trim() == "LINE") {
            var x1 = 0f; var y1 = 0f; var x2 = 0f; var y2 = 0f
            i++
            while (i < lines.size && lines[i].trim() != "0") {
                val code = lines[i].trim()
                val value = lines.getOrNull(i + 1)?.trim()?.toFloatOrNull()
                when (code) {
                    "10" -> x1 = value ?: 0f
                    "20" -> y1 = value ?: 0f
                    "11" -> x2 = value ?: 0f
                    "21" -> y2 = value ?: 0f
                }
                i += 2
            }
            elements.add(
                DrawingElement.Line(
                    start = Offset(x1 * pxPerUnit, y1 * pxPerUnit * ySign),
                    end = Offset(x2 * pxPerUnit, y2 * pxPerUnit * ySign),
                    color = Color.Gray, strokeWidth = 1f
                )
            )
            continue
        }
        i++
    }
    return elements
}

/** The file's $INSUNITS, or null when its header does not say. */
private fun insUnits(lines: List<String>): Int? {
    val at = lines.indexOfFirst { it.trim() == "\$INSUNITS" }
    if (at < 0) return null
    return lines.getOrNull(at + 2)?.trim()?.toIntOrNull()
}

private fun pxPerDxfUnit(insUnits: Int?, state: CanvasState): Float {
    val inches = when (insUnits) {
        1 -> 1f                 // inches
        2 -> 12f                // feet
        4 -> 1f / 25.4f         // millimetres
        5 -> 1f / 2.54f         // centimetres
        6 -> 39.3701f           // metres
        else -> if (state.unitSystem == UnitSystem.METRIC) 1f / 25.4f else 1f
    }
    return state.inchesToPx(inches)
}
