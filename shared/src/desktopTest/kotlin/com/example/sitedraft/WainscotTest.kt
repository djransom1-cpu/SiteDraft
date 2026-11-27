package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.Wainscot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wainscot on a wall face (Dan, Nov 25). */
class WainscotTest {
    private val inch = 50f / 12f

    private fun wall(props: Map<String, Float> = emptyMap()) = DrawingElement.Symbol(
        id = "w", symbolName = FloorPlan.EXTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset(120f * inch, 3.25f * inch), size = Size(240f * inch, 6.5f * inch), properties = props)

    @Test
    fun `no wainscot unless a style is set, defaults 39 and tile 48`() {
        assertNull(Wainscot.specOf(wall(), ViewerSide.BELOW))
        val bead = Wainscot.specOf(wall(mapOf(Wainscot.styleKey(ViewerSide.BELOW) to 1f)), ViewerSide.BELOW)!!
        assertEquals(39f, bead.heightIn, 0.01f)
        // The other face of the wall has its own setting.
        assertNull(Wainscot.specOf(wall(mapOf(Wainscot.styleKey(ViewerSide.BELOW) to 1f)), ViewerSide.ABOVE))
        val tile = Wainscot.specOf(wall(mapOf(Wainscot.styleKey(ViewerSide.BELOW) to Wainscot.TILE.toFloat())), ViewerSide.BELOW)!!
        assertEquals(48f, tile.heightIn, 0.01f)
    }

    @Test
    fun `stops at doors and runs under low windows`() {
        val s = Wainscot.stretches(240f, 39f,
            doors = listOf(40f to 80f),
            windows = listOf(Wainscot.Window(120f, 160f, 30f), Wainscot.Window(180f, 220f, 44f)))
        // 0-40 | door | 80-120 at 39, under the low window 120-160 at 30, 160-240 at 39 (the high window doesn't cut it).
        assertEquals(listOf(Wainscot.Stretch(0f, 40f, 39f), Wainscot.Stretch(80f, 120f, 39f),
            Wainscot.Stretch(120f, 160f, 30f), Wainscot.Stretch(160f, 240f, 39f)), s)
    }

    @Test
    fun `shadow boxes are sized evenly, longer than tall, with the gaps`() {
        val spec = Wainscot.Spec(Wainscot.SHADOW_BOXES, 39f, 3)
        val marks = Wainscot.marks(spec, listOf(Wainscot.Stretch(0f, 120f, 39f)), 5.5f)
        val outer = marks.filterIsInstance<Wainscot.Mark.Box>().filter { !it.heavy }.chunked(2).map { it[0] }
        assertEquals(3, outer.size)
        val w = (120f - 4 * Wainscot.BOX_GAP_IN) / 3f
        outer.forEach { assertEquals(w, it.x2 - it.x1, 0.01f) }
        assertEquals(Wainscot.BOX_GAP_IN, outer[0].x1, 0.01f)
        // 6" up from the 5-1/2" base and 6" down from the chair rail.
        assertEquals(11.5f, outer[0].h1, 0.01f)
        assertEquals(39f - Wainscot.CHAIR_RAIL_IN - 6f, outer[0].h2, 0.01f)
        // Auto: as many as fit while staying longer than tall.
        val auto = Wainscot.marks(spec.copy(count = 0), listOf(Wainscot.Stretch(0f, 120f, 39f)), 5.5f)
            .filterIsInstance<Wainscot.Mark.Box>().filter { !it.heavy }.chunked(2).map { it[0] }
        auto.forEach { assertTrue(it.x2 - it.x1 >= it.h2 - it.h1) }
    }

    @Test
    fun `panels divide the stretch evenly with 1x6 stiles`() {
        val marks = Wainscot.marks(Wainscot.Spec(Wainscot.RAISED_PANEL, 39f, 4), listOf(Wainscot.Stretch(0f, 100f, 39f)), 0f)
        val panels = marks.filterIsInstance<Wainscot.Mark.Box>().filter { !it.heavy && it.h1 == Wainscot.BOTTOM_RAIL_IN }
        assertEquals(4, panels.size)
        val w = (100f - 5 * Wainscot.STILE_IN) / 4f
        panels.forEach { assertEquals(w, it.x2 - it.x1, 0.01f) }
    }

    @Test
    fun `rooms along a wall are the stretches between the walls that butt in`() {
        val rooms = Wainscot.roomSpans(240f, listOf(100f to 104.5f, 180f to 184.5f))
        assertEquals(listOf(0f to 100f, 104.5f to 180f, 184.5f to 240f), rooms)
        val only2 = Wainscot.Spec(Wainscot.BEADBOARD, 39f, 0, rooms = 0b010)
        assertTrue(only2.inRoom(1))
        assertTrue(!only2.inRoom(0) && !only2.inRoom(2))
        assertTrue(Wainscot.Spec(Wainscot.BEADBOARD, 39f, 0).inRoom(5))
    }

    @Test
    fun `stops behind cabinets and appliances, tile runs behind a vanity`() {
        fun sym(name: String, cat: String) = DrawingElement.Symbol(id = name, symbolName = name, category = cat, color = Color.White,
            center = Offset.Zero, size = Size(100f, 100f))
        assertTrue(Wainscot.stopsBehind(sym("Base 36\"", PlanSymbols.CABINETS), Wainscot.BEADBOARD))
        assertTrue(Wainscot.stopsBehind(sym("Dishwasher", PlanSymbols.FIXTURES), Wainscot.SHADOW_BOXES))
        assertTrue(Wainscot.stopsBehind(sym("Vanity 60\" Double", PlanSymbols.CABINETS), Wainscot.RAISED_PANEL))
        assertTrue(!Wainscot.stopsBehind(sym("Vanity 60\" Double", PlanSymbols.CABINETS), Wainscot.TILE))
        assertTrue(!Wainscot.stopsBehind(sym("Toilet", PlanSymbols.FIXTURES), Wainscot.TILE))
    }

    @Test
    fun `the elevation draws it and it survives a rebuild`() {
        val w = wall(mapOf(Wainscot.styleKey(ViewerSide.BELOW) to Wainscot.BEADBOARD.toFloat()))
        val state = CanvasState(elements = listOf(w))
        val spec = ElevationSpec(wallId = "w", side = ViewerSide.BELOW)
        val plain = Elevation.build(wall(), spec, Offset.Zero, state.copy(elements = listOf(wall())))
        val with = Elevation.build(w, spec, Offset.Zero, state)
        assertTrue(with.size > plain.size + 50)
        // Built again from the plan: the same wainscot.
        assertEquals(with.size, Elevation.build(w, spec, Offset.Zero, state).size)
    }
}
