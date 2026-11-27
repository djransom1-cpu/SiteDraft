package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FinishSchedule
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoomFinish
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.WallDecor
import com.example.sitedraft.ui.canvas.WallDecorations
import com.example.sitedraft.ui.canvas.Picture
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The finish schedule by room (Dan, Nov 26): floor, walls, ceiling, trim, notes; walls from the drawing. */
class FinishScheduleTest {
    private val inch = 50f / 12f

    /** A 12' x 10' room: four 2x4 walls around 0..144 by 0..120 (inside faces). */
    private fun room(): Pair<CanvasState, List<DrawingElement.Symbol>> {
        fun wall(id: String, x: Float, y: Float, w: Float, h: Float) = DrawingElement.Symbol(id = id, symbolName = "2x4 Wall", category = PlanSymbols.WALLS,
            color = Color.White, center = Offset((x + w / 2f) * inch, (y + h / 2f) * inch), size = Size(w * inch, h * inch))
        val n = wall("n", -4.5f, -4.5f, 153f, 4.5f)
        val s = wall("s", -4.5f, 120f, 153f, 4.5f)
        val w = wall("w", -4.5f, 0f, 4.5f, 120f)
        val e = wall("e", 144f, 0f, 4.5f, 120f)
        val walls = listOf(n, s, w, e)
        return CanvasState(elements = walls) to walls
    }

    @Test
    fun `walls alike show one finish, an accent wall shows wall by wall`() {
        val (s0, _) = room()
        val f = RoomFinish(name = "Bedroom", x = 72f * inch, y = 60f * inch)
        // Paint on all four walls (each face seen from inside the room).
        val gray = listOf("n" to ViewerSide.BELOW, "e" to ViewerSide.LEFT, "s" to ViewerSide.ABOVE, "w" to ViewerSide.RIGHT)
            .map { (id, side) -> WallDecorations.newPaint(id, side, 0xD0CCC4, "SW 7029", "Agreeable Gray") }
        val all = s0.copy(wallDecor = gray, roomFinishes = listOf(f))
        assertEquals("SW 7029 Agreeable Gray", FinishSchedule.wallsFromDrawing(f, all))
        // Wallpaper on the north wall instead of paint: by wall.
        val pic = Picture(id = "p", bitmap = ImageBitmap(10, 10))
        val accent = all.copy(pictures = listOf(pic), wallDecor = gray.filter { it.wallId != "n" } +
            WallDecor(wallId = "n", side = ViewerSide.BELOW, kind = WallDecor.WALLPAPER, pictureId = "p"))
        val text = FinishSchedule.wallsFromDrawing(f, accent)
        assertTrue(text, text.contains("North: Wallpaper"))
        assertTrue(text, text.contains("East, South, West: SW 7029 Agreeable Gray"))
        // Typed walls win over the drawing; rows carry every column.
        val typed = accent.copy(roomFinishes = listOf(f.copy(walls = "Venetian plaster", ceiling = "Flat white", trim = "SW 7006 Extra White, semi-gloss")))
        val row = FinishSchedule.rows(typed).single()
        // Trim moved to the trim schedule (Nov 26): ROOM, FLOOR, WALLS, CEILING, NOTES.
        assertEquals(listOf("Bedroom", "", "Venetian plaster", "Flat white", ""), row)
        assertTrue(Sheets.isTable(SheetView(kind = SheetViewKind.FINISH_SCHEDULE, title = "Finish Schedule")))
        assertTrue(FinishSchedule.tableSizeIn(typed).width > 15f)
    }
}
