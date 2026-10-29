package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingTool
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.dimLineEndpoints
import org.junit.Assert.assertEquals
import org.junit.Test

/** Selecting an object that sits inside, or on top of, another one. 1' = 50px, so 1" = 4.1667px. */
class PickingTest {

    private val inch = 50f / 12f

    private fun tap(vm: DrawingManager, at: Offset): String? {
        vm.onEvent(DrawingEvent.SelectTool(DrawingTool.SELECT))
        vm.onEvent(DrawingEvent.Start(at, resizeThreshold = 48f))
        vm.onEvent(DrawingEvent.End)
        return vm.state.value.selectedElementId
    }

    @Test
    fun `an upper cabinet over a base cabinet is picked where the upper is`() {
        val vm = DrawingManager().also { it.onEvent(DrawingEvent.ToggleSnap(false)) }
        // Base 36" (24" deep) with its back on y = 0; the Upper 36" (12" deep) shares that back edge.
        vm.onEvent(DrawingEvent.SelectSymbol("Base 36\"", PlanSymbols.CABINETS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 12f * inch)))
        val base = vm.state.value.elements.last()
        vm.onEvent(DrawingEvent.SelectSymbol("Upper 36\"", PlanSymbols.CABINETS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 6f * inch)))
        val upper = vm.state.value.elements.last()

        // Inside the upper's strip (8" from the back): the upper wins, whatever the creation order.
        assertEquals(upper.id, tap(vm, Offset(500f, 8f * inch)))
        // Deselect, then tap the front half of the base, where only the base is.
        vm.onEvent(DrawingEvent.DeselectElement)
        assertEquals(base.id, tap(vm, Offset(500f, 20f * inch)))
    }

    @Test
    fun `cabinets turned sideways against a wall are picked where they are drawn`() {
        val vm = DrawingManager().also { it.onEvent(DrawingEvent.ToggleSnap(false)) }
        // Both turned 270 degrees (backs to a wall on the left): the base's drawn footprint is
        // 24" wide x 36" tall, the upper's 12" x 36", sharing the back edge at x = 500 - 12".
        vm.onEvent(DrawingEvent.SelectSymbol("Base 36\"", PlanSymbols.CABINETS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 500f)))
        val base = vm.state.value.elements.last()
        vm.onEvent(DrawingEvent.UpdateElementProperty(base.id, "rotation", "270"))
        vm.onEvent(DrawingEvent.SelectSymbol("Upper 36\"", PlanSymbols.CABINETS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f - 6f * inch, 500f)))
        val upper = vm.state.value.elements.last()
        vm.onEvent(DrawingEvent.UpdateElementProperty(upper.id, "rotation", "270"))
        vm.onEvent(DrawingEvent.DeselectElement)

        // Near the top of the upper's strip - outside both unrotated boxes, inside both drawn ones.
        assertEquals(upper.id, tap(vm, Offset(500f - 8f * inch, 500f - 14f * inch)))
        vm.onEvent(DrawingEvent.DeselectElement)
        // The front half of the base, same height.
        assertEquals(base.id, tap(vm, Offset(500f + 6f * inch, 500f - 14f * inch)))
    }

    @Test
    fun `a dimension run along a wall is picked over the wall`() {
        val vm = DrawingManager().also { it.onEvent(DrawingEvent.ToggleSnap(false)) }
        vm.onEvent(DrawingEvent.SelectSymbol("2x6 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 300f)))
        val wall = vm.state.value.elements.last()
        vm.onEvent(DrawingEvent.UpdateElementProperty(wall.id, "width", "10"))   // x 250..750, 6.5" thick about y = 300
        // A dimension measured along the wall's top face, its line offset 2" down INTO the wall.
        vm.onEvent(DrawingEvent.SelectTool(DrawingTool.DIMENSION))
        vm.onEvent(DrawingEvent.Start(Offset(250f, 300f - 3.25f * inch)))
        vm.onEvent(DrawingEvent.Move(Offset(750f, 300f - 3.25f * inch)))
        vm.onEvent(DrawingEvent.End)
        vm.onEvent(DrawingEvent.Start(Offset(500f, 300f - 1.25f * inch)))
        vm.onEvent(DrawingEvent.Move(Offset(500f, 300f - 1.25f * inch)))
        vm.onEvent(DrawingEvent.End)
        val dim = vm.state.value.elements.filterIsInstance<DrawingElement.Dimension>().single()
        vm.onEvent(DrawingEvent.DeselectElement)
        // Tap on the dimension line, inside the wall's thickness.
        val (a, b) = dim.dimLineEndpoints()
        assertEquals(dim.id, tap(vm, (a + b) / 2f + Offset(60f, 1f)))
        vm.onEvent(DrawingEvent.DeselectElement)
        // Tap the wall well away from the dimension: still the wall.
        assertEquals(wall.id, tap(vm, Offset(300f, 300f + 2.5f * inch)))
    }

    @Test
    fun `a line drawn inside a rectangle can be picked`() {
        val vm = DrawingManager().also { it.onEvent(DrawingEvent.ToggleSnap(false)) }
        vm.onEvent(DrawingEvent.SelectTool(DrawingTool.RECTANGLE))
        vm.onEvent(DrawingEvent.Start(Offset(100f, 100f)))
        vm.onEvent(DrawingEvent.Move(Offset(700f, 500f)))
        vm.onEvent(DrawingEvent.End)
        val rect = vm.state.value.elements.single()
        vm.onEvent(DrawingEvent.AddLine(Offset(300f, 300f), Offset(500f, 300f)))
        val line = vm.state.value.elements.last()
        vm.onEvent(DrawingEvent.DeselectElement)

        assertEquals(line.id, tap(vm, Offset(400f, 305f)))
        vm.onEvent(DrawingEvent.DeselectElement)
        assertEquals(rect.id, tap(vm, Offset(150f, 400f)))
    }

    @Test
    fun `nearest outline still wins between two neighbouring shapes`() {
        val vm = DrawingManager().also { it.onEvent(DrawingEvent.ToggleSnap(false)) }
        vm.onEvent(DrawingEvent.SelectTool(DrawingTool.RECTANGLE))
        vm.onEvent(DrawingEvent.Start(Offset(0f, 0f))); vm.onEvent(DrawingEvent.Move(Offset(200f, 200f))); vm.onEvent(DrawingEvent.End)
        val left = vm.state.value.elements.last()
        vm.onEvent(DrawingEvent.Start(Offset(230f, 0f))); vm.onEvent(DrawingEvent.Move(Offset(430f, 200f))); vm.onEvent(DrawingEvent.End)
        val right = vm.state.value.elements.last()
        vm.onEvent(DrawingEvent.DeselectElement)
        assertEquals(left.id, tap(vm, Offset(205f, 100f)))
        vm.onEvent(DrawingEvent.DeselectElement)
        assertEquals(right.id, tap(vm, Offset(225f, 100f)))
    }
}
