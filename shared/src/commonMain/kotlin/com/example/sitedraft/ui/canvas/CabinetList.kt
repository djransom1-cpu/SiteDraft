package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The cabinets in the drawing, counted by room and shop code (see [PlanSymbols.cabinetLabel]):
 * "Kitchen: 2 x B24, 1 x SB36, 3 x W3030...". A cabinet's room is the area its centre lies in
 * (see [FloorPlan.areaAt]); the room's name is the first line of the room label inside that area.
 */
object CabinetList {
    data class Row(val room: String, val code: String, val description: String, val qty: Int)

    const val NO_ROOM = "Not in a room"

    /** Order the codes are listed in within a room: bases, uppers, tall, bath, island, fillers. */
    private val codeOrder = listOf("B", "BD", "SB", "LS", "W", "WDC", "P", "V", "VD", "LT", "ISL", "BF", "WF")

    private fun prefix(code: String) = code.takeWhile { it.isLetter() }

    /** The room [p] is in: the area around it and the name on the room label inside that area. */
    fun roomAt(p: Offset, state: CanvasState): Pair<Rect, String>? {
        val area = FloorPlan.areaAt(p, state) ?: return null
        val label = state.elements.filterIsInstance<DrawingElement.Text>()
            .firstOrNull { area.contains(it.topLeft + Offset(1f, 1f)) && it.text.isNotBlank() }
        val first = label?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        // A label that is only a size ("12' x 10'") names no room.
        val named = first != null && first.any { it.isLetter() } && !Regex("""^[\d\s.'"x×m-]+$""").matches(first)
        if (named) return area to first!!
        // No label: the name the room has in the finish schedule (Nov 26), so every schedule names it alike.
        state.roomFinishes.firstOrNull { it.name.isNotBlank() && area.contains(FinishSchedule.point(it)) }?.let { return area to it.name }
        val size = FloorPlan.formatDims(area.width / state.scale.ratio, area.height / state.scale.ratio, state.unitSystem)
        return area to "Room $size"
    }

    /** [p] lies on [s] as drawn (turned with it). */
    private fun hits(s: DrawingElement.Symbol, p: Offset): Boolean {
        val local = rotateAbout(p, s.center, -s.rotation) - s.center
        return kotlin.math.abs(local.x) <= s.size.width / 2f && kotlin.math.abs(local.y) <= s.size.height / 2f
    }

    /** Things at the same level as [c] that close off its side: what is beside a base, or beside an upper. */
    private fun closesSide(c: DrawingElement.Symbol, other: DrawingElement.Symbol): Boolean {
        if (other.id == c.id) return false
        val upper = PlanSymbols.isUpperCabinet(c)
        return when (other.category) {
            PlanSymbols.WALLS -> true
            PlanSymbols.CABINETS -> {
                val otherUpper = PlanSymbols.isUpperCabinet(other)
                val tall = PlanSymbols.isTallCabinet(other.symbolName)
                if (upper) otherUpper || tall else !otherUpper
            }
            PlanSymbols.HOODS -> upper
            PlanSymbols.APPLIANCES -> if (upper) Appliances.isWallOven(other.symbolName) || Appliances.isFridge(other.symbolName)
                else !Appliances.isOverlay(other)
            PlanSymbols.FIXTURES, PlanSymbols.OUTDOOR, PlanSymbols.SHOWERS, PlanSymbols.STAIRS -> !upper
            else -> false
        }
    }

    /**
     * Which ends of [c] are open to the room and need finishing, as you face its front: left,
     * right. An end counts as open when nothing at its level (a cabinet, an appliance, a wall)
     * stands against it. Islands are finished all round and fillers are not finished; both null.
     */
    fun finishedEnds(c: DrawingElement.Symbol, state: CanvasState): Pair<Boolean, Boolean>? {
        if (c.category != PlanSymbols.CABINETS || PlanSymbols.isIsland(c) || PlanSymbols.isFiller(c.symbolName)) return null
        val inch = state.inchesToPx(1f)
        val others = Layers.onLevelOf(c, state).filterIsInstance<DrawingElement.Symbol>().filter { closesSide(c, it) }
        val hw = c.size.width / 2f
        val hh = c.size.height / 2f
        fun open(sign: Float): Boolean {
            // Three points just past that end, along the cabinet's depth.
            val covered = listOf(-0.6f, 0f, 0.6f).count { f ->
                val world = rotateAbout(c.center + Offset(sign * (hw + inch), f * hh), c.center, c.rotation)
                others.any { o -> if (o.category == PlanSymbols.WALLS) FloorPlan.bounds(o).contains(world) else hits(o, world) }
            }
            return covered < 2
        }
        // Facing the front (the symbol's bottom edge), the left end is its -x side.
        return open(-1f) to open(1f)
    }

    private fun finishNote(ends: Pair<Boolean, Boolean>?): String? = when (ends) {
        true to true -> "finish both ends"
        true to false -> "finish left end"
        false to true -> "finish right end"
        else -> null
    }

    private fun description(s: DrawingElement.Symbol, state: CanvasState): String {
        // Its real size and kind, not the library entry it was placed from (Nov 27).
        val parts = mutableListOf(PlanSymbols.cabinetName(s, state) ?: s.symbolName)
        if (PlanSymbols.hasFrontChoice(s)) parts += PlanSymbols.frontLabels.getOrElse(PlanSymbols.front(s)) { "" }
        if (PlanSymbols.isUpperCabinet(s)) PlanSymbols.upperHeightOf(s)?.let { parts += "${PlanSymbols.codeInches(it)}\" high" }
        if (PlanSymbols.hasStyleChoice(s)) parts += PlanSymbols.styleLabels.getOrElse(PlanSymbols.doorStyle(s)) { "" }
        finishNote(finishedEnds(s, state))?.let { parts += it }
        return parts.filter { it.isNotBlank() }.joinToString(", ")
    }

    fun rows(state: CanvasState): List<Row> {
        val cabinets = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.CABINETS }
        val counted = LinkedHashMap<Triple<String, String, String>, Int>()
        for (c in cabinets) {
            val code = PlanSymbols.cabinetLabel(c, state) ?: continue
            // On the cabinet's own floor, whichever layer is active (rooms are found on the active level).
            val room = roomAt(c.center, state.copy(activeLayerId = Layers.layerOf(c, state).id))?.second ?: NO_ROOM
            val key = Triple(room, code, description(c, state))
            counted[key] = (counted[key] ?: 0) + 1
        }
        return counted.map { (k, n) -> Row(k.first, k.second, k.third, n) }
            .sortedWith(compareBy<Row>({ it.room == NO_ROOM }, { it.room }, { codeOrder.indexOf(prefix(it.code)).let { i -> if (i < 0) 99 else i } },
                { it.code.drop(prefix(it.code).length).takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }, { it.code }))
    }

    /** Tab-separated, one row per line, for pasting into Excel. */
    fun csv(rows: List<Row>): String = buildString {
        append("Room\tCode\tQty\tDescription\n")
        for (r in rows) append("${r.room}\t${r.code}\t${r.qty}\t${r.description}\n")
        val total = rows.sumOf { it.qty }
        append("\tTotal\t$total\t\n")
    }
}
