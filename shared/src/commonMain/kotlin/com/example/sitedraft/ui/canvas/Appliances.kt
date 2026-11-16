package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Kitchen appliances ([PlanSymbols.APPLIANCES]) and range hoods ([PlanSymbols.HOODS]), real size.
 * Like cabinets, the back is at the top of the unrotated symbol. Hoods and the over-the-range
 * microwave hang above the plan's cut plane: they draw dashed, over whatever is under them.
 */
object Appliances {
    const val RANGE_30 = "Range 30\""
    const val RANGE_36 = "Range 36\""
    const val RANGE_48 = "Range 48\""
    const val SLIDE_IN_30 = "Slide-In Range 30\""
    const val COOKTOP_30 = "Cooktop 30\""
    const val COOKTOP_36 = "Cooktop 36\""
    const val INDUCTION_30 = "Induction Cooktop 30\""
    const val OVEN_SINGLE = "Wall Oven Single 33\""
    const val OVEN_DOUBLE = "Wall Oven Double 33\""
    const val OTR_MICRO = "Over-Range Microwave 30\""
    const val MICRO_DRAWER = "Microwave Drawer 24\""
    const val FRIDGE_30 = "Refrigerator 30\""
    const val FRIDGE_33 = "Refrigerator 33\""
    const val FRIDGE_36 = "Refrigerator 36\" French Door"
    const val FRIDGE_42 = "Built-In Refrigerator 42\""
    const val FRIDGE_48 = "Built-In Refrigerator 48\""
    const val WINE_24 = "Wine Cooler 24\""
    const val BEVERAGE_15 = "Beverage Center 15\""
    const val ICE_15 = "Ice Maker 15\""
    const val DW_18 = "Dishwasher 18\""

    val appliances = listOf(
        RANGE_30, RANGE_36, RANGE_48, SLIDE_IN_30, COOKTOP_30, COOKTOP_36, INDUCTION_30,
        OVEN_SINGLE, OVEN_DOUBLE, OTR_MICRO, MICRO_DRAWER,
        FRIDGE_30, FRIDGE_33, FRIDGE_36, FRIDGE_42, FRIDGE_48,
        WINE_24, BEVERAGE_15, ICE_15, DW_18
    )

    val hoods = listOf(
        "Chimney Hood 30\"", "Chimney Hood 36\"", "Chimney Hood 48\"",
        "Under-Cabinet Hood 30\"", "Under-Cabinet Hood 36\"",
        "Island Hood 36\"", "Island Hood 42\"",
        "Wood Hood 36\"", "Wood Hood 42\"", "Wood Hood 48\""
    )

    val footprints: Map<String, Pair<Float, Float>> = mapOf(
        RANGE_30 to Pair(30f, 26f), RANGE_36 to Pair(36f, 26f), RANGE_48 to Pair(48f, 26f), SLIDE_IN_30 to Pair(30f, 25f),
        COOKTOP_30 to Pair(30f, 21f), COOKTOP_36 to Pair(36f, 21f), INDUCTION_30 to Pair(30f, 21f),
        OVEN_SINGLE to Pair(33f, 24f), OVEN_DOUBLE to Pair(33f, 24f),
        OTR_MICRO to Pair(30f, 16f), MICRO_DRAWER to Pair(24f, 24f),
        FRIDGE_30 to Pair(30f, 30f), FRIDGE_33 to Pair(33f, 30f), FRIDGE_36 to Pair(36f, 30f),
        FRIDGE_42 to Pair(42f, 24f), FRIDGE_48 to Pair(48f, 24f),
        WINE_24 to Pair(24f, 24f), BEVERAGE_15 to Pair(15f, 24f), ICE_15 to Pair(15f, 24f), DW_18 to Pair(18f, 24f),
    ) + hoods.associateWith { name ->
        val w = name.filter { it.isDigit() }.toFloat()
        Pair(w, if (name.startsWith("Island")) 27f else if (w >= 42f) 24f else 22f)
    }

    fun isRange(name: String) = name.startsWith("Range") || name.startsWith("Slide-In Range")
    fun isCooktop(name: String) = name.contains("Cooktop")
    fun isFridge(name: String) = name.contains("Refrigerator")
    fun isWallOven(name: String) = name.startsWith("Wall Oven")
    fun hoodKind(name: String) = when {
        name.startsWith("Chimney") -> 0
        name.startsWith("Under-Cabinet") -> 1
        name.startsWith("Island") -> 2
        else -> 3 // wood hood
    }

    /** Hung above the cut plane: drawn dashed over what is below. */
    fun isOverlay(s: DrawingElement.Symbol) = s.category == PlanSymbols.HOODS || s.symbolName == OTR_MICRO

    /** Stands at counter height in a cabinet run (a backsplash continues across it). */
    fun atCounter(name: String) = isRange(name) || isCooktop(name) || name == MICRO_DRAWER || name == WINE_24 ||
        name == BEVERAGE_15 || name == ICE_15 || name == DW_18

    /** Under the counter: the counter runs over it. */
    fun underCounter(name: String) = name == MICRO_DRAWER || name == WINE_24 || name == BEVERAGE_15 || name == ICE_15 || name == DW_18

    // ---- Hood settings ----
    const val HOOD_BOTTOM = "hoodBottomIn"   // underside of the hood, inches off the floor
    const val HOOD_STYLE = "hoodStyle"       // wood hood: 0 box, 1 tapered, 2 curved
    const val HOOD_TO_CEILING = "hoodToCeiling" // wood hood: 0 up to the uppers' tops, 1 to the ceiling
    const val HOOD_CORBELS = "hoodCorbels"   // wood hood: 1 corbels under the mantel
    val keys = setOf(HOOD_BOTTOM, HOOD_STYLE, HOOD_TO_CEILING, HOOD_CORBELS)
    val woodStyleLabels = listOf("Box", "Tapered", "Curved")

    fun prop(s: DrawingElement.Symbol, key: String): Float = s.properties[key] ?: when (key) {
        HOOD_BOTTOM -> 66f   // 30" over a 36" cooking surface
        HOOD_CORBELS -> 1f
        else -> 0f
    }

    // ---- Plan ----

    fun planParts(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float): List<Shower.Part> {
        val name = s.symbolName
        val hw = w / 2f
        val hh = h / 2f
        val parts = mutableListOf<Shower.Part>()
        val overlay = isOverlay(s)
        val outlineInk = if (overlay) Shower.Ink.DASHED else Shower.Ink.LINE
        fun rect(l: Float, t: Float, r: Float, b: Float, ink: Shower.Ink) {
            parts += Shower.Part.Poly(listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b)), true, ink)
        }
        fun label(text: String) { parts += Shower.Part.Label(text, Offset.Zero, (min(hw, hh) * 0.4f).coerceAtMost(inch * 5f).coerceAtLeast(5f), Shower.Ink.LINE) }
        rect(-hw, -hh, hw, hh, outlineInk)
        when {
            isRange(name) || isCooktop(name) -> {
                // Burners: four on a 30", six on 36" and 48" (a 48" also has a griddle).
                val n = if (w / inch < 33f) 4 else 6
                val cols = n / 2
                val griddle = w / inch > 42f
                val usable = if (griddle) w * 0.66f else w
                val left = -hw
                val top = -hh + (if (isRange(name) && !name.startsWith("Slide")) inch * 3f else inch * 1f)
                val bottom = hh - inch * (if (isRange(name)) 4f else 2f)
                if (isRange(name)) parts += Shower.Part.Seg(Offset(-hw, hh - inch * 3f), Offset(hw, hh - inch * 3f), Shower.Ink.FAINT)
                val r = min(usable / cols, bottom - top) * (if (name.startsWith("Induction")) 0.36f else 0.28f)
                for (c in 0 until cols) for (k in 0..1) {
                    val cx = left + usable * (c + 0.5f) / cols
                    val cy = top + (bottom - top) * (if (k == 0) 0.27f else 0.73f)
                    parts += Shower.Part.Ring(Offset(cx, cy), r, Shower.Ink.ACCENT)
                    if (!name.startsWith("Induction")) parts += Shower.Part.Ring(Offset(cx, cy), r * 0.45f, Shower.Ink.ACCENT)
                }
                if (griddle) rect(-hw + usable + inch, top + inch, hw - inch, bottom - inch, Shower.Ink.ACCENT)
            }
            isWallOven(name) -> { rect(-hw + inch * 1.5f, -hh, hw - inch * 1.5f, hh - inch * 1.5f, Shower.Ink.THIN); label("OVEN") }
            name == OTR_MICRO -> label("MW")
            name == MICRO_DRAWER -> { parts += Shower.Part.Seg(Offset(-hw, hh - inch * 2f), Offset(hw, hh - inch * 2f), Shower.Ink.ACCENT); label("MW") }
            isFridge(name) -> {
                parts += Shower.Part.Seg(Offset(-hw, hh - inch * 2f), Offset(hw, hh - inch * 2f), Shower.Ink.FAINT)
                if (name == FRIDGE_36 || name == FRIDGE_42 || name == FRIDGE_48) {
                    val split = if (name == FRIDGE_42) -hw + w * 0.42f else 0f
                    parts += Shower.Part.Seg(Offset(split, hh - inch * 2f), Offset(split, hh), Shower.Ink.FAINT)
                }
                label("REF")
            }
            name == WINE_24 -> { parts += Shower.Part.Seg(Offset(-hw, hh - inch * 2f), Offset(hw, hh - inch * 2f), Shower.Ink.ACCENT); label("WINE") }
            name == BEVERAGE_15 -> label("BEV")
            name == ICE_15 -> label("ICE")
            name == DW_18 -> { parts += Shower.Part.Seg(Offset(-hw, hh - inch * 2f), Offset(hw, hh - inch * 2f), Shower.Ink.FAINT); label("DW") }
            s.category == PlanSymbols.HOODS -> {
                // Hood: the canopy dashed, its inner edge and the duct (chimney) above.
                rect(-hw + inch * 2f, -hh + inch * 1f, hw - inch * 2f, hh - inch * 3f, Shower.Ink.FAINT_DASHED)
                if (hoodKind(name) != 1) rect(-inch * 6f, -hh + (if (hoodKind(name) == 2) h / 2f - inch * 6f else inch * 1f), inch * 6f, -hh + (if (hoodKind(name) == 2) h / 2f + inch * 6f else inch * 11f), Shower.Ink.DASHED)
                label("HOOD")
            }
        }
        return parts
    }

    fun DrawScope.drawAppliancePlan(
        s: DrawingElement.Symbol, w: Float, h: Float, inch: Float,
        lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke
    ) {
        val dashed = Stroke(width = line.width, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 3f, inch * 2f)))
        val faintDashed = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 2f, inch * 1.5f)))
        val overlayColor = if (isOverlay(s)) accentColor else lineColor
        fun color(ink: Shower.Ink) = when (ink) {
            Shower.Ink.LINE, Shower.Ink.THIN, Shower.Ink.DASHED -> overlayColor
            Shower.Ink.FAINT, Shower.Ink.FAINT_DASHED -> faint
            else -> accentColor
        }
        fun stroke(ink: Shower.Ink) = when (ink) {
            Shower.Ink.LINE, Shower.Ink.GLASS -> line
            Shower.Ink.DASHED -> dashed
            Shower.Ink.FAINT_DASHED -> faintDashed
            else -> thin
        }
        for (p in planParts(s, w, h, inch)) when (p) {
            is Shower.Part.Seg -> drawPath(Path().apply { moveTo(p.a.x, p.a.y); lineTo(p.b.x, p.b.y) }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Poly -> drawPath(Path().apply {
                moveTo(p.points[0].x, p.points[0].y); p.points.drop(1).forEach { lineTo(it.x, it.y) }; if (p.closed) close()
            }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Ring -> drawCircle(color = color(p.ink), radius = p.r, center = p.c, style = stroke(p.ink))
            is Shower.Part.Label -> drawLabel(p.text, p.c, p.size, color(p.ink))
        }
    }

    // ---- Elevation ----

    /** What [drawElevation] draws with: the elevation builder's own helpers (x in px along the view, heights in inches). */
    interface ElevPen {
        val inch: Float
        fun rect(x1: Float, x2: Float, h1: Float, h2: Float, sw: Float)
        fun line(x1: Float, h1: Float, x2: Float, h2: Float, sw: Float)
        fun circle(xc: Float, hc: Float, rIn: Float, sw: Float)
        fun doors(x1: Float, x2: Float, h1: Float, h2: Float, count: Int, knobsLow: Boolean)
        fun drawers(x1: Float, x2: Float, h1: Float, h2: Float, n: Int)
    }

    /**
     * One appliance or hood in a wall elevation between [from] and [to] (px). [counterIn] is the
     * counter height, [uppersTopIn] the top of the upper cabinets, [ceilingIn] the wall height.
     */
    fun drawElevation(s: DrawingElement.Symbol, from: Float, to: Float, counterIn: Float, uppersTopIn: Float, ceilingIn: Float, p: ElevPen) {
        val name = s.symbolName
        val inch = p.inch
        val w = to - from
        val xc = (from + to) / 2f
        val m = 2f
        val l = 1f
        fun knobs(n: Int, hIn: Float, x1: Float = from, x2: Float = to) {
            for (i in 0 until n) p.circle(x1 + (x2 - x1) * (i + 0.5f) / n, hIn, 0.75f, l)
        }
        fun handle(x1: Float, x2: Float, hIn: Float) = p.line(x1, hIn, x2, hIn, m)
        when {
            isRange(name) -> {
                val slideIn = name.startsWith("Slide")
                p.rect(from, to, 0f, counterIn, m)
                if (!slideIn) p.rect(from, to, counterIn, counterIn + 6f, l) // backguard
                p.line(from, counterIn - 6f, to, counterIn - 6f, l)           // control panel
                knobs(if (w / inch < 33f) 5 else 7, counterIn - 3f, from + 2f * inch, to - 2f * inch)
                p.rect(from, to, 0f, 4f, l)                                   // drawer / kick
                val doorTop = counterIn - 8f
                if (w / inch > 42f) {
                    // 48": a wide oven and a narrow one beside it.
                    val split = from + w * 0.6f
                    for ((a, b) in listOf(from to split, split to to)) {
                        p.rect(a + inch, b - inch, 5f, doorTop, l)
                        p.rect(a + 3f * inch, b - 3f * inch, 9f, doorTop - 6f, l)
                        handle(a + 3f * inch, b - 3f * inch, doorTop - 2f)
                    }
                } else {
                    p.rect(from + inch, to - inch, 5f, doorTop, l)
                    p.rect(from + 3f * inch, to - 3f * inch, 9f, doorTop - 6f, l)
                    handle(from + 3f * inch, to - 3f * inch, doorTop - 2f)
                }
            }
            isCooktop(name) -> {
                // Set into the counter: the top shows as a thin edge, gas grates stand on it.
                p.rect(from + inch, to - inch, counterIn, counterIn + 0.75f, l)
                if (!name.startsWith("Induction")) p.rect(from + 2f * inch, to - 2f * inch, counterIn + 0.75f, counterIn + 2f, l)
            }
            isWallOven(name) -> {
                // Oven tower: toe kick, drawers, the oven(s), doors above to the uppers' tops.
                val top = uppersTopIn
                p.rect(from, to, 0f, top, m)
                p.line(from, 4f, to, 4f, l)
                fun oven(h1: Float, h2: Float) {
                    p.rect(from + 1.5f * inch, to - 1.5f * inch, h1, h2, m)
                    p.rect(from + 4f * inch, to - 4f * inch, h1 + 3f, h2 - 7f, l)
                    handle(from + 4f * inch, to - 4f * inch, h2 - 3f)
                }
                if (name == OVEN_DOUBLE) {
                    p.drawers(from, to, 4f, 18f, 1)
                    oven(19f, 46f); oven(47f, 74f)
                    if (top - 75f > 6f) p.doors(from, to, 75f, top - 1f, 0, true)
                } else {
                    p.drawers(from, to, 4f, 26f, 2)
                    oven(27f, 56f)
                    if (top - 57f > 6f) p.doors(from, to, 57f, top - 1f, 0, true)
                }
            }
            name == OTR_MICRO -> {
                p.rect(from, to, 66f, 83f, m)
                p.rect(from + 2f * inch, from + w * 0.72f, 68f, 81f, l)
                p.line(from + w * 0.72f + inch, 69f, from + w * 0.72f + inch, 80f, m)
                for (k in 0..3) p.rect(from + w * 0.8f, to - 2f * inch, 79f - k * 3f, 80.5f - k * 3f, l)
            }
            name == MICRO_DRAWER -> {
                p.rect(from, to, 0f, counterIn - 1.5f, m)
                p.line(from, 4f, to, 4f, l)
                p.rect(from + inch, to - inch, counterIn - 17f, counterIn - 2.5f, m)
                p.rect(from + 3f * inch, to - 3f * inch, counterIn - 14f, counterIn - 6f, l)
                handle(from + 4f * inch, to - 4f * inch, counterIn - 4.5f)
                p.rect(from + inch, to - inch, 5f, counterIn - 18f, l)
            }
            isFridge(name) -> {
                val builtIn = name.startsWith("Built")
                val top = if (builtIn) 84f else 70f
                p.rect(from, to, 0f, top, m)
                if (builtIn) p.rect(from, to, top - 6f, top, l) // grille
                val body = if (builtIn) top - 6f else top
                when (name) {
                    FRIDGE_36, FRIDGE_48 -> {
                        // French doors over a freezer drawer.
                        p.line(from, 30f, to, 30f, m)
                        p.line(xc, 30f, xc, body, m)
                        p.line(xc - 1.5f * inch, 42f, xc - 1.5f * inch, body - 6f, m)
                        p.line(xc + 1.5f * inch, 42f, xc + 1.5f * inch, body - 6f, m)
                        handle(from + 6f * inch, to - 6f * inch, 27f)
                    }
                    FRIDGE_42 -> {
                        // Side by side: freezer on the left.
                        val split = from + w * 0.42f
                        p.line(split, 0f, split, body, m)
                        p.line(split - 1.5f * inch, 20f, split - 1.5f * inch, body - 10f, m)
                        p.line(split + 1.5f * inch, 20f, split + 1.5f * inch, body - 10f, m)
                    }
                    else -> {
                        // Top freezer.
                        p.line(from, body - 20f, to, body - 20f, m)
                        p.line(to - 2.5f * inch, body - 18f, to - 2.5f * inch, body - 4f, m)
                        p.line(to - 2.5f * inch, 28f, to - 2.5f * inch, body - 24f, m)
                    }
                }
            }
            name == WINE_24 || name == BEVERAGE_15 -> {
                p.rect(from, to, 0f, counterIn - 1.5f, m)
                p.rect(from, to, 0f, 4f, l)
                p.rect(from + 1.5f * inch, to - 1.5f * inch, 5.5f, counterIn - 3f, l)
                var hIn = 10f
                while (hIn < counterIn - 5f) { p.line(from + 2f * inch, hIn, to - 2f * inch, hIn, l); hIn += 5f }
                p.line(to - 2.5f * inch, 12f, to - 2.5f * inch, counterIn - 8f, m)
            }
            name == ICE_15 -> {
                p.rect(from, to, 0f, counterIn - 1.5f, m)
                p.rect(from + inch, to - inch, 5f, counterIn - 2.5f, l)
                for (k in 0..3) p.line(from + 2f * inch, 1f + k * 0.8f, to - 2f * inch, 1f + k * 0.8f, l)
                handle(from + 3f * inch, to - 3f * inch, counterIn - 6f)
            }
            name == DW_18 -> {
                p.rect(from, to, 0f, counterIn - 1.5f, m)
                p.line(from, counterIn - 5f, to, counterIn - 5f, l)
                handle(from + 3f * inch, to - 3f * inch, counterIn - 7f)
            }
            s.category == PlanSymbols.HOODS -> drawHood(s, from, to, uppersTopIn, ceilingIn, p)
        }
    }

    private fun drawHood(s: DrawingElement.Symbol, from: Float, to: Float, uppersTopIn: Float, ceilingIn: Float, p: ElevPen) {
        val inch = p.inch
        val w = to - from
        val xc = (from + to) / 2f
        val bottom = prop(s, HOOD_BOTTOM)
        val m = 2f
        val l = 1f
        when (hoodKind(s.symbolName)) {
            0, 2 -> {
                // Stainless canopy: a 3" lip, sloped sides up to a 12" chimney, chimney to the ceiling.
                val chimney = minOf(6f * inch, w * 0.25f)
                p.rect(from, to, bottom, bottom + 3f, m)
                p.line(from, bottom + 3f, xc - chimney, bottom + 12f, m)
                p.line(to, bottom + 3f, xc + chimney, bottom + 12f, m)
                p.line(xc - chimney, bottom + 12f, xc + chimney, bottom + 12f, l)
                p.rect(xc - chimney, xc + chimney, bottom + 12f, ceilingIn, m)
            }
            1 -> {
                // Slim hood under a short upper cabinet that runs up to the other uppers' tops.
                p.rect(from, to, bottom, bottom + 6f, m)
                p.line(from + inch, bottom + 1.5f, to - inch, bottom + 1.5f, l)
                if (uppersTopIn - (bottom + 6f) > 6f) {
                    p.rect(from, to, bottom + 6f, uppersTopIn, m)
                    p.doors(from, to, bottom + 6f, uppersTopIn, 0, true)
                }
            }
            else -> {
                // Custom wood hood: a mantel band at the bottom (corbels under its ends), the
                // body above it - straight, tapered or curved - up to the uppers or the ceiling.
                val top = if (prop(s, HOOD_TO_CEILING) >= 0.5f) ceilingIn else uppersTopIn
                val mantelTop = bottom + 5f
                p.rect(from - inch, to + inch, bottom, bottom + 1.5f, m)
                p.rect(from, to, bottom + 1.5f, mantelTop, m)
                p.rect(from - inch, to + inch, mantelTop, mantelTop + 1.5f, l)
                if (prop(s, HOOD_CORBELS) >= 0.5f) {
                    for (x in listOf(from + inch, to - 4f * inch)) {
                        p.rect(x, x + 3f * inch, bottom - 9f, bottom, l)
                        p.line(x, bottom - 9f, x + 3f * inch, bottom - 3f, l)
                    }
                }
                val base = mantelTop + 1.5f
                val narrow = w * 0.62f
                when (prop(s, HOOD_STYLE).toInt()) {
                    1 -> {
                        // Tapered: sloped sides up to 12" under the top, then straight.
                        val breakH = maxOf(base + 6f, top - 12f)
                        p.line(from, base, xc - narrow / 2f, breakH, m)
                        p.line(to, base, xc + narrow / 2f, breakH, m)
                        p.rect(xc - narrow / 2f, xc + narrow / 2f, breakH, top, m)
                        p.line(from, base, to, base, l)
                    }
                    2 -> {
                        // Curved (bell): concave sides sweeping in to the narrow top.
                        val breakH = maxOf(base + 6f, top - 10f)
                        val steps = 12
                        for (side in listOf(-1f, 1f)) {
                            var px0 = if (side < 0) from else to
                            var ph0 = base
                            for (k in 1..steps) {
                                val t = k / steps.toFloat()
                                val curve = 1f - sin(t * PI.toFloat() / 2f)
                                val half = narrow / 2f + (w / 2f - narrow / 2f) * curve
                                val x = xc + side * half
                                val hh = base + (breakH - base) * t
                                p.line(px0, ph0, x, hh, m)
                                px0 = x; ph0 = hh
                            }
                        }
                        p.rect(xc - narrow / 2f, xc + narrow / 2f, breakH, top, m)
                        p.line(from, base, to, base, l)
                    }
                    else -> {
                        p.rect(from, to, base, top, m)
                        p.rect(from + 3f * inch, to - 3f * inch, base + 3f, top - 3f, l)
                    }
                }
                p.rect(xc - narrow / 2f - inch, xc + narrow / 2f + inch, top - 1.5f, top, l) // crown
            }
        }
    }

    private fun unused() = cos(0.0)
}
