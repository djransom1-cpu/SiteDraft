package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.AutoDimension
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DerivedLayers
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.FoundationType
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Structure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 40' x 24' house of 2x6 walls (6 1/2") with a 2x4 partition running up and down at 20' and a
 * bearing 2x4 partition running across at 8' from the top. 1' = 50px. Joists span the 24' way
 * (up and down), so the up-and-down partition runs with them (a ladder) and the cross partition
 * is across them (a beam of its own, unless it sits over the centre beam).
 */
class DerivedLayersTest {
    private val inch = 50f / 12f

    private fun wall(name: String, l: Float, t: Float, r: Float, b: Float, id: String, props: Map<String, Float> = emptyMap()) =
        DrawingElement.Symbol(
            id = id, symbolName = name, category = PlanSymbols.WALLS, color = Color.White,
            center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch), properties = props
        )

    private val ext = "2x6 Wall"
    private val walls = listOf(
        wall(ext, 0f, 0f, 480f, 6.5f, "top"),
        wall(ext, 0f, 281.5f, 480f, 288f, "bottom"),
        wall(ext, 0f, 6.5f, 6.5f, 281.5f, "left"),
        wall(ext, 473.5f, 6.5f, 480f, 281.5f, "right"),
        wall("2x4 Wall", 237.75f, 6.5f, 242.25f, 96f, "upDown"),
        wall("2x4 Wall", 6.5f, 93.75f, 237.75f, 98.25f, "across", mapOf(Structure.BEARING to 1f))
    )
    private val on = CanvasState(elements = walls, building = BuildingSettings(autoLayers = true))

    private fun gen(s: CanvasState, prefix: String) = s.elements.filter { it.id.startsWith(DerivedLayers.GEN_PREFIX + prefix) }

    @Test
    fun `foundation walls and footings go under the exterior walls`() {
        val s = DerivedLayers.sync(on)
        assertEquals(4, gen(s, "fw:").size)
        assertEquals(4, gen(s, "ftg:").size)
        // The top foundation wall's outside face is flush with the top wall's outside face.
        val fw = gen(s, "fw:top").single() as DrawingElement.Symbol
        assertEquals(0f, (fw.center.y - fw.size.height / 2f) / inch, 0.01f)
        assertEquals(7.625f, fw.size.height / inch, 0.01f)
        // Footing 18" wide, centred on it.
        val ftg = gen(s, "ftg:top").single() as DrawingElement.Symbol
        assertEquals(18f, ftg.size.height / inch, 0.01f)
        assertEquals(fw.center.y, ftg.center.y, 0.01f)
    }

    @Test
    fun `joists span the short way with a centre beam on posts and pads`() {
        val s = DerivedLayers.sync(on)
        val bay = gen(s, "bay:").single() as DrawingElement.Symbol
        assertEquals(1, Structure.code(bay, Structure.DIRECTION)) // up and down: the 24' way
        assertEquals(1, Structure.code(bay, Structure.BEAMS))     // 24' span: one beam, 12' spans
        assertEquals(16f, Structure.prop(bay, Structure.SPACING), 0.01f)
        assertEquals("2x10", Structure.joistLabel(bay))
        // The 40' beam on posts at no more than 8': four posts, each on a pad.
        assertEquals(4, gen(s, "post:b0").size)
        assertEquals(4, gen(s, "pad:b0").size)
    }

    @Test
    fun `a wall running with the joists gets a ladder at 16 inch blocks`() {
        val s = DerivedLayers.sync(on)
        val ladder = gen(s, "ladder:upDown").single() as DrawingElement.Symbol
        assertEquals(16f, Structure.prop(ladder, Structure.BLOCKS), 0.01f)
        assertEquals(89.5f, ladder.size.height / inch, 0.01f)   // as long as the wall
        assertEquals(16f, ladder.size.width / inch, 0.01f)      // between two joists 16" o.c.
        // The cross wall is not parallel: no ladder for it.
        assertTrue(gen(s, "ladder:across").isEmpty())
    }

    @Test
    fun `a bearing wall across the joists, off the beam, gets a beam of its own`() {
        val s = DerivedLayers.sync(on)
        // The centre beam is at 12' from the top; the bearing wall is at 8': 4' off it.
        assertEquals(1, gen(s, "bbeam:across").size)
        assertTrue(gen(s, "post:wacross").isNotEmpty())
    }

    @Test
    fun `hand edits stay, deletions stay deleted, and plan changes follow`() {
        val first = DerivedLayers.sync(on)
        // Edit one footing by hand, delete one pad.
        val ftgId = DerivedLayers.GEN_PREFIX + "ftg:top"
        val padId = gen(first, "pad:b0").first().id
        val edited = first.copy(elements = first.elements.mapNotNull {
            when (it.id) {
                ftgId -> (it as DrawingElement.Symbol).copy(size = Size(it.size.width, 24f * inch))
                padId -> null
                else -> it
            }
        })
        // Move the right wall out a foot: the plan changes.
        val moved = edited.copy(elements = edited.elements.map {
            if (it.id == "right") (it as DrawingElement.Symbol).copy(center = it.center + Offset(12f * inch, 0f)) else it
        })
        val s = DerivedLayers.sync(moved)
        assertEquals(24f, (s.elements.single { it.id == ftgId } as DrawingElement.Symbol).size.height / inch, 0.01f)
        assertTrue(s.elements.none { it.id == padId })
        assertTrue(padId in s.suppressedGenIds)
        val fwRight = gen(s, "fw:right").single() as DrawingElement.Symbol
        assertEquals(492f, (fwRight.center.x + fwRight.size.width / 2f) / inch, 0.01f)
        // Rebuild drops the edit and brings the pad back.
        val r = DerivedLayers.rebuild(s)
        assertEquals(18f, (r.elements.single { it.id == ftgId } as DrawingElement.Symbol).size.height / inch, 0.01f)
        assertTrue(r.elements.any { it.id == padId })
    }

    @Test
    fun `syncing twice changes nothing`() {
        val once = DerivedLayers.sync(on)
        assertTrue(DerivedLayers.sync(once) === once)
    }

    @Test
    fun `off, slab, or no exterior walls builds nothing`() {
        val off = on.copy(building = BuildingSettings())
        assertTrue(DerivedLayers.sync(off) === off)
        assertTrue(DerivedLayers.build(on.copy(building = BuildingSettings(autoLayers = true, foundation = FoundationType.SLAB))).isEmpty())
        assertTrue(DerivedLayers.build(on.copy(elements = walls.filter { it.symbolName != ext })).isEmpty())
    }

    @Test
    fun `the floor plan's dimensions ignore the foundation walls`() {
        val plain = AutoDimension.plan(on.copy(building = BuildingSettings()))
        val built = AutoDimension.plan(DerivedLayers.sync(on))
        assertEquals(plain.size, built.size)
    }

    @Test
    fun `a floor opening cuts the joists it crosses`() {
        val bay = DrawingElement.Symbol(
            symbolName = Structure.JOIST_BAY, category = Structure.FRAMING, color = Color.White,
            center = Offset(0f, 0f), size = Size(192f * inch, 144f * inch), properties = Structure.defaults(Structure.JOIST_BAY)
        )
        val whole = Structure.planParts(bay, bay.size.width, bay.size.height, inch)
        val cut = Structure.planParts(bay, bay.size.width, bay.size.height, inch,
            listOf(androidx.compose.ui.geometry.Rect(-18f * inch, -60f * inch, 18f * inch, 60f * inch)))
        // Joists run across; a 36" x 120" opening breaks several of them in two and adds trimmers and headers.
        assertTrue(cut.size > whole.size)
    }
}
