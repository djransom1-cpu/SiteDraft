package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingTool
import com.example.sitedraft.ui.canvas.Fill
import com.example.sitedraft.ui.canvas.FillPattern
import com.example.sitedraft.ui.canvas.fillOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FillTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `fill round-trips through project serialization, including the null colour`() {
        val state = CanvasState(
            elements = listOf(
                DrawingElement.Rectangle(
                    topLeft = Offset(0f, 0f), size = Size(100f, 50f), color = Color.White, strokeWidth = 2f,
                    fill = Fill(FillPattern.CROSSHATCH, Color.Red, spacing = 12f)
                ),
                DrawingElement.Circle(
                    center = Offset(10f, 10f), radius = 5f, color = Color.White, strokeWidth = 2f,
                    fill = Fill(FillPattern.BRICK, color = null)
                )
            )
        )
        val restored = json.decodeFromString(CanvasState.serializer(), json.encodeToString(CanvasState.serializer(), state))
        assertEquals(Fill(FillPattern.CROSSHATCH, Color.Red, 12f), restored.elements[0].fillOrNull())
        assertEquals(Fill(FillPattern.BRICK, null), restored.elements[1].fillOrNull())
    }

    @Test
    fun `old project files without a fill still load with no fill`() {
        val state = CanvasState(
            elements = listOf(DrawingElement.Rectangle(topLeft = Offset.Zero, size = Size(10f, 10f), color = Color.White, strokeWidth = 1f))
        )
        val encoded = json.encodeToString(CanvasState.serializer(), state)
        // Simulate a pre-fill file: strip the fill object entirely.
        val stripped = encoded.replace(Regex("\"fill\"\\s*:\\s*\\{[^}]*\\},?"), "")
        val restored = json.decodeFromString(CanvasState.serializer(), stripped)
        assertEquals(Fill(), restored.elements[0].fillOrNull())
    }

    @Test
    fun `line elements have no fill`() {
        val line = DrawingElement.Line(start = Offset.Zero, end = Offset(10f, 0f), color = Color.White, strokeWidth = 1f)
        assertNull(line.fillOrNull())
    }

    @Test
    fun `fill properties update through the property event`() {
        val manager = DrawingManager()
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.RECTANGLE))
        manager.onEvent(DrawingEvent.Start(Offset(100f, 100f)))
        manager.onEvent(DrawingEvent.Move(Offset(300f, 250f)))
        manager.onEvent(DrawingEvent.End)
        val id = manager.state.value.elements.last().id

        manager.onEvent(DrawingEvent.UpdateElementProperty(id, "fillPattern", "BRICK"))
        manager.onEvent(DrawingEvent.UpdateElementProperty(id, "fillColor", "#FF0000"))
        manager.onEvent(DrawingEvent.UpdateElementProperty(id, "fillSpacing", "20"))
        assertEquals(Fill(FillPattern.BRICK, Color(0xFFFF0000), 20f), manager.state.value.elements.last().fillOrNull())

        manager.onEvent(DrawingEvent.UpdateElementProperty(id, "fillColor", "auto"))
        assertEquals(Fill(FillPattern.BRICK, null, 20f), manager.state.value.elements.last().fillOrNull())

        // Unknown pattern names and junk spacing are ignored rather than crashing.
        manager.onEvent(DrawingEvent.UpdateElementProperty(id, "fillPattern", "NOPE"))
        manager.onEvent(DrawingEvent.UpdateElementProperty(id, "fillSpacing", "wide"))
        assertEquals(Fill(FillPattern.BRICK, null, 20f), manager.state.value.elements.last().fillOrNull())
    }

    @Test
    fun `fill line width updates through the property event`() {
        val manager = DrawingManager()
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.RECTANGLE))
        manager.onEvent(DrawingEvent.Start(Offset(100f, 100f)))
        manager.onEvent(DrawingEvent.Move(Offset(300f, 250f)))
        manager.onEvent(DrawingEvent.End)
        val id = manager.state.value.elements.last().id
        manager.onEvent(DrawingEvent.UpdateElementProperty(id, "fillPattern", "HEXAGON"))
        manager.onEvent(DrawingEvent.UpdateElementProperty(id, "fillLineWidth", "2.5"))
        assertEquals(Fill(FillPattern.HEXAGON, null, 10f, 2.5f), manager.state.value.elements.last().fillOrNull())
    }

    @Test
    fun `eraser removes a pen stroke it crosses but leaves shapes alone`() {
        val manager = DrawingManager()
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.RECTANGLE))
        manager.onEvent(DrawingEvent.Start(Offset(400f, 400f)))
        manager.onEvent(DrawingEvent.Move(Offset(500f, 500f)))
        manager.onEvent(DrawingEvent.End)
        // A sparse pen stroke: only two recorded points, 100 px apart.
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.PEN))
        manager.onEvent(DrawingEvent.Start(Offset(100f, 100f)))
        manager.onEvent(DrawingEvent.Move(Offset(200f, 100f)))
        manager.onEvent(DrawingEvent.End)
        assertEquals(2, manager.state.value.elements.size)

        // Erase through the middle of the stroke, well away from either recorded point.
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.ERASER))
        manager.onEvent(DrawingEvent.Start(Offset(150f, 130f)))
        manager.onEvent(DrawingEvent.Move(Offset(150f, 105f)))
        manager.onEvent(DrawingEvent.End)

        val remaining = manager.state.value.elements
        assertEquals(1, remaining.size)
        assertEquals(true, remaining[0] is DrawingElement.Rectangle)
        // Nothing new was created by the eraser gesture, and undo brings the stroke back.
        manager.onEvent(DrawingEvent.Undo)
        assertEquals(2, manager.state.value.elements.size)
    }
}
