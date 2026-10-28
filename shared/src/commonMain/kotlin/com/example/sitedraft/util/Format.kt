package com.example.sitedraft.util

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The small part of printf the app uses, so the same code runs on every platform (the JVM's
 * String.format is not available on Web or iOS). Supports %d, %s, %f with an optional
 * precision (%.2f), %x / %X with an optional zero-padded width (%02X), and %%.
 */
fun String.format(vararg args: Any?): String {
    val out = StringBuilder()
    var argIndex = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%') { out.append(c); i++; continue }
        if (i + 1 < length && this[i + 1] == '%') { out.append('%'); i += 2; continue }
        // Parse flags/width/precision.
        var j = i + 1
        var zeroPad = false
        if (j < length && this[j] == '0') { zeroPad = true; j++ }
        var width = 0
        while (j < length && this[j].isDigit()) { width = width * 10 + (this[j] - '0'); j++ }
        var precision = -1
        if (j < length && this[j] == '.') {
            j++
            precision = 0
            while (j < length && this[j].isDigit()) { precision = precision * 10 + (this[j] - '0'); j++ }
        }
        if (j >= length) { out.append(substring(i)); break }
        val conv = this[j]
        val arg = if (argIndex < args.size) args[argIndex++] else null
        val text = when (conv) {
            'd' -> (arg as? Number)?.toLong()?.toString() ?: arg.toString()
            's' -> arg.toString()
            'f' -> formatFixed((arg as? Number)?.toDouble() ?: 0.0, if (precision < 0) 6 else precision)
            'x' -> toHexString(arg).lowercase()
            'X' -> toHexString(arg).uppercase()
            else -> substring(i, j + 1)
        }
        val padded = if (text.length < width) (if (zeroPad) "0" else " ").repeat(width - text.length) + text else text
        out.append(padded)
        i = j + 1
    }
    return out.toString()
}

private fun toHexString(arg: Any?): String = when (arg) {
    is Byte -> (arg.toInt() and 0xFF).toString(16)
    is Int -> arg.toUInt().toString(16)
    is Long -> arg.toULong().toString(16)
    is Number -> arg.toLong().toULong().toString(16)
    else -> arg.toString()
}

/** [value] with exactly [decimals] digits after the point, rounded half away from zero. */
fun formatFixed(value: Double, decimals: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val factor = 10.0.pow(decimals)
    val scaled = (abs(value) * factor).roundToLong()
    val whole = scaled / factor.toLong()
    val frac = scaled % factor.toLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole." + frac.toString().padStart(decimals, '0')
}

fun Float.fmt(decimals: Int): String = formatFixed(this.toDouble(), decimals)

fun toRadians(degrees: Double): Double = degrees * kotlin.math.PI / 180.0
fun toDegrees(radians: Double): Double = radians * 180.0 / kotlin.math.PI
