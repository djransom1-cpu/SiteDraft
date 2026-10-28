package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin

@Serializable
enum class FillPattern(val label: String) {
    NONE("None"),
    SOLID("Solid"),
    DIAGONAL("Hatch 45°"),
    DIAGONAL_BACK("Hatch 135°"),
    CROSSHATCH("Crosshatch"),
    CONCRETE("Concrete"),
    BRICK("Brick"),
    INSULATION("Insulation"),
    EARTH("Earth"),
    GRAVEL("Gravel"),
    TILE("Tile"),
    HERRINGBONE("Herringbone"),
    HEXAGON("Hex tile"),
    PLANKS("Planks"),
    SHINGLE("Shingle"),
    DOTS("Dots"),
    WAVES("Water"),
    STEEL("Steel"),
    GRASS("Grass"),
}

/**
 * Fill for a closed shape. [color] null means "same as the shape's line colour", so a plain
 * hatch follows the outline automatically (and flips with the canvas colour like everything
 * else). [spacing] and [lineWidth] are in world px, like element stroke widths, so the hatch
 * scales with the drawing and exports at the same density it is drawn at.
 */
@Serializable
data class Fill(
    val pattern: FillPattern = FillPattern.NONE,
    @Serializable(with = ColorSerializer::class)
    val color: Color? = null,
    val spacing: Float = 10f,
    val lineWidth: Float = 1f,
)

/** The fill of a closed shape, or null for element types that cannot be filled. */
fun DrawingElement.fillOrNull(): Fill? = when (this) {
    is DrawingElement.Rectangle -> fill
    is DrawingElement.Oval -> fill
    is DrawingElement.Triangle -> fill
    is DrawingElement.Circle -> fill
    is DrawingElement.Freehand -> fill
    else -> null
}

fun DrawingElement.withFill(fill: Fill): DrawingElement = when (this) {
    is DrawingElement.Rectangle -> copy(fill = fill)
    is DrawingElement.Oval -> copy(fill = fill)
    is DrawingElement.Triangle -> copy(fill = fill)
    is DrawingElement.Circle -> copy(fill = fill)
    is DrawingElement.Freehand -> copy(fill = fill)
    else -> this
}

/**
 * The closed outline (in the element's own, un-rotated frame - callers draw inside the same
 * rotate() the outline is drawn in) plus its bounds, or null for open/unfillable elements.
 * A Freehand stroke is closed back to its first point; fewer than three points is a line.
 */
fun DrawingElement.closedOutline(): Pair<Path, Rect>? = when (this) {
    is DrawingElement.Rectangle -> Rect(topLeft, size).let { r -> Path().apply { addRect(r) } to r }
    is DrawingElement.Oval -> Rect(topLeft, size).let { r -> Path().apply { addOval(r) } to r }
    is DrawingElement.Circle -> Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
        .let { r -> Path().apply { addOval(r) } to r }
    is DrawingElement.Triangle -> {
        val (a, b, c) = getVertices()
        Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() } to Rect(topLeft, size)
    }
    is DrawingElement.Freehand -> if (points.size < 3) null else {
        val path = Path().apply {
            moveTo(points[0].offset.x, points[0].offset.y)
            for (i in 1 until points.size) lineTo(points[i].offset.x, points[i].offset.y)
            close()
        }
        path to boundingRect()
    }
    else -> null
}

/** Draws [fill] clipped to [outline]. No-op for [FillPattern.NONE]. */
fun DrawScope.drawShapeFill(outline: Path, bounds: Rect, fill: Fill, strokeColor: Color) {
    if (fill.pattern == FillPattern.NONE || bounds.isEmpty) return
    val color = fill.color ?: strokeColor
    val spacing = fill.spacing.coerceAtLeast(2f)
    val lw = fill.lineWidth.coerceAtLeast(0.5f)
    clipPath(outline) {
        when (fill.pattern) {
            FillPattern.NONE -> Unit
            FillPattern.SOLID -> drawRect(color, bounds.topLeft, bounds.size)
            FillPattern.DIAGONAL -> hatch45(bounds, color, spacing, lw, forward = true)
            FillPattern.DIAGONAL_BACK -> hatch45(bounds, color, spacing, lw, forward = false)
            FillPattern.CROSSHATCH -> {
                hatch45(bounds, color, spacing, lw, forward = true)
                hatch45(bounds, color, spacing, lw, forward = false)
            }
            FillPattern.CONCRETE -> concrete(bounds, color, spacing, lw)
            FillPattern.BRICK -> brick(bounds, color, spacing, lw)
            FillPattern.INSULATION -> insulation(bounds, color, spacing, lw)
            FillPattern.EARTH -> earth(bounds, color, spacing, lw)
            FillPattern.GRAVEL -> gravel(bounds, color, spacing, lw)
            FillPattern.TILE -> tile(bounds, color, spacing, lw)
            FillPattern.HERRINGBONE -> herringbone(bounds, color, spacing, lw)
            FillPattern.HEXAGON -> hexagon(bounds, color, spacing, lw)
            FillPattern.PLANKS -> planks(bounds, color, spacing, lw)
            FillPattern.SHINGLE -> shingle(bounds, color, spacing, lw)
            FillPattern.DOTS -> dots(bounds, color, spacing, lw)
            FillPattern.WAVES -> waves(bounds, color, spacing, lw)
            FillPattern.STEEL -> steel(bounds, color, spacing, lw)
            FillPattern.GRASS -> grass(bounds, color, spacing, lw)
        }
    }
}

// Square tile grid with `spacing` tiles.
private fun DrawScope.tile(b: Rect, color: Color, spacing: Float, lw: Float) {
    var x = b.left
    while (x <= b.right) {
        drawLine(color, Offset(x, b.top), Offset(x, b.bottom), lw)
        x += spacing
    }
    var y = b.top
    while (y <= b.bottom) {
        drawLine(color, Offset(b.left, y), Offset(b.right, y), lw)
        y += spacing
    }
}

// Herringbone / chevron: rows of 45° zigzags whose legs span `spacing` in both axes, stacked
// `spacing` apart so adjacent rows touch and read as a parquet.
private fun DrawScope.herringbone(b: Rect, color: Color, spacing: Float, lw: Float) {
    var y = b.top - spacing
    while (y < b.bottom + spacing) {
        val path = Path().apply {
            moveTo(b.left, y)
            var x = b.left
            var up = false
            while (x <= b.right) {
                x += spacing
                lineTo(x, if (up) y else y + spacing)
                up = !up
            }
        }
        drawPath(path, color, style = Stroke(lw))
        y += spacing
    }
}

// Honeycomb of pointy-top hexagons with circumradius `spacing`.
private fun DrawScope.hexagon(b: Rect, color: Color, spacing: Float, lw: Float) {
    val r = spacing
    val w = 1.7320508f * r
    val h = 1.5f * r
    var j = 0
    var cy = b.top
    while (cy < b.bottom + r) {
        var cx = b.left + if (j % 2 == 0) 0f else w / 2f
        while (cx < b.right + w) {
            val path = Path()
            for (k in 0..5) {
                val a = Math.toRadians(60.0 * k - 30.0).toFloat()
                val px = cx + r * cos(a)
                val py = cy + r * sin(a)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, color, style = Stroke(lw))
            cx += w
        }
        cy += h
        j++
    }
}

// Wood flooring: boards `spacing` wide, six times as long, end joints staggered per row.
private fun DrawScope.planks(b: Rect, color: Color, spacing: Float, lw: Float) {
    val len = spacing * 6f
    var y = b.top
    var row = 0
    while (y <= b.bottom) {
        drawLine(color, Offset(b.left, y), Offset(b.right, y), lw)
        var x = b.left + ((row * 2f * spacing) % len)
        while (x <= b.right) {
            drawLine(color, Offset(x, y), Offset(x, minOf(y + spacing, b.bottom)), lw)
            x += len
        }
        y += spacing
        row++
    }
}

// Fish-scale shingles: rows of semicircles, each row offset by half a scale.
private fun DrawScope.shingle(b: Rect, color: Color, spacing: Float, lw: Float) {
    val r = spacing / 2f
    var j = 0
    var y = b.top
    while (y < b.bottom + r) {
        var x = b.left + if (j % 2 == 0) 0f else r
        while (x < b.right + r) {
            drawArc(
                color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(x - r, y - r), size = Size(spacing, spacing), style = Stroke(lw)
            )
            x += spacing
        }
        y += r
        j++
    }
}

private fun DrawScope.dots(b: Rect, color: Color, spacing: Float, lw: Float) {
    var y = b.top
    while (y <= b.bottom) {
        var x = b.left
        while (x <= b.right) {
            drawCircle(color, lw * 1.2f, Offset(x, y))
            x += spacing
        }
        y += spacing
    }
}

// Water: gently waving horizontal lines.
private fun DrawScope.waves(b: Rect, color: Color, spacing: Float, lw: Float) {
    var y = b.top
    while (y <= b.bottom) {
        val path = Path().apply {
            moveTo(b.left, y)
            var x = b.left
            var up = true
            while (x < b.right) {
                quadraticTo(x + spacing / 2f, if (up) y - spacing * 0.4f else y + spacing * 0.4f, x + spacing, y)
                x += spacing
                up = !up
            }
        }
        drawPath(path, color, style = Stroke(lw))
        y += spacing
    }
}

// ANSI-style steel: paired 45° lines.
private fun DrawScope.steel(b: Rect, color: Color, spacing: Float, lw: Float) {
    hatch45(b, color, spacing, lw, forward = true)
    val step = spacing * 1.41421f
    val span = b.width + b.height
    var d = spacing * 0.35f
    while (d <= span) {
        drawLine(color, Offset(b.left + d, b.top), Offset(b.left + d - b.height, b.bottom), lw)
        d += step
    }
}

// Site-plan grass: small three-blade tufts on a staggered grid.
private fun DrawScope.grass(b: Rect, color: Color, spacing: Float, lw: Float) {
    val h = spacing * 0.35f
    var j = 0
    var y = b.top
    while (y < b.bottom) {
        var x = b.left + if (j % 2 == 0) 0f else spacing / 2f
        while (x < b.right) {
            val base = Offset(x, y + h)
            drawLine(color, base, Offset(x, y), lw)
            drawLine(color, base, Offset(x - h * 0.6f, y + h * 0.3f), lw)
            drawLine(color, base, Offset(x + h * 0.6f, y + h * 0.3f), lw)
            x += spacing
        }
        y += spacing
        j++
    }
}

// Deterministic 0..1 "random" per grid cell, so patterns don't shimmer between frames or
// differ between the screen and the PDF.
private fun jitter(i: Int, j: Int, salt: Int): Float {
    var h = i * 374761393 + j * 668265263 + salt * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65535f
}

// 45° lines ("/" when forward, "\" otherwise) at perpendicular distance `spacing`.
private fun DrawScope.hatch45(b: Rect, color: Color, spacing: Float, lw: Float, forward: Boolean) {
    val step = spacing * 1.41421f
    val span = b.width + b.height
    var d = 0f
    while (d <= span) {
        if (forward) {
            drawLine(color, Offset(b.left + d, b.top), Offset(b.left + d - b.height, b.bottom), lw)
        } else {
            drawLine(color, Offset(b.left + d - b.height, b.top), Offset(b.left + d, b.bottom), lw)
        }
        d += step
    }
}

// Running bond: courses `spacing` high, bricks twice as long, joints staggered per course.
private fun DrawScope.brick(b: Rect, color: Color, spacing: Float, lw: Float) {
    val brickLen = spacing * 2f
    var y = b.top
    var row = 0
    while (y <= b.bottom) {
        drawLine(color, Offset(b.left, y), Offset(b.right, y), lw)
        var x = b.left + if (row % 2 == 0) 0f else brickLen / 2f
        while (x <= b.right) {
            drawLine(color, Offset(x, y), Offset(x, minOf(y + spacing, b.bottom)), lw)
            x += brickLen
        }
        y += spacing
        row++
    }
}

// Batt insulation: zigzag bands two `spacing` high.
private fun DrawScope.insulation(b: Rect, color: Color, spacing: Float, lw: Float) {
    val bandHeight = spacing * 2f
    var y = b.top
    while (y < b.bottom) {
        val yBottom = y + bandHeight
        val path = Path().apply {
            moveTo(b.left, yBottom)
            var x = b.left
            var up = true
            while (x <= b.right) {
                x += spacing
                lineTo(x, if (up) y else yBottom)
                up = !up
            }
        }
        drawPath(path, color, style = Stroke(lw))
        y = yBottom
    }
}

// Concrete: scattered dots with the occasional small aggregate triangle.
private fun DrawScope.concrete(b: Rect, color: Color, spacing: Float, lw: Float) {
    val cell = spacing * 1.5f
    var j = 0
    var y = b.top
    while (y < b.bottom) {
        var i = 0
        var x = b.left
        while (x < b.right) {
            val cx = x + jitter(i, j, 1) * cell
            val cy = y + jitter(i, j, 2) * cell
            if ((i + j) % 3 == 0) {
                val r = spacing * 0.35f
                val tri = Path().apply {
                    moveTo(cx, cy - r); lineTo(cx + r, cy + r * 0.7f); lineTo(cx - r, cy + r * 0.7f); close()
                }
                drawPath(tri, color, style = Stroke(lw))
            } else {
                drawCircle(color, lw * 1.2f, Offset(cx, cy))
            }
            x += cell
            i++
        }
        y += cell
        j++
    }
}

// Earth: short 45° dashes on a staggered grid.
private fun DrawScope.earth(b: Rect, color: Color, spacing: Float, lw: Float) {
    val len = spacing * 0.6f
    var j = 0
    var y = b.top
    while (y < b.bottom) {
        var x = b.left + if (j % 2 == 0) 0f else spacing / 2f
        while (x < b.right) {
            drawLine(color, Offset(x, y + len), Offset(x + len, y), lw)
            x += spacing
        }
        y += spacing * 0.75f
        j++
    }
}

// Gravel: small irregular stones on a staggered grid.
private fun DrawScope.gravel(b: Rect, color: Color, spacing: Float, lw: Float) {
    val cell = spacing * 1.3f
    var j = 0
    var y = b.top
    while (y < b.bottom) {
        var i = 0
        var x = b.left + if (j % 2 == 0) 0f else cell / 2f
        while (x < b.right) {
            val rx = spacing * 0.3f * (0.7f + 0.6f * jitter(i, j, 3))
            val ry = rx * (0.6f + 0.4f * jitter(i, j, 4))
            drawOval(color, Offset(x - rx, y - ry), Size(rx * 2f, ry * 2f), style = Stroke(lw))
            x += cell
            i++
        }
        y += cell
        j++
    }
}
