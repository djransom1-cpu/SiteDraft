package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import kotlin.math.max

/**
 * What every construction detail shares (Nov 22 2026): drawn in real inches (y = minus the
 * elevation, so up is up), notes with leaders out to a column on each side, stacked so none
 * overlap, and the detail as a model-space symbol true size, put on sheets at its own scale - the
 * way the typical wall section works. [Detail] says which detail a symbol is.
 */
object DetailKit {
    const val CATEGORY = "Details"
    const val SYMBOL = "Detail"
    const val KIND = "detailKind"
    const val SCALE_KEY = "scaleDenominator"

    /** The details there are, their sheet series and their own scale (12 = 1" = 1'-0"). */
    enum class Detail(val title: String, val series: String, val denominator: Int) {
        OVERHANG("Overhang Detail", "A", 12),
        BEAM_POCKET("Beam Pocket Detail", "S", 12),
        COLUMN("Column Detail", "S", 12),
        FOOTING("Footing Detail", "S", 12),
        PORCH_POST("Porch Post Footing Detail", "S", 12),
        GARAGE_FROST("Garage Frost Wall Detail", "S", 12),
        HOUSE_WALL("Exterior Wall Section", "S", 16),
        GARAGE_WALL("Garage Wall Section", "S", 16),
        STAIR("Stair Section Detail", "S", 24),
        /** A cut across the whole house, footing to ridge (BuildingSection, Nov 25). */
        BUILDING_SECTION("Building Section A-A", "S", 48)
    }

    data class Note(val text: String, val at: Offset, val left: Boolean)
    data class Geometry(val parts: List<Shower.Part>, val notes: List<Note>, val extent: Rect, val leftColX: Float, val rightColX: Float)

    fun isDetail(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY && e.symbolName == SYMBOL
    fun kindOf(e: DrawingElement.Symbol): Detail = Detail.entries.getOrElse((e.properties[KIND] ?: 0f).toInt()) { Detail.OVERHANG }
    fun denominatorOf(e: DrawingElement.Symbol): Int = (e.properties[SCALE_KEY] ?: kindOf(e).denominator.toFloat()).toInt().coerceAtLeast(1)
    fun detailIn(state: CanvasState, kind: Detail): DrawingElement.Symbol? =
        state.elements.filterIsInstance<DrawingElement.Symbol>().firstOrNull { isDetail(it) && kindOf(it) == kind }

    fun geometry(state: CanvasState, kind: Detail): Geometry = when (kind) {
        Detail.OVERHANG -> OverhangDetail.geometry(state)
        Detail.BEAM_POCKET -> BeamPocketDetail.geometry(state)
        Detail.COLUMN -> ColumnDetail.geometry(state)
        Detail.FOOTING -> FootingDetail.geometry(state)
        Detail.PORCH_POST -> PorchPostDetail.geometry(state)
        Detail.GARAGE_FROST -> GarageFrostDetail.geometry(state)
        Detail.HOUSE_WALL -> WallSections.house(state)
        Detail.GARAGE_WALL -> WallSections.garage(state)
        Detail.STAIR -> StairDetail.geometry(state)
        Detail.BUILDING_SECTION -> BuildingSection.geometry(state)
    }

    /** The detail's size in world px: its real extent (notes included) at the drawing's scale. */
    fun worldSize(state: CanvasState, kind: Detail): Size {
        val e = geometry(state, kind).extent
        val k = PrintPage.worldPxPerInch(state)
        return Size(e.width * k, e.height * k)
    }

    fun newSymbol(state: CanvasState, kind: Detail, center: Offset): DrawingElement.Symbol = DrawingElement.Symbol(
        symbolName = SYMBOL, category = CATEGORY, center = center, size = worldSize(state, kind), color = Color.White,
        properties = mapOf(KIND to kind.ordinal.toFloat(), SCALE_KEY to kind.denominator.toFloat())
    )

    /** Where a new detail goes: to the right of everything drawn, a few feet clear. */
    fun spotFor(state: CanvasState, kind: Detail): Offset {
        val size = worldSize(state, kind)
        val gap = PrintPage.worldPxPerInch(state) * 60f
        val rects = state.elements.map { it.boundingRect() }.filter { it.width > 0f || it.height > 0f }
        if (rects.isEmpty()) return Offset(size.width / 2f, size.height / 2f)
        return Offset(rects.maxOf { it.right } + gap + size.width / 2f, rects.minOf { it.top } + size.height / 2f)
    }

    /** Draws [g] with its centre at [centrePx], [dpi] page px per paper inch at 1:[denominator]; notes the same size on paper at any scale. */
    fun DrawScope.drawDetail(g: Geometry, centrePx: Offset, dpi: Float, denominator: Int, ink: Color) {
        val k = dpi / denominator
        fun map(o: Offset) = Offset(centrePx.x + (o.x - g.extent.center.x) * k, centrePx.y + (o.y - g.extent.center.y) * k)
        val lineW = max(1f, dpi / 100f)
        val heavy = Stroke(lineW * 1.6f, cap = StrokeCap.Round)
        val thin = Stroke(lineW * 0.8f, cap = StrokeCap.Round)
        val dashed = Stroke(lineW * 0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(lineW * 6f, lineW * 4f)))
        val grey = if (ink.luminance() > 0.5f) Color(0xFFAAAAAA) else Color(0xFF888888)
        fun color(i: Shower.Ink) = if (i == Shower.Ink.FAINT || i == Shower.Ink.FAINT_DASHED) grey else ink
        fun stroke(i: Shower.Ink) = when (i) {
            Shower.Ink.LINE -> heavy
            Shower.Ink.DASHED, Shower.Ink.FAINT_DASHED -> dashed
            else -> thin
        }
        for (p in g.parts) when (p) {
            is Shower.Part.Seg -> drawLine(color(p.ink), map(p.a), map(p.b), strokeWidth = stroke(p.ink).width, pathEffect = stroke(p.ink).pathEffect)
            is Shower.Part.Poly -> drawPath(Path().apply {
                val f = map(p.points[0]); moveTo(f.x, f.y); p.points.drop(1).forEach { val q = map(it); lineTo(q.x, q.y) }; if (p.closed) close()
            }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Ring -> drawCircle(color(p.ink), radius = p.r * k, center = map(p.c), style = stroke(p.ink))
            is Shower.Part.Label -> drawPageText(p.text, map(p.c), 0.09f * dpi * (p.size.coerceAtLeast(0.5f)), color(p.ink), bold = true, maxWidth = 4f * dpi)
        }
        val text = 0.09f * dpi
        val rowGap = text * 1.5f
        fun place(list: List<Note>, colX: Float, onRight: Boolean) {
            var lastY = Float.NEGATIVE_INFINITY
            for (n in list.sortedBy { it.at.y }) {
                val a = map(n.at)
                val ty = max(a.y, lastY + rowGap)
                lastY = ty
                val cx = map(Offset(colX, 0f)).x
                val knee = a.x + (cx - a.x) * 0.7f
                drawLine(ink, a, Offset(knee, ty), strokeWidth = lineW * 0.6f)
                drawLine(ink, Offset(knee, ty), Offset(cx, ty), strokeWidth = lineW * 0.6f)
                drawCircle(ink, radius = lineW * 1.4f, center = a)
                val w = text * 0.58f * n.text.length
                val tx = if (onRight) cx + text * 0.4f else cx - text * 0.4f - w
                drawPageText(n.text, Offset(tx, ty - text * 0.65f), text, ink, bold = false, maxWidth = w + text * 2f)
            }
        }
        place(g.notes.filter { !it.left }, g.rightColX, true)
        place(g.notes.filter { it.left }, g.leftColX, false)
    }

    /** A detail symbol in model space, true size at its own scale (the text sized for paper at it). */
    fun DrawScope.drawDetailSymbol(element: DrawingElement.Symbol, state: CanvasState, ink: Color) {
        val d = denominatorOf(element)
        drawDetail(geometry(state, kindOf(element)), element.center, PrintPage.worldPxPerInch(state) * d, d, ink)
    }
}
