package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Barn doors (Dan, Nov 26-27): how long the track is and what the panel would hit when it slides open. The panel hangs
 * just off the wall face on the side the door opens to, lapping the opening [LAP_IN] each side; a single panel slides
 * its full width one way (hinge right = slides right, seen from that side), a bi-parting pair half each way.
 */
object BarnDoors {
    const val LAP_IN = 1f
    /** How far off the wall face something still gets hit by the panel: trim, a switch plate, a wall. */
    private const val FACE_BAND_IN = 4f

    /** Track length: the panel's travel plus the panel itself, so the door clears the opening (2 x (width + 2 laps)). */
    fun trackIn(widthIn: Float): Float = 2f * (widthIn + 2f * LAP_IN)

    private fun rot(v: Offset, deg: Float): Offset {
        val r = deg * kotlin.math.PI / 180.0
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    /** The stretches (local x, door's own frame, px) the panel(s) slide over when open. */
    private fun slideRanges(door: DrawingElement.Symbol, inch: Float): List<Pair<Float, Float>> {
        val hw = door.size.width / 2f
        val lap = LAP_IN * inch
        return if (PlanSymbols.isBiParting(door.symbolName)) {
            val each = hw + lap
            listOf((-hw - lap - each) to (-hw - lap), (hw + lap) to (hw + lap + each))
        } else {
            val pw = door.size.width + 2f * lap
            if (PlanSymbols.hingeRight(door)) listOf((hw + lap) to (hw + lap + pw)) else listOf((-hw - lap - pw) to (-hw - lap))
        }
    }

    /**
     * What the open panel runs into: the end of the wall, a wall meeting it on that face, another door or window in the
     * wall (a window lower than the top of the panel), a switch or outlet on that face. Empty when it slides clear.
     */
    fun warnings(door: DrawingElement.Symbol, state: CanvasState): List<String> {
        if (!PlanSymbols.isBarnDoor(door.symbolName)) return emptyList()
        val inch = state.inchesToPx(1f)
        val host = OpeningPosition.hostWall(door, state) ?: return emptyList()
        val wb = FloorPlan.bounds(host)
        val horizontal = wb.width >= wb.height
        // The side it hangs on (the door's -y) and its +x along the wall, in plan.
        val n = rot(Offset(0f, -1f), door.rotation)
        val ex = rot(Offset(1f, 0f), door.rotation)
        val sign = if (horizontal) (if (ex.x >= 0f) 1f else -1f) else (if (ex.y >= 0f) 1f else -1f)
        val towardPlus = if (horizontal) n.y > 0f else n.x > 0f
        val face = if (horizontal) (if (towardPlus) wb.bottom else wb.top) else (if (towardPlus) wb.right else wb.left)
        val band = FACE_BAND_IN * inch
        val (n1, n2) = if (towardPlus) face to face + band else face - band to face
        val ca = if (horizontal) door.center.x else door.center.y
        fun along(p: Offset) = if (horizontal) p.x else p.y
        fun across(p: Offset) = if (horizontal) p.y else p.x
        val zones = slideRanges(door, inch).map { (a, b) -> val p = ca + sign * a; val q = ca + sign * b; minOf(p, q) to maxOf(p, q) }
        val doorTopIn = (door.properties[PlanSymbols.PROP_HEIGHT_IN] ?: state.building.doorHeadIn) + 1f
        val out = LinkedHashSet<String>()
        val walls = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.WALLS && it.id != host.id && Layers.layerOf(it, state).id == Layers.layerOf(host, state).id && Remodel.status(it) != WallStatus.DEMO
        }
        fun dirWord(z: Pair<Float, Float>): String = when {
            horizontal -> if (z.first > ca) "east" else "west"
            else -> if (z.first > ca) "south" else "north"
        }
        for (z in zones) {
            // Past the end of the wall, unless another wall carries on in line with it.
            val a1 = if (horizontal) wb.left else wb.top
            val a2 = if (horizontal) wb.right else wb.bottom
            val past = (z.first < a1 - 0.5f * inch) || (z.second > a2 + 0.5f * inch)
            val carriedOn = walls.any { w ->
                val b = FloorPlan.bounds(w)
                val wh = b.width >= b.height
                wh == horizontal && (if (horizontal) b.top < face + 0.5f * inch && b.bottom > face - 0.5f * inch else b.left < face + 0.5f * inch && b.right > face - 0.5f * inch) &&
                    (if (horizontal) b.right >= z.second - inch && b.left <= a2 + inch || b.left <= z.first + inch && b.right >= a1 - inch
                     else b.bottom >= z.second - inch && b.top <= a2 + inch || b.top <= z.first + inch && b.bottom >= a1 - inch)
            }
            if (past && !carriedOn) out += "It slides ${dirWord(z)} past the end of the wall."
            // A wall meeting this one on the side the door hangs on.
            for (w in walls) {
                val b = FloorPlan.bounds(w)
                val bA1 = if (horizontal) b.left else b.top
                val bA2 = if (horizontal) b.right else b.bottom
                val bN1 = if (horizontal) b.top else b.left
                val bN2 = if (horizontal) b.bottom else b.right
                if (bA2 > z.first + 0.25f * inch && bA1 < z.second - 0.25f * inch && bN2 > n1 && bN1 < n2 && (b.width >= b.height) != horizontal)
                    out += "It slides ${dirWord(z)} into a wall."
            }
            // Other doors and windows in the same wall.
            for (o in state.elements.filterIsInstance<DrawingElement.Symbol>()) {
                if (o.id == door.id || o.category != PlanSymbols.DOORS_WINDOWS) continue
                if (OpeningPosition.hostWall(o, state)?.id != host.id) continue
                val oc = along(Elevation.openingAnchor(o))
                val half = Schedules.widthAlongPx(o) / 2f
                if (oc + half <= z.first || oc - half >= z.second) continue
                if (PlanSymbols.isWindow(o.symbolName)) {
                    val sill = o.properties[PlanSymbols.PROP_SILL_IN] ?: 36f
                    if (sill >= doorTopIn) continue
                    out += "It slides ${dirWord(z)} over a window (sill ${formatInches(sill)})."
                } else out += "It slides ${dirWord(z)} across another ${if (PlanSymbols.isDoor(o.symbolName)) "door" else "opening"}."
            }
            // Switches and outlets on that face.
            for (o in state.elements.filterIsInstance<DrawingElement.Symbol>()) {
                val nm = o.symbolName
                if (nm != "Switch" && nm != "Outlet") continue
                val p = o.center
                if (along(p) < z.first || along(p) > z.second || across(p) < n1 - 2f * inch || across(p) > n2 + 2f * inch) continue
                out += "It slides ${dirWord(z)} over ${if (nm == "Switch") "a switch" else "an outlet"}."
            }
        }
        return out.toList()
    }

    /** One line for the properties and the schedule: the track length, as a whole inch up. */
    fun trackText(door: DrawingElement.Symbol, state: CanvasState): String {
        val w = door.size.width / state.inchesToPx(1f)
        return "Track ${Schedules.feetInches(trackIn(w).roundToInt().toFloat())}"
    }
}
