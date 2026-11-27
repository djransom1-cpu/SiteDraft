package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.PlanSymbols
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan drawing order (Dan, Nov 25: a cooktop placed before its base cabinet never showed). */
class PlanOrderTest {
    private fun sym(name: String, cat: String) = DrawingElement.Symbol(id = name, symbolName = name, category = cat,
        color = Color.White, center = Offset.Zero, size = Size(100f, 100f))

    @Test
    fun `cooktops and counter sinks draw over the cabinets, uppers over everything`() {
        val cooktop = sym("Cooktop 36\"", PlanSymbols.APPLIANCES)
        val base = sym("Base 36\"", PlanSymbols.CABINETS)
        val sink = sym("Kitchen Sink", PlanSymbols.FIXTURES)
        val toilet = sym("Toilet", PlanSymbols.FIXTURES)
        assertEquals(0, PlanSymbols.planTier(base))
        assertEquals(0, PlanSymbols.planTier(toilet))
        assertEquals(1, PlanSymbols.planTier(cooktop))
        assertEquals(1, PlanSymbols.planTier(sink))
        // Placed cooktop first, cabinet second: the cabinet still draws first.
        assertEquals(listOf(base, cooktop), listOf<DrawingElement>(cooktop, base).sortedBy { PlanSymbols.planTier(it) })
    }
}
