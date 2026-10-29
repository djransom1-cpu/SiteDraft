package com.example.sitedraft.platform

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.indexOfLastPressed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import com.example.sitedraft.cloud.AndroidCloudService
import com.example.sitedraft.cloud.CloudService
import com.example.sitedraft.data.laser.GlmLaserClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun encodePng(image: ImageBitmap): ByteArray {
    val out = ByteArrayOutputStream()
    image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

/**
 * S Pen side button. BUTTON_SECONDARY is what older Samsung firmware reports for it;
 * [StylusButtonState.held] is set by the Activity, which sees the raw Samsung pen actions.
 */
actual fun PointerEvent.stylusButtonHeld(): Boolean =
    // Compose carries MotionEvent.buttonState as PointerButtons: BUTTON_SECONDARY (older Samsung
    // firmware) is "secondary", BUTTON_STYLUS_PRIMARY (0x20) is button index 5.
    buttons.isSecondaryPressed || buttons.indexOfLastPressed() >= 5

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun randomId(): String = UUID.randomUUID().toString()

class AndroidPlatform(private val context: Context) : Platform {
    private val app = context.applicationContext
    override val name = "android"

    override val storage: ProjectStorage? = app.filesDir?.let { dir ->
        object : ProjectStorage {
            override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()
            override fun write(name: String, text: String) { File(dir, name).writeText(text) }
            override fun exists(name: String) = File(dir, name).exists()
        }
    }

    override val prefs: KeyValuePrefs = object : KeyValuePrefs {
        private val sp = app.getSharedPreferences("sitedraft_prefs", Context.MODE_PRIVATE)
        override fun getString(key: String): String? = sp.getString(key, null)
        override fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
    }

    override fun log(tag: String, message: String) { Log.d(tag, message) }

    override fun toast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? {
        val pdfDocument = PdfDocument()
        // PDF pages are in points (1/72 in): the paper size, with the page pixels scaled onto it,
        // so the drawing scale is true when the file is printed from any PDF viewer.
        val info = PdfDocument.PageInfo.Builder(page.widthPt.roundToInt(), page.heightPt.roundToInt(), 1).create()
        val pdfPage = pdfDocument.startPage(info)
        drawPageOnto(pdfPage.canvas, page, 72f / page.dpi, 0f, 0f, draw)
        pdfDocument.finishPage(pdfPage)
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val file = File(dir, fileName)
        FileOutputStream(file).use { pdfDocument.writeTo(it) }
        pdfDocument.close()
        share(file, "application/pdf")
        return "Saved ${file.absolutePath}"
    }

    /**
     * The Activity the print framework needs (PrintManager.print must be called from one); set by
     * MainActivity while it is on screen.
     */
    @Volatile
    var printActivity: Activity? = null

    /**
     * Android's print framework: the system print dialog (any printer, or "Save as PDF"), fed by a
     * one-page PdfDocument of the sheet. The paper asked for is pre-selected; if the user picks
     * another, the sheet is fitted to it uniformly (no longer true scale, but never cropped).
     */
    override fun printPage(jobName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? {
        val activity = printActivity ?: return "Printing needs the app window; try again"
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: return "Printing is not available on this device"
        val adapter = object : PrintDocumentAdapter() {
            private var attributes: PrintAttributes? = null

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                attributes = newAttributes
                if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()
                callback.onLayoutFinished(info, oldAttributes != newAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                val attrs = attributes ?: PrintAttributes.Builder().build()
                val document = PrintedPdfDocument(activity, attrs)
                try {
                    val pdfPage = document.startPage(0)
                    // Fit the sheet into the printable area (points); equals 72/dpi when the paper matches.
                    val area = pdfPage.info.contentRect
                    val scale = minOf(area.width() / page.widthPx.toFloat(), area.height() / page.heightPx.toFloat())
                    val x = area.left + (area.width() - page.widthPx * scale) / 2f
                    val y = area.top + (area.height() - page.heightPx * scale) / 2f
                    drawPageOnto(pdfPage.canvas, page, scale, x, y, draw)
                    document.finishPage(pdfPage)
                    if (cancellationSignal?.isCanceled == true) { callback.onWriteCancelled(); return }
                    FileOutputStream(destination.fileDescriptor).use { document.writeTo(it) }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                } finally {
                    document.close()
                }
            }
        }
        val mediaSize = mediaSizeFor(page)
        val attributes = PrintAttributes.Builder()
            .setMediaSize(mediaSize)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        return try {
            printManager.print(jobName, adapter, attributes)
            null
        } catch (e: Exception) {
            "Could not open the print dialog: ${e.message}"
        }
    }

    /** Draws the page's pixels onto [canvas] scaled by [scale] (points per page px) at ([x], [y]). */
    private fun drawPageOnto(canvas: android.graphics.Canvas, page: PageSpec, scale: Float, x: Float, y: Float, draw: DrawScope.() -> Unit) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        CanvasDrawScope().draw(Density(page.density), LayoutDirection.Ltr, Canvas(canvas), Size(page.widthPx.toFloat(), page.heightPx.toFloat())) { draw() }
        canvas.restore()
    }

    /** The framework's own size where it has one (so the printer's tray matches), else a custom one in mils. */
    private fun mediaSizeFor(page: PageSpec): PrintAttributes.MediaSize {
        val shortIn = minOf(page.widthIn, page.heightIn)
        val longIn = maxOf(page.widthIn, page.heightIn)
        fun near(a: Float, b: Float) = abs(a - b) < 0.05f
        val portrait = when {
            near(shortIn, 8.5f) && near(longIn, 11f) -> PrintAttributes.MediaSize.NA_LETTER
            near(shortIn, 8.5f) && near(longIn, 14f) -> PrintAttributes.MediaSize.NA_LEGAL
            near(shortIn, 11f) && near(longIn, 17f) -> PrintAttributes.MediaSize.NA_TABLOID
            else -> PrintAttributes.MediaSize(
                "sitedraft_${shortIn}x$longIn",
                "${shortIn.toInt()} x ${longIn.toInt()} in",
                (shortIn * 1000f).roundToInt(),
                (longIn * 1000f).roundToInt()
            )
        }
        return if (page.landscape) portrait.asLandscape() else portrait.asPortrait()
    }

    override fun exportText(fileName: String, text: String): String? {
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val file = File(dir, fileName)
        file.writeText(text)
        share(file, "application/dxf")
        return "Saved ${file.absolutePath}"
    }

    private fun share(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Could not open share sheet: ${e.message}")
        }
    }

    override fun createLaserClient(): Any = GlmLaserClient(app)
    override val supportsLaser: Boolean get() = true

    override val cloud: CloudService by lazy { AndroidCloudService(app) }

    override fun openUrl(url: String): Boolean = try {
        app.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        toast("Could not open a browser: ${e.message}")
        false
    }
}

actual fun todayDateText(): String {
    val c = Calendar.getInstance()
    val m = (c.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
    val d = c.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    return "$m/$d/${c.get(Calendar.YEAR)}"
}
