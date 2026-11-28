package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Generic wallpaper patterns (Natalie, Nov 28): drawn by the app instead of picked from a photo, at the repeat
 * sizes real papers come in, so an elevation can show the look before a paper is chosen. Each one is a swatch
 * one repeat wide and one repeat high; [WallDecorations.tiles] lays it across the wall like a photo swatch.
 */
object WallpaperPatterns {
    /** A pattern and its repeat, inches: [widthIn] across, [heightIn] up. */
    data class Pattern(val key: String, val label: String, val widthIn: Float, val heightIn: Float)

    val patterns = listOf(
        Pattern("stripe", "Stripe", 6f, 6f),            // a 6" stripe repeat
        Pattern("damask", "Damask", 21f, 25f),          // 20 1/2"-21" wide roll, about 25" drop
        Pattern("grasscloth", "Grasscloth", 36f, 12f),  // 36" panels, the weave runs across them
        Pattern("geometric", "Small geometric", 4f, 4f),// a 4" trellis
        Pattern("floral", "Floral", 21f, 21f)           // a 21" straight-match floral
    )

    /** Background and ink. */
    data class Colorway(val label: String, val ground: Color, val ink: Color)

    val colorways = listOf(
        Colorway("Neutral", Color(0xFFF2EBDD), Color(0xFFB8A685)),
        Colorway("Navy", Color(0xFFE9ECF1), Color(0xFF243B5E)),
        Colorway("Sage", Color(0xFFEEF1E8), Color(0xFF7D9270)),
        Colorway("Blush", Color(0xFFF7ECE9), Color(0xFFC08A86)),
        Colorway("Charcoal", Color(0xFFE6E6E4), Color(0xFF3E3F42))
    )

    /** Swatch pixels per inch: fine enough to read, small enough to keep the project file small. */
    private const val PPI = 12f

    fun find(key: String): Pattern? = patterns.firstOrNull { it.key == key }

    /** The picture's id, the same for the same pattern and colours so using it twice keeps one copy in the project. */
    fun pictureId(key: String, colorway: Int) = "pattern:$key:$colorway"

    fun label(key: String, colorway: Int) = "${find(key)?.label ?: key} (${colorways.getOrNull(colorway)?.label ?: ""})"

    fun picture(key: String, colorway: Int): Picture? {
        val p = find(key) ?: return null
        val c = colorways.getOrNull(colorway) ?: colorways.first()
        return Picture(id = pictureId(key, colorway), name = "Wallpaper: " + label(key, colorway), bitmap = draw(p, c))
    }

    fun draw(p: Pattern, c: Colorway): ImageBitmap {
        val w = (p.widthIn * PPI).roundToInt()
        val h = (p.heightIn * PPI).roundToInt()
        val img = ImageBitmap(w, h)
        val cv = Canvas(img)
        val fill = Paint().apply { isAntiAlias = true; color = c.ground }
        cv.drawRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), fill)
        val ink = Paint().apply { isAntiAlias = true; color = c.ink }
        val line = Paint().apply { isAntiAlias = true; color = c.ink; style = PaintingStyle.Stroke; strokeWidth = PPI * 0.12f }
        val i = PPI
        when (p.key) {
            "stripe" -> {
                // A wide band, then a pinstripe either side of the gap.
                cv.drawRect(Rect(0f, 0f, 2.5f * i, h.toFloat()), ink)
                cv.drawRect(Rect(3.6f * i, 0f, 3.85f * i, h.toFloat()), ink)
                cv.drawRect(Rect(4.9f * i, 0f, 5.15f * i, h.toFloat()), ink)
            }
            "damask" -> {
                // One medallion centred, quarter medallions at the corners: a half-drop when tiled.
                val centres = listOf(Offset(w / 2f, h / 2f), Offset(0f, 0f), Offset(w.toFloat(), 0f), Offset(0f, h.toFloat()), Offset(w.toFloat(), h.toFloat()))
                for (o in centres) medallion(cv, o, 8.5f * i, 10.5f * i, ink, c.ground)
            }
            "grasscloth" -> {
                // Fibres across the panel in uneven weights, and the seam at each panel edge.
                var seed = 7
                fun rnd(): Float { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return (seed % 1000) / 1000f }
                var y = 0f
                while (y < h) {
                    val t = 0.03f + rnd() * 0.12f
                    val a = 0.15f + rnd() * 0.45f
                    cv.drawRect(Rect(0f, y, w.toFloat(), y + t * i), Paint().apply { color = c.ink.copy(alpha = a) })
                    y += t * i + rnd() * 0.18f * i
                }
                cv.drawRect(Rect(0f, 0f, 0.06f * i, h.toFloat()), Paint().apply { color = c.ink.copy(alpha = 0.7f) })
            }
            "geometric" -> {
                // A diamond trellis with a dot in each diamond.
                val path = Path().apply {
                    moveTo(w / 2f, 0f); lineTo(w.toFloat(), h / 2f); lineTo(w / 2f, h.toFloat()); lineTo(0f, h / 2f); close()
                }
                cv.drawPath(path, line)
                cv.drawCircle(Offset(w / 2f, h / 2f), 0.35f * i, ink)
                for (o in listOf(Offset(0f, 0f), Offset(w.toFloat(), 0f), Offset(0f, h.toFloat()), Offset(w.toFloat(), h.toFloat()))) cv.drawCircle(o, 0.35f * i, ink)
            }
            "floral" -> {
                // Large blooms on a half-drop with smaller buds and leaves between.
                for (o in listOf(Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), Offset(w * 1.25f, h * 0.25f), Offset(w * -0.25f, h * 0.75f), Offset(w * 0.25f, h * 1.25f), Offset(w * 0.75f, h * -0.25f))) {
                    bloom(cv, o, 3.2f * i, 6, ink, c.ground)
                }
                for (o in listOf(Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), Offset(w * 0.25f, h * -0.25f), Offset(w * 0.75f, h * 1.25f))) {
                    bloom(cv, o, 1.4f * i, 5, ink, c.ground)
                    leaf(cv, o + Offset(1.9f * i, 0.6f * i), 1.6f * i, 25f, ink)
                    leaf(cv, o + Offset(-1.9f * i, -0.6f * i), 1.6f * i, 205f, ink)
                }
            }
        }
        return img
    }

    private fun bloom(cv: Canvas, o: Offset, r: Float, petals: Int, ink: Paint, ground: Color) {
        for (k in 0 until petals) {
            val a = 2.0 * PI * k / petals
            val pc = o + Offset((cos(a) * r * 0.55).toFloat(), (sin(a) * r * 0.55).toFloat())
            cv.drawCircle(pc, r * 0.45f, ink)
        }
        cv.drawCircle(o, r * 0.3f, Paint().apply { isAntiAlias = true; color = ground })
        cv.drawCircle(o, r * 0.15f, ink)
    }

    private fun leaf(cv: Canvas, o: Offset, len: Float, deg: Float, ink: Paint) {
        val a = deg * PI / 180.0
        val dx = (cos(a) * len / 2).toFloat()
        val dy = (sin(a) * len / 2).toFloat()
        val nx = -dy * 0.45f
        val ny = dx * 0.45f
        val path = Path().apply {
            moveTo(o.x - dx, o.y - dy)
            quadraticTo(o.x + nx, o.y + ny, o.x + dx, o.y + dy)
            quadraticTo(o.x - nx, o.y - ny, o.x - dx, o.y - dy)
            close()
        }
        cv.drawPath(path, ink)
    }

    /** A damask medallion: a pointed oval (ogee) with a smaller one cut in it, leaves either side, a finial above and below. */
    private fun medallion(cv: Canvas, o: Offset, halfW: Float, halfH: Float, ink: Paint, ground: Color) {
        fun ogee(sx: Float, sy: Float): Path = Path().apply {
            moveTo(o.x, o.y - halfH * sy)
            cubicTo(o.x + halfW * sx * 0.2f, o.y - halfH * sy * 0.55f, o.x + halfW * sx, o.y - halfH * sy * 0.45f, o.x + halfW * sx * 0.75f, o.y)
            cubicTo(o.x + halfW * sx, o.y + halfH * sy * 0.45f, o.x + halfW * sx * 0.2f, o.y + halfH * sy * 0.55f, o.x, o.y + halfH * sy)
            cubicTo(o.x - halfW * sx * 0.2f, o.y + halfH * sy * 0.55f, o.x - halfW * sx, o.y + halfH * sy * 0.45f, o.x - halfW * sx * 0.75f, o.y)
            cubicTo(o.x - halfW * sx, o.y - halfH * sy * 0.45f, o.x - halfW * sx * 0.2f, o.y - halfH * sy * 0.55f, o.x, o.y - halfH * sy)
            close()
        }
        val cut = Paint().apply { isAntiAlias = true; color = ground }
        cv.drawPath(ogee(0.62f, 0.62f), ink)
        cv.drawPath(ogee(0.42f, 0.42f), cut)
        cv.drawPath(ogee(0.24f, 0.24f), ink)
        cv.drawCircle(o, halfW * 0.06f, cut)
        // Leaves sweeping out from the waist, and the finials.
        leaf(cv, o + Offset(halfW * 0.62f, -halfH * 0.18f), halfW * 0.5f, -35f, ink)
        leaf(cv, o + Offset(-halfW * 0.62f, -halfH * 0.18f), halfW * 0.5f, 215f, ink)
        leaf(cv, o + Offset(halfW * 0.62f, halfH * 0.18f), halfW * 0.5f, 35f, ink)
        leaf(cv, o + Offset(-halfW * 0.62f, halfH * 0.18f), halfW * 0.5f, 145f, ink)
        cv.drawCircle(o + Offset(0f, -halfH * 0.75f), halfW * 0.07f, ink)
        cv.drawCircle(o + Offset(0f, halfH * 0.75f), halfW * 0.07f, ink)
    }
}
