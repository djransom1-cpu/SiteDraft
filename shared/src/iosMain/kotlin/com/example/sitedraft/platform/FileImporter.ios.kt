package com.example.sitedraft.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap

/** Document picking on iOS needs UIDocumentPickerViewController wiring; until then imports report themselves unavailable. */
@Composable
actual fun rememberFileImporter(): FileImporter {
    val platform = LocalPlatform.current
    return remember {
        object : FileImporter {
            override fun pickImage(onPicked: (ImageBitmap) -> Unit) = platform.toast("Image import is not available on iOS yet.")
            override fun pickPdf(onPicked: (List<ImageBitmap>) -> Unit) = platform.toast("PDF import is not available on iOS yet.")
            override fun pickText(onPicked: (String) -> Unit) = platform.toast("DXF import is not available on iOS yet.")
        }
    }
}
