package com.example.sitedraft.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// A hidden <input type=file>; the chosen file comes back base64-encoded through a callback.
@JsFun(
    """(accept, cb) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = accept;
        input.onchange = () => {
            const f = input.files && input.files[0];
            if (!f) return;
            const reader = new FileReader();
            reader.onload = () => {
                const s = reader.result;
                cb(f.name, s.substring(s.indexOf(',') + 1));
            };
            reader.readAsDataURL(f);
        };
        input.click();
    }"""
)
private external fun openFilePicker(accept: String, cb: (String, String) -> Unit)

@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun rememberFileImporter(): FileImporter {
    val platform = LocalPlatform.current
    return remember {
        object : FileImporter {
            override fun pickImage(onPicked: (ImageBitmap) -> Unit) {
                openFilePicker("image/*") { name, b64 ->
                    decodeImage(Base64.decode(b64))?.let(onPicked) ?: platform.toast("Could not read $name")
                }
            }

            override fun pickPdf(onPicked: (List<ImageBitmap>) -> Unit) {
                platform.toast("PDF import is not available in the browser yet; import the page as an image.")
            }

            override fun pickText(onPicked: (String) -> Unit) {
                openFilePicker(".dxf,text/plain") { _, b64 -> onPicked(Base64.decode(b64).decodeToString()) }
            }
        }
    }
}
