package com.example.sitedraft

import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.RoomNames
import com.example.sitedraft.ui.canvas.inchesToPx
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/** Prints Practice Run 1's unnamed rooms with what stands in them (Nov 28, to name them). Always passes. */
class RoomListProbe {
    @Test
    fun `list unnamed rooms`() {
        val st: CanvasState = Json { ignoreUnknownKeys = true }.decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/practice_run1.json").readText())
        val inch = st.inchesToPx(1f)
        for (r in RoomNames.unnamed(st)) {
            val inside = st.elements.filterIsInstance<DrawingElement.Symbol>().filter { r.contains(it.center) && !it.id.startsWith("gen:") }.map { it.symbolName }.distinct()
            println("ROOM center=(${r.center.x / inch}, ${r.center.y / inch}) size=${r.width / inch} x ${r.height / inch} has=$inside")
        }
    }
}
