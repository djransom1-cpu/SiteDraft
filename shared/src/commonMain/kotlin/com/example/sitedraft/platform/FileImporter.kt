package com.example.sitedraft.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Opens the platform's file picker for the three things the app can import. */
interface FileImporter {
    fun pickImage(onPicked: (ImageBitmap) -> Unit)
    /** Every page of a PDF as an image; platforms without a PDF renderer report that via [Platform.toast]. */
    fun pickPdf(onPicked: (List<ImageBitmap>) -> Unit)
    /** A text file (DXF). */
    fun pickText(onPicked: (String) -> Unit)
}

@Composable
expect fun rememberFileImporter(): FileImporter
