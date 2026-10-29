package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.Placement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.UnitSystem
import com.example.sitedraft.ui.canvas.boundingRect
import com.example.sitedraft.ui.canvas.rotateAround
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorPlanTest {

    private val t = 18.75f // 4.5" at 50 px/ft
    private val inch = 50f / 12f

    private fun walls(outer: Rect, existing: List<DrawingElement.Symbol> = emptyList()) =
        FloorPlan.wallsAround(outer, t, "2x4 Wall", Color.White, existing, inch)

    @Test
    fun `an outline on an empty canvas gets four walls that meet at the corners`() {
        val outer = Rect(100f, 100f, 1600f, 3100f) // 30 x 60 ft outside
        val w = walls(outer)
        assertEquals(4, w.size)
        val bounds = w.map { FloorPlan.bounds(it) }
        // Top and bottom run the full width; sides sit between them.
        assertTrue(bounds.any { it.left == 100f && it.right == 1600f && it.top == 100f && it.bottom == 100f + t })
        assertTrue(bounds.any { it.left == 100f && it.right == 1600f && it.bottom == 3100f })
        assertTrue(bounds.any { it.left == 100f && it.right == 100f + t && it.top == 100f + t && it.bottom == 3100f - t })
        assertTrue(bounds.any { it.right == 1600f && it.top == 100f + t && it.bottom == 3100f - t })
        assertTrue(w.all { it.category == PlanSymbols.WALLS && it.symbolName == "2x4 Wall" })
    }

    @Test
    fun `a room in the corner of the outline only adds the two walls it needs`() {
        val shell = walls(Rect(0f, 0f, 1500f, 3000f))
        // Inside top-left of the shell is (t, t); a 12 x 12 ft room there has its walls outside that.
        val inside = Rect(t, t, t + 600f, t + 600f)
        val outer = Rect(inside.left - t, inside.top - t, inside.right + t, inside.bottom + t)
        val room = walls(outer, shell)
        assertEquals(2, room.size)
        val bounds = room.map { FloorPlan.bounds(it) }
        // The right wall butts against the shell's top wall instead of running into it.
        val right = bounds.first { it.width < it.height }
        assertEquals(t, right.top, 0.01f)
        assertEquals(inside.bottom, right.bottom, 0.01f) // the new bottom wall sits below it
        // The bottom wall starts at the shell's inside face, not inside the shell wall.
        val bottom = bounds.first { it.width > it.height }
        assertEquals(t, bottom.left, 0.01f)
        assertEquals(inside.right + t, bottom.right, 0.01f)
    }

    @Test
    fun `two rooms side by side share a wall`() {
        val a = walls(Rect(0f, 0f, 600f, 600f))
        val aRightOutside = 600f
        // Room B's inside left corner snapped to A's outside right face.
        val bInside = Rect(aRightOutside, t, aRightOutside + 500f, t + 500f)
        val bOuter = Rect(bInside.left - t, bInside.top - t, bInside.right + t, bInside.bottom + t)
        val b = walls(bOuter, a)
        assertEquals(3, b.size)
        assertTrue(b.none { FloorPlan.bounds(it).let { r -> r.width < r.height && r.left < aRightOutside } })
    }

    @Test
    fun `outline and room placement through the view model`() {
        val vm = DrawingManager()
        val units = vm.state.value.unitSystem
        vm.onEvent(DrawingEvent.StartPlacement(Placement.Shell(30f, 60f, "2x6 Wall", FloorPlan.shellLabel(30f, 60f, units))))
        assertEquals("30' x 60' outline", vm.state.value.pendingPlacement?.label)
        vm.onEvent(DrawingEvent.PlaceAt(Offset(100f, 100f)))
        assertNull(vm.state.value.pendingPlacement)
        val shellWalls = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>()
        assertEquals(4, shellWalls.size)
        val ratio = vm.state.value.scale.ratio
        val union = shellWalls.map { FloorPlan.bounds(it) }.reduce { acc, r ->
            Rect(minOf(acc.left, r.left), minOf(acc.top, r.top), maxOf(acc.right, r.right), maxOf(acc.bottom, r.bottom))
        }
        assertEquals(30f * ratio, union.width, 0.01f)
        assertEquals(60f * ratio, union.height, 0.01f)

        val t6 = 6.5f / 12f * ratio
        vm.onEvent(DrawingEvent.StartPlacement(Placement.Room("Bedroom", 12f, 12f, "2x4 Wall", FloorPlan.roomPlacementLabel("Bedroom", 12f, 12f, units))))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(100f + t6, 100f + t6)))
        val symbols = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>()
        assertEquals(6, symbols.size) // two new walls
        val label = vm.state.value.elements.filterIsInstance<DrawingElement.Text>().single()
        assertEquals("Bedroom\n12' x 12'", label.text)
        assertEquals(UnitSystem.IMPERIAL, units)
    }

    @Test
    fun `a room by outside dimensions is placed from its outside corner and labelled inside`() {
        val vm = DrawingManager()
        vm.onEvent(DrawingEvent.ToggleSnap(false))
        val ratio = vm.state.value.scale.ratio
        val t = 4.5f / 12f * ratio
        vm.onEvent(DrawingEvent.StartPlacement(Placement.Room("Bath", 10f, 8f, "2x4 Wall", "Bath", outside = true)))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(100f, 100f)))
        val walls = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().map { FloorPlan.bounds(it) }
        assertEquals(4, walls.size)
        val outer = walls.reduce { acc, r -> Rect(minOf(acc.left, r.left), minOf(acc.top, r.top), maxOf(acc.right, r.right), maxOf(acc.bottom, r.bottom)) }
        // Outside is exactly what was typed, from the tapped corner.
        assertEquals(100f, outer.left, 0.01f)
        assertEquals(100f, outer.top, 0.01f)
        assertEquals(10f * ratio, outer.width, 0.01f)
        assertEquals(8f * ratio, outer.height, 0.01f)
        // The label gives the clear inside size: 10' - 9" and 8' - 9".
        val label = vm.state.value.elements.filterIsInstance<DrawingElement.Text>().single()
        assertEquals("Bath\n9.25' x 7.25'", label.text)
        assertEquals(t, walls.first().height.coerceAtMost(walls.first().width), 0.01f)
    }

    @Test
    fun `dimension labels`() {
        assertEquals("12' x 12'", FloorPlan.formatDims(12f, 12f, UnitSystem.IMPERIAL))
        assertEquals("12' x 3.5'", FloorPlan.formatDims(12f, 3.5f, UnitSystem.IMPERIAL))
        assertEquals("3.6 x 3.6 m", FloorPlan.formatDims(3.6f, 3.6f, UnitSystem.METRIC))
    }
}

class FloorPlanEditingTest {
    private val ratio = 50f
    private val inch = ratio / 12f

    private fun wall(cx: Float, cy: Float, w: Float, h: Float) = DrawingElement.Symbol(
        symbolName = "2x4 Wall", category = PlanSymbols.WALLS, center = Offset(cx, cy), size = androidx.compose.ui.geometry.Size(w, h), color = Color.White
    )

    private fun stateWith(vararg walls: DrawingElement.Symbol) =
        com.example.sitedraft.ui.canvas.CanvasState(elements = walls.toList())

    @Test
    fun `a cased opening tapped on a wall sits in the wall and takes its thickness`() {
        val t = 4.5f * inch
        val state = stateWith(wall(cx = 500f, cy = 300f, w = 1000f, h = t))
        val tap = Offset(400f, 305f)
        val p = FloorPlan.alignOpening("Opening 3'-0\"", tap, tap, state)!!
        assertEquals(400f, p.center.x, 0.01f)
        assertEquals(300f, p.center.y, 0.01f)
        assertEquals(androidx.compose.ui.geometry.Size(36f * inch, t), p.size)
        // And it shows in an elevation of that wall as an opening, not a door.
        val opening = DrawingElement.Symbol(symbolName = "Opening 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, center = p.center, size = p.size!!, color = Color.White)
        val wall = state.elements.first() as DrawingElement.Symbol
        val items = com.example.sitedraft.ui.canvas.Elevation.itemsOnWall(wall, com.example.sitedraft.ui.canvas.ViewerSide.BELOW, state.copy(elements = listOf(wall, opening)))
        assertEquals(listOf(com.example.sitedraft.ui.canvas.Elevation.Kind.OPENING), items.map { it.kind })
    }

    @Test
    fun `a door tapped on a horizontal wall sits with its threshold on the wall centre line`() {
        val t = 4.5f * inch
        val state = stateWith(wall(cx = 500f, cy = 300f, w = 1000f, h = t))
        val tap = Offset(400f, 305f)
        val p = FloorPlan.alignOpening("Door 3'-0\"", tap, tap, state)!!
        val w = 36f * inch
        assertEquals(400f, p.center.x, 0.01f)
        assertEquals(300f - w / 2f, p.center.y, 0.01f)
        assertEquals(0f, p.rotation, 0f)
        assertNull(p.size)
    }

    @Test
    fun `a window tapped on a vertical wall is centred in it and takes its thickness`() {
        val t = 6.5f * inch
        val state = stateWith(wall(cx = 100f, cy = 800f, w = t, h = 2000f))
        val tap = Offset(104f, 600f)
        val p = FloorPlan.alignOpening("Window 4'-0\"", tap, tap, state)!!
        assertEquals(100f, p.center.x, 0.01f)
        assertEquals(600f, p.center.y, 0.01f)
        assertEquals(t, p.size!!.width, 0.01f)
        assertEquals(48f * inch, p.size!!.height, 0.01f)
        // A hinged door on the same wall turns 90 degrees, threshold on the centre line.
        val d = FloorPlan.alignOpening("Door 2'-8\"", tap, tap, state)!!
        assertEquals(90f, d.rotation, 0f)
        assertEquals(100f + 32f * inch / 2f, d.center.x, 0.01f)
    }

    @Test
    fun `a tap away from any wall places the symbol as tapped`() {
        val state = stateWith(wall(cx = 500f, cy = 300f, w = 1000f, h = 4.5f * inch))
        assertNull(FloorPlan.alignOpening("Door 3'-0\"", Offset(400f, 500f), Offset(400f, 500f), state))
    }

    @Test
    fun `rotating two walls a quarter turn keeps them axis aligned`() {
        val top = wall(cx = 500f, cy = 0f, w = 1000f, h = 20f)
        val left = wall(cx = 0f, cy = 250f, w = 20f, h = 500f)
        val pivot = Offset(500f, 250f)
        val top2 = top.rotateAround(pivot, 90f) as DrawingElement.Symbol
        val left2 = left.rotateAround(pivot, 90f) as DrawingElement.Symbol
        // The top wall swings to the right side and becomes vertical; the left wall becomes the top.
        assertEquals(0f, top2.rotation, 0f)
        assertEquals(20f, top2.size.width, 0.01f)
        assertEquals(1000f, top2.size.height, 0.01f)
        assertEquals(750f, top2.center.x, 0.01f)
        assertEquals(250f, top2.center.y, 0.01f)
        assertEquals(500f, left2.size.width, 0.01f)
        assertEquals(500f, left2.center.x, 0.01f)
        assertEquals(-250f, left2.center.y, 0.01f)
        // Non-plan symbols keep their size and take the rotation instead.
        val tub = DrawingElement.Symbol(symbolName = "Bathtub", category = PlanSymbols.FIXTURES, center = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(250f, 125f), color = Color.White)
        val tub2 = tub.rotateAround(Offset.Zero, 90f) as DrawingElement.Symbol
        assertEquals(90f, tub2.rotation, 0f)
        assertEquals(250f, tub2.size.width, 0f)
    }

    @Test
    fun `offsetting a wall makes a parallel copy instead of a bigger wall`() {
        val vm = DrawingManager()
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 500f)))
        val original = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>().single()
        vm.onEvent(DrawingEvent.OffsetSelectedElement(2f, 1)) // 2 ft to side A
        val walls = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>()
        assertEquals(2, walls.size)
        val copy = walls.first { it.id != original.id }
        assertEquals(original.size.width, copy.size.width, 0f)
        assertEquals(original.size.height, copy.size.height, 0f)
        assertEquals(original.center.x, copy.center.x, 0.01f)
        assertEquals(original.center.y + 2f * vm.state.value.scale.ratio, copy.center.y, 0.01f)
    }

    @Test
    fun `rotating a box-selected group turns it about the group centre`() {
        val vm = DrawingManager()
        vm.onEvent(DrawingEvent.StartPlacement(Placement.Shell(30f, 60f, "2x6 Wall", "outline")))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(0f, 0f)))
        val ids = vm.state.value.elements.map { it.id }.toSet()
        vm.onEvent(DrawingEvent.SelectElements(ids))
        vm.onEvent(DrawingEvent.RotateSelection(90f))
        val union = vm.state.value.elements.map { it.boundingRect() }.reduce { a, r ->
            Rect(minOf(a.left, r.left), minOf(a.top, r.top), maxOf(a.right, r.right), maxOf(a.bottom, r.bottom))
        }
        val ratio = vm.state.value.scale.ratio
        assertEquals(60f * ratio, union.width, 0.01f)
        assertEquals(30f * ratio, union.height, 0.01f)
        assertTrue(vm.state.value.elements.all { it.rotation == 0f })
    }
}

class PlacementPreviewTest {
    private val inch = 50f / 12f

    @Test
    fun `a room ghost shows its inside and its walls around the pen point`() {
        val state = com.example.sitedraft.ui.canvas.CanvasState()
        val room = Placement.Room("Bedroom", 12f, 12f, "2x4 Wall", "room")
        val rects = FloorPlan.previewRects(room, Offset(200f, 300f), state)
        val t = 4.5f * inch
        assertEquals(2, rects.size)
        val inside = rects[1]
        assertEquals(200f, inside.left, 0.01f)
        assertEquals(300f, inside.top, 0.01f)
        assertEquals(600f, inside.width, 0.01f)
        assertEquals(inside.left - t, rects[0].left, 0.01f)
        assertEquals(inside.width + 2 * t, rects[0].width, 0.01f)
    }

    @Test
    fun `a door ghost sits in the wall it hovers over`() {
        val wall = DrawingElement.Symbol(
            symbolName = "2x4 Wall", category = PlanSymbols.WALLS, center = Offset(500f, 300f),
            size = androidx.compose.ui.geometry.Size(1000f, 4.5f * inch), color = Color.White
        )
        val state = com.example.sitedraft.ui.canvas.CanvasState(elements = listOf(wall))
        val rect = FloorPlan.previewRects(Placement.Symbol("Door 3'-0\"", PlanSymbols.DOORS_WINDOWS), Offset(400f, 302f), state).single()
        assertEquals(300f, rect.bottom, 0.01f) // threshold on the wall centre line
        assertEquals(36f * inch, rect.width, 0.01f)
    }

    @Test
    fun `hovering updates the preview and placing clears it`() {
        val vm = DrawingManager()
        vm.onEvent(DrawingEvent.PreviewPlacementAt(Offset(100f, 100f)))
        assertNull(vm.state.value.placementPreview) // nothing pending
        vm.onEvent(DrawingEvent.SelectSymbol("Toilet", PlanSymbols.FIXTURES))
        vm.onEvent(DrawingEvent.PreviewPlacementAt(Offset(100f, 100f)))
        assertEquals(100f, vm.state.value.placementPreview!!.x, 0.01f)
        vm.onEvent(DrawingEvent.PlaceAt(Offset(100f, 100f)))
        assertNull(vm.state.value.placementPreview)
        assertNull(vm.state.value.pendingPlacement)
    }
}

class RoomMagnetTest {
    private val inch = 50f / 12f
    private val t = 4.5f * inch

    private fun wall(cx: Float, cy: Float, w: Float, h: Float) = DrawingElement.Symbol(
        symbolName = "2x4 Wall", category = PlanSymbols.WALLS, center = Offset(cx, cy),
        size = androidx.compose.ui.geometry.Size(w, h), color = Color.White
    )

    @Test
    fun `a room dropped a few inches from a wall is pulled onto it`() {
        // Existing vertical wall occupying x 600..600+t (an earlier room's right wall).
        val existing = wall(cx = 600f + t / 2f, cy = 300f, w = t, h = 600f)
        // New room's inside-left corner 5" to the right of that wall's face, and 3" below its top.
        val dropped = Offset(600f + t + 5f * inch, 3f * inch)
        val corner = FloorPlan.magnetRoomCorner(dropped, 500f, 500f, listOf(existing), 12f * inch)
        assertEquals(600f + t, corner.x, 0.01f) // inside face now on the wall's face
        assertEquals(3f * inch, corner.y, 0.01f) // nothing to pull to vertically
    }

    @Test
    fun `a room more than a foot away is left alone`() {
        val existing = wall(cx = 600f + t / 2f, cy = 300f, w = t, h = 600f)
        val dropped = Offset(600f + t + 20f * inch, 0f)
        val corner = FloorPlan.magnetRoomCorner(dropped, 500f, 500f, listOf(existing), 12f * inch)
        assertEquals(dropped.x, corner.x, 0.01f)
    }

    @Test
    fun `placing a room roughly beside another shares the wall instead of doubling it`() {
        val vm = DrawingManager()
        val ratio = vm.state.value.scale.ratio
        vm.onEvent(DrawingEvent.StartPlacement(Placement.Room("A", 12f, 12f, "2x4 Wall", "A")))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(0f, 0f)))
        val aWalls = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>()
        assertEquals(4, aWalls.size)
        val aRightFace = 12f * ratio + t
        // Drop room B with its inside-left corner 4" short of A's outside face (would overlap).
        vm.onEvent(DrawingEvent.StartPlacement(Placement.Room("B", 10f, 12f, "2x4 Wall", "B")))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(aRightFace - 4f * inch, 0f)))
        val all = vm.state.value.elements.filterIsInstance<DrawingElement.Symbol>()
        assertEquals(7, all.size) // B added only three walls
        val bWalls = all.drop(4)
        assertTrue(bWalls.none { FloorPlan.bounds(it).let { b -> b.width < b.height && b.left < aRightFace + 1f } })
        assertEquals(aRightFace, bWalls.map { FloorPlan.bounds(it).left }.min(), 0.01f)
    }
}
