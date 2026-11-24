package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A wall's framing elevation and stud count (Dan, Nov 24 2026), drawn from the plan so it always agrees with it:
 * the bottom plate (cut out at doors), the double top plate, studs on the house layout (FramingLayout) with an end
 * stud wherever the wall starts, and at each opening a king and a jack each side, the header on the jacks (sized by
 * Dan's rule, Schedules.header; up to the plate when it fills, else cripples above it), and under a window a flat
 * rough sill with cripples below. Cripples always stay on layout. Rough openings by Dan's rules (Schedules).
 */
object WallFraming {
    private const val STUD_IN = 1.5f
    private const val PLATE_IN = 1.5f
    /** Room over the wall for the title and the header notes, inches. */
    private const val TOP_IN = 30f

    /** One opening in the wall: rough opening, inches from the wall's start (west / north end) and up from the subfloor. */
    data class Opening(val x1: Float, val x2: Float, val bottom: Float, val top: Float, val header: String, val headerDepthIn: Float, val window: Boolean, val label: String)

    /** The pieces, inches: left edges along the wall for studs, and the counts. */
    data class Frame(
        val lengthIn: Float, val heightIn: Float,
        val studs: List<Float>, val jacks: List<Pair<Float, Float>>, val kings: List<Float>,
        /** Cripples: (left edge, bottom, top). */
        val cripples: List<Triple<Float, Float, Float>>,
        val openings: List<Opening>,
        /** Wall bucks' flat studs: left edge along the wall and their width (the stud depth), inches. */
        val flats: List<Pair<Float, Float>> = emptyList()
    ) {
        val studCount get() = studs.size + flats.size
        val precutIn get() = heightIn - 3f * PLATE_IN
        /** Plates, lineal feet: one bottom (less door openings), two top. */
        val plateLf get() = (lengthIn - openings.filter { !it.window && it.bottom <= 0.01f }.sumOf { (it.x2 - it.x1).toDouble() }.toFloat() + 2f * lengthIn) / 12f
    }

    fun isFramed(wall: DrawingElement.Symbol): Boolean =
        wall.category == PlanSymbols.WALLS && Schedules.studDepthIn(wall.symbolName) != null

    private fun headerDepthIn(header: String): Float = when {
        header.contains("2x12") -> 11.25f
        header.contains("2x10") -> 9.25f
        header.contains("2x8") -> 7.25f
        header.startsWith("LVL") -> 11.875f
        else -> 9.25f
    }

    /** The wall's framing, worked out from the plan. */
    fun frame(wall: DrawingElement.Symbol, state: CanvasState): Frame {
        val inch = state.inchesToPx(1f)
        val horizontal = Elevation.isHorizontal(wall)
        val lenPx = Elevation.lengthPx(wall)
        val hw = lenPx / 2f
        val lengthIn = lenPx / inch
        val heightIn = state.building.plateHeightIn
        val plateBottom = heightIn - 2f * PLATE_IN
        val exterior = wall.symbolName == FloorPlan.EXTERIOR_WALL
        val wb = FloorPlan.bounds(wall)
        val start = if (horizontal) wb.left else wb.top
        // Openings in this wall, with their rough openings.
        val openings = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.DOORS_WINDOWS && OpeningPosition.hostWall(it, state)?.id == wall.id
        }.mapNotNull { o ->
            val ob = FloorPlan.bounds(o)
            var a1 = if (horizontal) ob.left else ob.top
            var a2 = if (horizontal) ob.right else ob.bottom
            if (PlanSymbols.isDoor(o.symbolName)) { val mid = (a1 + a2) / 2f; a1 = mid - o.size.width / 2f; a2 = mid + o.size.width / 2f }
            val widthIn = (a2 - a1) / inch
            val window = PlanSymbols.isWindow(o.symbolName)
            val h = o.properties[PlanSymbols.PROP_HEIGHT_IN] ?: if (window) 48f else PlanSymbols.DOOR_HEIGHT_IN
            val (rw, rh) = Schedules.ruleRoughOpening(o, widthIn, h, exterior)
            val roW = o.properties[Schedules.PROP_RO_W] ?: rw
            val roH = o.properties[Schedules.PROP_RO_H] ?: rh
            if (PlanSymbols.isPocketDoor(o.symbolName)) { if (PlanSymbols.pocketRight(o)) a2 += a2 - a1 else a1 -= a2 - a1 }
            val mid = ((a1 + a2) / 2f - start) / inch
            val w = if (PlanSymbols.isPocketDoor(o.symbolName)) (a2 - a1) / inch + 1f else roW
            val bottom = if (window) (o.properties[PlanSymbols.PROP_SILL_IN] ?: 36f) - (roH - h) / 2f else 0f
            val top = min(bottom + roH, plateBottom)
            val header = Schedules.header(widthIn)
            Opening(mid - w / 2f, mid + w / 2f, bottom, top, header, headerDepthIn(header), window, Schedules.mark(o) ?: "")
        }.sortedBy { it.x1 }
        // Full-height studs: as the plan lays them out (house layout, end studs, jacks and kings at openings).
        val first = wallFirstStud(wall, state, inch)
        val spans = openings.map { (it.x1 * inch) to (it.x2 * inch) }
        val bucks = FramingLayout.bucks(state)[wall.id] ?: emptyList()
        val depthPx = (Schedules.studDepthIn(wall.symbolName) ?: 3.5f) * inch
        val all = wallStudXs(hw, inch, first, spans, bucks, depthPx).map { (it + hw) / inch }.sorted()
        val flats = bucks.map { (buckFlatX(hw, inch, it, depthPx) + hw) / inch to depthPx / inch }
        val jackLefts = openings.flatMap { listOf(it.x1 - STUD_IN, it.x2) }
        val kingLefts = openings.flatMap { listOf(it.x1 - 2f * STUD_IN, it.x2 + STUD_IN) }
        fun near(a: Float, b: Float) = abs(a - b) < 0.05f
        val jacks = openings.flatMap { op -> listOf(op.x1 - STUD_IN to op.top, op.x2 to op.top) }.filter { (x, _) -> x >= -0.01f && x + STUD_IN <= lengthIn + 0.01f }
        val kings = all.filter { x -> kingLefts.any { near(it, x) } }
        val studs = all.filter { x -> jackLefts.none { near(it, x) } && kingLefts.none { near(it, x) } }
        // Cripples on layout (Dan: always on layout), inside each opening: above the header when it doesn't fill to the
        // plate, and under a window's rough sill.
        val sp = FramingLayout.SPACING_IN
        val firstIn = (first ?: 0f) / inch
        val marks = generateSequence(if (first != null) firstIn - STUD_IN / 2f else 0f) { it + sp }.takeWhile { it < lengthIn }.toList()
        val cripples = mutableListOf<Triple<Float, Float, Float>>()
        for (op in openings) {
            val inside = marks.filter { it >= op.x1 - 0.01f && it + STUD_IN <= op.x2 + 0.01f }
            val headerTop = op.top + op.headerDepthIn
            if (headerTop < plateBottom - 0.5f) inside.forEach { cripples += Triple(it, headerTop, plateBottom) }
            if (op.window && op.bottom - PLATE_IN > PLATE_IN + 0.5f) inside.forEach { cripples += Triple(it, PLATE_IN, op.bottom - PLATE_IN) }
        }
        return Frame(lengthIn, heightIn, studs, jacks, kings, cripples, openings, flats)
    }

    /** "14 studs (92 5/8"), 4 jacks, 4 kings, 9 cripples, 2 headers, 48 lf plate". */
    fun countText(f: Frame): String {
        val headers = f.openings.groupBy { it.header }.entries.joinToString(", ") { (h, l) -> "${l.size} ${h.lowercase()}" }
        val bucks = if (f.flats.isNotEmpty()) " (${f.flats.size} wall buck${if (f.flats.size > 1) "s" else ""})" else ""
        return "${f.studCount} studs (${formatInches(f.precutIn)}\")$bucks, ${f.jacks.size} jacks, ${f.kings.size} kings, ${f.cripples.size} cripples" +
            (if (headers.isNotEmpty()) ", headers: $headers" else "") + ", ${kotlin.math.ceil(f.plateLf.toDouble()).toInt()} lf plate"
    }

    fun elevationSize(wall: DrawingElement.Symbol, state: CanvasState): Size =
        Size(Elevation.lengthPx(wall), state.inchesToPx(state.building.plateHeightIn + TOP_IN))

    /**
     * The framing elevation as canvas elements; [origin] is the left end of the subfloor line. Seen from [side]: a wall
     * seen from the north or the east is drawn with its far end on the left, as you'd see it standing there.
     */
    fun buildElevation(wall: DrawingElement.Symbol, side: ViewerSide, origin: Offset, state: CanvasState, color: Color): List<DrawingElement> {
        val inch = state.inchesToPx(1f)
        val f = frame(wall, state)
        val mirror = side == ViewerSide.ABOVE || side == ViewerSide.RIGHT
        val out = mutableListOf<DrawingElement>()
        val light = 1f
        val medium = 2f
        fun x(xIn: Float) = origin.x + (if (mirror) f.lengthIn - xIn else xIn) * inch
        fun y(hIn: Float) = origin.y - hIn * inch
        fun box(x1: Float, x2: Float, h1: Float, h2: Float, sw: Float = light) {
            val ax = min(x(x1), x(x2)); val bx = max(x(x1), x(x2))
            out += DrawingElement.Rectangle(topLeft = Offset(ax, y(h2)), size = Size(bx - ax, (h2 - h1) * inch), color = color, strokeWidth = sw)
        }
        fun stud(left: Float, h1: Float, h2: Float) {
            box(left, left + STUD_IN, h1, h2)
        }
        fun text(str: String, xIn: Float, hIn: Float, sizeIn: Float = 3.5f) {
            val px = x(xIn) - str.length * sizeIn * inch * 0.27f
            out += DrawingElement.Text(text = str, topLeft = Offset(px, y(hIn)), fontSize = (sizeIn * inch).coerceIn(8f, 30f), color = color)
        }
        val plateBottom = f.heightIn - 2f * PLATE_IN
        // Plates: the bottom plate, cut out at doors; two top plates.
        var x0 = 0f
        for (op in f.openings.filter { !it.window && it.bottom <= 0.01f }) {
            if (op.x1 > x0) box(x0, op.x1, 0f, PLATE_IN, medium)
            x0 = op.x2
        }
        if (x0 < f.lengthIn) box(x0, f.lengthIn, 0f, PLATE_IN, medium)
        box(0f, f.lengthIn, plateBottom, plateBottom + PLATE_IN, medium)
        box(0f, f.lengthIn, plateBottom + PLATE_IN, f.heightIn, medium)
        // Studs and kings full height; jacks up to the header.
        for (s in f.studs + f.kings) stud(s, PLATE_IN, plateBottom)
        for ((s, top) in f.jacks) stud(s, PLATE_IN, top)
        for ((s, b, t) in f.cripples) if (t > b) stud(s, b, t)
        // Wall bucks' flat studs, seen on the face: as wide as the studs are deep.
        for ((x0, wd) in f.flats) box(x0, x0 + wd, PLATE_IN, plateBottom)
        // Openings: the header on the jacks, a rough sill under a window, the rough opening's size.
        for (op in f.openings) {
            val hTop = min(op.top + op.headerDepthIn, plateBottom)
            box(op.x1 - STUD_IN, op.x2 + STUD_IN, op.top, hTop, medium)
            text(op.header.uppercase() + " HDR", (op.x1 + op.x2) / 2f, hTop - 1f)
            if (op.window) box(op.x1, op.x2, op.bottom - PLATE_IN, op.bottom, medium)
            val ro = "${op.label} RO ${formatInches(op.x2 - op.x1)}\" x ${formatInches(op.top - op.bottom)}\"".trim()
            text(ro, (op.x1 + op.x2) / 2f, (op.top + op.bottom) / 2f + 2f)
        }
        // Subfloor line and the title with the count.
        out += DrawingElement.Line(start = Offset(x(-6f), y(0f)), end = Offset(x(f.lengthIn + 6f), y(0f)), color = color, strokeWidth = 3f)
        val name = "FRAMING ELEVATION, ${wall.symbolName.uppercase()} ${formatFeetInches(f.lengthIn / 12f)} (${side.looking.uppercase()})"
        text(name, f.lengthIn / 2f, f.heightIn + 22f, 5f)
        text("STUDS @ 16\" O.C. ON THE HOUSE LAYOUT, CRIPPLES ON LAYOUT", f.lengthIn / 2f, f.heightIn + 14f)
        text(countText(f).uppercase(), f.lengthIn / 2f, f.heightIn + 8f)
        return out
    }
}
