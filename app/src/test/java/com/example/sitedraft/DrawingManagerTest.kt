import android.app.Application
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingTool
import com.example.sitedraft.ui.canvas.contains
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawingManagerTest {

    @Test
    fun `reset zoom event resets zoom and pan to defaults`() {
        val manager = DrawingManager(Application())
        
        // Change zoom and pan from defaults
        manager.onEvent(DrawingEvent.UpdateZoom(1.5f))
        manager.onEvent(DrawingEvent.UpdatePan(Offset(100f, 200f)))
        
        assertEquals(1.5f, manager.state.value.zoom)
        assertEquals(Offset(100f, 200f), manager.state.value.pan)
        
        // Reset zoom
        manager.onEvent(DrawingEvent.ResetZoom)
        
        assertEquals(1.0f, manager.state.value.zoom)
        assertEquals(Offset.Zero, manager.state.value.pan)
    }

    @Test
    fun `toggle eraser event switches between pen and eraser`() {
        val manager = DrawingManager(Application())
        
        // Initial tool should be PEN
        assertEquals(DrawingTool.PEN, manager.state.value.currentTool)
        
        // Toggle once -> ERASER
        manager.onEvent(DrawingEvent.ToggleEraser)
        assertEquals(DrawingTool.ERASER, manager.state.value.currentTool)
        
        // Toggle again -> PEN
        manager.onEvent(DrawingEvent.ToggleEraser)
        assertEquals(DrawingTool.PEN, manager.state.value.currentTool)
    }

    @Test
    fun `toggle eraser event switches from other tools to eraser`() {
        val manager = DrawingManager(Application())
        
        // Select LINE tool
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.LINE))
        assertEquals(DrawingTool.LINE, manager.state.value.currentTool)
        
        // Toggle -> ERASER
        manager.onEvent(DrawingEvent.ToggleEraser)
        assertEquals(DrawingTool.ERASER, manager.state.value.currentTool)
        
        // Toggle -> PEN (default fallback when toggling off eraser)
        manager.onEvent(DrawingEvent.ToggleEraser)
        assertEquals(DrawingTool.PEN, manager.state.value.currentTool)
    }

    @Test
    fun `triangle vertices and containment check`() {
        val isosceles = com.example.sitedraft.ui.canvas.DrawingElement.Triangle(
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(100f, 100f),
            color = androidx.compose.ui.graphics.Color.White,
            strokeWidth = 2f,
            triangleType = "Isosceles"
        )
        val (p1, p2, p3) = isosceles.getVertices()
        assertEquals(Offset(50f, 0f), p1)
        assertEquals(Offset(0f, 100f), p2)
        assertEquals(Offset(100f, 100f), p3)

        // Inside containment checks
        org.junit.Assert.assertTrue(isosceles.contains(Offset(50f, 50f)))
        org.junit.Assert.assertFalse(isosceles.contains(Offset(10f, 10f)))

        val rightAngled = com.example.sitedraft.ui.canvas.DrawingElement.Triangle(
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(100f, 100f),
            color = androidx.compose.ui.graphics.Color.White,
            strokeWidth = 2f,
            triangleType = "Right-Angled"
        )
        val (rp1, rp2, rp3) = rightAngled.getVertices()
        assertEquals(Offset(0f, 0f), rp1)
        assertEquals(Offset(0f, 100f), rp2)
        assertEquals(Offset(100f, 100f), rp3)

        // Inside containment checks
        org.junit.Assert.assertTrue(rightAngled.contains(Offset(10f, 50f)))
        org.junit.Assert.assertFalse(rightAngled.contains(Offset(90f, 10f)))
    }

    @Test
    fun `triangle tool draws triangle and updates property`() {
        val manager = DrawingManager(Application())
        
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.TRIANGLE))
        
        manager.onEvent(DrawingEvent.Start(Offset(0f, 0f)))
        manager.onEvent(DrawingEvent.Move(Offset(100f, 100f)))
        manager.onEvent(DrawingEvent.End)
        
        val elements = manager.state.value.elements
        assertEquals(1, elements.size)
        val triangle = elements[0] as com.example.sitedraft.ui.canvas.DrawingElement.Triangle
        assertEquals("Isosceles", triangle.triangleType)
        
        manager.onEvent(DrawingEvent.UpdateElementProperty(triangle.id, "triangleType", "Right-Angled"))
        
        val updatedElements = manager.state.value.elements
        val updatedTriangle = updatedElements[0] as com.example.sitedraft.ui.canvas.DrawingElement.Triangle
        assertEquals("Right-Angled", updatedTriangle.triangleType)
    }

    @Test
    fun `S Pen button erases only while held and restores the previous tool`() {
        val manager = DrawingManager(Application())
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.PENCIL))

        manager.onEvent(DrawingEvent.StylusButtonErase(true))
        assertEquals(DrawingTool.ERASER, manager.state.value.currentTool)
        manager.onEvent(DrawingEvent.StylusButtonErase(false))
        assertEquals(DrawingTool.PENCIL, manager.state.value.currentTool)

        // Already erasing: the button must not switch the tool away from the eraser on release.
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.ERASER))
        manager.onEvent(DrawingEvent.StylusButtonErase(true))
        manager.onEvent(DrawingEvent.StylusButtonErase(false))
        assertEquals(DrawingTool.ERASER, manager.state.value.currentTool)
    }
}
