package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.sitedraft.ui.canvas.*
import com.example.sitedraft.ui.canvas.RoofPlan.drawRoof
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Benjeman's roof follows its two offset boxes (Nov 28), drawn with gable and with hip ends (build/benjeman-roof-*.png). */
class BenjemanRoofTest {
    private fun load(): CanvasState = Json { ignoreUnknownKeys = true }.decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/benjeman_full.json").readText())

    @Test fun `roof follows the outline`() {
        for ((name, end) in listOf("gable" to RoofEnd.GABLE, "hip" to RoofEnd.HIP)) {
            val base = load()
            val st = base.copy(building = base.building.copy(roof = RoofSettings(turnRidge = true, endA = end, endB = end)))
            val k = st.inchesToPx(1f)
            val wings = Wings.wings(st)
            val fp = RoofPlan.footprint(st)!!
            println("ROOFTEST $name wings=${wings.size} main=(${fp.left / k},${fp.top / k})-(${fp.right / k},${fp.bottom / k}) wingRoofSegs=${wings.sumOf { Wings.roof3d(it, st).size }}")
            assertEquals(1, wings.size)
            val walls = HouseOutline.planWalls(st)
            val all = walls.map { FloorPlan.bounds(it) }
            val l = all.minOf { it.left } - 40 * k; val t = all.minOf { it.top } - 40 * k
            val w = all.maxOf { it.right } + 40 * k - l; val h = all.maxOf { it.bottom } + 40 * k - t
            val px = 900f / maxOf(w, h)
            val bmp = ImageBitmap((w * px).toInt(), (h * px).toInt())
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
                drawRect(Color.White)
                scale(px, px, pivot = Offset.Zero) {
                    translate(-l, -t) {
                        for (r in all) drawRect(Color(0xFFB0B8C0), r.topLeft, r.size)
                        drawRoof(st, Color.Black)
                    }
                }
            }
            ImageIO.write(bmp.toAwtImage(), "png", File("build/benjeman-roof-$name.png"))
        }
    }
}
