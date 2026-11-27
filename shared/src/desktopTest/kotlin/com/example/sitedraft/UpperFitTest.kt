package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.Appliances
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.UpperFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Uppers over a sink or a cooktop come out shorter (Dan, Nov 25). */
class UpperFitTest {
    private val inch = 50f / 12f

    /** A symbol against a north wall at y = 0: [x] its left edge, [w] wide, [d] deep, inches. */
    private fun sym(id: String, name: String, cat: String, x: Float, w: Float, d: Float, props: Map<String, Float> = emptyMap()) =
        DrawingElement.Symbol(id = id, symbolName = name, category = cat, color = Color.White,
            center = Offset((x + w / 2f) * inch, d / 2f * inch), size = Size(w * inch, d * inch), properties = props)

    private fun upper(x: Float = 0f, props: Map<String, Float> = emptyMap()) = sym("u", "Upper 30\"", PlanSymbols.CABINETS, x, 30f, 12f, props)
    private fun heightOf(s: CanvasState) = (s.elements.first { it.id == "u" } as DrawingElement.Symbol).properties[PlanSymbols.PROP_UPPER_HEIGHT_IN]

    @Test
    fun `over a sink the bottom is 36 inches above the counter`() {
        val sink = sym("s", "Kitchen Sink", PlanSymbols.FIXTURES, -1.5f, 33f, 22f)
        val s = UpperFit.sync(CanvasState(elements = listOf(upper(), sink)))
        // Top at 84 (54 + 30), bottom at 36 + 36 = 72: 12" tall.
        assertEquals(12f, heightOf(s)!!, 0.01f)
    }

    @Test
    fun `over a cooktop 24 inches to the hood, then the hood`() {
        val cooktop = sym("c", "Cooktop 30\"", PlanSymbols.APPLIANCES, 0f, 30f, 21f)
        assertEquals(84f - (36f + 24f + 10f), heightOf(UpperFit.sync(CanvasState(elements = listOf(upper(), cooktop))))!!, 0.01f)
        // A slim under-cabinet hood set at 66": the cabinet starts over it.
        val hood = sym("h", "Under-Cabinet Hood 30\"", PlanSymbols.HOODS, 0f, 30f, 18f, mapOf(Appliances.HOOD_BOTTOM to 66f))
        assertEquals(84f - (66f + 6f), heightOf(UpperFit.sync(CanvasState(elements = listOf(upper(), cooktop, hood))))!!, 0.01f)
    }

    @Test
    fun `a height typed by hand stays, and an automatic one goes when the sink moves away`() {
        val sink = sym("s", "Kitchen Sink", PlanSymbols.FIXTURES, 0f, 33f, 22f)
        val manual = upper(props = mapOf(PlanSymbols.PROP_UPPER_HEIGHT_IN to 24f))
        assertEquals(24f, heightOf(UpperFit.sync(CanvasState(elements = listOf(manual, sink))))!!, 0.01f)
        val fitted = UpperFit.sync(CanvasState(elements = listOf(upper(), sink)))
        val moved = fitted.copy(elements = fitted.elements.map { if (it.id == "s") sym("s", "Kitchen Sink", PlanSymbols.FIXTURES, 200f, 33f, 22f) else it })
        assertNull(heightOf(UpperFit.sync(moved)))
        // An upper over nothing is left alone.
        assertNull(heightOf(UpperFit.sync(CanvasState(elements = listOf(upper())))))
    }
}
