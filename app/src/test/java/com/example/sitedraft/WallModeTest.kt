package com.example.sitedraft

import android.app.Application
import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingTool
import com.example.sitedraft.ui.canvas.WallDirection
import com.example.sitedraft.ui.canvas.WallHit
import com.example.sitedraft.ui.canvas.wallModeHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallModeTest {

    @Test
    fun `measurements become walls that chain around a room and close back to the start`() {
        val manager = DrawingManager(Application())
        manager.onEvent(DrawingEvent.SetWallMode(true))
        manager.onEvent(DrawingEvent.SetWallCursor(Offset(100f, 100f)))

        manager.onEvent(DrawingEvent.SetWallDirection(WallDirection.RIGHT))
        manager.onEvent(DrawingEvent.AddWall(300f))
        manager.onEvent(DrawingEvent.SetWallDirection(WallDirection.DOWN))
        manager.onEvent(DrawingEvent.AddWall(200f))
        manager.onEvent(DrawingEvent.SetWallDirection(WallDirection.LEFT))
        manager.onEvent(DrawingEvent.AddWall(300f))
        manager.onEvent(DrawingEvent.CloseRoom)

        val lines = manager.state.value.elements.filterIsInstance<DrawingElement.Line>()
        assertEquals(4, lines.size)
        assertEquals(Offset(100f, 100f), lines[0].start)
        assertEquals(Offset(400f, 100f), lines[0].end)
        assertEquals(Offset(400f, 300f), lines[1].end)
        assertEquals(Offset(100f, 300f), lines[2].end)
        assertEquals(Offset(100f, 100f), lines[3].end)
        assertEquals(Offset(100f, 100f), manager.state.value.wallMode.cursor)
    }

    @Test
    fun `cursor snaps to existing geometry when placed`() {
        val manager = DrawingManager(Application())
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.RECTANGLE))
        manager.onEvent(DrawingEvent.Start(Offset(100f, 100f)))
        manager.onEvent(DrawingEvent.Move(Offset(300f, 250f)))
        manager.onEvent(DrawingEvent.End)

        manager.onEvent(DrawingEvent.SetWallMode(true))
        manager.onEvent(DrawingEvent.SetWallCursor(Offset(312f, 108f)))
        assertEquals(Offset(300f, 100f), manager.state.value.wallMode.cursor)
    }

    @Test
    fun `continue from a selected line picks up its end`() {
        val manager = DrawingManager(Application())
        manager.onEvent(DrawingEvent.AddLine(Offset(0f, 0f), Offset(200f, 0f)))
        manager.onEvent(DrawingEvent.ContinueWallsFromSelectedLine)
        val wall = manager.state.value.wallMode
        assertTrue(wall.active)
        assertEquals(Offset(200f, 0f), wall.cursor)
        assertEquals(Offset(0f, 0f), wall.roomStart)
    }

    @Test
    fun `hit testing resolves arrows, cursor and placement`() {
        val manager = DrawingManager(Application())
        assertNull(wallModeHit(manager.state.value, Offset(50f, 50f)))
        manager.onEvent(DrawingEvent.SetWallMode(true))
        assertEquals(WallHit.Place, wallModeHit(manager.state.value, Offset(50f, 50f)))
        manager.onEvent(DrawingEvent.SetWallCursor(Offset(500f, 500f)))
        val state = manager.state.value
        assertEquals(WallHit.Cursor, wallModeHit(state, Offset(505f, 495f)))
        assertEquals(WallHit.Arrow(WallDirection.RIGHT), wallModeHit(state, Offset(556f, 500f)))
        assertEquals(WallHit.Arrow(WallDirection.UP), wallModeHit(state, Offset(500f, 444f)))
        assertNull(wallModeHit(state, Offset(700f, 700f)))
    }
}
