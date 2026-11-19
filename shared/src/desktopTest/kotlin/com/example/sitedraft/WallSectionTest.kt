package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DerivedLayers
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FoundationType
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.PrintScale
import com.example.sitedraft.ui.canvas.Structure
import com.example.sitedraft.ui.canvas.WallSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallSectionTest {
    private val inch = 50f / 12f

    private fun wall(name: String, l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(
        symbolName = name, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch)
    )

    @Test
    fun `anchor bolts 12 inches from the corners and no more than 32 apart`() {
        val b = DerivedLayers.anchorBolts(480f, 12f, 32f)
        assertEquals(12f, b.first(), 0.01f)
        assertEquals(468f, b.last(), 0.01f)
        assertTrue(b.zipWithNext().all { (a, c) -> c - a <= 32.001f })
        // 456" between the end bolts at 32" max: 15 gaps, 16 bolts.
        assertEquals(16, b.size)
        // A short piece still gets a bolt.
        assertEquals(1, DerivedLayers.anchorBolts(18f, 12f, 32f).size)
    }

    @Test
    fun `the foundation plan gets the bolts`() {
        val walls = listOf(
            wall("2x6 Wall", 0f, 0f, 480f, 6.5f), wall("2x6 Wall", 0f, 281.5f, 480f, 288f),
            wall("2x6 Wall", 0f, 6.5f, 6.5f, 281.5f), wall("2x6 Wall", 473.5f, 6.5f, 480f, 281.5f)
        )
        val s = CanvasState(elements = walls)
        val bolts = DerivedLayers.matchLayer(s, "foundation").filter { it.symbolName == Structure.ANCHOR_BOLT }
        // 16 on each 40' wall; the 22'-11" walls: 251" between end bolts, 8 gaps, 9 bolts.
        assertEquals(16 + 16 + 9 + 9, bolts.size)
    }

    @Test
    fun `the section follows the plan and the building inputs`() {
        val two6 = CanvasState(elements = listOf(wall("2x6 Wall", 0f, 0f, 480f, 6.5f)))
        assertEquals(5.5f, WallSection.studDepthIn(two6), 0.01f)
        val two4 = CanvasState(elements = listOf(wall("2x4 Wall", 0f, 0f, 480f, 4.5f)))
        assertEquals(3.5f, WallSection.studDepthIn(two4), 0.01f)
        val notes = WallSection.geometry(two6).notes.map { it.text }
        assertTrue(notes.any { it.startsWith("18\" x 8\" CONC. FOOTING") })
        assertTrue(notes.any { it.startsWith("11 COURSES") })
        assertTrue(notes.any { it.startsWith("2\" RIGID FOAM") })
        assertTrue(notes.any { it.startsWith("1/2\" A.B. 12\" FROM CORNERS, 32\" O.C.") })
        assertTrue(notes.any { it.startsWith("2x10 FLOOR JOISTS @ 16\" O.C.") })
        assertTrue(notes.any { it.startsWith("TREATED 2x6 SILL ON SILL SEAL") })
        assertTrue(notes.any { it == "4\" DRAIN TILE IN STONE" })
        // Change the inputs: the notes change with them.
        val other = two6.copy(building = BuildingSettings(footingWidthIn = 24f, blockCourses = 9, joistDepthIn = 11.25f, foundationFoamIn = 0f, foundation = FoundationType.CRAWL))
        val n2 = WallSection.geometry(other).notes.map { it.text }
        assertTrue(n2.any { it.startsWith("24\" x 8\" CONC. FOOTING") })
        assertTrue(n2.any { it.startsWith("9 COURSES") })
        assertTrue(n2.any { it.startsWith("2x12 FLOOR JOISTS") })
        assertTrue(n2.none { it.contains("FOAM") })
        assertTrue(n2.any { it.startsWith("CRAWL FLOOR") })
        // The footing projects 6" past the outside face (x = 0).
        val footing = WallSection.geometry(two6).parts.first() as com.example.sitedraft.ui.canvas.Shower.Part.Poly
        assertEquals(-6f, footing.points.minOf { it.x }, 0.01f)
        assertEquals(12f, footing.points.maxOf { it.x }, 0.01f)
    }

    @Test
    fun `a section view has a size on the sheet`() {
        val s = CanvasState(elements = listOf(wall("2x6 Wall", 0f, 0f, 480f, 6.5f)))
        val v = SheetView(kind = SheetViewKind.SECTION, title = "Typical Wall Section", scale = PrintScale.THREE_QUARTER)
        assertNotNull(Sheets.viewRect(v, s))
        val r = Sheets.viewRect(v, s)!!
        assertTrue(r.width > 4f && r.width < 14f)
        assertTrue(r.height > 4f && r.height < 14f)
        assertTrue(Sheets.viewElements(v, s).isEmpty())
    }
}
