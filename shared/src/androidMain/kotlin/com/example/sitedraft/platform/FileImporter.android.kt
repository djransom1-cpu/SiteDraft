package com.example.sitedraft.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/** Holds the callback for the file picker that is currently open. */
private class Pending<T> {
    var callback: (T) -> Unit = {}
}

@Composable
actual fun rememberFileImporter(): FileImporter {
    val context = LocalContext.current
    val pendingImage = remember { Pending<ImageBitmap>() }
    val pendingPdf = remember { Pending<List<ImageBitmap>>() }
    val pendingText = remember { Pending<String>() }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val bitmap = uri?.let { loadBitmap(context, it) } ?: return@rememberLauncherForActivityResult
        pendingImage.callback(bitmap.asImageBitmap())
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val pages = uri?.let { loadPdfPages(context, it) }.orEmpty()
        if (pages.isNotEmpty()) pendingPdf.callback(pages.map { it.asImageBitmap() })
    }
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val text = uri?.let { u -> context.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } }
            ?: return@rememberLauncherForActivityResult
        pendingText.callback(text)
    }
    val launchers = rememberUpdatedState(Triple(imageLauncher, pdfLauncher, textLauncher))
    return remember {
        object : FileImporter {
            override fun pickImage(onPicked: (ImageBitmap) -> Unit) {
                pendingImage.callback = onPicked
                launchers.value.first.launch("image/*")
            }
            override fun pickPdf(onPicked: (List<ImageBitmap>) -> Unit) {
                pendingPdf.callback = onPicked
                launchers.value.second.launch("application/pdf")
            }
            override fun pickText(onPicked: (String) -> Unit) {
                pendingText.callback = onPicked
                launchers.value.third.launch("*/*")
            }
        }
    }
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap? = try {
    context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
} catch (_: Exception) {
    null
}

private fun loadPdfPages(context: Context, uri: Uri): List<Bitmap> {
    val bitmaps = mutableListOf<Bitmap>()
    try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val renderer = PdfRenderer(pfd)
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmaps.add(bitmap)
                page.close()
            }
            renderer.close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return bitmaps
}
