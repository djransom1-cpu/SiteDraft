package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.AutoDimension
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.isOwnedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 20' x 12' building of 6" walls with a 4-1/2" partition at 10' (two rooms) and a 3' window
 * centred 5' from the left in the top wall. 1' = 50px.
 */
class AutoDimensionTest {
    private val inch = 50f / 12f

    private fun wall(l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(
        symbolName = "2x6 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch)
    )

    private val top = wall(0f, 0f, 240f, 6f)
    private val bottom = wall(0f, 138f, 240f, 144f)
    private val left = wall(0f, 6f, 6f, 138f)
    private val right = wall(234f, 6f, 240f, 138f)
    private val partition = wall(117.75f, 6f, 122.25f, 138f)
    private val window = DrawingElement.Symbol(
        symbolName = "Window 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
        center = Offset(60f * inch, 3f * inch), size = Size(36f * inch, PlanSymbols.WINDOW_DEPTH_IN * inch)
    )
    private val state = CanvasState(elements = listOf(top, bottom, left, right, partition, window))

    /** Value in inches (dimension values are feet at this scale). */
    private fun DrawingElement.Dimension.inches() = value * 12f

    @Test
    fun `the plan gets outside strings and both rooms`() {
        val dims = AutoDimension.plan(state)
        // Top: window string (2) + partition string (2) + overall; bottom: partition string (2) +
        // overall; left and right: overall; two rooms of two each.
        assertEquals(14, dims.size)
        assertTrue(dims.all { it.isOwnedBy(AutoDimension.PLAN_OWNER) })
        val topDims = dims.filter { it.start.y == 0f && it.end.y == 0f }
        assertEquals(listOf(60f, 180f, 120f, 120f, 240f), topDims.map { Math.round(it.inches()).toFloat() })
        // The strings stack outward (upward) from the building, the window string closest.
        assertTrue(topDims.all { it.offset != 0f })
        val firstLine = topDims.first().let { it.start.y + it.offset * if (it.end.x > it.start.x) 1f else -1f }
        assertTrue(firstLine < 0f)
        // Rooms (the last four): inside face to inside face, set outside the room's walls, 9"
        // past the wall's outer face (its top wall is 6" thick: 15" out from the inside face).
        val roomDims = dims.takeLast(4)
        val roomWidths = roomDims.filter { it.start.y == it.end.y }
        val widths = roomWidths.map { it.inches() }.sorted()
        assertEquals(117.75f - 6f, widths[0], 0.01f)
        assertEquals(234f - 122.25f, widths[1], 0.01f)
        assertTrue(roomWidths.all { kotlin.math.abs(it.start.y - 6f * inch) < 0.01f && kotlin.math.abs(it.offset + 15f * inch) < 0.01f })
        val roomDepths = roomDims.filter { it.start.x == it.end.x }
        assertTrue(roomDepths.all { kotlin.math.abs(it.inches() - 132f) < 0.01f })
        // Beside the left wall (6" thick) for the left room, the 4 1/2" partition for the right one.
        val sides = roomDepths.map { kotlin.math.abs(it.offset) / inch }.sorted()
        assertEquals(13.5f, sides[0], 0.01f)
        assertEquals(15f, sides[1], 0.01f)
    }

    @Test
    fun `interior walls can be dimensioned to both faces, and options can be turned off`() {
        val faces = AutoDimension.plan(state, AutoDimension.PlanOptions(rooms = false, interiorToCenter = false))
        val topDims = faces.filter { it.start.y == 0f && it.end.y == 0f }.map { it.inches() }
        assertTrue(topDims.any { kotlin.math.abs(it - 117.75f) < 0.01f })
        assertTrue(topDims.any { kotlin.math.abs(it - 4.5f) < 0.01f })
        assertEquals(4, AutoDimension.plan(state, AutoDimension.PlanOptions(outside = false)).size)
    }

    @Test
    fun `a room's elevations are the ones of its own walls`() {
        fun view(w: DrawingElement.Symbol, side: ViewerSide) = DrawingElement.ElevationView(
            spec = ElevationSpec(wallId = w.id, side = side), origin = Offset(0f, 3000f), size = Size(100f, 100f), color = Color.White)
        val topView = view(top, ViewerSide.BELOW)
        val partitionView = view(partition, ViewerSide.LEFT)
        val rightView = view(right, ViewerSide.LEFT)
        val s = state.copy(elements = state.elements + topView + partitionView + rightView)
        val leftRoom = com.example.sitedraft.ui.canvas.FloorPlan.areaAt(Offset(60f * inch, 70f * inch), s)!!
        assertEquals(setOf(topView.id, partitionView.id), AutoDimension.elevationsOf(leftRoom, s).map { it.id }.toSet())
    }

    @Test
    fun `one room is dimensioned on its own, and again replaces it`() {
        val dims = AutoDimension.room(Offset(60f * inch, 70f * inch), state)
        assertEquals(2, dims.size)
        assertTrue(dims.all { it.ownerId != null && it.ownerId == dims[0].ownerId })
        assertEquals(dims[0].ownerId, AutoDimension.room(Offset(30f * inch, 100f * inch), state)[0].ownerId)
        assertTrue(AutoDimension.room(Offset(-500f, -500f), state).isEmpty())
    }

    @Test
    fun `an elevation gets item widths across the top and heights down the side`() {
        val w = wall(0f, 0f, 240f, 4.5f)
        val face = 4.5f * inch
        fun item(name: String, xIn: Float): DrawingElement.Symbol {
            val (iw, d) = PlanSymbols.footprintInches.getValue(name)
            return DrawingElement.Symbol(symbolName = name, category = PlanSymbols.CABINETS, color = Color.White,
                center = Offset((xIn + iw / 2f) * inch, face + d / 2f * inch), size = Size(iw * inch, d * inch))
        }
        val base = item("Base 36\"", 24f)
        val upper = item("Upper 36\"", 24f)
        val plan = CanvasState(elements = listOf(w, base, upper))
        val spec = ElevationSpec(wallId = w.id, side = ViewerSide.BELOW)
        val view = DrawingElement.ElevationView(spec = spec, origin = Offset(0f, 2000f), size = Elevation.sizePx(w, spec, plan), color = Color.White)
        val dims = AutoDimension.elevation(view, plan.copy(elements = plan.elements + view))
        assertTrue(dims.all { it.isOwnedBy(view.id) })
        val across = dims.filter { it.start.y == it.end.y }.map { Math.round(it.inches()).toFloat() }
        assertEquals(listOf(24f, 36f, 180f, 240f), across)
        val up = dims.filter { it.start.x == it.end.x }.map { Math.round(it.inches()).toFloat() }
        // Floor to counter, counter to uppers, the uppers, uppers to ceiling; then overall.
        assertEquals(listOf(36f, 18f, 30f, 12f, 96f), up)
    }

    @Test
    fun `one tap dimensions the window, the wall or the room under it`() {
        val win = AutoDimension.quick(Offset(60f * inch, 3f * inch), state)
        assertEquals(1, win.size)
        assertEquals(36f, win[0].inches(), 0.01f)
        val w = AutoDimension.quick(Offset(3f * inch, 70f * inch), state)
        assertEquals(1, w.size)
        assertEquals(132f, w[0].inches(), 0.01f)
        val room = AutoDimension.quick(Offset(60f * inch, 70f * inch), state)
        assertEquals(2, room.size)
        assertTrue(AutoDimension.quick(Offset(-500f, -500f), state).isEmpty())
    }

    @Test
    fun `a dimension started at the last one's end, going the same way, continues its line`() {
        val prev = AutoDimension.dim(Offset(0f, 0f), Offset(100f, 0f), Offset(0f, -1f), 30f, state, null)
        val off = AutoDimension.continued(prev, Offset(100f, 0f), Offset(250f, 0f), 2f)
        assertNotNull(off)
        assertEquals(prev.offset, off!!, 0.001f)
        assertNull(AutoDimension.continued(prev, Offset(100f, 0f), Offset(100f, 80f), 2f))
        assertNull(AutoDimension.continued(prev, Offset(140f, 0f), Offset(250f, 0f), 2f))
    }
}
