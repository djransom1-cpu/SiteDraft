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
import java.awt.Desktop
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

    override fun exportPage(fileName: String, width: Int, height: Int, density: Float, draw: DrawScope.() -> Unit): String? {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) { draw() }
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
        val pdf = PdfWriter.singleImagePage(width, height, zipped.toByteArray(), flate = true)
        val exportDir = File(root, "exports").apply { mkdirs() }
        val file = File(exportDir, fileName)
        file.writeBytes(pdf)
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file) }
        return "Saved ${file.absolutePath}"
    }

    override fun exportText(fileName: String, text: String): String? {
        val exportDir = File(root, "exports").apply { mkdirs() }
        val file = File(exportDir, fileName)
        file.writeText(text)
        return "Saved ${file.absolutePath}"
    }
}
