package com.example.sitedraft.platform

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import androidx.compose.ui.graphics.toAwtImage
import java.awt.Desktop
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.print.PageFormat
import java.awt.print.Paper
import java.awt.print.Printable
import java.awt.print.PrinterException
import java.awt.print.PrinterJob
import java.util.Calendar
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import java.util.zip.Deflater

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun encodePng(image: ImageBitmap): ByteArray =
    Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/** On a desktop the right mouse button plays the S Pen's side button. */
actual fun PointerEvent.stylusButtonHeld(): Boolean = buttons.isSecondaryPressed

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun randomId(): String = UUID.randomUUID().toString()

/** Projects live in the user's Documents/SiteDraft folder; settings in the Java preferences store. */
class DesktopPlatform(
    private val root: File = File(System.getProperty("user.home"), "Documents/SiteDraft")
) : Platform {
    override val name = "desktop"
    override val storage: ProjectStorage = object : ProjectStorage {
        override fun read(name: String): String? = File(root, name).takeIf { it.exists() }?.readText()
        override fun write(name: String, text: String) { root.mkdirs(); File(root, name).writeText(text) }
        override fun exists(name: String) = File(root, name).exists()
    }
    override val prefs: KeyValuePrefs = object : KeyValuePrefs {
        private val node = Preferences.userRoot().node("com/example/sitedraft")
        override fun getString(key: String): String? = node.get(key, null)
        override fun putString(key: String, value: String) = node.put(key, value)
    }

    override fun log(tag: String, message: String) = println("$tag: $message")
    override fun toast(message: String) = println(message)

    /** Renders [page] through [draw] to a bitmap: the same pixels the PDF and the printer get. */
    private fun renderPage(page: PageSpec, draw: DrawScope.() -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(page.widthPx, page.heightPx)
        CanvasDrawScope().draw(Density(page.density), LayoutDirection.Ltr, Canvas(bitmap), Size(page.widthPx.toFloat(), page.heightPx.toFloat())) { draw() }
        return bitmap
    }

    private fun writePdf(fileName: String, page: PageSpec, bitmap: ImageBitmap): File {
        val width = page.widthPx
        val height = page.heightPx
        val pixels = bitmap.toPixelMap()
        val rgb = ByteArray(width * height * 3)
        var k = 0
        for (y in 0 until height) for (x in 0 until width) {
            val c = pixels[x, y]
            rgb[k++] = (c.red * 255f).toInt().toByte()
            rgb[k++] = (c.green * 255f).toInt().toByte()
            rgb[k++] = (c.blue * 255f).toInt().toByte()
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(rgb)
        deflater.finish()
        val buffer = ByteArray(64 * 1024)
        val zipped = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            zipped.write(buffer, 0, n)
        }
        deflater.end()
        // The PDF page is the paper size in points, so a 1/4" = 1'-0" sheet opens and prints true.
        val pdf = PdfWriter.singleImagePage(width, height, zipped.toByteArray(), flate = true, pageWidthPt = page.widthPt, pageHeightPt = page.heightPt)
        val exportDir = File(root, "exports").apply { mkdirs() }
        val file = File(exportDir, fileName)
        file.writeBytes(pdf)
        return file
    }

    override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? {
        val file = writePdf(fileName, page, renderPage(page, draw))
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file) }
        return "Saved ${file.absolutePath}"
    }

    /**
     * The system print dialog, then the page bitmap drawn onto the sheet at its paper size (fitted
     * uniformly, so a different paper picked in the dialog shrinks the sheet rather than cropping
     * it). The PDF is written as well, so there is always a file to send on.
     */
    override fun printPage(jobName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? {
        val bitmap = renderPage(page, draw)
        val file = writePdf("$jobName.pdf", page, bitmap)
        val image = bitmap.toAwtImage()
        val job = PrinterJob.getPrinterJob()
        job.jobName = jobName
        val format = sheetPageFormat(job.defaultPage(), page)
        job.setPrintable(sheetPrintable(image, page), format)
        return try {
            if (job.printDialog()) {
                job.print()
                "Sent to ${job.printService?.name ?: "the printer"}; PDF saved ${file.absolutePath}"
            } else {
                "Print cancelled; PDF saved ${file.absolutePath}"
            }
        } catch (e: PrinterException) {
            "Print failed: ${e.message}; PDF saved ${file.absolutePath}"
        }
    }

    override fun exportText(fileName: String, text: String): String? {
        val exportDir = File(root, "exports").apply { mkdirs() }
        val file = File(exportDir, fileName)
        file.writeText(text)
        return "Saved ${file.absolutePath}"
    }
}

actual fun todayDateText(): String {
    val c = Calendar.getInstance()
    val m = (c.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
    val d = c.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    return "$m/$d/${c.get(Calendar.YEAR)}"
}

/**
 * One page: the sheet image fitted uniformly into the format's imageable area, so it is true to
 * scale when the paper matches [page] and merely smaller (never cropped) when it does not.
 */
internal fun sheetPrintable(image: java.awt.Image, page: PageSpec): Printable = Printable { g, pf, index ->
    if (index > 0) return@Printable Printable.NO_SUCH_PAGE
    val g2 = g as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    val scale = minOf(pf.imageableWidth / page.widthPx, pf.imageableHeight / page.heightPx)
    val w = page.widthPx * scale
    val h = page.heightPx * scale
    val x = pf.imageableX + (pf.imageableWidth - w) / 2.0
    val y = pf.imageableY + (pf.imageableHeight - h) / 2.0
    g2.drawImage(image, x.toInt(), y.toInt(), w.toInt(), h.toInt(), null)
    Printable.PAGE_EXISTS
}

/** The page format for [page]: its paper, no printer margins, turned to its orientation. */
internal fun sheetPageFormat(base: PageFormat, page: PageSpec): PageFormat {
    val paper = Paper()
    // Paper is always described portrait; the orientation flag turns it.
    val shortPt = minOf(page.widthPt, page.heightPt).toDouble()
    val longPt = maxOf(page.widthPt, page.heightPt).toDouble()
    paper.setSize(shortPt, longPt)
    paper.setImageableArea(0.0, 0.0, shortPt, longPt)
    base.paper = paper
    base.orientation = if (page.landscape) PageFormat.LANDSCAPE else PageFormat.PORTRAIT
    return base
}
