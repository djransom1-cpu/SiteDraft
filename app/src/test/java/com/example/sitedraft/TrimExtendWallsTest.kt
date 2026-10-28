package com.example.sitedraft

import android.app.Application
import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingTool
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import org.junit.Assert.assertEquals
import org.junit.Test

/** Trim and Extend on wall sections (Walls symbols), treated as their centre lines. 1' = 50px, 2x4 wall = 18.75px thick. */
class TrimExtendWallsTest {

    private val t = 4.5f * 50f / 12f

    /** A horizontal 2x4 wall centred at [cx],[cy] and [feet] long. */
    private fun horizontalWall(vm: DrawingManager, cx: Float, cy: Float, feet: Float): DrawingElement.Symbol {
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(cx, cy)))
        val wall = vm.state.value.elements.last() as DrawingElement.Symbol
        vm.onEvent(DrawingEvent.UpdateElementProperty(wall.id, "width", feet.toString()))
        return vm.state.value.elements.last() as DrawingElement.Symbol
    }

    /** A vertical 2x4 wall centred at [cx],[cy] and [feet] long. */
    private fun verticalWall(vm: DrawingManager, cx: Float, cy: Float, feet: Float): DrawingElement.Symbol {
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(cx, cy)))
        val wall = vm.state.value.elements.last() as DrawingElement.Symbol
        vm.onEvent(DrawingEvent.UpdateElementProperty(wall.id, "width", (4.5f / 12f).toString()))
        vm.onEvent(DrawingEvent.UpdateElementProperty(wall.id, "height", feet.toString()))
        return vm.state.value.elements.last() as DrawingElement.Symbol
    }

    private fun walls(vm: DrawingManager) = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>()

    @Test
    fun `extend stretches a wall end until it meets the face of the next wall`() {
        val vm = DrawingManager(Application())
        val a = horizontalWall(vm, cx = 250f, cy = 0f, feet = 4f)   // x 150..350
        verticalWall(vm, cx = 800f, cy = 0f, feet = 10f)            // faces at x = 800 -+ t/2
        vm.onEvent(DrawingEvent.SelectTool(DrawingTool.EXTEND))
        vm.onEvent(DrawingEvent.Start(Offset(345f, 0f)))            // near A's right end
        val a2 = walls(vm).first { it.id == a.id }
        val b = FloorPlan.bounds(a2)
        assertEquals(150f, b.left, 0.01f)
        assertEquals(800f - t / 2f, b.right, 0.01f)
        assertEquals(t, b.height, 0.01f)
        assertEquals("2x4 Wall", a2.symbolName)
    }

    @Test
    fun `trim cuts a wall back to the face of the wall crossing it`() {
        val vm = DrawingManager(Application())
        val a = horizontalWall(vm, cx = 500f, cy = 0f, feet = 20f)  // x 0..1000
        verticalWall(vm, cx = 500f, cy = 0f, feet = 12f)            // crosses A at x = 500
        vm.onEvent(DrawingEvent.SelectTool(DrawingTool.TRIM))
        vm.onEvent(DrawingEvent.Start(Offset(700f, 0f)))            // the part to the right
        val pieces = walls(vm).filter { FloorPlan.bounds(it).height < FloorPlan.bounds(it).width }
        assertEquals(1, pieces.size)
        val b = FloorPlan.bounds(pieces.single())
        assertEquals(0f, b.left, 0.01f)
        assertEquals(500f + t / 2f, b.right, 0.01f)
    }

    @Test
    fun `trim removes the piece between two crossing walls and leaves two walls`() {
        val vm = DrawingManager(Application())
        horizontalWall(vm, cx = 500f, cy = 0f, feet = 20f)          // x 0..1000
        verticalWall(vm, cx = 300f, cy = 0f, feet = 12f)
        verticalWall(vm, cx = 700f, cy = 0f, feet = 12f)
        vm.onEvent(DrawingEvent.SelectTool(DrawingTool.TRIM))
        vm.onEvent(DrawingEvent.Start(Offset(500f, 0f)))
        val pieces = walls(vm).filter { FloorPlan.bounds(it).height < FloorPlan.bounds(it).width }
            .map { FloorPlan.bounds(it) }.sortedBy { it.left }
        assertEquals(2, pieces.size)
        assertEquals(0f, pieces[0].left, 0.01f)
        assertEquals(300f + t / 2f, pieces[0].right, 0.01f)
        assertEquals(700f - t / 2f, pieces[1].left, 0.01f)
        assertEquals(1000f, pieces[1].right, 0.01f)
    }
}
