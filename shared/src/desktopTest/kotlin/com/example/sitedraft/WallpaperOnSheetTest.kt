package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PaperSize
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.WallDecorations
import com.example.sitedraft.ui.canvas.WallpaperPatterns
import com.example.sitedraft.ui.canvas.drawSheet
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wallpaper prints (Nov 28, the user-built library card: "shows on sheets and in the exported PDF"): an elevation with a
 * pattern wallpaper, put on a sheet and drawn the way Print and Export PDF draw it, carries the paper's ink.
 */
class WallpaperOnSheetTest {
    @Test
    fun `a wallpapered elevation prints its paper on the sheet`() {
        val inch = 50f / 12f
        val wall = DrawingElement.Symbol(id = "w", symbolName = FloorPlan.EXTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.Black,
            center = Offset(60f * inch, 3f * inch), size = Size(120f * inch, 6f * inch))
        val navy = WallpaperPatterns.colorways.indexOfFirst { it.label == "Navy" }
        val pic = WallpaperPatterns.picture("stripe", navy)!!
        val view = DrawingElement.ElevationView(spec = ElevationSpec(wallId = "w", side = ViewerSide.BELOW),
            origin = Offset(0f, 2000f), size = Size(120f * inch, 96f * inch), color = Color.Black)
        var state = CanvasState(elements = listOf(wall, view), pictures = listOf(pic),
            wallDecor = listOf(WallDecorations.newWallpaper("w", ViewerSide.BELOW, pic, 6f)))
        val sheet0 = SheetLayout(paper = PaperSize.ARCH_D, views = emptyList())
        val sv = Sheets.placed(sheet0, SheetView(kind = SheetViewKind.ELEVATION, elementId = view.id, title = "Wall"), state)
        val sheet = sheet0.copy(views = listOf(sv))
        state = state.copy(sheets = listOf(sheet))

        val dpi = 40f
        val (wIn, hIn) = Sheets.paperIn(sheet)
        val bmp = ImageBitmap((wIn * dpi).toInt(), (hIn * dpi).toInt())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            drawSheet(sheet, state, dpi, "Test", "11/28/2026")
        }
        val ink = WallpaperPatterns.colorways[navy].ink
        val px = bmp.toPixelMap()
        var hits = 0
        for (y in 0 until px.height step 2) for (x in 0 until px.width step 2) {
            val c = px[x, y]
            if (kotlin.math.abs(c.red - ink.red) < 0.08f && kotlin.math.abs(c.green - ink.green) < 0.08f && kotlin.math.abs(c.blue - ink.blue) < 0.08f) hits++
        }
        assertTrue("navy stripe pixels on the sheet: $hits", hits > 50)
    }
}
