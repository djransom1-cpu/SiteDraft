package com.example.sitedraft.ui.canvas

import kotlin.math.roundToInt

/**
 * An alcove tub as a tub/shower (Dan, Nov 25): tile up the three walls around it (84" by default) and, on the
 * valve end, the spout, the lever and the showerhead. Kept on the tub as properties; a clawfoot tub stands free
 * and gets none of this.
 */
object TubShower {
    const val ON = "tsOn"             // 1 tub/shower (default), 0 tub only
    const val TILE_H = "tsTileH"      // top of the surround tile, inches above the floor
    const val VALVE_END = "tsValveEnd" // 0 the left end as seen from the front of the tub, 1 the right end
    const val VALVE_H = "tsValveH"    // the lever, inches above the floor
    const val HEAD_H = "tsHeadH"      // the showerhead, inches above the floor
    const val SPOUT_H = "tsSpoutH"    // the tub spout, inches above the floor
    val keys = setOf(ON, TILE_H, VALVE_END, VALVE_H, HEAD_H, SPOUT_H)

    const val RIM_IN = 18f             // the tub's rim as the elevation draws it
    const val SPOUT_OVER_RIM_IN = 4f
    const val TILE_COURSE_IN = 3f
    const val TILE_LENGTH_IN = 6f

    /** Dan, Nov 25: a tub/shower valve sits lower than a shower's, about 36", the spout at 24". */
    fun defaults(): Map<String, Float> = mapOf(ON to 1f, TILE_H to 84f, VALVE_END to 0f, VALVE_H to 36f, HEAD_H to 80f, SPOUT_H to 24f)

    fun isAlcoveTub(s: DrawingElement.Symbol) = s.symbolName == "Bathtub"
    fun prop(s: DrawingElement.Symbol, key: String): Float = s.properties[key] ?: defaults()[key] ?: 0f
    fun isOn(s: DrawingElement.Symbol) = isAlcoveTub(s) && prop(s, ON) >= 0.5f

    /** Compass direction (0 north, 1 east, 2 south, 3 west) the viewer of [side] looks: where the wall is. */
    private fun looks(side: ViewerSide) = when (side) {
        ViewerSide.BELOW -> 0
        ViewerSide.LEFT -> 1
        ViewerSide.ABOVE -> 2
        ViewerSide.RIGHT -> 3
    }

    /**
     * Is the wall seen from [side] the tub's valve end? The tub's front faces south at 0 degrees, west at 90,
     * north at 180, east at 270; standing in front of it facing it, its left end is west, north, east, south.
     */
    fun valveHere(tub: DrawingElement.Symbol, side: ViewerSide): Boolean {
        val q = ((((tub.rotation % 360f) + 360f) % 360f) / 90f).roundToInt() % 4
        val left = when (q) { 0 -> 3; 1 -> 0; 2 -> 1; else -> 2 }
        val valveDir = if (prop(tub, VALVE_END) >= 0.5f) (left + 2) % 4 else left
        return looks(side) == valveDir
    }
}
