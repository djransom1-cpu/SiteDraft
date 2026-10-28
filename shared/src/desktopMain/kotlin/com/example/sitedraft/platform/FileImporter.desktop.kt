package com.example.sitedraft.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
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

            override fun pickPdf(onPicked: (List<ImageBitmap>) -> Unit) {
                platform.toast("PDF import is not available on desktop yet; export the PDF page as an image and import that.")
            }

            override fun pickText(onPicked: (String) -> Unit) {
                val file = choose("Import DXF", ".dxf", ".txt") ?: return
                onPicked(file.readText())
            }
        }
    }
}
