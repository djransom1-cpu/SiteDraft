package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.canvas.BuildingSection
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DetailKit
import com.example.sitedraft.ui.canvas.RoofSettings
import com.example.sitedraft.ui.canvas.Shower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The building section A-A (Nov 25, for the architect demo): footing to ridge from the Job specs. */
class BuildingSectionTest {
    private fun labels(g: DetailKit.Geometry) = g.parts.filterIsInstance<Shower.Part.Label>().map { it.text }

    @Test
    fun `no plan yet - a 28 foot cut with level marks, footing to plate`() {
        val g = BuildingSection.geometry(CanvasState())
        assertEquals(336f, BuildingSection.spanIn(CanvasState()), 0.01f)
        val l = labels(g)
        assertTrue(l.any { it.startsWith("T.O. SUBFLOOR 100'-0") })
        assertTrue(l.any { it.startsWith("T.O. FOOTING") })
        assertTrue(l.any { it.startsWith("T.O. PLATE") })
        assertTrue(g.notes.any { it.text.contains("ROOF NOT SET UP") })
        assertTrue(g.extent.width > 336f && g.extent.height > 100f)
    }

    @Test
    fun `with a roof - ridge mark, trusses, overhang and the centre beam on a 28 foot basement`() {
        val st = CanvasState(building = BuildingSettings(roof = RoofSettings()))
        val g = BuildingSection.geometry(st)
        assertTrue(labels(g).any { it.startsWith("T.O. RIDGE") })
        assertTrue(g.notes.any { it.text.contains("TRUSSES") })
        assertTrue(g.notes.any { it.text.contains("STEEL BEAM") })
        assertTrue(g.notes.any { it.text.contains("FOOTING") })
        // Everything drawn sits inside the extent.
        val pts = g.parts.flatMap { p ->
            when (p) {
                is Shower.Part.Seg -> listOf(p.a, p.b)
                is Shower.Part.Poly -> p.points
                is Shower.Part.Ring -> listOf(p.c)
                is Shower.Part.Label -> listOf(p.c)
            }
        }
        val e = g.extent
        assertTrue(pts.all { it.x >= e.left && it.x <= e.right && it.y >= e.top && it.y <= e.bottom })
        // It is one of the details, on the S sheets.
        assertEquals("S", DetailKit.Detail.BUILDING_SECTION.series)
        assertTrue(DetailKit.newSymbol(st, DetailKit.Detail.BUILDING_SECTION, Offset.Zero).size.width > 0f)
    }
}
