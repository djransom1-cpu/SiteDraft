package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.max

/**
 * The T sheets (Dan, Nov 25, for the architect demo): the cover (T-1) with the sheet index, the
 * project data and the legend of symbols, and the general notes (T-2). Each is a table drawn live
 * from the drawing like the schedules: the index from the set, the project data from Project info
 * and Job specs, the notes from Dan's standard practice filled in with this job's numbers.
 */
object CoverSheet {

    /** One row: a label (or number) and its text; [symbol] draws a legend symbol in the label column instead. */
    data class Row(val label: String, val text: String, val symbol: LegendSymbol? = null, /** An electrical or HVAC symbol to draw (Mep). */ val mep: String? = null,
        /** A hatch swatch to draw (Nov 28: the hatches the drawing uses). */ val fill: Fill? = null)

    enum class LegendSymbol { DOOR_TAG, WINDOW_TAG, SECTION_CUT, DETAIL_BUBBLE, VIEW_TITLE, NORTH_ARROW, ROOM_DIVIDER, LEVEL_MARK }

    fun title(kind: SheetViewKind): String = when (kind) {
        SheetViewKind.SHEET_INDEX -> "SHEET INDEX"
        SheetViewKind.PROJECT_DATA -> "PROJECT DATA"
        SheetViewKind.LEGEND -> "LEGEND AND SYMBOLS"
        SheetViewKind.COVER_TITLE -> "COVER TITLE"
        SheetViewKind.PAINT_SCHEDULE -> "PAINT SCHEDULE"
        else -> "GENERAL NOTES"
    }

    fun isCover(kind: SheetViewKind) = kind == SheetViewKind.SHEET_INDEX || kind == SheetViewKind.PROJECT_DATA ||
        kind == SheetViewKind.LEGEND || kind == SheetViewKind.GENERAL_NOTES || kind == SheetViewKind.COVER_TITLE || kind == SheetViewKind.PAINT_SCHEDULE

    /** Notes read in inches under 4' (8" thick, 32" o.c.), feet and inches above (6'-8"). */
    private fun inch(v: Float): String = if (v < 48f) num(v) + "\"" else Building.elevationText(v)
    private fun num(v: Float): String = when {
        v == v.toInt().toFloat() -> v.toInt().toString()
        v * 2f == (v * 2f).toInt().toFloat() -> "${v.toInt()}-1/2".removePrefix("0-")
        else -> v.toString()
    }

    /** The T tables are drawn larger than the schedules: they are the whole sheet (Dan, Nov 25). */
    private const val BOOST = 1.4f

    fun rows(kind: SheetViewKind, state: CanvasState): List<Row> = when (kind) {
        SheetViewKind.SHEET_INDEX -> sheetIndex(state)
        SheetViewKind.PROJECT_DATA -> projectData(state)
        SheetViewKind.LEGEND -> legend() + wallLegend(state) + hatchLegend(state) + mepLegend(state)
        SheetViewKind.COVER_TITLE -> emptyList()
        SheetViewKind.PAINT_SCHEDULE -> paintSchedule(state)
        else -> generalNotes(state)
    }

    /** Every sheet in the set, in set order, as numbered. */
    fun sheetIndex(state: CanvasState): List<Row> =
        Sheets.renumbered(state.sheets, state).map { Row(it.number, it.title) }

    fun projectData(state: CanvasState): List<Row> {
        val p = state.projectInfo
        val b = state.building
        val s = b.structure
        val out = mutableListOf<Row>()
        if (p.clientName.isNotBlank()) out += Row("Client", p.clientName)
        if (p.address.isNotBlank()) out += Row("Address", p.address)
        if (p.company.isNotBlank()) out += Row("Drawings by", p.company + (if (p.drawnBy.isNotBlank()) " (${p.drawnBy})" else ""))
        else if (p.drawnBy.isNotBlank()) out += Row("Drawn by", p.drawnBy)
        if (p.startDate.isNotBlank()) out += Row("Started", p.startDate)
        out += Row("Code", b.codeEdition.label)
        out += Row("Foundation", "${b.foundation.label}, ${b.foundationWall.label} walls")
        out += Row("Floors", (listOf("First floor") + b.upperFloors.map { it.name }).joinToString(", "))
        out += Row("Design loads", "Floor ${num(s.liveLoadPsf)} psf live / ${num(s.deadLoadPsf)} psf dead; roof snow ${num(s.roofSnowPsf)} psf; soil ${num(s.soilBearingPsf)} psf")
        p.revisions.lastOrNull()?.let { out += Row("Revision", it.number + (if (it.date.isNotBlank()) ", ${it.date}" else "")) }
        return out
    }

    /**
     * The electrical and HVAC symbols the drawing uses (Dan, Nov 27), each drawn as on the plan, with what it is and, for
     * the wall boxes, the height to the bottom of the box (A.F.F., above the finished floor).
     */
    /** The wall types the plan uses, and the room divider if there is one (Nov 28: the legend card's "wall types"). */
    fun wallLegend(state: CanvasState): List<Row> {
        val walls = state.elements.filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.WALLS && !DerivedLayers.isGenerated(it) }.map { it.symbolName }.distinct().sorted()
        val rows = walls.map { Row("WALL", it) }
        val divider = state.elements.any { it is DrawingElement.Line && it.divider }
        return rows + (if (divider) listOf(Row("", "Room divider: the room and its finishes change here; not a wall", LegendSymbol.ROOM_DIVIDER)) else emptyList())
    }

    /**
     * The hatches the drawing uses (Dan's legend card, Nov 28): each pattern at each size once, drawn as a swatch, with its
     * name and size ("Tile 12\" x 24\""). Generated parts (a wing's slab, a footing's concrete) count too: they're on the sheets.
     */
    fun hatchLegend(state: CanvasState): List<Row> {
        val k = state.inchesToPx(1f).takeIf { it > 0f } ?: return emptyList()
        val fills = state.elements.mapNotNull { it.fillOrNull() }.filter { it.pattern != FillPattern.NONE && it.pattern != FillPattern.SOLID }
        fun r(v: Float) = kotlin.math.round(v * 4f) / 4f
        return fills.distinctBy { Triple(it.pattern, r(it.spacing / k), r(it.length / k)) }.sortedBy { it.pattern.ordinal }.map { f ->
            val sized = f.pattern in setOf(FillPattern.TILE, FillPattern.PLANKS, FillPattern.HERRINGBONE, FillPattern.HEXAGON, FillPattern.BRICK)
            val size = if (!sized) "" else " " + inch(r(f.spacing / k)) + (if (f.length > 0f) " x " + inch(r(f.length / k)) else "")
            Row("", f.pattern.label + size, fill = f)
        }
    }

    fun mepLegend(state: CanvasState): List<Row> {
        val used = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { Mep.isMep(it) }.map { it.symbolName }.toSet()
        return (Mep.electrical + Mep.hvac + Mep.plumbing).filter { it in used }.map { n ->
            val text = if (Mep.isWallDevice(n)) "$n, ${num(Mep.defaultMountIn(n))}\" A.F.F. to the bottom" else n
            Row("", text, mep = n)
        }
    }

    fun legend(): List<Row> = listOf(
        Row("", "Door mark: see the door schedule", LegendSymbol.DOOR_TAG),
        Row("", "Window mark: see the window schedule", LegendSymbol.WINDOW_TAG),
        Row("", "Section cut: section number over the sheet it is drawn on", LegendSymbol.SECTION_CUT),
        Row("", "Detail callout: detail number over the sheet it is drawn on", LegendSymbol.DETAIL_BUBBLE),
        Row("", "Drawing title: drawing number over this sheet's number", LegendSymbol.VIEW_TITLE),
        Row("", "Level mark: the level's name and elevation (FIRST FLOOR 100'-0\"), on the elevations and sections", LegendSymbol.LEVEL_MARK),
        Row("", "North", LegendSymbol.NORTH_ARROW),
        Row("O.C.", "On center"),
        Row("T.O.", "Top of (T.O. SUBFLOOR, T.O. PLATE)"),
        Row("TYP.", "Typical: the same wherever it occurs"),
        Row("R.O.", "Rough opening"),
        Row("OSB", "Oriented strand board"),
        Row("PT", "Pressure treated"),
        Row("W8x15", "Steel wide-flange beam: depth (in) x weight (lb/ft)"),
        Row("#4", "Rebar size in eighths of an inch (#4 = 1/2\")"),
    )

    /** Each paint colour on the walls, with the walls it goes on ("15'-0\" wall looking west"), most-used first. */
    fun paintSchedule(state: CanvasState): List<Row> {
        val paints = state.wallDecor.filter { it.kind == WallDecor.PAINT }
        if (paints.isEmpty()) return listOf(Row("—", "No walls painted yet: pick a colour on an elevation (Properties > Paint)."))
        val walls = state.elements.associateBy { it.id }
        val k = state.inchesToPx(1f)
        return paints.groupBy { it.paintLabel }.entries.sortedByDescending { it.value.size }.map { (_, list) ->
            val p = list.first()
            val where = list.joinToString(", ") { d ->
                val w = walls[d.wallId] as? DrawingElement.Symbol
                // Just its rooms' stretch of the wall when it doesn't cover the whole face.
                val len = w?.let { wall ->
                    if (d.rooms == 0) FloorPlan.bounds(wall).let { b -> maxOf(b.width, b.height) / k }
                    else Wainscot.roomSpans(wall, d.side, state).filterIndexed { i, _ -> (d.rooms shr i) and 1 == 1 }.sumOf { (a, b) -> (b - a).toDouble() }.toFloat()
                }
                "${Building.elevationText(len ?: 0f)} wall ${d.side.looking}".takeIf { len != null } ?: "wall ${d.side.looking}"
            }
            Row(p.colorCode.ifBlank { "#" + p.rgb.toString(16).padStart(6, '0').uppercase() }, "${p.colorName.ifBlank { "Custom colour" }}: $where")
        }
    }

    /** Dan's standard notes (memory: how he builds), with this job's numbers from Job specs. */
    fun generalNotes(state: CanvasState): List<Row> {
        val b = state.building
        val s = b.structure
        val bars = buildString {
            append("${b.footingBarsTop} #${b.rebarSize} top")
            if (b.footingBarsBottom > 0) append(", ${b.footingBarsBottom} #${b.rebarSize} bottom")
        }
        val notes = mutableListOf(
            "All work to the ${b.codeEdition.label} and local amendments. Contractor to verify all dimensions in the field.",
            "Design loads: floors ${num(s.liveLoadPsf)} psf live, ${num(s.deadLoadPsf)} psf dead; roof snow ${num(s.roofSnowPsf)} psf. Soil bearing ${num(s.soilBearingPsf)} psf assumed: verify (soil test where required).",
            "Footings: ${inch(b.footingWidthIn)} wide x ${inch(b.footingThicknessIn)} thick concrete, $bars continuous; bottom below frost depth.",
        )
        notes += if (b.foundationWall.poured)
            "Foundation walls: ${b.foundationWall.label} concrete on a keyway, #${b.rebarSize} vertical dowels from the footing at ${num(b.dowelSpacingIn)}\" o.c."
        else
            "Foundation walls: ${b.foundationWall.label}, #${b.rebarSize} vertical bars from the footing at ${num(b.dowelSpacingIn)}\" o.c., those cells filled solid."
        notes += "Drain tile: ${b.drainTile.label} inside and outside the footing, in stone, to a sump or daylight." +
            (if (b.foundationFoamIn > 0f) " ${inch(b.foundationFoamIn)} rigid foam on the outside of the foundation wall." else "")
        notes += "Sill: pressure-treated sill plate on sill seal; anchor bolts ${inch(b.anchorBoltEndIn)} from each end and corner, ${inch(b.anchorBoltSpacingIn)} o.c."
        notes += "Steel beam, columns and pad footings per the structural calculations; 6\" x 6\" column base plates, columns held by the slab."
        notes += "Floor framing per the framing plans. Double trimmers at every floor opening; blocking 16\" o.c. between the last joist and the rim where walls run with the joists."
        notes += "Wall framing on the same layout as the floor framing so wall cavities line up with joist bays. Headers per the door and window schedules."
        notes += "Exterior walls: 7/16\" OSB sheathing, house wrap, siding; insulation in the stud cavities; drywall inside."
        notes += "Roof: 1/2\" OSB sheathing; ice and water shield two rows (about 6') up from the eaves; framing per the roof framing plan."
        notes += "Window heads line up with the door heads (${inch(b.doorHeadIn)} above the subfloor) unless noted. Safety glazing in hazardous locations per code."
        notes += "Emergency escape and rescue openings in sleeping rooms and basements per code."
        return notes.mapIndexed { i, t -> Row("${i + 1}.", t) }
    }

    // ---- The tables ----

    private fun labelColIn(kind: SheetViewKind) = when (kind) {
        SheetViewKind.LEGEND -> 1.2f
        SheetViewKind.GENERAL_NOTES -> 0.45f
        SheetViewKind.SHEET_INDEX -> 0.8f
        SheetViewKind.PAINT_SCHEDULE -> 1.1f
        else -> 1.4f
    }

    private fun textColIn(kind: SheetViewKind) = when (kind) {
        SheetViewKind.GENERAL_NOTES -> 13.5f
        SheetViewKind.SHEET_INDEX -> 3.2f
        else -> 6.4f
    }

    private fun rowIn(kind: SheetViewKind, row: Row) = if (row.symbol != null) 0.62f else 0.3f
    private const val HEAD_IN = 0.36f

    /** The cover title's lines (Nov 25): the project's name big, then its address, then client and drawings by. */
    fun titleLines(state: CanvasState): List<String> {
        val p = state.projectInfo
        return listOfNotNull(
            state.projectName.ifBlank { "Project" }.uppercase(),
            p.address.takeIf { it.isNotBlank() },
            listOfNotNull(p.clientName.takeIf { it.isNotBlank() }?.let { "Client: $it" },
                (p.company.ifBlank { p.drawnBy }).takeIf { it.isNotBlank() }?.let { "Drawings by $it" }).joinToString("   |   ").ifBlank { null }
        )
    }

    private const val TITLE_IN = 0.75f
    private const val SUB_IN = 0.3f

    fun tableSizeIn(kind: SheetViewKind, state: CanvasState, size: Float = 1f): Size {
        if (kind == SheetViewKind.COVER_TITLE) {
            val lines = titleLines(state)
            val w = maxOf(lines[0].length * TITLE_IN * 0.62f, lines.drop(1).maxOfOrNull { it.length * SUB_IN * 0.55f } ?: 0f, 6f)
            return Size(w * size, (TITLE_IN * 1.35f + (lines.size - 1) * SUB_IN * 1.5f + 0.3f) * size)
        }
        val rows = rows(kind, state)
        return Size((labelColIn(kind) + textColIn(kind)) * size * BOOST, (HEAD_IN + rows.sumOf { rowIn(kind, it).toDouble() }.toFloat()) * size * BOOST)
    }

    fun DrawScope.drawCoverTable(kind: SheetViewKind, state: CanvasState, topLeft: Offset, dpi: Float, size: Float = 1f) {
        if (kind == SheetViewKind.COVER_TITLE) {
            val lines = titleLines(state)
            val k = dpi * size
            val w = tableSizeIn(kind, state, size).width * dpi
            drawPageText(lines[0], topLeft, TITLE_IN * k, Color.Black, bold = true, maxWidth = w + k)
            var y = topLeft.y + TITLE_IN * 1.35f * k
            drawLine(Color.Black, Offset(topLeft.x, y - 0.08f * k), Offset(topLeft.x + w, y - 0.08f * k), strokeWidth = max(1f, dpi / 60f))
            for (t in lines.drop(1)) {
                drawPageText(t, Offset(topLeft.x, y), SUB_IN * k, Color.Black, bold = false, maxWidth = w + k)
                y += SUB_IN * 1.5f * k
            }
            return
        }
        val rows = rows(kind, state)
        val k = dpi * size * BOOST
        val black = Color.Black
        val line = max(1f, dpi / 120f)
        val lw = labelColIn(kind) * k
        val w = (labelColIn(kind) + textColIn(kind)) * k
        val h = tableSizeIn(kind, state, size).height * dpi
        drawRect(black, topLeft, Size(w, h), style = Stroke(line * 1.5f))
        val text = 0.11f * k
        drawPageText(title(kind), Offset(topLeft.x + 0.08f * k, topLeft.y + (HEAD_IN * k - text * 1.2f) / 2f), text * 1.1f, black, bold = true, maxWidth = w)
        var y = topLeft.y + HEAD_IN * k
        drawLine(black, Offset(topLeft.x, y), Offset(topLeft.x + w, y), strokeWidth = line * 1.5f)
        for (r in rows) {
            val rh = rowIn(kind, r) * k
            if (r.symbol != null) drawSymbol(r.symbol, Offset(topLeft.x + lw / 2f, y + rh / 2f), rh * 0.8f, line)
            else if (r.mep != null) drawMep(r.mep, Offset(topLeft.x + lw / 2f, y + rh / 2f), rh * 0.8f, line)
            else if (r.fill != null) drawSwatch(r.fill, Offset(topLeft.x + lw / 2f, y + rh / 2f), rh * 0.8f, line)
            else drawPageText(r.label, Offset(topLeft.x + 0.08f * k, y + (rh - text * 1.2f) / 2f), text * 0.95f, black, bold = true, maxWidth = lw - 0.1f * k)
            drawPageText(r.text, Offset(topLeft.x + lw + 0.08f * k, y + (rh - text * 1.2f) / 2f), text * 0.95f, black, bold = false, maxWidth = w - lw - 0.12f * k)
            y += rh
            drawLine(black, Offset(topLeft.x, y), Offset(topLeft.x + w, y), strokeWidth = line)
        }
        drawLine(black, Offset(topLeft.x + lw, topLeft.y + HEAD_IN * k), Offset(topLeft.x + lw, topLeft.y + h), strokeWidth = line)
    }

    /** A hatch swatch [d] tall at [c]: the pattern at a size that shows a few repeats, in a box. */
    private fun DrawScope.drawSwatch(fill: Fill, c: Offset, d: Float, line: Float) {
        val box = androidx.compose.ui.geometry.Rect(c.x - d, c.y - d * 0.45f, c.x + d, c.y + d * 0.45f)
        val s = box.height / 3.5f
        val ratio = s / fill.spacing.coerceAtLeast(0.01f)
        val f = fill.copy(spacing = s, length = fill.length * ratio, lineWidth = line * 0.8f, color = Color.Black)
        val path = Path().apply { addRect(box) }
        drawShapeFill(path, box, f, Color.Black)
        drawRect(Color.Black, box.topLeft, box.size, style = Stroke(line))
    }

    /** An electrical or HVAC symbol fitted in a [d] square at [c], drawn as the plan draws it. */
    private fun DrawScope.drawMep(name: String, c: Offset, d: Float, line: Float) {
        val fp = PlanSymbols.footprintInches[name] ?: return
        val inch = d / maxOf(fp.first, fp.second)
        val sym = DrawingElement.Symbol(symbolName = name, category = if (name in Mep.hvac) Mep.HVAC else Mep.ELECTRICAL, color = Color.Black,
            center = Offset.Zero, size = Size(fp.first * inch, fp.second * inch))
        translate(c.x, c.y) {
            with(Mep) { drawMepPlan(sym, sym.size.width, sym.size.height, inch, Color.Black, Color.Black, Color.Gray, Stroke(line * 1.2f), Stroke(line * 0.7f)) }
        }
    }

    /** A legend symbol [d] tall, centred on [c] (page px), drawn as the drawing draws it. */
    private fun DrawScope.drawSymbol(sym: LegendSymbol, c: Offset, d: Float, line: Float) {
        val black = Color.Black
        val r = d * 0.32f
        val ts = r * 0.9f
        fun centred(t: String, cy: Float, size: Float = ts) {
            val tw = size * 0.6f * t.length
            drawPageText(t, Offset(c.x - tw / 2f, cy - size * 0.62f), size, black, bold = true, maxWidth = tw + size)
        }
        when (sym) {
            LegendSymbol.DOOR_TAG -> {
                drawCircle(black, radius = r, center = c, style = Stroke(line * 1.2f)); centred("1", c.y)
            }
            LegendSymbol.WINDOW_TAG -> {
                drawRect(black, topLeft = c - Offset(r, r), size = Size(2 * r, 2 * r), style = Stroke(line * 1.2f)); centred("1", c.y)
            }
            LegendSymbol.SECTION_CUT, LegendSymbol.DETAIL_BUBBLE, LegendSymbol.VIEW_TITLE -> {
                val bc = if (sym == LegendSymbol.SECTION_CUT) Offset(c.x + d * 0.35f, c.y) else c
                if (sym == LegendSymbol.SECTION_CUT) {
                    // The cut line runs into the bubble; the triangle points the way the section looks.
                    drawLine(black, Offset(c.x - d * 0.75f, c.y), Offset(bc.x - r, c.y), strokeWidth = line * 2.5f)
                    val tri = Path().apply {
                        moveTo(bc.x - r * 0.75f, c.y - r * 0.66f); lineTo(bc.x + r * 0.75f, c.y - r * 0.66f); lineTo(bc.x, c.y - r * 1.55f); close()
                    }
                    drawPath(tri, black)
                }
                drawCircle(Color.White, radius = r, center = bc)
                drawCircle(black, radius = r, center = bc, style = Stroke(line * 1.5f))
                drawLine(black, Offset(bc.x - r, bc.y), Offset(bc.x + r, bc.y), strokeWidth = line)
                val small = r * 0.55f
                fun at(t: String, cy: Float) {
                    val tw = small * 0.6f * t.length
                    drawPageText(t, Offset(bc.x - tw / 2f, cy - small * 0.62f), small, black, bold = true, maxWidth = 2f * r)
                }
                at("1", bc.y - r * 0.42f)
                at("S-4", bc.y + r * 0.46f)
                if (sym == LegendSymbol.VIEW_TITLE) {
                    drawLine(black, Offset(bc.x + r, bc.y + r * 0.2f), Offset(bc.x + d * 0.95f, bc.y + r * 0.2f), strokeWidth = line * 1.5f)
                }
            }
            LegendSymbol.LEVEL_MARK -> {
                // As the exterior elevations draw it: a short line at the level, its name and elevation beside it.
                drawLine(black, Offset(c.x - d * 0.75f, c.y), Offset(c.x - d * 0.3f, c.y), strokeWidth = line * 1.2f)
                val small = r * 0.5f
                drawPageText("100'-0\"", Offset(c.x - d * 0.25f, c.y - small * 0.62f), small, black, bold = true, maxWidth = d)
            }
            LegendSymbol.ROOM_DIVIDER -> {
                // One dashed line, as on the plan.
                var x = c.x - d * 0.4f
                while (x < c.x + d * 0.4f) {
                    drawLine(black, Offset(x, c.y), Offset(minOf(x + d * 0.1f, c.x + d * 0.4f), c.y), strokeWidth = line * 1.2f)
                    x += d * 0.16f
                }
            }
            LegendSymbol.NORTH_ARROW -> {
                drawCircle(black, radius = r, center = c, style = Stroke(line * 1.2f))
                val arrow = Path().apply {
                    moveTo(c.x, c.y - r * 1.25f); lineTo(c.x + r * 0.45f, c.y + r * 0.6f); lineTo(c.x, c.y + r * 0.25f); lineTo(c.x - r * 0.45f, c.y + r * 0.6f); close()
                }
                drawPath(arrow, black)
                centred("N", c.y - r * 1.75f, ts * 0.8f)
            }
        }
    }
}
