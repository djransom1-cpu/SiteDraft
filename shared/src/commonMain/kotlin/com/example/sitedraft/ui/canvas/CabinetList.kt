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
        val size = FloorPlan.formatDims(area.width / state.scale.ratio, area.height / state.scale.ratio, state.unitSystem)
        return area to (if (named) first!! else "Room $size")
    }

    private fun description(s: DrawingElement.Symbol): String {
        val parts = mutableListOf(s.symbolName)
        if (PlanSymbols.hasFrontChoice(s)) parts += PlanSymbols.frontLabels.getOrElse(PlanSymbols.front(s)) { "" }
        if (PlanSymbols.isUpperCabinet(s)) PlanSymbols.upperHeightOf(s)?.let { parts += "${PlanSymbols.codeInches(it)}\" high" }
        if (PlanSymbols.hasStyleChoice(s)) parts += PlanSymbols.styleLabels.getOrElse(PlanSymbols.doorStyle(s)) { "" }
        return parts.filter { it.isNotBlank() }.joinToString(", ")
    }

    fun rows(state: CanvasState): List<Row> {
        val cabinets = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.CABINETS }
        val counted = LinkedHashMap<Triple<String, String, String>, Int>()
        for (c in cabinets) {
            val code = PlanSymbols.cabinetLabel(c, state) ?: continue
            val room = roomAt(c.center, state)?.second ?: NO_ROOM
            val key = Triple(room, code, description(c))
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
