package com.example.sitedraft.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberFileImporter(): FileImporter {
    val platform = LocalPlatform.current
    return remember {
        object : FileImporter {
            private fun choose(title: String, vararg extensions: String): File? {
                val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
                dialog.setFilenameFilter { _, name -> extensions.isEmpty() || extensions.any { name.lowercase().endsWith(it) } }
                dialog.isVisible = true
                val name = dialog.file ?: return null
                return File(dialog.directory, name)
            }

            override fun pickImage(onPicked: (ImageBitmap) -> Unit) {
                val file = choose("Import image", ".png", ".jpg", ".jpeg", ".webp", ".bmp") ?: return
                decodeImage(file.readBytes())?.let(onPicked) ?: platform.toast("Could not read ${file.name}")
            }

            // Every page rendered at 72 DPI, one pixel per PDF point, the same size Android's
            // PdfRenderer gives, so a plan comes in the same size on both. Rendering runs off the
            // UI thread (a big plan set takes a while); the pages are handed back on it.
            override fun pickPdf(onPicked: (List<ImageBitmap>) -> Unit) {
                val file = choose("Import PDF", ".pdf") ?: return
                platform.toast("Loading ${file.name}...")
                Thread {
                    val pages = try {
                        Loader.loadPDF(file).use { doc ->
                            val renderer = PDFRenderer(doc)
                            (0 until doc.numberOfPages).map { i ->
                                renderer.renderImage(i, 1f, ImageType.RGB).toComposeImageBitmap()
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                    javax.swing.SwingUtilities.invokeLater {
                        when {
                            pages == null -> platform.toast("Could not read ${file.name}")
                            pages.isEmpty() -> platform.toast("${file.name} has no pages")
                            else -> onPicked(pages)
                        }
                    }
                }.apply { isDaemon = true }.start()
            }

            override fun pickText(onPicked: (String) -> Unit) {
                val file = choose("Import DXF", ".dxf", ".txt") ?: return
                onPicked(file.readText())
            }
        }
    }
}
