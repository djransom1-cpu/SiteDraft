package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Section and detail callouts (Dan, Nov 23 2026), as on architectural sets: a bubble split across
 * the middle, the drawing's number on top and the sheet it's on below ("2 / S-4").
 *
 * - Section callout: a cut line through the wall, the bubble at its end with a black triangle
 *   pointing the way the section looks (turn the symbol to point it).
 * - Detail callout: a leader from the spot to the bubble.
 *
 * Every drawing on a sheet is numbered 1, 2, 3 in the sheet's order ([Sheets.viewNumber]), and each
 * view's title carries its bubble. A callout points at one or more of the drawings (the typical
 * wall section and the construction details): the ones on the same sheet share one bubble ("1,2"
 * over "S-4"); ones on other sheets get a bubble each. All of it is read off the sheet set when
 * drawn, so renumbering the sheets or moving a detail updates every callout.
 */
object Callouts {
    const val CATEGORY = "Callouts"
    const val SECTION = "Section Callout"
    const val DETAIL = "Detail Callout"
    val library = listOf(SECTION, DETAIL)
    /** Which drawings it points to: a bit per [Target]. */
    const val TARGETS = "coTargets"
    val keys = setOf(TARGETS)
    /** The bubble's radius on the plan, real inches (at 1/4" = 1'-0" it prints about 1/2" across). */
    const val BUBBLE_R_IN = 12f

    /** What a callout can point to: the typical wall section and each construction detail. */
    enum class Target(val label: String) {
        WALL_SECTION("Typical wall section"), OVERHANG("Overhang detail"), BEAM_POCKET("Beam pocket detail"),
        COLUMN("Column detail"), FOOTING("Footing detail"), PORCH_POST("Porch post footing"), GARAGE_FROST("Garage frost wall"),
        HOUSE_WALL("Exterior wall section"), GARAGE_WALL("Garage wall section"), STAIR("Stair section"),
        BUILDING_SECTION("Building section");
        val bit get() = 1 shl ordinal
        fun detail(): DetailKit.Detail? = when (this) {
            WALL_SECTION -> null
            OVERHANG -> DetailKit.Detail.OVERHANG
            BEAM_POCKET -> DetailKit.Detail.BEAM_POCKET
            COLUMN -> DetailKit.Detail.COLUMN
            FOOTING -> DetailKit.Detail.FOOTING
            PORCH_POST -> DetailKit.Detail.PORCH_POST
            GARAGE_FROST -> DetailKit.Detail.GARAGE_FROST
            HOUSE_WALL -> DetailKit.Detail.HOUSE_WALL
            GARAGE_WALL -> DetailKit.Detail.GARAGE_WALL
            STAIR -> DetailKit.Detail.STAIR
            BUILDING_SECTION -> DetailKit.Detail.BUILDING_SECTION
        }
    }

    fun isCallout(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == CATEGORY
    fun defaults(name: String): Map<String, Float> =
        mapOf(TARGETS to (if (name == SECTION) Target.WALL_SECTION.bit else Target.FOOTING.bit).toFloat())

    fun targets(e: DrawingElement.Symbol): List<Target> {
        val bits = (e.properties[TARGETS] ?: defaults(e.symbolName)[TARGETS] ?: 0f).toInt()
        return Target.entries.filter { bits and it.bit != 0 }
    }

    /** Where a target is drawn in the sheet set: the sheet's number and the drawing's number on it. */
    fun placeOf(t: Target, state: CanvasState): Pair<String, Int>? {
        for (sheet in state.sheets) for (v in sheet.views) {
            val hit = when (t) {
                Target.WALL_SECTION -> v.kind == SheetViewKind.SECTION
                else -> {
                    val sym = DetailKit.detailIn(state, t.detail()!!)
                    sym != null && v.elementId == sym.id
                }
            }
            if (hit) return sheet.number to (Sheets.viewNumber(sheet, v) ?: continue)
        }
        return null
    }

    /** The bubbles: one per sheet, the drawing numbers on it joined ("1,2" over "S-4"); "?" for a drawing on no sheet yet. */
    fun bubbles(e: DrawingElement.Symbol, state: CanvasState): List<Pair<String, String>> {
        val placed = targets(e).map { it to placeOf(it, state) }
        val out = placed.mapNotNull { it.second }.groupBy({ it.first }, { it.second })
            .map { (sheet, nums) -> nums.distinct().sorted().joinToString(",") to sheet }.toMutableList()
        if (placed.any { it.second == null }) out += "?" to "—"
        return out.ifEmpty { listOf("?" to "—") }
    }

    /** Draws the callout in the model: line or leader turned with the symbol, the bubbles upright. */
    fun DrawScope.drawCallout(e: DrawingElement.Symbol, state: CanvasState, ink: Color) {
        val inch = state.inchesToPx(1f).takeIf { it > 0f } ?: return
        val r = BUBBLE_R_IN * inch
        val lineW = max(1f, r / 12f)
        val a = e.rotation * kotlin.math.PI / 180.0
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        fun world(x: Float, y: Float) = Offset(e.center.x + x * ca - y * sa, e.center.y + x * sa + y * ca)
        val hw = e.size.width / 2f
        val paper = if (state.backgroundColor.luminance() > 0.5f) Color.White else state.backgroundColor
        val list = bubbles(e, state)
        // Bubbles in a row past the right-hand end (in the symbol's frame).
        val centres = list.indices.map { i -> hw + r + i * (2.2f * r) }
        if (e.symbolName == SECTION) {
            // The cut line, heavy, through the whole length.
            drawLine(ink, world(-hw, 0f), world(hw, 0f), strokeWidth = lineW * 2.5f)
        } else {
            // A leader from the spot (a dot) to the first bubble.
            drawCircle(ink, radius = lineW * 2f, center = world(-hw, 0f))
            drawLine(ink, world(-hw, 0f), world(hw, 0f), strokeWidth = lineW)
        }
        for ((i, b) in list.withIndex()) {
            val cx = centres[i]
            if (i > 0) drawLine(ink, world(centres[i - 1] + r, 0f), world(cx - r, 0f), strokeWidth = lineW)
            if (e.symbolName == SECTION) {
                // The triangle the way the section looks: up in the symbol's frame.
                val t = Path().apply {
                    val p1 = world(cx - 1.35f * r, 0f); val p2 = world(cx + 1.35f * r, 0f); val p3 = world(cx, -1.9f * r)
                    moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close()
                }
                drawPath(t, ink)
            }
            val c = world(cx, 0f)
            drawCircle(paper, radius = r, center = c)
            drawCircle(ink, radius = r, center = c, style = Stroke(lineW * 1.5f))
            drawLine(ink, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = lineW)
            val ts = r * 0.62f
            fun centred(text: String, y: Float) {
                val w = ts * 0.6f * text.length
                drawPageText(text, Offset(c.x - w / 2f, y), ts, ink, bold = true, maxWidth = 2f * r)
            }
            centred(b.first, c.y - r * 0.78f)
            centred(b.second, c.y + r * 0.12f)
        }
    }

    /** A view's title bubble on its sheet: its number over the sheet's, [d] across, top-left at [at] (page px). */
    fun DrawScope.drawTitleBubble(number: Int, sheetNumber: String, at: Offset, d: Float, lineW: Float) {
        val r = d / 2f
        val c = Offset(at.x + r, at.y + r)
        drawCircle(Color.Black, radius = r, center = c, style = Stroke(lineW * 1.5f))
        drawLine(Color.Black, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = lineW)
        val ts = r * 0.62f
        fun centred(text: String, y: Float) {
            val w = ts * 0.6f * text.length
            drawPageText(text, Offset(c.x - w / 2f, y), ts, Color.Black, bold = true, maxWidth = 2f * r)
        }
        centred("$number", c.y - r * 0.78f)
        centred(sheetNumber, c.y + r * 0.12f)
    }
}
