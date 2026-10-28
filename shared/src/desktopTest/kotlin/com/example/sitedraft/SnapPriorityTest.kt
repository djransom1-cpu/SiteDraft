import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round

/**
 * Snap priority: object key points, then object edges, then the grid - never the grid first.
 *
 * Defaults used throughout: Imperial, 1' = 50px, so the snap grid step is 1" = 50/12 px
 * (~4.17 px). All "raw" pointer positions below are chosen so the grid would have claimed
 * them under the old grid-first search, which is what makes these tests meaningful.
 */
class SnapPriorityTest {

    private val gridStep = 50f / 12f

    private fun drawRectangle(manager: DrawingManager, from: Offset, to: Offset): DrawingElement.Rectangle {
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.RECTANGLE))
        manager.onEvent(DrawingEvent.Start(from))
        manager.onEvent(DrawingEvent.Move(to))
        manager.onEvent(DrawingEvent.End)
        return manager.state.value.elements.last() as DrawingElement.Rectangle
    }

    private fun assertOnGrid(value: Float, label: String) {
        val nearest = round(value / gridStep) * gridStep
        assertTrue("$label=$value is not on the ${gridStep}px grid", abs(value - nearest) < 0.01f)
    }

    @Test
    fun `line start snaps to a rectangle corner even though the grid is closer`() {
        val manager = DrawingManager()
        // Corners at (100,100) and (300,250) - all multiples of the grid step.
        val rect = drawRectangle(manager, Offset(100f, 100f), Offset(300f, 250f))
        assertEquals(Offset(100f, 100f), rect.topLeft)

        // Start a line ~14 px from the top-left corner. The nearest grid point is < 1 px away,
        // so grid-first would have quantized to (112.5, 108.3) instead of the corner.
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.LINE))
        manager.onEvent(DrawingEvent.Start(Offset(112f, 108f)))
        manager.onEvent(DrawingEvent.Move(Offset(300f, 400f)))
        manager.onEvent(DrawingEvent.End)

        val line = manager.state.value.elements.last() as DrawingElement.Line
        assertEquals(Offset(100f, 100f), line.start)
    }

    @Test
    fun `line end snaps to a rectangle edge even though the grid is closer`() {
        val manager = DrawingManager()
        drawRectangle(manager, Offset(100f, 100f), Offset(300f, 250f))

        // End the line 8 px below the top edge at x=150 - far from any corner or midpoint
        // (nearest key points are (100,100) and (200,100), both 50 px away), so this must
        // resolve via the edge pass, not the vertex pass, and must beat the grid.
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.LINE))
        manager.onEvent(DrawingEvent.Start(Offset(150f, 400f)))
        manager.onEvent(DrawingEvent.Move(Offset(150f, 108f)))
        manager.onEvent(DrawingEvent.End)

        val line = manager.state.value.elements.last() as DrawingElement.Line
        assertEquals(Offset(150f, 100f), line.end)
    }

    @Test
    fun `grid still applies when no object is nearby`() {
        val manager = DrawingManager()
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.LINE))
        manager.onEvent(DrawingEvent.Start(Offset(101f, 102f)))
        manager.onEvent(DrawingEvent.Move(Offset(301f, 302f)))
        manager.onEvent(DrawingEvent.End)

        val line = manager.state.value.elements.last() as DrawingElement.Line
        assertOnGrid(line.start.x, "start.x")
        assertOnGrid(line.start.y, "start.y")
        assertOnGrid(line.end.x, "end.x")
        assertOnGrid(line.end.y, "end.y")
    }

    @Test
    fun `dragging an object with select lands it on the grid`() {
        val manager = DrawingManager()
        drawRectangle(manager, Offset(100f, 100f), Offset(300f, 250f))

        // Grab the rectangle at its center and nudge it by an off-grid amount.
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.SELECT))
        manager.onEvent(DrawingEvent.Start(Offset(200f, 175f)))
        manager.onEvent(DrawingEvent.Move(Offset(203f, 178f)))
        manager.onEvent(DrawingEvent.End)

        val rect = manager.state.value.elements.last() as DrawingElement.Rectangle
        assertTrue("rectangle did not move", rect.topLeft != Offset(100f, 100f))
        assertOnGrid(rect.topLeft.x, "topLeft.x")
        assertOnGrid(rect.topLeft.y, "topLeft.y")
    }

    @Test
    fun `dragging an object onto another object still prefers the object corner`() {
        val manager = DrawingManager()
        // Target: a rectangle whose top-right corner is (300,100).
        drawRectangle(manager, Offset(100f, 100f), Offset(300f, 250f))
        // Mover: a rectangle well away from the target, top-left at (500,100).
        drawRectangle(manager, Offset(500f, 100f), Offset(600f, 150f))

        // Drag the mover left by ~189 px so its top-left lands ~11 px right of the target's
        // top-right corner (311,100) - within snap range of the corner, and a position the
        // grid alone would have left at (312.5,100).
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.SELECT))
        manager.onEvent(DrawingEvent.Start(Offset(550f, 125f)))
        manager.onEvent(DrawingEvent.Move(Offset(361f, 125f)))
        manager.onEvent(DrawingEvent.End)

        val mover = manager.state.value.elements.last() as DrawingElement.Rectangle
        assertEquals(Offset(300f, 100f), mover.topLeft)
    }

    @Test
    fun `object follows the pen again after leaving snap range`() {
        val manager = DrawingManager()
        // Target: top-right corner at (300,100). Mover: top-left at (500,100).
        drawRectangle(manager, Offset(100f, 100f), Offset(300f, 250f))
        drawRectangle(manager, Offset(500f, 100f), Offset(600f, 150f))

        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.SELECT))
        manager.onEvent(DrawingEvent.Start(Offset(550f, 125f)))
        // Frame 1: pen alone would put the top-left at (311,100); it snaps onto (300,100).
        manager.onEvent(DrawingEvent.Move(Offset(361f, 125f)))
        val snapped = manager.state.value.elements.last() as DrawingElement.Rectangle
        assertEquals(Offset(300f, 100f), snapped.topLeft)
        // Frame 2: pen is now +150 from where the drag started, far from any object. The
        // mover must be exactly +150 from ITS start too - (650,100), which is on the grid -
        // not (639,100) carrying the 11 px snap adjustment from frame 1.
        manager.onEvent(DrawingEvent.Move(Offset(700f, 125f)))
        manager.onEvent(DrawingEvent.End)

        val mover = manager.state.value.elements.last() as DrawingElement.Rectangle
        assertEquals(Offset(650f, 100f), mover.topLeft)
    }

    @Test
    fun `tap to select uses the raw tap even when snap would pull it onto a neighbour`() {
        val manager = DrawingManager()
        // A: rectangle with its top-right corner at (300,100).
        drawRectangle(manager, Offset(100f, 100f), Offset(300f, 250f))
        // B: vertical line 29 px right of that corner, with its endpoints and midpoint all far
        // from the tap so nothing on B is a vertex-snap candidate.
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.LINE))
        manager.onEvent(DrawingEvent.Start(Offset(329f, -50f)))
        manager.onEvent(DrawingEvent.Move(Offset(329f, 150f)))
        manager.onEvent(DrawingEvent.End)
        val line = manager.state.value.elements.last() as DrawingElement.Line

        // Tap at (319,100): 10 px from B, 19 px from A's corner. Snap would move the point onto
        // A's corner (vertex within 20 px), and a hit-test THERE picks A. The pen is nearer B.
        manager.onEvent(DrawingEvent.SelectTool(DrawingTool.SELECT))
        manager.onEvent(DrawingEvent.Start(Offset(319f, 100f)))
        manager.onEvent(DrawingEvent.End)

        assertEquals(line.id, manager.state.value.selectedElementId)
    }
}
