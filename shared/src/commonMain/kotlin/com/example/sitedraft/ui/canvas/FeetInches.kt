package com.example.sitedraft.ui.canvas

import com.example.sitedraft.util.format
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Feet and inches the way a tape measure reads them: fractions, not decimals. Every imperial
 * length the app shows (dimension labels, the Properties fields, wall lengths, elevation titles)
 * goes through [formatInches] / [formatFeetInches], and every imperial length the user types
 * goes through [parseInches] / [parseFeet], so "3 1/2" is as welcome as "3.5".
 */
const val INCH_DENOMINATOR = 16

/** Inches as "6 1/2", "3/4", "12" or "0", to the nearest 1/[denominator]. */
fun formatInches(inches: Float, denominator: Int = INCH_DENOMINATOR): String {
    val negative = inches < 0f
    val units = (abs(inches) * denominator).roundToInt()
    val whole = units / denominator
    var num = units % denominator
    var den = denominator
    while (num != 0 && num % 2 == 0) { num /= 2; den /= 2 }
    val text = when {
        num == 0 -> "$whole"
        whole == 0 -> "$num/$den"
        else -> "$whole $num/$den"
    }
    return if (negative) "-$text" else text
}

/** A length in feet as 3'-6 1/2", 6 1/2", 3' or 0", to the nearest 1/[denominator] inch. */
fun formatFeetInches(valueInFeet: Float, denominator: Int = INCH_DENOMINATOR): String {
    val (feet, inches) = splitFeetInches(abs(valueInFeet), 1f / denominator)
    val inchText = formatInches(inches, denominator)
    val text = when {
        feet > 0 && inchText == "0" -> "$feet'"
        feet > 0 -> "$feet'-$inchText\""
        else -> "$inchText\""
    }
    return if (valueInFeet < 0f) "-$text" else text
}

/**
 * Inches typed by the user: "6", "6.5", "6 1/2", "6-1/2", "1/2" or "6 1/2\"". Null when it is
 * not a number.
 */
fun parseInches(text: String): Float? {
    val s = text.trim().trimEnd('"').trim().replace('½', ' ').trim()
    if (s.isEmpty()) return null
    val negative = s.startsWith("-")
    val body = s.trimStart('-', '+').trim()
    val slash = body.indexOf('/')
    val value: Float = if (slash < 0) {
        body.toFloatOrNull() ?: return null
    } else {
        val num = body.substring(0, slash).trim()
        val den = body.substring(slash + 1).trim().toFloatOrNull() ?: return null
        if (den == 0f) return null
        // "6 1/2" or "6-1/2": a whole part, then the fraction.
        val split = num.lastIndexOfAny(charArrayOf(' ', '-'))
        val whole = if (split < 0) 0f else (num.substring(0, split).trim().toFloatOrNull() ?: return null)
        val numerator = num.substring(split + 1).trim().toFloatOrNull() ?: return null
        whole + numerator / den
    }
    return if (negative) -value else value
}

/**
 * A length in feet typed by the user: "12", "12.5", 12'6", 12' 6 1/2", "12-6", "12ft 6in" or
 * just 30" (inches only). Null when it is not a length.
 */
fun parseFeet(text: String): Float? {
    var s = text.trim().lowercase()
    if (s.isEmpty()) return null
    s = s.replace("feet", "'").replace("ft", "'").replace("inches", "\"").replace("in", "\"")
    val feetMark = s.indexOf('\'')
    if (feetMark < 0) {
        // No foot mark: inches when it ends in ", feet otherwise.
        return if (s.endsWith("\"")) parseInches(s)?.let { it / 12f } else s.toFloatOrNull() ?: parseDashFeet(s)
    }
    val feet = s.substring(0, feetMark).trim().let { if (it.isEmpty()) 0f else it.toFloatOrNull() ?: return null }
    val rest = s.substring(feetMark + 1).trim().trimStart('-').trim()
    val inches = if (rest.isEmpty()) 0f else parseInches(rest) ?: return null
    return feet + inches / 12f
}

/** "12-6" as 12 feet 6 inches (a plain negative number never reaches here: it parses as a float). */
private fun parseDashFeet(s: String): Float? {
    val dash = s.indexOf('-')
    if (dash <= 0) return null
    val feet = s.substring(0, dash).trim().toFloatOrNull() ?: return null
    val inches = parseInches(s.substring(dash + 1)) ?: return null
    return feet + inches / 12f
}

/** Feet in the project's words for a panel label: "12'-6\"" when imperial, "3.81 m" when metric. */
fun formatLength(valueInUnits: Float, unitSystem: UnitSystem): String =
    if (unitSystem == UnitSystem.IMPERIAL) formatFeetInches(valueInUnits) else "%.2f m".format(valueInUnits)
