package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * Text on the canvas is drawn with Compose's own text layout so it looks the same on Android,
 * desktop, web and iOS (the old code used Android's Paint). The measurer is created in the
 * composable that owns the canvas and shared here, because the drawing code also runs outside
 * composition (PDF export).
 */
object TextDrawing {
    var measurer: TextMeasurer? = null
}

/** A one-line label centred on [center], [sizePx] tall in world units, turned by [rotationDegrees]. */
fun DrawScope.drawLabel(
    text: String,
    center: Offset,
    sizePx: Float,
    color: Color,
    bold: Boolean = true,
    rotationDegrees: Float = 0f
) {
    val measurer = TextDrawing.measurer ?: return
    if (sizePx <= 0f) return
    val style = TextStyle(
        color = color,
        fontSize = sizePx.toSp(),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
    )
    val layout = measurer.measure(AnnotatedString(text), style)
    val topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    rotate(rotationDegrees, pivot = center) {
        drawText(layout, topLeft = topLeft)
    }
}

/** A text element: multi-line, with its font, weight, style and alignment. */
fun DrawScope.drawTextElement(element: DrawingElement.Text) {
    val measurer = TextDrawing.measurer ?: return
    val family = when (element.fontFamily) {
        "Serif" -> FontFamily.Serif
        "Monospace" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val style = TextStyle(
        color = element.color,
        fontSize = element.fontSize.toSp(),
        fontFamily = family,
        fontWeight = if (element.isBold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (element.isItalic) FontStyle.Italic else FontStyle.Normal,
        textAlign = when (element.textAlign) {
            "Center" -> TextAlign.Center
            "Right" -> TextAlign.Right
            else -> TextAlign.Left
        },
        lineHeight = (element.fontSize * 1.2f).toSp()
    )
    val prefix: (Int) -> String = when (element.listType) {
        TextListType.BULLET -> { _ -> "• " }
        TextListType.NUMBER -> { n -> "${n + 1}. " }
        else -> { _ -> "" }
    }
    val content = element.text.lines().mapIndexed { n, line -> prefix(n) + line }.joinToString("\n")
    val layout = measurer.measure(AnnotatedString(content), style)
    // Same anchor convention as before: Centre/Right text is centred on / ends at a point a
    // little right of topLeft, so existing drawings keep their placement.
    val x = when (element.textAlign) {
        "Center" -> element.topLeft.x + 50f - layout.size.width / 2f
        "Right" -> element.topLeft.x + 100f - layout.size.width
        else -> element.topLeft.x
    }
    drawText(layout, topLeft = Offset(x, element.topLeft.y))
}
