package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.Picture
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.Wainscot
import com.example.sitedraft.ui.canvas.WallDecor
import com.example.sitedraft.ui.canvas.WallDecorations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Art and wallpaper on a wall face (Dan, Nov 25: an interior designer's own wallpaper, a piece of art on the wall). */
class WallDecorTest {
    private val inch = 50f / 12f
    private val wall = DrawingElement.Symbol(id = "w", symbolName = FloorPlan.EXTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset(60f * inch, 3f * inch), size = Size(120f * inch, 6f * inch))
    private val art = Picture(id = "a", name = "Art", bitmap = ImageBitmap(300, 200))
    private val swatch = Picture(id = "s", name = "Swatch", bitmap = ImageBitmap(100, 100))
    private val spec = ElevationSpec(wallId = "w", side = ViewerSide.BELOW)

    @Test
    fun `wallpaper tiles cover the wall, cut short at the edges`() {
        val t = WallDecorations.tiles(0f, 50f, 0f, 30f, 21f, 21f)
        // 3 across (21, 21, 8) x 2 up (21, 9).
        assertEquals(6, t.size)
        val last = t.last()
        assertEquals(8f, last[2], 0.01f)
        assertEquals(9f, last[3], 0.01f)
        assertEquals(8f / 21f, last[4], 0.001f)
        assertEquals(9f / 21f, last[5], 0.001f)
    }

    @Test
    fun `new art keeps the picture's shape, centred at 60 inches`() {
        val d = WallDecorations.newArt("w", ViewerSide.BELOW, art, 60f)
        assertEquals(30f, d.widthIn, 0.01f)
        assertEquals(20f, d.heightIn, 0.01f)
        assertEquals(45f, d.xIn, 0.01f)
        assertEquals(50f, d.bottomIn, 0.01f)
    }

    @Test
    fun `the elevation hangs the art and papers the wall above the wainscot`() {
        val wainscotWall = wall.copy(properties = mapOf(Wainscot.styleKey(ViewerSide.BELOW) to Wainscot.BEADBOARD.toFloat()))
        val state = CanvasState(elements = listOf(wainscotWall), pictures = listOf(art, swatch), wallDecor = listOf(
            WallDecorations.newArt("w", ViewerSide.BELOW, art, 60f),
            WallDecorations.newWallpaper("w", ViewerSide.BELOW, swatch, 21f)))
        val parts = Elevation.build(wainscotWall, spec, Offset(0f, 2000f), state)
        val images = parts.filterIsInstance<DrawingElement.Image>()
        // Wallpaper from 39" to 96" (57" = 3 rows) by 120" (6 columns), plus the art.
        assertEquals(3 * 6 + 1, images.size)
        val lowest = images.filter { it.bitmap === swatch.bitmap }.maxOf { it.topLeft.y + it.size.height }
        assertEquals(2000f - 39f * inch, lowest, 0.5f)
        // The other face of the wall has none of it.
        val other = Elevation.build(wainscotWall, spec.copy(side = ViewerSide.ABOVE), Offset(0f, 2000f), state)
        assertTrue(other.none { it is DrawingElement.Image })
        // Wallpaper replaces wallpaper on a face; art can be several.
        assertEquals(WallDecor.WALLPAPER, state.wallDecor.last().kind)
    }

    @Test
    fun `art added before the wallpaper still hangs on top of it`() {
        val state = CanvasState(elements = listOf(wall), pictures = listOf(art, swatch), wallDecor = listOf(
            WallDecorations.newArt("w", ViewerSide.BELOW, art, 60f),
            WallDecorations.newWallpaper("w", ViewerSide.BELOW, swatch, 21f)))
        val images = Elevation.build(wall, spec, Offset(0f, 2000f), state).filterIsInstance<DrawingElement.Image>()
        val artAt = images.indexOfFirst { it.bitmap === art.bitmap }
        val lastPaper = images.indexOfLast { it.bitmap === swatch.bitmap }
        assertTrue("art drawn after the wallpaper", artAt > lastPaper)
    }
}
