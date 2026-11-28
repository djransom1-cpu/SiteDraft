package com.example.sitedraft

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.sitedraft.ui.canvas.*
import com.example.sitedraft.ui.components.drawSymbolThumb
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Every library item's icon on one sheet (library-icons.png), and a list of any that draw nothing (Nov 28). */
class LibraryIconsTest {
    private val lists: List<Pair<String, List<String>>> = listOf(
        Structure.FOUNDATION to Structure.foundationLibrary, Structure.FRAMING to Structure.framingLibrary,
        Garages.CATEGORY to Garages.library, Wings.CATEGORY to Wings.library, Porches.CATEGORY to Porches.library,
        Callouts.CATEGORY to Callouts.library, Mep.ELECTRICAL to Mep.electrical, Mep.HVAC to Mep.hvac, Mep.PLUMBING to Mep.plumbing,
        PlanSymbols.FIXTURES to PlanSymbols.fixtures, PlanSymbols.SHOWERS to Shower.library, PlanSymbols.APPLIANCES to Appliances.appliances,
        PlanSymbols.HOODS to Appliances.hoods, PlanSymbols.STAIRS to Stairs.library, PlanSymbols.RAILINGS to Guards.library,
        PlanSymbols.CABINETS to PlanSymbols.cabinets, PlanSymbols.OUTDOOR to PlanSymbols.outdoor, PlanSymbols.CLOSET to PlanSymbols.closet,
        PlanSymbols.FURNITURE to PlanSymbols.furniture, PlanSymbols.DOORS_WINDOWS to PlanSymbols.doorsWindows, PlanSymbols.WALLS to PlanSymbols.walls,
        "Lumber" to listOf("2x4", "2x6", "2x8", "2x10", "2x12", "1x4", "1x6", "1x8"),
        "Plywood" to listOf("Plywood 7/16\"", "Plywood 1/2\"", "Plywood 5/8\"", "Plywood 3/4\""),
        "Structural" to listOf("W-Beam (W12x26)", "W-Beam (W10x49)", "W-Beam (W8x31)", "W-Beam (W6x15)", "Custom W-Beam", "L-Channel", "Column", "Footing"),
        "Welding" to listOf("Fillet Weld", "V-Groove Weld", "Bevel Weld", "Plug/Slot Weld", "Spot Weld", "Weld All Around", "Field Weld"),
        "Site Layout" to listOf("Tree", "Bush", "Light Pole")
    )

    @Test fun drawsEveryIcon() {
        val items = lists.flatMap { (c, l) -> l.map { c to it } }
        val cell = 112; val cols = 12; val rows = (items.size + cols - 1) / cols
        val bmp = ImageBitmap(cols * cell, rows * cell)
        val bg = Color.White; val ink = Color(0xFF1B1B1F)
        val blank = mutableListOf<String>()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            drawRect(bg)
            items.forEachIndexed { i, (c, n) ->
                val x = (i % cols) * cell.toFloat(); val y = (i / cols) * cell.toFloat()
                translate(x + 4, y + 4) {
                    val one = ImageBitmap(cell - 8, cell - 8)
                    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(one), Size(one.width.toFloat(), one.height.toFloat())) {
                        drawRect(bg); drawSymbolThumb(n, c, bg, ink)
                    }
                    val px = one.toPixelMap()
                    var inked = 0
                    for (yy in 0 until one.height step 2) for (xx in 0 until one.width step 2) if (px[xx, yy] != bg) inked++
                    if (inked < 4) blank += "$c / $n"
                    drawImage(one)
                }
            }
        }
        val out = File("build/library-icons.png"); out.parentFile.mkdirs()
        ImageIO.write(bmp.toAwtImage(), "png", out)
        items.forEachIndexed { i, (c, n) -> println("ICON ${i}: $c / $n") }
        println("ICONS ${items.size}, BLANK ${blank.size}: $blank")
    }
}
