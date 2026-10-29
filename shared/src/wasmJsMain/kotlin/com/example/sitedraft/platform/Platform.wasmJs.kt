package com.example.sitedraft.platform

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.sitedraft.cloud.CloudService
import com.example.sitedraft.cloud.RestCloudService
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun encodePng(image: ImageBitmap): ByteArray =
    Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/** In the browser the right mouse button plays the S Pen's side button. */
actual fun PointerEvent.stylusButtonHeld(): Boolean = buttons.isSecondaryPressed

actual fun nowMillis(): Long = jsNow().toLong()

@JsFun("() => Date.now()")
private external fun jsNow(): Double

actual fun randomId(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(36)
    for (i in 0 until 36) {
        sb.append(
            when (i) {
                8, 13, 18, 23 -> '-'
                14 -> '4'
                else -> hex[Random.nextInt(16)]
            }
        )
    }
    return sb.toString()
}

@JsFun("(name, b64, mime) => { const a = document.createElement('a'); a.href = 'data:' + mime + ';base64,' + b64; a.download = name; document.body.appendChild(a); a.click(); a.remove(); }")
private external fun downloadBase64(name: String, b64: String, mime: String)

/** Projects and settings live in the browser's local storage; exports download as files. */
@OptIn(ExperimentalEncodingApi::class)
class WebPlatform : Platform {
    override val name = "web"
    override val storage: ProjectStorage = object : ProjectStorage {
        override fun read(name: String): String? = window.localStorage.getItem("sitedraft/$name")
        override fun write(name: String, text: String) = window.localStorage.setItem("sitedraft/$name", text)
        override fun exists(name: String) = window.localStorage.getItem("sitedraft/$name") != null
    }
    override val prefs: KeyValuePrefs = object : KeyValuePrefs {
        override fun getString(key: String): String? = window.localStorage.getItem("sitedraft.prefs/$key")
        override fun putString(key: String, value: String) = window.localStorage.setItem("sitedraft.prefs/$key", value)
    }

    override fun log(tag: String, message: String) = println("$tag: $message")
    override fun toast(message: String) = println(message)

    override fun exportPage(fileName: String, width: Int, height: Int, density: Float, draw: DrawScope.() -> Unit): String? {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) { draw() }
        val png = encodePng(bitmap)
        val name = fileName.removeSuffix(".pdf") + ".png"
        downloadBase64(name, Base64.encode(png), "image/png")
        return "Downloaded $name"
    }

    override fun exportText(fileName: String, text: String): String? {
        downloadBase64(fileName, Base64.encode(text.encodeToByteArray()), "application/dxf")
        return "Downloaded $fileName"
    }

    override val cloud: CloudService by lazy { RestCloudService(prefs) }

    override fun openUrl(url: String): Boolean {
        // Same tab: a popup from a click handler may be blocked, and Stripe Checkout sends the
        // user back to the app URL when done.
        window.location.href = url
        return true
    }

    override val checkoutReturnUrl: String get() = window.location.href.substringBefore('#').substringBefore('?')
}
