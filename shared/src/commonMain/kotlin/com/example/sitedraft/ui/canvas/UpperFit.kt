package com.example.sitedraft.ui.canvas

import kotlin.math.max
import kotlin.math.min

/**
 * Uppers over a sink or a cooktop come out shorter by themselves (Dan, Nov 25). The top stays on the line with
 * the other uppers; the bottom is set by the clearance below it:
 *
 * - Over a cooktop or range: at least 24" from the cooking surface to the bottom of the hood, then the hood
 *   itself (its own height, e.g. 10"), then the cabinet.
 * - Over a sink: the cabinet bottom 36" above the counter.
 *
 * Set as the cabinet's own height, marked automatic so it follows when things move; typing a height in
 * Properties makes it the user's and it is left alone from then on.
 */
object UpperFit {
    const val AUTO = "upperAuto"
    const val COUNTER_IN = 36f
    const val UPPER_BOTTOM_IN = 54f
    const val UPPER_HEIGHT_IN = 30f
    const val COOKTOP_TO_HOOD_IN = 24f
    const val SINK_TO_UPPER_IN = 36f
    /** A hood's own height when it doesn't say (Dan's example). */
    const val HOOD_HEIGHT_IN = 10f
    const val SLIM_HOOD_HEIGHT_IN = 6f
    const val MIN_CABINET_IN = 6f

    /** What an upper hangs over, and the bottom of the cabinet that leaves (inches off the floor); null = nothing. */
    fun fittedBottom(upper: DrawingElement.Symbol, state: CanvasState): Float? {
        val ub = FloorPlan.bounds(upper)
        val inch = state.inchesToPx(1f)
        val alongX = ub.width >= ub.height
        // Reach out into the room from the upper's footprint: a sink or cooktop sits in the counter in front of it.
        val reach = ub.inflate(16f * inch)
        fun under(s: DrawingElement.Symbol): Boolean {
            if (s.id == upper.id) return false
            val b = FloorPlan.bounds(s)
            val c = b.center
            if (!reach.contains(c)) return false
            val (a1, a2) = if (alongX) ub.left to ub.right else ub.top to ub.bottom
            val (b1, b2) = if (alongX) b.left to b.right else b.top to b.bottom
            val overlap = min(a2, b2) - max(a1, b1)
            return overlap >= 0.5f * min(a2 - a1, b2 - b1)
        }
        val syms = state.elements.filterIsInstance<DrawingElement.Symbol>()
        val hood = syms.firstOrNull { it.category == PlanSymbols.HOODS && under(it) }
        if (hood != null) {
            val h = if (Appliances.hoodKind(hood.symbolName) == 1) SLIM_HOOD_HEIGHT_IN else HOOD_HEIGHT_IN
            return max(Appliances.prop(hood, Appliances.HOOD_BOTTOM), COUNTER_IN + COOKTOP_TO_HOOD_IN) + h
        }
        if (syms.any { (Appliances.isCooktop(it.symbolName) || Appliances.isRange(it.symbolName)) && under(it) })
            return COUNTER_IN + COOKTOP_TO_HOOD_IN + HOOD_HEIGHT_IN
        if (syms.any { it.category == PlanSymbols.FIXTURES && it.symbolName in PlanSymbols.counterSinks && under(it) })
            return COUNTER_IN + SINK_TO_UPPER_IN
        return null
    }

    /** The uppers' heights brought up to date: automatic ones follow what they hang over; ones typed by hand stay. */
    fun sync(state: CanvasState): CanvasState {
        val top = UPPER_BOTTOM_IN + UPPER_HEIGHT_IN
        var changed = false
        val els = state.elements.map { e ->
            if (e !is DrawingElement.Symbol || !PlanSymbols.isUpperCabinet(e)) return@map e
            val manual = e.properties[PlanSymbols.PROP_UPPER_HEIGHT_IN] != null && e.properties[AUTO] == null
            if (manual) return@map e
            val bottom = fittedBottom(e, state)
            val want = bottom?.let { (top - it).coerceAtLeast(MIN_CABINET_IN) }
            val now = if (e.properties[AUTO] != null) e.properties[PlanSymbols.PROP_UPPER_HEIGHT_IN] else null
            if (want == now) return@map e
            changed = true
            if (want == null) e.copy(properties = e.properties - PlanSymbols.PROP_UPPER_HEIGHT_IN - AUTO)
            else e.copy(properties = e.properties + (PlanSymbols.PROP_UPPER_HEIGHT_IN to want) + (AUTO to 1f))
        }
        return if (changed) state.copy(elements = els) else state
    }
}
