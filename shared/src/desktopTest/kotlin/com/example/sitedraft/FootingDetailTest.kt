package com.example.sitedraft

import com.example.sitedraft.ui.canvas.Building
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DetailKit
import com.example.sitedraft.ui.canvas.DrainTile
import com.example.sitedraft.ui.canvas.FootingDetail
import com.example.sitedraft.ui.canvas.FoundationType
import com.example.sitedraft.ui.canvas.FoundationWall
import com.example.sitedraft.ui.canvas.Shower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.sitedraft.ui.canvas.DetailKit.drawDetail
import java.io.File
import javax.imageio.ImageIO

/** The footing detail (Nov 23 2026) and the foundation wall / footing rules behind it. */
class FootingDetailTest {
    private fun notes(s: BuildingSettings) = FootingDetail.geometry(CanvasState(building = s)).notes.map { it.text }

    @Test
    fun `12 inch block is the default wall and the footing is centred under it`() {
        val s = BuildingSettings()
        assertEquals(FoundationWall.BLOCK_12, s.foundationWall)
        assertEquals(11.625f, Building.foundationWallIn(s), 0.001f)
        val (o, i) = Building.footingProjections(s)
        assertEquals(3.1875f, o, 0.001f)
        assertEquals(3.1875f, i, 0.001f)
        assertTrue(Building.footingCodeIssues(s).isEmpty())
    }

    @Test
    fun `the code projection rule flags too little and too much`() {
        // 12" block on a 14" footing: 1 3/16" each side, under the 2" minimum.
        assertTrue(Building.footingCodeIssues(BuildingSettings(footingWidthIn = 14f)).any { it.contains("at least 2") })
        // Pushed all the way out: 12" past the outside on an 8" thick footing, and short inside.
        val far = Building.footingCodeIssues(BuildingSettings(footingWidthIn = 30f, footingOutsideIn = 12f))
        assertTrue(far.any { it.contains("more than") })
        // A slab has no footing projection to check here.
        assertTrue(Building.footingCodeIssues(BuildingSettings(footingWidthIn = 12f, foundation = FoundationType.SLAB)).isEmpty())
    }

    @Test
    fun `block footing detail - two bars top, dowels, cells solid, Form-A-Drain both sides, no keyway`() {
        val n = notes(BuildingSettings())
        assertTrue(n.any { it.startsWith("18\" x 8\" CONCRETE FOOTING, 3 3/16\" PAST WALL EACH SIDE") })
        assertTrue(n.contains("(2) #4 BARS TOP, CONTINUOUS"))
        assertTrue(n.none { it.contains("BOTTOM, CONTINUOUS") })
        assertTrue(n.contains("#4 VERTICAL DOWELS @ 4'-0\" O.C., HOOKED IN FOOTING"))
        assertTrue(n.contains("CELLS WITH DOWELS FILLED SOLID"))
        assertTrue(n.contains("FORM-A-DRAIN BOTH SIDES (FORMS STAY AS DRAIN TILE)"))
        assertTrue(n.contains("12\" BLOCK FOUNDATION WALL"))
        assertTrue(n.none { it.contains("KEYWAY") })
        // Basement: foam on the wall, not on the footing.
        assertTrue(n.contains("2\" RIGID FOAM ON WALL"))
        assertTrue(n.none { it.contains("FOAM ON FOOTING") })
    }

    @Test
    fun `poured wall gets the keyway, bars top and bottom, bigger bars and pipe follow the inputs`() {
        val s = BuildingSettings(foundationWall = FoundationWall.POURED_10, footingBarsBottom = 2, rebarSize = 5,
            drainTile = DrainTile.PERFORATED, dowelSpacingIn = 32f, footingThicknessIn = 10f, footingWidthIn = 20f)
        val n = notes(s)
        assertTrue(n.contains("2x4 KEYWAY"))
        assertTrue(n.contains("(2) #5 BARS TOP, CONTINUOUS"))
        assertTrue(n.contains("(2) #5 BARS BOTTOM, CONTINUOUS"))
        assertTrue(n.contains("#5 VERTICAL DOWELS @ 32\" O.C., HOOKED IN FOOTING"))
        assertTrue(n.contains("4\" PERFORATED DRAIN TILE BOTH SIDES"))
        assertTrue(n.contains("10\" POURED CONCRETE FOUNDATION WALL"))
        assertTrue(n.none { it.contains("CELLS") })
        assertTrue(n.any { it.startsWith("20\" x 10\" CONCRETE FOOTING, 5\" PAST WALL EACH SIDE") })
        // Four bars drawn (each a pair of rings), plus the two drain pipes.
        val rings = FootingDetail.geometry(CanvasState(building = s)).parts.filterIsInstance<Shower.Part.Ring>()
        assertEquals(4 * 2 + 2, rings.size)
    }

    @Test
    fun `slab on grade foams the footing too`() {
        assertTrue(notes(BuildingSettings(foundation = FoundationType.SLAB)).any { it.contains("FOAM ON FOOTING") })
    }

    @Test
    fun `the footing detail is one of the details and fits its notes`() {
        val st = CanvasState()
        val g = DetailKit.geometry(st, DetailKit.Detail.FOOTING)
        assertEquals("Footing Detail", DetailKit.Detail.FOOTING.title)
        assertEquals("S", DetailKit.Detail.FOOTING.series)
        assertTrue(g.extent.width > 0f && g.extent.height > 0f)
        for (n in g.notes) assertTrue(g.extent.contains(n.at))
    }

    /** Pictures of the detail to look at (build/previews), block and poured, at 1" = 1'-0" on 200 dpi paper. */
    @Test
    fun `render footing detail previews`() {
        for ((name, b) in listOf("footing-block.png" to BuildingSettings(),
            "footing-poured.png" to BuildingSettings(foundationWall = FoundationWall.POURED_10, footingBarsBottom = 2, drainTile = DrainTile.PERFORATED,
                footingWidthIn = 20f, footingThicknessIn = 10f))) {
            val g = FootingDetail.geometry(CanvasState(building = b))
            val dpi = 200f
            val w = (g.extent.width / 12f * dpi).toInt() + 40
            val h = (g.extent.height / 12f * dpi).toInt() + 40
            val bitmap = ImageBitmap(w, h)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
                drawRect(Color.White)
                drawDetail(g, Offset(w / 2f, h / 2f), dpi, 12, Color.Black)
            }
            val out = File(File("build/previews").apply { mkdirs() }, name)
            ImageIO.write(bitmap.toAwtImage(), "png", out)
            assertTrue(out.exists())
        }
    }

    @Test
    fun `code footing table - straight from 2015 IRC R403_1(1)`() {
        fun m(b: BuildingSettings, w: Float? = 32f) = com.example.sitedraft.ui.canvas.FootingCode.minimum(b, w)
        val snow = { psf: Float, soil: Float -> com.example.sitedraft.ui.canvas.StructureSpecs(roofSnowPsf = psf, soilBearingPsf = soil) }
        // 1 story plus basement, 20 psf: 18x6 on 1500, 14x6 on 2000.
        assertEquals(18f, m(BuildingSettings(structure = snow(20f, 1500f))).widthIn!!, 0.01f)
        assertEquals(14f, m(BuildingSettings(structure = snow(20f, 2000f))).widthIn!!, 0.01f)
        // 3 story plus basement, 70 psf, 1500: 30x10.
        val three = BuildingSettings(structure = snow(70f, 1500f), upperFloors = listOf(
            com.example.sitedraft.ui.canvas.UpperFloor("2nd", 1310f), com.example.sitedraft.ui.canvas.UpperFloor("3rd", 1420f)))
        assertEquals(30f, m(three).widthIn!!, 0.01f); assertEquals(10f, m(three).thickIn!!, 0.01f)
        // Between rows and columns it interpolates: 40 psf (between 30 and 50), 2000 soil, 1 story basement: 14 and 16 -> 15.
        assertEquals(15f, m(BuildingSettings(structure = snow(40f, 2000f))).widthIn!!, 0.01f)
        // A 36' house adds 4" of width (2" per 2'), a 28' house takes 4" off: 14 -> 18, 14 -> 12 (never under 12).
        assertEquals(18f, m(BuildingSettings(structure = snow(20f, 2000f)), 36f).widthIn!!, 0.01f)
        assertEquals(12f, m(BuildingSettings(structure = snow(20f, 2000f)), 28f).widthIn!!, 0.01f)
        // Off the table.
        assertTrue(m(BuildingSettings(structure = snow(80f, 2000f))).widthIn == null)
        assertTrue(m(BuildingSettings(structure = snow(30f, 1000f))).widthIn == null)
        // Suggested: wide enough for 12" block to stick out 2" each side (11 5/8 + 4 -> 16).
        val sug = com.example.sitedraft.ui.canvas.FootingCode.suggested(BuildingSettings(structure = snow(20f, 2000f)), 32f)!!
        assertEquals(16f, sug.first, 0.01f)
    }

    @Test
    fun `the code edition picks the footing table`() {
        fun m(e: com.example.sitedraft.ui.canvas.CodeEdition) = com.example.sitedraft.ui.canvas.FootingCode.minimum(
            BuildingSettings(codeEdition = e, structure = com.example.sitedraft.ui.canvas.StructureSpecs(roofSnowPsf = 20f, soilBearingPsf = 2000f)), 32f)
        assertEquals(com.example.sitedraft.ui.canvas.CodeEdition.MRC_2015, BuildingSettings().codeEdition)
        // 2015 and 2018 share the table.
        assertEquals(14f, m(com.example.sitedraft.ui.canvas.CodeEdition.IRC_2018).widthIn!!, 0.01f)
        assertTrue(m(com.example.sitedraft.ui.canvas.CodeEdition.MRC_2015).note.startsWith("2015 MRC"))
        // 2021 revised it; not loaded yet, so no number is made up.
        val r = m(com.example.sitedraft.ui.canvas.CodeEdition.IRC_2021)
        assertTrue(r.widthIn == null && r.note.contains("isn't in Site Draft yet"))
    }
}
