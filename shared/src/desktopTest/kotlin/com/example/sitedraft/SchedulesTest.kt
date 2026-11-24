package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Schedules
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Door and window schedules read off the plan (Schedules.kt). 1' = 50px. */
class SchedulesTest {
    private val inch = 50f / 12f

    private fun door(id: String, x: Float, y: Float, widthIn: Float = 36f, props: Map<String, Float> = emptyMap()) =
        DrawingElement.Symbol(id = id, symbolName = "Door 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(x * inch, y * inch), size = Size(widthIn * inch, widthIn * inch), properties = props)

    private fun window(id: String, x: Float, y: Float, widthIn: Float = 48f, props: Map<String, Float> = emptyMap()) =
        DrawingElement.Symbol(id = id, symbolName = "Window 4'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(x * inch, y * inch), size = Size(widthIn * inch, 5.5f * inch), properties = props)

    private val opening = DrawingElement.Symbol(id = "o", symbolName = "Opening 4'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
        center = Offset(0f, 0f), size = Size(48f * inch, 5.5f * inch))

    @Test
    fun `marks are given top to bottom then left to right, and stay put`() {
        val s0 = CanvasState(elements = listOf(door("b", 200f, 100f), door("a", 50f, 100f), door("top", 300f, 10f), window("w", 10f, 0f), opening))
        val s = Schedules.assignMarks(s0)
        val byId = s.elements.associateBy { it.id }
        fun m(id: String) = Schedules.mark(byId.getValue(id) as DrawingElement.Symbol)
        assertEquals("D1", m("top"))
        assertEquals("D2", m("a"))
        assertEquals("D3", m("b"))
        assertEquals("W1", m("w"))
        // A cased opening has no door: no mark, not in the schedule.
        assertNull(m("o"))
        // Nothing new: the same state back.
        assertSame(s, Schedules.assignMarks(s))
        // A door added later gets the next number; the others keep theirs even after moving.
        val moved = s.copy(elements = s.elements.map { if (it.id == "a") (it as DrawingElement.Symbol).copy(center = Offset(0f, 0f)) else it } + door("new", 0f, 0f))
        val s2 = Schedules.assignMarks(moved)
        val by2 = s2.elements.associateBy { it.id }
        assertEquals("D2", Schedules.mark(by2.getValue("a") as DrawingElement.Symbol))
        assertEquals("D4", Schedules.mark(by2.getValue("new") as DrawingElement.Symbol))
    }

    @Test
    fun `a door's room is found on its own floor even while the roof layer is active`() {
        fun wall(id: String, cx: Float, cy: Float, w: Float, h: Float) = DrawingElement.Symbol(id = id, symbolName = "2x6 Wall",
            category = PlanSymbols.WALLS, color = Color.White, center = Offset(cx * inch, cy * inch), size = Size(w * inch, h * inch))
        val room = listOf(wall("n", 120f, 2.75f, 240f, 5.5f), wall("s", 120f, 237.25f, 240f, 5.5f),
            wall("w", 2.75f, 120f, 5.5f, 240f), wall("e", 237.25f, 120f, 5.5f, 240f))
        val label = DrawingElement.Text(id = "t", text = "Kitchen", topLeft = Offset(100f * inch, 100f * inch), fontSize = 12f, color = Color.White)
        val s = CanvasState(elements = room + label + door("d", 60f, 60f), activeLayerId = "roof-structural")
        assertEquals("Kitchen", Schedules.rows(s, Schedules.Kind.DOOR).single().room)
        // Jamb width from the wall's studs (Dan, Nov 24): 2x6 takes 6 9/16", 2x4 4 9/16".
        assertEquals(6.5625f, Schedules.studDepthIn("2x6 Wall")!! + 1f + 1f / 16f, 0.001f)
        assertEquals(4.5625f, Schedules.studDepthIn("2x4 Wall")!! + 1f + 1f / 16f, 0.001f)
        assertEquals(null, Schedules.studDepthIn("8\" Block Wall"))
    }

    @Test
    fun `a copied door that came with another's mark is renumbered`() {
        val first = door("first", 0f, 0f, props = mapOf(Schedules.PROP_MARK to 1f))
        val copy = door("copy", 100f, 0f, props = mapOf(Schedules.PROP_MARK to 1f))
        val s = Schedules.assignMarks(CanvasState(elements = listOf(first, copy)))
        assertEquals(listOf("D1", "D2"), s.elements.map { Schedules.mark(it as DrawingElement.Symbol) })
    }

    @Test
    fun `rows carry size, operation, header by Dan's rule and a typical rough opening`() {
        val s = Schedules.assignMarks(CanvasState(elements = listOf(
            door("d", 0f, 0f, props = mapOf(PlanSymbols.PROP_HINGE_RIGHT to 1f)),
            window("w", 100f, 0f, widthIn = 72f, props = mapOf(PlanSymbols.PROP_SILL_IN to 32f, PlanSymbols.PROP_HEIGHT_IN to 48f))
        )))
        val d = Schedules.rows(s, Schedules.Kind.DOOR).single()
        assertEquals("D1", d.mark)
        assertEquals("3'-0\" x 6'-8\"", Schedules.sizeText(d.widthIn, d.heightIn))
        assertEquals("Hinged", d.type)
        assertEquals("Hinge right", d.operation)
        assertEquals("2-ply 2x10", d.header)
        // Dan's rule: a door adds 2" each way (3-0 x 6-8 is 38 x 82); exterior 2 1/2" on the height.
        assertEquals("3'-2\" x 6'-10\"", d.roughOpening)
        val dSym = s.elements.first { it.id == "d" } as DrawingElement.Symbol
        assertEquals(38f to 82.5f, Schedules.ruleRoughOpening(dSym, 36f, 80f, exterior = true))
        // The supplier's numbers, where entered on the unit, win.
        val supplied = dSym.copy(properties = dSym.properties + (Schedules.PROP_RO_W to 37.5f) + (Schedules.PROP_RO_H to 82.25f))
        assertEquals("3'-1 1/2\" x 6'-10 1/4\" (supplier)", Schedules.roughOpeningText(supplied, 36f, 80f, exterior = false))
        val w = Schedules.rows(s, Schedules.Kind.WINDOW).single()
        assertEquals(32f, w.sillIn!!, 0.01f)
        assertEquals("2-ply 2x10", w.header)
        // A window is double hung until it's set otherwise; a casement shows its hinge side.
        assertEquals("Double hung", w.type)
        val casement = s.copy(elements = s.elements.map {
            if (it.id == "w") (it as DrawingElement.Symbol).copy(properties = it.properties + (PlanSymbols.PROP_WINDOW_TYPE to PlanSymbols.WINDOW_CASEMENT.toFloat()) + (PlanSymbols.PROP_HINGE_RIGHT to 1f)) else it
        })
        val cw = Schedules.rows(casement, Schedules.Kind.WINDOW).single()
        assertEquals("Casement", cw.type)
        assertEquals("Hinge right", cw.operation)
        // Windows: 3/4" overall, 3/8" each side.
        assertEquals("6'-0 3/4\" x 4'-0 3/4\"", w.roughOpening)
        // Header rule: width in feet + 2 = depth, 2x10 minimum, LVL over 10'.
        assertEquals("2-ply 2x10", Schedules.header(96f))
        assertEquals("2-ply 2x12", Schedules.header(108f))
        assertEquals("2-ply 2x12", Schedules.header(120f))
        assertEquals("LVL (size for span)", Schedules.header(132f))
    }

    @Test
    fun `a window in a wall running up the page reads its width along the wall, not the wall's thickness`() {
        // Stood on end: 5 1/2" across, 48" along the wall.
        val upright = DrawingElement.Symbol(id = "up", symbolName = "Window 4'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(0f, 0f), size = Size(5.5f * inch, 48f * inch))
        val row = Schedules.rows(Schedules.assignMarks(CanvasState(elements = listOf(upright))), Schedules.Kind.WINDOW).single()
        assertEquals(48f, row.widthIn, 0.01f)
    }

    @Test
    fun `a schedule is a viewport on a sheet, sized by its rows, and adding one turns the marks on`() {
        val s = CanvasState(elements = listOf(door("a", 0f, 0f), door("b", 100f, 0f), door("c", 200f, 0f)))
        val v = SheetView(kind = SheetViewKind.DOOR_SCHEDULE, title = "Door Schedule", xIn = 10f, yIn = 5f)
        val r = Sheets.viewRect(v, s)
        assertNotNull(r)
        assertEquals(Schedules.tableSizeIn(Schedules.Kind.DOOR, s).height, r!!.height, 0.001f)
        assertTrue(Schedules.tableSizeIn(Schedules.Kind.DOOR, s).height > Schedules.tableSizeIn(Schedules.Kind.DOOR, CanvasState()).height)
        // Readable on a 24x36: rows over 3/8" at normal size, and the viewport's own size scales it.
        val normal = Schedules.tableSizeIn(Schedules.Kind.DOOR, CanvasState(), 1f)
        assertTrue(normal.height >= 0.32f * 1.6f + 0.25f * 1.6f - 0.001f)
        assertEquals(normal.width * 1.3f, Schedules.tableSizeIn(Schedules.Kind.DOOR, CanvasState(), 1.3f).width, 0.01f)
        assertEquals(normal.width * 1.3f, Sheets.viewRect(v.copy(tableSize = 1.3f), CanvasState())!!.width, 0.01f)

        // Two schedules dropped on top of each other are laid out apart by Arrange.
        val sheet = SheetLayout(views = listOf(v, SheetView(kind = SheetViewKind.WINDOW_SCHEDULE, title = "Window Schedule", xIn = 10f, yIn = 5f)))
        assertEquals(2, Sheets.overlapping(sheet, s).size)
        val tidy = Sheets.arranged(sheet, s)
        assertTrue(Sheets.overlapping(tidy, s).isEmpty())
        // Marks are given once a schedule is on a sheet (or the marks are switched on), not before.
        assertTrue(!Schedules.inUse(s))
        assertTrue(Schedules.inUse(s.copy(sheets = listOf(SheetLayout(views = listOf(v))))))
        assertTrue(Schedules.inUse(s.copy(showOpeningMarks = true)))
    }
}
