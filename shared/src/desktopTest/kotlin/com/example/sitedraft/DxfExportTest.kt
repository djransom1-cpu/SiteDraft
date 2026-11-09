package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DimensionFormat
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingScale
import com.example.sitedraft.ui.canvas.DxfExport
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.UnitSystem
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.parseDxf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * DXF export at real size: a small kitchen plan at 1' = 50px (12'-9" x 10', a door in the east
 * wall, a window in the north wall, a base cabinet, a toilet, a dimension, a note and an
 * elevation) must come out in inches, Y up, on named layers, with the symbols in it.
 */
class DxfExportTest {
    private val inch = 50f / 12f
    private val t = 4.5f * inch
    private val w = 153f * inch // 12'-9"
    private val d = 120f * inch // 10'

    private fun wall(cx: Float, cy: Float, ww: Float, hh: Float) = DrawingElement.Symbol(
        symbolName = "2x4 Wall", category = PlanSymbols.WALLS, center = Offset(cx, cy), size = Size(ww, hh), color = Color.White
    )

    private val north = wall(w / 2f, 0f, w, t)
    private val south = wall(w / 2f, d, w, t)
    private val west = wall(0f, d / 2f, t, d - t)
    private val east = wall(w, d / 2f, t, d - t)
    private val walls = listOf(north, south, west, east)

    private fun opening(name: String, tap: Offset, state: CanvasState): DrawingElement.Symbol {
        val placement = checkNotNull(FloorPlan.alignOpening(name, tap, tap, state)) { "the tap is not on a wall" }
        val (fw, fd) = PlanSymbols.footprintInches.getValue(name)
        return DrawingElement.Symbol(
            symbolName = name, category = PlanSymbols.DOORS_WINDOWS, center = placement.center,
            size = placement.size ?: Size(fw * inch, fd * inch), rotation = placement.rotation, color = Color.White
        )
    }

    private fun plan(): CanvasState {
        val base = CanvasState(elements = walls)
        val door = opening("Door 3'-0\"", Offset(w, d / 2f), base)
        val window = opening("Window 4'-0\"", Offset(w / 2f, 0f), base)
        val cabinet = DrawingElement.Symbol(
            symbolName = "Base 36\"", category = PlanSymbols.CABINETS,
            center = Offset(t / 2f + 18f * inch, t / 2f + 12f * inch), size = Size(36f * inch, 24f * inch), color = Color.White
        )
        val toilet = DrawingElement.Symbol(
            symbolName = "Toilet", category = PlanSymbols.FIXTURES,
            center = Offset(w - 30f * inch, d - 20f * inch), size = Size(20f * inch, 28f * inch), rotation = 180f, color = Color.White
        )
        val dimension = DrawingElement.Dimension(
            start = Offset(0f, d + 20f * inch), end = Offset(w, d + 20f * inch), value = w / 50f,
            unit = UnitSystem.IMPERIAL, format = DimensionFormat.ARCHITECTURAL, color = Color.White, offset = 12f * inch
        )
        val note = DrawingElement.Text(text = "KITCHEN", topLeft = Offset(w / 2f, d / 2f), fontSize = 24f, color = Color.White)
        val elevation = DrawingElement.ElevationView(
            spec = ElevationSpec(north.id, ViewerSide.LEFT), origin = Offset(0f, d + 600f),
            size = Size(w, 96f * inch), color = Color.White
        )
        return base.copy(elements = walls + door + window + cabinet + toilet + dimension + note + elevation)
    }

    // ---- a minimal DXF reader for the checks ----

    private data class Entity(val type: String, val pairs: List<Pair<Int, String>>) {
        val layer get() = value(8)
        fun value(code: Int) = pairs.firstOrNull { it.first == code }?.second
        fun num(code: Int) = value(code)!!.toDouble()
        fun nums(code: Int) = pairs.filter { it.first == code }.map { it.second.toDouble() }
    }

    private fun pairs(text: String): List<Pair<Int, String>> {
        val lines = text.split("\r\n")
        return (0 until lines.size - 1 step 2).map { lines[it].trim().toInt() to lines[it + 1] }
    }

    private fun header(text: String): Map<String, List<Pair<Int, String>>> {
        val p = pairs(text)
        val start = p.indexOf(2 to "HEADER")
        val end = p.subList(start, p.size).indexOf(0 to "ENDSEC") + start
        val out = linkedMapOf<String, MutableList<Pair<Int, String>>>()
        var current: MutableList<Pair<Int, String>>? = null
        for (pair in p.subList(start + 1, end)) {
            if (pair.first == 9) current = mutableListOf<Pair<Int, String>>().also { out[pair.second] = it }
            else current?.add(pair)
        }
        return out
    }

    /** Records of one section; a POLYLINE takes its VERTEX coordinates with it. */
    private fun records(text: String, section: String): List<Entity> {
        val p = pairs(text)
        val start = p.indexOf(2 to section)
        val end = p.subList(start, p.size).indexOf(0 to "ENDSEC") + start
        val out = mutableListOf<Entity>()
        var type: String? = null
        var body = mutableListOf<Pair<Int, String>>()
        var seenVertex = false
        fun flush() { type?.let { out += Entity(it, body) } }
        for (pair in p.subList(start + 1, end)) {
            if (pair.first == 0 && pair.second == "VERTEX" && type == "POLYLINE") { seenVertex = true; continue }
            if (pair.first == 0 && pair.second == "SEQEND") continue
            // The POLYLINE's own 10/20/30 is a dummy point; its shape is in the vertices.
            if (type == "POLYLINE" && !seenVertex && pair.first in listOf(10, 20, 30)) continue
            if (pair.first == 0) { flush(); type = pair.second; body = mutableListOf(); seenVertex = false } else body += pair
        }
        flush()
        return out
    }

    private fun entities(text: String) = records(text, "ENTITIES")

    private fun near(expected: Double, actual: Double, tol: Double = 0.01) =
        assertTrue("expected $expected, was $actual", abs(expected - actual) <= tol)

    // ---- tests ----

    @Test
    fun `header says R12 and inches for an imperial project`() {
        val h = header(DxfExport.export(plan()))
        assertEquals("AC1009", h.getValue("\$ACADVER").single().second)
        assertEquals("1", h.getValue("\$INSUNITS").single().second)
        assertEquals("4", h.getValue("\$LUNITS").single().second)
    }

    @Test
    fun `lines come out in inches with Y flipped`() {
        // 10' across the page, then 5' straight down the screen.
        val state = CanvasState(elements = listOf(
            DrawingElement.Line(start = Offset(0f, 0f), end = Offset(500f, 0f), color = Color.White, strokeWidth = 2f),
            DrawingElement.Line(start = Offset(500f, 0f), end = Offset(500f, 250f), color = Color.White, strokeWidth = 2f)
        ))
        val lines = entities(DxfExport.export(state)).filter { it.type == "LINE" }
        assertEquals(2, lines.size)
        near(120.0, lines[0].num(11))
        near(0.0, lines[0].num(21))
        near(120.0, lines[1].num(11))
        near(-60.0, lines[1].num(21)) // down the screen is down (negative Y) in CAD
        assertEquals(DxfExport.DRAWING, lines[0].layer)
    }

    @Test
    fun `metric projects export millimetres`() {
        val state = CanvasState(
            unitSystem = UnitSystem.METRIC, scale = DrawingScale(100f, "1 m = 100px"),
            elements = listOf(DrawingElement.Line(start = Offset(0f, 0f), end = Offset(250f, 0f), color = Color.White, strokeWidth = 2f))
        )
        val text = DxfExport.export(state)
        assertEquals("4", header(text).getValue("\$INSUNITS").single().second)
        near(2500.0, entities(text).single { it.type == "LINE" }.num(11))
    }

    @Test
    fun `layers are declared and used`() {
        val text = DxfExport.export(plan())
        val declared = records(text, "TABLES").filter { it.type == "LAYER" }.map { it.value(2) }.toSet()
        val wanted = listOf(
            DxfExport.WALLS, DxfExport.DOORS_WINDOWS, DxfExport.CABINETS, DxfExport.FIXTURES,
            DxfExport.DIMENSIONS, DxfExport.TEXT, DxfExport.ELEVATIONS
        )
        assertTrue("declared: $declared", declared.containsAll(wanted + "0"))
        val used = entities(text).map { it.layer }.toSet()
        assertTrue("used: $used", used.containsAll(wanted))
        assertTrue("every entity's layer is declared", declared.containsAll(used))
    }

    @Test
    fun `symbols are exported at real size`() {
        val all = entities(DxfExport.export(plan()))

        // The south wall: one closed polyline 12'-9" by 4 1/2".
        val southWall = all.filter { it.type == "POLYLINE" && it.layer == DxfExport.WALLS }
            .single { poly -> poly.nums(20).all { abs(it - (-d / inch).toDouble()) <= 2.3 } }
        near(153.0, southWall.nums(10).max() - southWall.nums(10).min())
        near(4.5, southWall.nums(20).max() - southWall.nums(20).min())

        // The door: a 36" swing arc on the doors layer.
        val swing = all.single { it.type == "ARC" }
        assertEquals(DxfExport.DOORS_WINDOWS, swing.layer)
        near(36.0, swing.num(40))

        // The 36" x 24" base cabinet.
        val cabinet = all.filter { it.type == "POLYLINE" && it.layer == DxfExport.CABINETS }
            .maxBy { it.nums(10).max() - it.nums(10).min() }
        near(36.0, cabinet.nums(10).max() - cabinet.nums(10).min())
        near(24.0, cabinet.nums(20).max() - cabinet.nums(20).min())

        assertTrue(all.any { it.layer == DxfExport.FIXTURES && it.type == "POLYLINE" })
        assertTrue(all.any { it.layer == DxfExport.FRAMING })
    }

    @Test
    fun `walls are broken where the door and window sit`() {
        val all = entities(DxfExport.export(plan()))
        val wallLines = all.filter { it.layer == DxfExport.WALLS && it.type == "LINE" }
        assertFalse("the cut walls are written as lines", wallLines.isEmpty())
        // No wall line runs along the east wall (x = 153") across the door's 36" opening, which is
        // centred halfway down: canvas y = d/2, so DXF y = -60".
        val eastX = (w / inch).toDouble()
        val acrossDoor = wallLines.filter { abs(it.num(10) - it.num(11)) < 0.01 && abs(it.num(10) - eastX) < 3.0 }
            .filter { minOf(it.num(20), it.num(21)) < -60.0 && maxOf(it.num(20), it.num(21)) > -60.0 }
        assertTrue("wall lines cross the door: $acrossDoor", acrossDoor.isEmpty())
        // The north wall is broken around the window (x = 76.5", 48" wide).
        val northLines = wallLines.filter { abs(it.num(20) - it.num(21)) < 0.01 && abs(it.num(20)) < 3.0 }
        assertTrue(northLines.isNotEmpty())
        assertTrue(northLines.none { minOf(it.num(10), it.num(11)) < 76.5 && maxOf(it.num(10), it.num(11)) > 76.5 })
    }

    @Test
    fun `dimensions, text and elevations are exported`() {
        val all = entities(DxfExport.export(plan()))
        val texts = all.filter { it.type == "TEXT" }
        assertNotNull(texts.find { it.layer == DxfExport.DIMENSIONS && it.value(1) == "12'-9\"" })
        val note = texts.single { it.layer == DxfExport.TEXT }
        assertEquals("KITCHEN", note.value(1))
        assertTrue(all.count { it.layer == DxfExport.DIMENSIONS && it.type == "LINE" } >= 5)
        assertTrue(all.count { it.layer == DxfExport.ELEVATIONS } >= 4)
    }

    @Test
    fun `importing the export gives back the lines at canvas size, upright`() {
        val state = CanvasState(elements = listOf(
            DrawingElement.Line(start = Offset(10f, 20f), end = Offset(510f, 270f), color = Color.White, strokeWidth = 2f)
        ))
        val back = parseDxf(DxfExport.export(state), state).single() as DrawingElement.Line
        assertEquals(10f, back.start.x, 0.01f)
        assertEquals(20f, back.start.y, 0.01f)
        assertEquals(510f, back.end.x, 0.01f)
        assertEquals(270f, back.end.y, 0.01f)
    }

    @Test
    fun `writes the sample plan for a look in AutoCAD`() {
        val file = File("build/dxf-export/SiteDraft sample plan.dxf")
        file.parentFile.mkdirs()
        file.writeText(DxfExport.export(plan()))
        assertTrue(file.length() > 0)
    }
}
