package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/** LINE entities of a DXF file as canvas lines (the same minimal reader the Android import used). */
fun parseDxf(text: String): List<DrawingElement> {
    val lines = text.lines()
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
            elements.add(DrawingElement.Line(start = Offset(x1, y1), end = Offset(x2, y2), color = Color.Gray, strokeWidth = 1f))
            continue
        }
        i++
    }
    return elements
}
