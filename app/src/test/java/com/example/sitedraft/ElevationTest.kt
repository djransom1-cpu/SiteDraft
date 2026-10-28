package com.example.sitedraft

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.Placement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1' = 50px, so 1" = 4.1667px; a 2x4 wall is 18.75px thick. */
class ElevationTest {
    private val inch = 50f / 12f
    private val t = 4.5f * inch

    private fun sym(name: String, category: String, cx: Float, cy: Float, w: Float, h: Float) = DrawingElement.Symbol(
        symbolName = name, category = category, center = Offset(cx, cy), size = Size(w, h), color = Color.White
    )

    /** A horizontal wall along y=0 from x=0 to x=1000, with a kitchen run against its underside. */
    private fun kitchen(): Pair<DrawingElement.Symbol, CanvasState> {
        val wall = sym("2x4 Wall", PlanSymbols.WALLS, 500f, 0f, 1000f, t)
        val face = t / 2f // the wall's lower face
        val base = sym("Base 36\"", PlanSymbols.CABINETS, 175f, face + 12f * inch, 36f * inch, 24f * inch)   // x 100..250
        val upper = sym("Upper 30\"", PlanSymbols.CABINETS, 175f, face + 6f * inch, 30f * inch, 12f * inch)
        val window = sym("Window 4'-0\"", PlanSymbols.DOORS_WINDOWS, 700f, 0f, 48f * inch, t)              // x 600..800
        val sideWall = sym("2x4 Wall", PlanSymbols.WALLS, 1000f - t / 2f, face + 300f, t, 600f)             // butts into the underside at the right end
        val island = sym("Island 3'x6'", PlanSymbols.CABINETS, 500f, 400f, 72f * inch, 36f * inch)
        val farTub = sym("Bathtub", PlanSymbols.FIXTURES, 300f, 900f, 60f * inch, 30f * inch)
        return wall to CanvasState(elements = listOf(wall, base, upper, window, sideWall, island, farTub))
    }

    @Test
    fun `items against the viewed face are found, the far side only sees the window`() {
        val (wall, state) = kitchen()
        val below = Elevation.itemsOnWall(wall, ViewerSide.BELOW, state)
        assertEquals(listOf("Base 36\"", "Upper 30\"", "Window 4'-0\"", "2x4 Wall"), below.map { it.symbol.symbolName })
        assertEquals(listOf(Elevation.Kind.FIXTURE, Elevation.Kind.FIXTURE, Elevation.Kind.OPENING, Elevation.Kind.CUT_WALL), below.map { it.kind })
        assertEquals(100f, below[0].from, 0.01f)
        assertEquals(250f, below[0].to, 0.01f)
        val above = Elevation.itemsOnWall(wall, ViewerSide.ABOVE, state)
        assertEquals(listOf("Window 4'-0\""), above.map { it.symbol.symbolName })
        assertEquals(ViewerSide.BELOW, Elevation.defaultSide(wall, state))
    }

    @Test
    fun `viewed from the other side the run is mirrored`() {
        val (wall, state) = kitchen()
        val window = Elevation.itemsOnWall(wall, ViewerSide.ABOVE, state).single()
        assertEquals(1000f - 800f, window.from, 0.01f)
        assertEquals(1000f - 600f, window.to, 0.01f)
    }

    @Test
    fun `a vertical wall seen from the right puts the south end on the left`() {
        val wall = sym("2x4 Wall", PlanSymbols.WALLS, 0f, 500f, t, 1000f) // y 0..1000, right face at x = t/2
        val cabinet = sym("Base 36\"", PlanSymbols.CABINETS, t / 2f + 12f * inch, 300f, 24f * inch, 36f * inch) // y 225..375
        val state = CanvasState(elements = listOf(wall, cabinet))
        val item = Elevation.itemsOnWall(wall, ViewerSide.RIGHT, state).single()
        assertEquals(1000f - 375f, item.from, 0.01f)
        assertEquals(1000f - 225f, item.to, 0.01f)
        assertEquals(ViewerSide.RIGHT, Elevation.defaultSide(wall, state))
    }

    @Test
    fun `the built elevation has the cabinets, counter, window and frame at the right heights`() {
        val (wall, state) = kitchen()
        val spec = ElevationSpec(wall.id, ViewerSide.BELOW)
        val origin = Offset(0f, 3000f)
        val built = Elevation.build(wall, spec, origin, state)
        val rects = built.filterIsInstance<DrawingElement.Rectangle>()
        // Base cabinet box: x 100..250, floor to 34.5".
        assertTrue(rects.any { r ->
            near(r.topLeft.x, 100f) && near(r.size.width, 150f) && near(r.topLeft.y + r.size.height, 3000f) && near(r.size.height, 34.5f * inch)
        })
        // Countertop over it, 1.5" thick, top at 36".
        assertTrue(rects.any { r -> near(r.size.height, 1.5f * inch) && near(r.topLeft.y, 3000f - 36f * inch) && r.topLeft.x < 100f && r.topLeft.x + r.size.width > 250f })
        // Upper cabinet (30" wide, centred on the base) from 54" to 84".
        assertTrue(rects.any { r -> near(r.topLeft.x, 112.5f) && near(r.size.width, 125f) && near(r.topLeft.y, 3000f - 84f * inch) && near(r.size.height, 30f * inch) })
        // Window from 36" sill to 84".
        assertTrue(rects.any { r -> near(r.topLeft.x, 600f) && near(r.size.width, 200f) && near(r.topLeft.y, 3000f - 84f * inch) && near(r.size.height, 48f * inch) })
        // Side wall cut, full height at the right end.
        assertTrue(rects.any { r -> near(r.topLeft.x, 1000f - t) && near(r.size.width, t) && near(r.size.height, 96f * inch) })
        // Floor line the length of the wall.
        val floor = built.filterIsInstance<DrawingElement.Line>().first { it.strokeWidth == 3f }
        assertEquals(0f, floor.start.x, 0.01f)
        assertEquals(1000f, floor.end.x, 0.01f)
        assertEquals(3000f, floor.start.y, 0.01f)
        val title = built.filterIsInstance<DrawingElement.Text>().single()
        assertEquals("Elevation, 20' wide x 8' high, looking north", title.text)
    }

    @Test
    fun `placing an elevation adds one live view that follows later plan edits`() {
        val vm = DrawingManager(Application())
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 500f))) // 4 ft wall, x 400..600, lower face at 500 + t/2
        val wall = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        vm.onEvent(DrawingEvent.StartPlacement(Placement.Elevation(ElevationSpec(wall.id, ViewerSide.BELOW), "elevation")))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(0f, 2000f)))
        val view = vm.state.value.elements.filterIsInstance<DrawingElement.ElevationView>().single()
        assertEquals(view.id, vm.state.value.selectedElementId)
        assertEquals(200f, view.size.width, 0.01f)
        assertEquals(96f * inch, view.size.height, 0.01f)
        val rectsBefore = Elevation.partsOf(view, vm.state.value).count { it is DrawingElement.Rectangle }

        // Now put a base cabinet against the wall: the same view shows it without being replaced.
        vm.onEvent(DrawingEvent.SelectSymbol("Base 24\"", PlanSymbols.CABINETS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(450f, 500f + t / 2f + 12f * inch)))
        val sameView = vm.state.value.elements.filterIsInstance<DrawingElement.ElevationView>().single()
        assertEquals(view.id, sameView.id)
        val rectsAfter = Elevation.partsOf(sameView, vm.state.value).count { it is DrawingElement.Rectangle }
        assertTrue(rectsAfter > rectsBefore)

        // Stretching the wall widens the view's frame on the next autosave tick.
        vm.onEvent(DrawingEvent.UpdateElementProperty(wall.id, "width", "10"))
        assertEquals(500f, vm.state.value.elements.filterIsInstance<DrawingElement.ElevationView>().single().size.width, 0.01f)
    }

    @Test
    fun `a Lynx 36 grill against the wall shows its hood above the counter and panel below`() {
        val wall = sym("2x4 Wall", PlanSymbols.WALLS, 500f, 0f, 1000f, t)
        val grill = sym(PlanSymbols.LYNX_36_GRILL, PlanSymbols.OUTDOOR, 300f, t / 2f + 25.375f * inch / 2f, 36f * inch, 25.375f * inch)
        val state = CanvasState(elements = listOf(wall, grill))
        val item = Elevation.itemsOnWall(wall, ViewerSide.BELOW, state).single()
        assertEquals(Elevation.Kind.FIXTURE, item.kind)
        val rects = Elevation.build(wall, ElevationSpec(wall.id, ViewerSide.BELOW), Offset(0f, 1000f), state).filterIsInstance<DrawingElement.Rectangle>()
        val hoodTop = 1000f - (36f + PlanSymbols.LYNX_36_HOOD_ABOVE_COUNTER) * inch
        assertTrue(rects.any { near(it.topLeft.y, hoodTop) && near(it.size.width, 36f * inch) })
        val panelTop = 1000f - 36f * inch
        assertTrue(rects.any { near(it.topLeft.y, panelTop) && near(it.size.height, PlanSymbols.LYNX_36_CUTOUT_H * inch) })
    }

    @Test
    fun `exploding a view freezes it into plain elements`() {
        val vm = DrawingManager(Application())
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 500f)))
        val wall = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        vm.onEvent(DrawingEvent.StartPlacement(Placement.Elevation(ElevationSpec(wall.id, ViewerSide.BELOW), "elevation")))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(0f, 2000f)))
        vm.onEvent(DrawingEvent.ExplodeSelectedElement)
        assertTrue(vm.state.value.elements.none { it is DrawingElement.ElevationView })
        assertTrue(vm.state.value.elements.count { it is DrawingElement.Line } >= 4)
        assertTrue(vm.state.value.selectedElementIds.isNotEmpty())
    }

    @Test
    fun `a view survives a save and load round trip`() {
        val view = DrawingElement.ElevationView(
            spec = ElevationSpec("wall-1", ViewerSide.LEFT, wallHeightIn = 108f),
            origin = Offset(10f, 20f), size = Size(300f, 450f), color = Color.Black
        )
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(CanvasState.serializer(), CanvasState(elements = listOf(view)))
        val back = json.decodeFromString(CanvasState.serializer(), text).elements.single() as DrawingElement.ElevationView
        assertEquals(view.spec, back.spec)
        assertEquals(10f, back.origin.x, 0f)
        assertEquals(450f, back.size.height, 0f)
    }

    private fun near(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.05f
}
