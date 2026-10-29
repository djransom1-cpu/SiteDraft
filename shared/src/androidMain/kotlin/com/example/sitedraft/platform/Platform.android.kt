package com.example.sitedraft.platform

import android.content.Context
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

    override fun exportPage(fileName: String, width: Int, height: Int, density: Float, draw: DrawScope.() -> Unit): String? {
        val pdfDocument = PdfDocument()
        val page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(page.canvas), Size(width.toFloat(), height.toFloat())) { draw() }
        pdfDocument.finishPage(page)
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val file = File(dir, fileName)
        FileOutputStream(file).use { pdfDocument.writeTo(it) }
        pdfDocument.close()
        share(file, "application/pdf")
        return "Saved ${file.absolutePath}"
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
