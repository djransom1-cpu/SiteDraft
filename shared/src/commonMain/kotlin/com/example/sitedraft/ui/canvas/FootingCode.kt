package com.example.sitedraft.ui.canvas

import kotlin.math.ceil

/**
 * The residential code's footing size for light-frame houses (Nov 23 2026): 2015 IRC Table
 * R403.1(1), Michigan's code in force (the 2021 Michigan Residential Code is held up by a court
 * order). Width x thickness by the roof's snow (or roof live) load, stories, slab / crawl space /
 * basement, and the soil's bearing. Checked against two published copies of the 2015 table.
 *
 * The table's notes: interpolation allowed, extrapolation not; based on a 32'-wide house with a
 * bearing centre wall; for every 2' the house is wider or narrower, add or take off 2" of width
 * and 1" of thickness, thickness never under 6".
 *
 * Light-frame only: brick veneer and masonry/concrete-walled houses have their own tables
 * (R403.1(2), R403.1(3)), not here yet. Commercial work is sized by its engineer.
 */
object FootingCode {
    val SOILS = listOf(1500f, 2000f, 2500f, 3000f, 3500f, 4000f)
    val SNOWS = listOf(20f, 30f, 50f, 70f)

    // [snow row][stories 1-3][slab, crawl, basement] -> six "WxT" for the soils above.
    private val rows: List<List<List<String>>> = listOf(
        listOf( // 20 psf
            listOf("12x6 12x6 12x6 12x6 12x6 12x6", "12x6 12x6 12x6 12x6 12x6 12x6", "18x6 14x6 12x6 12x6 12x6 12x6"),
            listOf("12x6 12x6 12x6 12x6 12x6 12x6", "16x6 12x6 12x6 12x6 12x6 12x6", "22x6 16x6 13x6 12x6 12x6 12x6"),
            listOf("14x6 12x6 12x6 12x6 12x6 12x6", "19x6 14x6 12x6 12x6 12x6 12x6", "25x8 19x6 15x6 13x6 12x6 12x6")
        ),
        listOf( // 30 psf
            listOf("12x6 12x6 12x6 12x6 12x6 12x6", "13x6 12x6 12x6 12x6 12x6 12x6", "19x6 14x6 12x6 12x6 12x6 12x6"),
            listOf("12x6 12x6 12x6 12x6 12x6 12x6", "17x6 13x6 12x6 12x6 12x6 12x6", "23x6 17x6 14x6 12x6 12x6 12x6"),
            listOf("15x6 12x6 12x6 12x6 12x6 12x6", "20x6 15x6 12x6 12x6 12x6 12x6", "26x8 20x6 16x6 13x6 12x6 12x6")
        ),
        listOf( // 50 psf
            listOf("12x6 12x6 12x6 12x6 12x6 12x6", "16x6 12x6 12x6 12x6 12x6 12x6", "21x6 16x6 13x6 12x6 12x6 12x6"),
            listOf("14x6 12x6 12x6 12x6 12x6 12x6", "19x6 14x6 12x6 12x6 12x6 12x6", "25x7 19x6 15x6 12x6 12x6 12x6"),
            listOf("17x6 13x6 12x6 12x6 12x6 12x6", "22x6 17x6 13x6 12x6 12x6 12x6", "28x9 21x6 17x6 14x6 12x6 12x6")
        ),
        listOf( // 70 psf
            listOf("12x6 12x6 12x6 12x6 12x6 12x6", "18x6 13x6 12x6 12x6 12x6 12x6", "24x7 18x6 14x6 12x6 12x6 12x6"),
            listOf("16x6 12x6 12x6 12x6 12x6 12x6", "21x6 16x6 13x6 12x6 12x6 12x6", "27x9 20x6 16x6 14x6 12x6 12x6"),
            listOf("19x6 14x6 12x6 12x6 12x6 12x6", "25x7 18x6 15x6 12x6 12x6 12x6", "30x10 23x6 18x6 15x6 13x6 12x6")
        )
    )

    private fun cell(snow: Int, stories: Int, type: Int, soil: Int): Pair<Float, Float> {
        val t = rows[snow][stories - 1][type].split(" ")[soil].split("x")
        return t[0].toFloat() to t[1].toFloat()
    }

    /** The table's size, or why there isn't one. */
    data class Result(val widthIn: Float?, val thickIn: Float?, val note: String)

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    /** Where [v] falls in [list]: the index below and the fraction to the next, clamped to the table's ends. */
    private fun place(list: List<Float>, v: Float): Pair<Int, Float> {
        if (v <= list.first()) return 0 to 0f
        if (v >= list.last()) return list.size - 2 to 1f
        val i = list.indexOfLast { it <= v }.coerceAtMost(list.size - 2)
        return i to (v - list[i]) / (list[i + 1] - list[i])
    }

    /**
     * The minimum footing for [s] (stories = first floor plus upper floors) on a house [houseWidthFt]
     * wide (the joists' way; 32' if not drawn), with the roof's [snowPsf] and the soil's bearing.
     */
    fun minimum(s: BuildingSettings, houseWidthFt: Float?): Result {
        val ed = s.codeEdition
        if (ed.footingTable == null)
            return Result(null, null, "The ${ed.short} footing table isn't in Site Draft yet; size it from Table R403.1(1) in your code book")
        val stories = 1 + s.upperFloors.size
        val snow = s.structure.roofSnowPsf
        val soil = s.structure.soilBearingPsf
        if (stories > 3) return Result(null, null, "Over 3 stories: past the code table, needs design")
        if (snow > 70f) return Result(null, null, "Snow load over 70 psf: past the code table, needs design")
        if (soil < 1500f) return Result(null, null, "Soil under 1,500 psf: the code wants a soils investigation")
        val type = when (s.foundation) { FoundationType.SLAB -> 0; FoundationType.CRAWL -> 1; FoundationType.BASEMENT -> 2 }
        val (si, sf) = place(SNOWS, snow.coerceAtLeast(20f))
        val (oi, of) = place(SOILS, soil.coerceAtMost(4000f))
        fun at(sn: Int): Pair<Float, Float> {
            val a = cell(sn, stories, type, oi); val b = cell(sn, stories, type, oi + 1)
            return lerp(a.first, b.first, of) to lerp(a.second, b.second, of)
        }
        val lo = at(si); val hi = at(si + 1)
        var w = lerp(lo.first, hi.first, sf)
        var t = lerp(lo.second, hi.second, sf)
        val hw = houseWidthFt ?: 32f
        val steps = (hw - 32f) / 2f
        w += 2f * steps
        t = (t + steps).coerceAtLeast(6f)
        val width = ceil(w.coerceAtLeast(12f) - 0.01f)
        val thick = ceil(t - 0.01f)
        val story = "$stories-story"
        val kind = when (type) { 0 -> "slab on grade"; 1 -> "crawl space"; else -> "basement" }
        return Result(width, thick,
            "${ed.short} Table R403.1(1): $story, $kind, ${snow.toInt()} psf roof, ${soil.toInt()} psf soil, ${formatFeetInches(hw)} wide house")
    }

    /** The house's width the joists' way, feet, from the plan; null if none is drawn. */
    fun houseWidthFt(state: CanvasState): Float? {
        val fp = runCatching { RoofPlan.footprint(state) }.getOrNull() ?: return null
        val k = state.inchesToPx(1f)
        if (k <= 0f) return null
        return minOf(fp.width, fp.height) / k / 12f
    }

    /**
     * What Site Draft suggests: the table's minimum, and wide enough to stick out at least 2"
     * past each face of the wall (R403.1.1), rounded up to the inch.
     */
    fun suggested(s: BuildingSettings, houseWidthFt: Float?): Pair<Float, Float>? {
        val m = minimum(s, houseWidthFt)
        val w = m.widthIn ?: return null
        val t = m.thickIn ?: return null
        return maxOf(w, ceil(Building.foundationWallIn(s) + 4f)) to t
    }
}
