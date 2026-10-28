package com.example.sitedraft

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingScale
import com.example.sitedraft.ui.canvas.Placement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.UnitSystem
import com.example.sitedraft.ui.canvas.inchesToPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSymbolsTest {

    @Test
    fun `every library entry has a footprint`() {
        for (name in PlanSymbols.fixtures + PlanSymbols.doorsWindows + PlanSymbols.walls + PlanSymbols.cabinets + PlanSymbols.outdoor) {
            assertTrue(name, PlanSymbols.footprintInches.containsKey(name))
        }
    }

    @Test
    fun `inches convert at the document scale in both unit systems`() {
        val imperial = CanvasState(scale = DrawingScale(ratio = 50f), unitSystem = UnitSystem.IMPERIAL)
        assertEquals(250f, imperial.inchesToPx(60f), 0.001f) // 5 ft at 50 px/ft
        val metric = CanvasState(scale = DrawingScale(ratio = 100f), unitSystem = UnitSystem.METRIC)
        assertEquals(152.4f, metric.inchesToPx(60f), 0.01f) // 1.524 m at 100 px/m
    }

    @Test
    fun `picking a symbol waits for a tap, which places it there`() {
        val vm = DrawingManager(Application())
        vm.onEvent(DrawingEvent.SelectSymbol("Toilet", PlanSymbols.FIXTURES))
        assertEquals(Placement.Symbol("Toilet", PlanSymbols.FIXTURES), vm.state.value.pendingPlacement)
        assertTrue(vm.state.value.elements.isEmpty())

        vm.onEvent(DrawingEvent.PlaceAt(Offset(300f, 250f)))
        val toilet = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        // On the grid already, so no snap shift. (Offset is a value class: compare components.)
        assertEquals(300f, toilet.center.x, 0.001f)
        assertEquals(250f, toilet.center.y, 0.001f)
        assertEquals(null, vm.state.value.pendingPlacement)

        // A second tap with nothing pending places nothing.
        vm.onEvent(DrawingEvent.PlaceAt(Offset(400f, 400f)))
        assertEquals(1, vm.state.value.elements.size)
    }

    @Test
    fun `cancelling a pending symbol places nothing`() {
        val vm = DrawingManager(Application())
        vm.onEvent(DrawingEvent.SelectSymbol("Bathtub", PlanSymbols.FIXTURES))
        vm.onEvent(DrawingEvent.CancelPlacement)
        assertEquals(null, vm.state.value.pendingPlacement)
        vm.onEvent(DrawingEvent.PlaceAt(Offset(100f, 100f)))
        assertTrue(vm.state.value.elements.isEmpty())
    }

    @Test
    fun `placing a bathtub gives it its real footprint`() {
        val vm = DrawingManager(Application())
        vm.onEvent(DrawingEvent.SelectSymbol("Bathtub", PlanSymbols.FIXTURES))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 500f)))
        val tub = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        val ratio = vm.state.value.scale.ratio
        assertEquals(Size(60f / 12f * ratio, 30f / 12f * ratio), tub.size)
        assertEquals(60f, tub.properties["widthInches"])
        assertEquals(30f, tub.properties["depthInches"])
        assertEquals(PlanSymbols.FIXTURES, tub.category)
    }

    @Test
    fun `a 2x4 wall is four and a half inches thick and four feet long`() {
        val vm = DrawingManager(Application())
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 500f)))
        val wall = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        val ratio = vm.state.value.scale.ratio
        assertEquals(4f * ratio, wall.size.width, 0.001f)
        assertEquals(4.5f / 12f * ratio, wall.size.height, 0.001f)
    }
}
