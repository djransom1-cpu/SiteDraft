package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.Appliances
import com.example.sitedraft.ui.canvas.CabinetSchedule
import com.example.sitedraft.ui.canvas.CabinetSpec
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FinishSchedule
import com.example.sitedraft.ui.canvas.ItemSchedules
import com.example.sitedraft.ui.canvas.ItemSpec
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoomFinish
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.TradeSchedules
import com.example.sitedraft.ui.canvas.TrimSchedule
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The schedules broken up by trade (Dan, Nov 26): finishes, trim, cabinets and countertops, appliances, plumbing fixtures. */
class TradeSchedulesTest {
    private val inch = 50f / 12f

    private fun wall(id: String, l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(id = id,
        symbolName = "2x4 Wall", category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch))

    private fun sym(id: String, name: String, cat: String, xIn: Float, yIn: Float, w: Float = 30f, d: Float = 24f) =
        DrawingElement.Symbol(id = id, symbolName = name, category = cat, color = Color.White,
            center = Offset(xIn * inch, yIn * inch), size = Size(w * inch, d * inch))

    /** A 12' x 10' kitchen (labelled) with a range, a fridge, a dishwasher, a sink, a toilet and a shower drawn twice. */
    private fun kitchen(): CanvasState {
        val walls = listOf(wall("n", -4.5f, -4.5f, 148.5f, 0f), wall("s", -4.5f, 120f, 148.5f, 124.5f),
            wall("w", -4.5f, 0f, 0f, 120f), wall("e", 144f, 0f, 148.5f, 120f))
        val label = DrawingElement.Text(text = "Kitchen", topLeft = Offset(60f * inch, 60f * inch), fontSize = 20f, color = Color.White)
        val items = listOf(
            sym("rng", Appliances.RANGE_30, PlanSymbols.APPLIANCES, 20f, 13f, 30f, 26f),
            sym("ref", Appliances.FRIDGE_36, PlanSymbols.APPLIANCES, 60f, 15f, 36f, 30f),
            sym("dw", "Dishwasher", PlanSymbols.FIXTURES, 100f, 12f, 24f, 24f),
            sym("snk", "Kitchen Sink", PlanSymbols.FIXTURES, 125f, 12f, 33f, 22f),
            sym("wc", "Toilet", PlanSymbols.FIXTURES, 20f, 100f, 20f, 28f),
            sym("st", "Shower Stall", PlanSymbols.FIXTURES, 110f, 100f, 36f, 36f),
            sym("al", "Alcove Shower", PlanSymbols.SHOWERS, 111f, 100f, 36f, 36f),
            sym("b24", "Base 24\"", PlanSymbols.CABINETS, 16.5f, 40f, 24f, 24f),
        )
        return CanvasState(elements = walls + label + items)
    }

    @Test
    fun `appliances and plumbing fixtures each get their own schedule`() {
        val s = kitchen().copy(itemSpecs = listOf(ItemSpec("rng", make = "GE", model = "JGB735", finish = "Stainless")))
        val appliances = ItemSchedules.rows(s, ItemSchedules.Kind.APPLIANCE)
        // The range, fridge and the dishwasher (drawn from the Fixtures list) are appliances; marked A1, A2...
        assertEquals(listOf("A1", "A2", "A3"), appliances.map { it[0] })
        assertEquals(setOf(Appliances.RANGE_30, Appliances.FRIDGE_36, "Dishwasher"), appliances.map { it[2] }.toSet())
        val range = appliances.single { it[2] == Appliances.RANGE_30 }
        assertEquals("Kitchen", range[1])
        assertEquals("GE JGB735", range[4])
        assertEquals("Stainless", range[5])
        assertTrue(range[3], range[3].startsWith("30\" x 26\""))
        // Plumbing: the sink, the toilet, and one shower (the stall under the alcove shower isn't counted twice).
        val plumbing = ItemSchedules.rows(s, ItemSchedules.Kind.PLUMBING)
        assertEquals(listOf("P1", "P2", "P3"), plumbing.map { it[0] })
        assertEquals(setOf("Kitchen Sink", "Toilet", "Alcove Shower"), plumbing.map { it[2] }.toSet())
        assertNull(ItemSchedules.kindOf(s.elements.filterIsInstance<DrawingElement.Symbol>().single { it.id == "b24" }))
        assertEquals("A1", ItemSchedules.markOf(s.elements.filterIsInstance<DrawingElement.Symbol>().single { it.id == "rng" }, s))
    }

    @Test
    fun `cabinets list by room with their typed lines printed when filled`() {
        val s = kitchen()
        assertEquals(listOf("Kitchen"), CabinetSchedule.rooms(s))
        val bare = CabinetSchedule.rows(s)
        assertEquals(1, bare.size)
        assertEquals("B24", bare[0].cells[2])
        val typed = s.copy(cabinetSpecs = listOf(CabinetSpec("Kitchen", cabinets = "Shaker, painted white", countertop = "Quartz, eased edge, 3 cm")))
        val rows = CabinetSchedule.rows(typed)
        assertEquals(listOf("CABINETS: Shaker, painted white", "COUNTERTOP: Quartz, eased edge, 3 cm"), rows.mapNotNull { it.line })
        assertEquals("Hardware", CabinetSchedule.withField(CabinetSpec("Kitchen"), "hardware", " Hardware ")?.hardware)
    }

    @Test
    fun `trim fills in from the room's elevations and typing overrides it`() {
        val f = RoomFinish(name = "Kitchen", x = 72f * inch, y = 60f * inch)
        val elev = DrawingElement.ElevationView(spec = ElevationSpec(wallId = "n", side = ViewerSide.BELOW, trimOn = true, casingIn = 3.25f, windowTrim = 1, baseIn = 5.25f),
            origin = Offset(0f, 2000f), size = Size(600f, 400f), color = Color.White)
        val s = kitchen().let { it.copy(elements = it.elements + elev, roomFinishes = listOf(f)) }
        assertEquals("5-1/4\" base".replace("-", " "), TrimSchedule.baseFromDrawing(f, s).replace("-", " "))
        assertTrue(TrimSchedule.casingFromDrawing(f, s), TrimSchedule.casingFromDrawing(f, s).contains("mitered"))
        assertTrue(TrimSchedule.windowsFromDrawing(f, s), TrimSchedule.windowsFromDrawing(f, s).contains("stool & apron"))
        val typed = s.copy(roomFinishes = listOf(f.copy(trimBase = "1x6 flat stock", trim = "Stain, Minwax Provincial")))
        val row = TrimSchedule.rows(typed).single()
        assertEquals("1x6 flat stock", row[1])
        assertEquals("Stain, Minwax Provincial", row[6])
    }

    @Test
    fun `the finish schedule prints kitchen and bath lines only when filled`() {
        val f = RoomFinish(name = "Bath", x = 72f * inch, y = 60f * inch, floor = "Tile 12x24")
        val s = kitchen().copy(roomFinishes = listOf(f))
        assertEquals(1, FinishSchedule.tableRows(s).size)
        val filled = s.copy(roomFinishes = listOf(f.copy(showerWalls = "Subway 3x6, white", showerFloor = "Penny round")))
        assertEquals(listOf("SHOWER WALLS: Subway 3x6, white", "SHOWER FLOOR: Penny round"), FinishSchedule.tableRows(filled).mapNotNull { it.line })
        assertTrue(FinishSchedule.tableSizeIn(filled).height > FinishSchedule.tableSizeIn(s).height)
    }

    @Test
    fun `each trade schedule is a table on the A sheets`() {
        val s = kitchen()
        for (kind in TradeSchedules.kinds) {
            val v = SheetView(kind = kind, title = kind.name)
            assertTrue(kind.name, Sheets.isTable(v))
            assertTrue(kind.name, (Sheets.viewRect(v, s)?.width ?: 0f) > 5f)
            assertEquals("A", Sheets.slotOf(v, s).series)
        }
        assertTrue(Sheets.isTable(SheetView(kind = SheetViewKind.FINISH_SCHEDULE, title = "Finish")))
    }
}
