package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.sitedraft.util.formatFixed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The drawing as an AutoCAD DXF at real size: inches for an imperial project, millimetres for a
 * metric one ($INSUNITS says which), Y up the way CAD expects (the canvas's Y grows down the
 * screen), and each kind of thing on its own layer so it can be frozen or recoloured in AutoCAD.
 *
 * Written as DXF R12 (AC1009): the oldest format every CAD program still reads, with no handles
 * or object dictionaries to get wrong. That means POLYLINE rather than LWPOLYLINE and TEXT
 * rather than MTEXT; AutoCAD converts both when the drawing is saved as a DWG.
 *
 * Plan symbols are written as the lines the canvas draws for them (outline, door swing, window
 * glass line, cabinet face...), and wall lines are broken where a door, window or opening sits
 * in them, as the canvas blanks them. Fills and hatches, images and PDFs are not exported.
 */
object DxfExport {
    const val DRAWING = "DRAWING"
    const val WALLS = "WALLS"
    const val FRAMING = "WALLS-FRAMING"
    const val DOORS_WINDOWS = "DOORS-WINDOWS"
    const val CABINETS = "CABINETS"
    const val UPPER_CABINETS = "CABINETS-UPPER"
    const val FIXTURES = "FIXTURES"
    const val FURNITURE = "FURNITURE"
    const val SYMBOLS = "SYMBOLS"
    const val DIMENSIONS = "DIMENSIONS"
    const val TEXT = "TEXT"
    const val ELEVATIONS = "ELEVATIONS"

    const val CONTINUOUS = "CONTINUOUS"
    const val DASHED = "DASHED"

    /** A layer: name, AutoCAD colour index, linetype. */
    data class Layer(val name: String, val color: Int, val linetype: String = CONTINUOUS)

    val layers = listOf(
        Layer("0", 7),
        Layer(DRAWING, 7),
        Layer(WALLS, 7),
        Layer(FRAMING, 8),
        Layer(DOORS_WINDOWS, 3),
        Layer(CABINETS, 4),
        // Uppers hang above the plan's cut plane, so they are dashed, as on the canvas.
        Layer(UPPER_CABINETS, 4, DASHED),
        Layer(FIXTURES, 6),
        Layer(FURNITURE, 30),
        Layer(SYMBOLS, 5),
        Layer(DIMENSIONS, 1),
        Layer(TEXT, 2),
        Layer(ELEVATIONS, 7)
    )

    /** $INSUNITS: 1 inches, 4 millimetres. */
    fun insUnits(state: CanvasState) = if (state.unitSystem == UnitSystem.METRIC) 4 else 1

    /** DXF units (inches or mm) per canvas world unit. */
    fun unitsPerPx(state: CanvasState): Double =
        if (state.unitSystem == UnitSystem.METRIC) 1000.0 / state.scale.ratio else 12.0 / state.scale.ratio

    /** The layer a symbol goes on. */
    fun layerFor(symbol: DrawingElement.Symbol): String = when {
        symbol.category == PlanSymbols.WALLS -> WALLS
        symbol.category == PlanSymbols.DOORS_WINDOWS -> DOORS_WINDOWS
        symbol.category == PlanSymbols.CABINETS && symbol.symbolName.startsWith("Upper") -> UPPER_CABINETS
        symbol.category == PlanSymbols.CABINETS || symbol.category == PlanSymbols.CLOSET -> CABINETS
        symbol.category == PlanSymbols.FIXTURES || symbol.category == PlanSymbols.OUTDOOR -> FIXTURES
        symbol.category == PlanSymbols.FURNITURE -> FURNITURE
        else -> SYMBOLS
    }

    /** The whole DXF file. */
    fun export(state: CanvasState): String {
        val metric = state.unitSystem == UnitSystem.METRIC
        val k = unitsPerPx(state)
        val entities = DxfWriter(k)
        val blanks = state.elements.filterIsInstance<DrawingElement.Symbol>().mapNotNull { openingBlank(it, state) }
        state.elements.forEach { writeElement(entities, it, null, state, blanks) }

        // Real-size defaults for an empty drawing: 10' or 3 m square.
        val ext = entities.extents() ?: if (metric) Extents(0.0, 0.0, 3000.0, 3000.0) else Extents(0.0, 0.0, 120.0, 120.0)
        val out = DxfWriter(k)
        out.section("HEADER") {
            out.variable("\$ACADVER") { out.code(1, "AC1009") }
            out.variable("\$INSBASE") { out.point(10, 0.0, 0.0) }
            out.variable("\$EXTMIN") { out.point(10, ext.minX, ext.minY) }
            out.variable("\$EXTMAX") { out.point(10, ext.maxX, ext.maxY) }
            out.variable("\$LIMMIN") { out.num(10, ext.minX); out.num(20, ext.minY) }
            out.variable("\$LIMMAX") { out.num(10, ext.maxX); out.num(20, ext.maxY) }
            out.variable("\$INSUNITS") { out.code(70, insUnits(state).toString()) }
            out.variable("\$MEASUREMENT") { out.code(70, if (metric) "1" else "0") }
            // Architectural (feet and inches to 1/16") or decimal millimetres.
            out.variable("\$LUNITS") { out.code(70, if (metric) "2" else "4") }
            out.variable("\$LUPREC") { out.code(70, if (metric) "1" else "4") }
            out.variable("\$LTSCALE") { out.num(40, 1.0) }
            out.variable("\$TEXTSIZE") { out.num(40, if (metric) 150.0 else 6.0) }
            out.variable("\$TEXTSTYLE") { out.code(7, "STANDARD") }
            out.variable("\$CLAYER") { out.code(8, "0") }
        }
        out.section("TABLES") {
            out.table("VPORT", 1) {
                // Open on the whole drawing with a little margin around it.
                val aspect = 1.6
                val height = max(ext.maxY - ext.minY, (ext.maxX - ext.minX) / aspect) * 1.1
                out.code(0, "VPORT"); out.code(2, "*ACTIVE"); out.code(70, "0")
                out.num(10, 0.0); out.num(20, 0.0); out.num(11, 1.0); out.num(21, 1.0)
                out.num(12, (ext.minX + ext.maxX) / 2); out.num(22, (ext.minY + ext.maxY) / 2)
                out.num(13, 0.0); out.num(23, 0.0)
                val grid = if (metric) 1000.0 else 12.0
                out.num(14, grid); out.num(24, grid); out.num(15, grid); out.num(25, grid)
                out.num(16, 0.0); out.num(26, 0.0); out.num(36, 1.0)
                out.num(17, 0.0); out.num(27, 0.0); out.num(37, 0.0)
                out.num(40, if (height > 0.0) height else 1.0); out.num(41, aspect); out.num(42, 50.0)
                out.num(43, 0.0); out.num(44, 0.0); out.num(50, 0.0); out.num(51, 0.0)
                out.code(71, "0"); out.code(72, "100"); out.code(73, "1"); out.code(74, "3")
                out.code(75, "0"); out.code(76, "0"); out.code(77, "0"); out.code(78, "0")
            }
            out.table("LTYPE", 2) {
                out.code(0, "LTYPE"); out.code(2, CONTINUOUS); out.code(70, "0"); out.code(3, "Solid line")
                out.code(72, "65"); out.code(73, "0"); out.num(40, 0.0)
                // 3" dash, 2" gap (75 / 50 mm), the canvas's upper-cabinet dash at real size.
                val dash = if (metric) 75.0 else 3.0
                val gap = if (metric) 50.0 else 2.0
                out.code(0, "LTYPE"); out.code(2, DASHED); out.code(70, "0"); out.code(3, "Dashed __ __ __")
                out.code(72, "65"); out.code(73, "2"); out.num(40, dash + gap); out.num(49, dash); out.num(49, -gap)
            }
            out.table("LAYER", layers.size) {
                layers.forEach { layer ->
                    out.code(0, "LAYER"); out.code(2, layer.name); out.code(70, "0")
                    out.code(62, layer.color.toString()); out.code(6, layer.linetype)
                }
            }
            out.table("STYLE", 1) {
                out.code(0, "STYLE"); out.code(2, "STANDARD"); out.code(70, "0"); out.num(40, 0.0)
                out.num(41, 1.0); out.num(50, 0.0); out.code(71, "0"); out.num(42, if (metric) 150.0 else 6.0)
                out.code(3, "txt"); out.code(4, "")
            }
        }
        out.section("ENTITIES") { out.raw(entities.text()) }
        out.code(0, "EOF")
        return out.text()
    }

    // ---- elements ----

    private fun writeElement(
        w: DxfWriter, element: DrawingElement, forcedLayer: String?, state: CanvasState, blanks: List<Blank>
    ) {
        // The same turn the renderer gives the element, about the same pivot.
        val pivot = element.rotationPivot() ?: Offset.Zero
        val rotation = element.rotation
        fun pen(layer: String) = Pen(w, forcedLayer ?: layer, rotation) { if (rotation == 0f) it else rotateAbout(it, pivot, rotation) }
        when (element) {
            is DrawingElement.Line -> pen(DRAWING).line(element.start, element.end)
            is DrawingElement.Rectangle -> pen(DRAWING).rect(element.topLeft, element.size)
            is DrawingElement.Circle -> pen(DRAWING).circle(element.center, element.radius)
            is DrawingElement.Oval -> pen(DRAWING).oval(element.topLeft, element.size)
            is DrawingElement.Triangle -> {
                val (a, b, c) = element.getVertices()
                pen(DRAWING).poly(listOf(a, b, c), closed = true)
            }
            is DrawingElement.Freehand -> if (element.points.size >= 2) pen(DRAWING).poly(element.points.map { it.offset }, closed = false)
            is DrawingElement.Symbol -> writeSymbol(w, element, forcedLayer, state, blanks)
            is DrawingElement.Dimension -> writeDimension(pen(DIMENSIONS), element, state)
            is DrawingElement.Text -> writeText(pen(TEXT), element)
            is DrawingElement.Dxf -> element.elements.forEach { writeElement(w, it.move(element.topLeft), forcedLayer, state, blanks) }
            is DrawingElement.ElevationView ->
                Elevation.partsOf(element, state).forEach { writeElement(w, it, forcedLayer ?: ELEVATIONS, state, emptyList()) }
            is DrawingElement.Image, is DrawingElement.Pdf -> {}
        }
    }

    private fun writeDimension(p: Pen, d: DrawingElement.Dimension, state: CanvasState) {
        val metric = state.unitSystem == UnitSystem.METRIC
        val k = p.w.k
        // Real-size annotation: 6" text (1/8" on paper at 1/4" = 1'-0"), 150 mm metric.
        val textHeight = if (metric) 150.0 else 6.0
        val tick = ((if (metric) 50.0 else 2.0) / k).toFloat()
        val gap = ((if (metric) 50.0 else 2.0) / k).toFloat()
        val overshoot = ((if (metric) 75.0 else 3.0) / k).toFloat()
        val lift = ((if (metric) 75.0 else 3.0) / k).toFloat()
        val (dimStart, dimEnd) = d.dimLineEndpoints()

        fun extension(truePoint: Offset, dimPoint: Offset) {
            val v = dimPoint - truePoint
            val len = v.getDistance()
            if (len > 0.5f) {
                val u = v / len
                p.line(truePoint + u * min(gap, len * 0.4f), dimPoint + u * overshoot)
            }
        }
        extension(d.start, dimStart)
        extension(d.end, dimEnd)
        p.line(dimStart, dimEnd)

        val dist = (dimEnd - dimStart).getDistance()
        if (dist > 1f) {
            val dir = (dimEnd - dimStart) / dist
            val slant = (dir + Offset(-dir.y, dir.x)) * (tick * 0.7071f)
            p.line(dimStart - slant, dimStart + slant)
            p.line(dimEnd - slant, dimEnd + slant)
        }

        var angle = (atan2((dimEnd.y - dimStart.y).toDouble(), (dimEnd.x - dimStart.x).toDouble()) * 180.0 / PI).toFloat()
        if (angle > 90f) angle -= 180f
        if (angle < -90f) angle += 180f
        val rad = angle * PI / 180.0
        val at = (dimStart + dimEnd) / 2f + Offset(sin(rad).toFloat(), -cos(rad).toFloat()) * lift
        p.text(formatDimension(d.value, d.unit, d.format), at, textHeight, angle, hAlign = 1, vAlign = 1)
    }

    private fun writeText(p: Pen, t: DrawingElement.Text) {
        val k = p.w.k
        val prefix: (Int) -> String = when (t.listType) {
            TextListType.BULLET -> { _ -> "• " }
            TextListType.NUMBER -> { n -> "${n + 1}. " }
            else -> { _ -> "" }
        }
        // Same anchors as drawTextElement; the baseline sits about 0.95 em below the top of each
        // 1.2 em line, and capitals are about 0.7 em tall (what DXF text height measures).
        val (x, hAlign) = when (t.textAlign) {
            "Center" -> t.topLeft.x + 50f to 1
            "Right" -> t.topLeft.x + 100f to 2
            else -> t.topLeft.x to 0
        }
        t.text.lines().forEachIndexed { n, line ->
            if (line.isBlank() && t.listType == TextListType.NONE) return@forEachIndexed
            val baseline = t.topLeft.y + t.fontSize * (0.95f + 1.2f * n)
            p.text(prefix(n) + line, Offset(x, baseline), t.fontSize * 0.7 * k, 0f, hAlign = hAlign, vAlign = 0)
        }
    }

    // ---- symbols ----

    private fun writeSymbol(
        w: DxfWriter, s: DrawingElement.Symbol, forcedLayer: String?, state: CanvasState, blanks: List<Blank>
    ) {
        val layer = forcedLayer ?: layerFor(s)
        // Local frame: origin at the symbol's centre, y down, turned by the symbol's rotation.
        val p = Pen(w, layer, s.rotation) { rotateAbout(s.center + it, s.center, s.rotation) }
        if (s.category in PlanSymbols.categories) {
            planSymbol(if (s.category == PlanSymbols.WALLS) p.copy(blanks = blanks) else p, s, state)
        } else {
            val hw = s.size.width / 2f
            val hh = s.size.height / 2f
            p.rect(Offset(-hw, -hh), s.size)
            if (s.category == "Lumber") {
                p.line(Offset(-hw, -hh), Offset(hw, hh))
                p.line(Offset(-hw, hh), Offset(hw, -hh))
            }
        }
    }

    /** What a door, window, sliding door or cased opening blanks out of the wall it sits in. */
    private fun openingBlank(s: DrawingElement.Symbol, state: CanvasState): Blank? {
        val name = s.symbolName
        val inch = state.inchesToPx(1f)
        val w = s.size.width
        val h = s.size.height
        return when {
            PlanSymbols.isDoor(name) -> {
                // A strip 8" deep along the threshold (the bottom edge in the door's own frame).
                val local = Offset(0f, h / 2f)
                Blank(rotateAbout(s.center + local, s.center, s.rotation), w / 2f, inch * 4f, s.rotation)
            }
            PlanSymbols.isSlidingDoor(name) || PlanSymbols.isWindow(name) || PlanSymbols.isOpening(name) -> {
                val vertical = h > w
                val length = if (vertical) h else w
                val depth = max(if (vertical) w else h, inch * 8f)
                Blank(s.center, length / 2f, depth / 2f, s.rotation + if (vertical) 90f else 0f)
            }
            else -> null
        }
    }

    private fun planSymbol(p: Pen, s: DrawingElement.Symbol, state: CanvasState) {
        val w = s.size.width
        val h = s.size.height
        val hw = w / 2f
        val hh = h / 2f
        val inch = state.inchesToPx(1f)
        val name = s.symbolName
        when {
            s.category == PlanSymbols.WALLS ->
                if (h > w) wallSection(p.turned(90f), name, h, w, inch) else wallSection(p, name, w, h, inch)
            s.category == PlanSymbols.CABINETS -> cabinet(p, s, w, h, inch)
            s.category == PlanSymbols.CLOSET -> closetPart(p, name, w, h, inch)
            s.category == PlanSymbols.FURNITURE -> furniture(p, name, w, h, inch)
            PlanSymbols.isDoor(name) -> {
                // Hinge bottom-left, leaf standing open up the left edge, swing to the latch.
                p.line(Offset(-hw, hh), Offset(hw, hh))
                p.rect(Offset(-hw, hh - w), Size(inch * 1.75f, w))
                p.arc(Offset(-hw, hh), w, -90f, 90f)
            }
            PlanSymbols.isSlidingDoor(name) || PlanSymbols.isWindow(name) || PlanSymbols.isOpening(name) ->
                if (h > w) opening(p.turned(90f), name, h, w, inch) else opening(p, name, w, h, inch)
            name == "Bathtub" -> {
                val r = min(w, h) * 0.15f
                val ins = inch * 2.5f
                p.roundRect(Offset(-hw, -hh), Size(w, h), r)
                p.roundRect(Offset(-hw + ins, -hh + ins), Size(w - 2 * ins, h - 2 * ins), r * 1.6f)
                p.circle(Offset(-hw + inch * 8f, 0f), inch * 1.5f)
                p.rect(Offset(-hw + inch * 0.5f, -inch * 2f), Size(inch * 2f, inch * 4f))
            }
            name == "Shower Stall" -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                val d = inch * 2f
                // Pan slopes from the corners, stopping short of the drain.
                for (corner in listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh))) {
                    val len = corner.getDistance()
                    if (len > d * 1.3f) p.line(corner, corner * (d * 1.3f / len))
                }
                p.circle(Offset.Zero, d)
                p.line(Offset(-d * 0.7f, -d * 0.7f), Offset(d * 0.7f, d * 0.7f))
                p.line(Offset(-d * 0.7f, d * 0.7f), Offset(d * 0.7f, -d * 0.7f))
                p.line(Offset(-hw + inch * 2f, hh - inch), Offset(hw - inch * 2f, hh - inch))
            }
            name == "Toilet" -> {
                val tankD = min(h * 0.3f, inch * 8f)
                val tankW = min(w, inch * 20f)
                p.rect(Offset(-tankW / 2f, -hh), Size(tankW, tankD))
                val bowlW = min(w, inch * 14f)
                val bowlTop = -hh + tankD
                val bowlH = hh - bowlTop
                p.oval(Offset(-bowlW / 2f, bowlTop), Size(bowlW, bowlH))
                val ins = inch * 2f
                p.oval(Offset(-bowlW / 2f + ins, bowlTop + ins), Size(bowlW - 2 * ins, bowlH - 2 * ins))
            }
            name == "Bathroom Sink" -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                val bw = min(w * 0.65f, inch * 17f)
                val bh = min(h * 0.6f, inch * 13f)
                p.oval(Offset(-bw / 2f, -bh / 2f + inch), Size(bw, bh))
                p.circle(Offset(0f, -bh / 2f - inch * 0.5f), inch)
            }
            name == "Kitchen Sink" -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                val gap = inch * 1.5f
                val bw = hw - gap * 1.5f
                val bh = h - inch * 6f
                p.roundRect(Offset(-hw + gap, -bh / 2f + inch), Size(bw, bh), inch)
                p.roundRect(Offset(gap / 2f, -bh / 2f + inch), Size(bw, bh), inch)
                p.circle(Offset(0f, -hh + inch * 2f), inch)
            }
            name == "Water Heater" -> {
                val r = min(hw, hh)
                p.circle(Offset.Zero, r)
                p.circle(Offset.Zero, r * 0.8f)
                p.label("WH", Offset.Zero, r * 0.55f)
            }
            name == "Washer" || name == "Dryer" -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, -hh + inch * 3f), Offset(hw, -hh + inch * 3f))
                p.circle(Offset(0f, inch * 1.5f), min(hw, hh) * 0.55f)
                p.label(if (name == "Washer") "W" else "D", Offset(0f, inch * 1.5f), min(hw, hh) * 0.45f)
            }
            name == "Refrigerator" -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, hh - inch * 2f), Offset(hw, hh - inch * 2f))
                p.line(Offset(0f, hh - inch * 2f), Offset(0f, hh))
                p.label("REF", Offset.Zero, min(hw, hh) * 0.4f)
            }
            name == "Range" -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, -hh + inch * 3f), Offset(hw, -hh + inch * 3f))
                val br = min(inch * 3.5f, min(w, h) * 0.15f)
                val cy = inch * 1.5f
                for (x in listOf(-w * 0.25f, w * 0.25f)) for (y in listOf(-h * 0.2f + cy, h * 0.22f + cy)) p.circle(Offset(x, y), br)
            }
            name == PlanSymbols.LYNX_36_GRILL -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, -hh + inch * 3f), Offset(hw, -hh + inch * 3f))
                val grateTop = -hh + inch * 4f
                val grateBottom = hh - inch * 5f
                var gx = -hw + inch * 2f
                while (gx < hw - inch) {
                    p.line(Offset(gx, grateTop), Offset(gx, grateBottom))
                    gx += inch * 1.5f
                }
                p.line(Offset(-hw, grateBottom), Offset(hw, grateBottom))
                for (i in 0 until 4) p.circle(Offset(-hw + w * (0.2f + 0.2f * i), hh - inch * 2.5f), inch * 0.9f)
                val cutW = inch * PlanSymbols.LYNX_36_CUTOUT_W
                val cutD = inch * PlanSymbols.LYNX_36_CUTOUT_D
                val cutFront = hh - inch * PlanSymbols.LYNX_36_FRONT_LIP
                p.dashed().rect(Offset(-cutW / 2f, cutFront - cutD), Size(cutW, cutD))
                p.circle(Offset(cutW / 2f - inch * 2f, cutFront - inch * 2.25f), inch * PlanSymbols.LYNX_36_HOLE_DIA / 2f)
            }
            name == "Dishwasher" -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, hh - inch * 2f), Offset(hw, hh - inch * 2f))
                p.label("DW", Offset.Zero, min(hw, hh) * 0.45f)
            }
            else -> p.rect(Offset(-hw, -hh), Size(w, h))
        }
    }

    /** A window, sliding door or cased opening [w] long in a wall [h] thick, running left to right. */
    private fun opening(p: Pen, name: String, w: Float, h: Float, inch: Float) {
        val hw = w / 2f
        val hh = h / 2f
        val blank = max(h, inch * 8f)
        when {
            PlanSymbols.isOpening(name) -> {
                p.line(Offset(-hw, -blank / 2f), Offset(-hw, blank / 2f))
                p.line(Offset(hw, -blank / 2f), Offset(hw, blank / 2f))
                p.dashed().line(Offset(-hw, 0f), Offset(hw, 0f))
            }
            PlanSymbols.isSlidingDoor(name) -> {
                val panel = inch * 1.5f
                val overlap = inch * 2f
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.rect(Offset(-hw, -panel), Size(hw + overlap, panel))
                p.rect(Offset(-overlap, 0f), Size(hw + overlap, panel))
            }
            else -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, 0f), Offset(hw, 0f))
            }
        }
    }

    /** A wall [w] long and [h] thick, running left to right: outline, drywall faces, studs on their own layer. */
    private fun wallSection(p: Pen, name: String, w: Float, h: Float, inch: Float) {
        val hw = w / 2f
        val hh = h / 2f
        p.rect(Offset(-hw, -hh), Size(w, h))
        if (name.contains("Wall") && name.startsWith("2x") || name.contains("Framing")) {
            val drywall = if (name.contains("Framing")) 0f else inch * 0.5f
            if (drywall > 0f) {
                p.line(Offset(-hw, -hh + drywall), Offset(hw, -hh + drywall))
                p.line(Offset(-hw, hh - drywall), Offset(hw, hh - drywall))
            }
            val studW = inch * 1.5f
            val studTop = -hh + drywall
            val studH = h - 2f * drywall
            if (studW > 0f && studH > 0f) {
                val studs = p.onLayer(FRAMING)
                val xs = generateSequence(-hw) { it + inch * 16f }.takeWhile { it + studW <= hw - studW * 0.5f }.toMutableList()
                xs += hw - studW
                for (x in xs) {
                    studs.rect(Offset(x, studTop), Size(studW, studH))
                    studs.line(Offset(x, studTop), Offset(x + studW, studTop + studH))
                    studs.line(Offset(x, studTop + studH), Offset(x + studW, studTop))
                }
            }
        }
    }

    /** A cabinet, back edge at the top, doors facing down the page (see drawCabinet). */
    private fun cabinet(p: Pen, s: DrawingElement.Symbol, w: Float, h: Float, inch: Float) {
        val name = s.symbolName
        val hw = w / 2f
        val hh = h / 2f
        val face = inch * 1.5f
        when {
            PlanSymbols.isFiller(name) -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, hh), Offset(hw, -hh))
            }
            name == PlanSymbols.UPPER_CORNER || name.startsWith("Corner") -> {
                val d = inch * 12f
                p.poly(listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh - d), Offset(hw - d, hh), Offset(-hw, hh)), closed = true)
                p.line(Offset(hw - face, hh - d + face * 0.4f), Offset(hw - d + face * 0.4f, hh - face))
                if (name == PlanSymbols.UPPER_CORNER) {
                    p.line(Offset(hw - d, -hh + d), Offset(hw - d, hh))
                    p.line(Offset(-hw, -hh + d), Offset(hw - d, -hh + d))
                } else {
                    p.circle(Offset(-inch * 2f, -inch * 2f), min(hw, hh) * 0.75f)
                }
            }
            name.startsWith("Upper") -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                if (w > inch * 24f) p.line(Offset(0f, -hh), Offset(0f, hh))
            }
            name.startsWith("Island") -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, hh - face), Offset(hw, hh - face))
                p.line(Offset(-hw, -hh + face), Offset(hw, -hh + face))
                var x = -hw + inch * 24f
                while (x < hw - inch) {
                    p.line(Offset(x, -hh + face), Offset(x, hh - face))
                    x += inch * 24f
                }
                val over = s.properties[PlanSymbols.PROP_OVERHANG]?.toInt() ?: 0
                if (over > 0) {
                    val d = inch * (s.properties[PlanSymbols.PROP_OVERHANG_IN] ?: 12f)
                    val left = -hw
                    val right = if (over == 3) hw + d else hw
                    val top = if (over == 2) -hh - d else -hh
                    val bottom = hh + d
                    p.rect(Offset(left, top), Size(right - left, bottom - top))
                    var sx = -hw + inch * 12f
                    while (sx < hw - inch * 6f) {
                        p.circle(Offset(sx, hh + d - inch * 6f), inch * 6f)
                        if (over == 2) p.circle(Offset(sx, -hh - d + inch * 6f), inch * 6f)
                        sx += inch * 24f
                    }
                    if (over == 3) p.circle(Offset(hw + d - inch * 6f, 0f), inch * 6f)
                }
            }
            else -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, hh - face), Offset(hw, hh - face))
                val front = PlanSymbols.front(s)
                if (front == PlanSymbols.FRONT_THREE_DRAWERS || front == PlanSymbols.FRONT_FOUR_DRAWERS) {
                    p.line(Offset(-hw, hh - face * 0.5f), Offset(hw, hh - face * 0.5f))
                } else if (PlanSymbols.doorCount(s, w / inch) == 2) {
                    p.line(Offset(0f, hh - face), Offset(0f, hh))
                }
                if (name.startsWith("Pantry")) {
                    p.line(Offset(-hw, -hh), Offset(hw, hh - face))
                    p.line(Offset(-hw, hh - face), Offset(hw, -hh))
                }
            }
        }
    }

    /** Closet parts, back at the top (see drawClosetPart). */
    private fun closetPart(p: Pen, name: String, w: Float, h: Float, inch: Float) {
        val hw = w / 2f
        val hh = h / 2f
        val textSize = (inch * 3.5f).coerceAtLeast(6f)
        p.rect(Offset(-hw, -hh), Size(w, h))
        when {
            name.startsWith("Shelf & Rod") || name.startsWith("Double Hang") -> {
                val rodY = -hh + inch * 10.5f
                p.dashed().line(Offset(-hw + inch, rodY), Offset(hw - inch, rodY))
                p.line(Offset(-hw + inch, rodY - inch), Offset(-hw + inch, rodY + inch))
                p.line(Offset(hw - inch, rodY - inch), Offset(hw - inch, rodY + inch))
                if (name.startsWith("Double")) p.label("DBL", Offset(0f, -hh + inch * 5f), textSize)
            }
            name.startsWith("Shelving") -> {
                p.line(Offset(-hw, hh - inch), Offset(hw, hh - inch))
                p.label("SHELVES", Offset.Zero, textSize)
            }
            name.startsWith("Drawers") -> {
                p.line(Offset(-hw, hh - inch * 1.5f), Offset(hw, hh - inch * 1.5f))
                p.line(Offset(-inch * 2f, hh - inch * 0.75f), Offset(inch * 2f, hh - inch * 0.75f))
                p.label("DRAWERS", Offset.Zero, textSize)
            }
            name.startsWith("Shoe") -> {
                p.dashed().line(Offset(-hw, hh - inch * 2f), Offset(hw, hh - inch * 2f))
                p.label("SHOES", Offset.Zero, textSize)
            }
        }
    }

    /** Furniture, back at the top (see drawFurniture). */
    private fun furniture(p: Pen, name: String, w: Float, h: Float, inch: Float) {
        val hw = w / 2f
        val hh = h / 2f
        val textSize = (inch * 3.5f).coerceAtLeast(6f)
        val r = inch * 2f
        fun seating(seats: Int, arms: Boolean) {
            val back = inch * 8f
            val arm = if (arms) inch * 6f else 0f
            p.roundRect(Offset(-hw, -hh), Size(w, h), r)
            p.line(Offset(-hw + arm, -hh + back), Offset(hw - arm, -hh + back))
            if (arms) {
                p.line(Offset(-hw + arm, -hh + back), Offset(-hw + arm, hh))
                p.line(Offset(hw - arm, -hh + back), Offset(hw - arm, hh))
            }
            val seatW = (w - 2f * arm) / seats
            for (i in 1 until seats) {
                val x = -hw + arm + seatW * i
                p.line(Offset(x, -hh + back), Offset(x, hh))
            }
        }
        fun box(text: String? = null, frontLine: Boolean = false) {
            p.rect(Offset(-hw, -hh), Size(w, h))
            if (frontLine) p.line(Offset(-hw, hh - inch * 1.5f), Offset(hw, hh - inch * 1.5f))
            if (text != null) p.label(text, Offset.Zero, textSize)
        }
        when {
            name.startsWith("Sofa") -> seating(3, true)
            name.startsWith("Loveseat") -> seating(2, true)
            name == "Armchair" -> seating(1, true)
            name == "Recliner" -> {
                seating(1, true)
                p.line(Offset(-hw + inch * 6f, hh - inch * 10f), Offset(hw - inch * 6f, hh - inch * 10f))
            }
            name.startsWith("Round Table") -> {
                p.circle(Offset.Zero, min(hw, hh))
                p.circle(Offset.Zero, min(hw, hh) * 0.3f)
            }
            name.startsWith("Coffee Table") || name.startsWith("End Table") || name.startsWith("Kitchen Table") -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.rect(Offset(-hw + inch * 2f, -hh + inch * 2f), Size(w - inch * 4f, h - inch * 4f))
            }
            name == "Dining Chair" -> {
                p.roundRect(Offset(-hw, -hh), Size(w, h), r)
                p.line(Offset(-hw, -hh + inch * 3f), Offset(hw, -hh + inch * 3f))
            }
            name == "Office Chair" -> {
                p.circle(Offset(0f, inch * 2f), min(hw, hh) * 0.8f)
                p.poly(ellipsePoints(Offset(0f, -hh + h * 0.4f), hw, h * 0.4f, 200f, 140f, 24), closed = false)
            }
            name.endsWith("Bed") -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.line(Offset(-hw, -hh + inch * 3f), Offset(hw, -hh + inch * 3f))
                val pr = inch * 3f
                if (w > inch * 45f) {
                    val pillowW = (w - inch * 9f) / 2f
                    p.roundRect(Offset(-hw + inch * 3f, -hh + inch * 5f), Size(pillowW, inch * 18f), pr)
                    p.roundRect(Offset(inch * 1.5f, -hh + inch * 5f), Size(pillowW, inch * 18f), pr)
                } else {
                    p.roundRect(Offset(-hw + inch * 3f, -hh + inch * 5f), Size(w - inch * 6f, inch * 18f), pr)
                }
                val fold = -hh + h / 3f
                p.line(Offset(-hw, fold), Offset(hw, fold))
                p.line(Offset(-hw, fold + inch * 2f), Offset(hw, fold + inch * 2f))
            }
            name == "Nightstand" -> box("NS", frontLine = true)
            name.startsWith("Dresser") -> box("DRESSER", frontLine = true)
            name.startsWith("Desk") -> {
                box("DESK")
                p.rect(Offset(-inch * 9f, hh - inch * 10f), Size(inch * 18f, inch * 6f))
            }
            name.startsWith("Wall Unit") -> {
                box("WALL UNIT")
                p.line(Offset(-hw / 3f, -hh), Offset(-hw / 3f, hh))
                p.line(Offset(hw / 3f, -hh), Offset(hw / 3f, hh))
            }
            name.startsWith("Bookcase") -> box("BOOKS")
            name.startsWith("TV Stand") -> box("TV STAND", frontLine = true)
            name.startsWith("TV") -> {
                p.rect(Offset(-hw, -hh), Size(w, h))
                p.label("TV", Offset(0f, hh + inch * 3f), textSize)
            }
            else -> box(name)
        }
    }

    // ---- geometry helpers ----

    /** Points round an ellipse (or part of one), angles in screen degrees (clockwise, y down). */
    private fun ellipsePoints(c: Offset, rx: Float, ry: Float, start: Float = 0f, sweep: Float = 360f, segments: Int = 48): List<Offset> {
        val n = if (sweep >= 360f) segments else segments + 1
        return (0 until n).map { i ->
            val a = (start + sweep * i / segments) * PI.toFloat() / 180f
            Offset(c.x + rx * cos(a), c.y + ry * sin(a))
        }
    }

    private fun roundRectPoints(tl: Offset, size: Size, radius: Float): List<Offset> {
        val r = radius.coerceIn(0f, min(size.width, size.height) / 2f)
        if (r <= 0f) return listOf(tl, tl + Offset(size.width, 0f), tl + Offset(size.width, size.height), tl + Offset(0f, size.height))
        val l = tl.x + r
        val t = tl.y + r
        val rr = tl.x + size.width - r
        val b = tl.y + size.height - r
        fun corner(c: Offset, from: Float) = (0..6).map { i ->
            val a = (from + 90f * i / 6) * PI.toFloat() / 180f
            Offset(c.x + r * cos(a), c.y + r * sin(a))
        }
        return corner(Offset(rr, t), -90f) + corner(Offset(rr, b), 0f) + corner(Offset(l, b), 90f) + corner(Offset(l, t), 180f)
    }

    /**
     * A rectangle centred on [center], [halfLength] by [halfDepth] in its own frame turned
     * [rotation] degrees, that a door or window blanks out of the walls under it.
     */
    private data class Blank(val center: Offset, val halfLength: Float, val halfDepth: Float, val rotation: Float) {
        /** The parts of a-b outside this rectangle (Liang-Barsky in the rectangle's frame). */
        fun subtract(a: Offset, b: Offset): List<Pair<Offset, Offset>> {
            val la = rotateAbout(a, center, -rotation) - center
            val lb = rotateAbout(b, center, -rotation) - center
            val d = lb - la
            var t0 = 0f
            var t1 = 1f
            val checks = listOf(-d.x to la.x + halfLength, d.x to halfLength - la.x, -d.y to la.y + halfDepth, d.y to halfDepth - la.y)
            for ((pp, q) in checks) {
                if (abs(pp) < 1e-6f) {
                    if (q < 0f) return listOf(a to b)
                } else {
                    val t = q / pp
                    if (pp < 0f) t0 = max(t0, t) else t1 = min(t1, t)
                    if (t0 > t1) return listOf(a to b)
                }
            }
            val out = mutableListOf<Pair<Offset, Offset>>()
            val e = 1e-4f
            if (t0 > e) out += a to (a + (b - a) * t0)
            if (t1 < 1f - e) out += (a + (b - a) * t1) to b
            return out
        }
    }

    /**
     * Draws in a frame: [map] takes a point to canvas world space, [turn] is how far that frame
     * is turned (screen degrees), which arcs and text need. Wall pens carry the [blanks] their
     * lines are broken at.
     */
    private data class Pen(
        val w: DxfWriter,
        val layer: String,
        val turn: Float,
        val linetype: String? = null,
        val blanks: List<Blank> = emptyList(),
        val map: (Offset) -> Offset
    ) {
        fun turned(degrees: Float) = copy(turn = turn + degrees, map = { map(rotateAbout(it, Offset.Zero, degrees)) })
        fun dashed() = copy(linetype = DASHED)
        fun onLayer(name: String) = copy(layer = name)

        /** World segment [a]-[b] less whatever the [blanks] take out of it. */
        private fun cut(a: Offset, b: Offset): List<Pair<Offset, Offset>> {
            var pieces = listOf(a to b)
            for (blank in blanks) pieces = pieces.flatMap { (s, e) -> blank.subtract(s, e) }
            return pieces
        }

        fun line(a: Offset, b: Offset) = cut(map(a), map(b)).forEach { (s, e) -> w.line(layer, linetype, s, e) }

        fun poly(points: List<Offset>, closed: Boolean) {
            if (points.size < 2) return
            val world = points.map(map)
            if (blanks.isNotEmpty()) {
                // A wall something cuts goes out as the lines that are left; an uncut one stays
                // one polyline.
                val edges = world.zipWithNext() + if (closed) listOf(world.last() to world.first()) else emptyList()
                val pieces = edges.map { (a, b) -> cut(a, b) }
                if (pieces.zip(edges).any { (p, e) -> p.size != 1 || p[0] != e }) {
                    pieces.flatten().forEach { (s, e) -> w.line(layer, linetype, s, e) }
                    return
                }
            }
            w.poly(layer, linetype, world, closed)
        }

        fun rect(tl: Offset, size: Size) =
            poly(listOf(tl, tl + Offset(size.width, 0f), tl + Offset(size.width, size.height), tl + Offset(0f, size.height)), closed = true)
        fun roundRect(tl: Offset, size: Size, radius: Float) = poly(roundRectPoints(tl, size, radius), closed = true)
        fun oval(tl: Offset, size: Size) =
            poly(ellipsePoints(tl + Offset(size.width / 2f, size.height / 2f), size.width / 2f, size.height / 2f), closed = true)
        fun circle(c: Offset, r: Float) = w.circle(layer, linetype, map(c), r)
        fun arc(c: Offset, r: Float, start: Float, sweep: Float) = w.arc(layer, linetype, map(c), r, start + turn, sweep)

        /** Text at [at] (canvas space, the alignment point), [height] in DXF units. */
        fun text(content: String, at: Offset, height: Double, angle: Float, hAlign: Int, vAlign: Int) =
            w.text(layer, content, map(at), height, angle + turn, hAlign, vAlign)

        /** A symbol label centred on [center], [sizePx] the canvas font size. */
        fun label(content: String, center: Offset, sizePx: Float) = text(content, center, sizePx * 0.7 * w.k, 0f, hAlign = 1, vAlign = 2)
    }

    private data class Extents(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    /** Group-code writer. Canvas points go in as world px and come out in DXF units, Y flipped. */
    private class DxfWriter(val k: Double) {
        private val sb = StringBuilder()
        private var minX = Double.POSITIVE_INFINITY
        private var minY = Double.POSITIVE_INFINITY
        private var maxX = Double.NEGATIVE_INFINITY
        private var maxY = Double.NEGATIVE_INFINITY

        fun text(): String = sb.toString()
        fun raw(s: String) { sb.append(s) }
        fun extents(): Extents? = if (minX <= maxX) Extents(minX, minY, maxX, maxY) else null

        fun code(code: Int, value: String) {
            sb.append(code.toString().padStart(3)).append("\r\n").append(value).append("\r\n")
        }
        fun num(code: Int, value: Double) = code(code, formatFixed(value, 6))
        fun point(code: Int, x: Double, y: Double) { num(code, x); num(code + 10, y); num(code + 20, 0.0) }

        fun section(name: String, body: () -> Unit) { code(0, "SECTION"); code(2, name); body(); code(0, "ENDSEC") }
        fun table(name: String, count: Int, body: () -> Unit) { code(0, "TABLE"); code(2, name); code(70, count.toString()); body(); code(0, "ENDTAB") }
        fun variable(name: String, body: () -> Unit) { code(9, name); body() }

        private fun track(x: Double, y: Double) {
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        private fun at(code: Int, p: Offset) {
            val x = p.x * k
            val y = -p.y * k
            track(x, y)
            point(code, x, y)
        }
        private fun entity(type: String, layer: String, linetype: String?) {
            code(0, type); code(8, layer)
            if (linetype != null) code(6, linetype)
        }

        fun line(layer: String, linetype: String?, a: Offset, b: Offset) {
            entity("LINE", layer, linetype); at(10, a); at(11, b)
        }

        fun poly(layer: String, linetype: String?, points: List<Offset>, closed: Boolean) {
            entity("POLYLINE", layer, linetype)
            code(66, "1"); point(10, 0.0, 0.0); code(70, if (closed) "1" else "0")
            points.forEach { p -> code(0, "VERTEX"); code(8, layer); at(10, p) }
            code(0, "SEQEND"); code(8, layer)
        }

        fun circle(layer: String, linetype: String?, c: Offset, rPx: Float) {
            val r = rPx * k
            entity("CIRCLE", layer, linetype); at(10, c); num(40, r)
            track(c.x * k - r, -c.y * k - r); track(c.x * k + r, -c.y * k + r)
        }

        /**
         * An arc given in screen degrees (clockwise from +x, y down) from [start] through [sweep].
         * Flipping Y mirrors the angles, so it is the DXF counter-clockwise arc from
         * -(start + sweep) to -start.
         */
        fun arc(layer: String, linetype: String?, c: Offset, rPx: Float, start: Float, sweep: Float) {
            val r = rPx * k
            val (s, e) = if (sweep >= 0f) -(start + sweep) to -start else -start to -(start + sweep)
            entity("ARC", layer, linetype); at(10, c); num(40, r)
            num(50, normalize(s.toDouble())); num(51, normalize(e.toDouble()))
            track(c.x * k - r, -c.y * k - r); track(c.x * k + r, -c.y * k + r)
        }

        /** TEXT; [angle] in screen degrees. [hAlign] 0 left / 1 centre / 2 right, [vAlign] 0 baseline / 1 bottom / 2 middle. */
        fun text(layer: String, text: String, p: Offset, height: Double, angle: Float, hAlign: Int, vAlign: Int) {
            if (text.isBlank() || height <= 0.0) return
            entity("TEXT", layer, null)
            at(10, p); num(40, height); code(1, escape(text))
            if (angle != 0f) num(50, normalize(-angle.toDouble()))
            code(7, "STANDARD")
            if (hAlign != 0 || vAlign != 0) {
                code(72, hAlign.toString()); at(11, p); code(73, vAlign.toString())
            }
        }

        private fun normalize(degrees: Double): Double {
            var d = degrees % 360.0
            if (d < 0) d += 360.0
            return d
        }

        /** ASCII only: anything else as AutoCAD's \U+XXXX escape. */
        private fun escape(text: String): String = buildString {
            for (ch in text) {
                if (ch.code in 32..126) append(ch)
                else if (ch.code >= 128) append("\\U+").append(ch.code.toString(16).uppercase().padStart(4, '0'))
            }
        }
    }
}
