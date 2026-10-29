package com.example.sitedraft.web

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.example.sitedraft.platform.LocalPlatform
import com.example.sitedraft.platform.WebPlatform
import com.example.sitedraft.ui.cloud.SiteDraftApp
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.theme.SiteDraftTheme
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        val platform = remember { WebPlatform() }
        val manager = remember { DrawingManager(platform) }
        CompositionLocalProvider(LocalPlatform provides platform) {
            SiteDraftTheme {
                SiteDraftApp(manager = manager)
            }
        }
    }
}
