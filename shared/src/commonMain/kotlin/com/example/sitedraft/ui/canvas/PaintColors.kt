package com.example.sitedraft.ui.canvas

/**
 * Paint colours to pick from (Dan, Nov 26): Sherwin-Williams' ColorSnap colours, from their own color information
 * spreadsheet (number, name, hex), kept in [SwColorData]. Dan is asking Sherwin-Williams for permission before
 * Site Draft is sold with them; a colour can also be typed in by hex with any name.
 */
object PaintColors {
    data class PaintColor(val code: String, val name: String, val rgb: Int) {
        val hex: String get() = "#" + rgb.toString(16).padStart(6, '0').uppercase()
    }

    /** Every colour, in the spreadsheet's order (by number). */
    val all: List<PaintColor> by lazy {
        SwColorData.DATA.lineSequence().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 3) return@mapNotNull null
            val rgb = p[2].trim().toIntOrNull(16) ?: return@mapNotNull null
            PaintColor("SW " + p[0].trim(), p[1].trim(), rgb)
        }.toList()
    }

    private fun digits(s: String) = s.filter { it.isDigit() }

    /** The colour with this number ("SW 7036", "sw7036" or "7036"); null when there isn't one. */
    fun find(code: String): PaintColor? {
        val d = digits(code).trimStart('0')
        if (d.isEmpty()) return null
        return all.firstOrNull { digits(it.code).trimStart('0') == d }
    }

    /** Up to [limit] colours whose number or name matches [query]: exact number first, then names starting with it, then containing it. */
    fun search(query: String, limit: Int = 24): List<PaintColor> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val d = digits(q).trimStart('0')
        val byNumber = if (d.isNotEmpty() && q.removePrefix("sw").trim().all { it.isDigit() })
            all.filter { digits(it.code).trimStart('0').startsWith(d) }.sortedBy { digits(it.code).length } else emptyList()
        val starts = all.filter { it.name.lowercase().startsWith(q) }
        val contains = all.filter { q in it.name.lowercase() }
        return (byNumber + starts + contains).distinct().take(limit)
    }
}
