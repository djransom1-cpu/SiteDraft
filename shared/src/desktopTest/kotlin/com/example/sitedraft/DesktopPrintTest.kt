package com.example.sitedraft

import androidx.compose.ui.graphics.Color
import com.example.sitedraft.platform.DesktopPlatform
import com.example.sitedraft.platform.PageSpec
import com.example.sitedraft.platform.sheetPageFormat
import com.example.sitedraft.platform.sheetPrintable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.awt.print.PageFormat
import java.awt.print.Printable
import java.io.File
import java.nio.file.Files

/** Letter landscape at 150 dpi: 1650 x 1275 page px standing for 11 x 8.5 in = 792 x 612 pt. */
class DesktopPrintTest {
    private val letterLandscape = PageSpec(1650, 1275, 150f, 1f)

    @Test
    fun `the exported PDF page is the paper size in points`() {
        val root = Files.createTempDirectory("sitedraft-print").toFile()
        val platform = DesktopPlatform(root)
        val message = platform.exportPage("sheet.pdf", letterLandscape) { drawRect(Color.Red) }
        val file = File(root, "exports/sheet.pdf")
        assertTrue(message ?: "", file.exists())
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertTrue(text.contains("/MediaBox [0 0 792.00 612.00]"))
        assertTrue(text.contains("/Width 1650 /Height 1275"))
        root.deleteRecursively()
    }

    @Test
    fun `the page format is the paper turned to the sheet's orientation with no printer margins`() {
        val format = sheetPageFormat(PageFormat(), letterLandscape)
        assertEquals(PageFormat.LANDSCAPE, format.orientation)
        assertEquals(792.0, format.width, 0.01)
        assertEquals(612.0, format.height, 0.01)
        assertEquals(792.0, format.imageableWidth, 0.01)
        assertEquals(0.0, format.imageableX, 0.01)
        val portrait = sheetPageFormat(PageFormat(), PageSpec(1275, 1650, 150f, 1f))
        assertEquals(PageFormat.PORTRAIT, portrait.orientation)
        assertEquals(612.0, portrait.width, 0.01)
    }

    @Test
    fun `the printable paints one page of the sheet image across the whole sheet`() {
        val image = BufferedImage(1650, 1275, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.color = java.awt.Color.RED
            g.fillRect(0, 0, width, height)
            g.dispose()
        }
        val printable = sheetPrintable(image, letterLandscape)
        val format = sheetPageFormat(PageFormat(), letterLandscape)
        val sheet = BufferedImage(792, 612, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        assertEquals(Printable.PAGE_EXISTS, printable.print(g, format, 0))
        assertEquals(Printable.NO_SUCH_PAGE, printable.print(g, format, 1))
        g.dispose()
        // Corners and centre all red: the 1650 px sheet landed on the 792 pt page edge to edge.
        for ((x, y) in listOf(1 to 1, 790 to 1, 1 to 610, 790 to 610, 396 to 306)) {
            assertEquals("pixel $x,$y", 0xFF0000, sheet.getRGB(x, y) and 0xFFFFFF)
        }
    }
}
