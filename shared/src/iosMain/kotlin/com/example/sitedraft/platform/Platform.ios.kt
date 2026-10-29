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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

// Dispatchers.IO on Kotlin/Native is the extension in kotlinx.coroutines (imported above);
// the member of the same name is internal, which is what the first iOS CI build tripped on.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun encodePng(image: ImageBitmap): ByteArray =
    Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/** Apple Pencil has no side button; nothing to report. */
actual fun PointerEvent.stylusButtonHeld(): Boolean = false

actual fun nowMillis(): Long = (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun randomId(): String = NSUUID().UUIDString

/** Projects live in the app's Documents folder; settings in NSUserDefaults. */
@OptIn(ExperimentalForeignApi::class)
class IosPlatform : Platform {
    override val name = "ios"
    private val documents: String =
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String) ?: "."

    private fun path(name: String) = "$documents/$name"

    override val storage: ProjectStorage = object : ProjectStorage {
        override fun read(name: String): String? =
            NSString.stringWithContentsOfFile(path(name), NSUTF8StringEncoding, null)
        override fun write(name: String, text: String) {
            (text as NSString).writeToFile(path(name), true, NSUTF8StringEncoding, null)
        }
        override fun exists(name: String) = NSFileManager.defaultManager.fileExistsAtPath(path(name))
    }
    override val prefs: KeyValuePrefs = object : KeyValuePrefs {
        override fun getString(key: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(key)
        override fun putString(key: String, value: String) = NSUserDefaults.standardUserDefaults.setObject(value, key)
    }

    override fun log(tag: String, message: String) = println("$tag: $message")
    override fun toast(message: String) = println(message)

    override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? {
        val width = page.widthPx
        val height = page.heightPx
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(page.density), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) { draw() }
        val pixels = bitmap.toPixelMap()
        val rgb = ByteArray(width * height * 3)
        var k = 0
        for (y in 0 until height) for (x in 0 until width) {
            val c = pixels[x, y]
            rgb[k++] = (c.red * 255f).toInt().toByte()
            rgb[k++] = (c.green * 255f).toInt().toByte()
            rgb[k++] = (c.blue * 255f).toInt().toByte()
        }
        val pdf = PdfWriter.singleImagePage(width, height, rgb, flate = false, pageWidthPt = page.widthPt, pageHeightPt = page.heightPt)
        val target = path(fileName)
        pdf.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), pdf.size.toULong()).writeToFile(target, true)
        }
        return "Saved $target"
    }

    /** No print sheet on iOS yet: the PDF is written to Documents, where Files can print it. */
    override fun printPage(jobName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? = exportPage("$jobName.pdf", page, draw)

    override fun exportText(fileName: String, text: String): String? {
        (text as NSString).writeToFile(path(fileName), true, NSUTF8StringEncoding, null)
        return "Saved ${path(fileName)}"
    }
}

actual fun todayDateText(): String {
    val formatter = platform.Foundation.NSDateFormatter()
    formatter.dateFormat = "MM/dd/yyyy"
    return formatter.stringFromDate(platform.Foundation.NSDate())
}
