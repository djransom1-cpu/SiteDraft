package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `serialize and deserialize CanvasState`() {
        val state = CanvasState(
            elements = listOf(
                DrawingElement.Line(
                    start = Offset(10f, 20f),
                    end = Offset(100f, 200f),
                    color = Color.Red,
                    strokeWidth = 5f
                ),
                DrawingElement.Rectangle(
                    topLeft = Offset(50f, 50f),
                    size = Size(100f, 80f),
                    color = Color.Blue,
                    strokeWidth = 2f
                )
            ),
            currentTool = DrawingTool.RECTANGLE,
            currentColor = Color.Green,
            background = DrawingBackground.ISOMETRIC
        )

        val serialized = json.encodeToString(CanvasState.serializer(), state)
        val deserialized = json.decodeFromString(CanvasState.serializer(), serialized)

        assertEquals(state.elements.size, deserialized.elements.size)
        assertEquals(state.currentTool, deserialized.currentTool)
        assertEquals(state.currentColor.value, deserialized.currentColor.value)
        assertEquals(state.background, deserialized.background)
        
        val line = deserialized.elements[0] as DrawingElement.Line
        assertEquals(10f, line.start.x)
        assertEquals(Color.Red.value, line.color.value)
    }
}
