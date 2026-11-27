package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.CoverSheet
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FillPattern
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PaintColors
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.WallDecor
import com.example.sitedraft.ui.canvas.WallDecorations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paint on a wall face (Dan, Nov 26, for Natalie's demo): Sherwin-Williams colours or any hex. */
class PaintTest {
    private val inch = 50f / 12f
    private val wall = DrawingElement.Symbol(id = "w", symbolName = FloorPlan.EXTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset(60f * inch, 3f * inch), size = Size(120f * inch, 6f * inch))
    private val spec = ElevationSpec(wallId = "w", side = ViewerSide.BELOW)

    @Test
    fun `the Sherwin-Williams colours load and can be found by number or name`() {
        assertTrue(PaintColors.all.size > 1000)
        val beige = PaintColors.find("SW 7036")
        assertNotNull(beige)
        assertEquals("Accessible Beige", beige!!.name)
        assertEquals(beige, PaintColors.find("7036"))
        assertEquals(beige, PaintColors.find("sw7036"))
        assertEquals(beige, PaintColors.search("7036").first())
        assertTrue(PaintColors.search("accessible").any { it.code == "SW 7036" })
        assertNull(PaintColors.find("not a number"))
    }

    @Test
    fun `a room elevation paints just its rooms, and a new colour keeps the other rooms' paint`() {
        // Three rooms along a wall: 0-100, 104-200, 204-300.
        val spans = listOf(0f to 100f, 104f to 200f, 204f to 300f)
        assertEquals(0, WallDecorations.roomsShown(spans, 0f, 300f, 300f))          // the whole wall
        assertEquals(0b010, WallDecorations.roomsShown(spans, 104f, 200f, 300f))    // the middle room
        assertEquals(0b011, WallDecorations.roomsShown(spans, 0f, 200f, 300f))
        val whole = WallDecorations.newPaint("w", ViewerSide.BELOW, 0xFFFFFF, "SW 7005", "Pure White")
        val after = WallDecorations.paintsAfter(listOf(whole), 0b010, 3)
        assertEquals(1, after.size)
        assertEquals(0b101, after[0].rooms)                                           // white stays on the other two
        assertTrue(WallDecorations.paintsAfter(after, 0, 3).isEmpty())               // a whole-wall colour replaces all
    }

    @Test
    fun `hex codes read with or without the hash`() {
        assertEquals(0xD1C7B8, WallDecorations.parseHex("#D1C7B8"))
        assertEquals(0xD1C7B8, WallDecorations.parseHex("d1c7b8"))
        assertNull(WallDecorations.parseHex("#12345"))
        assertNull(WallDecorations.parseHex("zzzzzz"))
    }

    @Test
    fun `paint fills the wall face behind everything, is named in the corner and goes in the schedule`() {
        val paint = WallDecorations.newPaint("w", ViewerSide.BELOW, 0xD1C7B8, "SW 7036", "Accessible Beige")
        assertEquals(WallDecor.PAINT, paint.kind)
        assertEquals("SW 7036 Accessible Beige", paint.paintLabel)
        val state = CanvasState(elements = listOf(wall), wallDecor = listOf(paint))
        val parts = Elevation.build(wall, spec, Offset(0f, 2000f), state)
        val fills = parts.filterIsInstance<DrawingElement.Rectangle>().filter { it.fill.pattern == FillPattern.SOLID && it.fill.color == Color(0xFFD1C7B8) }
        assertEquals(1, fills.size)
        assertEquals(120f * inch, fills[0].size.width, 0.5f)
        assertTrue(parts.filterIsInstance<DrawingElement.Text>().any { it.text.contains("SW 7036 ACCESSIBLE BEIGE") })
        // The other face of the wall isn't painted.
        assertTrue(Elevation.build(wall, spec.copy(side = ViewerSide.ABOVE), Offset(0f, 2000f), state)
            .filterIsInstance<DrawingElement.Rectangle>().none { it.fill.color == Color(0xFFD1C7B8) })
        val rows = CoverSheet.paintSchedule(state)
        assertEquals(1, rows.size)
        assertEquals("SW 7036", rows[0].label)
        assertTrue(rows[0].text, rows[0].text.startsWith("Accessible Beige: 10'-0\" wall looking north"))
    }
}
