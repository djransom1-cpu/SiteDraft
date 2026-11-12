package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.example.sitedraft.util.format
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Tile quantities from what is drawn, by tile size and where it goes:
 *
 * - Floors: every closed shape filled with a tile pattern (an area hatch, a hatched rectangle),
 *   less what stands on the floor where no tile goes - base cabinets, vanities and islands,
 *   showers (counted on their own), tubs, shower stalls and stairs.
 * - Showers: the floor inside the curb (less a bench), and the tiled walls to the tile height
 *   plus the curb (top and both faces), the bench (top and front) and the niche (its sides).
 * - Walls: each elevation's wall tile band (backsplash, wainscot), less what stands in front
 *   of it - cabinets, appliances, windows and doors.
 *
 * Areas are in square feet; tile counts add the waste and round up. Herringbone and hex get 5%
 * more waste than the base figure, for the extra cuts.
 */
object TileTakeoff {
    val tilePatterns = setOf(FillPattern.TILE, FillPattern.BRICK, FillPattern.HERRINGBONE, FillPattern.HEXAGON)

    /** One tiled surface. [widthIn]/[lengthIn] are the tile's sides (inches); [netSqFt] after deductions. */
    data class Row(
        val location: String,
        val where: String,
        val pattern: FillPattern,
        val widthIn: Float,
        val lengthIn: Float,
        val netSqFt: Float,
        val note: String = "",
    ) {
        val tile get() = Tile(pattern, widthIn, lengthIn)
    }

    /** A tile: what the totals are grouped by. */
    data class Tile(val pattern: FillPattern, val widthIn: Float, val lengthIn: Float) {
        /** Face area of one tile, square inches. */
        val areaSqIn: Float get() = when (pattern) {
            FillPattern.HEXAGON -> 2.598076f * widthIn * widthIn // circumradius = the pattern size
            FillPattern.BRICK -> widthIn * (if (lengthIn > 0f) lengthIn else widthIn * 2f)
            FillPattern.HERRINGBONE -> widthIn * (if (lengthIn > widthIn) lengthIn else widthIn)
            else -> widthIn * (if (lengthIn > 0f) lengthIn else widthIn)
        }
        val label: String get() {
            fun n(v: Float) = if (abs(v - v.toInt()) < 0.01f) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
            val name = when (pattern) {
                FillPattern.BRICK -> "subway"
                FillPattern.HERRINGBONE -> "herringbone"
                FillPattern.HEXAGON -> "hex"
                else -> "grid"
            }
            return when (pattern) {
                FillPattern.HEXAGON -> "${n(widthIn * 2f)}\" $name"
                FillPattern.BRICK -> "${n(widthIn)} x ${n(if (lengthIn > 0f) lengthIn else widthIn * 2f)} $name"
                else -> "${n(widthIn)} x ${n(if (lengthIn > 0f) lengthIn else widthIn)} $name"
            }
        }
        val extraWastePct: Float get() = if (pattern == FillPattern.HERRINGBONE || pattern == FillPattern.HEXAGON) 5f else 0f
    }

    /** A tile's total: net area, area with waste, tiles, boxes (when a box coverage is given). */
    data class Total(val tile: Tile, val netSqFt: Float, val withWasteSqFt: Float, val wastePct: Float, val tiles: Int, val boxes: Int?)

    fun totals(rows: List<Row>, wastePct: Float, sqFtPerBox: Float?): List<Total> =
        rows.groupBy { it.tile }.map { (tile, rs) ->
            val net = rs.sumOf { it.netSqFt.toDouble() }.toFloat()
            val waste = wastePct + tile.extraWastePct
            val gross = net * (1f + waste / 100f)
            val tiles = if (tile.areaSqIn > 0f) ceil(gross * 144f / tile.areaSqIn - 0.0001f).toInt() else 0
            Total(tile, net, gross, waste, tiles, sqFtPerBox?.takeIf { it > 0f }?.let { ceil(gross / it - 0.0001f).toInt() })
        }.sortedByDescending { it.netSqFt }

    // ---- What is drawn ----

    fun rows(state: CanvasState): List<Row> {
        val inch = state.inchesToPx(1f)
        val sqIn = inch * inch
        val out = mutableListOf<Row>()
        val symbols = state.elements.filterIsInstance<DrawingElement.Symbol>()
        val labels = state.elements.filterIsInstance<DrawingElement.Text>()

        fun roomAt(p: Offset, inside: List<Offset>?): String? {
            val hit = labels.firstOrNull { t ->
                val c = t.topLeft + Offset(t.fontSize, t.fontSize * 0.6f)
                if (inside != null) HatchGeometry.inside(c, inside) || HatchGeometry.inside(t.topLeft, inside)
                else (c - p).getDistance() < inch * 72f
            }
            return hit?.text?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        // Floors.
        var areaNo = 0
        for (e in state.elements) {
            val fill = e.fillOrNull() ?: continue
            if (fill.pattern !in tilePatterns) continue
            val local = HatchGeometry.outlineOf(e) ?: continue
            val pivot = e.rotationPivot() ?: Offset.Zero
            val poly = if (e.rotation == 0f) local else local.map { rotateAbout(it, pivot, e.rotation) }
            var area = abs(polygonArea(poly))
            if (area <= 0f) continue
            var deducted = 0f
            for (s in symbols) {
                if (!coversFloor(s)) continue
                val b = FloorPlan.bounds(s)
                deducted += abs(polygonArea(clipToRect(poly, b)))
            }
            area = (area - deducted).coerceAtLeast(0f)
            areaNo++
            val room = roomAt(Offset.Zero, poly) ?: "Area $areaNo"
            out += Row(
                room, "Floor", fill.pattern,
                fill.spacing / inch, if (fill.length > 0f) fill.length / inch else 0f,
                area / sqIn / 144f,
                if (deducted > 0f) "less ${fmt(deducted / sqIn / 144f)} sq ft under cabinets, tubs, showers, stairs" else ""
            )
        }

        // Showers.
        for (s in symbols.filter { it.category == PlanSymbols.SHOWERS }) {
            val name = s.symbolName
            val room = roomAt(s.center, null)?.let { "$it, $name" } ?: name
            val t = Shower.type(name)
            val wIn = s.size.width / inch
            val dIn = s.size.height / inch
            val g = Shower.planGeometry(s, s.size.width, s.size.height, inch)
            val benchWall = Shower.code(s, Shower.BENCH_WALL)
            val hasBench = benchWall != Shower.NONE && benchWall in Shower.solidFaces(t)
            val benchLen = if (!hasBench) 0f else if (benchWall == Shower.BACK) wIn else dIn
            val benchD = Shower.prop(s, Shower.BENCH_D)
            val benchH = Shower.prop(s, Shower.BENCH_H)
            if (g.floorTile > 0f) {
                val floorSqIn = abs(polygonArea(g.floor)) / sqIn - (if (hasBench) benchLen * benchD else 0f)
                out += Row(room, "Shower floor", FillPattern.TILE, g.floorTile / inch, g.floorTileLength / inch, (floorSqIn / 144f).coerceAtLeast(0f))
            }
            val tileH = minOf(Shower.prop(s, Shower.TILE_H), Shower.prop(s, Shower.CEILING))
            var wallSqIn = Shower.solidFaces(t).sumOf { f -> ((if (f == Shower.BACK) wIn else dIn) * tileH).toDouble() }.toFloat()
            val notes = mutableListOf<String>()
            val curbH = Shower.prop(s, Shower.CURB_H)
            if (curbH > 0f) {
                val curbW = Shower.prop(s, Shower.CURB_W)
                val run = when (t) {
                    Shower.Type.CORNER -> wIn + dIn
                    Shower.Type.NEO_ANGLE -> Shower.neoCut(wIn, dIn).let { a -> (wIn - a) + a * sqrt(2f) + (dIn - a) }
                    else -> wIn
                }
                wallSqIn += run * (curbW + 2f * curbH)
                notes += "curb"
            }
            if (hasBench) { wallSqIn += benchLen * benchD; notes += "bench" }
            val nicheWall = Shower.code(s, Shower.NICHE_WALL)
            if (nicheWall != Shower.NONE && nicheWall in Shower.solidFaces(t)) {
                wallSqIn += 2f * (Shower.prop(s, Shower.NICHE_W) + Shower.prop(s, Shower.NICHE_T)) * 3.5f
                notes += "niche"
            }
            val pattern = Shower.tilePatterns.getOrElse(Shower.code(s, Shower.TILE_PAT)) { FillPattern.TILE }
            out += Row(
                room, "Shower walls", pattern, Shower.prop(s, Shower.TILE_IN), Shower.prop(s, Shower.TILE_LEN),
                wallSqIn / 144f, if (notes.isEmpty()) "to ${fmt(tileH)}\" high" else "to ${fmt(tileH)}\" high, includes ${notes.joinToString(", ")}"
            )
        }

        // Wall tile on elevations.
        for (v in state.elements.filterIsInstance<DrawingElement.ElevationView>()) {
            val spec = v.spec
            if (spec.showerFace >= 0 || spec.stairSide >= 0 || spec.tileToIn <= spec.tileFromIn) continue
            val top = minOf(spec.tileToIn, spec.wallHeightIn)
            val band = Rect(v.origin.x, v.origin.y - top * inch, v.origin.x + v.size.width, v.origin.y - spec.tileFromIn * inch)
            if (band.width <= 0f || band.height <= 0f) continue
            // What stands in front of the tile: the parts the elevation blanks it with.
            val covers = Elevation.partsOf(v, state).filterIsInstance<DrawingElement.Rectangle>()
                .filter { it.fill.pattern == FillPattern.SOLID }
                .mapNotNull { Rect(it.topLeft, it.size).intersectOrNull(band) }
            val covered = unionArea(covers)
            val net = (band.width * band.height - covered) / sqIn / 144f
            val wall = state.elements.find { it.id == spec.wallId } as? DrawingElement.Symbol
            val room = wall?.let { roomAt(FloorPlan.bounds(it).center, null) }
            val lengthLabel = formatFeetInches(v.size.width / inch / 12f)
            out += Row(
                listOfNotNull(room, "wall $lengthLabel ${spec.side.looking}").joinToString(", "), "Wall",
                Shower.tilePatterns.getOrElse(spec.tilePattern) { FillPattern.TILE }, spec.tileSizeIn, spec.tileLengthIn,
                net.coerceAtLeast(0f),
                "${fmt(spec.tileFromIn)}\" to ${fmt(top)}\"" + if (covered > 0f) ", less ${fmt(covered / sqIn / 144f)} sq ft behind cabinets, windows, doors" else ""
            )
        }
        return out
    }

    /** Plan items that stand where no floor tile goes. */
    private fun coversFloor(s: DrawingElement.Symbol): Boolean = when (s.category) {
        PlanSymbols.CABINETS -> !s.symbolName.startsWith("Upper") && !PlanSymbols.isFiller(s.symbolName)
        PlanSymbols.SHOWERS, PlanSymbols.STAIRS -> true
        PlanSymbols.FIXTURES -> s.symbolName == "Bathtub" || s.symbolName == "Shower Stall"
        else -> false
    }

    /** Copy-and-paste table: one line per surface, then the totals. */
    fun csv(rows: List<Row>, totals: List<Total>): String = buildString {
        appendLine("Location,Where,Tile,Net sq ft,Note")
        for (r in rows) appendLine("\"${r.location}\",${r.where},${r.tile.label},${fmt(r.netSqFt)},\"${r.note}\"")
        appendLine()
        appendLine("Tile,Net sq ft,Waste %,With waste sq ft,Tiles,Boxes")
        for (t in totals) appendLine("${t.tile.label},${fmt(t.netSqFt)},${fmt(t.wastePct)},${fmt(t.withWasteSqFt)},${t.tiles},${t.boxes ?: ""}")
    }

    fun fmt(v: Float): String = "%.1f".format(v).removeSuffix(".0")

    // ---- Geometry ----

    /** Signed shoelace area. */
    fun polygonArea(p: List<Offset>): Float {
        if (p.size < 3) return 0f
        var a = 0.0
        for (i in p.indices) {
            val q = p[i]
            val r = p[(i + 1) % p.size]
            a += q.x.toDouble() * r.y - r.x.toDouble() * q.y
        }
        return (a / 2.0).toFloat()
    }

    /** [poly] clipped to rectangle [r] (Sutherland-Hodgman). */
    fun clipToRect(poly: List<Offset>, r: Rect): List<Offset> {
        var pts = poly
        val edges = listOf<Pair<(Offset) -> Boolean, (Offset, Offset) -> Offset>>(
            Pair({ p -> p.x >= r.left }, { a, b -> at(a, b, (r.left - a.x) / (b.x - a.x)) }),
            Pair({ p -> p.x <= r.right }, { a, b -> at(a, b, (r.right - a.x) / (b.x - a.x)) }),
            Pair({ p -> p.y >= r.top }, { a, b -> at(a, b, (r.top - a.y) / (b.y - a.y)) }),
            Pair({ p -> p.y <= r.bottom }, { a, b -> at(a, b, (r.bottom - a.y) / (b.y - a.y)) }),
        )
        for ((inside, cross) in edges) {
            if (pts.isEmpty()) break
            val next = mutableListOf<Offset>()
            for (i in pts.indices) {
                val cur = pts[i]
                val prev = pts[(i + pts.size - 1) % pts.size]
                val curIn = inside(cur)
                val prevIn = inside(prev)
                if (curIn) {
                    if (!prevIn) next += cross(prev, cur)
                    next += cur
                } else if (prevIn) next += cross(prev, cur)
            }
            pts = next
        }
        return pts
    }

    private fun at(a: Offset, b: Offset, t: Float) = a + (b - a) * t

    /** Area covered by a set of rectangles (overlaps counted once). */
    fun unionArea(rects: List<Rect>): Float {
        if (rects.isEmpty()) return 0f
        val xs = rects.flatMap { listOf(it.left, it.right) }.distinct().sorted()
        var total = 0.0
        for (i in 0 until xs.size - 1) {
            val x0 = xs[i]
            val x1 = xs[i + 1]
            val spans = rects.filter { it.left <= x0 && it.right >= x1 }.map { it.top to it.bottom }.sortedBy { it.first }
            var covered = 0.0
            var curTop = Float.NEGATIVE_INFINITY
            var curBottom = Float.NEGATIVE_INFINITY
            for ((t, b) in spans) {
                if (t > curBottom) {
                    if (curBottom > curTop) covered += curBottom - curTop
                    curTop = t; curBottom = b
                } else if (b > curBottom) curBottom = b
            }
            if (curBottom > curTop) covered += curBottom - curTop
            total += covered * (x1 - x0)
        }
        return total.toFloat()
    }

    private fun Rect.intersectOrNull(o: Rect): Rect? {
        val r = Rect(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))
        return if (r.width > 0f && r.height > 0f) r else null
    }
}
