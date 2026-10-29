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

/**
 * Prints the sheet from a hidden iframe holding the image at its paper size: the browser's print
 * dialog opens on that document alone (its @page size is the sheet), and no popup blocker is
 * involved, which a new window would be. Returns false if the frame could not be made.
 */
@JsFun("""(b64, wIn, hIn, title) => {
    try {
        const old = document.getElementById('sitedraft-print');
        if (old) old.remove();
        const f = document.createElement('iframe');
        f.id = 'sitedraft-print';
        f.setAttribute('aria-hidden', 'true');
        f.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden;';
        document.body.appendChild(f);
        const d = f.contentDocument;
        d.open();
        d.write('<!DOCTYPE html><html><head><meta charset="utf-8"><title>' + title + '</title>' +
            '<style>@page { size: ' + wIn + 'in ' + hIn + 'in; margin: 0; } html, body { margin: 0; padding: 0; } ' +
            'img { display: block; width: ' + wIn + 'in; height: ' + hIn + 'in; }</style></head><body>' +
            '<img src="data:image/png;base64,' + b64 + '"></body></html>');
        d.close();
        const img = d.querySelector('img');
        const go = () => setTimeout(() => {
            try { f.contentWindow.focus(); f.contentWindow.print(); } catch (e) { console.log('print failed: ' + e); }
            setTimeout(() => f.remove(), 120000);
        }, 50);
        if (img.complete) go(); else img.onload = go;
        return true;
    } catch (e) {
        console.log('print frame failed: ' + e);
        return false;
    }
}""")
private external fun printInFrame(b64: String, wIn: Double, hIn: Double, title: String): Boolean

@JsFun("() => { const d = new Date(); return String(d.getMonth() + 1).padStart(2, '0') + '/' + String(d.getDate()).padStart(2, '0') + '/' + d.getFullYear(); }")
private external fun jsTodayText(): String

actual fun todayDateText(): String = jsTodayText()

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

    private fun renderPng(page: PageSpec, draw: DrawScope.() -> Unit): ByteArray {
        val bitmap = ImageBitmap(page.widthPx, page.heightPx)
        CanvasDrawScope().draw(Density(page.density), LayoutDirection.Ltr, Canvas(bitmap), Size(page.widthPx.toFloat(), page.heightPx.toFloat())) { draw() }
        return encodePng(bitmap)
    }

    override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? {
        val name = fileName.removeSuffix(".pdf") + ".png"
        downloadBase64(name, Base64.encode(renderPng(page, draw)), "image/png")
        return "Downloaded $name"
    }

    /**
     * The browser's print: the sheet goes into a hidden frame as an image sized in paper inches
     * (with a matching @page size) and that frame prints once the image has loaded. If the frame
     * cannot be made the PNG is downloaded instead.
     */
    override fun printPage(jobName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? {
        val png = renderPng(page, draw)
        val title = jobName.replace(Regex("[<>&\"']"), " ")
        val opened = printInFrame(Base64.encode(png), page.widthIn.toDouble(), page.heightIn.toDouble(), title)
        if (opened) return null
        val name = "$jobName.png"
        downloadBase64(name, Base64.encode(png), "image/png")
        return "The browser could not print; downloaded $name instead"
    }

    override fun exportText(fileName: String, text: String): String? {
        downloadBase64(fileName, Base64.encode(text.encodeToByteArray()), "application/dxf")
        return "Downloaded $fileName"
    }
}
