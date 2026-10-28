package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingTool
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Things a new line's start point can snap onto besides rectangles and lines: pen strokes,
 * triangles, ovals, dimensions, and the edges of rotated shapes where they are actually drawn.
 * Default scale 1' = 50px; the snap grid is 1" (~4.17px) and always closer than the edge here.
 */
class SnapTargetsTest {

    private fun draw(manager: DrawingManager, tool: DrawingTool, points: List<Offset>) {
        manager.onEvent(DrawingEvent.SelectTool(tool))
        manager.onEvent(DrawingEvent.Start(points.first()))
        points.drop(1).forEach { manager.onEvent(DrawingEvent.Move(it)) }
        manager.onEvent(DrawingEvent.End)
    }

    private fun draw(manager: DrawingManager, tool: DrawingTool, from: Offset, to: Offset) =
        draw(manager, tool, listOf(from, to))

    private fun lineStartedAt(manager: DrawingManager, at: Offset): Offset {
        draw(manager, DrawingTool.LINE, at, at + Offset(300f, 300f))
        return (manager.state.value.elements.last() as DrawingElement.Line).start
    }

    @Test
    fun `a line snaps onto a pen stroke`() {
        val m = DrawingManager()
        draw(m, DrawingTool.PEN, listOf(Offset(0f, 100f), Offset(200f, 100f), Offset(400f, 100f)))
        assertEquals(DrawingElement.Freehand::class, m.state.value.elements.last()::class)
        val start = lineStartedAt(m, Offset(150f, 108f))
        assertEquals(150f, start.x, 0.01f)
        assertEquals(100f, start.y, 0.01f)
    }

    @Test
    fun `a line snaps onto the end of a pen stroke`() {
        val m = DrawingManager()
        draw(m, DrawingTool.PEN, listOf(Offset(0f, 100f), Offset(200f, 100f), Offset(400f, 100f)))
        val start = lineStartedAt(m, Offset(410f, 92f))
        assertEquals(400f, start.x, 0.01f)
        assertEquals(100f, start.y, 0.01f)
    }

    @Test
    fun `a line snaps onto the edge of a rotated rectangle where it is drawn`() {
        val m = DrawingManager()
        draw(m, DrawingTool.RECTANGLE, Offset(100f, 100f), Offset(300f, 250f))
        val rect = m.state.value.elements.last() as DrawingElement.Rectangle
        m.onEvent(DrawingEvent.UpdateElementProperty(rect.id, "rotation", "90"))
        // Turned a quarter clockwise about (200,175), the old right edge now lies along y=275
        // between x=125 and x=275. Aim 8px below it, away from its midpoint.
        val start = lineStartedAt(m, Offset(230f, 283f))
        assertEquals(230f, start.x, 0.01f)
        assertEquals(275f, start.y, 0.01f)
    }

    @Test
    fun `a line snaps onto a triangle's base`() {
        val m = DrawingManager()
        draw(m, DrawingTool.TRIANGLE, Offset(100f, 100f), Offset(300f, 300f))
        val start = lineStartedAt(m, Offset(240f, 308f))
        assertEquals(240f, start.x, 0.01f)
        assertEquals(300f, start.y, 0.01f)
    }

    @Test
    fun `a line snaps onto an oval`() {
        val m = DrawingManager()
        draw(m, DrawingTool.OVAL, Offset(100f, 100f), Offset(300f, 200f))
        // Ellipse centre (200,150), radii 100 x 50. Nearest point in this direction ~ (234.2, 197.0).
        val start = lineStartedAt(m, Offset(240f, 205f))
        assertEquals(234.2f, start.x, 0.5f)
        assertEquals(197.0f, start.y, 0.5f)
    }
}
