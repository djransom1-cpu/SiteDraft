package com.example.sitedraft.ui.canvas

import com.example.sitedraft.util.format
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** "#RRGGBB", or "#AARRGGBB" when the colour is not fully opaque. */
fun Color.toHexString(): String {
    val argb = toArgb()
    return if (alpha >= 0.999f) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
}

/**
 * Parses RGB, RRGGBB or AARRGGBB, with or without a leading '#', in either case.
 * Returns null for anything else, including partially typed input.
 */
fun parseHexColor(text: String): Color? {
    val s = text.trim().removePrefix("#")
    if (s.isEmpty() || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return when (s.length) {
        3 -> {
            val (r, g, b) = s.toList()
            Color("FF$r$r$g$g$b$b".toLong(16).toInt())
        }
        6 -> Color("FF$s".toLong(16).toInt())
        8 -> Color(s.toLong(16).toInt())
        else -> null
    }
}
